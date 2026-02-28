# mK:a App Improvements for Next Build
**Date:** 2026-02-04
**Device:** Pixel device
**Current Build:** Unknown (needs version tracking)

---

## Critical Improvements Needed

### 1. Watchdog Diagnostic System (PRIORITY 1 - CRITICAL)

**Problem:**
- 28+ watchdog timeouts occurred today
- Child processes hang and block pipeline for 120 seconds
- No diagnostic information captured - can't debug what's hanging

**Solution:**
Integration code ready in: `~/watchdog_fixes.kt`

**What it does:**
- **SmartWatchdog** - Command-specific timeouts instead of blanket 120s
  - curl: 30s
  - find: 60s
  - grep/rg: 45s
  - ls: 15s
  - am start: 20s
  - pm: 30s
  - pgrep: 10s
  - default: 120s

- **PreflightChecks** - Blocks known hanging commands BEFORE execution
  - `/sdcard/*` paths (hang forever)
  - `/storage/emulated/*` paths (hang forever)
  - `termux-*` commands (no IPC bridge, hang forever)

- **ProcessRegistry** - Track and cleanup processes per session
  - Prevents zombie processes
  - Session cleanup on destroy
  - Parent/child process tracking

- **SafeProcessExecutor** - Integrated execution with diagnostics
  - Graceful shutdown at 75% timeout (process.destroy())
  - Force kill at 100% timeout (process.destroyForcibly())
  - Calls `~/watchdog_diagnostic.py` to log full details

**Diagnostic Logging Captures:**
- Full command that was executing
- stdout (first 1000 chars)
- stderr (first 1000 chars)
- Duration before timeout
- Exit code
- PID and parent PID
- Session ID
- Process state (if still alive)
- System load average
- Memory usage at time of timeout

**Files:**
- `~/watchdog_fixes.kt` - Copy to `app/src/main/kotlin/com/mobilekinetic/agent/watchdog/`
- `~/watchdog_diagnostic.py` - Already in home dir, app calls it
- `~/watchdog_integration_guide.md` - Full implementation guide
- `~/WATCHDOG_QUICKREF.md` - Quick reference

**Integration Guide:**
See `~/watchdog_integration_guide.md` for step-by-step Kotlin integration

---

### 2. Biometric Authentication for Secure Vault (PRIORITY 2)

**Current State:**
- `~/secure_vault.py` works but uses master password
- Stores encrypted credentials (AES-256, PBKDF2HMAC, 100k iterations)
- Storage at `~/.secure/vault/` with 700 permissions

**Improvement Needed:**
- Replace master password with Android BiometricPrompt API
- Store encrypted key in Android Keystore after biometric auth
- Fallback to password if no biometric hardware available

**Implementation:**
```kotlin
// Add to app
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager

fun authenticateWithBiometric(onSuccess: (key: ByteArray) -> Unit) {
    val biometricPrompt = BiometricPrompt(
        this,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Retrieve key from Android Keystore
                val key = retrieveKeyFromKeystore()
                onSuccess(key)
            }

            override fun onAuthenticationFailed() {
                // Fallback to password prompt
                promptForPassword()
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Secure Vault")
        .setSubtitle("Use fingerprint to access encrypted credentials")
        .setNegativeButtonText("Use Password")
        .build()

    biometricPrompt.authenticate(promptInfo)
}
```

**Benefits:**
- More secure (no password to type/intercept)
- Better UX (quick fingerprint unlock)
- Hardware-backed encryption via Keystore

---

## Supporting Files Already Created

### Build Management System
- `~/promote_build.sh` - Promotes staging to production with backups
- `~/build_promotion_template.md` - Checklist for promotions
- `~/mK:a/` directory structure:
  - `production/` - Last known good source (never edited)
  - `staging/` - Development workspace
  - `backups/` - Timestamped production backups

### Knowledge Base Backup
- `~/backup_knowledge.sh` - Backs up RAG + configs + vault + source
- `~/restore_knowledge.sh` - Restores from backup
- Supports: local, network share, cloud sync, git repo

### Secure Credential Storage
- `~/secure_vault.py` - Working encrypted vault
  - Commands: store, get, list, delete
  - AES-256 encryption
  - Ready to add biometric auth

---

## Current Timeout Statistics

**Total logged timeouts:** 28
**Time range:** 2026-02-04 09:03:20 to 13:02:06
**Average duration:** 120.0s (all hitting max timeout)

**Command breakdown:**
- curl: 8 timeouts
- grep: 4 timeouts
- find: 3 timeouts
- Task tool: 3 timeouts
- Write operations: 5 timeouts
- Tasker: 2 timeouts
- Other: 3 timeouts

**Most common descriptions:**
- "curl to RAG": 5 occurrences
- "grep command": 4 occurrences
- "Write operation": 5 occurrences

---

## Implementation Priority

1. **Watchdog diagnostic system** (CRITICAL - blocking workflow daily)
2. **Biometric vault authentication** (Nice to have - improves security/UX)

---

## Testing Checklist After Integration

### Watchdog System
- [ ] Verify command-specific timeouts work
- [ ] Test blocked commands are rejected (try `ls /sdcard`)
- [ ] Confirm diagnostic logs are written to `~/watchdog_diagnostics.jsonl`
- [ ] Test session cleanup on app destroy
- [ ] Run `python3 ~/watchdog_diagnostic.py analyze` to see patterns

### Biometric Auth
- [ ] Test fingerprint unlock flow
- [ ] Test fallback to password when biometric fails
- [ ] Verify key stored in Android Keystore
- [ ] Test on device without biometric hardware

---

## Files to Copy to Desktop for Development

**Critical files:**
1. `~/watchdog_fixes.kt` - Main integration code
2. `~/watchdog_integration_guide.md` - Implementation guide
3. `~/watchdog_diagnostic.py` - Diagnostic logger (reference)

**Supporting files:**
4. `~/secure_vault.py` - Current vault implementation
5. `~/WATCHDOG_QUICKREF.md` - Quick reference

---

## Notes

- All diagnostic logging fails gracefully - won't crash app
- Watchdog timeouts are killing productivity - fix ASAP
- Biometric auth can wait for next iteration if needed
- Three-tier build system (production/staging/runtime) is ready to use
- Backup system tested and working

---

**Last Updated:** 2026-02-04 13:10:00
**Created By:** Claude (mK:a session)
