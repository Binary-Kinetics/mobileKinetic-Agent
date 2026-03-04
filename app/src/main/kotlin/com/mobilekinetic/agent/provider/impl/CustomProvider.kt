package com.mobilekinetic.agent.provider.impl

import com.mobilekinetic.agent.provider.AiMessage
import com.mobilekinetic.agent.provider.AiProvider
import com.mobilekinetic.agent.provider.AiStreamEvent
import com.mobilekinetic.agent.provider.AiTool
import com.mobilekinetic.agent.provider.FieldType
import com.mobilekinetic.agent.provider.ProviderConfigField
import kotlinx.coroutines.flow.Flow

/**
 * AiProvider for OpenAI-compatible API servers.
 *
 * Delegates all work to [OpenAiProvider] with a custom base URL.
 * Supports local inference servers such as Ollama, vLLM, LM Studio,
 * text-generation-webui, and any other server that implements the
 * OpenAI Chat Completions SSE protocol.
 */
class CustomProvider(
    apiKey: String,
    model: String,
    baseUrl: String
) : AiProvider {

    companion object {
        private const val RELAY_URL = "http://10.10.255.222:7300"
    }

    override val name: String = "Custom (OpenAI-Compatible)"
    override val id: String = "custom"

    private val delegate: OpenAiProvider

    init {
        val effectiveUrl: String
        val effectiveModel: String
        if (baseUrl.trim() == "ClaudeCode") {
            effectiveUrl = RELAY_URL
            effectiveModel = "claude-code"
        } else {
            effectiveUrl = baseUrl.trimEnd('/')
            effectiveModel = model
        }
        delegate = OpenAiProvider(
            apiKey = apiKey.ifBlank { "not-needed" },
            model = effectiveModel,
            baseUrl = effectiveUrl
        )
    }

    // -----------------------------------------------------------------------
    // Delegate all AiProvider methods
    // -----------------------------------------------------------------------

    override fun sendMessage(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool>
    ): Flow<AiStreamEvent> = delegate.sendMessage(messages, systemPrompt, tools)

    override suspend fun cancel() = delegate.cancel()

    override suspend fun initialize(): String? = delegate.initialize()

    override fun dispose() = delegate.dispose()

    override fun isReady(): Boolean = delegate.isReady()

    // -----------------------------------------------------------------------
    // Custom configuration schema
    // -----------------------------------------------------------------------

    override fun getConfigSchema(): List<ProviderConfigField> = listOf(
        ProviderConfigField(
            key = "base_url",
            label = "Server URL",
            type = FieldType.URL,
            required = true,
            placeholder = "http://localhost:11434/v1"
        ),
        ProviderConfigField(
            key = "api_key",
            label = "API Key",
            type = FieldType.PASSWORD,
            required = false,
            placeholder = "Optional - leave blank if not required"
        ),
        ProviderConfigField(
            key = "model",
            label = "Model Name",
            type = FieldType.TEXT,
            required = true,
            placeholder = "llama3.1"
        )
    )
}
