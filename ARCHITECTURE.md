# mK:a - Architecture Document

**Version:** 2.1
**Date:** 2026-02-25
**Status:** Living document reflecting implemented application (audited from device, repo, RAG, and endpoint testing)
**Previous Version:** 2.0 (2026-02-25, pre-audit), 1.0 (2026-01-31, pre-implementation blueprint)

---

## 1. Project Overview

### What mK:a Is

mK:a is a privacy-first AI assistant Android app for the Pixel device. It fuses a forked Termux terminal emulator with an AI agent powered by Claude Code. The Linux environment and the AI agent share one process space, one UID, and one lifecycle.

### Tech Stack

- Kotlin 2.0+ with K2 compiler, Jetpack Compose, Material 3, Hilt DI, Room with KSP
- compileSdk 36, targetSdk 36, minSdk 26
- Home directory: INTERNAL storage `/data/data/com.mobilekinetic.agent/files/home/` (**NEVER external**)
- Git repo: `A:\Android-Studio-SDK\mK:a\` on `main` branch

### Why It Exists

AndroidInsider (the predecessor) required two separate apps: the Android UI app and Termux running a Node.js bridge server. This created a fragile HTTP bridge on port 10101, two-app coordination complexity, duplicate process management, and permission/UID isolation headaches. mK:a eliminates all of this by forking Termux directly.

### Key Constraint

Termux is GPLv3. mK:a is personal-use only (single device). No distribution.

---

## 2. Architecture Diagram

```
+====================================================================+
|                         COMPOSE UI LAYER                            |
|  +--------------------------------------------------------------+  |
|  | ChatScreen | TerminalScreen | ToolsScreen | SettingsScreen    |  |
|  | VaultScreen | SetupScreen | BlacklistScreen | PrivacySettings |  |
|  | MemoryInjectionSettings                                       |  |
|  | Material 3 + LCARS Theme | Adaptive Foldable Layouts          |  |
|  | Compose Navigation (type-safe routes)                         |  |
|  +-------------------------------+------------------------------+  |
+===================================|=================================+
                                    | ViewModels (Hilt-injected)
+===================================|=================================+
|                      ANDROID APP LAYER                              |
|  +-------------------------------+------------------------------+  |
|  | MobileKineticService (Foreground)                               |  |
|  |   - ClaudeProcessManager (42KB, process lifecycle + IPC)      |  |
|  |   - DeviceApiServer (NanoHTTPD :5563, ~184 endpoints)         |  |
|  |   - RagHttpServer (NanoHTTPD :5562)                           |  |
|  |   - VaultHttpServer (Ktor/CIO :5565)                          |  |
|  |   - ScriptManager (asset extraction + merge)                  |  |
|  +-------------------------------+------------------------------+  |
|  | RagRepository | VaultRepository | CredentialVault | TtsManager|  |
|  | MemoryDecayEngine | SessionSummarizer | PrivacyGate           |  |
|  +-------------------------------+------------------------------+  |
+===================================|=================================+
                                    |
         +----------+----------+----+----+-----------+
         |          |          |         |           |
+========|=+  +====|====+  +==|======+  |  +========|=========+
| TERMINAL |  | CLAUDE  |  | RAG    |  |  | DEVICE APIs      |
| ENGINE   |  | PROCESS |  | SYSTEM |  |  | ~184 Kotlin      |
|          |  |         |  |        |  |  | endpoints +      |
| terminal-|  | Python  |  | ONNX   |  |  | ~184 MCP tools   |
| emulator |  | agent_  |  | MiniLM |  |  | (unified_device_ |
| module   |  | orch.   |  | 384d   |  |  |  mcp.py, 318KB)  |
| (JNI/C)  |  | (84KB)  |  | Room   |  |  |                  |
+====|=====+  +====|====+  +========+  |  +==================+
     |              |                   |
     | PTY          | stdin/stdout      | HTTP + MCP stdio
     | fork/exec    | stream-json       |
