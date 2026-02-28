package com.mobilekinetic.agent.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm
import com.mobilekinetic.agent.ui.viewmodel.BackupSettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // NAS state
    val nasEnabled by viewModel.smbBackupEnabled.collectAsState()
    val nasAddress by viewModel.smbHost.collectAsState()
    val nasUsername by viewModel.smbUsername.collectAsState()
    val nasPassword by viewModel.smbPassword.collectAsState()
    val nasPath by viewModel.smbPath.collectAsState()
    val nasConnected by viewModel.nasConnected.collectAsState()
    val lastNasTime by viewModel.lastSmbBackupTime.collectAsState()
    val lastNasResult by viewModel.lastSmbBackupResult.collectAsState()
    val nasTestResult by viewModel.smbTestResult.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("BACKUP", color = LcarsOrange) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

            // ── Local Backup ────────────────────────────────────────────────
            item { BackupSectionHeader("On-Device Backup") }
            item {
                InfoCard(
                    icon = Icons.Default.Storage,
                    title = "Automatic Rolling Backup",
                    subtitle = "Home directory archived every 6 hours (4 kept). " +
                            "Database backed up every 3 hours (8 kept)."
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── NAS Backup ──────────────────────────────────────────────────
            item { BackupSectionHeader("NAS Backup") }
            item {
                ToggleCard(
                    icon = Icons.Default.Lan,
                    title = "NAS Backup",
                    subtitle = "Upload backups to your NAS after each local archive",
                    checked = nasEnabled,
                    onCheckedChange = { viewModel.setSmbBackupEnabled(it) }
                )
            }
            item {
                NasConfigCard(
                    address = nasAddress,
                    username = nasUsername,
                    password = nasPassword,
                    enabled = nasEnabled,
                    onAddressChange = { viewModel.setSmbHost(it) },
                    onUsernameChange = { viewModel.setSmbUsername(it) },
                    onPasswordChange = { viewModel.setSmbPassword(it) }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.testSmbConnection() },
                        enabled = nasEnabled && nasAddress.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LcarsOrange)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection")
                    }
                    Button(
                        onClick = {
                            viewModel.triggerNasBackupNow()
                            Toast.makeText(context, "NAS backup started", Toast.LENGTH_SHORT).show()
                        },
                        enabled = nasEnabled && nasAddress.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = LcarsBlue)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Backup Now")
                    }
                }
            }
            if (nasTestResult.isNotBlank()) {
                item {
                    Text(
                        text = nasTestResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (nasTestResult.startsWith("SUCCESS")) LcarsGreen
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // Show backup folder field after successful connection
            if (nasConnected) {
                item {
                    NasFolderCard(
                        path = nasPath,
                        onPathChange = { viewModel.setSmbPath(it) }
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Status ──────────────────────────────────────────────────────
            item { BackupSectionHeader("Status") }
            item {
                StatusCard(
                    title = "Last NAS Backup",
                    lastBackupTime = lastNasTime,
                    lastBackupResult = lastNasResult
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────────────────

@Composable
private fun BackupSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = LcarsGreen,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = LcarsSubduedCool)
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LcarsSubduedWarm)
            }
        }
    }
}

@Composable
private fun ToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = LcarsSubduedCool)
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LcarsSubduedWarm)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LcarsSubduedWarm,
                    checkedTrackColor = LcarsSubduedWarm.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun NasConfigCard(
    address: String,
    username: String,
    password: String,
    enabled: Boolean,
    onAddressChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LcarsOrange,
        unfocusedBorderColor = LcarsSubduedCool.copy(alpha = 0.5f),
        focusedLabelColor = LcarsOrange,
        unfocusedLabelColor = LcarsSubduedCool,
        cursorColor = LcarsOrange,
        disabledBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                label = { Text("NAS Address") },
                placeholder = { Text("e.g. 192.168.1.100") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                enabled = enabled,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
        }
    }
}

@Composable
private fun NasFolderCard(
    path: String,
    onPathChange: (String) -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LcarsOrange,
        unfocusedBorderColor = LcarsSubduedCool.copy(alpha = 0.5f),
        focusedLabelColor = LcarsOrange,
        unfocusedLabelColor = LcarsSubduedCool,
        cursorColor = LcarsOrange
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text(
                text = "Connected -- choose your backup folder:",
                style = MaterialTheme.typography.bodySmall,
                color = LcarsGreen,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("Backup Folder") },
                placeholder = { Text("e.g. mK:a") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, lastBackupTime: Long, lastBackupResult: String) {
    val timeText = if (lastBackupTime > 0L) {
        SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US).format(Date(lastBackupTime))
    } else {
        "Never"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LcarsSubduedCool
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = LcarsSubduedWarm
                )
                if (lastBackupResult.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lastBackupResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastBackupResult.startsWith("SUCCESS"))
                                    LcarsGreen
                                else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
