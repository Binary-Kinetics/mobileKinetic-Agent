package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val executionType: String,
    val schemaJson: String,
    val isUserApproved: Boolean = false,
    val isBuiltIn: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val category: String = "OTHER",
    val source: String = "RUNTIME",
    val technicalName: String = ""
) {
    companion object {
        enum class ToolCategory {
            COMMUNICATION, MEDIA, DEVICE_CONTROL, SYSTEM, NETWORK,
            CALENDAR, TASKS, FILES, LOCATION, HOME_AUTOMATION,
            AI, SECURITY, SHELL, TASKER, NOTIFICATION, OTHER
        }
        enum class ToolSource { TASKER, RAG_SEED, RUNTIME }
    }
}
