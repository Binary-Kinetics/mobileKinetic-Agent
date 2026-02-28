package com.mobilekinetic.agent.data.gemma

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ModelType(val fileName: String, val sizeBytes: Long) {
    EMBEDDING_GEMMA("embeddinggemma-300M_seq1024_mixed-precision.tflite", 183_329_528L),
    GEMMA_3_1B("gemma3-1b-it-int4.task", 554_661_243L)
}

enum class ModelState {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, LOADING, READY, ERROR, RELEASED
}

data class ModelStatus(
    val state: ModelState = ModelState.NOT_DOWNLOADED,
    val progress: Float = 0f,
    val error: String? = null,
    val vramMb: Int = 0
)

@Singleton
class GemmaModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GemmaModelManager"
        private const val MODELS_DIR = "models"
        const val ESTIMATED_EMBEDDING_VRAM_MB = 450
        const val ESTIMATED_TEXT_GEN_VRAM_MB = 1000
        const val TOTAL_AVAILABLE_VRAM_MB = 1800
    }

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    private val _embeddingStatus = MutableStateFlow(ModelStatus())
    val embeddingStatus: StateFlow<ModelStatus> = _embeddingStatus.asStateFlow()

    private val _textGenStatus = MutableStateFlow(ModelStatus())
    val textGenStatus: StateFlow<ModelStatus> = _textGenStatus.asStateFlow()

    init {
        checkExistingModels()
    }

    private fun checkExistingModels() {
        ModelType.entries.forEach { type ->
            val file = getModelFile(type)
            if (file.exists() && file.length() > 0) {
                updateStatus(type, ModelStatus(state = ModelState.DOWNLOADED))
                Log.i(TAG, "${type.name} found at ${file.absolutePath} (${file.length()} bytes)")
            }
        }
    }

    fun getModelFile(type: ModelType): File = File(modelsDir, type.fileName)

    fun getModelPath(type: ModelType): String = getModelFile(type).absolutePath

    fun isModelDownloaded(type: ModelType): Boolean = getModelFile(type).let { it.exists() && it.length() > 0 }

    fun updateStatus(type: ModelType, status: ModelStatus) {
        when (type) {
            ModelType.EMBEDDING_GEMMA -> _embeddingStatus.value = status
            ModelType.GEMMA_3_1B -> _textGenStatus.value = status
        }
    }

    fun getCurrentVramUsageMb(): Int {
        var total = 0
        if (_embeddingStatus.value.state == ModelState.READY) total += ESTIMATED_EMBEDDING_VRAM_MB
        if (_textGenStatus.value.state == ModelState.READY) total += ESTIMATED_TEXT_GEN_VRAM_MB
        return total
    }

    fun canLoadModel(type: ModelType): Boolean {
        val required = when (type) {
            ModelType.EMBEDDING_GEMMA -> ESTIMATED_EMBEDDING_VRAM_MB
            ModelType.GEMMA_3_1B -> ESTIMATED_TEXT_GEN_VRAM_MB
        }
        return getCurrentVramUsageMb() + required <= TOTAL_AVAILABLE_VRAM_MB
    }

    fun releaseAll() {
        Log.w(TAG, "Releasing all models — VRAM nuke triggered")
        _embeddingStatus.value = ModelStatus(state = ModelState.RELEASED)
        _textGenStatus.value = ModelStatus(state = ModelState.RELEASED)
    }

    fun deleteModel(type: ModelType) {
        val file = getModelFile(type)
        if (file.exists()) {
            file.delete()
            updateStatus(type, ModelStatus(state = ModelState.NOT_DOWNLOADED))
            Log.i(TAG, "Deleted ${type.name}")
        }
    }
}
