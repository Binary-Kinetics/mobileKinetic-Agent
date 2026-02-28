package com.mobilekinetic.agent.claude

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import com.mobilekinetic.agent.data.DiagnosticLogger
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.preferences.UserPreferences
import com.mobilekinetic.agent.data.vault.CredentialVault
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/**
 * Manages the lifecycle of the Python orchestrator process that wraps the Claude Agent SDK.
 *
 * Communication is via JSON over stdin/stdout of the child process:
 *   - Android writes JSON messages to the process stdin (user prompts, control)
 *   - The process writes JSON messages to stdout (assistant responses, results, stream events)
 *
 * Exposes a SharedFlow<Message> for the ViewModel layer to collect.
 */
@Singleton
class ClaudeProcessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val credentialVault: CredentialVault
) {
    companion object {
        private const val TAG = "ClaudeProcessManager"
        private const val ORCHESTRATOR_SCRIPT = "agent_orchestrator.py"
        private const val RESTART_DELAY_MS = 5000L
        private const val SHUTDOWN_TIMEOUT_MS = 5000L
        private const val MAX_RESTART_ATTEMPTS = 2
        private const val READ_TIMEOUT_MS = 30000L // 30 second timeout for readLine()
        private const val HEALTH_CHECK_INTERVAL_MS = 10000L // 10 second health check interval

        /** Formatter for the timestamp prefix added to each user message, e.g. [2026-02-24 15:30:22] */
        private val MSG_TIMESTAMP_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    // ---- Message sealed class hierarchy ----

    sealed class Message {
        abstract val raw: JsonObject?
    }

    /** Claude's text / tool-use / thinking response */
    data class AssistantMessage(
        val content: List<ContentBlock>,
        val model: String?,
        val parentToolUseId: String?,
        val error: AssistantError?,
        override val raw: JsonObject? = null
    ) : Message()

    /** Echo of user message or tool result notification */
    data class UserMessageEcho(
        val content: String?,
        val uuid: String?,
        val parentToolUseId: String?,
        override val raw: JsonObject? = null
    ) : Message()

    /** System events: hook_start, hook_end, session_info, etc. */
    data class SystemMessage(
        val subtype: String,
        val data: JsonObject?,
        override val raw: JsonObject? = null
    ) : Message()

    /** Query completion with usage stats */
    data class ResultMessage(
        val subtype: String,
        val durationMs: Long,
        val durationApiMs: Long,
        val isError: Boolean,
        val numTurns: Int,
        val sessionId: String,
        val totalCostUsd: Float?,
        val usage: JsonObject?,
        val result: String?,
        override val raw: JsonObject? = null
    ) : Message()

    /** Partial streaming event (content_block_delta, etc.) */
    data class StreamEvent(
        val uuid: String?,
        val sessionId: String?,
        val event: JsonObject?,
        val parentToolUseId: String?,
        override val raw: JsonObject? = null
    ) : Message()

    /** Internal error from the process manager itself */
    data class ErrorMessage(
        val error: String,
        val details: String? = null,
        override val raw: JsonObject? = null
    ) : Message()

    // ---- Content block types ----

    sealed class ContentBlock
    data class TextBlock(val text: String) : ContentBlock()
    data class ThinkingBlock(val thinking: String, val signature: String?) : ContentBlock()
    data class ToolUseBlock(val id: String, val name: String, val input: JsonObject?) : ContentBlock()
    data class ToolResultBlock(val toolUseId: String, val content: String?, val isError: Boolean?) : ContentBlock()

    data class AssistantError(val type: String, val message: String?)

    // ---- State ----

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var readJob: Job? = null
    private var healthCheckJob: Job? = null
    private var heartbeatJob: Job? = null
    private var restartAttempts = 0
    private val processMutex = Mutex()

    private val _messages = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _selectedModel = MutableStateFlow("sonnet")

    // Live model list from Anthropic API
    data class ModelInfo(val id: String, val displayName: String, val createdAt: String)

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    private val _subAgentCount = MutableStateFlow(0)
    val subAgentCount: StateFlow<Int> = _subAgentCount.asStateFlow()

    private val _subAgentStatus = MutableStateFlow("none")  // "none" | "active" | "stale"
    val subAgentStatus: StateFlow<String> = _subAgentStatus.asStateFlow()

    private val _mcpAlive = MutableStateFlow(true)
    val mcpAlive: StateFlow<Boolean> = _mcpAlive.asStateFlow()

    private val _subAgentCrashed = MutableStateFlow(false)
    val subAgentCrashed: StateFlow<Boolean> = _subAgentCrashed.asStateFlow()

    init {
        settingsRepository.modelSelection
            .onEach { model ->
                _selectedModel.value = model
                Log.d(TAG, "Model selection updated: $model")
            }
            .launchIn(scope)

        // Observe heartbeat settings and restart loop when they change
        settingsRepository.heartbeatEnabled
            .onEach { enabled ->
                Log.d(TAG, "Heartbeat enabled changed: $enabled")
                restartHeartbeatIfNeeded()
            }
            .launchIn(scope)

        settingsRepository.heartbeatPrompt
            .onEach { prompt ->
                Log.d(TAG, "Heartbeat prompt changed: ${prompt.take(50)}")
                restartHeartbeatIfNeeded()
            }
            .launchIn(scope)

        settingsRepository.heartbeatIntervalMinutes
            .onEach { minutes ->
                Log.d(TAG, "Heartbeat interval changed: ${minutes}min")
                restartHeartbeatIfNeeded()
            }
            .launchIn(scope)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---- Public API ----

    /**
     * Start the Python orchestrator process.
     * Idempotent -- does nothing if already running.
     */
    fun start() {
        if (_isRunning.value) {
            Log.d(TAG, "start() called but already running")
            return
        }
        scope.launch { startProcess() }
    }

    /**
     * Stop the orchestrator process gracefully.
     * Closes stdin -> sends SIGTERM -> waits up to 5 seconds.
     */
    fun stop() {
        scope.launch { stopProcess() }
    }

    /**
     * Send a user message to Claude via the orchestrator.
     *
     * @param content The user prompt text.
     * @param sessionId Session identifier for conversation continuity.
     */
    fun sendMessage(content: String, sessionId: String = "default") {
        scope.launch {
            if (!_isRunning.value) {
                _messages.tryEmit(ErrorMessage("Process not running", "Call start() before sending messages"))
                return@launch
            }
            _isStreaming.value = true
            _lastError.value = null

            // Prepend a timestamp so Claude has temporal awareness of when each message was sent.
            // Format: [YYYY-MM-DD HH:mm:ss] <original content>
            val timestamp = LocalDateTime.now().format(MSG_TIMESTAMP_FMT)
            val timestampedContent = "[$timestamp] $content"

            val messageJson = JsonObject(mapOf(
                "type" to JsonPrimitive("user"),
                "message" to JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "content" to JsonPrimitive(timestampedContent)
                )),
                "session_id" to JsonPrimitive(sessionId)
            ))

            DiagnosticLogger.startTrace("orchestrator_query")
            DiagnosticLogger.log("MSG_TO_ORCHESTRATOR", "chars=${content.length}, session=$sessionId, preview='${content.take(80)}'")
            writeToStdin(messageJson.toString())
        }
    }

    /**
     * Interrupt the current generation.
     */
    fun interrupt() {
        scope.launch {
            if (!_isRunning.value) return@launch

            val controlJson = JsonObject(mapOf(
                "type" to JsonPrimitive("control"),
                "action" to JsonPrimitive("interrupt")
            ))

            writeToStdin(controlJson.toString())
            _isStreaming.value = false
        }
    }

    /**
     * Request available models from Anthropic API via the orchestrator.
     */
    fun requestModels() {
        scope.launch {
            if (!_isRunning.value) return@launch
            _isLoadingModels.value = true
            val json = JsonObject(mapOf("type" to JsonPrimitive("list_models")))
            writeToStdin(json.toString())
        }
    }

    fun isRunning(): Boolean = _isRunning.value

    // ---- Process lifecycle ----

    private suspend fun startProcess() = processMutex.withLock {
        if (process?.isAlive == true) {
            Log.w(TAG, "startProcess() called but process is already alive, skipping")
            return@withLock
        }
        try {
            val home = File(context.filesDir, "home")
            val prefix = File(context.filesDir, "usr")
            val scriptPath = File(home, ORCHESTRATOR_SCRIPT).absolutePath

            if (!File(scriptPath).exists()) {
                val msg = "Orchestrator script not found at $scriptPath"
                Log.e(TAG, msg)
                _lastError.value = msg
                _messages.tryEmit(ErrorMessage(msg, "Deploy $ORCHESTRATOR_SCRIPT to ${home.absolutePath}"))
                return@withLock
            }

            val python3 = File(prefix, "bin/python3").absolutePath
            if (!File(python3).exists()) {
                val msg = "Python3 not found at $python3"
                Log.e(TAG, msg)
                _lastError.value = msg
                _messages.tryEmit(ErrorMessage(msg, "Install Python: apt install python"))
                return@withLock
            }

            // Phase 2C: Read current user preferences for MKA_* env vars
            val userPrefs: UserPreferences = settingsRepository.userPreferences.first()

            // Read API key from CredentialVault (AES-256-GCM, no biometric gate)
            val apiKey: String? = try {
                credentialVault.get("ANTHROPIC_API_KEY")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read API key from vault: ${e.message}")
                null
            }

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val env = buildMap {
                // HOME must be internal filesDir/home — NOT external storage.
                // fs-guard.js blocks /storage/emulated (see badc7a0 revert).
                put("HOME", home.absolutePath)
                put("PREFIX", prefix.absolutePath)
                put("TMPDIR", File(prefix, "tmp").absolutePath)
                put("PATH", "$nativeLibDir:${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets")
                put("LD_LIBRARY_PATH", "$nativeLibDir:${prefix.absolutePath}/lib")
                put("LANG", "en_US.UTF-8")
                put("TERM", "xterm-256color")
                put("SHELL", "$nativeLibDir/libbash.so")
                put("PYTHONUNBUFFERED", "1")
                // termux-exec hooks execve to allow execution from app data dirs
                // (Android W^X enforcement blocks direct exec from app_data_file context)
                put("LD_PRELOAD", "$nativeLibDir/libtermux-exec.so")
                // Let the orchestrator know the RAG endpoint
                put("RAG_ENDPOINT", "http://127.0.0.1:5562")
                // Pass selected model to orchestrator
                put("ANTHROPIC_MODEL", _selectedModel.value)
                // Inject API key from vault if available
                if (!apiKey.isNullOrBlank()) {
                    put("ANTHROPIC_API_KEY", apiKey)
                }

                // Phase 2C: MKA_* env vars — user preferences for on-device Claude
                put("MKA_USER_NAME", userPrefs.userName.ifEmpty { "User" })
                put("MKA_DEVICE_NAME", userPrefs.deviceName.ifEmpty { android.os.Build.MODEL })
                put("MKA_TTS_HOST", userPrefs.ttsServerHost)
                put("MKA_TTS_PORT", userPrefs.ttsServerPort.toString())
                put("MKA_TTS_WSS_URL", userPrefs.ttsWssUrl)
                put("MKA_HA_URL", userPrefs.haServerUrl)
                put("MKA_NAS_IP", userPrefs.nasIp)
                put("MKA_SWITCHBOARD_URL", userPrefs.switchboardUrl)
                put("MKA_LOCAL_MODEL_URL", userPrefs.localModelUrl)
                put("MKA_DOMAIN", userPrefs.personalDomain)
            }

            // Launch python3 through shell so LD_LIBRARY_PATH is set before
            // the dynamic linker resolves shared libraries (e.g. libandroid-support.so).
            // ProcessBuilder.environment() only sets env for the child AFTER exec,
            // but the linker needs LD_LIBRARY_PATH at link time.
            val shell = "$nativeLibDir/libbash.so"
            val ldPath = "$nativeLibDir:${prefix.absolutePath}/lib"
            Log.i(TAG, "Starting orchestrator via shell: $shell -c LD_LIBRARY_PATH=$ldPath $python3 $scriptPath")

            val pb = ProcessBuilder(
                shell, "-c",
                "export LD_LIBRARY_PATH='$ldPath' && exec $python3 $scriptPath"
            )
            pb.directory(home)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false) // keep stderr separate

            process = pb.start()
            stdinWriter = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            stdoutReader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

            _isRunning.value = true
            _lastError.value = null
            restartAttempts = 0

            Log.i(TAG, "Orchestrator process started (pid available via process handle)")
            DiagnosticLogger.log("PROCESS_START", "model=${_selectedModel.value}, python=$python3, script=$scriptPath")

            // Start reading stdout in a coroutine
            readJob = scope.launch { readStdoutLoop() }

            // Start reading stderr in a separate coroutine (log only)
            scope.launch { readStderrLoop() }

            // Start periodic health check
            healthCheckJob = scope.launch { processHealthCheckLoop() }

            // Start heartbeat timer if enabled
            restartHeartbeatIfNeeded()

        } catch (e: Exception) {
            val msg = "Failed to start orchestrator: ${e.message}"
            Log.e(TAG, msg, e)
            _isRunning.value = false
            _lastError.value = msg
            _messages.tryEmit(ErrorMessage(msg, e.stackTraceToString()))
        }
    }

    private suspend fun stopProcess() {
        Log.i(TAG, "Stopping orchestrator process")
        restartAttempts = MAX_RESTART_ATTEMPTS
        _isRunning.value = false
        _isStreaming.value = false
        readJob?.cancel()
        healthCheckJob?.cancel()
        heartbeatJob?.cancel()

        try {
            // 1. Close stdin to signal the process to exit
            stdinWriter?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stdin: ${e.message}")
        }

        val proc = process
        if (proc != null) {
            try {
                // 2. Send SIGTERM (destroy)
                proc.destroy()

                // 3. Wait up to SHUTDOWN_TIMEOUT_MS
                withContext(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()
                    while (proc.isAlive && (System.currentTimeMillis() - startTime) < SHUTDOWN_TIMEOUT_MS) {
                        delay(100)
                    }
                    if (proc.isAlive) {
                        Log.w(TAG, "Process did not exit in time, force destroying")
                        proc.destroyForcibly()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during process shutdown: ${e.message}")
            }
        }

        // Kill any orphaned Claude CLI (node) processes
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val home = File(context.filesDir, "home")
            val prefix = File(context.filesDir, "usr")
            val ldPath = "$nativeLibDir:${prefix.absolutePath}/lib"
            val killProc = ProcessBuilder(
                "$nativeLibDir/libbash.so", "-c",
                "export LD_LIBRARY_PATH='$ldPath' && pkill -f 'node.*cli\\.js' 2>/dev/null; pkill -f 'node.*claude' 2>/dev/null"
            )
            killProc.directory(home)
            killProc.environment().putAll(mapOf(
                "PATH" to "$nativeLibDir:${prefix.absolutePath}/bin",
                "LD_LIBRARY_PATH" to ldPath,
                "LD_PRELOAD" to "$nativeLibDir/libtermux-exec.so",
                "TMPDIR" to "${prefix.absolutePath}/tmp"
            ))
            val kp = killProc.start()
            withContext(Dispatchers.IO) { kp.waitFor() }
            Log.i(TAG, "Killed orphaned Claude CLI node processes")
        } catch (e: Exception) {
            Log.w(TAG, "Error killing orphaned node processes: ${e.message}")
        }

        process = null
        stdinWriter = null
        stdoutReader = null

        // Clean up stale Claude CLI temp files that cause hangs on next launch
        try {
            val home = File(context.filesDir, "home")
            val prefix = File(context.filesDir, "usr")

            // Remove stale CWD tracking files
            File(home, "tmp").listFiles()?.filter {
                it.name.matches(Regex("claude-.*-cwd"))
            }?.forEach { it.delete() }
            File(prefix, "tmp").listFiles()?.filter {
                it.name.matches(Regex("claude-.*-cwd"))
            }?.forEach { it.delete() }

            // Remove stale session-env directories
            File(home, ".claude/session-env").listFiles()?.forEach {
                it.deleteRecursively()
            }

            Log.i(TAG, "Cleaned up stale Claude CLI temp files")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up stale Claude files: ${e.message}")
        }

        Log.i(TAG, "Orchestrator process stopped")
    }

    private suspend fun handleProcessExit() {
        _isRunning.value = false
        _isStreaming.value = false
        _subAgentCount.value = 0
        _subAgentStatus.value = "none"
        _mcpAlive.value = true
        _subAgentCrashed.value = false

        val exitCode = try {
            process?.waitFor() ?: -1
        } catch (e: Exception) {
            -1
        }

        Log.w(TAG, "Orchestrator process exited with code $exitCode")
        DiagnosticLogger.logError("PROCESS_DIED", "exitCode=$exitCode, restartAttempts=$restartAttempts/$MAX_RESTART_ATTEMPTS")

        // Clean up orphaned processes before potential restart
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val home = File(context.filesDir, "home")
            val prefix = File(context.filesDir, "usr")
            val ldPath = "$nativeLibDir:${prefix.absolutePath}/lib"
            val killProc = ProcessBuilder(
                "$nativeLibDir/libbash.so", "-c",
                "export LD_LIBRARY_PATH='$ldPath' && pkill -f 'node.*cli\\.js' 2>/dev/null; pkill -f 'node.*claude' 2>/dev/null; pkill -f 'agent_orchestrator' 2>/dev/null"
            )
            killProc.directory(home)
            killProc.environment().putAll(mapOf(
                "PATH" to "$nativeLibDir:${prefix.absolutePath}/bin",
                "LD_LIBRARY_PATH" to ldPath,
                "LD_PRELOAD" to "$nativeLibDir/libtermux-exec.so",
                "TMPDIR" to "${prefix.absolutePath}/tmp"
            ))
            val kp = killProc.start()
            withContext(Dispatchers.IO) { kp.waitFor() }
            Log.i(TAG, "Cleaned up orphaned processes before restart")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up orphaned processes: ${e.message}")
        }

        if (restartAttempts < MAX_RESTART_ATTEMPTS) {
            restartAttempts++
            // Exponential backoff: 2s for first restart, 5s for second
            val backoffDelayMs = when (restartAttempts) {
                1 -> 2000L
                2 -> 5000L
                else -> RESTART_DELAY_MS
            }
            val msg = "Orchestrator crashed (exit $exitCode), restarting (attempt $restartAttempts/$MAX_RESTART_ATTEMPTS)..."
            Log.i(TAG, msg)
            DiagnosticLogger.log("RESTART_BACKOFF", "attempt=$restartAttempts/$MAX_RESTART_ATTEMPTS, exitCode=$exitCode, backoffDelayMs=$backoffDelayMs")
            _messages.tryEmit(SystemMessage(
                subtype = "process_restart",
                data = JsonObject(mapOf(
                    "exit_code" to JsonPrimitive(exitCode),
                    "attempt" to JsonPrimitive(restartAttempts)
                ))
            ))
            delay(backoffDelayMs)
            if (process?.isAlive == true) {
                Log.w(TAG, "handleProcessExit() found process already alive after delay, skipping restart")
                return
            }
            startProcess()
        } else {
            val msg = "Orchestrator crashed (exit $exitCode) after $MAX_RESTART_ATTEMPTS restart attempts"
            Log.e(TAG, msg)
            _lastError.value = msg
            _messages.tryEmit(ErrorMessage(msg, "Manual restart required"))
        }
    }

    // ---- I/O loops ----

    private suspend fun readStdoutLoop() {
        try {
            val reader = stdoutReader ?: return
            var jsonBuffer = StringBuilder()
            var consecutiveTimeouts = 0

            while (scope.isActive && _isRunning.value) {
                // Read with timeout to prevent indefinite blocking
                val line = try {
                    withTimeoutOrNull(READ_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            reader.readLine()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error during readLine(): ${e.message}")
                    null
                }

                // null could be timeout or stream closed
                if (line == null) {
                    // Check if process is still alive
                    val proc = process
                    if (proc == null || !proc.isAlive) {
                        Log.w(TAG, "Process terminated, exiting read loop")
                        break
                    }
                    // Retry logic: first timeout gets one retry, second consecutive timeout proceeds with error handling
                    consecutiveTimeouts++
                    if (consecutiveTimeouts == 1) {
                        DiagnosticLogger.log("READ_TIMEOUT", "timeoutMs=$READ_TIMEOUT_MS, isStreaming=${_isStreaming.value}, action=retry")
                        Log.w(TAG, "readLine() timeout after ${READ_TIMEOUT_MS}ms, retrying once...")
                        continue
                    }
                    // Second consecutive timeout - proceed with existing handling
                    DiagnosticLogger.log("READ_TIMEOUT", "timeoutMs=$READ_TIMEOUT_MS, isStreaming=${_isStreaming.value}, consecutiveTimeouts=$consecutiveTimeouts, action=continue")
                    Log.w(TAG, "readLine() consecutive timeout #$consecutiveTimeouts after ${READ_TIMEOUT_MS}ms, continuing...")
                    consecutiveTimeouts = 0
                    continue
                }

                // Successful read resets the consecutive timeout counter
                consecutiveTimeouts = 0

                jsonBuffer.append(line.trim())

                // Try to parse accumulated buffer as JSON
                val bufferStr = jsonBuffer.toString()
                if (bufferStr.isBlank()) continue

                try {
                    val jsonElement = json.parseToJsonElement(bufferStr)
                    jsonBuffer = StringBuilder() // clear on success

                    if (jsonElement is JsonObject) {
                        val msgType = jsonElement["type"]?.jsonPrimitive?.content ?: "unknown"
                        DiagnosticLogger.log("MSG_FROM_ORCHESTRATOR", "type=$msgType, chars=${bufferStr.length}, preview='${bufferStr.take(120)}'")
                        val message = parseMessage(jsonElement)
                        if (message != null) {
                            // Intercept models_list — internal, don't emit to chat
                            if (message is SystemMessage && message.subtype == "models_list") {
                                val modelsArray = message.data?.get("models")?.jsonArray
                                val models = modelsArray?.mapNotNull { elem ->
                                    val obj = elem.jsonObject
                                    ModelInfo(
                                        id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                                        displayName = obj["display_name"]?.jsonPrimitive?.content ?: "",
                                        createdAt = obj["created_at"]?.jsonPrimitive?.content ?: ""
                                    )
                                } ?: emptyList()
                                _availableModels.value = models
                                _isLoadingModels.value = false
                            } else {
                                _messages.tryEmit(message)

                                // Detect end of streaming response
                                if (message is ResultMessage) {
                                    DiagnosticLogger.endTrace("orchestrator_query")
                                    _isStreaming.value = false
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.serialization.SerializationException) {
                    // Incomplete JSON -- keep accumulating
                    if (jsonBuffer.length > 1_048_576) { // 1 MB limit
                        DiagnosticLogger.logError("JSON_BUFFER_OVERFLOW", "bufferSize=${jsonBuffer.length}, discarding. Last 200 chars='${bufferStr.takeLast(200)}'")
                        Log.e(TAG, "JSON buffer exceeded 1MB, discarding")
                        jsonBuffer = StringBuilder()
                    } else {
                        DiagnosticLogger.log("JSON_PARSE_ERROR", "SerializationException, bufferSize=${jsonBuffer.length}, raw='${bufferStr.take(200)}'")
                    }
                } catch (e: IllegalArgumentException) {
                    // Malformed JSON -- keep accumulating
                    if (jsonBuffer.length > 1_048_576) {
                        DiagnosticLogger.logError("JSON_BUFFER_OVERFLOW", "bufferSize=${jsonBuffer.length}, discarding. Last 200 chars='${bufferStr.takeLast(200)}'")
                        Log.e(TAG, "JSON buffer exceeded 1MB, discarding")
                        jsonBuffer = StringBuilder()
                    } else {
                        DiagnosticLogger.log("JSON_PARSE_ERROR", "IllegalArgumentException, bufferSize=${jsonBuffer.length}, raw='${bufferStr.take(200)}'")
                    }
                }
            }
        } catch (e: Exception) {
            if (_isRunning.value) {
                Log.e(TAG, "Error reading stdout: ${e.message}", e)
            }
        }

        // Process exited -- attempt restart if not intentionally stopped
        if (_isRunning.value) {
            handleProcessExit()
        }
    }

    private suspend fun readStderrLoop() {
        try {
            val proc = process ?: return
            val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
            while (scope.isActive) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break

                if (line.startsWith("[status]")) {
                    parseStatusLine(line)
                } else if (line.startsWith("[event] subagent_crashed")) {
                    _subAgentCrashed.value = true
                    scope.launch {
                        delay(5000)
                        _subAgentCrashed.value = false
                    }
                }

                DiagnosticLogger.log("STDERR_OUTPUT", "line='${line.take(200)}'")
                Log.d(TAG, "[stderr] $line")
            }
        } catch (e: Exception) {
            // Suppress -- don't crash main flow
            Log.d(TAG, "Stderr reader stopped: ${e.message}")
        }
    }

    private fun parseStatusLine(line: String) {
        try {
            val parts = line.removePrefix("[status] ").split(" ")
            val map = parts.associate { part ->
                val (key, value) = part.split("=", limit = 2)
                key to value
            }

            map["subagent_count"]?.toIntOrNull()?.let { _subAgentCount.value = it }
            map["mcp_alive"]?.let { _mcpAlive.value = it == "true" }

            val stalePids = map["stale_pids"]?.takeIf { it.isNotEmpty() }
            val activePids = map["active_pids"]?.takeIf { it.isNotEmpty() }

            _subAgentStatus.value = when {
                stalePids != null -> "stale"
                activePids != null -> "active"
                else -> "none"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse status line: ${e.message}")
        }
    }

    private suspend fun writeToStdin(jsonString: String) {
        try {
            withContext(Dispatchers.IO) {
                val writer = stdinWriter
                if (writer != null) {
                    synchronized(writer) {
                        writer.write(jsonString)
                        writer.write("\n")
                        writer.flush()
                    }
                } else {
                    Log.w(TAG, "Cannot write to stdin: writer is null")
                }
            }
        } catch (firstError: Exception) {
            // Retry once after 500ms before reporting failure
            DiagnosticLogger.log("MSG_RETRY", "firstError='${firstError.message}', retrying in 500ms")
            Log.w(TAG, "writeToStdin failed, retrying in 500ms: ${firstError.message}")
            try {
                delay(500)
                withContext(Dispatchers.IO) {
                    val writer = stdinWriter
                    if (writer != null) {
                        synchronized(writer) {
                            writer.write(jsonString)
                            writer.write("\n")
                            writer.flush()
                        }
                    } else {
                        throw IllegalStateException("Writer is null on retry")
                    }
                }
                DiagnosticLogger.log("MSG_RETRY", "retry succeeded")
                Log.i(TAG, "writeToStdin retry succeeded")
            } catch (retryError: Exception) {
                DiagnosticLogger.logError("MSG_RETRY", "retry also failed: '${retryError.message}'")
                Log.e(TAG, "Error writing to stdin (after retry): ${retryError.message}", retryError)
                _messages.tryEmit(ErrorMessage("Failed to send message", retryError.message))
                _isStreaming.value = false
            }
        }
    }

    /**
     * Periodic health check to detect if the process has died unexpectedly.
     * If process is dead but _isRunning is true, update state and trigger cleanup.
     */
    private suspend fun processHealthCheckLoop() {
        try {
            while (scope.isActive && _isRunning.value) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                val proc = process
                val alive = proc?.isAlive ?: false
                DiagnosticLogger.log("HEALTH_CHECK", "processAlive=$alive, isRunning=${_isRunning.value}, isStreaming=${_isStreaming.value}")
                DiagnosticLogger.logResourceSnapshot()
                if (proc != null && !alive && _isRunning.value) {
                    Log.w(TAG, "Health check detected dead process (isRunning was true)")
                    _isRunning.value = false
                    _isStreaming.value = false

                    // Trigger cleanup and potential restart
                    handleProcessExit()
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check loop error: ${e.message}")
        }
    }

    // ---- Heartbeat ----

    /**
     * Restart the heartbeat coroutine based on current settings from the repository.
     * Cancels any existing heartbeat loop and starts a new one if enabled with a non-blank prompt.
     * Safe to call from any context.
     */
    private fun restartHeartbeatIfNeeded() {
        heartbeatJob?.cancel()
        heartbeatJob = null

        if (!_isRunning.value) return

        heartbeatJob = scope.launch {
            val enabled = settingsRepository.heartbeatEnabled.first()
            val prompt = settingsRepository.heartbeatPrompt.first()
            val intervalMinutes = settingsRepository.heartbeatIntervalMinutes.first()

            if (!enabled || prompt.isBlank()) {
                Log.d(TAG, "Heartbeat disabled or prompt blank, not starting")
                return@launch
            }

            val intervalMs = intervalMinutes * 60_000L
            Log.i(TAG, "Starting heartbeat loop: interval=${intervalMinutes}min")
            heartbeatLoop(intervalMs, prompt)
        }
    }

    /**
     * Sends the heartbeat prompt to Claude at a fixed interval.
     * - Waits one full interval before the first send (no immediate fire on enable).
     * - Skips if Claude is currently streaming a response.
     * - Uses sendMessage() so the prompt behaves exactly like a user-typed message.
     */
    private suspend fun heartbeatLoop(intervalMs: Long, prompt: String) {
        try {
            delay(intervalMs)

            while (scope.isActive && _isRunning.value) {
                if (_isStreaming.value) {
                    Log.d(TAG, "Heartbeat skipped: Claude is streaming")
                    delay(60_000L)
                    continue
                }

                Log.i(TAG, "Firing heartbeat prompt")
                sendMessage(prompt)

                delay(intervalMs)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Heartbeat loop error: ${e.message}")
        }
    }

    // ---- JSON parsing ----

    private fun parseMessage(obj: JsonObject): Message? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null

        return when (type) {
            "assistant" -> parseAssistantMessage(obj)
            "user" -> parseUserMessage(obj)
            "system" -> parseSystemMessage(obj)
            "result" -> parseResultMessage(obj)
            "stream_event" -> parseStreamEvent(obj)
            "error" -> ErrorMessage(
                error = obj["error"]?.jsonPrimitive?.content ?: "Unknown error",
                details = obj["details"]?.jsonPrimitive?.content,
                raw = obj
            )
            else -> {
                Log.d(TAG, "Unknown message type: $type")
                null
            }
        }
    }

    private fun parseAssistantMessage(obj: JsonObject): AssistantMessage {
        val messageObj = obj["message"]?.jsonObject ?: obj

        val contentArray = messageObj["content"]?.jsonArray
        val blocks = contentArray?.mapNotNull { parseContentBlock(it) } ?: emptyList()

        val errorObj = messageObj["error"]?.jsonObject
        val error = if (errorObj != null) {
            AssistantError(
                type = errorObj["type"]?.jsonPrimitive?.content ?: "unknown",
                message = errorObj["message"]?.jsonPrimitive?.content
            )
        } else null

        return AssistantMessage(
            content = blocks,
            model = messageObj["model"]?.jsonPrimitive?.content,
            parentToolUseId = messageObj["parent_tool_use_id"]?.jsonPrimitive?.content,
            error = error,
            raw = obj
        )
    }

    private fun parseContentBlock(element: JsonElement): ContentBlock? {
        if (element !is JsonObject) return null

        val type = element["type"]?.jsonPrimitive?.content ?: return null

        return when (type) {
            "text" -> TextBlock(
                text = element["text"]?.jsonPrimitive?.content ?: ""
            )
            "thinking" -> ThinkingBlock(
                thinking = element["thinking"]?.jsonPrimitive?.content ?: "",
                signature = element["signature"]?.jsonPrimitive?.content
            )
            "tool_use" -> {
                val toolName = element["name"]?.jsonPrimitive?.content ?: ""
                val toolInput = element["input"]?.jsonObject
                DiagnosticLogger.log("TOOL_USE_RECEIVED", "tool=$toolName, inputPreview='${toolInput.toString().take(120)}'")
                ToolUseBlock(
                    id = element["id"]?.jsonPrimitive?.content ?: "",
                    name = toolName,
                    input = toolInput
                )
            }
            "tool_result" -> {
                val toolUseId = element["tool_use_id"]?.jsonPrimitive?.content ?: ""
                val resultContent = element["content"]?.jsonPrimitive?.content
                val isError = element["is_error"]?.jsonPrimitive?.booleanOrNull
                DiagnosticLogger.log("TOOL_RESULT_RECEIVED", "toolUseId=$toolUseId, isError=$isError, resultPreview='${resultContent?.take(120) ?: ""}'")
                ToolResultBlock(
                    toolUseId = toolUseId,
                    content = resultContent,
                    isError = isError
                )
            }
            else -> {
                Log.d(TAG, "Unknown content block type: $type")
                null
            }
        }
    }

    private fun parseUserMessage(obj: JsonObject): UserMessageEcho {
        val messageObj = obj["message"]?.jsonObject ?: obj
        return UserMessageEcho(
            content = messageObj["content"]?.jsonPrimitive?.content,
            uuid = messageObj["uuid"]?.jsonPrimitive?.content,
            parentToolUseId = messageObj["parent_tool_use_id"]?.jsonPrimitive?.content,
            raw = obj
        )
    }

    private fun parseSystemMessage(obj: JsonObject): SystemMessage {
        return SystemMessage(
            subtype = obj["subtype"]?.jsonPrimitive?.content ?: "unknown",
            data = obj["data"]?.jsonObject,
            raw = obj
        )
    }

    private fun parseResultMessage(obj: JsonObject): ResultMessage {
        return ResultMessage(
            subtype = obj["subtype"]?.jsonPrimitive?.content ?: "unknown",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationApiMs = obj["duration_api_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false,
            numTurns = obj["num_turns"]?.jsonPrimitive?.intOrNull ?: 0,
            sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
            totalCostUsd = obj["total_cost_usd"]?.jsonPrimitive?.floatOrNull,
            usage = obj["usage"]?.jsonObject,
            result = obj["result"]?.jsonPrimitive?.content,
            raw = obj
        )
    }

    private fun parseStreamEvent(obj: JsonObject): StreamEvent {
        return StreamEvent(
            uuid = obj["uuid"]?.jsonPrimitive?.content,
            sessionId = obj["session_id"]?.jsonPrimitive?.content,
            event = obj["event"]?.jsonObject,
            parentToolUseId = obj["parent_tool_use_id"]?.jsonPrimitive?.content,
            raw = obj
        )
    }
}