+====|==============|===================|======================+
|                    LINUX ENVIRONMENT                          |
|  /data/data/com.mobilekinetic.agent/files/                           |
|  usr/  - bash, python3.12, node 25.3, pip, apt               |
|  home/ - agent_orchestrator.py, unified_device_mcp.py,       |
|          MCP servers, config, Memory/, Design/, RLM/          |
|  Claude Code CLI v2.0.37 (npm-installed)                     |
+==============================================================+
```

---

## 3. 5-Phase Architecture

Implemented 2026-02-22. Layered security and intelligence architecture:

| Phase | Name | Components | Status |
|-------|------|-----------|--------|
| 1 | **Credential Vault** | Ktor/CIO on :5565, StrongBox Titan M2 AES-256-GCM encryption, HA token seeded | Implemented |
| 2 | **Privacy Gate** | Rule-based BLOCK/REDACT filtering on notifications+SMS, PrivacySettingsScreen UI | Implemented |
| 3 | **Gemma LiteRT** | Model manager, download worker, dual embedding provider (384/768-dim), text generator stubs for mediapipe-tasks-genai. LiteRT 2.1.0 uses CompiledModel API (NOT Interpreter) for GPU/NPU acceleration | Partially implemented (GemmaEmbeddingProvider DISABLED, ONNX MiniLM is sole provider) |
| 4 | **Gemma-Enhanced Privacy** | GemmaPrivacyFilter (fail-open), CredentialGatekeeper on vault endpoints | Partially implemented (Gemma-Vault bridge NOT wired) |
| 5 | **Session Memory + Fade** | Ebbinghaus decay engine, session summarizer, context builder, DecayWorker daily periodic | Implemented |

### LiteRT 2.1.0 Critical API Notes

- Use `CompiledModel` NOT `Interpreter` for GPU/NPU acceleration
- `CompiledModel.Options(Accelerator.NPU)` -- direct constructor, NO `.builder()`
- `model.createInputBuffers()` / `model.createOutputBuffers()` -- NO `TensorBuffer.createFrom()`
- Input: `writeInt(IntArray)`, Output: `readFloat(): FloatArray`
- `litert-gpu:2.1.0` does NOT exist on Maven -- GPU/NPU baked into core `litert:2.1.0`
- EmbeddingGemma prompt: `"task: search result | query: $text"` -- exact spacing matters
- Gemma text gen model is `.task` (MediaPipe bundle), embedding model is `.tflite`

---

## 4. Port Map

| Port | Server | Framework | Location | Description |
|------|--------|-----------|----------|-------------|
| 5562 | RagHttpServer.kt | NanoHTTPD | On-device | Kotlin RAG memory API (ONNX embeddings, Room storage) |
| 5563 | DeviceApiServer.kt | NanoHTTPD | On-device | Tier 1: Native Android APIs (~184 endpoint registrations) |
| 5564 | device_api_mcp.py | FastAPI/uvicorn | On-device | Tier 2: Shell/filesystem operations |
| 5565 | VaultHttpServer.kt | Ktor/CIO | On-device | Vault credential API (biometric-gated) |

All on-device HTTP servers bind exclusively to `127.0.0.1`.

Additionally, stdio-based MCP servers communicate via JSON-RPC 2.0 over stdin/stdout:
- `unified_device_mcp.py` (~184 MCP tools, used as Claude CLI MCP server)
- `mcp_tier1_server.py` (wraps Tier 1 Kotlin API)
- `mcp_tier2_server.py` (wraps Tier 2 Python API)
- `mcp_shell_server.py` (secure shell execution)
- `mcp_tasker_server.py` (Tasker automation tools)
- `rag_mcp_server.py` (RAG memory tools, reads from :5562)
- `rlm_unified_implementation.py` (Recursive Language Model)

---

## 5. Device API Server (Kotlin, port 5563)

**DeviceApiServer.kt**: ~11,600 lines, ~184 endpoint registrations.

### Read Endpoints (65+)

SMS, contacts, battery, wifi, location, sensors (57 total), bluetooth, camera list/status, photos, network capabilities/data usage, call log, biometric, geofences, notifications (49 active), calendar (12 calendars), downloads, apps (214 installed), accounts (12), features (161), modules (39), MMS, logcat, share, audio.

### Write/Action Endpoints (35+)

Clipboard, torch, toast, notification, vibrate (simple/effect/pattern/cancel), brightness, volume, DND, media control, TTS, SMS (deprecated), MMS send, calendar CRUD, tasks CRUD, camera photo/video, audio record, notification dismiss/dismiss_all, browser open/prefetch, intent activity/broadcast, download enqueue/status, share, work schedule/cancel/status.

### Specialized Endpoint Groups

| Group | Count | Description |
|-------|-------|-------------|
| Tasker integration | 6+ | Variable set, task run, profile toggle, 6 convenience wrappers (screenshot, browse_url, lamp_on/off, print, play_music) |
| Notification channels/groups CRUD | 7 | Channel and group management |
| Notification management | 10 | Active list, dismiss, reply, create, etc. |
| Zen/DND rules CRUD | 8 | Do Not Disturb rule management |
| Clipboard extended | 5 | Get/set/history |
| SharedPreferences CRUD | 7 | Read/write/delete preferences |
| PackageManager queries | 5 | App info, permissions, activities |
| ContentResolver generic CRUD | 4 | Generic content provider access |
| Intent send/resolve | 2 | Activity launch, broadcast |

---

## 6. Python-Side Architecture (deployed to ~/home)

19 Python files deployed via APK assets:

| File | Lines | Size | Purpose |
|------|-------|------|---------|
| unified_device_mcp.py | 5,927 | 318KB | Mega MCP server wrapping RAG + Tasker + Tier 1 + Tier 2 + HA into ~184 unified MCP tools |
| agent_orchestrator.py | 1,913 | 84KB | Conversation routing, tool dispatch, Claude subprocess transport |
| mcp_tier1_server.py | 814 | - | MCP wrapper for Kotlin API (port 5563) |
| mcp_shell_server.py | 794 | - | MCP shell command server |
| rlm_unified_implementation.py | 708 | - | On-device Recursive Language Model |
| mcp_tier2_server.py | 697 | - | MCP wrapper for Python API (port 5564) |
| device_api_mcp.py | 583 | - | Device API MCP bridge (FastAPI on :5564) |
| context_injector.py | 549 | - | Context injection pipeline (Tiers 0-3) |
| conversation_processor.py | 528 | - | Conversation processing/message handling |
| rag_mcp_server.py | 521 | - | RAG MCP server for child agents |
| memory_lifecycle.py | 503 | - | Memory lifecycle management (Ebbinghaus decay) |
| vault_client.py | 313 | - | Client for Vault (StrongBox) |
| watchdog_diagnostic.py | 277 | - | Watchdog diagnostic tool |
| mcp_tasker_server.py | 270 | - | Tasker MCP wrapper |
| secure_vault.py | 203 | - | Secure vault implementation |
| trigger_store.py | 175 | - | Trigger/event store |
| check_sdk.py | - | - | SDK check utility |
| seed_rag.py | - | - | RAG seeding utility |
| test_import.py | - | - | Import test utility |

### Supporting Assets

- **Shell scripts**: backup_knowledge.sh, restore_knowledge.sh, promote_build.sh, cli_search.sh
- **Config**: mcp_config.json, toolDescriptions.json (22 Tasker tools), seed_tasker_docs.json
- **Docs**: CLAUDE.md (on-device), RAG_TAXONOMY.md, RECENT_TOPICS.md, WATCHDOG_QUICKREF.md, memory.md, errors.md, watchdog_integration_guide.md, build_promotion_template.md
- **Models**: all-MiniLM-L6-v2.onnx (87MB, 384-dim), sentencepiece.model, vocab.txt
- **Bootstrap**: bootstrap-aarch64.zip (30MB), python_complete.tar.gz (8.4MB)
- **Other**: napkin_messages.json (NapkinDab), rag_seed.json (93+ tool definitions), jsonl_strip.js, .bashrc

---

## 7. Module Structure

```
mK:a/
+-- gradle/
|   +-- libs.versions.toml            (version catalog)
+-- app/                              (main application module)
|   +-- src/main/
|       +-- kotlin/com/mobilekinetic/agent/
|       |   +-- app/                   MobileKineticApp, MobileKineticService, BootstrapInstaller,
|       |   |                          MainActivity, ScriptManager
|       |   +-- claude/                ClaudeProcessManager (42KB, 1013 lines)
|       |   +-- data/
|       |   |   +-- chat/              ConversationRepository, ResponseParser
|       |   |   +-- db/                MobileKineticDatabase (v6), Converters, DatabaseKeyManager
|       |   |   |   +-- dao/           ConversationDao, CredentialDao, RagDao, ToolDao, VaultDao,
|       |   |   |   |                  BlacklistDao, SessionSummaryDao, MemoryFactDao
|       |   |   |   +-- entity/        ConversationEntity, MessageEntity, CredentialEntity,
|       |   |   |                      RagDocumentEntity, ToolEntity, ToolUsageEntity,
|       |   |   |                      VaultEntryEntity, BlacklistRuleEntity,
|       |   |   |                      SessionSummaryEntity, MemoryFactEntity
|       |   |   +-- gemma/             GemmaModelManager, GemmaTextGenerator,
|       |   |   |                      GemmaModelDownloader, GemmaModelDownloadWorker,
|       |   |   |                      GemmaPrompts, EmbeddingMigrationWorker
|       |   |   +-- memory/            MemoryDecayEngine, SessionSummarizer,
|       |   |   |                      SessionContextBuilder, SessionMemoryRepository,
|       |   |   |                      DecayWorker, BackupWorker, SmbBackupTransport
|       |   |   +-- model/             ChatModels
|       |   |   +-- preferences/       SettingsDataStore
|       |   |   +-- rag/               EmbeddingModel, EmbeddingProvider, OnnxEmbeddingProvider,
|       |   |   |                      GemmaEmbeddingProvider (DISABLED), DualEmbeddingProvider,
|       |   |   |                      SentencePieceTokenizer, WordPieceTokenizer,
|       |   |   |                      RagRepository, RagHttpServer, RagSeeder, ToolMemory
|       |   |   +-- settings/          InjectionSettingsRepository
|       |   |   +-- tts/               TtsManager, TtsSettingsRepository, AudioVisualizerBridge
|       |   |   +-- vault/             VaultRepository, CredentialVault, VaultHttpServer
|       |   +-- device/api/            DeviceApiServer (~11,600 lines, ~184 endpoints),
|       |   |                          DeviceApiWorker, MobileKineticAccessibilityService,
|       |   |                          MobileKineticDeviceAdmin, MobileKineticNotificationListener
|       |   +-- di/                    AppModule (Hilt providers)
|       |   +-- privacy/               PrivacyGate, PrivacyGateRuleSync, GemmaPrivacyFilter
|       |   +-- receiver/              AlarmReceiver, GeofenceBroadcastReceiver
|       |   +-- security/              BiometricAuthManager, VaultKeyManager,
|       |   |                          CredentialVaultKeyManager, CredentialGatekeeper
|       |   +-- ui/
|       |       +-- MobileKineticApp.kt  (top-level Compose app shell)
|       |       +-- TextProcessingActivity.kt (PROCESS_TEXT intent handler)
|       |       +-- TerminalScreen.kt
|       |       +-- components/        AudioVisualizer (Three.js WebView)
|       |       +-- navigation/        Destination (type-safe routes)
|       |       +-- screens/           ChatScreen, SettingsScreen, SetupScreen, ToolsScreen,
|       |       |                      VaultScreen, BlacklistScreen, PrivacySettingsScreen,
|       |       |                      MemoryInjectionSettingsScreen
|       |       +-- theme/             Color, Shape, Theme, Type (LCARS scheme)
|       |       +-- viewmodel/         ChatViewModel, SettingsViewModel, ToolsViewModel,
|       |                              VaultViewModel, BlacklistViewModel,
|       |                              PrivacySettingsViewModel, InjectionSettingsViewModel
|       +-- assets/
|       |   +-- scripts/               Python on-device scripts (deployed to $HOME/)
|       |   +-- models/                all-MiniLM-L6-v2.onnx, vocab.txt
|       |   +-- visualizer/            visualizer.html (Three.js 3D audio sphere)
|       +-- res/                       Resources (icons, strings, XML configs)
|       +-- AndroidManifest.xml        49 permissions
+-- terminal-emulator/                 Termux terminal emulation backend (Java/C, JNI)
+-- terminal-view/                     Termux terminal rendering (Compose AndroidView bridge)
+-- shared/                            MobileKineticConstants, shell utils
+-- tools/
|   +-- adb_mcp_server.py             Dev PC ADB REST API
+-- docs/
|   +-- APP_IMPROVEMENTS_FOR_BUILD.md
|   +-- watchdog/                      Integration guide, quickref
+-- ClaudeShares/                      DESIGN_SYSTEM.md, shared assets
```

### Module Dependency Graph

```
app --> terminal-view --> terminal-emulator
app --> shared
```

---

## 8. Database Schema

**Room Database v6** with SQLCipher encryption. 10 entities, 8 DAOs. Destructive migration OK (model files survive reinstall via `adb install -r`).

DB file location: `/data/data/com.mobilekinetic.agent/files/home/Memory/Room/mobilekinetic_db`

| Entity | DAO | Purpose |
|--------|-----|---------|
| `ConversationEntity` | ConversationDao | Chat session metadata (id, title, claudeSessionId, timestamps) |
| `MessageEntity` | ConversationDao | Individual chat messages (role, rawContent, toolName, costUsd) |
| `CredentialEntity` | CredentialDao | Encrypted credential store (name, category, encryptedValue) |
| `RagDocumentEntity` | RagDao | RAG vector store (text, category, embedding BLOB 384-dim, metadata) |
| `ToolEntity` | ToolDao | Tool registry with approval (name, schema, isUserApproved, useCount) |
| `ToolUsageEntity` | ToolDao | Execution audit log (inputJson, resultJson, isSuccess, executionTimeMs) |
| `VaultEntryEntity` | VaultDao | Biometric vault entries (double-encrypted credentials) |
| `BlacklistRuleEntity` | BlacklistDao | Privacy gate blacklist rules |
| `SessionSummaryEntity` | SessionSummaryDao | Compressed session context for memory injection |
| `MemoryFactEntity` | MemoryFactDao | Ebbinghaus-decayed memory facts (strength, lastAccess, decayRate) |

### Encryption

- **SQLCipher** full-database encryption (AES-256-CBC)
- Passphrase: `SecureRandom` 32 bytes, encrypted with AES-256-GCM, stored in SharedPreferences
- Keystore key alias: `mobilekinetic_db_key`
- **Vault double encryption**: field-level AES-256-GCM (StrongBox Titan M2) inside already-encrypted database

---

## 9. Security Model

### Vault (Phase 1)

```
Biometric (fingerprint/face, Class 3 only)
  -> CryptoObject-bound Cipher
    -> VaultKeyManager (AES-256-GCM, Android Keystore, StrongBox Titan M2)
      -> VaultRepository (encrypt/pack -> Base64(ciphertext)::Base64(iv))
        -> VaultDao (Room, inside SQLCipher-encrypted database)
