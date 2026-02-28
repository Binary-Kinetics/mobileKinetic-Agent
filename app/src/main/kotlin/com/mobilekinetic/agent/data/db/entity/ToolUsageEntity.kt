package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_usage",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("toolId")]
)
data class ToolUsageEntity(
    @PrimaryKey val id: String,
    val toolId: String,
    val inputJson: String,
    val resultJson: String = "",
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val conversationId: String? = null,
    val executionTimeMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
