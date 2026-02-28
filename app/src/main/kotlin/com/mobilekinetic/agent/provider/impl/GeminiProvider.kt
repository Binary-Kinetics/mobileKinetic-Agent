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
 * AiProvider implementation for Google's Gemini API.
 *
 * Uses SSE streaming via the `streamGenerateContent` endpoint.
 * Connects with [java.net.HttpURLConnection] to avoid adding external
 * HTTP-client dependencies.
 *
 * Gemini API differences from OpenAI:
 *  - Role name: "model" instead of "assistant"
 *  - System prompt: separate `systemInstruction` field (not a message)
 *  - Tool definitions: `tools[].functionDeclarations[]` (not `tools[].function`)
 *  - SSE payload: `candidates[0].content.parts[0].text`
 *  - Tool calls: `candidates[0].content.parts[].functionCall`
 *  - Stream terminator: `finishReason` in the last chunk (no `[DONE]` sentinel)
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash"
) : AiProvider {

    override val name: String = "Google Gemini"
    override val id: String = "gemini"

    private val ready = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val cancelled = AtomicBoolean(false)

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        val SUPPORTED_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-pro",
            "gemini-1.5-flash"
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
            placeholder = "AIza..."
        ),
        ProviderConfigField(
            key = "model",
            label = "Model",
            type = FieldType.SELECT,
            required = true,
            defaultValue = "gemini-2.0-flash",
            options = SUPPORTED_MODELS
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

                val endpoint = "$BASE_URL/models/$model:streamGenerateContent" +
                    "?key=$apiKey&alt=sse"

                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
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
                val reader = BufferedReader(
                    InputStreamReader(connection.inputStream, Charsets.UTF_8)
                )

                var inputTokens = 0
                var outputTokens = 0
                var receivedContent = false

                reader.use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        if (cancelled.get() || !isActive) break

                        val data = line ?: continue

                        // SSE format: lines starting with "data: " carry JSON payloads.
                        // Blank lines and comment lines (starting with ':') are ignored.
                        if (!data.startsWith("data: ")) continue

                        val payload = data.removePrefix("data: ").trim()
                        if (payload.isEmpty()) continue

                        try {
                            val chunk = JSONObject(payload)

                            // --- Usage metadata ---
                            val usageMetadata = chunk.optJSONObject("usageMetadata")
                            if (usageMetadata != null) {
                                inputTokens = usageMetadata.optInt(
                                    "promptTokenCount", inputTokens
                                )
                                outputTokens = usageMetadata.optInt(
                                    "candidatesTokenCount", outputTokens
                                )
                            }

                            // --- Candidates ---
                            val candidates = chunk.optJSONArray("candidates")
                            if (candidates == null || candidates.length() == 0) continue

                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")

                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)

                                    // -- Text content --
                                    val text = part.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        receivedContent = true
                                        trySend(AiStreamEvent.TextDelta(text))
                                    }

                                    // -- Function call (tool use) --
                                    val functionCall = part.optJSONObject("functionCall")
                                    if (functionCall != null) {
                                        receivedContent = true
                                        val funcName = functionCall.optString("name", "")
                                        val args = functionCall.optJSONObject("args")
                                        val argsStr = args?.toString() ?: "{}"
                                        // Gemini delivers function calls in one shot
                                        // (no streaming argument deltas), so emit
                                        // start + full delta + end in sequence.
                                        val callId = "gemini_call_${System.nanoTime()}"
                                        trySend(
                                            AiStreamEvent.ToolCallStart(
                                                callId = callId,
                                                toolName = funcName
                                            )
                                        )
                                        trySend(
                                            AiStreamEvent.ToolCallDelta(
                                                callId = callId,
                                                argumentsDelta = argsStr
                                            )
                                        )
                                        trySend(AiStreamEvent.ToolCallEnd(callId))
                                    }
                                }
                            }

                            // -- Finish reason --
                            val finishReason = candidate.optString("finishReason", "")
                            if (finishReason.isNotEmpty() && finishReason != "null") {
                                trySend(
                                    AiStreamEvent.StreamComplete(
                                        inputTokens = inputTokens,
                                        outputTokens = outputTokens
                                    )
                                )
                                break
                            }

                        } catch (e: Exception) {
                            // Malformed JSON chunk -- skip, don't crash the stream
                            continue
                        }
                    }
                }

                // If we exhausted the stream without seeing a finishReason,
                // emit StreamComplete so the UI doesn't hang.
                if (receivedContent && !cancelled.get()) {
                    trySend(
                        AiStreamEvent.StreamComplete(
                            inputTokens = inputTokens,
                            outputTokens = outputTokens
                        )
                    )
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
                        "Cannot reach Gemini API: ${e.message}",
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

    /**
     * Build the JSON request body for Gemini's `generateContent` endpoint.
     *
     * Structure:
     * ```json
     * {
     *   "systemInstruction": { "parts": [{"text": "..."}] },
     *   "contents": [
     *     { "role": "user",  "parts": [{"text": "..."}] },
     *     { "role": "model", "parts": [{"text": "..."}] }
     *   ],
     *   "tools": [{"functionDeclarations": [...]}],
     *   "generationConfig": { ... }
     * }
     * ```
     */
    private fun buildRequestBody(
        messages: List<AiMessage>,
        systemPrompt: String,
        tools: List<AiTool>
    ): JSONObject {
        val body = JSONObject()

        // System instruction (separate field, not a message)
        if (systemPrompt.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
        }

        // Conversation contents
        val contentsArray = JSONArray()
        for (msg in messages) {
            // Gemini does not support system messages in the contents array.
            // Skip them since system prompt is handled via systemInstruction.
            if (msg.role == MessageRole.SYSTEM) continue

            val converted = convertMessage(msg)
            if (converted != null) {
                contentsArray.put(converted)
            }
        }
        body.put("contents", contentsArray)

        // Tools (function declarations)
        if (tools.isNotEmpty()) {
            val declarations = JSONArray()
            for (tool in tools) {
                declarations.put(convertTool(tool))
            }
            body.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("functionDeclarations", declarations)
                )
            )
        }

        return body
    }

    /**
     * Convert an [AiMessage] to Gemini content format.
     *
     * Gemini uses "user" and "model" roles. Tool results use the
     * "function" role with `functionResponse` parts.
     */
    private fun convertMessage(message: AiMessage): JSONObject? {
        val role = when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "model"
            MessageRole.SYSTEM -> return null // handled via systemInstruction
            MessageRole.TOOL -> "function"
        }

        val parts = JSONArray()

        for (block in message.content) {
            when (block) {
                is ContentBlock.Text -> {
                    if (message.role == MessageRole.TOOL && message.toolCallId != null) {
                        // Gemini expects functionResponse parts for tool results
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject()
                                    .put("name", message.toolCallId)
                                    .put(
                                        "response",
                                        JSONObject().put("result", block.text)
                                    )
                            )
                        )
                    } else if (block.text.isNotEmpty()) {
                        parts.put(JSONObject().put("text", block.text))
                    }
                }

                is ContentBlock.ToolCall -> {
                    // Assistant message containing a tool call -> functionCall part
                    val argsJson = try {
                        JSONObject(block.arguments)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    parts.put(
                        JSONObject().put(
                            "functionCall",
                            JSONObject()
                                .put("name", block.name)
                                .put("args", argsJson)
                        )
                    )
                }

                is ContentBlock.Image -> {
                    // Gemini supports inline images via inlineData
                    parts.put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", block.mimeType)
                                .put("data", block.base64)
                        )
                    )
                }
            }
        }

        // Don't produce empty content objects
        if (parts.length() == 0) return null

        return JSONObject()
            .put("role", role)
            .put("parts", parts)
    }

    /**
     * Convert an [AiTool] to Gemini's `functionDeclaration` format.
     */
    private fun convertTool(tool: AiTool): JSONObject {
        val declaration = JSONObject()
        declaration.put("name", tool.name)
        declaration.put("description", tool.description)

        // Parse the input schema JSON string into a JSONObject for parameters
        try {
            val schema = JSONObject(tool.inputSchema)
            declaration.put("parameters", schema)
        } catch (_: Exception) {
            // Fallback: empty object schema
            declaration.put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject())
            )
        }

        return declaration
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Parse a Gemini API error response into a human-readable message.
     */
    private fun parseApiError(responseCode: Int, errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val error = json.optJSONObject("error")
            val message = error?.optString("message", "") ?: ""
            val status = error?.optString("status", "") ?: ""

            when (responseCode) {
                400 -> "Bad request: ${message.ifEmpty { "Invalid request format" }}"
                401, 403 -> "Invalid API key. Please check your Gemini API key."
                404 -> "Model '$model' not found. Verify the model name is correct."
                429 -> "Rate limited: ${message.ifEmpty { "Too many requests. Please try again later." }}"
                500 -> "Gemini server error: ${message.ifEmpty { "Internal server error" }}"
                502, 503, 504 -> "Gemini service temporarily unavailable ($responseCode)."
                else -> "API error $responseCode${if (status.isNotEmpty()) " ($status)" else ""}: ${message.ifEmpty { errorBody.take(200) }}"
            }
        } catch (_: Exception) {
            "API error $responseCode: ${errorBody.take(200)}"
        }
    }
}
