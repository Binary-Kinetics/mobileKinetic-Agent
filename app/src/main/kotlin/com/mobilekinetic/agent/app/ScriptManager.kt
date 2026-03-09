package com.mobilekinetic.agent.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import android.content.pm.ApplicationInfo

/**
 * ScriptManager extracts bundled Python/shell scripts from assets to the app's
 * home directory on first run or app update. Also merges MCP server configuration
 * into ~/.claude/settings.json so the Claude CLI discovers and launches MCP servers.
 */
object ScriptManager {

    private const val TAG = "ScriptManager"
    private const val SCRIPTS_ASSET_DIR = "scripts"
    private const val VERSION_FILE = ".scripts_version"
    private const val MCP_CONFIG_ASSET = "scripts/config/mcp_config.json"
    private const val CLAUDE_SETTINGS_REL = ".claude/settings.json"

    /**
     * Main entry point. Call from MobileKineticService.onCreate() after servers are
     * initialized but before ClaudeCodeManager.start().
     */
    fun init(context: Context) {
        val home = File(context.filesDir, "home")
        val currentVersion = getAppVersionCode(context)
        val installedVersion = readInstalledVersion(home)

        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebug && currentVersion == installedVersion) {
            Log.i(TAG, "Scripts already at version $currentVersion, skipping extraction")
            return
        }
        if (isDebug) {
            Log.i(TAG, "Debug build: forcing script extraction")
        }

        Log.i(TAG, "Deploying scripts: installed=$installedVersion, current=$currentVersion")

        // Extract all scripts from assets
        extractAssets(context, SCRIPTS_ASSET_DIR, home)

        // Set execute permissions on .py and .sh files
        setExecutePermissions(home)

        // Merge MCP config into Claude CLI settings
        mergeMcpConfig(context, home)

        // Stamp version
        writeVersion(home, currentVersion)

        Log.i(TAG, "Script deployment complete (version $currentVersion)")
    }

    private fun getAppVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get package info", e)
            -1L
        }
    }

    private fun readInstalledVersion(home: File): Long {
        val versionFile = File(home, VERSION_FILE)
        return try {
            if (versionFile.exists()) versionFile.readText().trim().toLong() else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun writeVersion(home: File, version: Long) {
        try {
            File(home, VERSION_FILE).writeText(version.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write version stamp", e)
        }
    }

    /**
     * Recursively extracts assets from [assetSubDir] into [targetDir].
     */
    private fun extractAssets(context: Context, assetSubDir: String, targetDir: File) {
        val assetManager = context.assets
        val entries: Array<String>

        try {
            entries = assetManager.list(assetSubDir) ?: return
        } catch (e: IOException) {
            Log.e(TAG, "Failed to list assets in $assetSubDir", e)
            return
        }

        if (entries.isEmpty()) {
            // This is a file, not a directory — copy it
            copyAssetFile(context, assetSubDir, targetDir)
            return
        }

        // This is a directory — recurse
        for (entry in entries) {
            val childAssetPath = "$assetSubDir/$entry"
            // Determine target: strip the leading "scripts/" prefix for the target path
            val relativePath = childAssetPath.removePrefix("$SCRIPTS_ASSET_DIR/")
            val childTarget: File

            // Check if child is a directory or file
            val childEntries = try {
                assetManager.list(childAssetPath)
            } catch (e: IOException) {
                null
            }

            if (childEntries != null && childEntries.isNotEmpty()) {
                // It's a directory — recurse into it
                childTarget = File(targetDir, relativePath.substringBefore("/"))
                extractAssets(context, childAssetPath, targetDir)
            } else {
                // It's a file — copy it
                copyAssetFile(context, childAssetPath, targetDir)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, homeDir: File) {
        val relativePath = assetPath.removePrefix("$SCRIPTS_ASSET_DIR/")
        val targetFile = File(homeDir, relativePath)

        try {
            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Extracted: $relativePath")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract $assetPath to $targetFile", e)
        }
    }

    /**
     * Recursively sets execute permission on all .py and .sh files under [dir].
     */
    private fun setExecutePermissions(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile && (file.name.endsWith(".py") || file.name.endsWith(".sh"))) {
                val success = file.setExecutable(true, false)
                if (success) {
                    Log.d(TAG, "chmod +x: ${file.name}")
                } else {
                    Log.w(TAG, "Failed to set executable: ${file.name}")
                }
            }
        }
    }

    /**
     * Merges MCP server definitions from the bundled config into
     * ~/.claude/settings.json, preserving any existing user settings.
     */
    private fun mergeMcpConfig(context: Context, home: File) {
        val claudeDir = File(home, ".claude")
        val settingsFile = File(claudeDir, "settings.json")

        try {
            // Read bundled MCP config from assets
            val mcpConfigStr = context.assets.open(MCP_CONFIG_ASSET).bufferedReader().readText()
            val mcpConfig = JSONObject(mcpConfigStr)

            // Rewrite hardcoded paths to actual filesDir
            val actualFilesDir = context.filesDir.absolutePath + "/"
            val mcpConfigFixed = JSONObject(
                mcpConfigStr.replace("/data/user/0/com.mobilekinetic.agent/files/", actualFilesDir)
                    .replace("/data/data/com.mobilekinetic.agent/files/", actualFilesDir)
            )

            // Read existing settings (or create empty)
            claudeDir.mkdirs()
            val existingSettings = if (settingsFile.exists()) {
                try {
                    JSONObject(settingsFile.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Existing settings.json was malformed, starting fresh", e)
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            // Deep-merge mcpServers
            val existingServers = if (existingSettings.has("mcpServers")) {
                existingSettings.getJSONObject("mcpServers")
            } else {
                JSONObject()
            }

            val newServers = mcpConfigFixed.getJSONObject("mcpServers")
            val serverNames = newServers.keys()
            while (serverNames.hasNext()) {
                val name = serverNames.next()
                existingServers.put(name, newServers.getJSONObject(name))
            }

            existingSettings.put("mcpServers", existingServers)

            // Write merged settings
            settingsFile.writeText(existingSettings.toString(2))
            Log.i(TAG, "MCP config merged into ${settingsFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge MCP config", e)
        }
    }
}
