package com.mobilekinetic.agent.data

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic logging utility for tracing the complete flow:
 *   User action -> UI -> ViewModel -> TTS service -> WebSocket -> Audio playback
 *
 * All output uses the single tag "BA-DIAG" so the entire pipeline can be
 * filtered with:  adb logcat -s BA-DIAG
 *
 * Each log line includes:
 *   [HH:mm:ss.SSS] [+Nms] STAGE: details
 *
 * where +Nms is the elapsed time since the previous log entry, making it
 * trivial to spot where lag is introduced.
 *
 * Usage:
 *   DiagnosticLogger.log("TTS_QUEUE", "text='Hello world', chars=11, voice=bf_eleanor")
 *   DiagnosticLogger.startTrace("api_call")
 *   // ... work ...
 *   DiagnosticLogger.endTrace("api_call")  // logs duration automatically
 */
object DiagnosticLogger {

    private const val TAG = "BA-DIAG"

    /** Master switch -- set to false to silence all diagnostic output. */
    @Volatile
    var enabled: Boolean = true

    // Wall-clock formatter (HH:mm:ss.SSS)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Last log timestamp for computing deltas (nanoTime for monotonic precision)
    @Volatile
    private var lastNano: Long = System.nanoTime()

    // Named traces: id -> start nanoTime
    private val traces = ConcurrentHashMap<String, Long>()

    // ---- Primary API -------------------------------------------------------

    /**
     * Log a diagnostic stage event.
     *
     * @param stage  Short uppercase label (e.g. "WS_CONNECTED", "EXOPLAYER_START")
     * @param details  Optional human-readable context string
     */
    fun log(stage: String, details: String = "") {
        if (!enabled) return

        val now = System.nanoTime()
        val deltaMs = (now - lastNano) / 1_000_000
        lastNano = now

        val wall = timeFormat.format(Date())
        val deltaStr = "+${deltaMs}ms"
        val msg = if (details.isNotEmpty()) {
            "[$wall] [$deltaStr] $stage: $details"
        } else {
            "[$wall] [$deltaStr] $stage"
        }

        Log.d(TAG, msg)
    }

    /**
     * Log an error-level diagnostic event.
     */
    fun logError(stage: String, details: String = "", throwable: Throwable? = null) {
        if (!enabled) return

        val now = System.nanoTime()
        val deltaMs = (now - lastNano) / 1_000_000
        lastNano = now

        val wall = timeFormat.format(Date())
        val deltaStr = "+${deltaMs}ms"
        val msg = if (details.isNotEmpty()) {
            "[$wall] [$deltaStr] ERROR/$stage: $details"
        } else {
            "[$wall] [$deltaStr] ERROR/$stage"
        }

        if (throwable != null) {
            Log.e(TAG, msg, throwable)
        } else {
            Log.e(TAG, msg)
        }
    }

    // ---- Trace API (start/end pairs) ---------------------------------------

    /**
     * Begin a named trace. Call [endTrace] with the same [id] to log the
     * elapsed duration.
     */
    fun startTrace(id: String) {
        if (!enabled) return
        traces[id] = System.nanoTime()
        log("TRACE_START", id)
    }

    /**
     * End a named trace and log the elapsed time since [startTrace] was called.
     * Returns the duration in milliseconds, or -1 if the trace was not found.
     */
    fun endTrace(id: String): Long {
        if (!enabled) return -1L
        val startNano = traces.remove(id)
        if (startNano == null) {
            log("TRACE_END", "$id (no matching start)")
            return -1L
        }
        val durationMs = (System.nanoTime() - startNano) / 1_000_000
        log("TRACE_END", "$id took ${durationMs}ms")
        return durationMs
    }

    // ---- Convenience helpers for common stages -----------------------------

    /** User tapped send in the chat UI. */
    fun userMessageSent(textLength: Int) {
        log("USER_MSG_SENT", "chars=$textLength")
    }

    /** Message forwarded to Claude process manager. */
    fun claudeApiRequestSent(sessionId: String) {
        startTrace("claude_api")
        log("CLAUDE_API_SENT", "session=$sessionId")
    }

    /** First streaming token arrived from Claude. */
    fun claudeFirstToken() {
        log("CLAUDE_FIRST_TOKEN", "ttft=${traceElapsed("claude_api")}ms")
    }

    /** Claude response stream complete (ResultMessage received). */
    fun claudeResponseComplete(durationMs: Long = 0) {
        val apiDuration = endTrace("claude_api")
        log("CLAUDE_RESPONSE_DONE", "apiTrace=${apiDuration}ms, reported=${durationMs}ms")
    }

    /** Text chunk sent to TTS queue. */
    fun ttsQueued(text: String, queueDepth: Int, voice: String) {
        log("TTS_QUEUED", "chars=${text.length}, queue=$queueDepth, voice=$voice, text='${text.take(80)}'")
    }

    /** TTS queue begins processing next item. */
    fun ttsPlayNext(remaining: Int, textLength: Int) {
        startTrace("tts_speak")
        log("TTS_PLAY_NEXT", "remaining=$remaining, chars=$textLength")
    }

    /** WebSocket connection initiated. */
    fun wsConnecting(url: String) {
        startTrace("ws_connect")
        log("WS_CONNECTING", url)
    }

