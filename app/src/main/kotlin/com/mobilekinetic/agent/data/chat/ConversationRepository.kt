package com.mobilekinetic.agent.data.chat

import com.mobilekinetic.agent.data.db.dao.ConversationDao
import com.mobilekinetic.agent.data.db.entity.ConversationEntity
import com.mobilekinetic.agent.data.db.entity.MessageEntity
import com.mobilekinetic.agent.data.model.ChatMessage
import com.mobilekinetic.agent.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ConversationRepository(private val dao: ConversationDao) {

    val allConversations: Flow<List<ConversationEntity>> = dao.getAllConversations()

    suspend fun saveConversation(conversation: Conversation) {
        dao.insertConversation(ConversationEntity(
            id = conversation.id,
            title = conversation.title,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            claudeSessionId = conversation.claudeSessionId
        ))
    }

    suspend fun saveMessage(conversationId: String, message: ChatMessage) {
        dao.insertMessage(MessageEntity(
            id = message.id,
            conversationId = conversationId,
            role = message.role,
            rawContent = message.content,
            timestamp = message.timestamp,
            messageType = message.messageType,
            toolName = message.toolName,
            toolInput = message.toolInput,
            isError = message.isError,
            costUsd = message.costUsd,
            durationMs = message.durationMs
        ))
    }

    suspend fun loadMessages(conversationId: String): List<ChatMessage> {
        return dao.getMessagesForConversationOnce(conversationId).map { entity ->
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = entity.rawContent,
                timestamp = entity.timestamp,
                messageType = entity.messageType,
                toolName = entity.toolName,
                toolInput = entity.toolInput,
                isError = entity.isError,
                costUsd = entity.costUsd,
                durationMs = entity.durationMs
            )
        }
    }

    suspend fun loadAllConversations(): List<Conversation> {
        return dao.getAllConversations().first().map { entity ->
            val messages = loadMessages(entity.id)
            Conversation(
                id = entity.id,
                title = entity.title,
                messages = messages,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                claudeSessionId = entity.claudeSessionId
            )
        }
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)
    }
}
