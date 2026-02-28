package com.mobilekinetic.agent.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.provider.FieldType
import com.mobilekinetic.agent.provider.ProviderConfigField
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsSubduedCool
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProviderSettingsViewModel = hiltViewModel()
) {
    val providers by viewModel.providers.collectAsState()
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val activeProviderId by viewModel.activeProviderId.collectAsState()
    val configValues by viewModel.configValues.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Show status message as toast
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatus()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("AI PROVIDERS", color = LcarsOrange) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Section: Available Providers
            item {
                Text(
                    text = "Available Providers",
                    style = MaterialTheme.typography.titleSmall,
                    color = LcarsGreen,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Provider selection cards
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    isSelected = provider.id == selectedProviderId,
                    isActive = provider.id == activeProviderId,
                    onClick = { viewModel.selectProvider(provider.id) }
                )
            }

            // Configuration section (shown when a provider is selected)
            val selectedProvider = providers.find { it.id == selectedProviderId }
            if (selectedProvider != null && selectedProvider.configFields.isNotEmpty()) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        color = LcarsGreen,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ConfigurationCard(
                        fields = selectedProvider.configFields,
                        values = configValues,
                        onValueChange = { key, value ->
                            viewModel.updateConfigValue(key, value)
                        }
                    )
                }
            }

            // Save & Activate button
            if (selectedProviderId != null) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    Button(
                        onClick = {
                            selectedProviderId?.let { viewModel.saveAndActivate(it) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedProviderId != activeProviderId ||
                                configValues.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LcarsBlue
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (selectedProviderId == activeProviderId)
                                "Save Configuration"
                            else
                                "Save & Activate"
                        )
                    }
                }

                item {
                    Text(
                        text = if (selectedProviderId == activeProviderId)
                            "This provider is currently active. Changes will be saved."
                        else
                            "Saves configuration and switches to this provider.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderSettingsViewModel.ProviderInfo,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(1.dp, LcarsOrange)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = LcarsOrange
                )
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LcarsSubduedCool
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = provider.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = LcarsSubduedWarm
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LcarsGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = LcarsGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurationCard(
    fields: List<ProviderConfigField>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            fields.forEachIndexed { index, field ->
                if (index > 0) Spacer(Modifier.height(12.dp))

                val currentValue = values[field.key] ?: field.defaultValue

                when (field.type) {
                    FieldType.TEXT -> {
                        OutlinedTextField(
                            value = currentValue,
                            onValueChange = { onValueChange(field.key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(field.label) },
                            placeholder = { Text(field.placeholder) },
                            singleLine = true,
                            isError = field.required && currentValue.isBlank()
                        )
                    }

                    FieldType.PASSWORD -> {
                        PasswordField(
                            value = currentValue,
                            onValueChange = { onValueChange(field.key, it) },
                            label = field.label,
                            placeholder = field.placeholder,
                            isRequired = field.required
                        )
                    }

                    FieldType.URL -> {
                        OutlinedTextField(
                            value = currentValue,
                            onValueChange = { onValueChange(field.key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(field.label) },
                            placeholder = { Text(field.placeholder) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri
                            ),
                            isError = field.required && currentValue.isBlank()
                        )
                    }

                    FieldType.NUMBER -> {
                        OutlinedTextField(
                            value = currentValue,
                            onValueChange = { newValue ->
                                // Only allow numeric input
                                if (newValue.isEmpty() || newValue.all { it.isDigit() || it == '.' || it == '-' }) {
                                    onValueChange(field.key, newValue)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(field.label) },
                            placeholder = { Text(field.placeholder) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            isError = field.required && currentValue.isBlank()
                        )
                    }

                    FieldType.SELECT -> {
                        SelectField(
                            value = currentValue,
                            options = field.options,
                            onValueChange = { onValueChange(field.key, it) },
                            label = field.label
                        )
                    }
                }

                // Show required indicator
                if (field.required && currentValue.isBlank()) {
                    Text(
                        text = "Required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isRequired: Boolean,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (passwordVisible)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible)
                        Icons.Default.Visibility
                    else
                        Icons.Default.VisibilityOff,
                    contentDescription = if (passwordVisible) "Hide" else "Show"
                )
            }
        },
        isError = isRequired && value.isBlank()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
