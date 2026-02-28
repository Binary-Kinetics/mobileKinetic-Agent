package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mobilekinetic.agent.data.db.entity.SessionSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionSummaryDao {
    @Query("SELECT * FROM session_summaries ORDER BY endTime DESC")
    fun getAllSummaries(): Flow<List<SessionSummaryEntity>>

    @Query("SELECT * FROM session_summaries WHERE isPinned = 1 OR isLandmark = 1 ORDER BY endTime DESC")
    fun getPinnedAndLandmarks(): Flow<List<SessionSummaryEntity>>

    @Query("SELECT * FROM session_summaries WHERE id = :id LIMIT 1")
    suspend fun getSummary(id: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSummaryBySessionId(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries ORDER BY endTime DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int): List<SessionSummaryEntity>

    @Upsert
    suspend fun upsert(summary: SessionSummaryEntity)

    @Query("UPDATE session_summaries SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE session_summaries SET lastAccessedAt = :now, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun recordAccess(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE session_summaries SET currentTier = :tier, stabilityScore = :stability, updatedAt = :now WHERE id = :id")
    suspend fun updateDecayState(id: String, tier: String, stability: Float, now: Long = System.currentTimeMillis())

    @Query("UPDATE session_summaries SET summaryKeypoints = :keypoints, summaryTopics = :topics, summaryFacts = :facts, currentTier = :tier, updatedAt = :now WHERE id = :id")
    suspend fun compressSummary(id: String, keypoints: String?, topics: String?, facts: String?, tier: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM session_summaries WHERE isPinned = 0 AND isLandmark = 0 AND stabilityScore < :threshold")
    suspend fun pruneDecayed(threshold: Float)

    @Query("SELECT * FROM session_summaries WHERE isPinned = 0 AND isLandmark = 0 AND stabilityScore > :threshold ORDER BY stabilityScore ASC")
    suspend fun getDecayCandidates(threshold: Float): List<SessionSummaryEntity>

    @Query("SELECT COUNT(*) FROM session_summaries")
    suspend fun getCount(): Int
}
