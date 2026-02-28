package com.mobilekinetic.agent.ui.screens

import android.app.Activity
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.db.entity.CredentialEntity
import com.mobilekinetic.agent.ui.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(viewModel: VaultViewModel = hiltViewModel(), onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    LaunchedEffect(uiState.error) { uiState.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Secure Vault") }, navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.onAddCredential() }) { Icon(Icons.Default.Add, "Add") } },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.credentials.isEmpty() -> Text("No credentials stored. Tap + to add one.", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.credentials, key = { it.id }) { credential ->
                        CredentialCard(credential, uiState.selectedCredential?.id == credential.id, if (uiState.selectedCredential?.id == credential.id) uiState.decryptedPassword else null,
                            onReveal = { activity?.let { viewModel.revealPassword(it, credential) } },
                            onHide = { viewModel.hidePassword() },
                            onEdit = { viewModel.onEditCredential(credential) },
                            onDelete = { viewModel.deleteCredential(credential.id) })
                    }
                }
            }
        }
    }
    if (uiState.showAddDialog) { AddEditDialog(false, "", "", { viewModel.onDismissDialog() }) { s, u, p -> activity?.let { viewModel.saveCredential(it, s, u, p) } } }
    if (uiState.showEditDialog && uiState.selectedCredential != null) { AddEditDialog(true, uiState.selectedCredential!!.name, uiState.selectedCredential!!.category, { viewModel.onDismissDialog() }) { s, u, p -> activity?.let { viewModel.updateCredential(it, uiState.selectedCredential!!.id, s, u, p) } } }
}

@Composable
private fun CredentialCard(credential: CredentialEntity, isRevealed: Boolean, decryptedPwd: String?, onReveal: () -> Unit, onHide: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(credential.name, style = MaterialTheme.typography.titleMedium)
                    Text(credential.category, style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    IconButton(onClick = if (isRevealed) onHide else onReveal) { Icon(if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (isRevealed) "Hide" else "Reveal") }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
            }
            if (isRevealed && decryptedPwd != null) { Spacer(Modifier.height(8.dp)); OutlinedTextField(decryptedPwd, {}, label = { Text("Password") }, readOnly = true, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun AddEditDialog(isEdit: Boolean, initName: String, initUser: String, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var serviceName by rememberSaveable { mutableStateOf(initName) }
    var username by rememberSaveable { mutableStateOf(initUser) }
    var password by rememberSaveable { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isEdit) "Edit Credential" else "Add Credential") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(serviceName, { serviceName = it }, label = { Text("Service Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { pwdVisible = !pwdVisible }) { Icon(if (pwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }, modifier = Modifier.fillMaxWidth())
        }},
        confirmButton = { TextButton(onClick = { if (serviceName.isNotBlank() && username.isNotBlank() && password.isNotBlank()) onSave(serviceName, username, password) }, enabled = serviceName.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