    /** WebSocket connection established. */
    fun wsConnected() {
        endTrace("ws_connect")
        log("WS_CONNECTED")
    }

    /** TTS request JSON sent over WebSocket. */
    fun wsTtsRequestSent(textLength: Int, voice: String, speed: Float) {
        startTrace("ws_first_chunk")
        log("WS_TTS_REQ_SENT", "chars=$textLength, voice=$voice, speed=$speed")
    }

    /** First audio chunk received from WebSocket. */
    fun wsFirstChunk(sizeBytes: Int) {
        val latency = endTrace("ws_first_chunk")
        log("WS_FIRST_CHUNK", "size=${sizeBytes}B, latency=${latency}ms")
    }

    /** Subsequent audio chunk received. */
    fun wsChunk(chunkNumber: Int, sizeBytes: Int, totalBytes: Long) {
        log("WS_CHUNK", "chunk=$chunkNumber, size=${sizeBytes}B, total=${totalBytes}B")
    }

    /** WebSocket stream complete. */
    fun wsStreamComplete(totalChunks: Int) {
        log("WS_STREAM_DONE", "totalChunks=$totalChunks")
    }

    /** WebSocket error. */
    fun wsError(message: String, throwable: Throwable? = null) {
        logError("WS_ERROR", message, throwable)
    }

    /** ExoPlayer started audio playback. */
    fun exoPlayerStarted(audioSessionId: Int) {
        log("EXOPLAYER_STARTED", "audioSession=$audioSessionId")
    }

    /** ExoPlayer finished playing all chunks. */
    fun exoPlayerFinished(totalChunks: Int) {
        val speakDuration = endTrace("tts_speak")
        log("EXOPLAYER_FINISHED", "chunks=$totalChunks, speakDuration=${speakDuration}ms")
    }

    /** ExoPlayer error. */
    fun exoPlayerError(message: String, throwable: Throwable? = null) {
        logError("EXOPLAYER_ERROR", message, throwable)
    }

    /** Visualizer attached to audio session. */
    fun visualizerAttached(audioSessionId: Int) {
        log("VISUALIZER_ATTACHED", "audioSession=$audioSessionId")
    }

    /** Visualizer detached / released. */
    fun visualizerReleased() {
        log("VISUALIZER_RELEASED")
    }

    /** TTS buffer state during streaming. */
    fun ttsBufferState(phase: Int, bufferChars: Int, sentencesSoFar: Boolean) {
        log("TTS_BUFFER", "phase=$phase, bufferChars=$bufferChars, firstSent=$sentencesSoFar")
    }

    /** TTS chunk sent from ChatViewModel buffer to TtsManager. */
    fun ttsChunkSent(phase: Int, chars: Int, reason: String) {
        log("TTS_CHUNK_SENT", "phase=$phase, chars=$chars, reason=$reason")
    }

    // ---- Resource monitoring ------------------------------------------------

    /** Thresholds for slow operation warnings (milliseconds). */
    private const val THRESHOLD_API_MS = 10_000L
    private const val THRESHOLD_TOOL_MS = 5_000L
    private const val DEFAULT_THRESHOLD_MS = 5_000L

    /**
     * Log a snapshot of current JVM resource usage.
     *
     * Format: `RESOURCE_SNAPSHOT: heap=45MB/128MB (35%), threads=24`
     */
    fun logResourceSnapshot() {
        if (!enabled) return

        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val usedMb = totalMb - freeMb
        val usedPercent = if (maxMb > 0) (usedMb * 100) / maxMb else 0
        val threads = Thread.activeCount()

        log("RESOURCE_SNAPSHOT", "heap=${usedMb}MB/${maxMb}MB ($usedPercent%), threads=$threads")
    }

    /**
     * Log an operation's response time, with automatic WARN-level logging
     * when the duration exceeds the threshold for that operation type.
     *
     * Known operation prefixes and their thresholds:
     *   - `claude_api` / `api_*`  -> 10 000 ms
     *   - `tool_*` / `shell_*`    ->  5 000 ms
     *   - everything else          ->  5 000 ms
     *
     * @param operation  Short label (e.g. "claude_api", "tool_bash", "shell_exec")
     * @param durationMs Elapsed time in milliseconds
     */
    fun logResponseTime(operation: String, durationMs: Long) {
        if (!enabled) return

        val threshold = when {
            operation.startsWith("claude_api") || operation.startsWith("api_") -> THRESHOLD_API_MS
            operation.startsWith("tool_") || operation.startsWith("shell_") -> THRESHOLD_TOOL_MS
            else -> DEFAULT_THRESHOLD_MS
        }

        if (durationMs > threshold) {
            log("SLOW_OPERATION", "operation=$operation, duration=${durationMs}ms (threshold=${threshold}ms)")
            Log.w(TAG, "SLOW_OPERATION: operation=$operation, duration=${durationMs}ms (threshold=${threshold}ms)")
        } else {
            log("RESPONSE_TIME", "operation=$operation, duration=${durationMs}ms")
        }
    }

    // ---- Internal helpers --------------------------------------------------

    /**
     * Get elapsed time for a running trace without ending it.
     * Returns -1 if trace not found.
     */
    private fun traceElapsed(id: String): Long {
        val startNano = traces[id] ?: return -1L
        return (System.nanoTime() - startNano) / 1_000_000
    }
}
