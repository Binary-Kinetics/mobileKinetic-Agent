package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import com.mobilekinetic.agent.data.db.entity.ToolUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: ToolEntity)

    @Update
    suspend fun updateTool(tool: ToolEntity)

    @Query("SELECT * FROM tools ORDER BY useCount DESC")
    fun getAllTools(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE isUserApproved = 1 ORDER BY useCount DESC")
    fun getApprovedTools(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE id = :id")
    suspend fun getTool(id: String): ToolEntity?

    @Query("SELECT * FROM tools WHERE name = :name")
    suspend fun getToolByName(name: String): ToolEntity?

    @Query("DELETE FROM tools WHERE id = :id")
    suspend fun deleteTool(id: String)

    @Query("UPDATE tools SET useCount = useCount + 1, lastUsedAt = :timestamp WHERE id = :id")
    suspend fun incrementUseCount(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM tools")
    suspend fun getToolCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(usage: ToolUsageEntity)

    @Query("SELECT * FROM tool_usage WHERE toolId = :toolId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getUsageHistory(toolId: String, limit: Int = 20): List<ToolUsageEntity>

    @Query("SELECT * FROM tool_usage ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentUsage(limit: Int = 50): Flow<List<ToolUsageEntity>>

    @Query("DELETE FROM tool_usage WHERE toolId = :toolId")
    suspend fun clearUsageHistory(toolId: String)

    @Query("SELECT COUNT(*) FROM tool_usage WHERE toolId = :toolId AND isSuccess = 1")
    suspend fun getSuccessCount(toolId: String): Int

    @Query("SELECT COUNT(*) FROM tool_usage WHERE toolId = :toolId AND isSuccess = 0")
    suspend fun getFailureCount(toolId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertToolsIfAbsent(tools: List<ToolEntity>)

    @Query("SELECT * FROM tools WHERE technicalName = :technicalName LIMIT 1")
    suspend fun getToolByTechnicalName(technicalName: String): ToolEntity?

    @Query("SELECT * FROM tools WHERE category = :category ORDER BY useCount DESC")
    fun getToolsByCategory(category: String): Flow<List<ToolEntity>>

    @Query("SELECT DISTINCT category FROM tools ORDER BY category")
    fun getActiveCategories(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM tools WHERE source = :source")
    suspend fun getToolCountBySource(source: String): Int
}
