package com.mobilekinetic.agent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mobilekinetic.agent.ui.navigation.Destination
import com.mobilekinetic.agent.ui.theme.MyriadProCond
import com.mobilekinetic.agent.ui.screens.BlacklistScreen
import com.mobilekinetic.agent.ui.screens.ChatScreen
import com.mobilekinetic.agent.ui.screens.BackupSettingsScreen
import com.mobilekinetic.agent.ui.screens.GemmaCredentialsScreen
import com.mobilekinetic.agent.ui.screens.MemoryInjectionSettingsScreen
import com.mobilekinetic.agent.ui.screens.SettingsScreen
import com.mobilekinetic.agent.ui.screens.ToolsScreen
import com.mobilekinetic.agent.ui.settings.ProviderSettingsScreen
import com.mobilekinetic.agent.ui.settings.TtsSettingsScreen
import com.mobilekinetic.agent.ui.viewmodel.ChatViewModel

/**
 * Main app composable with adaptive navigation.
 *
 * Uses NavigationSuiteScaffold which automatically switches between:
 * - Bottom Navigation Bar (compact/phone)
 * - Navigation Rail (medium/foldable)
 * - Permanent Navigation Drawer (expanded/tablet)
 *
 * TerminalScreen is accessed via the Terminal tab in bottom nav.
 * SetupScreen is handled separately in MainActivity before this is shown.
 */
@Composable
fun MobileKineticApp(
    terminalContent: @Composable () -> Unit,
    onStopClaude: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedDestination by remember { mutableStateOf(Destination.Chat) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showBlacklistScreen by remember { mutableStateOf(false) }
    var showMemoryInjectionScreen by remember { mutableStateOf(false) }
    var showBackupSettingsScreen by remember { mutableStateOf(false) }
    var showGemmaCredentialsScreen by remember { mutableStateOf(false) }
    var showTtsSettingsScreen by remember { mutableStateOf(false) }
    var showProviderSettingsScreen by remember { mutableStateOf(false) }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Interrupt Claude") },
            text = { Text("This will interrupt the current Claude action. Use Settings to fully kill processes.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    onStopClaude()
                }) {
                    Text("Interrupt")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    NavigationSuiteScaffold(
        modifier = modifier.fillMaxSize(),
        navigationSuiteItems = {
            Destination.entries.forEach { destination ->
                item(
                    selected = selectedDestination == destination,
                    onClick = { selectedDestination = destination },
                    icon = {
                        Icon(
                            imageVector = if (selectedDestination == destination) {
                                destination.selectedIcon
                            } else {
                                destination.unselectedIcon
                            },
                            contentDescription = destination.contentDescription
                        )
                    },
                    label = { Text(destination.label, style = TextStyle(fontFamily = MyriadProCond, fontSize = 13.sp)) }
                )
            }
            item(
                selected = false,
                onClick = { showStopDialog = true },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.StopCircle,
                        contentDescription = "Stop Claude"
                    )
                },
                label = { Text("Stop", style = TextStyle(fontFamily = MyriadProCond, fontSize = 13.sp)) }
            )
        }
    ) {
        if (showBlacklistScreen) {
            BlacklistScreen(onBack = { showBlacklistScreen = false })
        } else if (showMemoryInjectionScreen) {
            MemoryInjectionSettingsScreen(onBack = { showMemoryInjectionScreen = false })
        } else if (showBackupSettingsScreen) {
            BackupSettingsScreen(onBack = { showBackupSettingsScreen = false })
        } else if (showGemmaCredentialsScreen) {
            GemmaCredentialsScreen(onBack = { showGemmaCredentialsScreen = false })
        } else if (showTtsSettingsScreen) {
            TtsSettingsScreen(onBack = { showTtsSettingsScreen = false })
        } else if (showProviderSettingsScreen) {
            ProviderSettingsScreen(onBack = { showProviderSettingsScreen = false })
        } else {
            when (selectedDestination) {
                Destination.Chat -> ChatScreen()
                Destination.Terminal -> terminalContent()
                Destination.Tools -> ToolsScreen(
                    onToolSelected = { tool ->
                        val msg = buildString {
                            append("[TOOL] ${tool.technicalName}\n")
                            append("Description: ${tool.description}\n")
                            append("Category: ${tool.category} | Source: ${tool.source}\n")
                            append("Schema: ${tool.schemaJson}")
                        }
                        ChatViewModel.queuePendingMessage(msg)
                        selectedDestination = Destination.Chat
                    }
                )
                Destination.Settings -> SettingsScreen(
                    onNavigateToVault = { showGemmaCredentialsScreen = true },
                    onNavigateToBlacklist = { showBlacklistScreen = true },
                    onNavigateToMemoryInjection = { showMemoryInjectionScreen = true },
                    onNavigateToBackupSettings = { showBackupSettingsScreen = true },
                    onNavigateToTtsSettings = { showTtsSettingsScreen = true },
                    onNavigateToProviderSettings = { showProviderSettingsScreen = true }
                )
            }
        }
    }
}
