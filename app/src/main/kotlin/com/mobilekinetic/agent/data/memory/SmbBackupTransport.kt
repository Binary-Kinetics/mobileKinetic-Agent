package com.mobilekinetic.agent.data.memory

import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.msdtyp.AccessMask
import java.io.File
import java.util.EnumSet

/**
 * SMB backup transport with configurable connection settings.
 *
 * All credentials stay on-device in DataStore; nothing leaves the app.
 * Supports both guest and authenticated connections.
 */
class SmbBackupTransport(
    private val host: String,
    private val shareName: String,
    private val backupDir: String = "mK:a_backups",
    private val username: String = "",
    private val password: String = ""
) {
    companion object {
        private const val TAG = "SmbBackupTransport"
    }

    private fun buildAuthContext(): AuthenticationContext {
        return if (username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            // smbj expects domain as separate arg; extract if user typed DOMAIN\user
            val parts = username.split("\\", limit = 2)
            if (parts.size == 2) {
                AuthenticationContext(parts[1], password.toCharArray(), parts[0])
            } else {
                AuthenticationContext(username, password.toCharArray(), "")
            }
        }
    }

    fun uploadBackup(localFile: File) {
        if (!localFile.exists()) {
            Log.w(TAG, "Local backup file does not exist: ${localFile.absolutePath}")
            return
        }

        val client = SMBClient()
        try {
            val connection = client.connect(host)
            try {
                val session = connection.authenticate(buildAuthContext())
                try {
                    val share = session.connectShare(shareName) as DiskShare
                    try {
                        // Create backup directory if it doesn't exist
                        try {
                            share.openDirectory(
                                backupDir,
                                EnumSet.of(AccessMask.GENERIC_ALL),
                                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN_IF,
                                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                            ).close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not create/open backup dir, attempting write anyway", e)
                        }

                        val remotePath = "$backupDir/${localFile.name}"
                        val remoteFile = share.openFile(
                            remotePath,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null
                        )
                        try {
                            val outputStream = remoteFile.outputStream
                            localFile.inputStream().use { input ->
                                outputStream.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.i(TAG, "Successfully uploaded ${localFile.name} to NAS ($host/$shareName/$remotePath)")
                        } finally {
                            remoteFile.close()
                        }
                    } finally {
                        share.close()
                    }
                } finally {
                    session.close()
                }
            } finally {
                connection.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB upload failed for ${localFile.name}: ${e.message}", e)
            throw e
        } finally {
            client.close()
        }
    }

    /**
     * Upload only the latest backup file (most recent by modified time).
     */
    fun uploadLatest(sourceDir: File) {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            Log.w(TAG, "Backup directory does not exist: ${sourceDir.absolutePath}")
            return
        }
        val latest = sourceDir.listFiles { f -> f.isFile }
            ?.maxByOrNull { it.lastModified() }
        if (latest != null) {
            uploadBackup(latest)
        } else {
            Log.w(TAG, "No files found in ${sourceDir.absolutePath}")
        }
    }

    fun uploadAll(sourceDir: File) {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            Log.w(TAG, "Backup directory does not exist: ${sourceDir.absolutePath}")
            return
        }
        val files = sourceDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile) {
                try {
                    uploadBackup(file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload ${file.name}, continuing with next", e)
                }
            }
        }
    }

    /**
     * Quick connectivity test -- connects, authenticates, opens the share.
     * Returns null on success or an error message on failure.
     */
    fun testConnection(): String? {
        val client = SMBClient()
        return try {
            val connection = client.connect(host)
            try {
                val session = connection.authenticate(buildAuthContext())
                try {
                    val share = session.connectShare(shareName) as DiskShare
                    share.close()
                    session.close()
                    connection.close()
                    null // success
                } catch (e: Exception) {
                    "Share error: ${e.message}"
                }
            } catch (e: Exception) {
                "Auth error: ${e.message}"
            }
        } catch (e: Exception) {
            "Connection error: ${e.message}"
        } finally {
            client.close()
        }
    }
}
