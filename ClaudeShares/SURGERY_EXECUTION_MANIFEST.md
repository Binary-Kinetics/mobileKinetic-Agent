# mK:a Surgery Execution Manifest

**Generated**: 2026-02-23
**Compiler**: Claude Opus 4.6 (Planning Agent)
**Input**: 8 quickwin surgeries + 24 api surgeries = 32 total
**After dedup**: 30 unique surgeries (2 overlaps resolved)

---

## Overlap Analysis

### OVERLAP 1: `/accounts` endpoint
| Aspect | Quickwin (surgery_accounts_logcat.md) | API (wave2_accounts.md) |
|--------|---------------------------------------|-------------------------|
| Kotlin handler | YES -- full handler code | YES -- full handler code |
| MCP tool | YES -- `device_accounts_list` | YES -- `device_accounts_list` |
| Manifest perm | YES -- `GET_ACCOUNTS` | YES -- `GET_ACCOUNTS` |
| Bundled with | `/logcat` endpoint | Standalone |
| Detail level | MORE DETAIL (bundled with logcat, includes route table entries, line numbers) | Less detail |
| **VERDICT** | **USE QUICKWIN** -- more detailed, also includes `/logcat` which API package lacks |

### OVERLAP 2: `/camera/status` endpoint
| Aspect | Quickwin (surgery_phantom_camera_biometric.md) | API (wave2_camerax.md) |
|--------|------------------------------------------------|------------------------|
| Implementation | Camera2-based enumeration (standalone) | CameraX-based (depends on `cameraProvider` field from /camera/photo migration) |
| Dependencies | NONE | Depends on CameraX migration (/camera/photo must run first) |
| Scope | Also includes `/biometric/status` | Part of full CameraX migration chain |
| **VERDICT** | **USE QUICKWIN for Wave 2** (standalone, no deps). **API version supersedes in Wave 7** when CameraX migration rewrites camera handlers. The quickwin version works NOW; the API version is the final form after CameraX migration. |

### OVERLAP 3: `/camera/video/start` and `/camera/video/stop` phantom fixes
| Aspect | Quickwin (surgery_phantom_camera_video.md) | API (wave2_camerax.md Section 11) |
|--------|-------------------------------------------|-----------------------------------|
| Kotlin side | YES -- adds `handleCameraVideoStartDirect()` wrapper + modifies existing handler | NO Kotlin changes (says Kotlin side is correct) |
| MCP side | NO MCP changes (says tools already exist) | YES -- fixes MCP URLs from `/camera/video/start` to `/camera/video` |
| **CONFLICT** | The two packages have **opposite views** on where the bug is |
| **VERDICT** | **USE QUICKWIN for Wave 2** (adds missing Kotlin handlers for the split URL pattern `/camera/video/start` and `/camera/video/stop`). The API package's MCP URL fixes become relevant only AFTER CameraX migration in Wave 7 changes the URL scheme. Apply quickwin Kotlin fixes now; defer API MCP fixes to Wave 7. |

### NO OVERLAP (confirmed)
- `/logcat` -- quickwin only
- `/gnss/status`, `/input/keyevent` -- quickwin only
- PackageManager 8 endpoints -- quickwin only
- Force Close, Attribution Tag, Agent Threading -- quickwin only
- `/browser/open`, `/browser/prefetch` -- API only
- `/mms/list`, `/mms/read`, `/mms/send` -- API only
- `/notification/send`, `/notifications/active` -- API only
- `/sensors/read`, `/sensors/orientation`, `/sensors/trigger` -- API only
- `/share`, `/share/received` -- API only
- `/bluetooth/headset/status`, `/bluetooth/headset/voice_recognition` -- API only
- `/intent/broadcast`, `/intent/activity` -- API only
- `/camera/photo`, `/camera/video`, `/camera/analyze`, `/camera/list` (CameraX migration) -- API only

---

## Target Files Summary

| File | Size | RLM Required | Surgeries Touching It |
|------|------|-------------|----------------------|
| `DeviceApiServer.kt` | ~131KB, ~6900 lines | **YES** | Waves 2, 3, 4, 5, 7 (14+ surgeries) |
| `unified_device_mcp.py` | ~2588 lines | **YES** | Waves 2, 3, 4, 5, 6, 7 (12+ surgeries) |
| `agent_orchestrator.py` | ~1300 lines | Maybe | Wave 1 (1 surgery) |
| `SettingsScreen.kt` | ~400 lines | No | Wave 1 (1 surgery) |
| `AndroidManifest.xml` | ~60 lines | No | Waves 0, 3 (2 surgeries) |
| `strings.xml` | ~30 lines | No | Wave 0 (1 surgery) |
| `build.gradle.kts` | ~100 lines | No | Wave 0 (1 surgery) |
| `MobileKineticNotificationListener.kt` | ~150 lines | No | Wave 5 (1 surgery) |
| `MainActivity.kt` | ~200 lines | No | Wave 5 (1 surgery, /share/received) |
| `res/xml/file_paths.xml` | NEW FILE | No | Wave 6 (1 surgery, /mms/send) |

