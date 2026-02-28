package com.mobilekinetic.agent.data.tts.providers

import com.mobilekinetic.agent.data.tts.TtsProvider

class NoOpTtsProvider : TtsProvider {
    override val name = "Disabled"
    override val providerId = "none"

    override suspend fun speak(text: String, onLevel: (Float) -> Unit, onDone: () -> Unit) {
        onDone()
    }

    override fun stop() {}
    override fun release() {}
    override suspend fun testConnection(): String? = null
}
