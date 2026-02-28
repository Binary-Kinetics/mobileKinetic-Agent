package com.mobilekinetic.agent.data.gemma

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GemmaModelDownloader"
    }

    fun enqueueDownload(modelType: ModelType, downloadUrl: String, expectedSha256: String? = null) {
        val workData = workDataOf(
            GemmaModelDownloadWorker.KEY_MODEL_TYPE to modelType.name,
            GemmaModelDownloadWorker.KEY_DOWNLOAD_URL to downloadUrl,
            GemmaModelDownloadWorker.KEY_EXPECTED_SHA256 to expectedSha256
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<GemmaModelDownloadWorker>()
            .setInputData(workData)
            .setConstraints(constraints)
            .addTag("gemma_download_${modelType.name}")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "download_${modelType.name}",
                ExistingWorkPolicy.KEEP,
                request
            )

        Log.i(TAG, "Enqueued download for ${modelType.name} from $downloadUrl")
    }

    fun cancelDownload(modelType: ModelType) {
        WorkManager.getInstance(context).cancelUniqueWork("download_${modelType.name}")
        Log.i(TAG, "Cancelled download for ${modelType.name}")
    }
}
