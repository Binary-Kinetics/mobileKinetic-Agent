package com.mobilekinetic.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.model.ChatMessage
import com.mobilekinetic.agent.data.model.MessageRole
import com.mobilekinetic.agent.data.speech.SpeechRecognizerManager
import com.mobilekinetic.agent.data.tts.TtsManager
import com.mobilekinetic.agent.ui.components.AudioVisualizer
import com.mobilekinetic.agent.ui.theme.LcarsBlack
import com.mobilekinetic.agent.ui.theme.LcarsContainerGray
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsPurple
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsTextSecondary
import com.mobilekinetic.agent.ui.theme.MyriadPro
import com.mobilekinetic.agent.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val conversation by viewModel.currentConversation.collectAsState()
    val messages = conversation.messages
    val ttsIsPlaying by TtsManager.isPlaying.collectAsState()
    val ttsAudioLevel by TtsManager.audioLevel.collectAsState()
    val isClaudeRunning by viewModel.isClaudeRunning.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val subAgentStatus by viewModel.subAgentStatus.collectAsState()
    val mcpAlive by viewModel.mcpAlive.collectAsState()
    val subAgentCrashed by viewModel.subAgentCrashed.collectAsState()

    // Speech recognition
    val context = LocalContext.current
    val speechManager = remember { SpeechRecognizerManager(context) }
    val isListening by speechManager.isListening.collectAsState()

    DisposableEffect(Unit) {
        onDispose { speechManager.destroy() }
    }

    // Observe pending messages from PROCESS_TEXT (or other external sources)
    val pendingMsg by ChatViewModel.pendingMessage.collectAsState()
    LaunchedEffect(pendingMsg) {
        if (pendingMsg != null) {
            viewModel.consumePendingMessage()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LcarsBlack)
    ) {
        // LAYER_ORDER: z=0 (bottom) -- Visualizer renders behind all chat content
        AudioVisualizer(
            isPlaying = ttsIsPlaying,
            audioLevel = ttsAudioLevel,
            isClaudeRunning = isClaudeRunning,
            isStreaming = isStreaming,
            subAgentStatus = subAgentStatus,
            mcpAlive = mcpAlive,
            subAgentCrashed = subAgentCrashed,
            modifier = Modifier.fillMaxSize() // LAYER_ORDER: fills entire screen area
        )
        // LAYER_ORDER: z=1 (top) -- Chat content renders on top of visualizer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding() // LAYER_ORDER: Column inherits transparent background, visualizer shows through
        ) {
            // Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "BINARY",
                    color = LcarsOrange,
                    fontFamily = MyriadPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "AGENT",
                    color = LcarsOrange,
                    fontFamily = MyriadPro,
                    fontWeight = FontWeight.Light,
                    fontSize = 20.sp
                )
            }

            // Messages
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Start a conversation with Claude",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ChatBubble(message = message)
                            Spacer(Modifier.height(8.dp))
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }

            // Input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.Bottom
            ) {
                // Text field — expands to full width when listening
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(if (isListening || inputText.isNotBlank()) 1f else 0.75f)
                        .then(
                            if (isListening) Modifier.height(120.dp) else Modifier
                        ),
                    placeholder = {
                        Text(
                            text = if (isListening) "Listening..." else "Type here...",
                            color = if (isListening) LcarsOrange else LcarsTextSecondary
                        )
                    },
                    leadingIcon = if (isListening) {
                        {
                            IconButton(onClick = { speechManager.stopListening() }) {
                                Icon(
                                    imageVector = Icons.Default.MicOff,
                                    contentDescription = "Stop listening",
                                    tint = LcarsOrange
                                )
                            }
                        }
                    } else null,
                    trailingIcon = {
                        if (isListening) {
                            // Voice mode: Send on top, Cancel (X) below
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            speechManager.stopListening()
                                            viewModel.sendMessage(inputText)
                                            inputText = ""
                                            keyboardController?.hide()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send message"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        speechManager.cancel()
                                        inputText = ""
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel voice input",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .border(
                                                width = 1.dp,
                                                color = Color.Gray,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(2.dp)
                                    )
                                }
                            }
                        } else {
                            // Normal typing mode: just the send button
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        viewModel.sendMessage(inputText)
                                        inputText = ""
                                        keyboardController?.hide()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message"
                                )
                            }
                        }
                    },
                    singleLine = false,
                    maxLines = if (isListening) 8 else 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.5f),
                        focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                        unfocusedBorderColor = if (isListening) LcarsOrange else Color.Unspecified,
                        focusedBorderColor = if (isListening) LcarsOrange else Color.Unspecified
                    )
                )

                // Mic button — hidden when listening or typing (text field goes full width)
                if (!isListening && inputText.isBlank()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                // Stop TTS to prevent feedback loop
                                if (TtsManager.isPlaying.value) {
                                    TtsManager.stop()
                                }
                                keyboardController?.hide()
                                speechManager.startListening(
                                    existingText = inputText,
                                    onPartial = { text -> inputText = text },
                                    onFinal = { text -> inputText = text },
                                    onStopped = { /* UI updates via isListening StateFlow */ }
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(0.25f)
                            .height(56.dp)
                            .background(
                                color = LcarsOrange.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = LcarsOrange.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = LcarsOrange,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isUser) LcarsPurple.copy(alpha = 0.5f) else LcarsContainerGray.copy(alpha = 0.5f)
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    val timeText = remember(message.timestamp) {
        SimpleDateFormat("ddMMM yyyy | HHmm", Locale.ENGLISH).format(Date(message.timestamp)).uppercase()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 7.sp,
                fontFamily = MyriadPro,
                fontWeight = FontWeight.Bold,
                lineHeight = 8.sp
            ),
            color = LcarsTextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = alignment
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(shape)
                        .background(backgroundColor)
                        .padding(12.dp)
                ) {
                    Text(
                        text = styledMessageContent(
                            message.content,
                            MaterialTheme.colorScheme.onSurface
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * Styles exclamation sentences in Myriad Pro Bold + SubduedCool.
 * Matches each sentence individually (text up to and including . ! or ?)
 * so missing spaces between sentences don't cause bold to bleed.
 */
@Composable
private fun styledMessageContent(
    text: String,
    defaultColor: Color
) = buildAnnotatedString {
    // Match each sentence: any non-terminator chars + terminator, or trailing text
    val sentencePattern = Regex("[^.!?]*[.!?]|[^.!?]+$")
    val matches = sentencePattern.findAll(text).toList()
    if (matches.isEmpty()) {
        withStyle(SpanStyle(color = defaultColor)) { append(text) }
        return@buildAnnotatedString
    }
    for (match in matches) {
        val sentence = match.value
        if (sentence.trimEnd().endsWith("!")) {
            withStyle(
                SpanStyle(
                    color = LcarsSubduedCool,
                    fontFamily = MyriadPro,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(sentence)
            }
        } else {
            withStyle(SpanStyle(color = defaultColor)) {
                append(sentence)
            }
        }
    }
}