```

- Keys invalidated on new biometric enrollment
- Per-operation authentication (no session timeouts)
- Non-exportable, hardware-backed keys (StrongBox when available)
- VaultHttpServer (Ktor/CIO on :5565) provides API access to Claude

### Privacy Gate (Phase 2)

- PrivacyGate.kt intercepts sensitive data flows
- BlacklistRuleEntity defines blocked patterns
- PrivacyGateRuleSync keeps rules current
- Managed via BlacklistScreen and PrivacySettingsScreen

### Process Isolation

- All on-device servers bind to 127.0.0.1 only
- Termux runs in Android app sandbox
- stdio MCP servers use no network sockets
- fs-guard.js blocks `/storage/emulated` access to prevent FUSE deadlocks

---

## 10. On-Device File Layout

```
~/                              = /data/data/com.mobilekinetic.agent/files/home/
  agent_orchestrator.py         (main entry point, 84KB, 1,913 lines)
  unified_device_mcp.py         (unified MCP server, 318KB, 5,927 lines)
  context_injector.py           (system prompt builder, 549 lines)
  conversation_processor.py     (stream parser, 528 lines)
  memory_lifecycle.py           (Ebbinghaus decay logic, 503 lines)
  rag_mcp_server.py             (RAG MCP, reads from :5562, 521 lines)
  mcp_shell_server.py           (shell MCP, 794 lines)
  mcp_tier1_server.py           (Tier 1 wrapper, 814 lines)
  mcp_tier2_server.py           (Tier 2 wrapper, 697 lines)
  mcp_tasker_server.py          (Tasker integration, 270 lines)
  device_api_mcp.py             (Tier 2 FastAPI on :5564, 583 lines)
  rlm_unified_implementation.py (RLM engine, 708 lines)
  vault_client.py               (Vault API client, 313 lines)
  secure_vault.py               (CLI password manager, 203 lines)
  trigger_store.py              (Trigger persistence, 175 lines)
  watchdog_diagnostic.py        (Watchdog diagnostic, 277 lines)
  jsonl_strip.js                (JSONL processing)
  fs-guard.js                   (filesystem guard, blocks /storage/emulated)
  .bashrc                       (shell config)
  .claude.json                  (Claude Code config)
  config/                       (mcp_config.json, toolDescriptions.json)
  docs/                         (CLAUDE.md, guides)
  shell/                        (backup/restore/promote scripts)
