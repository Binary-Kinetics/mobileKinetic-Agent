package com.mobilekinetic.agent.data.rag

/** Controls the task prefix for asymmetric embedding models like Gemma. */
enum class EmbeddingMode {
    /** For storing documents/memories: "task: retrieval | content: ..." */
    DOCUMENT,
    /** For search queries: "task: search result | query: ..." */
    QUERY
}

interface EmbeddingProvider {
    val providerId: String
    val dimensions: Int
    val isReady: Boolean
    suspend fun embed(text: String, mode: EmbeddingMode = EmbeddingMode.QUERY): FloatArray
    suspend fun embedBatch(texts: List<String>, mode: EmbeddingMode = EmbeddingMode.QUERY): List<FloatArray>
}
