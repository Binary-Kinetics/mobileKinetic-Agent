package com.mobilekinetic.agent.data.memory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mobilekinetic.agent.data.settings.BackupSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 11: VPN Backup — WorkManager periodic worker.
 *
 * Copies the Room database file to a timestamped backup on external storage
 * every 3 hours. Verifies destination size is within 50% of source to guard
 * against partial writes, and rotates old copies so at most MAX_COPIES remain.
 *
 * Matches the @HiltWorker / @AssistedInject pattern used by [DecayWorker].
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupSettings: BackupSettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BackupWorker"
        const val WORK_NAME = "memory_backup_3h"

        private const val DB_NAME = "mobilekinetic_db"
        private const val BACKUP_SUBDIR = "home/Memory/Backups"
        private const val MAX_COPIES = 8
        private const val LOCKFILE_NAME = ".wipe_in_progress"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting periodic DB backup")

            val dbFile = File(appContext.filesDir, "home/Memory/Room/$DB_NAME")
            if (!dbFile.exists()) {
                Log.w(TAG, "Source DB not found at ${dbFile.absolutePath} — skipping backup")
                return@withContext Result.success()
            }

            // Abort if a wipe is in progress
            val lockFile = File(dbFile.parent, LOCKFILE_NAME)
            if (lockFile.exists()) {
                Log.w(TAG, "Wipe lockfile present — backup aborted")
                return@withContext Result.success()
            }

            val backupDir = File(appContext.filesDir, BACKUP_SUBDIR).also { it.mkdirs() }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val destFile = File(backupDir, "mobilekinetic_db_$timestamp.db")

            dbFile.copyTo(destFile, overwrite = false)

            // Size verification: dest must be at least 50% of source
            val srcSize = dbFile.length()
            val destSize = destFile.length()
            if (srcSize > 0 && destSize < srcSize / 2) {
                destFile.delete()
                val msg = "Backup size check failed: source=$srcSize dest=$destSize"
                Log.e(TAG, msg)
                return@withContext Result.retry()
            }

            rotateBackups(backupDir)

            Log.i(TAG, "Backup complete → ${destFile.absolutePath} ($destSize bytes)")

            // After local backup succeeds, zip all home subdirs and push to NAS
            try {
                val smbEnabled = backupSettings.smbBackupEnabled.first()
                if (smbEnabled) {
                    val host = backupSettings.smbHost.first()
                    val share = backupSettings.smbShare.first()
                    val path = backupSettings.smbPath.first()
                    val user = backupSettings.smbUsername.first()
                    val pass = backupSettings.smbPassword.first()
                    if (host.isNotBlank() && share.isNotBlank()) {
                        val homeDir = File(appContext.filesDir, "home")
                        val zipFile = createHomeZip(homeDir, timestamp)
                        try {
                            val transport = SmbBackupTransport(host, share, path, user, pass)
                            transport.uploadBackup(zipFile)
                            backupSettings.setLastSmbBackupTime(System.currentTimeMillis())
                            val sizeKb = zipFile.length() / 1024
                            backupSettings.setLastSmbBackupResult("SUCCESS: ${zipFile.name} (${sizeKb}KB)")
                        } finally {
                            zipFile.delete()
                            zipFile.parentFile?.let { staging ->
                                if (staging.name == "staging") staging.delete()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SMB backup transport failed (non-fatal)", e)
                try { backupSettings.setLastSmbBackupResult("FAILED: ${e.message}") } catch (_: Exception) {}
            }

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.retry()
        }
    }

    /**
     * Zip all subdirectories under [homeDir] plus critical app data paths
     * (Room database, DataStore preferences, SharedPreferences) into a
     * timestamped archive. Loose files in the home root are excluded.
     * Skips the staging directory to avoid including the zip in itself.
     */
    private fun createHomeZip(homeDir: File, timestamp: String): File {
        val stagingDir = File(homeDir, "Memory/Backups/staging").also { it.mkdirs() }
        // Clean any leftover staging zips from previous runs
        stagingDir.listFiles()?.forEach { it.delete() }

        val zipFile = File(stagingDir, "mobilekinetic_home_$timestamp.zip")
        val basePath = homeDir.toPath()

        // App data directories outside ~/home that still need backing up.
        // DataStore and triggers are now centralized under home/Memory/DataStore/
        // and home/Memory/Triggers/ respectively, so only legacy paths remain here.
        val appDataDir = appContext.filesDir.parentFile!!  // com.mobilekinetic.agent/
        val externalPaths = listOf(
            Pair(File(appDataDir, "databases"), "_app_data/databases"),
            Pair(File(appDataDir, "shared_prefs"), "_app_data/shared_prefs")
        )

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1) All subdirectories under home (not loose root files)
            val topLevelDirs = homeDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in topLevelDirs) {
                dir.walk()
                    .filter { it.isFile }
                    .filter { !it.absolutePath.startsWith(stagingDir.absolutePath) }
                    .forEach { file ->
                        val relativePath = basePath.relativize(file.toPath()).toString()
                        addFileToZip(zos, file, relativePath)
                    }
            }

            // 2) Critical app data outside home (databases, prefs, datastore)
            for ((dir, zipPrefix) in externalPaths) {
                if (!dir.exists()) continue
                dir.walk()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = "$zipPrefix/${dir.toPath().relativize(file.toPath())}"
                        addFileToZip(zos, file, relativePath)
                    }
            }
        }

        Log.i(TAG, "Created home zip: ${zipFile.name} (${zipFile.length()} bytes)")
        return zipFile
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryPath: String) {
        try {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { input -> input.copyTo(zos) }
            zos.closeEntry()
        } catch (e: Exception) {
            Log.w(TAG, "Skipping $entryPath: ${e.message}")
        }
    }

    /**
     * Keep only [MAX_COPIES] newest backups; delete the rest.
     */
    private fun rotateBackups(backupDir: File) {
        val backups = backupDir
            .listFiles { f -> f.name.startsWith("mobilekinetic_db_") && f.name.endsWith(".db") }
            ?.sortedBy { it.name }
            ?: return

        val excess = backups.size - MAX_COPIES
        if (excess > 0) {
            backups.take(excess).forEach { old ->
                val deleted = old.delete()
                Log.d(TAG, "Rotated out ${old.name} (deleted=$deleted)")
            }
        }
    }
}
