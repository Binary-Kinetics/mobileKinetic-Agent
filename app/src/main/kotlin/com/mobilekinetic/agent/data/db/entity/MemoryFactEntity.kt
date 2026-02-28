package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val category: String,  // PREFERENCE, ERROR, TOOL_USAGE, DEVICE_INFO, SYSTEM, CUSTOM
    val key: String,
    val value: String,
    val source: String? = null,
    val confidence: Float = 1.0f,
    val stabilityScore: Float = 1.0f,
    val decayRate: Float = 1.0f,
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
