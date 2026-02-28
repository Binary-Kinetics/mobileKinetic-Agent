package com.mobilekinetic.agent.data.rag

import android.content.Context
import android.util.Log
import com.mobilekinetic.agent.data.gemma.GemmaModelManager
import com.mobilekinetic.agent.data.gemma.ModelState
import com.mobilekinetic.agent.data.gemma.ModelStatus
import com.mobilekinetic.agent.data.gemma.ModelType
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DISABLED — Do not use. Produces buggy/inconsistent 768-dim vectors.
 * Retained in the DI graph but never called. ONNX MiniLM (384-dim) is
 * the sole embedding provider. See DualEmbeddingProvider.
 */
@Singleton
class GemmaEmbeddingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: GemmaModelManager
) : EmbeddingProvider {

    companion object {
        private const val TAG = "GemmaEmbeddingProvider"
        private const val SEQ_LEN = 1024
        private const val EMBEDDING_DIM = 768
        private const val TOKENIZER_ASSET = "models/sentencepiece.model"
        // SentencePiece special token IDs for Gemma
        private const val BOS_TOKEN_ID = 2   // <bos>
        private const val EOS_TOKEN_ID = 1   // <eos>
        private const val PAD_TOKEN_ID = 0   // <pad>
        const val VERSION = 2
    }

    override val providerId: String = "gemma_embedding_300m"
    override val dimensions: Int = EMBEDDING_DIM
    override val isReady: Boolean
        get() = modelManager.embeddingStatus.value.state == ModelState.READY

    private var compiledModel: CompiledModel? = null
    private var tokenizer: SentencePieceTokenizer? = null
    private val mutex = Mutex()

    override suspend fun embed(text: String, mode: EmbeddingMode): FloatArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val model = compiledModel
                ?: throw IllegalStateException("Gemma embedding model not ready")
            val tok = tokenizer
                ?: throw IllegalStateException("Tokenizer not loaded")

            val promptText = when (mode) {
                EmbeddingMode.DOCUMENT -> "task: retrieval | content: $text"
                EmbeddingMode.QUERY    -> "task: search result | query: $text"
            }
            // Tokenize: [BOS] + tokens + [EOS], pad to SEQ_LEN
            val tokenIds = tok.encode(promptText)
            val inputIds = IntArray(SEQ_LEN) { PAD_TOKEN_ID }
            inputIds[0] = BOS_TOKEN_ID
            val copyLen = minOf(tokenIds.size, SEQ_LEN - 2)
            for (i in 0 until copyLen) {
                inputIds[i + 1] = tokenIds[i]
            }
            inputIds[copyLen + 1] = EOS_TOKEN_ID

            // Number of real (non-PAD) tokens: BOS + user tokens + EOS
            val numTokens = minOf(copyLen + 2, SEQ_LEN)

            // Create NPU-optimized input/output buffers
            val inputBuffers = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()

            // Write token IDs into input buffer (Int32)
            inputBuffers[0].writeInt(inputIds)

            // If model accepts attention mask (second input buffer), populate it
            if (inputBuffers.size > 1) {
                val attentionMask = IntArray(SEQ_LEN)
                for (i in 0 until numTokens) attentionMask[i] = 1
                inputBuffers[1].writeInt(attentionMask)
                Log.d(TAG, "Attention mask set: ${numTokens} real tokens, ${SEQ_LEN - numTokens} padded")
            }

            // Run inference on NPU
            model.run(inputBuffers, outputBuffers)

            val rawOutput = outputBuffers[0].readFloat()

            // Model may output [768] (pre-pooled) or [SEQ_LEN*768] (per-token)
            val embedding = if (rawOutput.size == EMBEDDING_DIM) {
                // Model already returns a single pooled embedding
                rawOutput.copyOf()
            } else {
                // Per-token output: mean-pool over real token positions only
                val seqLen = rawOutput.size / EMBEDDING_DIM
                Log.d(TAG, "Mean-pooling over $numTokens/$seqLen tokens")
                val pooled = FloatArray(EMBEDDING_DIM)
                val poolCount = minOf(numTokens, seqLen)
                for (pos in 0 until poolCount) {
                    val offset = pos * EMBEDDING_DIM
                    for (dim in 0 until EMBEDDING_DIM) {
                        pooled[dim] += rawOutput[offset + dim]
                    }
                }
                for (dim in 0 until EMBEDDING_DIM) {
                    pooled[dim] /= poolCount.toFloat()
                }
                pooled
            }

            // L2 normalize the embedding
            var norm = 0f
            for (v in embedding) norm += v * v
            norm = kotlin.math.sqrt(norm.toDouble()).toFloat()
            if (norm > 0f) {
                for (i in embedding.indices) {
                    embedding[i] = embedding[i] / norm
                }
            }

            embedding
        }
    }

    override suspend fun embedBatch(texts: List<String>, mode: EmbeddingMode): List<FloatArray> {
        return texts.map { embed(it, mode) }
    }

    fun initialize() {
        if (!modelManager.isModelDownloaded(ModelType.EMBEDDING_GEMMA)) {
            Log.w(TAG, "Embedding model not downloaded")
            return
        }
        if (!modelManager.canLoadModel(ModelType.EMBEDDING_GEMMA)) {
            Log.w(TAG, "Insufficient VRAM to load embedding model")
            return
        }

        try {
            modelManager.updateStatus(ModelType.EMBEDDING_GEMMA, ModelStatus(state = ModelState.LOADING))

            // Load SentencePiece tokenizer from assets (noCompress in build.gradle.kts is critical)
            val tokenizerBytes = context.assets.open(TOKENIZER_ASSET).readBytes()
            tokenizer = SentencePieceTokenizer(tokenizerBytes)
            Log.i(TAG, "SentencePiece tokenizer loaded (${tokenizerBytes.size} bytes, vocab=${tokenizer!!.vocabSize})")

            // Load model via CompiledModel (NPU preferred, GPU fallback)
            val modelPath = modelManager.getModelPath(ModelType.EMBEDDING_GEMMA)
            Log.i(TAG, "Loading model from: $modelPath")
            compiledModel = try {
                val npuOptions = CompiledModel.Options(Accelerator.NPU)
                CompiledModel.create(modelPath, npuOptions).also {
                    Log.i(TAG, "Gemma embedding model loaded on NPU")
                }
            } catch (e: Exception) {
                Log.w(TAG, "NPU failed, falling back to GPU", e)
                val gpuOptions = CompiledModel.Options(Accelerator.GPU)
                CompiledModel.create(modelPath, gpuOptions).also {
                    Log.i(TAG, "Gemma embedding model loaded on GPU (fallback)")
                }
            }

            modelManager.updateStatus(ModelType.EMBEDDING_GEMMA, ModelStatus(
                state = ModelState.READY,
                vramMb = GemmaModelManager.ESTIMATED_EMBEDDING_VRAM_MB
            ))
            Log.i(TAG, "Gemma embedding provider initialized (${EMBEDDING_DIM}D, seq=$SEQ_LEN)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma embedding provider", e)
            modelManager.updateStatus(ModelType.EMBEDDING_GEMMA, ModelStatus(
                state = ModelState.ERROR,
                error = e.message
            ))
        }
    }

    fun release() {
        compiledModel?.close()
        compiledModel = null
        tokenizer = null
        modelManager.updateStatus(ModelType.EMBEDDING_GEMMA, ModelStatus(state = ModelState.RELEASED))
        Log.i(TAG, "Gemma embedding provider released")
    }
}
