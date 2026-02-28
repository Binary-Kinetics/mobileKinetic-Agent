package com.mobilekinetic.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val claudeSessionId: String? = null,
    @ColumnInfo(name = "provider_id", defaultValue = "claude_cli")
    val providerId: String = "claude_cli"
)
