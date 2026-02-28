package com.mobilekinetic.agent.data.tts

import android.app.Application
import android.content.Context
import android.util.Log
import com.mobilekinetic.agent.data.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Global TTS Manager - Singleton for app-wide voice synthesis
 *
 * Provides a single point of control for TTS playback with observable
 * audio state that can be consumed by the visualizer from any screen.
 *
 * Uses native Android Visualizer API for real-time audio level capture,
 * perfectly synchronized with actual audio output.
 *
 * Settings are persisted securely using encrypted DataStore.
 */
object TtsManager {
    private const val TAG = "TtsManager"

    // Speech queue for playlist functionality
    private data class SpeechRequest(
        val text: String,
        val voice: String,
        val speed: Float,
        val onComplete: (() -> Unit)?,
        val onError: ((Exception) -> Unit)?
    )
    private val speechQueue = ConcurrentLinkedQueue<SpeechRequest>()

    private var ttsService: KokoroTtsService? = null
    private var cacheDir: java.io.File? = null
    // Safe to hold in singleton - initialized with applicationContext (not Activity context)
    @Suppress("StaticFieldLeak")
    private var settingsRepository: TtsSettingsRepository? = null
    private var appContext: Context? = null

    // Native audio visualizer for real-time FFT data (when permission granted)
    private val visualizerBridge = AudioVisualizerBridge()

    // Observable audio state - consumed by visualizer anywhere in the app
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Fallback audio level from PCM computation (used when native Visualizer unavailable)
    private val _pcmAudioLevel = MutableStateFlow(0f)

    // Audio level - uses native Visualizer when active, falls back to PCM-computed level
    val audioLevel: StateFlow<Float> get() =
        if (visualizerBridge.isActive()) visualizerBridge.audioLevel else _pcmAudioLevel

    // Raw FFT data for advanced visualizations (may be used by external visualizers)
    @Suppress("unused")
    val fftData: StateFlow<FloatArray> = visualizerBridge.fftData

    // Server configuration (observable)
    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // Voice and speed settings (observable)
    private val _voice = MutableStateFlow("bf_eleanor")
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // Available voices from server
    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    private val _isLoadingVoices = MutableStateFlow(false)
    val isLoadingVoices: StateFlow<Boolean> = _isLoadingVoices.asStateFlow()

    // Coroutine scope with SupervisorJob for proper error isolation
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Mutex to prevent concurrent playNextInQueue calls
    private val playbackMutex = Mutex()

    // Track current playback job for proper cancellation
    private var currentPlaybackJob: Job? = null

    // Pluggable TTS provider system
    private var providerFactory: TtsProviderFactory? = null
    private var currentProvider: TtsProvider? = null
    private var currentProviderType: TtsProviderType = TtsProviderType.KOKORO

