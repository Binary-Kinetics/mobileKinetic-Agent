package com.mobilekinetic.agent.data.tts

data class TtsProviderConfig(
    val url: String = "",
    val apiKey: String = "",
    val voiceId: String = "",
    val model: String = "",
    val rate: Float = 1.0f,
    val extras: Map<String, String> = emptyMap()
)
