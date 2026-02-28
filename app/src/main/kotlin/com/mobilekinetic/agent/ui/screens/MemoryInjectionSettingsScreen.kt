package com.mobilekinetic.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm
import com.mobilekinetic.agent.ui.viewmodel.InjectionSettingsViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryInjectionSettingsScreen(
    onBack: () -> Unit,
    viewModel: InjectionSettingsViewModel = hiltViewModel()
) {
    // Collect all settings as state
    val contextInjectionEnabled by viewModel.contextInjectionEnabled.collectAsState()
    val tier1Enabled by viewModel.tier1Enabled.collectAsState()
    val tier2Enabled by viewModel.tier2Enabled.collectAsState()
    val napkinDabEnabled by viewModel.napkinDabEnabled.collectAsState()
    val napkinDabThreshold by viewModel.napkinDabThreshold.collectAsState()
    val backupEnabled by viewModel.backupEnabled.collectAsState()
    val haikuClassificationEnabled by viewModel.haikuClassificationEnabled.collectAsState()
    val autoLearnEnabled by viewModel.autoLearnEnabled.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("CONTEXT INJECTION", color = LcarsOrange) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
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

            // ── Context Injection Section ───────────────────────────────────
            item { InjectionSectionHeader("Context Injection") }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.Memory,
                    title = "Context Injection",
                    subtitle = "Master toggle for all memory injection into prompts",
                    checked = contextInjectionEnabled,
                    onCheckedChange = { viewModel.toggleContextInjection(it) }
                )
            }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.Storage,
                    title = "Tier 1 - RAG Context",
                    subtitle = "Inject relevant RAG documents into conversation context",
                    checked = tier1Enabled,
                    enabled = contextInjectionEnabled,
                    onCheckedChange = { viewModel.toggleTier1(it) }
                )
            }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.Psychology,
                    title = "Tier 2 - Semantic Triggers",
                    subtitle = "Activate semantic similarity matching for context enrichment",
                    checked = tier2Enabled,
                    enabled = contextInjectionEnabled,
                    onCheckedChange = { viewModel.toggleTier2(it) }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Maintenance Section ─────────────────────────────────────────
            item { InjectionSectionHeader("Maintenance") }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.CleaningServices,
                    title = "NapkinDab Cleanup",
                    subtitle = "Periodically compress and clean session context",
                    checked = napkinDabEnabled,
                    onCheckedChange = { viewModel.toggleNapkinDab(it) }
                )
            }
            item {
                InjectionSliderCard(
                    icon = Icons.Default.Tune,
                    title = "NapkinDab Threshold",
                    subtitle = "Exchanges between cleanup passes",
                    value = napkinDabThreshold.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    enabled = napkinDabEnabled,
                    valueLabel = "$napkinDabThreshold exchanges",
                    onValueChange = { viewModel.updateNapkinDabThreshold(it.roundToInt()) }
                )
            }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.Backup,
                    title = "Context Backup",
                    subtitle = "Backup session memory state for recovery",
                    checked = backupEnabled,
                    onCheckedChange = { viewModel.toggleBackup(it) }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Classification Section ──────────────────────────────────────
            item { InjectionSectionHeader("Classification") }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.Category,
                    title = "Haiku Classification",
                    subtitle = "Use Haiku model for intent classification before injection",
                    checked = haikuClassificationEnabled,
                    onCheckedChange = { viewModel.toggleHaikuClassification(it) }
                )
            }
            item {
                InjectionToggleCard(
                    icon = Icons.Default.School,
                    title = "Auto-Learn (TriggerLearner)",
                    subtitle = "Automatically learn new semantic triggers from conversation",
                    checked = autoLearnEnabled,
                    onCheckedChange = { viewModel.toggleAutoLearn(it) }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Private Helper Composables ─────────────────────────────────────────────

@Composable
private fun InjectionSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = LcarsGreen,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun InjectionToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) LcarsSubduedCool
                            else LcarsSubduedCool.copy(alpha = 0.38f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) LcarsSubduedWarm
                            else LcarsSubduedWarm.copy(alpha = 0.38f)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LcarsOrange,
                    checkedTrackColor = LcarsOrange.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun InjectionSliderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    valueLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) LcarsSubduedCool
                                else LcarsSubduedCool.copy(alpha = 0.38f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) LcarsSubduedWarm
                                else LcarsSubduedWarm.copy(alpha = 0.38f)
                    )
                }
                if (valueLabel != null) {
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = LcarsOrange
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = LcarsOrange,
                    activeTrackColor = LcarsOrange
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
    }
}
