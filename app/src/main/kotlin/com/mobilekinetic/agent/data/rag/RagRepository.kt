package com.mobilekinetic.agent.data.rag

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.RagDao
import com.mobilekinetic.agent.data.db.entity.RagDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class RagRepository(
    private val dao: RagDao,
    private val embeddingProvider: DualEmbeddingProvider
) {
    companion object {
        private const val TAG = "RagRepository"
    }

    data class SearchResult(
        val id: String,
        val text: String,
        val category: String,
        val metadata: String,
        val score: Float
    )

    data class ReindexProgress(
        val total: Int,
        val completed: Int,
        val failed: Int,
        val inProgress: Boolean
    )

    private val _reindexProgress = AtomicReference(ReindexProgress(0, 0, 0, false))
    val reindexProgress: ReindexProgress get() = _reindexProgress.get()

    suspend fun addDocument(
        text: String,
        category: String,
        metadata: String = "{}",
        id: String = UUID.randomUUID().toString(),
        embeddingText: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val embedding = embeddingProvider.embed(embeddingText ?: text, EmbeddingMode.DOCUMENT)
            val entity = RagDocumentEntity(
                id = id,
                text = text,
                category = category,
                embedding = floatArrayToBytes(embedding),
                metadata = metadata,
                embeddingVersion = embeddingVersion,
                embeddingDim = embedding.size
            )
            dao.insertDocument(entity)
            Log.d(TAG, "Added document: ${text.take(50)}... (category: $category)")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate embedding for document", e)
            null
        }
    }

    suspend fun search(
        query: String,
        topK: Int = 5,
        minScore: Float = 0.3f,
        embeddingText: String? = null
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val queryEmbedding = embeddingProvider.embed(embeddingText ?: query, EmbeddingMode.QUERY)
            val documents = dao.getAllDocumentsForSearch()
            rankDocuments(documents, queryEmbedding, topK, minScore)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate query embedding", e)
            emptyList()
        }
    }

    suspend fun searchInCategories(
        query: String,
        categories: List<String>,
        topK: Int = 5,
        minScore: Float = 0.3f
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val queryEmbedding = embeddingProvider.embed(query, EmbeddingMode.QUERY)
            val documents = dao.getDocumentsForSearch(categories)
            rankDocuments(documents, queryEmbedding, topK, minScore)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate query embedding", e)
            emptyList()
        }
    }

    suspend fun getContext(query: String, topK: Int = 10): String {
        val results = search(query, topK)
        if (results.isEmpty()) return ""

        return buildString {
            appendLine("## RELEVANT CONTEXT FROM ON-DEVICE RAG")
            appendLine()
            results.forEach { result ->
                appendLine("  [${result.category.uppercase()} - ${(result.score * 100).toInt()}% match] ${result.text}")
                appendLine()
            }
        }
    }

    suspend fun deleteDocument(id: String) {
        dao.deleteDocument(id)
    }

    suspend fun deleteCategory(category: String) {
        dao.deleteByCategory(category)
    }

    suspend fun getDocumentCount(): Int = dao.getDocumentCount()

    /** Full dump for SecondBrain backup -- returns all docs without embeddings overhead. */
    suspend fun getAllDocumentsForDump(): List<RagDocumentEntity> = dao.getAllDocumentsForSearch()

    val embeddingVersion: Int get() = EmbeddingModel.VERSION
    val embeddingDim: Int get() = embeddingProvider.dimensions

    val allDocuments: Flow<List<RagDocumentEntity>> = dao.getAllDocuments()

    fun documentsByCategory(category: String): Flow<List<RagDocumentEntity>> {
        return dao.getDocumentsByCategory(category)
    }

    suspend fun getReindexCount(): Int = dao.getReindexCount(embeddingDim)

    suspend fun reindexMissingEmbeddings(): ReindexProgress = withContext(Dispatchers.IO) {
        val docs = dao.getDocumentsNeedingReindex(embeddingDim)
        if (docs.isEmpty()) {
            return@withContext ReindexProgress(0, 0, 0, false)
        }

        _reindexProgress.set(ReindexProgress(docs.size, 0, 0, true))
        var completed = 0
        var failed = 0

        for (doc in docs) {
            try {
                val embedding = embeddingProvider.embed(doc.text, EmbeddingMode.DOCUMENT)
                dao.updateEmbedding(
                    id = doc.id,
                    embedding = floatArrayToBytes(embedding),
                    embeddingVersion = embeddingVersion,
                    embeddingDim = embedding.size
                )
                completed++
            } catch (e: Exception) {
                Log.e(TAG, "Reindex failed for doc ${doc.id}: ${e.message}")
                failed++
            }
            _reindexProgress.set(ReindexProgress(docs.size, completed, failed, true))
        }

        val result = ReindexProgress(docs.size, completed, failed, false)
        _reindexProgress.set(result)
        Log.i(TAG, "Reindex complete: $completed/${ docs.size} succeeded, $failed failed")
        result
    }

    private fun rankDocuments(
        documents: List<RagDocumentEntity>,
        queryEmbedding: FloatArray,
        topK: Int,
        minScore: Float
    ): List<SearchResult> {
        val queryDim = queryEmbedding.size
        return documents
            .filter { it.embeddingDim == queryDim }
            .map { doc ->
                val docEmbedding = bytesToFloatArray(doc.embedding)
                val score = cosineSimilarity(queryEmbedding, docEmbedding)
                SearchResult(
                    id = doc.id,
                    text = doc.text,
                    category = doc.category,
                    metadata = doc.metadata,
                    score = score
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    private fun floatArrayToBytes(array: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        array.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
