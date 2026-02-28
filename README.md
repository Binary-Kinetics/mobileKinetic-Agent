# mK:a

**An AI-powered Android OS assistant that runs Claude Code natively on-device with full system access.**

mK:a is an Android application that combines a Termux-based Linux environment with Anthropic's Claude AI agent, giving Claude direct programmatic control over Android device capabilities through a tiered API architecture. It features an encrypted on-device RAG memory system, biometric-protected credential vault (StrongBox Titan M2), streaming text-to-speech with 3D audio visualization, and a unified MCP (Model Context Protocol) server with 55 tools.

**Target Device:** Google Pixel device
**Organization:** Binary Kinetics
**License:** GPLv3 (personal use, Termux fork)

---

## Architecture

```
+------------------------------------------------------------------+
|                    mK:a Android App                        |
|                                                                  |
|  Compose UI (Material 3, LCARS Theme)                            |
|  +----+----------+--------+--------+--------+--------+           |
|  |Chat|  Terminal | Tools  |Settings| Vault  | Setup  |           |
|  +----+----------+--------+--------+--------+--------+           |
|       |                                                          |
|  ViewModels (Hilt DI, StateFlow, Coroutines)                     |
|       |                                                          |
|  +------------------+  +------------------+  +----------------+  |
|  | Claude Process   |  | TTS Manager      |  | Conversation   |  |
|  | Manager (42KB)   |  | (Kokoro + Exo)   |  | Repository     |  |
|  +--------+---------+  +------------------+  +----------------+  |
|           |                                                      |
|  +--------v---------+  +------------------+  +----------------+  |
|  | agent_            |  | RAG System       |  | Vault          |  |
|  | orchestrator.py   |  | (ONNX MiniLM     |  | (StrongBox +   |  |
|  | (84KB)            |  |  384-dim)        |  |  AES-256-GCM)  |  |
|  +--------+---------+  | Port 5562        |  | Port 5565      |  |
|           |             +------------------+  +----------------+  |
|  +--------v--------------------------------------------------+   |
|  |              MCP Server Ecosystem                         |   |
|  |                                                           |   |
|  |  Tier 1: DeviceApiServer.kt  (port 5563, 70 endpoints)  |   |
|  |  Tier 2: device_api_mcp.py   (port 5564, FastAPI)        |   |
|  |  stdio:  unified_device_mcp.py  (55 MCP tools, 318KB)   |   |
|  |  stdio:  mcp_tier1_server.py    (Tier 1 wrapper)         |   |
|  |  stdio:  mcp_tier2_server.py    (Tier 2 wrapper)         |   |
|  |  stdio:  mcp_shell_server.py    (shell access)           |   |
|  |  stdio:  mcp_tasker_server.py   (Tasker tools)           |   |
|  |  stdio:  rag_mcp_server.py      (RAG tools)              |   |
|  +-----------------------------------------------------------+   |
|                                                                  |
|  Termux Bootstrap (bash, Python 3.12, Node.js 25.3, apt)        |
+------------------------------------------------------------------+
```

---

## Features

### AI Agent
- **Claude Code on Android** -- Full Claude agent running via Python orchestrator in Termux environment
- **Claude Code CLI v2.0.37** -- Pinned known-good arm64 version via npm
- **Bidirectional streaming** -- JSON-over-stdin/stdout protocol between Kotlin app and Python agent
- **Auto-restart** -- Health monitoring with automatic process recovery (10s intervals, 2 max retries)
- **Session management** -- Persistent session IDs in Room DB, survive app restarts

### Text-to-Speech
- **Kokoro TTS** -- WebSocket streaming to remote TTS server (`wss://your-tts-server:9199/ws/tts`)
- **Media3 ExoPlayer** -- MP3 chunks fed directly into ExoPlayer playlist for gapless playback
- **Sentence-boundary chunking** -- Intelligent text splitting (2-sentence initial, 50+ char subsequent)
- **3D audio visualization** -- Three.js sphere in WebView synchronized with audio via Android Visualizer API
- **Voice/Display separation** -- `<voice>` tags for speech, `<display>` tags for rich content

