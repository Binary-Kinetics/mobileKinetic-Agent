package com.mobilekinetic.agent.data.rag

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnnxEmbeddingProvider @Inject constructor(
    private val embeddingModel: EmbeddingModel
) : EmbeddingProvider {
    override val providerId: String = "onnx_minilm"
    override val dimensions: Int = EmbeddingModel.EMBEDDING_DIM

    // EmbeddingModel uses a private isInitialized field with no public accessor,
    // so we track readiness based on whether embed() has succeeded.
    // Callers should ensure initialize() was called before using this provider.
    @Volatile
    private var initialized = false

    override val isReady: Boolean get() = initialized

    suspend fun initialize() {
        embeddingModel.initialize()
        initialized = true
    }

    override suspend fun embed(text: String, mode: EmbeddingMode): FloatArray {
        // ONNX MiniLM doesn't use task prefixes — mode is ignored
        val result = embeddingModel.embed(text)
            ?: throw IllegalStateException("ONNX embedding returned null — model may not be initialized")
        return result
    }

    override suspend fun embedBatch(texts: List<String>, mode: EmbeddingMode): List<FloatArray> {
        return texts.map { embed(it, mode) }
    }

    fun release() {
        embeddingModel.release()
        initialized = false
    }
}
