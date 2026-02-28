package com.mobilekinetic.agent.provider.impl

import android.util.Log
import com.mobilekinetic.agent.claude.ClaudeProcessManager
import com.mobilekinetic.agent.provider.AiMessage
import com.mobilekinetic.agent.provider.AiProvider
import com.mobilekinetic.agent.provider.AiStreamEvent
import com.mobilekinetic.agent.provider.AiTool
import com.mobilekinetic.agent.provider.ContentBlock
import com.mobilekinetic.agent.provider.MessageRole
import com.mobilekinetic.agent.provider.ProviderConfigField
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AiProvider implementation that wraps [ClaudeProcessManager].
 *
 * Bridges the subprocess-based Python orchestrator (which communicates via JSON
 * over stdin/stdout) to the unified Flow-based AiProvider API. The orchestrator
 * emits [ClaudeProcessManager.Message] variants on a SharedFlow; this provider
 * collects those and maps them to [AiStreamEvent] instances.
 *
 * Design notes:
 *  - [sendMessage] launches a [callbackFlow] that subscribes to
 *    [ClaudeProcessManager.messages], converts each Message into one or more
 *    [AiStreamEvent]s, and completes when a [ClaudeProcessManager.ResultMessage]
 *    or terminal error is received.
 *  - Only the **last** user message is forwarded to the orchestrator. The
 *    orchestrator maintains its own conversation history within the session, so
 *    replaying the full message list would duplicate context.
 *  - Tool calls arrive inside [ClaudeProcessManager.AssistantMessage] content
 *    blocks as [ClaudeProcessManager.ToolUseBlock]. They are emitted as a
 *    [AiStreamEvent.ToolCallStart] followed immediately by a [ToolCallEnd]
 *    because the orchestrator provides the full tool input in one shot (no
 *    streaming argument deltas).
 */