### On-Device RAG
- **ONNX embedding model** -- all-MiniLM-L6-v2 running locally (384-dim vectors)
- **WordPiece tokenizer** -- Pure Kotlin BERT-style tokenization
- **Cosine similarity search** -- Brute-force vector search with score filtering
- **HTTP REST API** -- NanoHTTPD server on port 5562 with search, context, and CRUD endpoints
- **Tool memory** -- Semantic tool discovery via natural language queries
- **Ebbinghaus memory decay** -- Memory facts decay over time, strengthened by access

### Security (5-Phase Architecture)
- **Phase 1: Vault** -- Biometric authentication via BiometricPrompt with CryptoObject binding, StrongBox Titan M2 hardware keys
- **Phase 2: Privacy Gate** -- Configurable blacklist rules for sensitive data interception
- **Phase 3: Gemma** -- On-device ML models (embedding DISABLED, ONNX MiniLM is sole active provider)
- **Phase 4: Credential Gatekeeper** -- Privacy filter for credential access
- **Phase 5: Session Memory** -- Ebbinghaus-decay memory with session summarization and SMB backup

### Database
- **SQLCipher encryption** -- Full-database AES-256-CBC encryption with Keystore-managed passphrase
- **Room v6** -- 10 entities, 8 DAOs (conversations, messages, credentials, RAG documents, tools, vault entries, blacklist rules, session summaries, memory facts)
- **Double encryption** -- Vault entries are AES-256-GCM encrypted within the already-encrypted database

### Device Control (70 Kotlin endpoints, 55 MCP tools)
- **Hardware** -- Battery, GPS, WiFi, Bluetooth (classic + BLE), sensors (all types), screen, volume, torch, vibration, NFC, camera
- **Communication** -- SMS/MMS, phone calls (place, answer, DTMF), contacts CRUD, notifications (read, dismiss, reply)
- **Calendar/Tasks** -- Calendar events, JTX Board CalDAV task integration
- **Media** -- Now playing, media control, audio routing, TTS synthesis
- **Apps** -- List installed, launch, usage stats, Custom Tabs browser
- **System** -- Settings read/write, device info, storage, downloads, alarms, geofencing, accessibility automation, device admin (lock screen)
- **Tasker** -- Full Tasker integration with pseudo-TaskList support

### UI
- **LCARS theme** -- Star Trek-inspired dark Material 3 design with orange/blue/purple accents
- **Adaptive navigation** -- Bottom bar on phones, navigation rail on tablets/foldables
- **6 main screens + 3 sub-screens** -- Chat, Terminal, Tools, Settings, Vault, Setup, Blacklist, Privacy Settings, Memory Injection Settings
- **Embedded terminal** -- Full Termux terminal via AndroidView bridge
- **Text selection overlay** -- PROCESS_TEXT intent handler for system-wide text processing

---

## Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Kotlin (K2 compiler) | 2.3.0 |
| UI | Jetpack Compose + Material 3 | BOM 2026.01.01 |
| DI | Hilt (Dagger) | 2.59 |
| Database | Room + SQLCipher | 2.8.4 / 4.5.4 |
| ML | ONNX Runtime | 1.21.0 |
| ML | MediaPipe tasks-genai | 0.10.27 |
| ML | Google AI Edge LiteRT | 2.1.0 |
| Media | Media3 ExoPlayer | 1.6.0 |
| Camera | CameraX | 1.4.1 |
| HTTP Server | NanoHTTPD (DeviceAPI, RAG) | 2.3.1 |
| HTTP Server | Ktor CIO (Vault) | 3.0.3 |
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
| WorkManager | Work Runtime KTX | 2.10.0 |
| Location | Play Services Location | 21.3.0 |
| Min SDK | 26 (Android 8.0 Oreo) | |
| Target SDK | 36 | |

---

## Port Map

| Port | Server | Framework | Description |
|------|--------|-----------|-------------|
| 5562 | RagHttpServer.kt | NanoHTTPD | On-device RAG memory API |
| 5563 | DeviceApiServer.kt | NanoHTTPD | Tier 1: Native Android APIs (70 endpoints) |
| 5564 | device_api_mcp.py | FastAPI | Tier 2: Shell/filesystem operations |
| 5565 | VaultHttpServer.kt | Ktor/CIO | Vault credential API (biometric-gated) |

