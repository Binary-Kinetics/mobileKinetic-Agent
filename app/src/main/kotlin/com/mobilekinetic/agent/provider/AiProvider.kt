package com.mobilekinetic.agent.provider

import kotlinx.coroutines.flow.Flow

/**
 * Interface for pluggable AI providers.
 * Each provider implements streaming message exchange via Flow.
 */
interface AiProvider {
    val name: String
    val id: String

    /**
     * Send messages and receive a stream of events.
     * @param messages Conversation history
     * @param systemPrompt System prompt for the model
     * @param tools Available tools the model can call
     * @return Flow of streaming events
     */
    fun sendMessage(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool> = emptyList()
    ): Flow<AiStreamEvent>

    /**
     * Cancel the current request.
     */
    suspend fun cancel()

    /**
     * Initialize the provider. Called once before use.
     * @return null if successful, error message string if failed
     */
    suspend fun initialize(): String?

    /**
     * Release all resources.
     */
    fun dispose()

    /**
     * Whether the provider is ready to handle requests.
     */
    fun isReady(): Boolean

    /**
     * Return the configuration schema for this provider.
     * Used by the settings UI to render the config form.
     */
    fun getConfigSchema(): List<ProviderConfigField>
}
