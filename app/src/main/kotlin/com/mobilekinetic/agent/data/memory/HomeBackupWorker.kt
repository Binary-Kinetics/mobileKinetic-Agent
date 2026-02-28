package com.mobilekinetic.agent.data.memory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
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
 * Rolling local backup of the entire home directory.
 *
 * Creates a standard zip archive of filesDir/home/ (excluding caches and
 * regenerable content) every 6 hours. Keeps [MAX_COPIES] most recent archives
 * and rotates older ones. Coexists with [BackupWorker] which handles the
 * faster 3-hour Room DB-only backup cycle.
 */
@HiltWorker
class HomeBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HomeBackupWorker"
        const val WORK_NAME = "home_backup_6h"

        private const val HOME_SUBDIR = "home"
        private const val BACKUP_SUBDIR = "home/Memory/Backups"
        private const val MAX_COPIES = 4
        private const val LOCKFILE_NAME = ".wipe_in_progress"

        /** Directories to skip — caches, venvs, and the backup dir itself. */
        private val EXCLUDED_DIRS = setOf(
            ".cache",
            ".npm",
            "__pycache__",
            ".rlm_venvs",
            ".termux",
            "visualizer",
            "Backups"       // Memory/Backups — don't back up backups
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting home directory backup")

            val homeDir = File(appContext.filesDir, HOME_SUBDIR)
            if (!homeDir.exists()) {
                Log.w(TAG, "Home directory not found — skipping")
                return@withContext Result.success()
            }

            // Abort if a wipe is in progress
            val lockFile = File(homeDir, "Memory/Room/$LOCKFILE_NAME")
            if (lockFile.exists()) {
                Log.w(TAG, "Wipe lockfile present — backup aborted")
                return@withContext Result.success()
            }

            val backupDir = File(appContext.filesDir, BACKUP_SUBDIR).also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(backupDir, "home_$timestamp.zip")

            // Write the zip — standard deflate compression
            var fileCount = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                homeDir.walkTopDown()
                    .onEnter { dir ->
                        // Skip excluded directories
                        dir.name !in EXCLUDED_DIRS
                    }
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = file.relativeTo(homeDir).path
                        try {
                            zos.putNextEntry(ZipEntry(relativePath))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            fileCount++
                        } catch (e: Exception) {
                            // Skip unreadable files (e.g. locked DB WAL), don't fail entire backup
                            Log.w(TAG, "Skipping unreadable file: $relativePath — ${e.message}")
                        }
                    }
            }

            // Sanity check — zip should contain at least some files
            val zipSize = zipFile.length()
            if (fileCount == 0 || zipSize < 1024) {
                zipFile.delete()
                Log.e(TAG, "Backup produced empty or tiny zip ($fileCount files, $zipSize bytes)")
                return@withContext Result.retry()
            }

            rotateBackups(backupDir)

            Log.i(TAG, "Home backup complete → ${zipFile.name} ($fileCount files, $zipSize bytes)")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Home backup failed", e)
            Result.retry()
        }
    }

    /**
     * Keep only [MAX_COPIES] newest home_*.zip archives; delete the rest.
     */
    private fun rotateBackups(backupDir: File) {
        val archives = backupDir
            .listFiles { f -> f.name.startsWith("home_") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?: return

        val excess = archives.size - MAX_COPIES
        if (excess > 0) {
            archives.take(excess).forEach { old ->
                val deleted = old.delete()
                Log.d(TAG, "Rotated out ${old.name} (deleted=$deleted)")
            }
        }
    }
}
