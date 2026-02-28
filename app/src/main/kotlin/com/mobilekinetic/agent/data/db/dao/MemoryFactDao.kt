package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mobilekinetic.agent.data.db.entity.MemoryFactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryFactDao {
    @Query("SELECT * FROM memory_facts ORDER BY updatedAt DESC")
    fun getAllFacts(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE category = :category ORDER BY stabilityScore DESC")
    fun getFactsByCategory(category: String): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE isPinned = 1 ORDER BY category ASC")
    fun getPinnedFacts(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE id = :id LIMIT 1")
    suspend fun getFact(id: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts WHERE `key` = :key AND category = :category LIMIT 1")
    suspend fun getFactByKey(key: String, category: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts ORDER BY stabilityScore DESC LIMIT :limit")
    suspend fun getTopFacts(limit: Int): List<MemoryFactEntity>

    @Upsert
    suspend fun upsert(fact: MemoryFactEntity)

    @Query("UPDATE memory_facts SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE memory_facts SET lastAccessedAt = :now, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun recordAccess(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE memory_facts SET stabilityScore = :stability, updatedAt = :now WHERE id = :id")
    suspend fun updateStability(id: String, stability: Float, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM memory_facts WHERE isPinned = 0 AND stabilityScore < :threshold")
    suspend fun pruneDecayed(threshold: Float)

    @Query("SELECT * FROM memory_facts WHERE isPinned = 0 AND stabilityScore > :threshold ORDER BY stabilityScore ASC")
    suspend fun getDecayCandidates(threshold: Float): List<MemoryFactEntity>

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun getCount(): Int
}
