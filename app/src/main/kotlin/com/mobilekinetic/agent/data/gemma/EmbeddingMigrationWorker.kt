package com.mobilekinetic.agent.data.gemma

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mobilekinetic.agent.data.db.dao.RagDao
import com.mobilekinetic.agent.data.rag.GemmaEmbeddingProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class EmbeddingMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val ragDao: RagDao,
    private val gemmaEmbeddingProvider: GemmaEmbeddingProvider
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "EmbeddingMigrationWorker"
        const val KEY_PROGRESS = "migration_progress"
        const val KEY_MIGRATED_COUNT = "migrated_count"
        const val KEY_TOTAL_COUNT = "total_count"
        private const val BATCH_SIZE = 10
    }

    override suspend fun doWork(): Result {
        if (!gemmaEmbeddingProvider.isReady) {
            Log.w(TAG, "Gemma embedding provider not ready, skipping migration")
            return Result.retry()
        }

        return try {
            val documents = ragDao.getDocumentsNeedingMigration(targetVersion = 2)
            val total = documents.size
            Log.i(TAG, "Starting embedding migration: $total documents to process")

            if (total == 0) {
                Log.i(TAG, "No documents need migration")
                return Result.success()
            }

            var migrated = 0
            documents.chunked(BATCH_SIZE).forEach { batch ->
                batch.forEach { doc ->
                    try {
                        val floatEmbedding = gemmaEmbeddingProvider.embed(doc.text, com.mobilekinetic.agent.data.rag.EmbeddingMode.DOCUMENT)
                        val byteBuffer = java.nio.ByteBuffer.allocate(floatEmbedding.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        floatEmbedding.forEach { byteBuffer.putFloat(it) }
                        ragDao.updateEmbedding(
                            id = doc.id,
                            embedding = byteBuffer.array(),
                            embeddingVersion = 2,
                            embeddingDim = 768
                        )
                        migrated++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate document ${doc.id}: ${e.message}")
                    }
                }

                val progress = migrated.toFloat() / total
                setProgress(workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_MIGRATED_COUNT to migrated,
                    KEY_TOTAL_COUNT to total
                ))
                Log.d(TAG, "Migration progress: $migrated/$total")
            }

            Log.i(TAG, "Embedding migration complete: $migrated/$total documents migrated")
            Result.success(workDataOf(
                KEY_MIGRATED_COUNT to migrated,
                KEY_TOTAL_COUNT to total
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Embedding migration failed", e)
            Result.retry()
        }
    }
}
