package com.mobilekinetic.agent.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.tts.TtsManager
import com.mobilekinetic.agent.ui.components.AudioVisualizer
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm
import com.mobilekinetic.agent.claude.ClaudeProcessManager
import com.mobilekinetic.agent.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToVault: () -> Unit = {},
    onNavigateToBlacklist: () -> Unit = {},
    onNavigateToMemoryInjection: () -> Unit = {},
    onNavigateToBackupSettings: () -> Unit = {},
    onNavigateToTtsSettings: () -> Unit = {},
    onNavigateToProviderSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val modelSelection by viewModel.modelSelection.collectAsState()
    var showModelDialog by remember { mutableStateOf(false) }
    var showForceCloseDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("SETTINGS", color = LcarsOrange) })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item { SectionHeader("API Configuration") }
            item {
                SettingsItem(
                    icon = Icons.Default.ModelTraining,
                    title = "Model Selection",
                    subtitle = modelSelection,
                    onClick = { showModelDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "AI Providers",
                    subtitle = "Select and configure AI provider backends",
                    onClick = onNavigateToProviderSettings
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Security") }
            item {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "Credential Vault",
                    subtitle = "Manage credentials for Gemma (API keys, tokens)",
                    onClick = onNavigateToVault
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Shield,
                    title = "Privacy Blacklist",
                    subtitle = "Manage privacy filtering rules",
                    onClick = onNavigateToBlacklist
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("System") }
            item {
                ClaudeProcessControlCard(viewModel = viewModel)
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Voice") }
            item {
                SettingsItem(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Text-to-Speech",
                    subtitle = "Provider selection, API keys, voice config",
                    onClick = onNavigateToTtsSettings
                )
            }
            item {
                TtsConfigurationCard(
                    onTestClick = { /* handled inside */ }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Heartbeat") }
            item {
                HeartbeatConfigurationCard(viewModel = viewModel)
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Memory & Context") }
            item {
                SettingsItem(
                    icon = Icons.Default.Memory,
                    title = "Context Injection",
                    subtitle = "RAG tiers, cleanup, classification",
                    onClick = onNavigateToMemoryInjection
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Backup") }
            item {
                SettingsItem(
                    icon = Icons.Default.Backup,
                    title = "Backup Configuration",
                    subtitle = "Local & remote backup settings",
                    onClick = onNavigateToBackupSettings
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
            item {
                Text(
                    text = "mK:a v0.1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Text(
                    text = "Binary Kinetics",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
            item {
                Text(
                    text = "Danger Zone",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFFF4444),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                Button(
                    onClick = { showForceCloseDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF331111),
                        contentColor = Color(0xFFFF4444)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Force Close mK:a")
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Model Selection Dialog — live from Anthropic API with fallbacks
    if (showModelDialog) {
        val isClaudeRunning by viewModel.isClaudeRunning.collectAsState()
        val liveModels by viewModel.availableModels.collectAsState()
        val isLoadingModels by viewModel.isLoadingModels.collectAsState()

        val fallbackModels = listOf(
            ClaudeProcessManager.ModelInfo("claude-opus-4-20250514", "Claude Opus 4", ""),
            ClaudeProcessManager.ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", ""),
            ClaudeProcessManager.ModelInfo("claude-haiku-3-5-20241022", "Claude Haiku 3.5", "")
        )

        // Trigger refresh when dialog opens if orchestrator is running
        LaunchedEffect(Unit) {
            if (isClaudeRunning) {
                viewModel.refreshModels()
            }
        }

        val displayModels = if (liveModels.isNotEmpty()) liveModels else fallbackModels

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Model", color = LcarsOrange, modifier = Modifier.weight(1f))
                    if (isClaudeRunning) {
                        androidx.compose.material3.IconButton(
                            onClick = { viewModel.refreshModels() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh models",
                                tint = LcarsOrange
                            )
                        }
                    }
                }
            },
            text = {
                Column {
                    if (liveModels.isEmpty() && !isLoadingModels) {
                        Text(
                            text = if (isClaudeRunning) "Using fallback models. Tap refresh to fetch live list."
                                   else "Start Claude to fetch live models.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    } else if (liveModels.isNotEmpty()) {
                        Text(
                            text = "Live models from Anthropic API",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    if (isLoadingModels) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = LcarsOrange
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Fetching models...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        displayModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setModelSelection(model.id)
                                        showModelDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = model.id == modelSelection,
                                    onClick = {
                                        viewModel.setModelSelection(model.id)
                                        showModelDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = LcarsOrange)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = model.displayName.ifBlank { model.id },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Force Close Confirmation Dialog
    if (showForceCloseDialog) {
        AlertDialog(
            onDismissRequest = { showForceCloseDialog = false },
            containerColor = Color(0xFF1A1A1A),
            title = {
                Text(
                    text = "Force Close",
                    color = Color(0xFFFF4444)
                )
            },
            text = {
                Text(
                    text = "This will immediately close mK:a. Are you sure?",
                    color = Color(0xFFDEDEDE)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF331111),
                        contentColor = Color(0xFFFF4444)
                    )
                ) {
                    Text("Force Close")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showForceCloseDialog = false }
                ) {
                    Text(
                        text = "Cancel",
                        color = LcarsOrange
                    )
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = LcarsGreen,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = LcarsSubduedCool)
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LcarsSubduedWarm)
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsConfigurationCard(
    onTestClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // TTS state from global manager
    val savedServerUrl by TtsManager.serverUrl.collectAsState()
    val savedEnabled by TtsManager.isEnabled.collectAsState()
    val savedVoice by TtsManager.voice.collectAsState()
    val savedSpeed by TtsManager.speed.collectAsState()
    val availableVoices by TtsManager.availableVoices.collectAsState()
    val isLoadingVoices by TtsManager.isLoadingVoices.collectAsState()
    val ttsAudioLevel by TtsManager.audioLevel.collectAsState()

    // Local editing state
    var ttsServerUrl by remember(savedServerUrl) { mutableStateOf(savedServerUrl) }
    var ttsEnabled by remember(savedEnabled) { mutableStateOf(savedEnabled) }
    var selectedVoice by remember(savedVoice) { mutableStateOf(savedVoice) }
    var speedValue by remember(savedSpeed) { mutableStateOf(savedSpeed) }
    var isTtsTesting by remember { mutableStateOf(false) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with enable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Kokoro TTS Server",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LcarsSubduedCool
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Voice synthesis for Claude responses",
                        style = MaterialTheme.typography.bodySmall,
                        color = LcarsSubduedWarm
                    )
                }
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = { enabled ->
                        ttsEnabled = enabled
                        TtsManager.configure(
                            serverUrl = ttsServerUrl,
                            voice = selectedVoice,
                            speed = speedValue,
                            enabled = enabled
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LcarsSubduedWarm,
                        checkedTrackColor = LcarsSubduedWarm.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // Server URL
            OutlinedTextField(
                value = ttsServerUrl,
                onValueChange = { ttsServerUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("https://your-domain.org:9299") },
                singleLine = true,
                enabled = ttsEnabled
            )

            Spacer(Modifier.height(12.dp))

            // Voice selection dropdown
            ExposedDropdownMenuBox(
                expanded = voiceDropdownExpanded,
                onExpandedChange = {
                    if (ttsEnabled) {
                        voiceDropdownExpanded = it
                        if (it && availableVoices.isEmpty()) {
                            TtsManager.refreshVoices()
                        }
                    }
                }
            ) {
                OutlinedTextField(
                    value = selectedVoice,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    label = { Text("Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceDropdownExpanded) },
                    enabled = ttsEnabled
                )
                ExposedDropdownMenu(
                    expanded = voiceDropdownExpanded,
                    onDismissRequest = { voiceDropdownExpanded = false }
                ) {
                    if (isLoadingVoices) {
                        DropdownMenuItem(
                            text = { Text("Loading voices...") },
                            onClick = {}
                        )
                    } else if (availableVoices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No voices available (check server)") },
                            onClick = { voiceDropdownExpanded = false }
                        )
                    } else {
                        availableVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice) },
                                onClick = {
                                    selectedVoice = voice
                                    TtsManager.setVoice(voice)
                                    voiceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Speed slider
            Text(
                text = "Speed: ${String.format("%.2f", speedValue)}x",
                style = MaterialTheme.typography.bodySmall,
                color = LcarsSubduedWarm
            )
            Slider(
                value = speedValue,
                onValueChange = { speedValue = it },
                onValueChangeFinished = { TtsManager.setSpeed(speedValue) },
                valueRange = 0.5f..2.0f,
                steps = 29,
                enabled = ttsEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = LcarsOrange,
                    activeTrackColor = LcarsOrange
                )
            )

            Spacer(Modifier.height(12.dp))

            // Visualizer preview during test
            if (isTtsTesting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                ) {
                    AudioVisualizer(
                        isPlaying = true,
                        audioLevel = ttsAudioLevel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Test / Save button
            Button(
                onClick = {
                    if (ttsServerUrl.isBlank()) {
                        Toast.makeText(context, "Enter server URL first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // Save configuration
                    TtsManager.configure(
                        serverUrl = ttsServerUrl,
                        voice = selectedVoice,
                        speed = speedValue,
                        enabled = true
                    )
                    ttsEnabled = true
                    isTtsTesting = true
                    scope.launch {
                        try {
                            if (!TtsManager.isServerAvailable()) {
                                Toast.makeText(context, "Cannot reach TTS server", Toast.LENGTH_SHORT).show()
                                isTtsTesting = false
                                return@launch
                            }
                            // Refresh voices after successful connection
                            TtsManager.refreshVoices()

                            TtsManager.speak(
                                text = "Hello! I am your text to speech assistant. " +
                                    "Binary Agent is now connected and ready to vocalize Claude's responses. " +
                                    "Voice synthesis complete.",
                                voice = selectedVoice,
                                speed = speedValue,
                                onComplete = { isTtsTesting = false },
                                onError = { e ->
                                    Toast.makeText(context, "TTS Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    isTtsTesting = false
                                }
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            isTtsTesting = false
                        }
                    }
                },
                enabled = ttsEnabled && ttsServerUrl.isNotBlank() && !isTtsTesting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LcarsBlue)
            ) {
                if (isTtsTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Test Voice")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enter your LuxTTS server URL (HTTPS recommended)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeartbeatConfigurationCard(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val heartbeatEnabled by viewModel.heartbeatEnabled.collectAsState()
    val heartbeatPrompt by viewModel.heartbeatPrompt.collectAsState()
    val heartbeatIntervalMinutes by viewModel.heartbeatIntervalMinutes.collectAsState()

    // Local editing state (keyed on saved values for sync)
    var enabled by remember(heartbeatEnabled) { mutableStateOf(heartbeatEnabled) }
    var promptText by remember(heartbeatPrompt) { mutableStateOf(heartbeatPrompt) }
    var intervalValue by remember(heartbeatIntervalMinutes) {
        mutableStateOf(heartbeatIntervalMinutes.toFloat())
    }

    // Debounced save for prompt text (save 1 second after user stops typing)
    LaunchedEffect(promptText) {
        if (promptText != heartbeatPrompt) {
            kotlinx.coroutines.delay(1000L)
            viewModel.setHeartbeatPrompt(promptText)
        }
    }

    // Map slider value to discrete interval options
    val intervalOptions = listOf(15, 30, 60, 120, 240, 480, 720, 1440)
    val intervalIndex = intervalOptions.indexOfFirst { it >= intervalValue.toInt() }
        .coerceAtLeast(0)

    fun minutesToLabel(minutes: Int): String = when {
        minutes < 60 -> "${minutes}min"
        minutes == 60 -> "1 hour"
        minutes < 1440 -> "${minutes / 60} hours"
        else -> "24 hours"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with enable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Heartbeat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LcarsSubduedCool
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Periodic prompt sent to Claude",
                        style = MaterialTheme.typography.bodySmall,
                        color = LcarsSubduedWarm
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { newEnabled ->
                        enabled = newEnabled
                        viewModel.setHeartbeatEnabled(newEnabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LcarsSubduedWarm,
                        checkedTrackColor = LcarsSubduedWarm.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // Prompt text field (multi-line)
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Heartbeat Prompt") },
                placeholder = { Text("Enter prompt to send periodically...") },
                minLines = 3,
                maxLines = 6,
                enabled = enabled
            )

            Spacer(Modifier.height(12.dp))

            // Interval slider
            Text(
                text = "Interval: ${minutesToLabel(intervalOptions.getOrElse(intervalIndex) { 60 })}",
                style = MaterialTheme.typography.bodySmall,
                color = LcarsSubduedWarm
            )
            Slider(
                value = intervalIndex.toFloat(),
                onValueChange = { newIdx ->
                    intervalValue = newIdx
                },
                onValueChangeFinished = {
                    val selectedMinutes = intervalOptions.getOrElse(intervalValue.toInt()) { 60 }
                    viewModel.setHeartbeatIntervalMinutes(selectedMinutes)
                },
                valueRange = 0f..(intervalOptions.size - 1).toFloat(),
                steps = intervalOptions.size - 2,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = LcarsOrange,
                    activeTrackColor = LcarsOrange
                )
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled && promptText.isNotBlank())
                    "Active: prompt will fire every ${minutesToLabel(intervalOptions.getOrElse(intervalIndex) { 60 })}"
                else if (enabled)
                    "Enter a prompt above to activate heartbeat"
                else
                    "When enabled, sends the prompt to Claude at the configured interval",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Auto-update after context treatment toggle
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(12.dp))

            val autoUpdateEnabled by viewModel.autoUpdateAfterDecay.collectAsState()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    Text(
                        text = "Auto-update (every 3rd day)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LcarsSubduedCool
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "After context treatment, Claude reviews notes and self-updates if needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = LcarsSubduedWarm
                    )
                }
                Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { viewModel.setAutoUpdateAfterDecay(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LcarsSubduedWarm,
                        checkedTrackColor = LcarsSubduedWarm.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

@Composable
private fun ClaudeProcessControlCard(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRunning by viewModel.isClaudeRunning.collectAsState()
    val lastError by viewModel.claudeLastError.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Claude Process",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LcarsSubduedCool
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isRunning) LcarsGreen else MaterialTheme.colorScheme.error)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "Running" else "Stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = LcarsSubduedWarm
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Error display
            lastError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start button
                Button(
                    onClick = {
                        viewModel.startClaude()
                        Toast.makeText(context, "Starting Claude process...", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = LcarsGreen)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Start", style = MaterialTheme.typography.bodySmall)
                }

                // Stop button
                Button(
                    onClick = {
                        viewModel.stopClaude()
                        Toast.makeText(context, "Stopping Claude process...", Toast.LENGTH_SHORT).show()
                    },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Restart button (full width)
            Button(
                onClick = {
                    viewModel.restartClaude()
                    Toast.makeText(context, "Restarting Claude process...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LcarsOrange)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Restart Claude")
            }

            Spacer(Modifier.height(8.dp))

            // Kill Terminal Processes button
            Button(
                onClick = {
                    viewModel.killTerminalProcesses { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Kill Terminal Processes")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Manage the Claude Agent SDK process. Use 'Kill Terminal Processes' if the process is hung.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
