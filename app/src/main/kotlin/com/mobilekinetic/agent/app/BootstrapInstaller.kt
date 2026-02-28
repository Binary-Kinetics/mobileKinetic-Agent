package com.mobilekinetic.agent.app

import android.content.Context
import android.system.Os
import android.util.Log
import com.mobilekinetic.agent.shared.MobileKineticConstants
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Installs the Termux bootstrap environment on first run.
 *
 * The bootstrap is a ZIP file (from Termux releases) stored in app assets.
 * It contains a Debian-like Linux sysroot with bin/, lib/, etc/, share/, etc.
 *
 * Installation follows the same algorithm as Termux TermuxInstaller:
 *  1. Extract all files to a staging directory (usr-staging/)
 *  2. Parse SYMLINKS.txt to create symbolic links
 *  3. Atomically rename staging to prefix (usr/)
 *  4. Create HOME directory (home/)
 *  5. Recursively chmod all executables in bin/, lib/, libexec/
 *
 * SYMLINKS.txt uses Unicode left-arrow (U+2190) as delimiter.
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    sealed class InstallResult {
        data object AlreadyInstalled : InstallResult()
        data object Success : InstallResult()
        data class Error(val message: String, val exception: Throwable? = null) : InstallResult()
    }

    fun isInstalled(context: Context): Boolean {
        return File(getNativeLibDir(context), "libbash.so").let { it.exists() } && getPrefix(context).exists()
    }

    fun getPrefix(context: Context): File = File(context.filesDir, MobileKineticConstants.PREFIX_REL)

    fun getHome(context: Context): File = File(context.filesDir, MobileKineticConstants.HOME_REL)

    fun getNativeLibDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    /**
     * Ensures all executable files have correct permissions.
     * Call this on EVERY app launch, not just first install.
     * This handles the case where permissions were lost or the app was updated
     * from a version that didn't set permissions correctly.
     */
    fun ensurePermissions(context: Context) {
        val prefix = getPrefix(context)
        if (!prefix.exists()) return

        val executableDirs = listOf("bin", "lib", "libexec")
        for (dirName in executableDirs) {
            val dir = File(prefix, dirName)
            if (dir.exists()) {
                // Use chmodRecursive which handles both regular files and symlinks
                chmodRecursive(dir, 493) // 0755
            }
        }

        // Extra safety: explicitly chmod bash via Runtime
        val bash = File(prefix, "bin/bash")
        if (bash.exists()) {
            if (!setFilePermissions(bash.absolutePath, 493)) { // 0755
                Log.w(TAG, "ensurePermissions: Failed to set bash executable")
            }
        }

        // Also fix sh if it exists (might be a separate file, not a symlink)
        val sh = File(prefix, "bin/sh")
        if (sh.exists() && !sh.isDirectory) {
            if (!setFilePermissions(sh.absolutePath, 493)) {
                Log.w(TAG, "ensurePermissions: Failed to set sh executable")
            }
        }

        // Fix any remaining com.termux references in symlinks and config files
        createNativeLibSymlinks(context)
        fixSymlinks(prefix, context.filesDir)
        fixConfigFiles(prefix, context.filesDir)

        // Ensure apt sources.list has [trusted=yes] to bypass GPG verification
        fixAptSourcesList(prefix)

        Log.i(TAG, "ensurePermissions: Permission repair completed")
    }

    /**
     * Create symlinks from usr/bin to nativeLibraryDir executables.
     * The nativeLibraryDir path changes on every app install/update,
     * so symlinks must be recreated on every launch.
     */
    private fun createNativeLibSymlinks(context: Context) {
        val nativeLibDir = getNativeLibDir(context)
        val binDir = File(getPrefix(context), "bin")
        if (!binDir.exists()) binDir.mkdirs()

        val symlinks = mapOf(
            "bash" to "libbash.so",
            "coreutils" to "libcoreutils.so",
            "sh" to "libdash.so"
        )

        for ((linkName, soName) in symlinks) {
            val linkFile = File(binDir, linkName)
            val target = File(nativeLibDir, soName).absolutePath
            // File.exists() returns false for dangling symlinks (follows target),
            // so broken symlinks after reinstall are never deleted, causing EEXIST.
            // Files.deleteIfExists operates on the link itself, not its target.
            try {
                java.nio.file.Files.deleteIfExists(linkFile.toPath())
            } catch (e: Exception) {
                Log.w(TAG, "createNativeLibSymlinks: Failed to delete old $linkName: ${e.message}")
            }
            try {
                Os.symlink(target, linkFile.absolutePath)
                Log.i(TAG, "createNativeLibSymlinks: $linkName -> $target")
            } catch (e: Exception) {
                Log.w(TAG, "createNativeLibSymlinks: Failed $linkName: ${e.message}")
            }
        }
    }

    private fun getStagingPrefix(context: Context): File =
        File(context.filesDir, MobileKineticConstants.STAGING_PREFIX_REL)

    fun install(context: Context, onProgress: (String) -> Unit = {}): InstallResult {
        if (isInstalled(context)) {
            Log.i(TAG, "Bootstrap already installed.")
            return InstallResult.AlreadyInstalled
        }

        return try {
            doInstall(context, onProgress)
            InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap installation failed", e)
            cleanupStagingDir(context)
            InstallResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Set file permissions using Os.chmod with Runtime.exec as fallback.
     * Returns true if permissions were set successfully.
     */
    private fun setFilePermissions(path: String, mode: Int): Boolean {
        // Try Os.chmod first
        try {
            Os.chmod(path, mode)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Os.chmod failed for " + path + ": " + e.message + ", trying Runtime.exec fallback")
        }

        // Fallback: use Runtime.exec
        try {
            val modeStr = String.format("%o", mode)
            val process = Runtime.getRuntime().exec(arrayOf("chmod", modeStr, path))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return true
            } else {
                Log.w(TAG, "chmod command failed with exit code " + exitCode + " for " + path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Runtime.exec chmod failed for " + path + ": " + e.message)
        }

        // Last resort: try File.setExecutable
        try {
            val file = File(path)
            if (file.setExecutable(true, false) && file.setReadable(true, false)) {
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "File.setExecutable failed for " + path + ": " + e.message)
        }

        return false
    }

    /**
     * Recursively chmod all files in a directory to the given mode.
     * Handles both regular files AND symlinks (resolving symlink targets).
     */
    private fun chmodRecursive(dir: File, mode: Int) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                // Regular file
                if (!setFilePermissions(file.absolutePath, mode)) {
                    Log.w(TAG, "Failed to set permissions on: " + file.absolutePath)
                }
            } else if (isSymlink(file)) {
                // Symlink: chmod the symlink target so the resolved binary is executable.
                // Many usr/bin/ entries (apt, dpkg, etc.) are symlinks from SYMLINKS.txt.
                try {
                    val resolvedTarget = file.toPath().toRealPath().toFile()
                    if (resolvedTarget.isFile) {
                        if (!setFilePermissions(resolvedTarget.absolutePath, mode)) {
                            Log.w(TAG, "Failed to set permissions on symlink target: " + resolvedTarget.absolutePath + " (via " + file.absolutePath + ")")
                        }
                    }
                } catch (e: Exception) {
                    // Symlink target may not exist (dangling symlink) - skip
                    Log.w(TAG, "chmodRecursive: Could not resolve symlink: " + file.absolutePath + ": " + e.message)
                }
            }
        }
    }

    /**
     * Check if a byte array starts with ELF magic bytes (0x7F 'E' 'L' 'F').
     */
    private fun isElfBinary(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x7F.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0x4C.toByte() &&
            bytes[3] == 0x46.toByte()
    }

    @Suppress("NestedBlockDepth")
    private fun doInstall(context: Context, onProgress: (String) -> Unit) {
        val prefix = getPrefix(context)
        val stagingPrefix = getStagingPrefix(context)
        val home = getHome(context)

        onProgress("Cleaning up previous installation attempts...")
        if (stagingPrefix.exists()) {
            Log.i(TAG, "Deleting leftover staging: " + stagingPrefix.absolutePath)
            stagingPrefix.deleteRecursively()
        }

        if (prefix.exists()) {
            Log.i(TAG, "Deleting incomplete prefix: " + prefix.absolutePath)
            prefix.deleteRecursively()
        }

        onProgress("Creating directories...")
        if (!stagingPrefix.mkdirs()) {
            throw RuntimeException("Failed to create staging prefix: " + stagingPrefix.absolutePath)
        }

        onProgress("Extracting bootstrap (this may take a moment)...")
        Log.i(TAG, "Extracting bootstrap to: " + stagingPrefix.absolutePath)

        val buffer = ByteArray(8192)
        val symlinks = mutableListOf<Pair<String, String>>()

        context.assets.open(MobileKineticConstants.BOOTSTRAP_ASSET_NAME).use { assetStream ->
            ZipInputStream(assetStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    if (entryName == MobileKineticConstants.BOOTSTRAP_SYMLINKS_FILE) {
                        // CRITICAL FIX: Read entire entry as raw bytes FIRST, then
                        // parse as text from the byte array. Previously, wrapping
                        // the ZipInputStream directly in BufferedReader(InputStreamReader(zis))
                        // caused the BufferedReader to read ahead past the current entry
                        // boundary, consuming bytes from subsequent binary ZIP entries.
                        // This corrupted ELF headers making .so files unlinkable.
                        val entryBytes = zis.readBytes()
                        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(entryBytes)))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split(MobileKineticConstants.SYMLINK_DELIMITER)
                            if (parts.size != 2) {
                                throw RuntimeException("Malformed symlink line: " + line)
                            }
                            val target = parts[0]
                            val linkRel = parts[1]

                            val linkPath = if (linkRel.startsWith("./")) {
                                linkRel.substring(2)
                            } else {
                                linkRel
                            }

                            val absoluteLinkPath = File(stagingPrefix, linkPath).absolutePath
                            symlinks.add(Pair(target, absoluteLinkPath))

                            val parentDir = File(absoluteLinkPath).parentFile
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs()
                            }

                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingPrefix, entryName)

                        if (entry.isDirectory) {
                            if (!targetFile.exists()) {
                                targetFile.mkdirs()
                            }
                        } else {
                            targetFile.parentFile?.let { parent ->
                                if (!parent.exists()) parent.mkdirs()
                            }

                            // CRITICAL: Raw byte stream only - NO text encoding layers.
                            FileOutputStream(targetFile).use { fos ->
                                BufferedOutputStream(fos).use { bos ->
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } != -1) {
                                        bos.write(buffer, 0, len)
                                    }
                                }
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        onProgress("Creating symlinks (" + symlinks.size + ")...")
        Log.i(TAG, "Creating " + symlinks.size + " symlinks.")

        if (symlinks.isEmpty()) {
            throw RuntimeException("No SYMLINKS.txt encountered in bootstrap ZIP")
        }

        for ((target, linkPath) in symlinks) {
            try {
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink: " + target + " -> " + linkPath + ": " + e.message)
            }
        }

        onProgress("Setting file permissions...")
        Log.i(TAG, "Setting execute permissions on extracted files.")

        val executableDirs = listOf("bin", "lib", "libexec")
        for (dirName in executableDirs) {
            val dir = File(stagingPrefix, dirName)
            if (dir.exists()) {
                Log.i(TAG, "chmod 755 recursively: " + dir.absolutePath)
                chmodRecursive(dir, 493) // 0755
            }
        }

        val bashFile = File(stagingPrefix, "bin/bash")
        if (bashFile.exists()) {
            Log.i(TAG, "Explicitly setting bash executable: " + bashFile.absolutePath)
            if (!setFilePermissions(bashFile.absolutePath, 493)) {
                Log.e(TAG, "CRITICAL: Failed to make bash executable!")
            }
        } else {
            Log.w(TAG, "bash not found at expected location: " + bashFile.absolutePath)
        }
        onProgress("Finalizing installation...")
        Log.i(TAG, "Moving staging prefix to final prefix directory.")

        if (!stagingPrefix.renameTo(prefix)) {
            throw RuntimeException(
                "Failed to move staging prefix (" + stagingPrefix.absolutePath +
                    ") to prefix (" + prefix.absolutePath + ")"
            )
        }

        if (!home.exists()) {
            if (!home.mkdirs()) {
                throw RuntimeException("Failed to create home directory: " + home.absolutePath)
            }
        }

        val tmpDir = File(prefix, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
        }

        // Re-apply permissions on ALL executable dirs after rename.
        // Some Android filesystems/versions lose execute bits on renameTo().
        // Previously only bash was re-checked, leaving apt, dpkg, etc. broken.
        for (dirName in executableDirs) {
            val dir = File(prefix, dirName)
            if (dir.exists()) {
                Log.i(TAG, "Post-rename chmod 755 recursively: " + dir.absolutePath)
                chmodRecursive(dir, 493) // 0755
            }
        }
        // Fix hardcoded com.termux references from Termux bootstrap
        onProgress("Fixing com.termux references...")
        fixSymlinks(prefix, context.filesDir)
        fixConfigFiles(prefix, context.filesDir)

        // Add [trusted=yes] to apt sources.list to bypass GPG verification
        fixAptSourcesList(prefix)

        val fileCount = prefix.walkTopDown().count { it.isFile }
        Log.i(TAG, "Bootstrap complete. " + fileCount + " files, " + symlinks.size + " symlinks.")
        onProgress("Bootstrap installed (" + fileCount + " files, " + symlinks.size + " symlinks).")
    }

    private fun cleanupStagingDir(context: Context) {
        val stagingPrefix = getStagingPrefix(context)
        if (stagingPrefix.exists()) {
            try {
                stagingPrefix.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up staging: " + e.message)
            }
        }
    }

    fun uninstall(context: Context) {
        val prefix = getPrefix(context)
        val home = getHome(context)
        val staging = getStagingPrefix(context)

        listOf(prefix, home, staging).forEach { dir ->
            if (dir.exists()) {
                Log.i(TAG, "Deleting: " + dir.absolutePath)
                dir.deleteRecursively()
            }
        }
        Log.i(TAG, "Bootstrap uninstalled.")
    }

    /**
     * Fix symlinks that reference the old com.termux package path.
     * The Termux bootstrap contains symlinks targeting /data/data/com.termux/files/...
     * which must be rewritten to /data/data/com.mobilekinetic.agent/files/...
     */
    private fun fixSymlinks(prefix: File, filesDir: File) {
        val oldPrefix = "/data/data/com.termux/files"
        val newPrefix = filesDir.absolutePath

        prefix.walkTopDown().forEach { file ->
            if (isSymlink(file)) {
                try {
                    val target = Files.readSymbolicLink(file.toPath()).toString()
                    if (target.contains("com.termux")) {
                        val newTarget = target.replace(oldPrefix, newPrefix)
                        file.delete()
                        try {
                            Os.symlink(newTarget, file.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "fixSymlinks: Failed to recreate symlink: " + file.absolutePath + " -> " + newTarget)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fixSymlinks: Error reading symlink: " + file.absolutePath + ": " + e.message)
                }
            }
        }
        Log.i(TAG, "fixSymlinks: Completed symlink fixup")
    }

    /**
     * Fix config files and scripts that contain hardcoded com.termux references.
     * Scans etc/, share/, lib/, and bin/ for text files referencing com.termux
     * and replaces them with com.mobilekinetic.agent. The bin/ scan catches scripts
     * with shebangs like #!/data/data/com.termux/files/usr/bin/bash.
     */
    private fun fixConfigFiles(prefix: File, filesDir: File) {
        // Include "bin" so scripts with #!/data/data/com.termux/... shebangs get rewritten
        val configDirs = listOf("etc", "share", "lib", "bin")

        for (dir in configDirs) {
            val target = File(prefix, dir)
            if (target.exists()) {
                target.walkTopDown().forEach { file ->
                    if (file.isFile && !isSymlink(file) && file.length() < 100_000) {
                        try {
                            // Read raw bytes first to check for binary content
                            val bytes = file.readBytes()

                            // Skip ELF binaries - readText() would corrupt them
                            if (isElfBinary(bytes)) {
                                return@forEach
                            }

                            // Skip files with excessive non-text bytes (likely binary)
                            val nonTextCount = bytes.count { b ->
                                val unsigned = b.toInt() and 0xFF
                                unsigned < 0x09 || (unsigned in 0x0E..0x1F && unsigned != 0x1B)
                            }
                            if (bytes.isNotEmpty() && nonTextCount > bytes.size / 10) {
                                return@forEach
                            }

                            val content = String(bytes, Charsets.UTF_8)
                            if (content.contains("com.termux")) {
                                file.writeText(content.replace("com.termux", "com.mobilekinetic.agent"))
                            }
                        } catch (e: Exception) {
                            // Skip files that cannot be read
                        }
                    }
                }
            }
        }
        Log.i(TAG, "fixConfigFiles: Completed config file fixup")
    }

    /**
     * Add [trusted=yes] to apt sources.list entries to bypass GPG key verification.
     * The Termux bootstrap ships sources.list without this option, but our repackaged
     * app does not have the Termux GPG keys, causing apt update to fail with
     * "The following signatures couldn't be verified". This rewrites any plain
     * "deb https://" lines to "deb [trusted=yes] https://".
     */
    private fun fixAptSourcesList(prefix: File) {
        val sourcesFile = File(prefix, "etc/apt/sources.list")
        if (!sourcesFile.exists()) {
            Log.w(TAG, "fixAptSourcesList: sources.list not found")
            return
        }

        try {
            val content = sourcesFile.readText()
            // Only rewrite if not already patched — avoid double-applying
            if (content.contains("[trusted=yes]")) {
                Log.i(TAG, "fixAptSourcesList: Already patched, skipping")
                return
            }

            // Match "deb http" that does NOT already have a [...] options block
            // This handles both "deb https://..." and "deb http://..."
            val patched = content.replace(
                Regex("""^(deb\s+)(https?://)""", RegexOption.MULTILINE),
                "$1[trusted=yes] $2"
            )

            if (patched != content) {
                sourcesFile.writeText(patched)
                Log.i(TAG, "fixAptSourcesList: Added [trusted=yes] to sources.list")
            } else {
                Log.i(TAG, "fixAptSourcesList: No changes needed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixAptSourcesList: Failed to patch sources.list: " + e.message)
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (e: Exception) {
            false
        }
    }

    fun getEnvironment(context: Context): Map<String, String> {
        val prefix = getPrefix(context)
        val home = getHome(context)
        val filesDir = context.filesDir
        val nativeLibDir = getNativeLibDir(context)

        return mapOf(
            "HOME" to home.absolutePath,
            "PREFIX" to prefix.absolutePath,
            "TMPDIR" to File(prefix, "tmp").absolutePath,
            "PATH" to (nativeLibDir + ":" + File(prefix, "bin").absolutePath + ":" + File(prefix, "bin/applets").absolutePath),
            "LD_LIBRARY_PATH" to (nativeLibDir + ":" + File(prefix, "lib").absolutePath),
            // W^X workaround: intercept execve() to route ELF binaries in /data/
            // through /system/bin/linker64 (Android 15+ / API 36)
            "LD_PRELOAD" to File(nativeLibDir, "libtermux-exec.so").absolutePath,
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "SHELL" to File(nativeLibDir, "libbash.so").absolutePath,
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "TERMUX_VERSION" to MobileKineticConstants.VERSION,
            "TERMUX_APP__PACKAGE_NAME" to MobileKineticConstants.PACKAGE_NAME,
            "TERMUX_APP__FILES_DIR" to filesDir.absolutePath,
            "BASH_ENV" to File(prefix, "etc/bash.bashrc").absolutePath,
            "ENV" to File(prefix, "etc/profile").absolutePath,
        )
    }
}
