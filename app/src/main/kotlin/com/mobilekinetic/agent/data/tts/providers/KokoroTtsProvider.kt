package com.mobilekinetic.agent.data.tts.providers

import android.app.Application
import android.util.Log
import com.mobilekinetic.agent.data.tts.KokoroTtsService
import com.mobilekinetic.agent.data.tts.TtsProvider
import com.mobilekinetic.agent.data.tts.TtsProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * TtsProvider wrapper around the existing KokoroTtsService.
 *
 * Delegates speak/stop/release to a KokoroTtsService instance and bridges
 * its callback-based API to the TtsProvider interface.
 *
 * The KokoroTtsService is a plain Kotlin class (not an Android Service).
 * It streams audio via WebSocket from a LuxTTS server and plays it through
 * ExoPlayer with gapless chunk playback.
 *
 * @param application Application context for ExoPlayer initialization
 * @param config Provider configuration containing the server URL, voice, and speed
 */
class KokoroTtsProvider(
    private val application: Application,
    private val config: TtsProviderConfig
) : TtsProvider {

    companion object {
        private const val TAG = "KokoroTtsProvider"
    }

    override val name = "Kokoro (Self-Hosted)"
    override val providerId = "kokoro"

    private var service: KokoroTtsService? = null

    /**
     * Lazily get or create the underlying KokoroTtsService instance.
     * The service is a plain class instantiated with serverUrl, cacheDir, and context.
     */
    private fun getOrCreateService(): KokoroTtsService {
        return service ?: KokoroTtsService(
            serverUrl = config.url,
            cacheDir = application.cacheDir,
            context = application
        ).also { service = it }
    }

    /**
     * Speak text through the KokoroTtsService.
     *
     * KokoroTtsService.speak() is non-suspending and callback-based, so we
     * bridge it to a suspending call using suspendCancellableCoroutine.
     * The service streams audio chunks via WebSocket and plays them through
     * ExoPlayer. Completion fires when all chunks have been played.
     *
     * Audio levels are forwarded from the service's onAudioLevel callback
     * to the provider's onLevel callback.
     */
    override suspend fun speak(text: String, onLevel: (Float) -> Unit, onDone: () -> Unit) {
        if (config.url.isBlank()) {
            Log.w(TAG, "Server URL not configured, completing immediately")
            onDone()
            return
        }

        val svc = getOrCreateService()

        // KokoroTtsService.speak() returns immediately (ExoPlayer is async).
        // We use suspendCancellableCoroutine to wait for the onComplete/onError callback.
        suspendCancellableCoroutine { continuation ->
            var resumed = false

            svc.speak(
                text = text,
                voice = config.voiceId.ifBlank { "bf_eleanor" },
                speed = config.rate,
                onStart = {
                    Log.d(TAG, "Playback started")
                },
                onComplete = {
                    if (!resumed) {
                        resumed = true
                        onDone()
                        continuation.resume(Unit)
                    }
                },
                onError = { e ->
                    if (!resumed) {
                        resumed = true
                        Log.e(TAG, "Playback error: ${e.message}", e)
                        // Still call onDone so the caller knows we're finished
                        onDone()
                        continuation.resume(Unit)
                    }
                },
                onAudioLevel = { level ->
                    onLevel(level)
                }
            )

            continuation.invokeOnCancellation {
                Log.d(TAG, "speak() cancelled, stopping service")
                svc.stop()
            }
        }
    }

    /**
     * Stop any currently playing audio.
     */
    override fun stop() {
        service?.stop()
    }

    /**
     * Release all resources held by the underlying KokoroTtsService.
     * After this call, the service instance is discarded and will be
     * recreated on the next speak() call.
     */
    override fun release() {
        service?.release()
        service = null
    }

    /**
     * Test connectivity to the Kokoro/LuxTTS server.
     *
     * Attempts a WebSocket connection to the server's /ws/tts endpoint
     * (the same endpoint used for speech streaming). Returns null on
     * success or an error message string on failure.
     */
    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        if (config.url.isBlank()) {
            return@withContext "Server URL is not configured"
        }

        try {
            // First try the HTTP /voices endpoint (lighter than WebSocket)
            val svc = getOrCreateService()
            val available = svc.isServerAvailable()
            if (available) {
                null
            } else {
                "Server at ${config.url} is not reachable"
            }
        } catch (e: Exception) {
            "Connection test failed: ${e.message}"
        }
    }
}
