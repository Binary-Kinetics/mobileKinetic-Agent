package com.mobilekinetic.agent.data.gemma

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@HiltWorker
class GemmaModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelManager: GemmaModelManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "GemmaDownloadWorker"
        const val KEY_MODEL_TYPE = "model_type"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_PROGRESS = "progress"
        const val BUFFER_SIZE = 8192
    }

    override suspend fun doWork(): Result {
        val modelTypeName = inputData.getString(KEY_MODEL_TYPE) ?: return Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val expectedSha = inputData.getString(KEY_EXPECTED_SHA256)

        val modelType = try {
            ModelType.valueOf(modelTypeName)
        } catch (e: Exception) {
            return Result.failure()
        }

        val targetFile = modelManager.getModelFile(modelType)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        modelManager.updateStatus(modelType, ModelStatus(state = ModelState.DOWNLOADING, progress = 0f))

        return try {
            val client = OkHttpClient.Builder().build()
            val startByte = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(downloadUrl)
            if (startByte > 0) {
                requestBuilder.addHeader("Range", "bytes=$startByte-")
                Log.i(TAG, "Resuming download from byte $startByte")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("Download failed: ${response.code}")
            }

            val totalBytes = response.header("Content-Length")?.toLongOrNull()?.let { it + startByte }
                ?: modelType.sizeBytes

            val body = response.body ?: throw Exception("Empty response body")
            FileOutputStream(tempFile, startByte > 0).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var downloaded = startByte

                body.byteStream().use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                        setProgress(workDataOf(KEY_PROGRESS to progress))
                        modelManager.updateStatus(modelType, ModelStatus(state = ModelState.DOWNLOADING, progress = progress))
                    }
                }
            }

            if (expectedSha != null) {
                Log.i(TAG, "Verifying SHA-256...")
                val digest = MessageDigest.getInstance("SHA-256")
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    tempFile.delete()
                    throw Exception("SHA-256 mismatch: expected=$expectedSha actual=$actualSha")
                }
            }

            tempFile.renameTo(targetFile)
            modelManager.updateStatus(modelType, ModelStatus(state = ModelState.DOWNLOADED))
            Log.i(TAG, "${modelType.name} download complete: ${targetFile.absolutePath}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${modelType.name}", e)
            modelManager.updateStatus(modelType, ModelStatus(state = ModelState.ERROR, error = e.message))
            Result.retry()
        }
    }
}