All on-device HTTP servers bind exclusively to `127.0.0.1`. stdio-based MCP servers (unified_device_mcp, tier1, tier2, shell, tasker, RAG) communicate via JSON-RPC 2.0 over stdin/stdout as Claude CLI subprocesses.

---

## Module Structure

```
mK:a/
+-- app/                        Main application module
|   +-- src/main/
|   |   +-- kotlin/com/mobilekinetic/agent/
|   |   |   +-- app/            MobileKineticApp, MobileKineticService, BootstrapInstaller,
|   |   |   |                   MainActivity, ScriptManager
|   |   |   +-- claude/         ClaudeProcessManager (42KB, process lifecycle, IPC)
|   |   |   +-- data/
|   |   |   |   +-- chat/       ConversationRepository, ResponseParser
|   |   |   |   +-- db/         MobileKineticDatabase (v6), DAOs, entities, SQLCipher
|   |   |   |   +-- gemma/      GemmaModelManager, GemmaTextGenerator, downloads
|   |   |   |   +-- memory/     MemoryDecayEngine, SessionSummarizer, BackupWorker
|   |   |   |   +-- model/      ChatMessage, Conversation data classes
|   |   |   |   +-- preferences/ SettingsDataStore
|   |   |   |   +-- rag/        EmbeddingModel, RagRepository, RagHttpServer, ToolMemory
|   |   |   |   +-- settings/   InjectionSettingsRepository
|   |   |   |   +-- tts/        TtsManager, TtsSettingsRepository, AudioVisualizerBridge
|   |   |   |   +-- vault/      VaultRepository, CredentialVault, VaultHttpServer
|   |   |   +-- device/api/     DeviceApiServer (631KB, 70 endpoints),
|   |   |   |                   AccessibilityService, DeviceAdmin, NotificationListener
|   |   |   +-- di/             AppModule (Hilt providers)
|   |   |   +-- privacy/        PrivacyGate, PrivacyGateRuleSync, GemmaPrivacyFilter
|   |   |   +-- receiver/       AlarmReceiver, GeofenceBroadcastReceiver
|   |   |   +-- security/       BiometricAuthManager, VaultKeyManager,
|   |   |   |                   CredentialVaultKeyManager, CredentialGatekeeper
|   |   |   +-- ui/
|   |   |       +-- components/ AudioVisualizer (Three.js WebView)
|   |   |       +-- navigation/ Route definitions (type-safe)
|   |   |       +-- screens/    Chat, Settings, Setup, Tools, Vault, Terminal,
|   |   |       |               Blacklist, PrivacySettings, MemoryInjectionSettings
|   |   |       +-- theme/      LCARS color scheme, typography, shapes
|   |   |       +-- viewmodel/  Chat, Settings, Tools, Vault, Blacklist,
|   |   |                       PrivacySettings, InjectionSettings ViewModels
|   |   +-- assets/
|   |   |   +-- scripts/        Python on-device scripts (deployed to $HOME/)
|   |   |   |   +-- agent_orchestrator.py       (84KB, main entry)
|   |   |   |   +-- unified_device_mcp.py       (318KB, 55 MCP tools)
|   |   |   |   +-- context_injector.py, conversation_processor.py
|   |   |   |   +-- memory_lifecycle.py, rag_mcp_server.py
|   |   |   |   +-- mcp_shell_server.py, mcp_tier1_server.py, mcp_tier2_server.py
|   |   |   |   +-- mcp_tasker_server.py, device_api_mcp.py
|   |   |   |   +-- vault_client.py, secure_vault.py
|   |   |   |   +-- rlm_unified_implementation.py
|   |   |   |   +-- caldav_client.py, caldav_server.py
|   |   |   |   +-- config/    mcp_config.json, toolDescriptions.json
|   |   |   |   +-- docs/      CLAUDE.md, guides
|   |   |   |   +-- shell/     backup/restore/promote scripts
|   |   |   +-- models/         ONNX model + vocab for RAG embeddings
|   |   |   +-- visualizer/     visualizer.html (Three.js 3D audio sphere)
|   |   +-- res/                Resources (icons, strings, XML configs)
|   |   +-- AndroidManifest.xml 49 permissions
|   +-- build.gradle.kts
+-- terminal-emulator/          Termux terminal emulation backend (Java/C, JNI)
+-- terminal-view/              Termux terminal rendering (Compose AndroidView bridge)
+-- shared/                     Shared constants (MobileKineticConstants)
+-- tools/
|   +-- adb_mcp_server.py       Dev PC ADB REST API
|   +-- adb_mcp_server.py
+-- docs/
|   +-- APP_IMPROVEMENTS_FOR_BUILD.md
|   +-- watchdog/               Watchdog integration guides
+-- gradle/libs.versions.toml   Version catalog
+-- build.gradle.kts            Root build file
+-- settings.gradle.kts         Module definitions
```

