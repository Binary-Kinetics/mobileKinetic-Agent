package com.mobilekinetic.agent.provider

/**
 * Events emitted during streaming AI responses.
 */
sealed class AiStreamEvent {
    /** Incremental text output */
    data class TextDelta(val text: String) : AiStreamEvent()

    /** Start of a tool call */
    data class ToolCallStart(
        val callId: String,
        val toolName: String
    ) : AiStreamEvent()

    /** Incremental tool call arguments */
    data class ToolCallDelta(
        val callId: String,
        val argumentsDelta: String
    ) : AiStreamEvent()

    /** Tool call complete */
    data class ToolCallEnd(val callId: String) : AiStreamEvent()

    /** Stream completed successfully */
    data class StreamComplete(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : AiStreamEvent()

    /** Error during streaming */
    data class StreamError(
        val message: String,
        val isRetryable: Boolean = false
    ) : AiStreamEvent()
}
