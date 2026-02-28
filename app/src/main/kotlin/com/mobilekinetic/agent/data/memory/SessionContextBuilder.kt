package com.mobilekinetic.agent.data.memory

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.MemoryFactDao
import com.mobilekinetic.agent.data.db.dao.SessionSummaryDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionContextBuilder @Inject constructor(
    private val sessionSummaryDao: SessionSummaryDao,
    private val memoryFactDao: MemoryFactDao
) {
    companion object {
        private const val TAG = "SessionContextBuilder"
        private const val MAX_CONTEXT_CHARS = 3200
    }

    suspend fun buildContext(): String {
        val builder = StringBuilder()
        var remaining = MAX_CONTEXT_CHARS

        val pinnedFacts = memoryFactDao.getTopFacts(50).filter { it.isPinned }
        val recentSummaries = sessionSummaryDao.getRecentSummaries(5)
        val topFacts = memoryFactDao.getTopFacts(20)

        // Priority 1: Pinned landmarks
        recentSummaries.filter { it.isPinned || it.isLandmark }.forEach { summary ->
            val entry = "[LANDMARK] ${summary.summaryFacts ?: summary.summaryTopics ?: summary.summaryKeypoints ?: summary.summaryFull}\n"
            if (entry.length <= remaining) {
                builder.append(entry)
                remaining -= entry.length
                sessionSummaryDao.recordAccess(summary.id)
            }
        }

        // Priority 2: Pinned facts
        pinnedFacts.forEach { fact ->
            val entry = "[PINNED] ${fact.category}: ${fact.key} = ${fact.value}\n"
            if (entry.length <= remaining) {
                builder.append(entry)
                remaining -= entry.length
                memoryFactDao.recordAccess(fact.id)
            }
        }

        // Priority 3: Recent summaries
        recentSummaries.filter { !it.isPinned && !it.isLandmark }.forEach { summary ->
            val text = when (summary.currentTier) {
                "FACTS" -> summary.summaryFacts
                "TOPICS" -> summary.summaryTopics
                "KEYPOINTS" -> summary.summaryKeypoints
                else -> summary.summaryFull
            } ?: return@forEach
            val entry = "[SESSION] $text\n"
            if (entry.length <= remaining) {
                builder.append(entry)
                remaining -= entry.length
                sessionSummaryDao.recordAccess(summary.id)
            }
        }

        // Priority 4: Top facts by stability
        topFacts.filter { !it.isPinned }.forEach { fact ->
            val entry = "[FACT] ${fact.category}: ${fact.key} = ${fact.value}\n"
            if (entry.length <= remaining) {
                builder.append(entry)
                remaining -= entry.length
                memoryFactDao.recordAccess(fact.id)
            }
        }

        Log.i(TAG, "Built context: ${builder.length} chars")
        return builder.toString()
    }
}
