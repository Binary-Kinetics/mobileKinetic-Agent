package com.mobilekinetic.agent.provider

/**
 * Role of a message in the conversation.
 */
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

/**
 * A message in the conversation.
 */
data class AiMessage(
    val role: MessageRole,
    val content: List<ContentBlock> = emptyList(),
    val toolCallId: String? = null
) {
    companion object {
        fun user(text: String) = AiMessage(
            role = MessageRole.USER,
            content = listOf(ContentBlock.Text(text))
        )

        fun assistant(text: String) = AiMessage(
            role = MessageRole.ASSISTANT,
            content = listOf(ContentBlock.Text(text))
        )

        fun toolResult(callId: String, result: String) = AiMessage(
            role = MessageRole.TOOL,
            content = listOf(ContentBlock.Text(result)),
            toolCallId = callId
        )
    }
}

/**
 * Content block within a message.
 */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String  // JSON string
    ) : ContentBlock()
    data class Image(
        val base64: String,
        val mimeType: String
    ) : ContentBlock()
}
