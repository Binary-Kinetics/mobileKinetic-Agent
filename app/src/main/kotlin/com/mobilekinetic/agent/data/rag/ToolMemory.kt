package com.mobilekinetic.agent.data.rag

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import com.mobilekinetic.agent.data.db.entity.ToolUsageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ToolMemory(
    private val toolDao: ToolDao,
    private val ragRepository: RagRepository
) {
    companion object {
        private const val TAG = "ToolMemory"
        private const val RAG_CATEGORY_TOOL = "tool"
    }

    val allTools: Flow<List<ToolEntity>> = toolDao.getAllTools()

    val approvedTools: Flow<List<ToolEntity>> = toolDao.getApprovedTools()

    suspend fun registerTool(tool: ToolEntity) {
        toolDao.insertTool(tool)
        ragRepository.addDocument(
            text = "${tool.name}: ${tool.description}",
            category = RAG_CATEGORY_TOOL,
            id = "tool_${tool.id}",
            metadata = """{"tool_id": "${tool.id}", "execution_type": "${tool.executionType}"}"""
        )
        Log.i(TAG, "Registered tool: ${tool.name} (${tool.executionType})")
    }

    suspend fun findTools(query: String, topK: Int = 5): List<ToolEntity> {
        val results = ragRepository.searchInCategories(query, listOf(RAG_CATEGORY_TOOL), topK)
        val toolIds = results.mapNotNull { result ->
            result.id.removePrefix("tool_").takeIf { it != result.id }
        }
        return toolIds.mapNotNull { toolDao.getTool(it) }
    }

    suspend fun recordUsage(
        toolId: String,
        inputJson: String,
        resultJson: String = "",
        isSuccess: Boolean = true,
        errorMessage: String? = null,
        conversationId: String? = null,
        executionTimeMs: Long = 0
    ) {
        val usage = ToolUsageEntity(
            id = UUID.randomUUID().toString(),
            toolId = toolId,
            inputJson = inputJson,
            resultJson = resultJson,
            isSuccess = isSuccess,
            errorMessage = errorMessage,
            conversationId = conversationId,
            executionTimeMs = executionTimeMs
        )
        toolDao.insertUsage(usage)
        toolDao.incrementUseCount(toolId)
    }

    suspend fun getTool(id: String): ToolEntity? = toolDao.getTool(id)

    suspend fun getToolByName(name: String): ToolEntity? = toolDao.getToolByName(name)

    suspend fun approveTool(id: String) {
        toolDao.getTool(id)?.let { tool ->
            toolDao.updateTool(tool.copy(isUserApproved = true, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun revokeTool(id: String) {
        toolDao.getTool(id)?.let { tool ->
            toolDao.updateTool(tool.copy(isUserApproved = false, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteTool(id: String) {
        toolDao.deleteTool(id)
        ragRepository.deleteDocument("tool_$id")
    }

    suspend fun getUsageHistory(toolId: String, limit: Int = 20): List<ToolUsageEntity> {
        return toolDao.getUsageHistory(toolId, limit)
    }

    fun getRecentUsage(limit: Int = 50): Flow<List<ToolUsageEntity>> {
        return toolDao.getRecentUsage(limit)
    }

    suspend fun getToolStats(toolId: String): ToolStats {
        val tool = toolDao.getTool(toolId)
        val successCount = toolDao.getSuccessCount(toolId)
        val failureCount = toolDao.getFailureCount(toolId)
        return ToolStats(
            toolName = tool?.name ?: "Unknown",
            totalUses = tool?.useCount ?: 0,
            successCount = successCount,
            failureCount = failureCount,
            successRate = if (successCount + failureCount > 0) {
                successCount.toFloat() / (successCount + failureCount)
            } else 0f
        )
    }

    data class ToolStats(
        val toolName: String,
        val totalUses: Int,
        val successCount: Int,
        val failureCount: Int,
        val successRate: Float
    )
}
