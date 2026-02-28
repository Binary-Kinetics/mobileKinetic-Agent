package com.mobilekinetic.agent.data.memory

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.MemoryFactDao
import com.mobilekinetic.agent.data.db.dao.SessionSummaryDao
import com.mobilekinetic.agent.data.db.entity.MemoryFactEntity
import com.mobilekinetic.agent.data.db.entity.SessionSummaryEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionMemoryRepository @Inject constructor(
    private val sessionSummaryDao: SessionSummaryDao,
    private val memoryFactDao: MemoryFactDao,
    private val summarizer: SessionSummarizer,
    private val contextBuilder: SessionContextBuilder
) {
    companion object {
        private const val TAG = "SessionMemoryRepo"
    }

    suspend fun archiveSession(
        sessionId: String,
        startTime: Long,
        endTime: Long,
        messageCount: Int,
        toolsUsed: List<String>,
        errorsEncountered: Int,
        transcript: String
    ) {
        val summary = summarizer.summarize(transcript)

        val entity = SessionSummaryEntity(
            sessionId = sessionId,
            startTime = startTime,
            endTime = endTime,
            messageCount = messageCount,
            toolsUsed = toolsUsed.joinToString(","),
            errorsEncountered = errorsEncountered,
            summaryFull = summary
        )

        sessionSummaryDao.upsert(entity)
        Log.i(TAG, "Archived session $sessionId ($messageCount messages)")
    }

    suspend fun captureErrorFact(error: String, source: String? = null) {
        val fact = MemoryFactEntity(
            category = "ERROR",
            key = "error_${System.currentTimeMillis()}",
            value = error.take(500),
            source = source
        )
        memoryFactDao.upsert(fact)
    }

    suspend fun captureFact(category: String, key: String, value: String, source: String? = null) {
        val existing = memoryFactDao.getFactByKey(key, category)
        val fact = MemoryFactEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            category = category,
            key = key,
            value = value,
            source = source,
            confidence = if (existing != null) (existing.confidence + 0.1f).coerceAtMost(1.0f) else 0.8f,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        memoryFactDao.upsert(fact)
    }

    suspend fun buildSessionContext(): String = contextBuilder.buildContext()

    suspend fun pinSummary(id: String) = sessionSummaryDao.setPinned(id, true)
    suspend fun unpinSummary(id: String) = sessionSummaryDao.setPinned(id, false)
    suspend fun pinFact(id: String) = memoryFactDao.setPinned(id, true)
    suspend fun unpinFact(id: String) = memoryFactDao.setPinned(id, false)

    /** Dump all memory facts for SecondBrain backup. */
    suspend fun getAllFacts(): List<MemoryFactEntity> = memoryFactDao.getTopFacts(10000)

    /** Dump all session summaries for SecondBrain backup. */
    suspend fun getAllSummaries(): List<SessionSummaryEntity> = sessionSummaryDao.getRecentSummaries(10000)
}
