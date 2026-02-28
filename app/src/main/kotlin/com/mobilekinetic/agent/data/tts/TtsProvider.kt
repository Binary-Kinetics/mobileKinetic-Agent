package com.mobilekinetic.agent.data.tts

/**
 * Interface for pluggable TTS providers.
 */
interface TtsProvider {
    val name: String
    val providerId: String

    /**
     * Speak the given text.
     * @param text Text to synthesize
     * @param onLevel Callback with audio level (0.0-1.0) for visualization
     * @param onDone Callback when speech completes
     */
    suspend fun speak(text: String, onLevel: (Float) -> Unit = {}, onDone: () -> Unit = {})

    /**
     * Stop current speech.
     */
    fun stop()

    /**
     * Release resources.
     */
    fun release()

    /**
     * Test the provider connection/configuration.
     * @return null if successful, error message string if failed
     */
    suspend fun testConnection(): String?
}
