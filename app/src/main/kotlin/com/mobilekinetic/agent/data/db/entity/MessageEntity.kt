package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mobilekinetic.agent.data.model.MessageRole

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val rawContent: String,
    val timestamp: Long,
    val messageType: String = "text",
    val toolName: String? = null,
    val toolInput: String? = null,
    val isError: Boolean = false,
    val costUsd: Float? = null,
    val durationMs: Long? = null
)