---

## Database Schema

Room Database v6 with SQLCipher encryption. 10 entities, 8 DAOs:

| Table | Purpose | Key Fields |
|-------|---------|------------|
| `conversations` | Chat session metadata | id, title, claudeSessionId, timestamps |
| `messages` | Individual chat messages | conversationId (FK), role, rawContent, toolName, costUsd |
| `credentials` | Encrypted credential store | name, category, encryptedValue (AES-256-GCM) |
| `rag_documents` | RAG vector store | text, category, embedding (384-dim BLOB), metadata |
| `tools` | Tool registry with approval | name, executionType, schemaJson, isUserApproved, useCount |
| `tool_usage` | Execution audit log | toolId (FK), inputJson, resultJson, isSuccess, executionTimeMs |
| `vault_entries` | Biometric vault (double-encrypted) | name, encryptedValue, iv |
| `blacklist_rules` | Privacy gate rules | pattern, action, scope |
| `session_summaries` | Compressed session context | summary, tokens, timestamp |
| `memory_facts` | Ebbinghaus-decayed facts | text, strength, lastAccess, decayRate |

---

## Security Model

### Vault (Credential Storage)
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
- Double encryption: field-level AES-256-GCM + database-level SQLCipher

### Database
- SQLCipher full-database encryption (AES-256-CBC)
- Passphrase generated via `SecureRandom` (32 bytes)
- Passphrase encrypted with AES-256-GCM and stored in SharedPreferences
- Keystore key (alias: `mobilekinetic_db_key`) protects the passphrase

### Process Isolation
- All on-device servers bind to localhost only
- Termux runs in Android app sandbox
- MCP servers use stdio (no network sockets)
- fs-guard.js prevents access to `/storage/emulated` (FUSE deadlock prevention)

---

## Startup Sequence

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
  |     |     +-- NeedsSetup -> SetupScreen (first run: extract bootstrap)
  |     |     +-- Ready -> Main UI
  |     +-- Start MobileKineticService (foreground)
  |
  +-- MobileKineticService.onCreate()
        +-- Foreground notification (IMPORTANCE_LOW)
        +-- RAG system: EmbeddingModel + RagHttpServer (port 5562)
        +-- Device API: DeviceApiServer (port 5563)
        +-- Vault: VaultHttpServer (port 5565)
        +-- ScriptManager.init() -> extract scripts, merge MCP config
        +-- ClaudeProcessManager.start() -> spawn agent_orchestrator.py
              +-- stdout reader coroutine
              +-- stderr reader coroutine
              +-- health monitor coroutine (10s interval)
```

---

## TTS Pipeline

```
ChatViewModel (sentence-boundary detection)
  -> TtsManager.speak(chunk) -> ConcurrentLinkedQueue
    -> KokoroTtsService.speak() -> WebSocket -> MP3 chunks
      -> ByteArrayDataSource -> ProgressiveMediaSource -> ExoPlayer playlist
        -> AudioVisualizerBridge.attach(audioSessionId)
          -> Android Visualizer (FFT + waveform)
            -> Three.js sphere animation in WebView
