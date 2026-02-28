package com.mobilekinetic.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.db.entity.BlacklistRuleEntity
import com.mobilekinetic.agent.ui.viewmodel.PrivacySettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: PrivacySettingsViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Blacklist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Rule")
                    }
                }
            )
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No privacy rules configured", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { viewModel.toggleRule(rule.id, !rule.isEnabled) },
                        onDelete = { viewModel.deleteRule(rule.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { type, value, action, desc ->
                viewModel.addRule(type, value, action, desc)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: BlacklistRuleEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${rule.ruleType}: ${rule.value}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Action: ${rule.action}${rule.description?.let { " — $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = rule.isEnabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (type: String, value: String, action: String, description: String?) -> Unit
) {
    val types = listOf("KEYWORD", "TOPIC", "SENDER", "APP_PACKAGE", "PATTERN")
    val actions = listOf("REDACT", "BLOCK")
    var selectedType by remember { mutableStateOf(types[0]) }
    var selectedAction by remember { mutableStateOf(actions[0]) }
    var value by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Privacy Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = selectedType, onValueChange = {},
                        readOnly = true, label = { Text("Rule Type") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        types.forEach { type ->
                            DropdownMenuItem(text = { Text(type) }, onClick = {
                                selectedType = type; typeExpanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") })
                ExposedDropdownMenuBox(expanded = actionExpanded, onExpandedChange = { actionExpanded = it }) {
                    OutlinedTextField(
                        value = selectedAction, onValueChange = {},
                        readOnly = true, label = { Text("Action") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }) {
                        actions.forEach { action ->
                            DropdownMenuItem(text = { Text(action) }, onClick = {
                                selectedAction = action; actionExpanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selectedType, value, selectedAction, description.ifBlank { null }) },
                enabled = value.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