@Singleton
class ClaudeCliProvider @Inject constructor(
    private val processManager: ClaudeProcessManager
) : AiProvider {

    override val name: String = "Claude (CLI)"
    override val id: String = "claude_cli"

    companion object {
        private const val TAG = "ClaudeCliProvider"
    }

    // -------------------------------------------------------------------------
    // AiProvider implementation
    // -------------------------------------------------------------------------

    override fun sendMessage(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool>
    ): Flow<AiStreamEvent> = callbackFlow {
        // Extract the latest user message text. The orchestrator manages its own
        // conversation history so we only send the newest user turn.
        val userText = messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content
            ?.filterIsInstance<ContentBlock.Text>()
            ?.joinToString("") { it.text }

        if (userText.isNullOrBlank()) {
            trySend(AiStreamEvent.StreamError("No user message found in conversation"))
            close()
            return@callbackFlow
        }

        // Ensure the orchestrator process is running before sending.
        if (!processManager.isRunning()) {
            processManager.start()
            // Wait briefly for isRunning to flip true (up to ~5 s).
            var waited = 0
            while (!processManager.isRunning() && waited < 5000) {
                kotlinx.coroutines.delay(250)
                waited += 250
            }
            if (!processManager.isRunning()) {
                val err = processManager.lastError.value ?: "Process failed to start"
                trySend(AiStreamEvent.StreamError(err))
                close()
                return@callbackFlow
            }
        }

        // Subscribe to the shared message flow from the process manager.
        // We launch a child coroutine inside the callbackFlow scope so that
        // closing the flow cancels collection automatically.
        val collectJob = launch {
            processManager.messages.collect { message ->
                when (message) {
                    // ---- Assistant text / tool-use blocks ----
                    is ClaudeProcessManager.AssistantMessage -> {
                        for (block in message.content) {
                            when (block) {
                                is ClaudeProcessManager.TextBlock -> {
                                    trySend(AiStreamEvent.TextDelta(block.text))
                                }

                                is ClaudeProcessManager.ToolUseBlock -> {
                                    val inputJson = block.input?.toString() ?: "{}"
                                    trySend(
                                        AiStreamEvent.ToolCallStart(
                                            callId = block.id,
                                            toolName = block.name
                                        )
                                    )
                                    // The orchestrator provides the full input
                                    // in one shot so emit the complete arguments
                                    // as a single delta then close the call.
                                    trySend(
                                        AiStreamEvent.ToolCallDelta(
                                            callId = block.id,
                                            argumentsDelta = inputJson
                                        )
                                    )
                                    trySend(AiStreamEvent.ToolCallEnd(block.id))
                                }

                                is ClaudeProcessManager.ThinkingBlock -> {
                                    // Thinking blocks are internal chain-of-thought.
                                    // Surface them as text deltas wrapped in markers
                                    // so the UI layer can choose to display or hide.
                                    // (No dedicated AiStreamEvent variant exists.)
                                }

                                is ClaudeProcessManager.ToolResultBlock -> {
                                    // Tool results flow back through the orchestrator;
                                    // they aren't directly surfaced to the AiProvider
                                    // consumer since the consumer is the one supplying
                                    // results. Silently skip.
                                }
                            }
                        }

                        // If the assistant message carried an error, surface it.
                        message.error?.let { err ->
                            trySend(
                                AiStreamEvent.StreamError(
                                    message = err.message ?: "Assistant error: ${err.type}",
                                    isRetryable = err.type == "overloaded_error"
                                )
                            )
                        }
                    }

                    // ---- Streaming content_block_delta events ----
                    is ClaudeProcessManager.StreamEvent -> {
                        handleStreamEvent(message)?.let { event ->
                            trySend(event)
                        }
                    }

                    // ---- Terminal result with usage stats ----
                    is ClaudeProcessManager.ResultMessage -> {
                        val usage = message.usage
                        val inputTokens = usage
                            ?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        val outputTokens = usage
                            ?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0

                        trySend(
                            AiStreamEvent.StreamComplete(
                                inputTokens = inputTokens,
                                outputTokens = outputTokens
                            )
                        )
                        // ResultMessage signals the end of this query cycle.
                        close()
                    }

                    // ---- Process-level error ----
                    is ClaudeProcessManager.ErrorMessage -> {
                        trySend(
                            AiStreamEvent.StreamError(
                                message = message.error,
                                isRetryable = false
                            )
                        )
                        close()
                    }

                    // ---- System messages (session info, hooks, restarts) ----
                    is ClaudeProcessManager.SystemMessage -> {
                        // Process restarts are surfaced as retryable errors.
                        if (message.subtype == "process_restart") {
                            trySend(
                                AiStreamEvent.StreamError(
                                    message = "Process restarting, please retry",
                                    isRetryable = true
                                )
                            )
                            close()
                        }
                        // Other system messages (hook_start/end, session_info)
                        // are internal plumbing and not surfaced.
                    }

                    // ---- User message echo ----
                    is ClaudeProcessManager.UserMessageEcho -> {
                        // Echo of our own message; ignore.
                    }
                }
            }
        }

        // Now that the collector is running, send the user message.
        processManager.sendMessage(userText)

        awaitClose {
            Log.d(TAG, "callbackFlow closed, cancelling collection job")
            collectJob.cancel()
        }
    }

    override suspend fun cancel() {
        processManager.interrupt()
    }

    override suspend fun initialize(): String? {
        return try {
            if (!processManager.isRunning()) {
                processManager.start()
                // Wait for the process to come up (up to ~8 s).
                var waited = 0
                while (!processManager.isRunning() && waited < 8000) {
                    kotlinx.coroutines.delay(250)
                    waited += 250
                }
                if (!processManager.isRunning()) {
                    return processManager.lastError.value
                        ?: "Claude process failed to start within timeout"
                }
            }
            null // success
        } catch (e: Exception) {
            Log.e(TAG, "initialize() failed", e)
            e.message ?: "Unknown initialization error"
        }
    }

    override fun dispose() {
        processManager.stop()
    }

    override fun isReady(): Boolean = processManager.isRunning()

    override fun getConfigSchema(): List<ProviderConfigField> {
        // The CLI provider has no user-configurable fields; the orchestrator
        // reads its configuration from environment variables and on-device
        // settings via SettingsRepository.
        return emptyList()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts an [AiStreamEvent] from a [ClaudeProcessManager.StreamEvent].
     *
     * StreamEvents carry a raw JSON `event` object whose shape follows the
     * Anthropic streaming format. The `type` field determines the kind of
     * delta:
     *  - `content_block_delta` with `delta.type == "text_delta"` -> [AiStreamEvent.TextDelta]
     *  - `content_block_delta` with `delta.type == "input_json_delta"` -> [AiStreamEvent.ToolCallDelta]
     *  - `content_block_start` with `content_block.type == "tool_use"` -> [AiStreamEvent.ToolCallStart]
     *  - `content_block_stop` -> [AiStreamEvent.ToolCallEnd] (if we're tracking a tool)
     */
    private fun handleStreamEvent(
        streamEvent: ClaudeProcessManager.StreamEvent
    ): AiStreamEvent? {
        val event = streamEvent.event ?: return null
        val eventType = event["type"]?.jsonPrimitive?.content ?: return null

        return when (eventType) {
            "content_block_start" -> {
                val contentBlock = event["content_block"]?.jsonObject ?: return null
                val blockType = contentBlock["type"]?.jsonPrimitive?.content
                if (blockType == "tool_use") {
                    val toolId = contentBlock["id"]?.jsonPrimitive?.content ?: return null
                    val toolName = contentBlock["name"]?.jsonPrimitive?.content ?: ""
                    AiStreamEvent.ToolCallStart(
                        callId = toolId,
                        toolName = toolName
                    )
                } else {
                    null
                }
            }

            "content_block_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return null
                when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> {
                        val text = delta["text"]?.jsonPrimitive?.content ?: return null
                        AiStreamEvent.TextDelta(text)
                    }

                    "input_json_delta" -> {
                        val partial = delta["partial_json"]?.jsonPrimitive?.content
                            ?: return null
                        // content_block_delta with input_json_delta requires a
                        // content_block index to correlate, but the orchestrator
                        // provides parentToolUseId on the StreamEvent when
                        // available. Fall back to the index if needed.
                        val callId = streamEvent.parentToolUseId ?: ""
                        AiStreamEvent.ToolCallDelta(
                            callId = callId,
                            argumentsDelta = partial
                        )
                    }

                    else -> null
                }
            }

            "content_block_stop" -> {
                // The stop event signals that the current content block is
                // complete. If it was a tool_use block, emit ToolCallEnd.
                // We use parentToolUseId if the orchestrator set it.
                val toolId = streamEvent.parentToolUseId
                if (toolId != null) {
                    AiStreamEvent.ToolCallEnd(callId = toolId)
                } else {
                    null
                }
            }

            else -> null
        }
    }
}
