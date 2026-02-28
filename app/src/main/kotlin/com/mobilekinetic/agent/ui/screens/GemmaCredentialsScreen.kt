package com.mobilekinetic.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.db.entity.VaultEntryEntity
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.viewmodel.GemmaCredentialsViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mobilekinetic.agent.data.vault.CatalogMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaCredentialsScreen(
    viewModel: GemmaCredentialsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemma Credentials", color = LcarsOrange) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, "Add Credential")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Instructions card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "How Credentials Work",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LcarsOrange
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add your API keys, tokens, and passwords here. " +
                            "Gemma uses these to make authenticated requests " +
                            "on your behalf \u2014 Claude never sees the values.\n\n" +
                            "Each credential needs:\n" +
                            "\u2022 A name (e.g. ha_token, github_pat)\n" +
                            "\u2022 The secret value\n" +
                            "\u2022 How to inject it (Bearer header, API key, etc.)\n" +
                            "\u2022 The service URL it authenticates against\n" +
                            "\u2022 Allowed contexts (tags Gemma uses to verify requests)\n" +
                            "\u2022 A usage hint telling Claude when to use it\n\n" +
                            "You\u2019ll authenticate with your fingerprint to add, " +
                            "modify, or delete credentials. The vault auto-locks " +
                            "after 5 minutes of inactivity.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Vault status card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isUnlocked)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (uiState.isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (uiState.isUnlocked)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    if (uiState.isUnlocked) "Vault Unlocked" else "Vault Locked",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (uiState.isUnlocked && uiState.remainingSeconds > 0) {
                                    val mins = uiState.remainingSeconds / 60
                                    val secs = uiState.remainingSeconds % 60
                                    Text(
                                        "Auto-locks in ${mins}:${"%02d".format(secs)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (uiState.isUnlocked) {
                                    viewModel.lockVault()
                                } else {
                                    activity?.let { viewModel.unlockVault(it) }
                                }
                            }
                        ) {
                            Text(if (uiState.isUnlocked) "Lock" else "Unlock")
                        }
                    }
                }
            }

            // Credential list
            if (uiState.credentials.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        "No credentials stored. Tap + to add one.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.credentials, key = { it.id }) { credential ->
                    GemmaCredentialCard(
                        credential = credential,
                        onDelete = {
                            activity?.let { viewModel.deleteCredential(it, credential.name) }
                        }
                    )
                }
            }
        }
    }

    // Add credential dialog
    if (uiState.showAddDialog) {
        AddGemmaCredentialDialog(
            onDismiss = { viewModel.dismissDialog() },
            onSave = { name, value, description ->
                activity?.let { viewModel.addCredential(it, name, value, description) }
            }
        )
    }
}

@Composable
private fun GemmaCredentialCard(
    credential: VaultEntryEntity,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val meta = remember(credential.description) { CatalogMeta.parse(credential.description) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    credential.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (meta.desc.isNotBlank()) {
                    Text(
                        meta.desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(
                            when (meta.inject) {
                                "bearer_header" -> "Bearer"
                                "basic_auth" -> "Basic Auth"
                                else -> meta.inject.removePrefix("header:")
                            },
                            style = MaterialTheme.typography.labelSmall
                        ) }
                    )
                    if (meta.service.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label = { Text(
                                meta.service.take(30) + if (meta.service.length > 30) "..." else "",
                                style = MaterialTheme.typography.labelSmall
                            ) }
                        )
                    }
                }
                Text(
                    "Updated ${dateFormat.format(Date(credential.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddGemmaCredentialDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, description: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var serviceUrl by rememberSaveable { mutableStateOf("") }
    var contexts by rememberSaveable { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf("") }
    var injectType by rememberSaveable { mutableStateOf("bearer_header") }
    val injectOptions = listOf("bearer_header", "header:X-API-Key", "basic_auth")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Credential") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Credential Name") },
                    placeholder = { Text("e.g. ha_token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Secret Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Home Assistant long-lived token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Injection type
                Text("Injection Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    injectOptions.forEach { option ->
                        FilterChip(
                            selected = injectType == option,
                            onClick = { injectType = option },
                            label = {
                                Text(
                                    when (option) {
                                        "bearer_header" -> "Bearer"
                                        "basic_auth" -> "Basic Auth"
                                        else -> "API Key"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = serviceUrl,
                    onValueChange = { serviceUrl = it },
                    label = { Text("Service URL") },
                    placeholder = { Text("e.g. http://192.168.1.x:8123/api/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contexts,
                    onValueChange = { contexts = it },
                    label = { Text("Allowed Contexts") },
                    placeholder = { Text("e.g. ha_mcp_tool, home_automation") },
                    supportingText = { Text("Comma-separated tags Gemma uses to verify requests") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text("Usage Hint for Claude") },
                    placeholder = { Text("e.g. Use for all HA API calls") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val contextList = contexts.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    val meta = CatalogMeta(
                        desc = desc.trim(),
                        inject = injectType,
                        service = serviceUrl.trim(),
                        contexts = contextList,
                        hint = hint.trim()
                    )
                    onSave(name.trim(), value, CatalogMeta.toJson(meta))
                },
                enabled = name.isNotBlank() && value.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