    /**
     * Initialize with application context (call from Application.onCreate)
     * Loads saved settings from encrypted storage.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        cacheDir = context.cacheDir
        settingsRepository = TtsSettingsRepository(context)
        providerFactory = TtsProviderFactory(context.applicationContext as Application)

        // Load saved settings
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository?.loadSettings()
                if (settings != null && settings.isConfigured) {
                    Log.d(TAG, "Restoring saved TTS settings")
                    _serverUrl.value = settings.serverUrl
                    _voice.value = settings.voice
                    _speed.value = settings.speed
                    _isEnabled.value = settings.enabled

                    // Initialize service if enabled
                    if (settings.enabled) {
                        ttsService = cacheDir?.let { cache ->
                            appContext?.let { ctx -> KokoroTtsService(settings.serverUrl, cache, ctx) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TTS settings", e)
            }
        }
    }

    /**
     * Configure the TTS server URL and persist settings
     */
    fun configure(
        serverUrl: String,
        voice: String = _voice.value,
        speed: Float = _speed.value,
        enabled: Boolean = true
    ) {
        _serverUrl.value = serverUrl
        _voice.value = voice
        _speed.value = speed
        _isEnabled.value = enabled

        // Recreate service with new URL
        ttsService?.release()
        ttsService = if (serverUrl.isNotBlank() && enabled) {
            cacheDir?.let { cache ->
                appContext?.let { ctx -> KokoroTtsService(serverUrl, cache, ctx) }
            }
        } else {
            null
        }

        // Persist settings securely
        scope.launch(Dispatchers.IO) {
            try {
                settingsRepository?.saveSettings(serverUrl, voice, speed, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save TTS settings", e)
            }
        }
    }

    /**
     * Switch to a different TTS provider.
     *
     * Stops any current playback, releases the previous provider,
     * and creates a new one via TtsProviderFactory.
     *
     * When switching to KOKORO, the native Kokoro integration is used
     * (with visualizer support, queue management, etc.). For other
     * provider types, the generic TtsProvider interface is used.
     *
     * @param type The provider type to switch to
     * @param config Provider-specific configuration
     */
    fun switchProvider(type: TtsProviderType, config: TtsProviderConfig) {
        Log.d(TAG, "Switching TTS provider to: ${type.displayName}")

        // Stop current playback
        stop()

        // Release previous non-Kokoro provider if any
        currentProvider?.release()
        currentProvider = null

        currentProviderType = type

        val factory = providerFactory
        if (factory == null) {
            Log.w(TAG, "TtsProviderFactory not initialized, cannot switch provider")
            return
        }

        if (type == TtsProviderType.KOKORO) {
            // For Kokoro, use the native integration path (KokoroTtsService directly)
            // This preserves visualizer support, audio session access, etc.
            ttsService?.release()
            ttsService = if (config.url.isNotBlank()) {
                cacheDir?.let { cache ->
                    appContext?.let { ctx -> KokoroTtsService(config.url, cache, ctx) }
                }
            } else null

            _serverUrl.value = config.url
            if (config.voiceId.isNotBlank()) _voice.value = config.voiceId
            if (config.rate > 0f) _speed.value = config.rate
            _isEnabled.value = true
        } else if (type == TtsProviderType.NONE) {
            // Disable TTS
            ttsService?.release()
            ttsService = null
            _isEnabled.value = false
        } else {
            // For non-Kokoro providers, use the TtsProvider interface
            ttsService?.release()
            ttsService = null
            currentProvider = factory.create(type, config)
            _isEnabled.value = true
        }

        // Persist settings
        scope.launch(Dispatchers.IO) {
            try {
                settingsRepository?.saveSettings(
                    _serverUrl.value,
                    _voice.value,
                    _speed.value,
                    _isEnabled.value
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings after provider switch", e)
            }
        }
    }

    /**
     * Check if TTS server is available
     */
    suspend fun isServerAvailable(): Boolean {
        return ttsService?.isServerAvailable() ?: false
    }

    /**
     * Fetch available voices from the configured TTS server
     */
    fun refreshVoices() {
        val service = ttsService ?: return
        _isLoadingVoices.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val voices = service.fetchVoices()
                _availableVoices.value = voices
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch voices", e)
            } finally {
                _isLoadingVoices.value = false
            }
        }
    }

    /**
     * Update the selected voice and persist
     */
    fun setVoice(voice: String) {
        _voice.value = voice
        scope.launch(Dispatchers.IO) {
            try {
                settingsRepository?.saveVoice(voice)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save voice", e)
            }
        }
    }

