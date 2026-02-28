package com.mobilekinetic.agent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsContainerGray
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm
import com.mobilekinetic.agent.ui.theme.LcarsTextBody
import com.mobilekinetic.agent.ui.theme.LcarsTextPrimary
import com.mobilekinetic.agent.ui.viewmodel.SetupWizardViewModel
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 6

/**
 * First-run setup wizard -- 6 pages collected via HorizontalPager.
 *
 * Page 0: Welcome
 * Page 1: Identity (user name, device name)
 * Page 2: AI Provider (API key, model selection)
 * Page 3: Voice / TTS (server host, port, WSS URL) -- optional
 * Page 4: Integrations (HA, NAS, Switchboard) -- optional
 * Page 5: Review & Finish
 */
@Composable
fun FirstRunWizardScreen(
    viewModel: SetupWizardViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Progress indicator ───────────────────────────────────────────────
        Spacer(modifier = Modifier.height(24.dp))
        PageIndicator(
            pageCount = PAGE_COUNT,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Pager ────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> IdentityPage(viewModel)
                2 -> AiProviderPage(viewModel)
                3 -> VoicePage(viewModel)
                4 -> IntegrationsPage(viewModel)
                5 -> ReviewPage(viewModel)
            }
        }

        // ── Navigation buttons ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button (hidden on first page)
            if (pagerState.currentPage > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LcarsSubduedCool)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back")
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            // Skip button (pages 3 and 4 are optional)
            if (pagerState.currentPage == 3 || pagerState.currentPage == 4) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                ) {
                    Text("Skip", color = LcarsSubduedWarm)
                }
            }

            // Next / Finish button
            if (pagerState.currentPage < PAGE_COUNT - 1) {
                Button(
                    onClick = {
                        viewModel.savePageData(pagerState.currentPage)
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LcarsOrange)
                ) {
                    Text("Next", color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            } else {
                Button(
                    onClick = {
                        viewModel.completeSetup(onSetupComplete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LcarsGreen)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Finish", color = MaterialTheme.colorScheme.background)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ─── Page Indicator ──────────────────────────────────────────────────────────

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) LcarsOrange
                        else if (index < currentPage) LcarsGreen
                        else LcarsSubduedCool.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

// ─── Page 0: Welcome ─────────────────────────────────────────────────────────

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "mK:a",
            style = MaterialTheme.typography.displayLarge,
            color = LcarsOrange,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "mobileKinetic:Agent",
            style = MaterialTheme.typography.headlineSmall,
            color = LcarsBlue
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Your AI assistant, always with you.",
            style = MaterialTheme.typography.bodyLarge,
            color = LcarsTextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This wizard will help you configure the essential settings to get started. You can change any of these later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ─── Page 1: Identity ────────────────────────────────────────────────────────

@Composable
private fun IdentityPage(viewModel: SetupWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardSectionTitle("Identity")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "How should the assistant address you?",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody
        )
        Spacer(modifier = Modifier.height(24.dp))

        WizardTextField(
            value = viewModel.userName,
            onValueChange = { viewModel.userName = it },
            label = "Your Name",
            placeholder = "e.g. Alex"
        )
        Spacer(modifier = Modifier.height(16.dp))

        WizardTextField(
            value = viewModel.deviceName,
            onValueChange = { viewModel.deviceName = it },
            label = "Device Nickname",
            placeholder = "e.g. Pixel Fold"
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "The device name helps identify this device on your network.",
            style = MaterialTheme.typography.bodySmall,
            color = LcarsSubduedWarm
        )
    }
}

// ─── Page 2: AI Provider ─────────────────────────────────────────────────────

@Composable
private fun AiProviderPage(viewModel: SetupWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardSectionTitle("AI Provider")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configure your Anthropic API access.",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody
        )
        Spacer(modifier = Modifier.height(24.dp))

        // API Key
        OutlinedTextField(
            value = viewModel.apiKey,
            onValueChange = { viewModel.apiKey = it },
            label = { Text("Anthropic API Key") },
            placeholder = { Text("sk-ant-...") },
            visualTransformation = if (viewModel.apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { viewModel.apiKeyVisible = !viewModel.apiKeyVisible }) {
                    Icon(
                        imageVector = if (viewModel.apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (viewModel.apiKeyVisible) "Hide" else "Show"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = wizardTextFieldColors()
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = LcarsSubduedCool.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(16.dp))

        // Model Selection
        Text(
            text = "Select Model",
            style = MaterialTheme.typography.titleSmall,
            color = LcarsOrange,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        ModelOption(
            name = "claude-haiku-4-5",
            description = "Fast, efficient -- ideal for quick tasks",
            selected = viewModel.selectedModel == "haiku",
            onClick = { viewModel.selectedModel = "haiku" }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelOption(
            name = "claude-sonnet-4-5",
            description = "Balanced performance -- recommended",
            selected = viewModel.selectedModel == "sonnet",
            onClick = { viewModel.selectedModel = "sonnet" }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelOption(
            name = "claude-opus-4-5",
            description = "Most capable -- complex reasoning",
            selected = viewModel.selectedModel == "opus",
            onClick = { viewModel.selectedModel = "opus" }
        )
    }
}

@Composable
private fun ModelOption(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LcarsOrange.copy(alpha = 0.12f) else LcarsContainerGray
        ),
        shape = RoundedCornerShape(8.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = LcarsOrange)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) LcarsOrange else LcarsTextPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LcarsSubduedWarm
                )
            }
        }
    }
}

