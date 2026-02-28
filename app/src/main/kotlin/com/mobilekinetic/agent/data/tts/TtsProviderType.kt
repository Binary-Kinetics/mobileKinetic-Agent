package com.mobilekinetic.agent.data.tts

enum class TtsProviderType(val id: String, val displayName: String) {
    KOKORO("kokoro", "Kokoro (Self-Hosted)"),
    ANDROID_TTS("android_tts", "Android Built-in TTS"),
    ELEVENLABS("elevenlabs", "ElevenLabs"),
    OPENAI_TTS("openai_tts", "OpenAI TTS"),
    NONE("none", "Disabled");

    companion object {
        fun fromId(id: String): TtsProviderType =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
