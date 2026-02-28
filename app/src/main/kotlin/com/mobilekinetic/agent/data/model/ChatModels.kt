package com.mobilekinetic.agent.data.model

import com.mobilekinetic.agent.data.chat.ParsedResponse
import com.mobilekinetic.agent.data.chat.ResponseParser
import com.mobilekinetic.agent.data.chat.ResponseSegment
import com.mobilekinetic.agent.data.chat.SegmentType
import java.util.UUID

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole = MessageRole.USER,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String = "text",
    val toolName: String? = null,
    val toolInput: String? = null,
    val isError: Boolean = false,
    val costUsd: Float? = null,
    val durationMs: Long? = null
) {
    /**
     * Lazily parsed response for voice/display separation
     */
    val parsed: ParsedResponse by lazy {
        if (role == MessageRole.ASSISTANT) {
            ResponseParser.parse(content)
        } else {
            ParsedResponse(listOf(ResponseSegment(SegmentType.VOICE, content)))
        }
    }

    /**
     * Voice content extracted for TTS - cleaned and ready to speak
     */
    val voiceContent: String
        get() = if (role == MessageRole.ASSISTANT) {
            ResponseParser.cleanForVoice(parsed.voiceContent)
        } else ""

    /**
     * Whether this message has voice content that should be spoken
     */
    val hasVoiceContent: Boolean
        get() = role == MessageRole.ASSISTANT && parsed.hasVoice
}

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Conversation",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val claudeSessionId: String? = null
)
