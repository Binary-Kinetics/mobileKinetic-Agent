# Watchdog Diagnostic Integration Guide
## mK:a App-Level Implementation

This guide shows how to integrate the enhanced watchdog diagnostic logging into the mK:a app to automatically capture detailed timeout information.

---

## Overview

The current watchdog kills processes after 120s but doesn't capture diagnostic details. This integration adds comprehensive logging with:

- Full command that was executing
- stdout/stderr output before timeout
- Process state and system metrics
- Duration and exit codes
- Session and parent process tracking

---

## Files Created

1. **~/watchdog_diagnostic.py** - Python diagnostic logger
2. **~/watchdog_diagnostics.jsonl** - Machine-readable log (JSONL format)
3. **~/watchdog_summary.txt** - Human-readable summaries
4. **~/watchdog_errors.log** - Legacy simple log (kept for compatibility)
5. **~/watchdog_fixes.kt** - Kotlin code for app integration
6. **~/migrate_watchdog_logs.sh** - Migrates old log entries

---

## Integration Steps

### Step 1: Add Diagnostic Logger to App

Copy the enhanced watchdog code from `~/watchdog_fixes.kt` into the app:

**Location:** `app/src/main/kotlin/com/mobilekinetic/agent/watchdog/`

**Key Classes:**
- `SmartWatchdog` - Command-specific timeouts
- `ProcessRegistry` - Track and cleanup processes
- `PreflightChecks` - Block known hanging commands
- `SafeProcessExecutor` - Integrated execution with diagnostics

### Step 2: Modify Process Execution

Replace current process execution with `SafeProcessExecutor`:

```kotlin
// OLD CODE (current)
val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", command))
val exitCode = process.waitFor()

// NEW CODE (with diagnostics)
val watchdog = SmartWatchdog()
val registry = ProcessRegistry()
val executor = SafeProcessExecutor(watchdog, registry, sessionId)

val result = executor.execute(command)
if (result.timedOut) {
    logWatchdogTimeout(
        pid = process.pid(),
        command = command,
        description = "Command timeout",
        duration = result.duration,
        stdout = result.stdout,
        stderr = result.stderr
    )
}
```

### Step 3: Add Python Logger Call

When a timeout occurs, call the Python diagnostic logger:

```kotlin
fun logWatchdogTimeout(
    pid: Long,
    command: String,
    description: String,
    duration: Double,
    stdout: String,
    stderr: String,
    sessionId: String? = null
) {
    try {
        val pythonScript = "/data/user/0/com.mobilekinetic.agent/files/home/watchdog_diagnostic.py"

        // Escape arguments
        val escapedStdout = stdout.replace("\"", "\\\"").take(1000)
        val escapedStderr = stderr.replace("\"", "\\\"").take(1000)
        val escapedCommand = command.replace("\"", "\\\"")

        // Call Python logger
        val logCommand = arrayOf(
            "python3", pythonScript,
            pid.toString(),
            escapedCommand,
            description,
            duration.toString(),
            escapedStdout,
            escapedStderr
        )

        Runtime.getRuntime().exec(logCommand)
    } catch (e: Exception) {
        // Don't crash on logging failure
        Log.e("Watchdog", "Failed to log timeout: ${e.message}")
    }
}
```

### Step 4: Session Cleanup

Ensure sessions are cleaned up properly:

```kotlin
class ClaudeSession(val id: String) {
    private val processRegistry = ProcessRegistry()

    fun cleanup() {
        processRegistry.cleanupSession(id)
    }
}

// In activity/service lifecycle
override fun onDestroy() {
    super.onDestroy()
    currentSession?.cleanup()
}
```

### Step 5: Pre-flight Checks

Add command validation before execution:

```kotlin
fun executeCommand(command: String): ProcessResult {
    // Check if command should be blocked
    val (blocked, reason) = PreflightChecks.shouldBlock(command)

    if (blocked) {
        return ProcessResult(
            exitCode = -2,
            stdout = "",
            stderr = "Blocked: $reason",
            timedOut = false
        )
    }

    // Execute with watchdog
    return executor.execute(command)
}
```

---

## Command-Specific Timeouts

The SmartWatchdog uses different timeouts based on command type:

| Command | Timeout | Reason |
|---------|---------|--------|
| curl | 30s | HTTP requests should be fast |
| find | 60s | Filesystem searches can be slow |
| grep/rg | 45s | Code searches, medium duration |
| ls | 15s | Directory listings should be quick |
| am start | 20s | Activity launches |
| pm | 30s | Package manager operations |
| pgrep | 10s | Process searches are fast |
| default | 120s | Unknown commands get 2 minutes |

To add new command-specific timeouts, edit the `timeouts` map in `SmartWatchdog`.

---

## Blocked Commands

These commands are automatically blocked (known to hang forever):

- `/sdcard/*` - Any access to /sdcard paths
- `/storage/emulated/*` - Emulated storage paths
- `termux-*` - All Termux API commands (no IPC bridge)

To add new blocked commands, edit `PreflightChecks.shouldBlock()`.

---

## Graceful Shutdown

The watchdog attempts graceful shutdown before force-kill:

1. **75% of timeout** - Warning logged, `process.destroy()` called
2. **100% of timeout** - Force kill with `process.destroyForcibly()`

This gives processes a chance to clean up before being killed.

---

## Analysis and Debugging

### View Recent Timeouts

```bash
python3 ~/watchdog_diagnostic.py analyze
```

### Check Detailed Logs

```bash
# Machine-readable (JSONL)
cat ~/watchdog_diagnostics.jsonl | jq .

# Human-readable summaries
less ~/watchdog_summary.txt

# Legacy simple log
cat ~/watchdog_errors.log
```

### Migrate Historical Data

```bash
~/migrate_watchdog_logs.sh
```

---

## Testing

### Test the Diagnostic Logger

```bash
# Manual test
python3 ~/watchdog_diagnostic.py 12345 "test command" "test timeout" 120 "test output" "test error"

# Check it was logged
python3 ~/watchdog_diagnostic.py analyze
```

### Test Pre-flight Checks

```kotlin
// Should block
val (blocked1, reason1) = PreflightChecks.shouldBlock("ls /sdcard")
assert(blocked1 == true)

// Should allow
val (blocked2, reason2) = PreflightChecks.shouldBlock("ls /data")
assert(blocked2 == false)
```

### Test Smart Timeouts

```kotlin
val watchdog = SmartWatchdog()
assertEquals(30_000L, watchdog.getTimeout("curl http://example.com"))
assertEquals(60_000L, watchdog.getTimeout("find / -name test"))
assertEquals(120_000L, watchdog.getTimeout("unknown-command"))
```

---

## Integration Checklist

- [ ] Copy `watchdog_fixes.kt` classes to app codebase
- [ ] Replace process execution with `SafeProcessExecutor`
- [ ] Add Python logger call on timeout
- [ ] Implement session cleanup in lifecycle methods
- [ ] Add pre-flight command validation
- [ ] Test timeout detection and logging
- [ ] Test blocked command prevention
- [ ] Test graceful shutdown behavior
- [ ] Verify diagnostic logs are being written
- [ ] Run analysis to confirm pattern detection works

---

## Expected Benefits

1. **Detailed Diagnostics** - Know exactly what command hung and why
2. **Pattern Detection** - Identify which commands timeout most frequently
3. **Faster Debugging** - stdout/stderr available for hung processes
4. **Proactive Blocking** - Known bad commands rejected before execution
5. **Resource Cleanup** - Zombie processes cleaned up per session
6. **Smart Timeouts** - Commands get appropriate timeout for their type
7. **Graceful Degradation** - Processes given chance to clean up before kill

---

## Notes

- Diagnostic logger fails gracefully - won't crash app if logging fails
- All stdout/stderr truncated to 1000 chars to prevent log bloat
- System metrics (load, memory) captured at timeout for context
- Process info captured if process still alive at timeout
- JSONL format allows streaming analysis and easy parsing
- Human-readable summary for quick debugging without tools

---

## Future Enhancements

1. **Real-time Dashboard** - Web UI showing current running processes
2. **Alerting** - Notify when timeout rate exceeds threshold
3. **Auto-remediation** - Restart services that timeout repeatedly
4. **ML Pattern Detection** - Predict which commands will timeout
5. **Historical Trends** - Graph timeout frequency over time