```

### Persistent Directories (survive reinstalls via `adb install -r`)

These live at `~/home/` and are NOT part of the APK:

| Directory | Contents |
|-----------|----------|
| `Memory/` | memory.md, endpoint audits, resolved issues, Room DB backups, triggers |
| `ClaudeShares/` | Resolver spec, Tasker help/userguide/XML parsed docs, unified_device_mcp.py mirror |
| `Design/` | docs/, fonts/ |
| `RLM/` | On-device RLM implementation files, MCP design docs |
| `Reference/` | Android core areas reference, RAG/API specs, sensor/connectivity/vision guides |
| `QuickApps/` | Contact quickapp, level app, quickapp launcher/template |
| `AppFactory/` | Backup/, GitSource/, Workshop/ |
| `repos/` | (reserved) |
| `.claude/` | Claude session data, credentials, history, settings, todos, plans |
| `.persistent/` | RLM persistent data |

**CRITICAL**: Home directory is INTERNAL storage. DO NOT move to external. A previous attempt caused fs-guard.js EACCES errors, incomplete migration, timestamp flattening, and two hours of debugging. Reverted in commit `badc7a0`.

---

## 11. RAG System (port 5562)

### Embedding Pipeline

```
Text Input -> WordPieceTokenizer -> ONNX Runtime (all-MiniLM-L6-v2)
  -> 384-dim FloatArray -> RagRepository (cosine similarity)
    -> Room DB (SQLCipher) <- RagHttpServer (port 5562) <- Claude/scripts
