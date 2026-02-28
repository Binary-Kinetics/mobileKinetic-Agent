# Watchdog Diagnostic System - Quick Reference

## Files
- `~/watchdog_diagnostic.py` - Enhanced logger (Python)
- `~/watchdog_diagnostics.jsonl` - Machine-readable log
- `~/watchdog_summary.txt` - Human-readable summaries
- `~/watchdog_errors.log` - Legacy simple log
- `~/watchdog_fixes.kt` - App integration code
- `~/watchdog_integration_guide.md` - Full integration guide

## Usage

### Log a Timeout Manually
```bash
python3 ~/watchdog_diagnostic.py <pid> "<command>" "<description>" [duration] [stdout] [stderr]
```

Example:
```bash
python3 ~/watchdog_diagnostic.py 12345 "curl http://api.example.com" "API call timeout" 120 "partial output" "connection reset"
```

### Analyze Patterns
```bash
python3 ~/watchdog_diagnostic.py analyze
```

### Migrate Old Logs
```bash
~/migrate_watchdog_logs.sh
```

### View Logs
```bash
# Human-readable
less ~/watchdog_summary.txt

# Machine-readable (with jq)
cat ~/watchdog_diagnostics.jsonl | jq .

# Legacy format
cat ~/watchdog_errors.log
```

## Command Timeouts
| Command | Timeout |
|---------|---------|
| curl | 30s |
| find | 60s |
| grep/rg | 45s |
| ls | 15s |
| am start | 20s |
| pm | 30s |
| pgrep | 10s |
| default | 120s |

## Blocked Commands (Auto-rejected)
- `/sdcard/*` - Hangs forever
- `/storage/emulated/*` - Hangs forever
- `termux-*` - No IPC bridge, hangs forever

## What Gets Logged
- Timestamp (ISO format)
- PID and parent PID
- Command and description
- Duration before timeout
- stdout (first 1000 chars)
- stderr (first 1000 chars)
- Exit code
- Session ID
- Process state (if still alive)
- System load average
- Memory info

## Integration (Kotlin)
```kotlin
val watchdog = SmartWatchdog()
val registry = ProcessRegistry()
val executor = SafeProcessExecutor(watchdog, registry, sessionId)

val result = executor.execute(command)
if (result.timedOut) {
    // Automatically logged to Python diagnostic system
}
```

## Analysis Output
```
Total timeouts: 26
Time range: 2026-02-04T09:03:20 to 2026-02-04T12:55:28
Average duration: 120.0s

Command breakdown:
  curl: 8
  grep: 4
  find: 3
  Task: 2
  ...

Description breakdown:
  curl to RAG: 5
  grep command: 4
  Write operation: 3
  ...
```

## Files for Next Build
1. `~/watchdog_fixes.kt` - Copy to app source
2. `~/watchdog_diagnostic.py` - Already in home dir, app calls it
3. `~/watchdog_integration_guide.md` - Implementation reference
