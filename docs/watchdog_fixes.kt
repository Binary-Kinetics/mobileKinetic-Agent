/**
 * Watchdog Timeout Fixes for mK:a
 * =======================================
 * Solutions to prevent child process hangs and improve process management
 */

package com.mobilekinetic.agent

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ============================================================================
// 1. Smart Watchdog with Command-Specific Timeouts
// ============================================================================

class SmartWatchdog {
    private val timeouts = mapOf(
        "curl" to 30_000L,          // 30s for HTTP requests
        "find" to 60_000L,          // 60s for filesystem searches
        "grep" to 45_000L,          // 45s for code searches
        "rg" to 45_000L,            // 45s for ripgrep
        "ls" to 15_000L,            // 15s for directory listings
        "am start" to 20_000L,      // 20s for activity launches
        "pm" to 30_000L,            // 30s for package manager
        "pgrep" to 10_000L,         // 10s for process search
        "default" to 120_000L       // 120s default
    )

    fun getTimeout(command: String): Long {
        return timeouts.entries
            .firstOrNull { command.trim().startsWith(it.key) }
            ?.value ?: timeouts["default"]!!
    }

    // Graceful shutdown: warn at 75%, kill at 100%
    suspend fun monitorProcess(
        process: Process,
        command: String,
        onWarning: () -> Unit = {},
        onKill: () -> Unit = {}
    ): Int {
        val timeout = getTimeout(command)
        val warningTime = (timeout * 0.75).toLong()

        return withTimeoutOrNull(timeout) {
            // Start warning timer
            launch {
                delay(warningTime)
                if (process.isAlive) {
                    onWarning()
                    // Try graceful termination
                    process.destroy()
                }
            }

            // Wait for process
            process.waitFor()
        } ?: run {
            // Timeout - force kill
            onKill()
            process.destroyForcibly()
            -1 // Timeout exit code
        }
    }
}

// ============================================================================
// 2. Process Registry & Cleanup
// ============================================================================

class ProcessRegistry {
    private val processes = ConcurrentHashMap<String, ProcessInfo>()

    data class ProcessInfo(
        val pid: Long,
        val command: String,
        val startTime: Long,
        val process: Process,
        val sessionId: String
    )

    fun register(sessionId: String, command: String, process: Process): String {
        val id = "${sessionId}_${System.currentTimeMillis()}"
        val pid = process.pid()

        processes[id] = ProcessInfo(
            pid = pid,
            command = command,
            startTime = System.currentTimeMillis(),
            process = process,
            sessionId = sessionId
        )

        return id
    }

    fun unregister(id: String) {
        processes.remove(id)
    }

    fun cleanupSession(sessionId: String) {
        val toClean = processes.filter { it.value.sessionId == sessionId }

        toClean.forEach { (id, info) ->
            if (info.process.isAlive) {
                info.process.destroyForcibly()
                logWatchdog(info.pid, "Session cleanup: ${info.command}")
            }
            processes.remove(id)
        }
    }

    fun getRunningProcesses(): List<ProcessInfo> {
        return processes.values.filter { it.process.isAlive }
    }
}

// ============================================================================
// 3. Pre-flight Checks
// ============================================================================

object PreflightChecks {
    private val blockedPaths = setOf(
        "/sdcard",
        "/storage/emulated"
    )

    private val blockedCommands = setOf(
        "termux-battery-status",
        "termux-location",
        "termux-",  // Block all termux-* commands
    )

    fun shouldBlock(command: String): Pair<Boolean, String?> {
        // Check blocked commands
        blockedCommands.forEach { blocked ->
            if (command.contains(blocked)) {
                return true to "Blocked command: $blocked (known to hang)"
            }
        }

        // Check blocked paths for find/ls/grep
        if (command.matches(Regex("(find|ls|grep).*"))) {
            blockedPaths.forEach { path ->
                if (command.contains(path)) {
                    return true to "Blocked path: $path (known to hang)"
                }
            }
        }

        // Check if Tier 2 is running before curl
        if (command.contains("curl") && command.contains("5564")) {
            if (!isTier2Running()) {
                return true to "Tier 2 API not running on port 5564"
            }
        }

        return false to null
    }

    private fun isTier2Running(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("pgrep -f device_api_mcp")
            process.waitFor(2, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// 4. Improved Process Execution
// ============================================================================

class SafeProcessExecutor(
    private val watchdog: SmartWatchdog,
    private val registry: ProcessRegistry,
    private val sessionId: String
) {

    suspend fun execute(command: String): ProcessResult {
        // Pre-flight checks
        val (blocked, reason) = PreflightChecks.shouldBlock(command)
        if (blocked) {
            return ProcessResult(
                exitCode = -2,
                stdout = "",
                stderr = "Command blocked: $reason",
                timedOut = false
            )
        }

        // Start process
        val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", command))
        val processId = registry.register(sessionId, command, process)

        return try {
            // Monitor with smart watchdog
            var killed = false
            val exitCode = watchdog.monitorProcess(
                process = process,
                command = command,
                onWarning = {
                    // Log warning
                    logWatchdog(process.pid(), "Warning: approaching timeout for: $command")
                },
                onKill = {
                    killed = true
                    logWatchdog(process.pid(), "Killed: timeout for: $command")
                }
            )

            // Read output
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            ProcessResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                timedOut = killed
            )
        } finally {
            registry.unregister(processId)
        }
    }

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    )
}

// ============================================================================
// 5. Logging Utility
// ============================================================================

private val watchdogLogPath = "/data/user/0/com.mobilekinetic.agent/files/home/watchdog_errors.log"

fun logWatchdog(pid: Long, message: String) {
    try {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val logEntry = "[$timestamp] PID: $pid - $message\n"

        java.io.File(watchdogLogPath).appendText(logEntry)
    } catch (e: Exception) {
        // Fail silently - don't crash on logging
    }
}

// ============================================================================
// 6. Usage Example
// ============================================================================

/*
class ClaudeCodeInterface {
    private val watchdog = SmartWatchdog()
    private val registry = ProcessRegistry()

    suspend fun executeCommand(sessionId: String, command: String): String {
        val executor = SafeProcessExecutor(watchdog, registry, sessionId)

        val result = executor.execute(command)

        return when {
            result.timedOut -> "Command timed out and was killed"
            result.exitCode == -2 -> result.stderr // Blocked command
            result.exitCode != 0 -> "Error (${result.exitCode}): ${result.stderr}"
            else -> result.stdout
        }
    }

    fun cleanupSession(sessionId: String) {
        registry.cleanupSession(sessionId)
    }
}
*/