```

**Active provider**: OnnxEmbeddingProvider (all-MiniLM-L6-v2, 384 dimensions)
**Disabled provider**: GemmaEmbeddingProvider (was 768 dimensions, buggy, disabled)
**Memory count**: 350+ memories after ONNX reindex (346 legacy docs re-embedded from Gemma 768 to ONNX 384)

### Kotlin Components

| Component | File | Role |
|-----------|------|------|
| EmbeddingModel | data/rag/EmbeddingModel.kt | ONNX Runtime inference |
| EmbeddingProvider | data/rag/EmbeddingProvider.kt | Provider interface |
| OnnxEmbeddingProvider | data/rag/OnnxEmbeddingProvider.kt | Active 384-dim embeddings |
| GemmaEmbeddingProvider | data/rag/GemmaEmbeddingProvider.kt | DISABLED (768-dim, buggy) |
| DualEmbeddingProvider | data/rag/DualEmbeddingProvider.kt | Provider switching logic |
| WordPieceTokenizer | data/rag/WordPieceTokenizer.kt | BERT-style tokenization |
| SentencePieceTokenizer | data/rag/SentencePieceTokenizer.kt | Alternative tokenizer |
| RagRepository | data/rag/RagRepository.kt | Cosine similarity search, CRUD |
| RagHttpServer | data/rag/RagHttpServer.kt | NanoHTTPD API on :5562 |
| RagSeeder | data/rag/RagSeeder.kt | Initial document seeding |
| ToolMemory | data/rag/ToolMemory.kt | Semantic tool discovery |

### HTTP API (port 5562)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Status + memory count |
| `/search` | POST | Semantic vector search (query, top_k, min_score) |
| `/context` | POST | Formatted context string for LLM injection |
| `/memory` | POST | Add new document/memory |
| `/memory` | GET | List all memories |
| `/memory/{id}` | DELETE | Delete memory by ID |
| `/reindex` | POST | Re-embed all documents (used for Gemma->ONNX migration) |
| `/categories` | GET | List document categories |

---

## 12. Device Control

### Tier 1: Kotlin DeviceApiServer (port 5563)

**NanoHTTPD**, ~11,600 lines, ~184 endpoint registrations covering:

| Category | Capabilities |
|----------|-------------|
| SMS | List inbox/sent, MMS with attachments (/sms/send deprecated -- use /mms/send) |
| Calls | Place call, answer, DTMF, call log |
| Contacts | List, search, create, update, delete |
| Calendar | Events CRUD, JTX Board CalDAV tasks (12 calendars) |
| Location | GPS, network, geofencing, GNSS status |
| Sensors | All hardware sensors (57 total), orientation, triggers |
| Bluetooth | Classic scan, BLE scan/connect/read/write, headset profile |
| NFC | Read/write NDEF, tech discovery |
| WiFi | Status, scan, suggest networks |
| Network | Capabilities, data usage, bind process, active network |
| Battery | Status, charging, level |
| Volume | Get/set all streams, DND mode |
| Screen | Brightness, state, rotation, display settings |
| Torch | On/off with strobe pattern |
| Vibration | Simple, effect, pattern, cancel |
| Camera | Photo/video capture via CameraX |
| Media | Now playing, media control, audio routing |
| Notifications | Active list (49 active), dismiss, reply, create channels (10 endpoints) |
| Clipboard | Get/set + extended (5 endpoints) |
| Alarms | Create, list, cancel (exact and system) |
| Apps | List installed (214), launch, app info, usage stats |
| Downloads | Enqueue, status |
| Browser | Custom Tabs launch, prefetch |
| Settings | System and Secure settings read/write |
| TTS | Android TTS synthesize, Kokoro/LuxTTS streaming |
| RAG | Semantic search proxy (queries :5562) |
| Accessibility | UI node tree, click, type, gestures |
| Device Admin | Lock screen |
| System | Device info, storage info, health endpoint |
| Tasker | Variable set, task run, profile toggle, 6 convenience wrappers |
| SharedPreferences | CRUD (7 endpoints) |
| ContentResolver | Generic CRUD (4 endpoints) |
| Zen/DND Rules | CRUD (8 endpoints) |
| Intent | Send activity/broadcast, resolve (2 endpoints) |
| Work | Schedule/cancel/status |
| Share | Share intent, receive |
| Audio | Record |
| Logcat | Read filtered logs |

### Tier 2: Python device_api_mcp.py (port 5564)

**FastAPI/uvicorn**, shell execution, filesystem operations, package management, /proc, /sys, cron.

**Note**: `/network/interfaces` (Tier 2) currently returns empty.

### Unified MCP: unified_device_mcp.py (stdio)

**~184 MCP tools** in a single 318KB file. Registered as Claude CLI MCP server via `mcp_config.json`. Wraps both Tier 1 and Tier 2 APIs plus RAG, Vault, Tasker, and Home Assistant integration.

---

## 13. TTS Pipeline

```
ChatViewModel (sentence-boundary detection)
  -> TtsManager.speak(chunk) -> ConcurrentLinkedQueue
    -> KokoroTtsService.speak() -> WebSocket -> MP3 chunks
      -> ByteArrayDataSource -> ProgressiveMediaSource -> ExoPlayer playlist
        -> AudioVisualizerBridge.attach(audioSessionId)
          -> Android Visualizer (FFT + waveform)
            -> Three.js sphere animation in WebView (visualizer.html)
