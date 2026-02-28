package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val messageCount: Int,
    val toolsUsed: String = "[]",
    val errorsEncountered: Int = 0,
    val summaryFull: String,
    val summaryKeypoints: String? = null,
    val summaryTopics: String? = null,
    val summaryFacts: String? = null,
    val currentTier: String = "FULL",
    val stabilityScore: Float = 1.0f,
    val decayRate: Float = 1.0f,
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val isPinned: Boolean = false,
    val isLandmark: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
