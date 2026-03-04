package com.mobilekinetic.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.provider.ProviderConfigField
import com.mobilekinetic.agent.provider.ProviderConfigStore
import com.mobilekinetic.agent.provider.ProviderRegistry
import com.mobilekinetic.agent.provider.impl.CustomProvider
import com.mobilekinetic.agent.provider.impl.GeminiProvider
import com.mobilekinetic.agent.provider.impl.OpenAiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val configStore: ProviderConfigStore
) : ViewModel() {

    val activeProviderId = providerRegistry.activeProviderId

    data class ProviderInfo(
        val id: String,
        val name: String,
        val isActive: Boolean,
        val configFields: List<ProviderConfigField>
    )

    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providers: StateFlow<List<ProviderInfo>> = _providers.asStateFlow()

    private val _configValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val configValues: StateFlow<Map<String, String>> = _configValues.asStateFlow()

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refreshProviders()
    }

    fun refreshProviders() {
        viewModelScope.launch {
            val activeId = providerRegistry.activeProviderId.value
            val all = providerRegistry.getAllProviders().map { p ->
                ProviderInfo(
                    id = p.id,
                    name = p.name,
                    isActive = p.id == activeId,
                    configFields = p.getConfigSchema()
                )
            }
            _providers.value = all

            // Auto-select the active provider if nothing is selected
            if (_selectedProviderId.value == null) {
                selectProvider(activeId)
            }
        }
    }

    fun selectProvider(providerId: String) {
        viewModelScope.launch {
            _selectedProviderId.value = providerId
            val config = configStore.getProviderConfig(providerId)
            _configValues.value = config
        }
    }

    fun updateConfigValue(key: String, value: String) {
        _configValues.update { it + (key to value) }
    }

    fun saveAndActivate(providerId: String) {
        viewModelScope.launch {
            val config = _configValues.value

            // Save config values
            configStore.setProviderConfig(providerId, config)

            // Rebuild the provider instance with updated config
            providerRegistry.unregisterProvider(providerId)
            val newProvider = when (providerId) {
                "custom" -> CustomProvider(
                    apiKey = config["api_key"] ?: "",
                    model = config["model"] ?: "",
                    baseUrl = config["base_url"] ?: ""
                )
                "openai" -> OpenAiProvider(
                    apiKey = config["api_key"] ?: "",
                    model = config["model"] ?: "gpt-4o",
                    baseUrl = config["base_url"] ?: "https://api.openai.com/v1"
                )
                "gemini" -> GeminiProvider(
                    apiKey = config["api_key"] ?: "",
                    model = config["model"] ?: "gemini-2.0-flash"
                )
                else -> null
            }
            if (newProvider != null) {
                providerRegistry.registerProvider(newProvider)
            }

            // Switch active provider
            val error = providerRegistry.switchProvider(providerId)
            if (error != null) {
                _statusMessage.value = "Error: $error"
            } else {
                _statusMessage.value = "Switched to ${providerRegistry.getProvider(providerId)?.name ?: providerId}"
                refreshProviders()
            }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}
