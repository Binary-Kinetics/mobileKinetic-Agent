package com.mobilekinetic.agent.data.tts.providers

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.mobilekinetic.agent.data.tts.TtsProvider
import com.mobilekinetic.agent.data.tts.TtsProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * TtsProvider implementation wrapping Android's built-in TextToSpeech engine.
 *
 * Uses [TextToSpeech] with [UtteranceProgressListener] to provide speech synthesis
 * through the system TTS engine (typically Google TTS). Since Android's TTS API
 * does not expose real-time audio levels, this provider simulates audio levels
 * with a gentle pulsing pattern during speech for visualizer compatibility.
 *
 * The TextToSpeech engine is initialized lazily on first use via [ensureInitialized],
 * which bridges the async init callback to a suspend function. Subsequent calls
 * reuse the existing engine instance.
 *
 * @param application Application context for TextToSpeech initialization
 * @param config Provider configuration (voiceId selects a system voice, rate controls speed)
 */
class AndroidTtsProvider(
    private val application: Application,
    private val config: TtsProviderConfig
) : TtsProvider {

    companion object {
        private const val TAG = "AndroidTtsProvider"

        // Simulated audio level parameters
        private const val LEVEL_PULSE_INTERVAL_MS = 80L
        private const val LEVEL_BASE = 0.35f
        private const val LEVEL_AMPLITUDE = 0.25f
    }

    override val name = "Android Built-in TTS"
    override val providerId = "android_tts"

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var initError: String? = null

    // Coroutine job for the simulated audio level pulse during speech
    private var levelSimulationJob: Job? = null

    /**
     * Ensure the TextToSpeech engine is initialized.
     *
     * Android's [TextToSpeech] constructor takes an [TextToSpeech.OnInitListener]
     * callback that fires asynchronously. This function bridges that callback to
     * a suspend function so callers can await initialization.
     *
     * If already initialized, returns immediately. If a previous init failed,
     * retries by creating a fresh instance.
     *
     * @return null on success, or an error message string on failure
     */
    private suspend fun ensureInitialized(): String? {
        // Already good to go
        if (initialized && tts != null) return null

        // Previous attempt failed and we haven't retried yet
        if (initError != null) {
            // Release stale instance before retrying
            tts?.shutdown()
            tts = null
            initialized = false
            initError = null
        }

        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            try {
                val engine = TextToSpeech(application) { status ->
                    if (resumed) return@TextToSpeech
                    resumed = true

                    if (status == TextToSpeech.SUCCESS) {
                        initialized = true
                        initError = null
                        Log.d(TAG, "TextToSpeech engine initialized successfully")

                        // Apply locale (default to US English)
                        tts?.language = Locale.US

                        // Apply voice from config if specified
                        if (config.voiceId.isNotBlank()) {
                            applyVoice(config.voiceId)
                        }

                        // Apply speech rate from config
                        if (config.rate > 0f) {
                            tts?.setSpeechRate(config.rate)
                        }

                        continuation.resume(null)
                    } else {
                        val errorMsg = "TextToSpeech init failed with status: $status"
                        Log.e(TAG, errorMsg)
                        initialized = false
                        initError = errorMsg
                        continuation.resume(errorMsg)
                    }
                }
                tts = engine

                continuation.invokeOnCancellation {
                    if (!initialized) {
                        Log.d(TAG, "Init cancelled, shutting down engine")
                        engine.shutdown()
                        tts = null
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "TextToSpeech creation failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                initError = errorMsg
                if (!resumed) {
                    resumed = true
                    continuation.resume(errorMsg)
                }
            }
        }
    }

    /**
     * Apply a voice by name from the available system voices.
     *
     * Searches the engine's installed voices for one whose [android.speech.tts.Voice.getName]
     * matches [voiceId]. If no exact match is found, logs a warning and keeps
     * the engine's default voice.
     */
    private fun applyVoice(voiceId: String) {
        val engine = tts ?: return
        try {
            val voices = engine.voices ?: return
            val match = voices.firstOrNull { it.name == voiceId }
            if (match != null) {
                engine.voice = match
                Log.d(TAG, "Applied voice: ${match.name}")
            } else {
                Log.w(TAG, "Voice '$voiceId' not found among ${voices.size} available voices")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply voice '$voiceId': ${e.message}")
        }
    }

    /**
     * Speak the given text using the Android TextToSpeech engine.
     *
     * Initializes the engine if needed, then uses [TextToSpeech.speak] with
     * [TextToSpeech.QUEUE_FLUSH] to begin synthesis. An [UtteranceProgressListener]
     * is registered to detect completion.
     *
     * Since Android TTS does not provide real-time audio amplitude data, a
     * simulated pulsing level (oscillating around 0.35-0.60) is emitted via
     * [onLevel] during playback for visualizer compatibility.
     *
     * @param text The text to synthesize and speak
     * @param onLevel Callback receiving simulated audio levels (0.0-1.0)
     * @param onDone Callback invoked when speech finishes or an error occurs
     */
    override suspend fun speak(text: String, onLevel: (Float) -> Unit, onDone: () -> Unit) {
        val initResult = ensureInitialized()
        if (initResult != null) {
            Log.e(TAG, "Cannot speak: $initResult")
            onDone()
            return
        }

        val engine = tts
        if (engine == null) {
            Log.e(TAG, "TTS engine is null after successful init")
            onDone()
            return
        }

        val utteranceId = UUID.randomUUID().toString()

        suspendCancellableCoroutine { continuation ->
            var resumed = false

            // Register utterance listener before calling speak()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) {
                        Log.d(TAG, "Utterance started: $id")
                        startLevelSimulation(onLevel)
                    }
                }

                override fun onDone(id: String?) {
                    if (id == utteranceId && !resumed) {
                        resumed = true
                        Log.d(TAG, "Utterance completed: $id")
                        stopLevelSimulation(onLevel)
                        onDone()
                        continuation.resume(Unit)
                    }
                }

                @Deprecated("Deprecated in API level 21")
                override fun onError(id: String?) {
                    if (id == utteranceId && !resumed) {
                        resumed = true
                        Log.e(TAG, "Utterance error (legacy): $id")
                        stopLevelSimulation(onLevel)
                        onDone()
                        continuation.resume(Unit)
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && !resumed) {
                        resumed = true
                        Log.e(TAG, "Utterance error: $id, code=$errorCode")
                        stopLevelSimulation(onLevel)
                        onDone()
                        continuation.resume(Unit)
                    }
                }
            })

            // Start speaking
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            if (result != TextToSpeech.SUCCESS) {
                if (!resumed) {
                    resumed = true
                    Log.e(TAG, "TextToSpeech.speak() returned error: $result")
                    stopLevelSimulation(onLevel)
                    onDone()
                    continuation.resume(Unit)
                }
            }

            continuation.invokeOnCancellation {
                Log.d(TAG, "speak() cancelled, stopping TTS")
                engine.stop()
                stopLevelSimulation(onLevel)
                if (!resumed) {
                    resumed = true
                    onDone()
                }
            }
        }
    }

    /**
     * Start the simulated audio level pulse.
     *
     * Launches a coroutine that emits a sinusoidal level pattern at ~12.5 Hz,
     * producing a gentle breathing effect for the visualizer. The pattern
     * oscillates between [LEVEL_BASE] and [LEVEL_BASE] + [LEVEL_AMPLITUDE]
     * with small random perturbations to feel organic.
     */
    private fun startLevelSimulation(onLevel: (Float) -> Unit) {
        stopLevelSimulation(onLevel)
        levelSimulationJob = CoroutineScope(Dispatchers.Default).launch {
            var tick = 0
            while (isActive) {
                ensureActive()
                // Sinusoidal pulse with slight randomness for organic feel
                val phase = (tick * 0.15)  // Controls pulse speed
                val sine = kotlin.math.sin(phase).toFloat()
                val jitter = (Math.random().toFloat() - 0.5f) * 0.08f
                val level = (LEVEL_BASE + sine * LEVEL_AMPLITUDE + jitter).coerceIn(0f, 1f)
                onLevel(level)
                tick++
                delay(LEVEL_PULSE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the simulated audio level pulse and emit a final zero level.
     */
    private fun stopLevelSimulation(onLevel: (Float) -> Unit) {
        levelSimulationJob?.cancel()
        levelSimulationJob = null
        onLevel(0f)
    }

    /**
     * Stop any currently playing speech.
     */
    override fun stop() {
        tts?.stop()
        levelSimulationJob?.cancel()
        levelSimulationJob = null
    }

    /**
     * Release the TextToSpeech engine and all associated resources.
     *
     * After this call, the engine is fully shut down. A new instance will
     * be created on the next [speak] or [testConnection] call.
     */
    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
        initError = null
        Log.d(TAG, "AndroidTtsProvider released")
    }

    /**
     * Test that the Android TTS engine can initialize successfully.
     *
     * Attempts to initialize the engine (if not already initialized) and
     * verifies that at least the default language is available.
     *
     * @return null if the engine is working, or an error message string
     */
    override suspend fun testConnection(): String? {
        val initResult = ensureInitialized()
        if (initResult != null) return initResult

        val engine = tts ?: return "TTS engine is null after init"

        // Verify language availability
        return try {
            val langResult = engine.isLanguageAvailable(Locale.US)
            when (langResult) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                    Log.d(TAG, "Test connection: language available (result=$langResult)")
                    null  // Success
                }
                TextToSpeech.LANG_MISSING_DATA -> "TTS language data not installed"
                TextToSpeech.LANG_NOT_SUPPORTED -> "TTS language not supported"
                else -> "Unexpected language check result: $langResult"
            }
        } catch (e: Exception) {
            "TTS language check failed: ${e.message}"
        }
    }
}
