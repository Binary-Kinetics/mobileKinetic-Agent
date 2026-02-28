package com.mobilekinetic.agent.data.gemma

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaTextGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: GemmaModelManager
) {
    companion object {
        private const val TAG = "GemmaTextGenerator"
        private const val DEFAULT_MAX_TOKENS = 1024
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95f
    }

    private var llmInference: LlmInference? = null
    private var isInitialized = false

    val isReady: Boolean get() = isInitialized && modelManager.textGenStatus.value.state == ModelState.READY

    fun initialize() {
        if (isInitialized) return
        if (!modelManager.isModelDownloaded(ModelType.GEMMA_3_1B)) {
            Log.w(TAG, "Gemma 3 1B model not downloaded")
            return
        }
        if (!modelManager.canLoadModel(ModelType.GEMMA_3_1B)) {
            Log.w(TAG, "Insufficient VRAM to load Gemma 3 1B")
            return
        }

        try {
            modelManager.updateStatus(ModelType.GEMMA_3_1B, ModelStatus(state = ModelState.LOADING))

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelManager.getModelPath(ModelType.GEMMA_3_1B))
                .setPreferredBackend(LlmInference.Backend.GPU)
                .setMaxTokens(DEFAULT_MAX_TOKENS)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            modelManager.updateStatus(ModelType.GEMMA_3_1B, ModelStatus(
                state = ModelState.READY,
                vramMb = GemmaModelManager.ESTIMATED_TEXT_GEN_VRAM_MB
            ))
            Log.i(TAG, "Gemma text generator initialized on GPU")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma text generator", e)
            modelManager.updateStatus(ModelType.GEMMA_3_1B, ModelStatus(
                state = ModelState.ERROR,
                error = e.message
            ))
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS, temperature: Float = DEFAULT_TEMPERATURE): String {
        val engine = llmInference ?: throw IllegalStateException("Gemma text generator not ready")

        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(DEFAULT_TOP_K)
            .setTopP(DEFAULT_TOP_P)
            .build()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        return try {
            session.addQueryChunk(prompt)
            session.generateResponse()
        } finally {
            session.close()
        }
    }

    fun generateStream(prompt: String, temperature: Float = DEFAULT_TEMPERATURE): Flow<String> = callbackFlow {
        val engine = llmInference ?: run {
            close(IllegalStateException("Gemma text generator not ready"))
            return@callbackFlow
        }

        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(DEFAULT_TOP_K)
            .setTopP(DEFAULT_TOP_P)
            .build()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

        session.addQueryChunk(prompt)
        session.generateResponseAsync { partialResult, done ->
            trySend(partialResult)
            if (done) close()
        }

        awaitClose {
            session.close()
        }
    }

    fun release() {
        llmInference?.close()
        llmInference = null
        isInitialized = false
        modelManager.updateStatus(ModelType.GEMMA_3_1B, ModelStatus(state = ModelState.RELEASED))
        Log.i(TAG, "Gemma text generator released")
    }
}