```

- WebSocket endpoint: configured via Settings (Kokoro TTS, default port 9199)
- First chunk: 2 complete sentences
- Subsequent chunks: 50+ chars to next sentence ending
- Final fragment: 1.5s timer then flush
- Default voice: `af_kore` at 1.2x speed
- Race condition guard: `AtomicBoolean` in KokoroTtsService prevents double `onComplete` firing
- TtsManager: Guard `onComplete`/`onError` lambdas with `if (!completed)` to prevent double playNextInQueue
- ChatViewModel: Add `ensureActive()` after `delay()` in remainder timer coroutines

### Voice/Display Content Separation

```
<voice>Hello! Here's the function you asked for.</voice>
<display>```kotlin
fun greet(name: String) = "Hello, $name!"
```</display>
```

- `<voice>` segments sent to Kokoro TTS
- `<display>` segments rendered in chat UI
- Untagged content defaults to VOICE (spoken)

---

## 14. UI Layer

### Screens (Jetpack Compose, Material 3)

| Screen | File | Bottom Nav | Description |
|--------|------|-----------|-------------|
| Chat | ChatScreen.kt | Yes | Primary AI interaction, streaming responses, tool use display |
| Terminal | TerminalScreen.kt | Yes | Full Termux terminal via AndroidView bridge |
| Tools | ToolsScreen.kt | Yes | Searchable tool registry with approval management |
| Settings | SettingsScreen.kt | Yes | TTS config, Claude process, model selection, memory injection |
| Vault | VaultScreen.kt | Yes | Biometric-protected credential manager (StrongBox Titan M2) |
| Setup | SetupScreen.kt | No | First-run bootstrap wizard |
| Blacklist | BlacklistScreen.kt | Sub-screen | Privacy blacklist rule editor |
| Privacy Settings | PrivacySettingsScreen.kt | Sub-screen | Privacy gate configuration |
| Memory Injection | MemoryInjectionSettingsScreen.kt | Sub-screen | Context injection tuning |

**Missing UI** (not yet implemented):
- SettingsScreen needs "Privacy Blacklist" navigation entry
- SettingsScreen needs "AI Models" section

### Theme

- LCARS-inspired dark Material 3 design with orange/blue/purple accents
- Custom color tokens for terminal, status, AI response blocks
- Adaptive layouts: `WindowSizeClass` for fold detection, pane scaffolds
- Window Manager for Pixel device hinge detection
- `NavigationSuiteScaffold` renders bottom bar on phones, nav rail on tablets/foldables

### Additional UI

- `TextProcessingActivity` -- Android PROCESS_TEXT intent handler (text selection overlay)
- `AudioVisualizer` -- Three.js WebView 3D sphere synchronized with ExoPlayer audio

---

## 15. Claude Integration

### Process Architecture

```
Kotlin App (MobileKineticService)
    |
    v
ClaudeProcessManager.kt (42KB, 1013 lines)
    |-- Spawns agent_orchestrator.py via PTY
    |-- stdin/stdout JSON streaming
    |-- Health monitoring (10s intervals, 2 max retries)
    |-- Environment: RAG_ENDPOINT=http://127.0.0.1:5562, etc.
    |
    v
agent_orchestrator.py (84KB, 1,913 lines)
    |-- Imports Claude Agent SDK
    |-- Spawns Claude Code CLI (Node.js, v2.0.37)
    |-- Configures MCP servers via mcp_config.json
    |-- System prompt via context_injector.py
    |-- Manages Tier 2 server (device_api_mcp.py on :5564)
    |
    v
Claude Code CLI (Node.js 25.3)
    |-- MCP server: unified_device_mcp.py (~184 tools)
    |-- Permission mode: bypassPermissions
    |
    v
Anthropic API (Claude model)
```

### Message Flow

```
1. User types in ChatScreen (Compose)
2. ChatViewModel sends prompt to ClaudeProcessManager
3. ClaudeProcessManager writes JSON to agent_orchestrator.py stdin
4. Orchestrator pipes to Claude CLI subprocess
5. Claude CLI calls Anthropic API
6. Response streams back as JSON on stdout:
   - AssistantMessage (text, thinking, tool_use blocks)
   - SystemMessage (init, MCP status)
   - ResultMessage (cost, usage, session_id)
