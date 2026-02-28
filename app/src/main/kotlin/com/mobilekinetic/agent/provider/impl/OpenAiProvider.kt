package com.mobilekinetic.agent.provider.impl

import com.mobilekinetic.agent.provider.AiMessage
import com.mobilekinetic.agent.provider.AiProvider
import com.mobilekinetic.agent.provider.AiStreamEvent
import com.mobilekinetic.agent.provider.AiTool
import com.mobilekinetic.agent.provider.ContentBlock
import com.mobilekinetic.agent.provider.FieldType
import com.mobilekinetic.agent.provider.MessageRole
import com.mobilekinetic.agent.provider.ProviderConfigField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AiProvider implementation for OpenAI's Chat Completions API.
 *
 * Supports SSE streaming, function/tool calling, and vision (image) inputs.
 * Uses java.net.HttpURLConnection to avoid adding extra dependencies.
 */
class OpenAiProvider(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1"
) : AiProvider {

    override val name: String = "OpenAI"
    override val id: String = "openai"

    private val ready = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val cancelled = AtomicBoolean(false)

    companion object {
        private val SUPPORTED_MODELS = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-3.5-turbo"
        )
    }

    // -----------------------------------------------------------------------
    // AiProvider lifecycle
    // -----------------------------------------------------------------------

    override suspend fun initialize(): String? {
        if (apiKey.isBlank()) {
            return "API key is required"
        }
        ready.set(true)
        return null
    }

    override fun isReady(): Boolean = ready.get()

    override fun dispose() {
        ready.set(false)
        activeConnection.getAndSet(null)?.disconnect()
    }

    override suspend fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    // -----------------------------------------------------------------------
    // Configuration schema
    // -----------------------------------------------------------------------

    override fun getConfigSchema(): List<ProviderConfigField> = listOf(
        ProviderConfigField(
            key = "api_key",
            label = "API Key",
            type = FieldType.PASSWORD,
            required = true,
            placeholder = "sk-..."
        ),
        ProviderConfigField(
            key = "model",
            label = "Model",
            type = FieldType.SELECT,
            required = true,
            defaultValue = "gpt-4o",
            options = SUPPORTED_MODELS
        ),
        ProviderConfigField(
            key = "base_url",
            label = "Base URL",
            type = FieldType.URL,
            required = false,
            defaultValue = "https://api.openai.com/v1",
            placeholder = "https://api.openai.com/v1"
        )
    )

    // -----------------------------------------------------------------------
    // Streaming message exchange
    // -----------------------------------------------------------------------

    override fun sendMessage(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool>
    ): Flow<AiStreamEvent> = callbackFlow {
        cancelled.set(false)

        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val requestBody = buildRequestBody(messages, systemPrompt, tools)

                val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "text/event-stream")
                    // Prevent buffering the entire response
                    setRequestProperty("Cache-Control", "no-cache")
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                activeConnection.set(connection)

                // Write request body
                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val responseCode = connection.responseCode

                if (responseCode != 200) {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
                    } catch (_: Exception) {
                        "Could not read error body"
                    }
                    val errorMessage = parseApiError(responseCode, errorBody)
                    val retryable = responseCode in listOf(429, 500, 502, 503, 504)
                    trySend(AiStreamEvent.StreamError(errorMessage, isRetryable = retryable))
                    return@withContext
                }

                // Read SSE stream
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))

                // Track accumulated tool call arguments per call ID
                val toolCallArgs = mutableMapOf<String, StringBuilder>()
                // Track which tool call indices we have started
                val startedToolCalls = mutableSetOf<Int>()
                // Map tool call index to its call ID (id only appears on first chunk)
                val indexToCallId = mutableMapOf<Int, String>()

                var inputTokens = 0
                var outputTokens = 0

                reader.use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        if (cancelled.get() || !isActive) break

                        val data = line ?: continue

                        // SSE lines that don't start with "data: " are ignored
                        // (comments, empty lines, event: lines, etc.)
                        if (!data.startsWith("data: ")) continue

                        val payload = data.removePrefix("data: ").trim()

                        // [DONE] terminates the stream
                        if (payload == "[DONE]") {
                            trySend(
                                AiStreamEvent.StreamComplete(
                                    inputTokens = inputTokens,
                                    outputTokens = outputTokens
                                )
                            )
                            break
                        }

                        // Parse JSON chunk
                        try {
                            val chunk = JSONObject(payload)
                            val choices = chunk.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue

                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta") ?: continue

                            // -- Text content --
                            val textContent = delta.optString("content", "")
                            if (textContent.isNotEmpty()) {
                                trySend(AiStreamEvent.TextDelta(textContent))
                            }

                            // -- Tool calls --
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null) {
                                for (i in 0 until toolCalls.length()) {
                                    val tc = toolCalls.getJSONObject(i)
                                    val index = tc.optInt("index", 0)
                                    val function = tc.optJSONObject("function")

                                    // First chunk for this tool call index -> emit ToolCallStart
                                    if (index !in startedToolCalls) {
                                        startedToolCalls.add(index)
                                        val callId = tc.optString("id", "call_$index")
                                        val funcName = function?.optString("name", "") ?: ""
                                        indexToCallId[index] = callId
                                        trySend(AiStreamEvent.ToolCallStart(callId, funcName))
                                        toolCallArgs[callId] = StringBuilder()
                                    }

                                    // Argument deltas
                                    val argDelta = function?.optString("arguments", "") ?: ""
                                    if (argDelta.isNotEmpty()) {
                                        // On subsequent delta chunks, id may be absent;
                                        // fall back to the index->id map we built above
                                        val callId = tc.optString("id", "")
                                            .ifEmpty { indexToCallId[index] ?: "call_$index" }
                                        toolCallArgs[callId]?.append(argDelta)
                                        trySend(AiStreamEvent.ToolCallDelta(callId, argDelta))
                                    }
                                }
                            }

                            // -- Finish reason --
                            val finishReason = choice.optString("finish_reason", "")
                            if (finishReason == "tool_calls" || finishReason == "stop") {
                                // Emit ToolCallEnd for any open tool calls
                                for (callId in toolCallArgs.keys) {
                                    trySend(AiStreamEvent.ToolCallEnd(callId))
                                }
                                toolCallArgs.clear()
                                startedToolCalls.clear()
                                indexToCallId.clear()
                            }

                            // -- Usage (if present in chunk) --
                            val usage = chunk.optJSONObject("usage")
                            if (usage != null) {
                                inputTokens = usage.optInt("prompt_tokens", inputTokens)
                                outputTokens = usage.optInt("completion_tokens", outputTokens)
                            }

                        } catch (e: Exception) {
                            // Malformed JSON chunk -- skip, don't crash the stream
                            continue
                        }
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                trySend(
                    AiStreamEvent.StreamError(
                        "Connection timed out: ${e.message}",
                        isRetryable = true
                    )
                )
            } catch (e: java.net.UnknownHostException) {
                trySend(
                    AiStreamEvent.StreamError(
                        "Cannot reach ${baseUrl}: ${e.message}",
                        isRetryable = true
                    )
                )
            } catch (e: java.net.ConnectException) {
                trySend(
                    AiStreamEvent.StreamError(
                        "Connection refused: ${e.message}",
                        isRetryable = true
                    )
                )
            } catch (e: java.io.IOException) {
                if (cancelled.get()) {
                    // Cancelled by user -- emit complete, not error
                    trySend(AiStreamEvent.StreamComplete())
                } else {
                    trySend(
                        AiStreamEvent.StreamError(
                            "Network error: ${e.message}",
                            isRetryable = true
                        )
                    )
                }
            } catch (e: Exception) {
                trySend(
                    AiStreamEvent.StreamError(
                        "Unexpected error: ${e.message}",
                        isRetryable = false
                    )
                )
            } finally {
                activeConnection.set(null)
                connection?.disconnect()
            }
        }

        awaitClose {
            cancelled.set(true)
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    // -----------------------------------------------------------------------
    // Request body construction
    // -----------------------------------------------------------------------

    private fun buildRequestBody(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool>
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("stream", true)
        // Request usage stats in streaming (OpenAI supports this with stream_options)
        body.put("stream_options", JSONObject().put("include_usage", true))

        // Build messages array
        val messagesArray = JSONArray()

        // System prompt as the first message
        if (systemPrompt.isNotBlank()) {
            messagesArray.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
        }

        // Conversation messages
        for (msg in messages) {
            messagesArray.put(convertMessage(msg))
        }
        body.put("messages", messagesArray)

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(convertTool(tool))
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    /**
     * Convert an AiMessage to the OpenAI message JSON format.
     */
    private fun convertMessage(message: AiMessage): JSONObject {
        val obj = JSONObject()

        obj.put("role", when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "tool"
        })

        // Tool result messages need tool_call_id
        if (message.role == MessageRole.TOOL && message.toolCallId != null) {
            obj.put("tool_call_id", message.toolCallId)
            // Tool results in OpenAI are simple string content
            val text = message.content.filterIsInstance<ContentBlock.Text>()
                .joinToString("") { it.text }
            obj.put("content", text)
            return obj
        }

        // Check if the message contains tool calls (assistant messages with ToolCall blocks)
        val toolCallBlocks = message.content.filterIsInstance<ContentBlock.ToolCall>()
        val textBlocks = message.content.filterIsInstance<ContentBlock.Text>()
        val imageBlocks = message.content.filterIsInstance<ContentBlock.Image>()

        if (message.role == MessageRole.ASSISTANT && toolCallBlocks.isNotEmpty()) {
            // Assistant message with tool calls
            val textContent = textBlocks.joinToString("") { it.text }
            if (textContent.isNotEmpty()) {
                obj.put("content", textContent)
            } else {
                obj.put("content", JSONObject.NULL)
            }
            val tcArray = JSONArray()
            for (tc in toolCallBlocks) {
                tcArray.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tc.name)
                                .put("arguments", tc.arguments)
                        )
                )
            }
            obj.put("tool_calls", tcArray)
            return obj
        }

        // Messages with images use the multimodal content array format
        if (imageBlocks.isNotEmpty()) {
            val contentArray = JSONArray()
            for (block in message.content) {
                when (block) {
                    is ContentBlock.Text -> {
                        if (block.text.isNotEmpty()) {
                            contentArray.put(
                                JSONObject()
                                    .put("type", "text")
                                    .put("text", block.text)
                            )
                        }
                    }
                    is ContentBlock.Image -> {
                        val dataUrl = "data:${block.mimeType};base64,${block.base64}"
                        contentArray.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", dataUrl)
                                )
                        )
                    }
                    is ContentBlock.ToolCall -> {
                        // Already handled above
                    }
                }
            }
            obj.put("content", contentArray)
            return obj
        }

        // Simple text-only message
        val text = textBlocks.joinToString("") { it.text }
        obj.put("content", text)
        return obj
    }

    /**
     * Convert an AiTool to OpenAI function-calling tool format.
     */
    private fun convertTool(tool: AiTool): JSONObject {
        val parameters = try {
            JSONObject(tool.inputSchema)
        } catch (_: Exception) {
            // Fallback: empty object schema
            JSONObject().put("type", "object").put("properties", JSONObject())
        }

        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", parameters)
            )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Parse an API error response into a human-readable message.
     */
    private fun parseApiError(responseCode: Int, errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val error = json.optJSONObject("error")
            val message = error?.optString("message", "") ?: ""
            val type = error?.optString("type", "") ?: ""
            when (responseCode) {
                401 -> "Invalid API key. Please check your OpenAI API key."
                403 -> "Access denied: $message"
                404 -> "Model '$model' not found or endpoint unavailable."
                429 -> "Rate limited: ${message.ifEmpty { "Too many requests. Please try again later." }}"
                500 -> "OpenAI server error: ${message.ifEmpty { "Internal server error" }}"
                502, 503, 504 -> "OpenAI service temporarily unavailable ($responseCode)."
                else -> "API error $responseCode${if (type.isNotEmpty()) " ($type)" else ""}: ${message.ifEmpty { errorBody.take(200) }}"
            }
        } catch (_: Exception) {
            "API error $responseCode: ${errorBody.take(200)}"
        }
    }
}
