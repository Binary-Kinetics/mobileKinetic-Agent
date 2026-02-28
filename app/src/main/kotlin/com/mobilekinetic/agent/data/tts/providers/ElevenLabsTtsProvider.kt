package com.mobilekinetic.agent.data.tts.providers

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.util.Log
import com.mobilekinetic.agent.data.tts.TtsProvider
import com.mobilekinetic.agent.data.tts.TtsProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * TtsProvider implementation for the ElevenLabs Text-to-Speech API.
 *
 * Sends text to the ElevenLabs streaming TTS endpoint, receives raw MP3 audio
 * bytes, writes them to a temp file, and plays them back through MediaPlayer.
 * Audio levels are captured via Android's Visualizer API for waveform-based
 * visualization callbacks.
 *
 * @param context Android context for temp file creation and MediaPlayer
 * @param config Provider configuration containing API key, voice ID, model, and rate
 */
class ElevenLabsTtsProvider(
    private val context: Context,
    private val config: TtsProviderConfig
) : TtsProvider {

    companion object {
        private const val TAG = "ElevenLabsTtsProvider"
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel
        private const val DEFAULT_MODEL = "eleven_multilingual_v2"
        private const val DEFAULT_STABILITY = 0.5
        private const val DEFAULT_SIMILARITY_BOOST = 0.75
    }

    override val name = "ElevenLabs"
    override val providerId = "elevenlabs"

    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var tempFile: File? = null

    /**
     * Speak the given text using the ElevenLabs TTS API.
     *
     * Flow:
     * 1. POST text to /v1/text-to-speech/{voice_id}/stream
     * 2. Read response MP3 bytes into a temp file
     * 3. Play the temp file with MediaPlayer
     * 4. Attach a Visualizer for audio level callbacks
     * 5. Wait for playback completion via suspendCancellableCoroutine
     */
    override suspend fun speak(text: String, onLevel: (Float) -> Unit, onDone: () -> Unit) {
        if (config.apiKey.isBlank()) {
            Log.w(TAG, "API key not configured, completing immediately")
            onDone()
            return
        }

        try {
            // Step 1: Fetch audio from ElevenLabs API
            val audioBytes = withContext(Dispatchers.IO) {
                fetchAudio(text)
            }

            if (audioBytes == null || audioBytes.isEmpty()) {
                Log.e(TAG, "Failed to fetch audio from ElevenLabs")
                onDone()
                return
            }

            // Step 2: Write audio to temp file
            val file = withContext(Dispatchers.IO) {
                writeTempFile(audioBytes)
            }

            // Step 3: Play with MediaPlayer and wait for completion
            playAudio(file, onLevel, onDone)
        } catch (e: Exception) {
            Log.e(TAG, "speak() failed: ${e.message}", e)
            onDone()
        }
    }

    /**
     * Fetch audio bytes from the ElevenLabs streaming TTS endpoint.
     *
     * @param text The text to synthesize
     * @return Raw MP3 audio bytes, or null on failure
     */
    private fun fetchAudio(text: String): ByteArray? {
        val voiceId = config.voiceId.ifBlank { DEFAULT_VOICE_ID }
        val modelId = config.model.ifBlank { DEFAULT_MODEL }
        val stability = config.extras["stability"]?.toDoubleOrNull() ?: DEFAULT_STABILITY
        val similarityBoost = config.extras["similarity_boost"]?.toDoubleOrNull()
            ?: DEFAULT_SIMILARITY_BOOST

        val url = URL("$BASE_URL/text-to-speech/$voiceId/stream")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("xi-api-key", config.apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "audio/mpeg")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true

            val body = JSONObject().apply {
                put("text", text)
                put("model_id", modelId)
                put("voice_settings", JSONObject().apply {
                    put("stability", stability)
                    put("similarity_boost", similarityBoost)
                })
            }

            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (_: Exception) { "unreadable" }
                Log.e(TAG, "ElevenLabs API error $responseCode: $errorBody")
                return null
            }

            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAudio() network error: ${e.message}", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Write audio bytes to a temporary MP3 file.
     */
    private fun writeTempFile(audioBytes: ByteArray): File {
        // Clean up previous temp file
        tempFile?.delete()

        val file = File.createTempFile("elevenlabs_tts_", ".mp3", context.cacheDir)
        FileOutputStream(file).use { fos ->
            fos.write(audioBytes)
            fos.flush()
        }
        tempFile = file
        return file
    }

    /**
     * Play the audio file using MediaPlayer with visualization support.
     *
     * Uses suspendCancellableCoroutine to bridge MediaPlayer's callback-based
     * completion listener to a suspend function. Attaches a Visualizer to
     * capture waveform data for audio level callbacks.
     */
    private suspend fun playAudio(file: File, onLevel: (Float) -> Unit, onDone: () -> Unit) {
        suspendCancellableCoroutine { continuation ->
            var resumed = false

            try {
                // Release any previous player
                releaseMediaPlayer()

                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()

                    setOnCompletionListener {
                        if (!resumed) {
                            resumed = true
                            releaseVisualizer()
                            onDone()
                            continuation.resume(Unit)
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        if (!resumed) {
                            resumed = true
                            releaseVisualizer()
                            onDone()
                            continuation.resume(Unit)
                        }
                        true
                    }
                }

                mediaPlayer = player

                // Attach visualizer for audio level callbacks
                attachVisualizer(player.audioSessionId, onLevel)

                player.start()

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Playback cancelled")
                    releaseVisualizer()
                    player.stop()
                    player.release()
                    mediaPlayer = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "playAudio() failed: ${e.message}", e)
                if (!resumed) {
                    resumed = true
                    onDone()
                    continuation.resume(Unit)
                }
            }
        }
    }

    /**
     * Attach an Android Visualizer to capture waveform data and derive
     * a normalized audio level (0.0 - 1.0) for visualization callbacks.
     *
     * Falls back silently if Visualizer cannot be created (e.g., missing
     * RECORD_AUDIO permission).
     */
    private fun attachVisualizer(audioSessionId: Int, onLevel: (Float) -> Unit) {
        try {
            releaseVisualizer()

            val viz = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0] // minimum capture size
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            waveform?.let { data ->
                                // Calculate RMS-based normalized level from waveform
                                // Waveform bytes are unsigned 0-255, with 128 as center
                                var sumSquares = 0.0
                                for (b in data) {
                                    val sample = (b.toInt() and 0xFF) - 128
                                    sumSquares += sample * sample
                                }
                                val rms = Math.sqrt(sumSquares / data.size)
                                // Normalize: max possible RMS is 128 (full-scale square wave)
                                val level = (rms / 128.0).toFloat().coerceIn(0f, 1f)
                                onLevel(level)
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Not used
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2, // capture rate
                    true,  // waveform
                    false  // FFT
                )
                enabled = true
            }
            visualizer = viz
        } catch (e: Exception) {
            // Visualizer may fail without RECORD_AUDIO permission - not critical
            Log.w(TAG, "Could not attach Visualizer: ${e.message}")
        }
    }

    /**
     * Stop any currently playing audio immediately.
     */
    override fun stop() {
        try {
            releaseVisualizer()
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "stop() error: ${e.message}")
        }
    }

    /**
     * Release all resources: MediaPlayer, Visualizer, and temp files.
     */
    override fun release() {
        stop()
        tempFile?.delete()
        tempFile = null
    }

    /**
     * Test connectivity to the ElevenLabs API.
     *
     * Hits GET /v1/voices with the configured API key. Returns null on success
     * or an error message string on failure.
     */
    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext "API key is not configured"
        }

        try {
            val url = URL("$BASE_URL/voices")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("xi-api-key", config.apiKey)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    null // Success
                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    "Invalid API key (HTTP 401)"
                } else {
                    "ElevenLabs API returned HTTP $responseCode"
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            "Connection test failed: ${e.message}"
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }
}
