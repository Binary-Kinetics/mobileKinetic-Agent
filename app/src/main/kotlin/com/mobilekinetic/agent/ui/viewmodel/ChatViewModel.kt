package com.mobilekinetic.agent.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.claude.ClaudeProcessManager
import com.mobilekinetic.agent.data.model.ChatMessage
import com.mobilekinetic.agent.data.model.Conversation
import com.mobilekinetic.agent.data.model.MessageRole
import com.mobilekinetic.agent.data.chat.ConversationRepository
import com.mobilekinetic.agent.data.rag.ToolMemory
import com.mobilekinetic.agent.data.chat.ResponseParser
import com.mobilekinetic.agent.data.DiagnosticLogger
import com.mobilekinetic.agent.data.tts.TtsManager
import com.mobilekinetic.agent.provider.AiMessage
import com.mobilekinetic.agent.provider.AiProvider
import com.mobilekinetic.agent.provider.AiStreamEvent
import com.mobilekinetic.agent.provider.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val claudeProcessManager: ClaudeProcessManager,
    private val conversationRepository: ConversationRepository,
    private val toolMemory: ToolMemory,
    private val providerRegistry: ProviderRegistry
) : ViewModel() {

    companion object {
        /**
         * Holds a message queued from an external source (e.g., PROCESS_TEXT intent).
         * Observable as a StateFlow so that ChatScreen can reactively consume it
         * even when the ViewModel is already alive (onNewIntent scenario).
         */
        private val _pendingMessage = MutableStateFlow<String?>(null)
        val pendingMessage: StateFlow<String?> = _pendingMessage.asStateFlow()

        /** Queue a message to be sent to the orchestrator. */
        fun queuePendingMessage(message: String) {
            _pendingMessage.value = message
        }
    }

    /**
     * Consume and send the pending message. Returns true if a message was consumed.
     */
    fun consumePendingMessage(): Boolean {
        val queued = _pendingMessage.value
        if (!queued.isNullOrBlank()) {
            _pendingMessage.value = null
            Log.d("ChatViewModel", "Consuming pending PROCESS_TEXT message (${queued.length} chars)")
            sendMessage(queued)
            return true
        }
        return false
    }

    private val _conversations = MutableStateFlow<List<Conversation>>(listOf(Conversation()))
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(_conversations.value.firstOrNull()?.id)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    private val _currentConversation = MutableStateFlow(Conversation())
    val currentConversation: StateFlow<Conversation> = _currentConversation.asStateFlow()

    /** True while waiting for Claude to finish a response */
    val isStreaming: StateFlow<Boolean> = claudeProcessManager.isStreaming

    /** True when the orchestrator process is alive */
    val isClaudeRunning: StateFlow<Boolean> = claudeProcessManager.isRunning

    /** Last error from the process manager, or null */
    val claudeError: StateFlow<String?> = claudeProcessManager.lastError

    /** Sub-agent and MCP health for visualizer */
    val subAgentStatus: StateFlow<String> = claudeProcessManager.subAgentStatus
    val mcpAlive: StateFlow<Boolean> = claudeProcessManager.mcpAlive
    val subAgentCrashed: StateFlow<Boolean> = claudeProcessManager.subAgentCrashed

    /** TTS state for UI (AudioVisualizer) */
    val isTtsPlaying = TtsManager.isPlaying
    val audioLevel = TtsManager.audioLevel

    // Accumulator for streaming text blocks within the current response
    private var streamingTextAccumulator = StringBuilder()
    private var streamingMessageId: String? = null

    // Diagnostic: track first token for TTFT measurement
    private var diagFirstTokenReceived = false

    // TTS streaming state - sentence-based chunking
    private var ttsFirstSentenceSent = false
    private var ttsBuffer = StringBuilder()
    private var ttsRemainderTimer: Job? = null
    private val TTS_REMAINDER_DELAY_MS = 1500L
    private val TTS_BUFFER_SENTENCES = 2   // How many sentences to buffer after the first one
    private val TTS_SAFETY_VALVE_CHARS = 300  // Force-send if buffer exceeds this without sentence ends
    private val TTS_PUNCTUATION_LOOKAHEAD = 50   // Max chars to search ahead for punctuation break

    // Provider system state
    /** The currently active provider ID, observable by the UI */
    val activeProviderId: StateFlow<String> = providerRegistry.activeProviderId

    /** True while a non-CLI provider is streaming a response */
    private val _isProviderStreaming = MutableStateFlow(false)
    val isProviderStreaming: StateFlow<Boolean> = _isProviderStreaming.asStateFlow()

    /** Active coroutine job for non-CLI provider streaming */
    private var providerStreamJob: Job? = null

    /** Accumulator for tool call arguments during non-CLI streaming */
    private val toolCallArgBuffers = mutableMapOf<String, StringBuilder>()

    init {
        // Ensure Claude is running when chat is opened (auto-managed, invisible to user)
        if (!claudeProcessManager.isRunning()) {
            Log.d("ChatViewModel", "Auto-starting Claude process")
            claudeProcessManager.start()
        }

        // Load persisted conversations from DB
        viewModelScope.launch {
            try {
                val saved = conversationRepository.loadAllConversations()
                if (saved.isNotEmpty()) {
                    _conversations.value = saved
                    val mostRecent = saved.first()
                    _selectedConversationId.value = mostRecent.id
                    _currentConversation.value = mostRecent
                    Log.d("ChatViewModel", "Loaded ${saved.size} conversations from DB")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load conversations: ${e.message}")
            }

            // Check for a pending message queued from PROCESS_TEXT or other external source
            val queued = _pendingMessage.value
            if (!queued.isNullOrBlank()) {
                _pendingMessage.value = null
                Log.d("ChatViewModel", "Processing queued PROCESS_TEXT message (${queued.length} chars)")
                // Small delay to ensure UI is ready and Claude process has started
                delay(500)
                sendMessage(queued)
            }
        }

        // Collect messages from Claude and route them to the current conversation
        viewModelScope.launch {
            claudeProcessManager.messages
                .buffer(Channel.UNLIMITED)
                .collect { message ->
                    handleClaudeMessage(message)
                }
        }
    }

    fun selectConversation(id: String) {
        _selectedConversationId.value = id
        _currentConversation.value = _conversations.value.find { it.id == id } ?: Conversation()
    }

    fun newConversation() {
        val conv = Conversation()
        _conversations.value = _conversations.value + conv
        selectConversation(conv.id)
        viewModelScope.launch { conversationRepository.saveConversation(conv) }
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        if (_selectedConversationId.value == id) {
            _selectedConversationId.value = _conversations.value.firstOrNull()?.id
            _currentConversation.value = _conversations.value.firstOrNull() ?: Conversation()
        }
        viewModelScope.launch { conversationRepository.deleteConversation(id) }
    }

    /**
     * Finds the first sentence ending in text
     * Returns index after the ending punctuation, or -1 if not found
     */
    private fun findSentenceEnd(text: String, startIndex: Int = 0): Int {
        val sentenceEnders = setOf('.', '!', '?')
        for (i in startIndex until text.length) {
            if (text[i] in sentenceEnders) {
                val result = i + 1
                Log.d("ChatViewModel", "findSentenceEnd: Found '${text[i]}' at index $i, returning $result")
                return result
            }
        }
        Log.d("ChatViewModel", "findSentenceEnd: No sentence end found in text='$text' from startIndex=$startIndex")
        return -1
    }

    /**
     * Finds the position after the Nth sentence ending in text.
     * Returns index after the Nth ending punctuation, or -1 if not enough endings found.
     */
    private fun findNthSentenceEnd(text: String, n: Int, startIndex: Int = 0): Int {
        val sentenceEnders = setOf('.', '!', '?')
        var count = 0
        for (i in startIndex until text.length) {
            if (text[i] in sentenceEnders) {
                count++
                if (count >= n) {
                    return i + 1  // Return index after the ending punctuation
                }
            }
        }
        return -1  // Not enough sentence endings found
    }

    /**
     * Finds the first punctuation break point suitable for TTS chunking.
     * Searches from startIndex up to (startIndex + maxLookahead) for a natural pause.
     * Returns index after the punctuation, or -1 if not found within range.
     * Recognizes: . ! ? , ; : and em-dash (\u2014)
     */
    private fun findPunctuationBreak(text: String, startIndex: Int = 0, maxLookahead: Int = TTS_PUNCTUATION_LOOKAHEAD): Int {
        val breakChars = setOf('.', '!', '?', ',', ';', ':', '\u2014')
        val searchEnd = minOf(startIndex + maxLookahead, text.length)
        for (i in startIndex until searchEnd) {
            if (text[i] in breakChars) {
                val result = i + 1
                Log.d("ChatViewModel", "findPunctuationBreak: Found '${text[i]}' at index $i, returning $result")
                return result
            }
        }
        Log.d("ChatViewModel", "findPunctuationBreak: No punctuation in range [$startIndex, $searchEnd)")
        return -1
    }

    /**
     * Sends text to TTS if it has voice content
     */
    private fun sendToTts(text: String) {
        val trimmed = text.trim()
        Log.d("ChatViewModel", "sendToTts: text='$trimmed', length=${trimmed.length}")
        if (trimmed.isNotBlank()) {
            val parsed = ResponseParser.parse(trimmed)
            Log.d("ChatViewModel", "sendToTts: hasVoice=${parsed.hasVoice}, voiceContent='${parsed.voiceContent}'")
            if (parsed.hasVoice) {
                Log.d("ChatViewModel", "sendToTts: CALLING TtsManager.speak()")
                val cleanText = ResponseParser.cleanForVoice(parsed.voiceContent)
                DiagnosticLogger.ttsQueued(cleanText, 0, TtsManager.voice.value)
                TtsManager.speak(cleanText)
            } else {
                Log.d("ChatViewModel", "sendToTts: No voice content detected, NOT calling TtsManager.speak()")
            }
        } else {
            Log.d("ChatViewModel", "sendToTts: Text is blank, skipping")
        }
    }

    /**
     * Send a user message.
     * Routes to the appropriate backend based on the active provider:
     * - "claude_cli" → ClaudeProcessManager (existing subprocess path)
     * - Any other provider → AiProvider.sendMessage() Flow
     */
    fun sendMessage(text: String) {
        DiagnosticLogger.userMessageSent(text.length)
        DiagnosticLogger.log("USER_INTENT", "chars=${text.length}, preview='${text.take(120)}'")
        val userMessage = ChatMessage(role = MessageRole.USER, content = text)
        appendMessage(userMessage)

        val activeProvider = providerRegistry.getActiveProvider()
        if (activeProvider.id == "claude_cli") {
            sendViaClaude(text)
        } else {
            sendViaProvider(text, activeProvider)
        }
    }

    /**
     * Existing Claude CLI code path — sends via ClaudeProcessManager subprocess.
     * This method is the original sendMessage body, extracted without modification.
     */
    private fun sendViaClaude(text: String) {
        if (claudeProcessManager.isRunning()) {
            // Reset streaming accumulator for this new response
            streamingTextAccumulator = StringBuilder()
            streamingMessageId = null

            // Reset TTS streaming state for new response
            ttsRemainderTimer?.cancel()
            ttsRemainderTimer = null
            ttsBuffer = StringBuilder()
            ttsFirstSentenceSent = false
            diagFirstTokenReceived = false

            // Send to Claude via the orchestrator
            val sessionId = _currentConversation.value.claudeSessionId ?: "default"
            DiagnosticLogger.claudeApiRequestSent(sessionId)
            claudeProcessManager.sendMessage(text, sessionId)
        } else {
            // Fallback stub when Claude isn't running
            val stubReply = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Claude process not running. Go to Settings > System to start it, or restart the app."
            )
            appendMessage(stubReply)
        }
    }

    /**
     * New code path for API-based providers (Gemini, OpenAI, Custom, etc.).
     * Collects the AiStreamEvent flow and maps events to the existing UI model.
     */
    private fun sendViaProvider(text: String, provider: AiProvider) {
        if (!provider.isReady()) {
            val stubReply = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Provider '${provider.name}' is not ready. Check its configuration in Settings.",
                messageType = "error",
                isError = true
            )
            appendMessage(stubReply)
            return
        }

        // Reset streaming state
        streamingTextAccumulator = StringBuilder()
        streamingMessageId = null
        toolCallArgBuffers.clear()

        // Reset TTS streaming state for new response
        ttsRemainderTimer?.cancel()
        ttsRemainderTimer = null
        ttsBuffer = StringBuilder()
        ttsFirstSentenceSent = false
        diagFirstTokenReceived = false

        _isProviderStreaming.value = true

        // Build the AiMessage conversation history from the current conversation
        val aiMessages = buildAiMessageHistory()

        providerStreamJob = viewModelScope.launch {
            try {
                provider.sendMessage(
                    messages = aiMessages,
                    systemPrompt = "" // Providers use their own default system prompt
                ).buffer(Channel.UNLIMITED)
                    .collect { event ->
                        handleProviderStreamEvent(event)
                    }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Provider stream error: ${e.message}", e)
                val errorMsg = ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Provider error: ${e.message ?: "Unknown error"}",
                    messageType = "error",
                    isError = true
                )
                appendMessage(errorMsg)
            } finally {
                _isProviderStreaming.value = false
                providerStreamJob = null
            }
        }
    }

    /**
     * Build AiMessage list from the current conversation's ChatMessage history.
     * Maps the internal ChatMessage/MessageRole model to the provider AiMessage model.
     */
    private fun buildAiMessageHistory(): List<AiMessage> {
        val conv = _currentConversation.value
        return conv.messages
            .filter { it.messageType != "streaming" } // Exclude in-progress streaming placeholders
            .mapNotNull { msg ->
                when (msg.role) {
                    MessageRole.USER -> AiMessage.user(msg.content)
                    MessageRole.ASSISTANT -> AiMessage.assistant(msg.content)
                    MessageRole.SYSTEM -> null // System/tool messages are not sent to API providers
                }
            }
    }

    /**
     * Handle a single AiStreamEvent from a non-CLI provider.
     * Maps each event variant to the appropriate UI update.
     */
    private fun handleProviderStreamEvent(event: AiStreamEvent) {
        when (event) {
            is AiStreamEvent.TextDelta -> {
                // Diagnostic: first token
                if (!diagFirstTokenReceived) {
                    diagFirstTokenReceived = true
                    DiagnosticLogger.claudeFirstToken()
                }

                streamingTextAccumulator.append(event.text)
                updateStreamingMessage(streamingTextAccumulator.toString())

                // Feed TTS buffer with the same sentence-chunking logic as the CLI path
                feedTtsBuffer(event.text)
            }

            is AiStreamEvent.ToolCallStart -> {
                toolCallArgBuffers[event.callId] = StringBuilder()
                DiagnosticLogger.log("TOOL_USE_DISPLAYED", "tool=${event.toolName}, id=${event.callId}")
                val chatMsg = ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Using tool: ${event.toolName}",
                    messageType = "tool_use",
                    toolName = event.toolName
                )
                appendMessage(chatMsg)
            }

            is AiStreamEvent.ToolCallDelta -> {
                toolCallArgBuffers[event.callId]?.append(event.argumentsDelta)
            }

            is AiStreamEvent.ToolCallEnd -> {
                val argsJson = toolCallArgBuffers.remove(event.callId)?.toString() ?: "{}"
                DiagnosticLogger.log("TOOL_CALL_END", "id=${event.callId}, argsLength=${argsJson.length}")

                viewModelScope.launch {
                    try {
                        toolMemory.recordUsage(
                            toolId = event.callId,
                            inputJson = argsJson,
                            conversationId = _currentConversation.value.id
                        )
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Failed to record tool usage: ${e.message}")
                    }
                }
            }

            is AiStreamEvent.StreamComplete -> {
                // Finalize the streaming message: remove streaming placeholder, add final message
                val finalText = streamingTextAccumulator.toString()
                if (streamingMessageId != null) {
                    val conv = _currentConversation.value
                    val cleaned = conv.copy(
                        messages = conv.messages.filter { it.messageType != "streaming" },
                        updatedAt = System.currentTimeMillis()
                    )
                    _currentConversation.value = cleaned
                    _conversations.value = _conversations.value.map {
                        if (it.id == cleaned.id) cleaned else it
                    }
                    streamingMessageId = null
                }

                if (finalText.isNotBlank()) {
                    DiagnosticLogger.log("ASSISTANT_RESPONSE", "chars=${finalText.length}, preview='${finalText.take(120)}'")
                    val chatMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = finalText,
                        messageType = "text"
                    )
                    appendMessage(chatMsg)
                }

                // TTS Phase 3: flush remaining buffer
                ttsRemainderTimer?.cancel()
                ttsRemainderTimer = null
                if (ttsBuffer.isNotEmpty()) {
                    Log.d("ChatViewModel", "Provider TTS Phase 3: Sending remaining buffer='${ttsBuffer}'")
                    DiagnosticLogger.ttsChunkSent(3, ttsBuffer.length, "final_flush")
                    sendToTts(ttsBuffer.toString())
                    ttsBuffer.clear()
                }
                ttsFirstSentenceSent = false

                // Reset streaming accumulator
                streamingTextAccumulator = StringBuilder()

                DiagnosticLogger.log("PROVIDER_COMPLETE", "inputTokens=${event.inputTokens}, outputTokens=${event.outputTokens}")
            }

            is AiStreamEvent.StreamError -> {
                Log.e("ChatViewModel", "Provider stream error: ${event.message}")
                val errorMsg = ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: ${event.message}",
                    messageType = "error",
                    isError = true
                )
                appendMessage(errorMsg)
            }
        }
    }

    /**
     * Feed a text chunk into the TTS sentence-buffering pipeline.
     * Reuses the same 3-phase TTS logic as the CLI streaming path.
     */
    private fun feedTtsBuffer(text: String) {
        ttsBuffer.append(text)
        Log.d("ChatViewModel", "TTS: Buffer append, text='$text', bufferSize=${ttsBuffer.length}, firstSentSent=$ttsFirstSentenceSent")

        // Cancel any pending remainder timer on new data
        ttsRemainderTimer?.cancel()
        ttsRemainderTimer = null

        DiagnosticLogger.ttsBufferState(if (!ttsFirstSentenceSent) 1 else 2, ttsBuffer.length, ttsFirstSentenceSent)

        if (!ttsFirstSentenceSent) {
            // Phase 1: Send first complete sentence ASAP for low latency
            val bufferText = ttsBuffer.toString()
            val firstEnd = findSentenceEnd(bufferText)

            if (firstEnd > 0) {
                Log.d("ChatViewModel", "TTS Phase 1: First sentence found, sending immediately ($firstEnd chars)")
                DiagnosticLogger.ttsChunkSent(1, firstEnd, "first_sentence")
                sendToTts(bufferText.substring(0, firstEnd))
                ttsBuffer.delete(0, firstEnd)
                ttsFirstSentenceSent = true
            } else {
                // No complete sentence yet - start fallback timer
                Log.d("ChatViewModel", "TTS Phase 1: No sentence end yet, starting ${TTS_REMAINDER_DELAY_MS}ms timer")
                ttsRemainderTimer = viewModelScope.launch {
                    delay(TTS_REMAINDER_DELAY_MS)
                    ensureActive()
                    val textToSend = ttsBuffer.toString()
                    if (textToSend.isNotBlank()) {
                        Log.d("ChatViewModel", "TTS Phase 1: Timer expired, sending raw buffer (${textToSend.length} chars)")
                        DiagnosticLogger.ttsChunkSent(1, textToSend.length, "timer_expired")
                        sendToTts(textToSend)
                        ttsBuffer.clear()
                    }
                    ttsFirstSentenceSent = true
                    ttsRemainderTimer = null
                }
            }
        } else {
            // Phase 2: Buffer 2 complete sentences before sending
            val bufferText = ttsBuffer.toString()
            val secondEnd = findNthSentenceEnd(bufferText, TTS_BUFFER_SENTENCES)

            if (secondEnd > 0) {
                // Found 2 sentences - send them together
                Log.d("ChatViewModel", "TTS Phase 2: Found $TTS_BUFFER_SENTENCES sentences, sending ($secondEnd chars)")
                DiagnosticLogger.ttsChunkSent(2, secondEnd, "2_sentences")
                sendToTts(bufferText.substring(0, secondEnd))
                ttsBuffer.delete(0, secondEnd)
            } else if (bufferText.length >= TTS_SAFETY_VALVE_CHARS) {
                // Safety valve: buffer too large, send at best break point
                val sentenceEnd = findSentenceEnd(bufferText)
                if (sentenceEnd > 0) {
                    Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending at sentence end ($sentenceEnd chars)")
                    DiagnosticLogger.ttsChunkSent(2, sentenceEnd, "safety_valve_sentence")
                    sendToTts(bufferText.substring(0, sentenceEnd))
                    ttsBuffer.delete(0, sentenceEnd)
                } else {
                    val punctBreak = findPunctuationBreak(bufferText, bufferText.length / 2)
                    if (punctBreak > 0) {
                        Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending at punctuation ($punctBreak chars)")
                        DiagnosticLogger.ttsChunkSent(2, punctBreak, "safety_valve_punct")
                        sendToTts(bufferText.substring(0, punctBreak))
                        ttsBuffer.delete(0, punctBreak)
                    } else {
                        Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending entire buffer (${bufferText.length} chars)")
                        DiagnosticLogger.ttsChunkSent(2, bufferText.length, "safety_valve_full")
                        sendToTts(bufferText)
                        ttsBuffer.clear()
                    }
                }
            } else {
                // Not enough sentences yet and under safety valve
                // Start remainder timer in case stream stalls or ends slowly
                Log.d("ChatViewModel", "TTS Phase 2: Waiting for $TTS_BUFFER_SENTENCES sentences (have ${bufferText.length} chars)")
                ttsRemainderTimer = viewModelScope.launch {
                    delay(TTS_REMAINDER_DELAY_MS)
                    ensureActive()
                    val remaining = ttsBuffer.toString()
                    if (remaining.isNotBlank()) {
                        // Timer expired - send whatever we have at best break point
                        val sentEnd = findSentenceEnd(remaining)
                        if (sentEnd > 0) {
                            Log.d("ChatViewModel", "TTS Phase 2: Timer expired, sending at sentence end ($sentEnd chars)")
                            DiagnosticLogger.ttsChunkSent(2, sentEnd, "timer_sentence")
                            sendToTts(remaining.substring(0, sentEnd))
                            ttsBuffer.delete(0, sentEnd)
                        } else {
                            Log.d("ChatViewModel", "TTS Phase 2: Timer expired, sending raw (${remaining.length} chars)")
                            DiagnosticLogger.ttsChunkSent(2, remaining.length, "timer_raw")
                            sendToTts(remaining)
                            ttsBuffer.clear()
                        }
                    }
                    ttsRemainderTimer = null
                }
            }
        }
    }

    /** Interrupt the current response generation (works for both CLI and API providers) */
    fun interruptResponse() {
        val activeProvider = providerRegistry.getActiveProvider()
        if (activeProvider.id == "claude_cli") {
            claudeProcessManager.interrupt()
        } else {
            // Cancel the provider stream coroutine and call provider.cancel()
            providerStreamJob?.cancel()
            providerStreamJob = null
            _isProviderStreaming.value = false
            viewModelScope.launch {
                try {
                    activeProvider.cancel()
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Provider cancel failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Switch the active AI provider.
     * Delegates to ProviderRegistry and returns null on success or an error message.
     */
    fun switchProvider(providerId: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = providerRegistry.switchProvider(providerId)
            if (error != null) {
                Log.e("ChatViewModel", "switchProvider failed: $error")
            } else {
                Log.d("ChatViewModel", "Switched to provider: $providerId")
            }
            onResult(error)
        }
    }

    // ---- Internal helpers ----

    private fun handleClaudeMessage(message: ClaudeProcessManager.Message) {
        when (message) {
            is ClaudeProcessManager.AssistantMessage -> {
                // Extract text content from content blocks
                val textParts = message.content.filterIsInstance<ClaudeProcessManager.TextBlock>()
                val toolBlocks = message.content.filterIsInstance<ClaudeProcessManager.ToolUseBlock>()
                val toolResults = message.content.filterIsInstance<ClaudeProcessManager.ToolResultBlock>()

                // Remove streaming preview before adding final message
                if (streamingMessageId != null) {
                    val conv = _currentConversation.value
                    val cleaned = conv.copy(
                        messages = conv.messages.filter { it.messageType != "streaming" },
                        updatedAt = System.currentTimeMillis()
                    )
                    _currentConversation.value = cleaned
                    _conversations.value = _conversations.value.map {
                        if (it.id == cleaned.id) cleaned else it
                    }
                    streamingMessageId = null
                }

                // Emit text blocks as assistant messages
                for (textBlock in textParts) {
                    DiagnosticLogger.log("ASSISTANT_RESPONSE", "chars=${textBlock.text.length}, preview='${textBlock.text.take(120)}'")
                    val chatMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = textBlock.text,
                        messageType = "text"
                    )
                    appendMessage(chatMsg)

                    // TTS is handled by streaming buffer phases, not by AssistantMessage
                    // This would cause double-speaking since stream deltas already send to TTS
                    // if (chatMsg.hasVoiceContent) {
                    //     TtsManager.speak(chatMsg.voiceContent)
                    // }
                }

                // Emit tool use as system messages for display
                for (tool in toolBlocks) {
                    val inputPreview = tool.input?.toString()?.take(150) ?: "(none)"
                    DiagnosticLogger.log("TOOL_USE_DISPLAYED", "tool=${tool.name}, id=${tool.id}, input=$inputPreview")
                    val chatMsg = ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = "Using tool: ${tool.name}",
                        messageType = "tool_use",
                        toolName = tool.name,
                        toolInput = tool.input?.toString()
                    )
                    appendMessage(chatMsg)

                    viewModelScope.launch {
                        try {
                            toolMemory.recordUsage(
                                toolId = tool.name,
                                inputJson = tool.input?.toString() ?: "{}",
                                conversationId = _currentConversation.value.id
                            )
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Failed to record tool usage: ${e.message}")
                        }
                    }
                }

                // Emit tool results
                for (result in toolResults) {
                    val resultPreview = (result.content ?: "(no output)").take(150)
                    val isErr = result.isError ?: false
                    DiagnosticLogger.log("TOOL_RESULT_DISPLAYED", "toolUseId=${result.toolUseId}, is_error=$isErr, preview=$resultPreview")
                    if (isErr) {
                        DiagnosticLogger.logError("TOOL_ERROR", "toolUseId=${result.toolUseId}, error=${result.content?.take(300) ?: "(no content)"}")
                    }
                    val chatMsg = ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = result.content ?: "(no output)",
                        messageType = "tool_result",
                        isError = isErr
                    )
                    appendMessage(chatMsg)
                }

                // Handle assistant error
                if (message.error != null) {
                    val errorMsg = ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = "Error: ${message.error.type} - ${message.error.message ?: ""}",
                        messageType = "error",
                        isError = true
                    )
                    appendMessage(errorMsg)
                }
            }

            is ClaudeProcessManager.ResultMessage -> {
                // Update conversation with session ID for future resume
                if (message.sessionId.isNotBlank()) {
                    val conv = _currentConversation.value.copy(
                        claudeSessionId = message.sessionId,
                        updatedAt = System.currentTimeMillis()
                    )
                    _currentConversation.value = conv
                    _conversations.value = _conversations.value.map {
                        if (it.id == conv.id) conv else it
                    }
                }

                // Phase 3: Final flush - send any remaining TTS buffer
                DiagnosticLogger.claudeResponseComplete(message.durationMs)
                DiagnosticLogger.log("CONVERSATION_COMPLETE", "durationMs=${message.durationMs}, durationApiMs=${message.durationApiMs}, sessionId=${message.sessionId}")
                Log.d("ChatViewModel", "TTS Phase 3: ResultMessage received, flushing buffer")
                ttsRemainderTimer?.cancel()
                ttsRemainderTimer = null

                if (ttsBuffer.isNotEmpty()) {
                    Log.d("ChatViewModel", "TTS Phase 3: Sending remaining buffer='${ttsBuffer.toString()}'")
                    DiagnosticLogger.ttsChunkSent(3, ttsBuffer.length, "final_flush")
                    sendToTts(ttsBuffer.toString())
                    ttsBuffer.clear()
                } else {
                    Log.d("ChatViewModel", "TTS Phase 3: Buffer empty, nothing to send")
                }

                // Reset TTS state for next conversation
                ttsFirstSentenceSent = false

                // Reset streaming accumulator so it doesn't fire again
                streamingTextAccumulator = StringBuilder()
                streamingMessageId = null
            }

            is ClaudeProcessManager.StreamEvent -> {
                // Handle real-time streaming deltas
                val event = message.event
                val eventType = event?.get("type")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

                if (eventType == "content_block_delta") {
                    val delta = event?.get("delta")
                        ?.let { it as? kotlinx.serialization.json.JsonObject }
                    val deltaType = delta?.get("type")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

                    if (deltaType == "text_delta") {
                        val text = delta?.get("text")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        if (text != null) {
                            // Diagnostic: first token from Claude
                            if (!diagFirstTokenReceived) {
                                diagFirstTokenReceived = true
                                DiagnosticLogger.claudeFirstToken()
                            }

                            streamingTextAccumulator.append(text)
                            // Update or create the streaming message
                            updateStreamingMessage(streamingTextAccumulator.toString())

                            // TTS streaming logic - 2-sentence buffering strategy
                            // Phase 1: Send FIRST sentence immediately (low latency start)
                            // Phase 2: Buffer 2 sentences at a time (smoother playback, pre-queued)
                            // Phase 3: Flush remaining on stream end (handled in ResultMessage)
                            ttsBuffer.append(text)
                            Log.d("ChatViewModel", "TTS: Buffer append, text='$text', bufferSize=${ttsBuffer.length}, firstSentSent=$ttsFirstSentenceSent")

                            // Cancel any pending remainder timer on new data
                            ttsRemainderTimer?.cancel()
                            ttsRemainderTimer = null

                            DiagnosticLogger.ttsBufferState(if (!ttsFirstSentenceSent) 1 else 2, ttsBuffer.length, ttsFirstSentenceSent)

                            if (!ttsFirstSentenceSent) {
                                // Phase 1: Send first complete sentence ASAP for low latency
                                val bufferText = ttsBuffer.toString()
                                val firstEnd = findSentenceEnd(bufferText)

                                if (firstEnd > 0) {
                                    Log.d("ChatViewModel", "TTS Phase 1: First sentence found, sending immediately (${firstEnd} chars)")
                                    DiagnosticLogger.ttsChunkSent(1, firstEnd, "first_sentence")
                                    sendToTts(bufferText.substring(0, firstEnd))
                                    ttsBuffer.delete(0, firstEnd)
                                    ttsFirstSentenceSent = true
                                } else {
                                    // No complete sentence yet - start fallback timer
                                    Log.d("ChatViewModel", "TTS Phase 1: No sentence end yet, starting ${TTS_REMAINDER_DELAY_MS}ms timer")
                                    ttsRemainderTimer = viewModelScope.launch {
                                        delay(TTS_REMAINDER_DELAY_MS)
                                        ensureActive()
                                        val textToSend = ttsBuffer.toString()
                                        if (textToSend.isNotBlank()) {
                                            Log.d("ChatViewModel", "TTS Phase 1: Timer expired, sending raw buffer (${textToSend.length} chars)")
                                            DiagnosticLogger.ttsChunkSent(1, textToSend.length, "timer_expired")
                                            sendToTts(textToSend)
                                            ttsBuffer.clear()
                                        }
                                        ttsFirstSentenceSent = true
                                        ttsRemainderTimer = null
                                    }
                                }
                            } else {
                                // Phase 2: Buffer 2 complete sentences before sending
                                val bufferText = ttsBuffer.toString()
                                val secondEnd = findNthSentenceEnd(bufferText, TTS_BUFFER_SENTENCES)

                                if (secondEnd > 0) {
                                    // Found 2 sentences - send them together
                                    Log.d("ChatViewModel", "TTS Phase 2: Found $TTS_BUFFER_SENTENCES sentences, sending ($secondEnd chars)")
                                    DiagnosticLogger.ttsChunkSent(2, secondEnd, "2_sentences")
                                    sendToTts(bufferText.substring(0, secondEnd))
                                    ttsBuffer.delete(0, secondEnd)
                                } else if (bufferText.length >= TTS_SAFETY_VALVE_CHARS) {
                                    // Safety valve: buffer too large, send at best break point
                                    val sentenceEnd = findSentenceEnd(bufferText)
                                    if (sentenceEnd > 0) {
                                        Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending at sentence end ($sentenceEnd chars)")
                                        DiagnosticLogger.ttsChunkSent(2, sentenceEnd, "safety_valve_sentence")
                                        sendToTts(bufferText.substring(0, sentenceEnd))
                                        ttsBuffer.delete(0, sentenceEnd)
                                    } else {
                                        val punctBreak = findPunctuationBreak(bufferText, bufferText.length / 2)
                                        if (punctBreak > 0) {
                                            Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending at punctuation ($punctBreak chars)")
                                            DiagnosticLogger.ttsChunkSent(2, punctBreak, "safety_valve_punct")
                                            sendToTts(bufferText.substring(0, punctBreak))
                                            ttsBuffer.delete(0, punctBreak)
                                        } else {
                                            Log.d("ChatViewModel", "TTS Phase 2: Safety valve, sending entire buffer (${bufferText.length} chars)")
                                            DiagnosticLogger.ttsChunkSent(2, bufferText.length, "safety_valve_full")
                                            sendToTts(bufferText)
                                            ttsBuffer.clear()
                                        }
                                    }
                                } else {
                                    // Not enough sentences yet and under safety valve
                                    // Start remainder timer in case stream stalls or ends slowly
                                    Log.d("ChatViewModel", "TTS Phase 2: Waiting for $TTS_BUFFER_SENTENCES sentences (have ${bufferText.length} chars)")
                                    ttsRemainderTimer = viewModelScope.launch {
                                        delay(TTS_REMAINDER_DELAY_MS)
                                        ensureActive()
                                        val remaining = ttsBuffer.toString()
                                        if (remaining.isNotBlank()) {
                                            // Timer expired - send whatever we have at best break point
                                            val sentEnd = findSentenceEnd(remaining)
                                            if (sentEnd > 0) {
                                                Log.d("ChatViewModel", "TTS Phase 2: Timer expired, sending at sentence end ($sentEnd chars)")
                                                DiagnosticLogger.ttsChunkSent(2, sentEnd, "timer_sentence")
                                                sendToTts(remaining.substring(0, sentEnd))
                                                ttsBuffer.delete(0, sentEnd)
                                            } else {
                                                Log.d("ChatViewModel", "TTS Phase 2: Timer expired, sending raw (${remaining.length} chars)")
                                                DiagnosticLogger.ttsChunkSent(2, remaining.length, "timer_raw")
                                                sendToTts(remaining)
                                                ttsBuffer.clear()
                                            }
                                        }
                                        ttsRemainderTimer = null
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is ClaudeProcessManager.SystemMessage -> {
                // Log system messages but don't typically show them to user
                // unless it's a process restart or error
                when (message.subtype) {
                    "process_restart" -> {
                        val chatMsg = ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Claude process restarting...",
                            messageType = "system"
                        )
                        appendMessage(chatMsg)
                    }
                    "ready" -> {
                        // Silently note readiness
                    }
                }
            }

            is ClaudeProcessManager.ErrorMessage -> {
                val chatMsg = ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: ${message.error}${if (message.details != null) "\n${message.details}" else ""}",
                    messageType = "error",
                    isError = true
                )
                appendMessage(chatMsg)
            }

            is ClaudeProcessManager.UserMessageEcho -> {
                // Typically ignore echoes -- we already added the user message
            }
        }
    }

    /**
     * Update the in-progress streaming message with accumulated text.
     * Creates the message on first call, updates content on subsequent calls.
     */
    private fun updateStreamingMessage(text: String) {
        val conv = _currentConversation.value
        val id = streamingMessageId

        if (id != null) {
            // Update existing streaming message
            val updatedMessages = conv.messages.map { msg ->
                if (msg.id == id) msg.copy(content = text) else msg
            }
            val updated = conv.copy(
                messages = updatedMessages,
                updatedAt = System.currentTimeMillis()
            )
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map {
                if (it.id == updated.id) updated else it
            }
        } else {
            // Create new streaming message
            val newMsg = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = text,
                messageType = "streaming"
            )
            streamingMessageId = newMsg.id
            appendMessage(newMsg)
        }
    }

    private fun appendMessage(message: ChatMessage) {
        val updated = _currentConversation.value.copy(
            messages = _currentConversation.value.messages + message,
            updatedAt = System.currentTimeMillis()
        )
        _currentConversation.value = updated
        _conversations.value = _conversations.value.map {
            if (it.id == updated.id) updated else it
        }
        if (message.messageType != "streaming") {
            viewModelScope.launch {
                try {
                    conversationRepository.saveConversation(updated)
                    conversationRepository.saveMessage(updated.id, message)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Failed to persist message: ${e.message}")
                }
            }
        }
    }
}