// ─── Page 3: Voice / TTS ─────────────────────────────────────────────────────

@Composable
private fun VoicePage(viewModel: SetupWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardSectionTitle("Voice Settings")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Optional",
            style = MaterialTheme.typography.labelMedium,
            color = LcarsSubduedWarm
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configure text-to-speech for spoken responses. You can skip this and set it up later.",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody
        )
        Spacer(modifier = Modifier.height(24.dp))

        WizardTextField(
            value = viewModel.ttsServerHost,
            onValueChange = { viewModel.ttsServerHost = it },
            label = "TTS Server Host",
            placeholder = "https://example.com"
        )
        Spacer(modifier = Modifier.height(16.dp))

        WizardTextField(
            value = viewModel.ttsServerPort,
            onValueChange = { viewModel.ttsServerPort = it },
            label = "TTS Server Port",
            placeholder = "9199",
            keyboardType = KeyboardType.Number
        )
        Spacer(modifier = Modifier.height(16.dp))

        WizardTextField(
            value = viewModel.ttsWssUrl,
            onValueChange = { viewModel.ttsWssUrl = it },
            label = "TTS WebSocket URL (optional)",
            placeholder = "wss://..."
        )
    }
}

// ─── Page 4: Integrations ────────────────────────────────────────────────────

@Composable
private fun IntegrationsPage(viewModel: SetupWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardSectionTitle("Integrations")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Optional",
            style = MaterialTheme.typography.labelMedium,
            color = LcarsSubduedWarm
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Connect to home automation, network storage, and other services. All optional -- skip if you don't use these.",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody
        )
        Spacer(modifier = Modifier.height(24.dp))

        WizardTextField(
            value = viewModel.haServerUrl,
            onValueChange = { viewModel.haServerUrl = it },
            label = "Home Assistant URL",
            placeholder = "http://homeassistant.local:8123"
        )
        Spacer(modifier = Modifier.height(16.dp))

        WizardTextField(
            value = viewModel.nasIp,
            onValueChange = { viewModel.nasIp = it },
            label = "NAS IP Address",
            placeholder = "192.168.1.100"
        )
        Spacer(modifier = Modifier.height(16.dp))

        WizardTextField(
            value = viewModel.switchboardUrl,
            onValueChange = { viewModel.switchboardUrl = it },
            label = "Switchboard URL",
            placeholder = "http://your-switchboard-ip:5559"
        )
    }
}

// ─── Page 5: Review & Finish ─────────────────────────────────────────────────

@Composable
private fun ReviewPage(viewModel: SetupWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = LcarsGreen,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Ready to Go",
                style = MaterialTheme.typography.headlineMedium,
                color = LcarsGreen,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Review your configuration:",
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextBody
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LcarsContainerGray),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SummaryRow("Name", viewModel.userName.ifBlank { "(not set)" })
                SummaryRow("Device", viewModel.deviceName.ifBlank { "(not set)" })
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = LcarsSubduedCool.copy(alpha = 0.2f)
                )
                SummaryRow("Model", viewModel.modelDisplayName)
                SummaryRow("API Key", viewModel.maskedApiKey)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = LcarsSubduedCool.copy(alpha = 0.2f)
                )
                SummaryRow("TTS", if (viewModel.ttsConfigured) viewModel.ttsServerHost else "Not configured")
                SummaryRow("Integrations", "${viewModel.integrationsConfigured} configured")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "You can change any of these settings later in the Settings tab.",
            style = MaterialTheme.typography.bodySmall,
            color = LcarsSubduedWarm,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsSubduedCool,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = LcarsTextPrimary
        )
    }
}

// ─── Shared Components ───────────────────────────────────────────────────────

@Composable
private fun WizardSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = LcarsOrange,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun WizardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = wizardTextFieldColors()
    )
}

@Composable
private fun wizardTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LcarsOrange,
    unfocusedBorderColor = LcarsSubduedCool.copy(alpha = 0.4f),
    focusedLabelColor = LcarsOrange,
    unfocusedLabelColor = LcarsSubduedCool,
    cursorColor = LcarsOrange,
    focusedTextColor = LcarsTextPrimary,
    unfocusedTextColor = LcarsTextPrimary,
    focusedPlaceholderColor = LcarsSubduedWarm.copy(alpha = 0.5f),
    unfocusedPlaceholderColor = LcarsSubduedWarm.copy(alpha = 0.3f)
)
