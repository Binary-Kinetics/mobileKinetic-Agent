package com.mobilekinetic.agent.data.memory

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mobilekinetic.agent.data.settings.BackupSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Nightly remote backup — copies the latest local home archive to a
 * user-selected SAF folder (Google Drive, OneDrive, USB, local, etc.).
 *
 * Reads configuration from [BackupSettingsRepository]. If remote backup is
 * disabled or no URI is configured, exits immediately with success.
 * Records the outcome (time + status) back to DataStore so the settings
 * UI can display it.
 */
@HiltWorker
class RemoteBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupSettings: BackupSettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "RemoteBackupWorker"
        const val WORK_NAME = "remote_backup_nightly"
        private const val BACKUP_SUBDIR = "home/Memory/Backups"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val enabled = backupSettings.remoteBackupEnabled.first()
            if (!enabled) {
                Log.i(TAG, "Remote backup disabled, skipping")
                return@withContext Result.success()
            }

            val uriString = backupSettings.remoteBackupUri.first()
            if (uriString.isBlank()) {
                Log.w(TAG, "No remote backup URI configured, skipping")
                return@withContext Result.success()
            }

            val treeUri = Uri.parse(uriString)
            val destDir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (destDir == null || !destDir.canWrite()) {
                val msg = "Cannot write to remote backup location"
                Log.e(TAG, msg)
                backupSettings.setLastRemoteBackupResult("FAILED: $msg")
                return@withContext Result.retry()
            }

            // Find the latest local home archive
            val backupDir = File(appContext.filesDir, BACKUP_SUBDIR)
            val latestArchive = backupDir
                .listFiles { f -> f.name.startsWith("home_") && f.name.endsWith(".zip") }
                ?.maxByOrNull { it.lastModified() }

            if (latestArchive == null || !latestArchive.exists()) {
                val msg = "No local home archive found to upload"
                Log.w(TAG, msg)
                backupSettings.setLastRemoteBackupResult("SKIPPED: $msg")
                return@withContext Result.success()
            }

            // Remove existing file with same name, then create new
            destDir.findFile(latestArchive.name)?.delete()

            val remoteFile = destDir.createFile("application/zip", latestArchive.name)
            if (remoteFile == null) {
                val msg = "Failed to create remote file"
                Log.e(TAG, msg)
                backupSettings.setLastRemoteBackupResult("FAILED: $msg")
                return@withContext Result.retry()
            }

            // Stream copy local → remote via ContentResolver
            appContext.contentResolver.openOutputStream(remoteFile.uri)?.use { output ->
                latestArchive.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                val msg = "Failed to open output stream for remote file"
                Log.e(TAG, msg)
                backupSettings.setLastRemoteBackupResult("FAILED: $msg")
                return@withContext Result.retry()
            }

            // Size verification
            val remoteSize = remoteFile.length()
            val localSize = latestArchive.length()
            if (localSize > 0 && remoteSize < localSize / 2) {
                remoteFile.delete()
                val msg = "Size check failed: local=$localSize remote=$remoteSize"
                Log.e(TAG, msg)
                backupSettings.setLastRemoteBackupResult("FAILED: $msg")
                return@withContext Result.retry()
            }

            val now = System.currentTimeMillis()
            backupSettings.setLastRemoteBackupTime(now)
            backupSettings.setLastRemoteBackupResult(
                "SUCCESS: ${latestArchive.name} ($remoteSize bytes)"
            )
            Log.i(TAG, "Remote backup complete: ${latestArchive.name} → ${remoteFile.uri}")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Remote backup failed", e)
            backupSettings.setLastRemoteBackupResult("FAILED: ${e.message}")
            Result.retry()
        }
    }
}