7. ClaudeProcessManager parses via ResponseParser, emits to ChatViewModel via Flow
8. ChatScreen renders response blocks in real-time
```

### Session Management

- Session IDs stored in Room DB, survive app restarts
- Auto-restart on unexpected process exit (2 max retries)
- Bidirectional interrupt support

### Dependencies (Linux side)

- Python 3.12 (via bootstrap apt)
- Node.js 25.3 (via bootstrap)
- Claude Code CLI v2.0.37 (npm-installed, pinned -- v2.1+ broken on arm64)
- `mcp` Python package (MCP protocol)
- `httpx` (async HTTP client)
- `uvicorn` + `fastapi` (Tier 2 server)

---

## 16. Startup Sequence

```
Android Process Start
  |
  +-- MobileKineticApp.onCreate()
  |     +-- Hilt DI graph initialized
  |     +-- TtsManager.init(applicationContext)
  |
  +-- MainActivity.onCreate()
  |     +-- Permission checks (MANAGE_EXTERNAL_STORAGE, RECORD_AUDIO)
  |     +-- Bootstrap state evaluation:
  |     |     +-- NeedsSetup -> SetupScreen
  |     |     +-- Ready -> Main UI (MobileKineticApp composable)
  |     +-- Start MobileKineticService (foreground)
  |
  +-- MobileKineticService.onCreate()
        +-- Foreground notification (IMPORTANCE_LOW)
        +-- RAG system: EmbeddingModel + RagHttpServer (port 5562)
        +-- Device API: DeviceApiServer (port 5563)
        +-- Vault: VaultHttpServer (port 5565)
        +-- ScriptManager.init() -> extract scripts to $HOME, merge MCP config
        +-- ClaudeProcessManager.start() -> spawn agent_orchestrator.py
              +-- stdout reader coroutine
              +-- stderr reader coroutine
              +-- health monitor coroutine (10s interval)
```

### Bootstrap (First Run)

1. Extract Termux bootstrap ZIP to `/data/data/com.mobilekinetic.agent/files/usr/`
2. Install Python 3.12 via apt
3. Install Node.js 25.3
4. Install Claude Code CLI v2.0.37 via npm
5. Deploy scripts from assets to `$HOME/`

---

## 17. Permissions

### Granted Permissions

| Category | Permissions |
|----------|------------|
| Core | INTERNET, FOREGROUND_SERVICE (all types: specialUse, microphone, camera), WAKE_LOCK |
| Communication | READ_SMS, SEND_SMS, READ_CALL_LOG, READ_CONTACTS |
| Calendar | READ_CALENDAR, WRITE_CALENDAR |
| Location | ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION |
| Media | CAMERA, RECORD_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED |
| Bluetooth | BLUETOOTH, BLUETOOTH_CONNECT |
| Audio | MODIFY_AUDIO_SETTINGS, VIBRATE |
| Network | ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE |
| Notifications | POST_NOTIFICATIONS, ACCESS_NOTIFICATION_POLICY |
| Biometrics | USE_BIOMETRIC, USE_FINGERPRINT |
| System | QUERY_ALL_PACKAGES, USE_EXACT_ALARM, WRITE_SECURE_SETTINGS, RECEIVE_BOOT_COMPLETED |
| Other | NFC |

### NOT Granted (6 endpoints affected)

| Permission | Impact |
|------------|--------|
| READ_PHONE_STATE | Phone info endpoints blocked |
| CALL_PHONE | Outgoing call placement blocked |
| ANSWER_PHONE_CALLS | Call answer blocked |
| SET_ALARM | System alarm creation blocked |
| WRITE_SETTINGS | System settings write blocked |
| BLUETOOTH_SCAN | BT classic scan blocked |
| ACCESS_BACKGROUND_LOCATION | Background location blocked |
| GET_ACCOUNTS | Account listing blocked |
| PACKAGE_USAGE_STATS | Usage stats blocked |
| SCHEDULE_EXACT_ALARM | Exact alarm scheduling blocked |
| at.techbee.jtx.permission.JTX | JTX Board direct access blocked |

### Manifest Components

- **Activities**: MainActivity (NFC intent filters), TextProcessingActivity (PROCESS_TEXT)
- **Services**: MobileKineticService (foreground, specialUse|microphone|camera), MobileKineticAccessibilityService, MobileKineticNotificationListener
- **Receivers**: AlarmReceiver, GeofenceBroadcastReceiver, MobileKineticDeviceAdmin
- **Providers**: FileProvider (for MMS PDU temp files)

---

## 18. Process Lifecycle

### Runtime Process Tree

```
Android OS
  +-- com.mobilekinetic.agent (main process)
      +-- MobileKineticService (foreground)
      |   +-- NanoHTTPD thread (:5562, RAG)
      |   +-- NanoHTTPD thread (:5563, DeviceAPI)
      |   +-- Ktor CIO thread (:5565, Vault)
      |   +-- WorkManager (DecayWorker, BackupWorker)
      +-- ComposeUI (main thread)
      +-- Room/SQLCipher (IO dispatcher)
      +-- ONNX Runtime (embedding inference)
      |
      +-- [Linux processes via PTY]
          +-- /usr/bin/bash (terminal sessions)
          +-- /usr/bin/python3 agent_orchestrator.py
          |   +-- device_api_mcp.py (uvicorn, :5564)
          |   +-- claude CLI (Node.js 25.3, v2.0.37)
          |       +-- unified_device_mcp.py (MCP stdio)
          |       +-- [network] -> Anthropic API
          +-- [other user shells/processes]
