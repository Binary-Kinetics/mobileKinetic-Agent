package com.mobilekinetic.agent.data.tts

import android.app.Application
import android.util.Log
import com.mobilekinetic.agent.data.tts.providers.AndroidTtsProvider
import com.mobilekinetic.agent.data.tts.providers.ElevenLabsTtsProvider
import com.mobilekinetic.agent.data.tts.providers.KokoroTtsProvider
import com.mobilekinetic.agent.data.tts.providers.NoOpTtsProvider
import com.mobilekinetic.agent.data.tts.providers.OpenAiTtsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating TtsProvider instances based on provider type and configuration.
 *
 * Currently supports:
 * - KOKORO: Self-hosted LuxTTS/Kokoro server (WebSocket streaming + ExoPlayer)
 * - NONE: Disabled (no-op provider that completes immediately)
 *
 * Future providers (ANDROID_TTS, ELEVENLABS, OPENAI_TTS) will return NoOpTtsProvider
 * until their implementations are completed.
 *
 * @param application Application context for provider initialization
 */
@Singleton
class TtsProviderFactory @Inject constructor(
    private val application: Application
) {
    companion object {
        private const val TAG = "TtsProviderFactory"
    }

    /**
     * Create a TtsProvider for the given type and configuration.
     *
     * @param type The provider type to create
     * @param config Provider-specific configuration (URL, API key, voice, etc.)
     * @return A configured TtsProvider instance
     */
    fun create(type: TtsProviderType, config: TtsProviderConfig): TtsProvider {
        Log.d(TAG, "Creating TTS provider: ${type.displayName}")

        return when (type) {
            TtsProviderType.KOKORO -> KokoroTtsProvider(application, config)
            TtsProviderType.NONE -> NoOpTtsProvider()
            TtsProviderType.ANDROID_TTS -> AndroidTtsProvider(application, config)
            TtsProviderType.ELEVENLABS -> ElevenLabsTtsProvider(application, config)
            TtsProviderType.OPENAI_TTS -> OpenAiTtsProvider(application, config)
        }
    }
}
