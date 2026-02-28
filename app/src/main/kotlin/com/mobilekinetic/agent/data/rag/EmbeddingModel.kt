package com.mobilekinetic.agent.data.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

class EmbeddingModel(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingModel"
        const val MODEL_PATH = "models/all-MiniLM-L6-v2.onnx"
        const val VOCAB_PATH = "models/vocab.txt"
        const val EMBEDDING_DIM = 384
        const val MAX_SEQ_LENGTH = 128
        const val VERSION = 1
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val mutex = Mutex()
    private var isInitialized = false

    val version: Int get() = VERSION
    val embeddingDim: Int get() = EMBEDDING_DIM

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInitialized) return@withContext

            try {
                val vocabText = context.assets.open(VOCAB_PATH).bufferedReader().readText()
                tokenizer = WordPieceTokenizer(vocabText)

                ortEnvironment = OrtEnvironment.getEnvironment()
                val modelBytes = context.assets.open(MODEL_PATH).readBytes()
                ortSession = ortEnvironment?.createSession(modelBytes)

                isInitialized = true
                Log.i(TAG, "Embedding model initialized (${EMBEDDING_DIM}D)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize embedding model", e)
                throw e
            }
        }
    }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isInitialized) {
                Log.w(TAG, "Model not initialized")
                return@withContext null
            }

            try {
                val tokens = tokenizer!!.tokenize(text, MAX_SEQ_LENGTH)
                runInference(tokens)
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed for: ${text.take(50)}", e)
                null
            }
        }
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
        return texts.map { embed(it) }
    }

    private fun runInference(tokens: TokenizedInput): FloatArray {
        val env = ortEnvironment ?: throw IllegalStateException("ORT environment not initialized")
        val session = ortSession ?: throw IllegalStateException("ORT session not initialized")

        val inputIds = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens.inputIds),
            longArrayOf(1, tokens.inputIds.size.toLong())
        )
        val attentionMask = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens.attentionMask),
            longArrayOf(1, tokens.attentionMask.size.toLong())
        )
        val tokenTypeIds = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens.tokenTypeIds),
            longArrayOf(1, tokens.tokenTypeIds.size.toLong())
        )

        val inputs = mapOf(
            "input_ids" to inputIds,
            "attention_mask" to attentionMask,
            "token_type_ids" to tokenTypeIds
        )

        val results = session.run(inputs)

        @Suppress("UNCHECKED_CAST")
        val outputArray = results[0].value as Array<Array<FloatArray>>
        val tokenEmbeddings = outputArray[0]

        val embedding = FloatArray(EMBEDDING_DIM)
        var validTokens = 0f
        for (i in tokenEmbeddings.indices) {
            if (tokens.attentionMask[i] == 1L) {
                for (j in 0 until EMBEDDING_DIM) {
                    embedding[j] += tokenEmbeddings[i][j]
                }
                validTokens++
            }
        }
        if (validTokens > 0) {
            for (j in 0 until EMBEDDING_DIM) {
                embedding[j] /= validTokens
            }
        }

        var norm = 0f
        for (v in embedding) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (j in 0 until EMBEDDING_DIM) {
                embedding[j] /= norm
            }
        }

        inputIds.close()
        attentionMask.close()
        tokenTypeIds.close()
        results.close()

        return embedding
    }

    fun release() {
        ortSession?.close()
        ortEnvironment?.close()
        ortSession = null
        ortEnvironment = null
        isInitialized = false
        Log.i(TAG, "Embedding model released")
    }
}