    /**
     * Update the playback speed and persist
     */
    fun setSpeed(speed: Float) {
        _speed.value = speed
        scope.launch(Dispatchers.IO) {
            try {
                settingsRepository?.saveSpeed(speed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save speed", e)
            }
        }
    }

    /**
     * Speak text with voice synthesis (queued)
     * Audio levels are automatically published to audioLevel flow
     * Uses saved voice/speed settings by default
     * Multiple calls will queue speech items to play sequentially
     */
    fun speak(
        text: String,
        voice: String = _voice.value,
        speed: Float = _speed.value,
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (ttsService == null && currentProvider == null) {
            onError?.invoke(IllegalStateException("TTS not configured"))
            return
        }

        // Add to queue
        speechQueue.offer(SpeechRequest(text, voice, speed, onComplete, onError))
        val queueSize = speechQueue.size
        Log.d(TAG, "Queued speech, queue size: $queueSize")
        DiagnosticLogger.ttsQueued(text, queueSize, voice)

        // If not currently playing, start playback
        if (!_isPlaying.value) {
            playNextInQueue()
        }
    }

    /**
     * Play the next item in the speech queue
     * Uses mutex to prevent race conditions from concurrent calls
     */
    private fun playNextInQueue() {
        scope.launch {
            playbackMutex.withLock {
                // Double-check we're not already playing
                if (_isPlaying.value) {
                    Log.d(TAG, "Already playing, skipping playNextInQueue")
                    return@withLock
                }

                val request = speechQueue.poll() ?: return@withLock
                val service = ttsService
                val provider = currentProvider

                if (service == null && provider == null) {
                    request.onError?.invoke(IllegalStateException("TTS not configured"))
                    return@withLock
                }

                val remaining = speechQueue.size
                Log.d(TAG, "Playing next in queue, remaining: $remaining")
                DiagnosticLogger.ttsPlayNext(remaining, request.text.length)

                // Set playing BEFORE starting to prevent race condition
                _isPlaying.value = true

                if (service != null) {
                    // Native Kokoro path - full visualizer + ExoPlayer integration
                    playViaKokoro(service, request)
                } else if (provider != null) {
                    // Generic TtsProvider path
                    playViaProvider(provider, request)
                }
            }
        }
    }

    /**
     * Play a speech request using the native KokoroTtsService path.
     * This preserves the full Kokoro integration: visualizer attachment,
     * ExoPlayer audio session access, and PCM-level fallback.
     */
    private fun playViaKokoro(service: KokoroTtsService, request: SpeechRequest) {
        var visualizerAttached = false
        var completed = false

        currentPlaybackJob = scope.launch {
            try {
                service.speak(
                    text = request.text,
                    voice = request.voice,
                    speed = request.speed,
                    onStart = {
                        // Attach native visualizer to the audio session
                        val sessionId = service.audioSessionId
                        DiagnosticLogger.exoPlayerStarted(sessionId)
                        if (sessionId != 0) {
                            Log.d(TAG, "Attaching visualizer to audio session: $sessionId")
                            visualizerBridge.attach(sessionId, appContext)
                            visualizerAttached = true
                            DiagnosticLogger.visualizerAttached(sessionId)
                        }
                    },
                    onComplete = {
                        if (!completed) {
                            completed = true
                            DiagnosticLogger.exoPlayerFinished(service.totalChunksPlayed)
                            _isPlaying.value = false
                            DiagnosticLogger.visualizerReleased()
                            visualizerBridge.release()
                            visualizerAttached = false
                            request.onComplete?.invoke()
                            DiagnosticLogger.log("TTS_QUEUE_ADVANCE", "remaining=${speechQueue.size}")
                            // Auto-play next queued item
                            playNextInQueue()
                        }
                    },
                    onError = { e ->
                        if (!completed) {
                            completed = true
                            DiagnosticLogger.logError("TTS_PLAYBACK_ERROR", e.message ?: "unknown", e)
                            _isPlaying.value = false
                            if (visualizerAttached) {
                                DiagnosticLogger.visualizerReleased()
                                visualizerBridge.release()
                                visualizerAttached = false
                            }
                            request.onError?.invoke(e)
                            // Continue with next item even on error
                            playNextInQueue()
                        }
                    },
                    onAudioLevel = { level ->
                        // Use PCM-computed level as fallback when native Visualizer isn't active
                        if (!visualizerBridge.isActive()) {
                            _pcmAudioLevel.value = level
                        }
                    }
                )
            } catch (e: Exception) {
                if (!completed) {
                    Log.e(TAG, "Exception during Kokoro playback", e)
                    _isPlaying.value = false
                    if (visualizerAttached) {
                        visualizerBridge.release()
                    }
                    request.onError?.invoke(e)
                    playNextInQueue()
                }
            } finally {
                // Only clean up on actual cancellation, not normal return
                // service.speak() returns immediately with ExoPlayer (async)
                // so finally runs before callbacks fire - that's normal, not interrupted
                if (_isPlaying.value && !completed && coroutineContext[Job]?.isCancelled == true) {
                    Log.d(TAG, "Playback interrupted by cancellation, cleaning up")
                    _isPlaying.value = false
                    if (visualizerAttached) {
                        visualizerBridge.release()
                    }
                    playNextInQueue()
                }
            }
        }
    }

    /**
     * Play a speech request using the generic TtsProvider interface.
     * Used for non-Kokoro providers (ElevenLabs, OpenAI, Android TTS, etc.).
     * Audio levels are delivered via the provider's onLevel callback.
     */
    private fun playViaProvider(provider: TtsProvider, request: SpeechRequest) {
        currentPlaybackJob = scope.launch {
            try {
                provider.speak(
                    text = request.text,
                    onLevel = { level ->
                        _pcmAudioLevel.value = level
                    },
                    onDone = {
                        _isPlaying.value = false
                        _pcmAudioLevel.value = 0f
                        request.onComplete?.invoke()
                        DiagnosticLogger.log("TTS_QUEUE_ADVANCE", "provider=${provider.providerId}, remaining=${speechQueue.size}")
                        // Auto-play next queued item
                        playNextInQueue()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during ${provider.providerId} playback", e)
                _isPlaying.value = false
                _pcmAudioLevel.value = 0f
                request.onError?.invoke(e)
                // Continue with next item even on error
                playNextInQueue()
            }
        }
    }

    /**
     * Stop any current playback and clear the queue
     */
    fun stop() {
        // Cancel current playback coroutine
        currentPlaybackJob?.cancel()
        currentPlaybackJob = null

        speechQueue.clear()
        ttsService?.stop()
        currentProvider?.stop()
        visualizerBridge.release()
        _isPlaying.value = false
        _pcmAudioLevel.value = 0f
        DiagnosticLogger.log("TTS_STOP", "playback stopped, queue cleared")
        Log.d(TAG, "Stopped playback and cleared queue")
    }

    /**
     * Release resources (call when app is destroyed)
     */
    @Suppress("unused")
    fun release() {
        visualizerBridge.release()
        ttsService?.release()
        ttsService = null
        currentProvider?.release()
        currentProvider = null
    }
}
