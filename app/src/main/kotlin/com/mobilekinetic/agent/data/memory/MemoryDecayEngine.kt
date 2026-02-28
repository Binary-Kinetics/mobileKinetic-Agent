package com.mobilekinetic.agent.data.memory

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.MemoryFactDao
import com.mobilekinetic.agent.data.db.dao.SessionSummaryDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class MemoryDecayEngine @Inject constructor(
    private val sessionSummaryDao: SessionSummaryDao,
    private val memoryFactDao: MemoryFactDao
) {
    companion object {
        private const val TAG = "MemoryDecayEngine"
        private const val COMPRESSION_THRESHOLD = 0.7f
        private const val PRUNE_THRESHOLD = 0.05f
        private const val MS_PER_DAY = 86_400_000L

        // Tier transition thresholds (days)
        private const val FULL_TO_KEYPOINTS_DAYS = 1.0
        private const val KEYPOINTS_TO_TOPICS_DAYS = 3.0
        private const val TOPICS_TO_FACTS_DAYS = 7.0
        private const val FACTS_TO_LANDMARK_DAYS = 30.0

        // Per-category decay rates (higher = slower decay)
        val CATEGORY_DECAY_RATES = mapOf(
            "PREFERENCE" to 5.0f,
            "ERROR" to 2.0f,
            "TOOL_USAGE" to 3.0f,
            "DEVICE_INFO" to 10.0f,
            "SYSTEM" to 10.0f,
            "CUSTOM" to 3.0f
        )
    }

    fun calculateRetention(timeSinceCreationMs: Long, stabilityDays: Float): Float {
        val t = timeSinceCreationMs.toDouble() / MS_PER_DAY
        val s = stabilityDays.toDouble()
        return exp(-t / s).toFloat().coerceIn(0f, 1f)
    }

    fun getTargetTier(ageDays: Double, isPinned: Boolean, isLandmark: Boolean): String {
        if (isPinned || isLandmark) return "LANDMARK"
        return when {
            ageDays < FULL_TO_KEYPOINTS_DAYS -> "FULL"
            ageDays < KEYPOINTS_TO_TOPICS_DAYS -> "KEYPOINTS"
            ageDays < TOPICS_TO_FACTS_DAYS -> "TOPICS"
            ageDays < FACTS_TO_LANDMARK_DAYS -> "FACTS"
            else -> "LANDMARK"
        }
    }

    suspend fun runDecayPass() {
        val now = System.currentTimeMillis()
        Log.i(TAG, "Running decay pass...")

        // Decay session summaries
        val summaryCandidates = sessionSummaryDao.getDecayCandidates(PRUNE_THRESHOLD)
        var summariesDecayed = 0
        for (summary in summaryCandidates) {
            val age = now - summary.createdAt
            val retention = calculateRetention(age, summary.decayRate)
            val boostedRetention = boostForAccess(retention, summary.accessCount)
            sessionSummaryDao.updateDecayState(summary.id, summary.currentTier, boostedRetention)
            summariesDecayed++
        }

        // Prune dead summaries
        sessionSummaryDao.pruneDecayed(PRUNE_THRESHOLD)

        // Decay memory facts
        val factCandidates = memoryFactDao.getDecayCandidates(PRUNE_THRESHOLD)
        var factsDecayed = 0
        for (fact in factCandidates) {
            val age = now - fact.createdAt
            val categoryRate = CATEGORY_DECAY_RATES[fact.category] ?: 3.0f
            val retention = calculateRetention(age, categoryRate)
            val boostedRetention = boostForAccess(retention, fact.accessCount)
            memoryFactDao.updateStability(fact.id, boostedRetention)
            factsDecayed++
        }

        // Prune dead facts
        memoryFactDao.pruneDecayed(PRUNE_THRESHOLD)

        Log.i(TAG, "Decay pass complete: $summariesDecayed summaries, $factsDecayed facts processed")
    }

    private fun boostForAccess(retention: Float, accessCount: Int): Float {
        // Spaced repetition: each access boosts retention
        val boost = 1.0f + (accessCount * 0.1f).coerceAtMost(0.5f)
        return (retention * boost).coerceAtMost(1.0f)
    }
}