```

### Lifecycle Rules

| Event | Behavior |
|-------|---------|
| Activity destroyed (swipe away) | Service continues. Claude session alive. |
| Service killed (memory pressure) | All Linux processes die. Session IDs saved in Room. |
| App reopened | Reconnect to running service. Resume session by ID. |
| Device reboot | All processes lost. Fresh bootstrap check, resume from Room DB. |
| Screen off | Wake lock keeps CPU active (if enabled). |

---

## 19. Build System

### Tech Stack (from libs.versions.toml)

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Kotlin (K2 compiler) | 2.3.0 |
| UI | Jetpack Compose + Material 3 | BOM 2026.01.01 |
| DI | Hilt (Dagger) | 2.59 |
| Database | Room + SQLCipher | 2.8.4 / 4.5.4 |
| ML - Embeddings | ONNX Runtime | 1.21.0 |
| ML - Gemma | MediaPipe tasks-genai | 0.10.27 |
| ML - LiteRT | Google AI Edge LiteRT | 2.1.0 |
| Media | Media3 ExoPlayer | 1.6.0 |
| Camera | CameraX | 1.4.1 |
| HTTP Server (DeviceAPI, RAG) | NanoHTTPD | 2.3.1 |
| HTTP Server (Vault) | Ktor CIO | 3.0.3 |
| Networking | OkHttp | 5.3.2 |
| Serialization | kotlinx.serialization | 1.10.0 |
| Coroutines | kotlinx.coroutines | 1.10.2 |
| Build | AGP + Gradle | 9.0.1 |
| KSP | Kotlin Symbol Processing | 2.3.5 |
| Navigation | Compose Navigation | 2.9.7 |
| Lifecycle | Lifecycle (runtime, viewmodel, process) | 2.10.0 |
| DataStore | Preferences DataStore | 1.2.0 |
| Biometrics | androidx.biometric-ktx | 1.4.0-alpha02 |
| Window Manager | androidx.window | 1.5.1 |
| Work | WorkManager | 2.10.0 |
| Location | Play Services Location | 21.3.0 |
| Core KTX | androidx.core | 1.17.0 |
| Activity Compose | androidx.activity | 1.12.3 |
| Min SDK | 26 (Android 8.0 Oreo) | |
| Target SDK | 36 | |
| Compile SDK | 36 | |
| ABI | arm64-v8a only | |

### Build Commands

```bash
# Build
./gradlew assembleDebug

# Install (preserves all app data including home/)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.mobilekinetic.agent/.app.MainActivity
```

---

## 20. Known Issues (2026-02-25)

| Issue | Severity | Notes |
|-------|----------|-------|
| GemmaEmbeddingProvider DISABLED | Medium | ONNX MiniLM 384-dim is sole provider; Gemma 768-dim vectors were buggy |
| Gemma-Vault bridge NOT wired | Medium | Phase 4 incomplete -- no connection between GemmaPrivacyFilter and Vault |
| Gemma text gen endpoints NOT exposed | Low | DeviceApiServer has no endpoints for Gemma text generation |
| SettingsScreen missing navigation | Low | Needs "Privacy Blacklist" nav entry and "AI Models" section |
| Tasker convenience wrappers wrong param key | Fixed | Fixed in repo 2026-02-25 |
| 6 endpoints need ungrantable permissions | Low | Phone, alarms, geofence endpoints blocked by missing runtime permissions |
| /sms/send deprecated | Info | Use /mms/send instead |
| /network/interfaces returns empty | Low | Tier 2 endpoint returns no data |
| Notification event listener not implemented | Medium | Push-based notification events not available (polling only) |
| Web/HTTP fetch on hold | Deferred | Prompt injection security concern -- needs design review |
| agent_orchestrator.py session context | Deferred | Phase 5 session context injection not yet implemented |

---

## 21. Development Tools

### ADB MCP Server (Dev PC, port 6473)

Located at `tools/adb_mcp_server.py`. Provides REST API for ADB operations from the development PC.

**WARNING**: Do NOT use raw `adb push/pull/shell` from Git Bash -- it mangles Android paths. Always use ADB MCP or PowerShell with explicit paths.

### Logcat Filter

```bash
adb logcat mK:a:* ExoPlayer:* KokoroTtsService:* TtsManager:* ChatViewModel:* AndroidRuntime:E *:F
```

---

## Appendix A: File Locations Reference

| Path | Contents |
|------|---------|
| `tools/adb_mcp_server.py` | Dev PC ADB REST API (port 6473) |
| `/data/data/com.mobilekinetic.agent/files/home/` | On-device home directory |
| `/data/data/com.mobilekinetic.agent/files/usr/` | On-device Linux environment (bash, python, node) |
| `/data/data/com.mobilekinetic.agent/files/models/` | On-device model files (re-downloadable, survive reinstall) |

---

## Appendix B: Key File Sizes

| File | Size | Lines |
|------|------|-------|
| DeviceApiServer.kt | ~500 KB | ~11,600 |
| unified_device_mcp.py | 318 KB | 5,927 |
| agent_orchestrator.py | 84 KB | 1,913 |
| ClaudeProcessManager.kt | 42 KB | 1,013 |
| mcp_tier1_server.py | - | 814 |
| mcp_shell_server.py | - | 794 |
| rlm_unified_implementation.py | - | 708 |
| mcp_tier2_server.py | - | 697 |
| device_api_mcp.py | - | 583 |
| context_injector.py | - | 549 |
| conversation_processor.py | - | 528 |
| rag_mcp_server.py | - | 521 |
| memory_lifecycle.py | - | 503 |
| vault_client.py | - | 313 |
| watchdog_diagnostic.py | - | 277 |
| mcp_tasker_server.py | - | 270 |

---

*Last updated: 2026-02-25 (v2.1 -- full audit from device, repo, RAG, and endpoint testing). This is a living document -- update as the application evolves.*