```

- First chunk: 2 complete sentences
- Subsequent chunks: 50+ chars to next sentence ending
- Final fragment: 1.5s timer then flush
- Default voice: `af_kore` at 1.2x speed

---

## On-Device Python Scripts

| Script | Size | Purpose | Communication |
|--------|------|---------|---------------|
| `agent_orchestrator.py` | 84KB | Claude Agent SDK bridge, process management | stdin/stdout JSON |
| `unified_device_mcp.py` | 318KB | 55 MCP tools (RAG + Tasker + Tier1 + Tier2 + HA + Vault) | stdio JSON-RPC |
| `context_injector.py` | 23KB | System prompt builder with memory injection | Imported |
| `conversation_processor.py` | 21KB | Stream parser for Claude output | Imported |
| `memory_lifecycle.py` | 20KB | Ebbinghaus decay logic | Imported |
| `device_api_mcp.py` | 19KB | Tier 2 shell/filesystem API (FastAPI) | HTTP :5564 |
| `rag_mcp_server.py` | 18KB | RAG memory search/store MCP | stdio JSON-RPC |
| `mcp_shell_server.py` | 28KB | Secure shell execution MCP | stdio JSON-RPC |
| `mcp_tier1_server.py` | 27KB | MCP wrapper for Tier 1 Kotlin API | stdio JSON-RPC |
| `mcp_tier2_server.py` | 26KB | MCP wrapper for Tier 2 Python API | stdio JSON-RPC |
| `mcp_tasker_server.py` | 9KB | Tasker automation tools | stdio JSON-RPC |
| `rlm_unified_implementation.py` | 25KB | Recursive Language Model engine | Imported |
| `vault_client.py` | 12KB | Vault API client (calls :5565) | HTTP client |
| `secure_vault.py` | 6KB | CLI password manager (Fernet/PBKDF2) | CLI |

Scripts are bundled in `assets/scripts/` and deployed to `$HOME/` by ScriptManager on app install/update.

---

## Permissions (49)

| Category | Permissions |
|----------|------------|
| Core | INTERNET, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_CAMERA, MANAGE_EXTERNAL_STORAGE |
| Location | ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION |
| Network | ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, READ_PHONE_STATE |
| Bluetooth | BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT |
| Phone | CALL_PHONE, ANSWER_PHONE_CALLS |
| Communication | READ_SMS, SEND_SMS, READ_CALL_LOG, READ_CONTACTS |
| Calendar | READ_CALENDAR, WRITE_CALENDAR |
| Device | VIBRATE, GET_ACCOUNTS, CAMERA, MODIFY_AUDIO_SETTINGS, ACCESS_NOTIFICATION_POLICY, WRITE_SETTINGS, USE_BIOMETRIC |
| NFC | NFC |
| Media/UI | POST_NOTIFICATIONS, READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE, DOWNLOAD_WITHOUT_NOTIFICATION |
| Alarms | SET_ALARM, SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED |
| System | PACKAGE_USAGE_STATS, WRITE_SECURE_SETTINGS, QUERY_ALL_PACKAGES |
| JTX Board | READ, WRITE, JTX |

---

## Building

### Prerequisites
- Android Studio with AGP 9.0.1+
- JDK 17+
- Android SDK 36

### Build
```bash
./gradlew assembleDebug
```

### Deploy
```bash
# Preserves all app data (home/, Room DB, models)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### First Run
The app will display the SetupScreen to extract the Termux bootstrap environment (~100MB), install Python 3.12, Node.js 25.3, and Claude Code CLI v2.0.37. This is a one-time operation.

---

## Development Tools

### ADB MCP Server (Dev PC)
```bash
python tools/adb_mcp_server.py  # Port 6473
```

### Logcat Filter
```bash
adb logcat mK:a:* ExoPlayer:* KokoroTtsService:* TtsManager:* ChatViewModel:* AndroidRuntime:E *:F
```

---

## Navigation

Type-safe Compose Navigation with adaptive layout:

| Route | Screen | Bottom Nav | Description |
|-------|--------|-----------|-------------|
| Chat | ChatScreen | Yes | Primary conversational interface |
| Terminal | TerminalScreen | Yes | Full Termux terminal (AndroidView bridge) |
| Tools | ToolsScreen | Yes | Searchable tool registry with approval management |
| Settings | SettingsScreen | Yes | TTS config, Claude process, model selection |
| Vault | VaultScreen | Yes | Biometric-protected credential manager |
| Setup | SetupScreen | No | First-run bootstrap wizard |

`NavigationSuiteScaffold` renders as bottom bar on phones, navigation rail on tablets/foldables.
