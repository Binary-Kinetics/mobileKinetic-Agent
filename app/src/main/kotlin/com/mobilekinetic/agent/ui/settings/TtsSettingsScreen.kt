package com.mobilekinetic.agent.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.tts.TtsProviderType
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    viewModel: TtsSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe persisted state
    val savedProviderType by viewModel.ttsProviderType.collectAsState()
    val savedProviderUrl by viewModel.ttsProviderUrl.collectAsState()
    val savedApiKey by viewModel.ttsApiKey.collectAsState()
    val savedVoiceId by viewModel.ttsVoiceId.collectAsState()
    val savedModel by viewModel.ttsModel.collectAsState()
    val savedRate by viewModel.ttsRate.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()

    // Local editing state (keyed on saved values for sync)
    var selectedType by remember(savedProviderType) { mutableStateOf(savedProviderType) }
    var providerUrl by remember(savedProviderUrl) { mutableStateOf(savedProviderUrl) }
    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var voiceId by remember(savedVoiceId) { mutableStateOf(savedVoiceId) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var rate by remember(savedRate) { mutableFloatStateOf(savedRate) }

    // Show status messages as snackbar
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("TEXT-TO-SPEECH", color = LcarsOrange) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = LcarsOrange
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // --- Provider Selection ---
            item {
                Text(
                    text = "Provider",
                    style = MaterialTheme.typography.titleSmall,
                    color = LcarsGreen,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TtsProviderType.entries.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedType = type.id }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedType == type.id,
                                    onClick = { selectedType = type.id },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = LcarsOrange
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = type.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LcarsSubduedCool
                                    )
                                    Text(
                                        text = providerDescription(type),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LcarsSubduedWarm
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            // --- Provider-Specific Configuration ---
            item {
                val currentType = TtsProviderType.fromId(selectedType)
                if (currentType != TtsProviderType.NONE) {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        color = LcarsGreen,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            item {
                val currentType = TtsProviderType.fromId(selectedType)
                AnimatedVisibility(visible = currentType != TtsProviderType.NONE) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            when (currentType) {
                                TtsProviderType.KOKORO -> {
                                    OutlinedTextField(
                                        value = providerUrl,
                                        onValueChange = { providerUrl = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Server URL") },
                                        placeholder = { Text("https://your-domain.org:9199") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = voiceId,
                                        onValueChange = { voiceId = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Voice") },
                                        placeholder = { Text("af_heart") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Enter the URL of your Kokoro/LuxTTS server",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TtsProviderType.ELEVENLABS -> {
                                    OutlinedTextField(
                                        value = apiKey,
                                        onValueChange = { apiKey = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("API Key") },
                                        placeholder = { Text("sk-...") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation()
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = voiceId,
                                        onValueChange = { voiceId = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Voice ID") },
                                        placeholder = { Text("21m00Tcm4TlvDq8ikWAM") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = model,
                                        onValueChange = { model = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Model") },
                                        placeholder = { Text("eleven_multilingual_v2") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Requires an ElevenLabs API key from elevenlabs.io",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TtsProviderType.OPENAI_TTS -> {
                                    OutlinedTextField(
                                        value = apiKey,
                                        onValueChange = { apiKey = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("API Key") },
                                        placeholder = { Text("sk-...") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation()
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = voiceId,
                                        onValueChange = { voiceId = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Voice") },
                                        placeholder = { Text("alloy") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = model,
                                        onValueChange = { model = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Model") },
                                        placeholder = { Text("tts-1") },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Requires an OpenAI API key from platform.openai.com",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TtsProviderType.ANDROID_TTS -> {
                                    Text(
                                        text = "Uses the built-in Android text-to-speech engine. " +
                                            "Voice selection is managed by your device's TTS settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LcarsSubduedWarm
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Go to Settings > Accessibility > Text-to-Speech to configure voices",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TtsProviderType.NONE -> {
                                    // No config needed
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            // --- Speech Rate ---
            item {
                Text(
                    text = "Speech Rate",
                    style = MaterialTheme.typography.titleSmall,
                    color = LcarsGreen,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Speed: ${String.format("%.2f", rate)}x",
                            style = MaterialTheme.typography.bodySmall,
                            color = LcarsSubduedWarm
                        )
                        Slider(
                            value = rate,
                            onValueChange = { rate = it },
                            valueRange = 0.5f..2.0f,
                            steps = 29,
                            colors = SliderDefaults.colors(
                                thumbColor = LcarsOrange,
                                activeTrackColor = LcarsOrange
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "0.5x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "1.0x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "2.0x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            // --- Action Buttons ---
            item {
                // Test button
                Button(
                    onClick = {
                        if (isTesting) {
                            viewModel.stopTest()
                        } else {
                            // Push local edits to ViewModel before testing
                            viewModel.setProviderType(selectedType)
                            viewModel.setProviderUrl(providerUrl)
                            viewModel.setApiKey(apiKey)
                            viewModel.setVoiceId(voiceId)
                            viewModel.setModel(model)
                            viewModel.setRate(rate)
                            viewModel.testVoice()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LcarsBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Test")
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
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                // Apply / Save button
                Button(
                    onClick = {
                        viewModel.setProviderType(selectedType)
                        viewModel.setProviderUrl(providerUrl)
                        viewModel.setApiKey(apiKey)
                        viewModel.setVoiceId(voiceId)
                        viewModel.setModel(model)
                        viewModel.setRate(rate)
                        viewModel.applySettings()
                        Toast.makeText(context, "TTS settings applied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LcarsOrange),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Apply & Switch Provider")
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Text(
                    text = "Changes are saved immediately. Apply switches the active TTS provider.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun providerDescription(type: TtsProviderType): String = when (type) {
    TtsProviderType.KOKORO -> "Self-hosted Kokoro/LuxTTS server"
    TtsProviderType.ELEVENLABS -> "Cloud API - high quality voices"
    TtsProviderType.OPENAI_TTS -> "Cloud API - natural sounding voices"
    TtsProviderType.ANDROID_TTS -> "Built-in device TTS engine"
    TtsProviderType.NONE -> "TTS disabled"
}