---

## Execution Waves

### Wave 0: Infrastructure
**Purpose**: Gradle deps, manifest setup, permission additions
**Dependencies**: NONE
**RLM needed**: No

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 0a | `quickwin_surgeries/surgery_attribution_tag.md` | quickwin | `AndroidManifest.xml`, `strings.xml` | Add `<attribution>` element + string resource |
| 0b | N/A (manual) | api | `build.gradle.kts` | Add `androidx.browser:browser:1.8.0` dependency |
| 0c | `quickwin_surgeries/surgery_accounts_logcat.md` (edit #4 only) | quickwin | `AndroidManifest.xml` | Add `GET_ACCOUNTS` permission |

**Notes**: 0b is extracted from `wave1_customtabs.md` requirements. CameraX deps (`camera-compose:1.4.1`, `lifecycle-process`) are deferred to Wave 7 pre-step.

---

### Wave 1: Safe Standalone (no DeviceApiServer.kt)
**Purpose**: UI changes and Python-only fixes -- zero risk to API server
**Dependencies**: NONE
**RLM needed**: No

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 1a | `quickwin_surgeries/surgery_force_close.md` | quickwin | `SettingsScreen.kt` | Add "Danger Zone" + Force Close button + confirmation dialog |
| 1b | `quickwin_surgeries/surgery_agent_threading.md` | quickwin | `agent_orchestrator.py` | Fix blocking `await client.query()` with fire-and-forget task pattern |

---

### Wave 2: Phantom Fixes (wire existing MCP tools to real Kotlin handlers)
**Purpose**: Fix endpoints that MCP tools already call but get 404s or wrong responses
**Dependencies**: Wave 0 (manifest)
**RLM needed**: **YES** (DeviceApiServer.kt)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 2a | `quickwin_surgeries/surgery_phantom_camera_biometric.md` | quickwin | `DeviceApiServer.kt` | Add `handleCameraStatus()` + `handleBiometricStatus()` handlers + routes + BiometricManager import |
| 2b | `quickwin_surgeries/surgery_phantom_camera_video.md` | quickwin | `DeviceApiServer.kt` | Add `handleCameraVideoStartDirect()` wrapper, wire `/camera/video/start` and `/camera/video/stop` routes, add quality→bitrate mapping |

**Notes**: These fix 4 phantom MCP tools (`device_camera_status`, `device_biometric_status`, `device_camera_video_start`, `device_camera_video_stop`) that already exist in the Python layer.

---

### Wave 3: Simple New Endpoints (low complexity, independent)
**Purpose**: New standalone handlers with no cross-dependencies
**Dependencies**: Wave 0 (permissions)
**RLM needed**: **YES** (DeviceApiServer.kt + unified_device_mcp.py)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 3a | `quickwin_surgeries/surgery_gnss_keyevent.md` | quickwin | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/gnss/status` (GnssStatus.Callback) + `/input/keyevent` (Runtime.exec) + 2 MCP tools |
| 3b | `quickwin_surgeries/surgery_accounts_logcat.md` (edits 1-3, 5) | quickwin | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/accounts` (AccountManager) + `/logcat` (Runtime.exec logcat) + 2 MCP tools |
| 3c | `api_surgeries/wave1_sensors.md` (orientation only) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/sensors/orientation` handler + MCP tool |
| 3d | `api_surgeries/wave2_accounts.md` | api | **SKIP -- covered by 3b** | Quickwin version preferred (includes logcat) |
| 3e | `api_surgeries/wave1_customtabs.md` | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/browser/open` + `/browser/prefetch` + class-level CustomTabs fields + 2 MCP tools |
| 3f | `api_surgeries/wave2_bluetooth_hfp.md` | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/bluetooth/headset/status` + `/bluetooth/headset/voice_recognition` + `ensureHeadsetProfile()` + 2 MCP tools |
| 3g | `api_surgeries/wave1_sensors.md` (trigger only) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/sensors/trigger` handler + MCP tool |

---

### Wave 4: PackageManager Endpoints (8 handlers, large batch)
**Purpose**: Bulk addition of PackageManager query endpoints
**Dependencies**: Wave 2 (so route table insertions don't conflict)
**RLM needed**: **YES** (DeviceApiServer.kt + unified_device_mcp.py -- large insertion block)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 4a | `quickwin_surgeries/surgery_packagemanager.md` | quickwin | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add 8 routes + 8 handlers (`/apps/info`, `/apps/resolve`, `/apps/find-by-permission`, `/apps/changes`, `/apps/components`, `/apps/defaults`, `/device/features`, `/device/modules`) + 9 MCP tools + API list update |

---

### Wave 5: Enhanced Existing Endpoints (modify existing handlers)
**Purpose**: Upgrade existing handlers with richer parameters/responses
**Dependencies**: Waves 2-4 (so we're modifying stable code)
**RLM needed**: **YES** (DeviceApiServer.kt + unified_device_mcp.py)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 5a | `api_surgeries/wave1_notifications.md` (notification/send) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Replace `handleNotification` with `handleNotificationSend` (priority, actions, styles, deep_link) + add `/notification/send` route alias |
| 5b | `api_surgeries/wave1_notifications.md` (notifications/active) | api | `MobileKineticNotificationListener.kt`, `unified_device_mcp.py` | Enrich `sbnToJson()` with 18+ new fields + add `package_filter` to MCP tool |
| 5c | `api_surgeries/wave1_sensors.md` (sensors/read enhance) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `sampling_rate` parameter to existing `handleSensorsRead` + `readSensorOnce` |
| 5d | `api_surgeries/wave1_sharing.md` (share enhance) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Replace `handleShare` with ShareCompat.IntentBuilder version + expand MCP schema |
| 5e | `api_surgeries/wave1_sharing.md` (share/received) | api | `DeviceApiServer.kt`, `MainActivity.kt`, `unified_device_mcp.py` | Add `/share/received` route + `ReceivedShareData` class + `processIncomingShare()` in MainActivity |
| 5f | `api_surgeries/wave2_intents.md` (intent/broadcast) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Replace `handleIntentBroadcast` with enhanced version (permission param, BroadcastOptions API 34+) |
| 5g | `api_surgeries/wave2_intents.md` (intent/activity) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Replace `handleIntentActivity` with enhanced version (categories, flags, resolveFirst, mimeType) |

---

### Wave 6: MMS (complex PDU construction)
**Purpose**: Full MMS support -- list, read, send (with PDU builder)
**Dependencies**: Wave 0 (manifest for FileProvider config)
**RLM needed**: **YES** (DeviceApiServer.kt)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 6a | `api_surgeries/wave1_mms.md` (mms/list) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/mms/list` handler + `getMmsAddresses` + `getMmsPartsSummary` helpers + MCP tool |
| 6b | `api_surgeries/wave1_mms.md` (mms/read) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/mms/read` handler + `getMmsParts` + `readMmsPartText` + `readMmsPartBase64` helpers + MCP tool |
| 6c | `api_surgeries/wave1_mms.md` (mms/send) | api | `DeviceApiServer.kt`, `AndroidManifest.xml`, `res/xml/file_paths.xml` (NEW), `unified_device_mcp.py` | Add `/mms/send` handler + `buildMmsPdu` + `serializeMmsPdu` + `writeUintVar` + FileProvider config + MCP tool |

**Notes**: 6a must execute before 6b (shares `getMmsAddresses` helper). 6c requires FileProvider config in manifest.

---

### Wave 7: CameraX Migration (highest risk, absolute last for camera)
**Purpose**: Replace Camera2+MediaRecorder with CameraX across photo/video/analyze/list/status
**Dependencies**: All prior waves complete. Waves 2a/2b quickwin camera fixes will be SUPERSEDED.
**RLM needed**: **YES** (DeviceApiServer.kt -- massive replacement blocks)

**Pre-step**: Add CameraX gradle deps (`camera-compose:1.4.1`, verify `lifecycle-process`)

| # | Surgery File | Source | Target Files | What It Does |
|---|-------------|--------|-------------|-------------|
| 7a | `api_surgeries/wave2_camerax.md` (/camera/photo) | api | `DeviceApiServer.kt`, `build.gradle.kts` | Replace Camera2 `handleCameraPhoto` (~200 lines) with CameraX ImageCapture (~80 lines) + add `cameraProvider` lazy field |
| 7b | `api_surgeries/wave2_camerax.md` (/camera/analyze) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Add `/camera/analyze` handler + `isAnalyzing` state field + MCP tool |
| 7c | `api_surgeries/wave2_camerax.md` (/camera/video) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | Replace Camera2+MediaRecorder with CameraX VideoCapture<Recorder> + pause/resume + 2 new MCP tools |
| 7d | `api_surgeries/wave2_camerax.md` (/camera/list) | api | `DeviceApiServer.kt` | Add concurrent camera info to existing handler |
| 7e | `api_surgeries/wave2_camerax.md` (/camera/status) | api | `DeviceApiServer.kt`, `unified_device_mcp.py` | **SUPERSEDES Wave 2a quickwin camera/status** -- CameraX-based version replaces Camera2 version |
| 7f | `api_surgeries/wave2_camerax.md` (phantom MCP fixes) | api | `unified_device_mcp.py` | Fix `device_camera_video_start` URL, fix `device_camera_video_stop` URL (post-CameraX URL scheme) |

**Execution order within wave**: 7a -> 7b -> 7c -> 7d -> 7e -> 7f (strict chain -- each depends on `cameraProvider` from 7a)

---

### Wave 8: ONNX Restore (post-surgery stabilization)
**Purpose**: After all waves complete, verify ONNX embedding provider still works
**Dependencies**: All waves complete
**RLM needed**: No

| # | Action | Details |
|---|--------|---------|
| 8a | Build + install | `.\gradlew.bat assembleDebug` + `adb install -r` |
| 8b | Smoke test | Hit every new endpoint with curl via adb forward |
| 8c | ONNX verification | Verify `DualEmbeddingProvider` still initializes, RAG queries return results |
| 8d | Regression check | Test existing endpoints (battery, sms, contacts, etc.) still work |

---

## Per-Wave Edit Counts

| Wave | Surgeries | Edits | New Endpoints | New MCP Tools | Modified Endpoints | Modified MCP Tools |
|------|-----------|-------|---------------|---------------|-------------------|-------------------|
| 0 | 2 | 3 | 0 | 0 | 0 | 0 |
| 1 | 2 | 9 | 0 | 0 | 0 | 0 |
| 2 | 2 | 8 | 4 (phantom) | 0 | 0 | 0 |
| 3 | 6 | ~18 | 9 | 9 | 0 | 0 |
| 4 | 1 | 12 | 8 | 9 | 0 | 1 |
| 5 | 7 | ~20 | 1 | 1 | 6 | 6 |
| 6 | 3 | ~12 | 3 | 3 | 0 | 0 |
| 7 | 6 | ~25 | 2 | 3 | 3 | 3 |
| 8 | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total** | **29** | **~107** | **27** | **25** | **9** | **10** |

---

## RLM Strategy

DeviceApiServer.kt (~131KB) and unified_device_mcp.py (~2588 lines) CANNOT be read directly by the executing agent. For every wave touching these files:

1. **Use RLM** (`POST http://localhost:6100/rlm/analyze`) to:
   - Find exact insertion points (line numbers for route table, handler section, imports)
   - Read surrounding context (10-20 lines before/after insertion point)
   - Verify no naming collisions with existing handlers

2. **Batch edits per file per wave** -- apply all edits to `DeviceApiServer.kt` within a single wave before moving to the next wave. Do NOT interleave waves on the same file.

3. **After each wave**: build (`gradlew assembleDebug`) and verify no compile errors before proceeding.

---

## Critical Warnings

1. **Wave 2a camera/status is temporary** -- it will be REPLACED by Wave 7e. The quickwin Camera2 version works standalone; the API CameraX version needs the full migration chain. Both are valid, but 7e supersedes 2a.

2. **Wave 2b camera video fixes are temporary** -- the quickwin adds split URL handlers (`/camera/video/start`, `/camera/video/stop`). Wave 7c replaces the entire video subsystem with CameraX. Wave 7f fixes the MCP URLs to match the new CameraX URL scheme.

3. **wave2_shared_element.md is informational only** -- no endpoints, no handlers, no MCP tools. It is a UI reference doc. DO NOT attempt to execute it.

4. **wave2_accounts.md is SKIPPED** -- fully covered by quickwin `surgery_accounts_logcat.md` which has more detail and also includes `/logcat`.

5. **MMS send (Wave 6c) creates a NEW file** (`res/xml/file_paths.xml`) -- this is the only surgery that creates a new source file.

6. **CameraX migration (Wave 7) has a strict internal order** -- photo MUST come first (creates `cameraProvider`), then analyze, then video, then list, then status. Do not reorder.

---

## Skipped / Excluded Files

| File | Reason |
|------|--------|
| `api_surgeries/wave2_shared_element.md` | Informational only, no endpoints |
| `api_surgeries/wave2_accounts.md` | Duplicate of quickwin accounts (quickwin preferred) |
| `api_surgeries/KOTLIN_PATTERN.md` | Reference doc, not a surgery |
| `api_surgeries/MCP_PATTERN.md` | Reference doc, not a surgery |
| `quickwin_surgeries/api_specs.json` | Reference data, not a surgery |
| `api_surgeries/api_specs.json` | Reference data, not a surgery |
