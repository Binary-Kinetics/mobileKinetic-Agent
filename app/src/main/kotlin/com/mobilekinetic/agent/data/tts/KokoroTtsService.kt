package com.mobilekinetic.agent.data.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobilekinetic.agent.data.DiagnosticLogger
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Serializable
private data class TtsRequest(
    val text: String,
    val voice: String,
    val speed: Float,
    val engine: String = "luxtts"
)

/**
 * TTS Service - Streaming text-to-speech client (LuxTTS backend)
 *
 * Uses WebSocket streaming for low-latency audio playback.
 * Audio begins playing within seconds as first chunk arrives, not after full synthesis.
 * MP3 chunks are fed to ExoPlayer as a dynamic playlist for gapless playback.
 *
 * Server requirements:
 * - LuxTTS FastAPI server with WebSocket /ws/tts endpoint
 * - Returns MP3 audio chunks (48kHz) per sentence
 * - Server applies per-voice defaults (temperature, steps, guidance, volume) from stored JSON sidecar files
 *
 * @param serverUrl Base URL of the LuxTTS server (e.g., "https://your-tts-server:9299")
 * @param cacheDir Application cache directory (retained for interface compatibility)
 * @param context Application context for ExoPlayer initialization
 */
class KokoroTtsService(
    private val serverUrl: String,
    private val cacheDir: File,
    private val context: Context
) {
    companion object {
        private const val TAG = "KokoroTtsService"
        private const val DEFAULT_VOICE = "bf_eleanor"
        private const val DEFAULT_SPEED = 1.0f
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val READ_TIMEOUT_MS = 60000L
    }

    private val isPlaying = AtomicBoolean(false)
    private val isReceivingChunks = AtomicBoolean(false)
    private val completionFired = AtomicBoolean(false)
    private var currentWebSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val chunkCount = AtomicInteger(0)
    private var totalBytesReceived: Long = 0L

    // ExoPlayer - handles decoding + playback + gapless transitions
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onStartCallback: (() -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onErrorCallback: ((Exception) -> Unit)? = null
    private var hasStartedPlaying = false

    // Audio session ID for Visualizer API - exposed for real-time audio analysis
    val audioSessionId: Int get() = player?.audioSessionId ?: 0

    // Total chunks played in current speak() call - exposed for diagnostics
    val totalChunksPlayed: Int get() = chunkCount.get()

    // JSON serializer for TTS requests
    private val json = Json { encodeDefaults = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fire the completion callback exactly once per speak() call.
     * Guards against the race between onPlaybackStateChanged(STATE_ENDED) and onClosed().
     */
    private fun fireCompletionOnce() {
        if (completionFired.compareAndSet(false, true)) {
            DiagnosticLogger.log("TTS_COMPLETION_FIRED", "chunks=${chunkCount.get()}, bytes=$totalBytesReceived")
            isPlaying.set(false)
            onCompleteCallback?.invoke()
        }
    }

    /**
     * ExoPlayer event listener - handles playback state transitions and errors
     */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            DiagnosticLogger.log("EXOPLAYER_STATE", "isPlaying=$isPlayingNow, hasStarted=$hasStartedPlaying")
            if (isPlayingNow && !hasStartedPlaying) {
                hasStartedPlaying = true
                isPlaying.set(true)
                onStartCallback?.invoke()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            DiagnosticLogger.log("EXOPLAYER_PLAYBACK_STATE", "state=$stateName, receivingChunks=${isReceivingChunks.get()}")
            if (playbackState == Player.STATE_ENDED && !isReceivingChunks.get()) {
                // Only fire completion when ALL chunks have been received AND played
                fireCompletionOnce()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.message}", error)
            DiagnosticLogger.exoPlayerError(error.message ?: "unknown", error)
            isPlaying.set(false)
            onErrorCallback?.invoke(Exception(error.message, error))
        }
    }

    /**
     * Get or create the ExoPlayer instance on the main thread.
     * Must be called from mainHandler.post {} or main thread.
     */
    private fun getOrCreatePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .build()
            .also { newPlayer ->
                player = newPlayer
                newPlayer.addListener(playerListener)
                newPlayer.playWhenReady = true
            }
    }

    /**
     * Add an MP3 chunk to ExoPlayer's playlist for gapless playback.
     * Dispatches to main thread since ExoPlayer requires main-thread access.
     */
    private fun addChunkToPlayer(mp3Data: ByteArray) {
        mainHandler.post {
            try {
                val p = getOrCreatePlayer()
                val dataSourceFactory = DataSource.Factory {
                    ByteArrayDataSource(mp3Data)
                }
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri("chunk://${chunkCount.get()}"))
                p.addMediaSource(mediaSource)

                if (p.playbackState == Player.STATE_IDLE) {
                    p.prepare()
                }
                if (p.playbackState == Player.STATE_ENDED) {
                    // More chunks arrived after playback ended - re-prepare to resume
                    p.prepare()
                    p.play()
                }

                Log.d(TAG, "Added chunk ${chunkCount.get()} to ExoPlayer playlist")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding chunk to player: ${e.message}", e)
            }
        }
    }

    /**
     * Check if the TTS server is reachable
     */
    suspend fun isServerAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false

        try {
            val url = URL("$serverUrl/voices")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "Server health check failed: ${e.message}")
            false
        }
    }

    /**
     * Fetch available voices from the TTS server
     *
     * Supports multiple response formats for backward compatibility:
     * - LuxTTS format: {"voices": [{"id": "bf_eleanor", "name": "Eleanor", ...}, ...]}
     * - Legacy object format: {"voices": ["af_kore", "af_bella", ...]}
     * - Plain array format: ["af_kore", "af_bella", ...]
     *
     * @return List of voice IDs, or empty list on failure
     */
    suspend fun fetchVoices(): List<String> = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext emptyList()

        try {
            val url = URL("$serverUrl/voices")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val json = Json { ignoreUnknownKeys = true }
                try {
                    val jsonElement = json.parseToJsonElement(responseBody)
                    when {
                        // Plain JSON array of strings: ["voice1", "voice2", ...]
                        jsonElement is JsonArray -> {
                            jsonElement.mapNotNull { element ->
                                when (element) {
                                    is JsonObject -> element["id"]?.jsonPrimitive?.content
                                    else -> try { element.jsonPrimitive.content } catch (_: Exception) { null }
                                }
                            }
                        }
                        // Object with "voices" key
                        jsonElement is JsonObject && jsonElement.containsKey("voices") -> {
                            jsonElement["voices"]?.let { voicesElement ->
                                (voicesElement as? JsonArray)?.mapNotNull { element ->
                                    when (element) {
                                        // LuxTTS format: array of objects with "id" field
                                        is JsonObject -> element["id"]?.jsonPrimitive?.content
                                        // Legacy format: array of strings
                                        else -> try { element.jsonPrimitive.content } catch (_: Exception) { null }
                                    }
                                }
                            } ?: emptyList()
                        }
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse voices response: ${e.message}")
                    emptyList()
                }
            } else {
                connection.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch voices: ${e.message}")
            emptyList()
        }
    }

    /**
     * Synthesize text to speech with streaming playback
     *
     * Audio begins playing as soon as first chunk arrives from server.
     * Uses WebSocket for low-latency streaming - consistent ~2-3 second latency
     * regardless of text length.
     *
     * Only sends text, voice, speed, and engine to the server. The LuxTTS server
     * applies per-voice defaults (temperature, steps, guidance, volume) from
     * stored JSON sidecar files.
     *
     * @param text The text to synthesize
     * @param voice Voice model name (default: bf_eleanor)
     * @param speed Playback speed multiplier (default: 1.0)
     * @param onStart Callback when audio starts playing
     * @param onComplete Callback when audio finishes
     * @param onError Callback on error
     * @param onAudioLevel Callback with audio level (0.0-1.0) during playback for visualization (retained for interface compatibility)
     */
    fun speak(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = DEFAULT_SPEED,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null,
        onAudioLevel: ((Float) -> Unit)? = null
    ) {
        if (serverUrl.isBlank()) {
            onError?.invoke(IllegalStateException("TTS server URL not configured"))
            return
        }

        // Stop any currently playing audio
        stop()

        DiagnosticLogger.log("TTS_SPEAK_START", "chars=${text.length}, voice=$voice, speed=$speed")
        hasStartedPlaying = false
        completionFired.set(false)
        onStartCallback = onStart
        onCompleteCallback = onComplete
        onErrorCallback = onError
        isReceivingChunks.set(true)
        chunkCount.set(0)
        totalBytesReceived = 0L

        mainHandler.post {
            val p = getOrCreatePlayer()
            p.clearMediaItems()
        }

        try {
            isPlaying.set(true)

            // Connect WebSocket and start receiving chunks
            connectWebSocket(text, voice, speed, onError)

        } catch (e: Exception) {
            Log.e(TAG, "TTS streaming error: ${e.message}", e)
            isPlaying.set(false)
            onError?.invoke(e)
        }
    }

    /**
     * Connect WebSocket and stream audio chunks
     */
    private fun connectWebSocket(
        text: String,
        voice: String,
        speed: Float,
        onError: ((Exception) -> Unit)?
    ) {
        // Convert HTTP(S) URL to WS(S) URL
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws/tts"

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        DiagnosticLogger.wsConnecting(wsUrl)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                DiagnosticLogger.wsConnected()
                // Send TTS request - server applies voice defaults for temperature/steps/guidance/volume
                val request = TtsRequest(
                    text = text,
                    voice = voice,
                    speed = speed
                )
                val requestJson = json.encodeToString(request)
                Log.d(TAG, "Sending TTS request: $requestJson")
                DiagnosticLogger.wsTtsRequestSent(text.length, voice, speed)
                webSocket.send(requestJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message: $text")
                when {
                    text.contains("\"error\"") -> {
                        Log.e(TAG, "Server error: $text")
                        DiagnosticLogger.wsError("Server error: $text")
                        isReceivingChunks.set(false)
                        onError?.invoke(Exception(text))
                        webSocket.close(1000, "Error received")
                    }
                    text.contains("\"warning\"") -> {
                        Log.w(TAG, "Server warning: $text")
                    }
                    text.contains("\"status\"") -> {
                        // LuxTTS sends status JSON between chunks:
                        // {"status": "generating", "chunk_index": 0, "total_chunks": 5}
                        // {"status": "streaming", ...}
                        // {"status": "complete", ...}
                        // All are informational - log and continue
                        Log.d(TAG, "Server status: $text")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Received MP3 chunk - feed directly to ExoPlayer
                val chunk = bytes.toByteArray()
                val currentChunk = chunkCount.incrementAndGet()
                totalBytesReceived += chunk.size
                Log.d(TAG, "Received chunk $currentChunk: ${chunk.size} bytes")

                if (currentChunk == 1) {
                    DiagnosticLogger.wsFirstChunk(chunk.size)
                } else {
                    DiagnosticLogger.wsChunk(currentChunk, chunk.size, totalBytesReceived)
                }

                addChunkToPlayer(chunk)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason, received ${chunkCount.get()} chunks")
                DiagnosticLogger.wsStreamComplete(chunkCount.get())
                currentWebSocket = null
                isReceivingChunks.set(false)
                // Check if player already finished (all chunks played before WS closed)
                mainHandler.post {
                    if (player?.playbackState == Player.STATE_ENDED) {
                        fireCompletionOnce()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                DiagnosticLogger.wsError("WebSocket failure: ${t.message}", t)
                currentWebSocket = null
                isReceivingChunks.set(false)
                isPlaying.set(false)
                onError?.invoke(Exception(t.message))
            }
        }

        currentWebSocket = httpClient.newWebSocket(request, listener)
    }

    /**
     * Stop any currently playing audio
     */
    fun stop() {
        isPlaying.set(false)
        isReceivingChunks.set(false)

        currentWebSocket?.close(1000, "Stopped")
        currentWebSocket = null

        // Cancel any pending coroutine jobs
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }

        mainHandler.post {
            player?.stop()
            player?.clearMediaItems()
        }
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        mainHandler.post {
            player?.release()
            player = null
        }
        httpClient.dispatcher.executorService.shutdown()
    }
}
