package com.mobilekinetic.agent.data.rag

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedding provider — ONNX MiniLM only.
 *
 * GemmaEmbeddingProvider is DISABLED. It produced buggy/inconsistent vectors
 * (768-dim) that caused dimension mismatches and silent search failures.
 * Do NOT re-enable Gemma as a fallback without fixing the underlying issues.
 */
@Singleton
class DualEmbeddingProvider @Inject constructor(
    private val onnxProvider: OnnxEmbeddingProvider,
    @Suppress("unused") private val gemmaProvider: GemmaEmbeddingProvider // retained for DI graph only
) : EmbeddingProvider {

    override val providerId: String = "onnx"
    override val dimensions: Int get() = onnxProvider.dimensions
    override val isReady: Boolean get() = onnxProvider.isReady

    val activeProvider: EmbeddingProvider get() = onnxProvider

    override suspend fun embed(text: String, mode: EmbeddingMode): FloatArray {
        if (!onnxProvider.isReady) {
            throw IllegalStateException("ONNX embedding provider not ready")
        }
        return onnxProvider.embed(text, mode)
    }

    override suspend fun embedBatch(texts: List<String>, mode: EmbeddingMode): List<FloatArray> {
        return texts.map { embed(it, mode) }
    }
}
