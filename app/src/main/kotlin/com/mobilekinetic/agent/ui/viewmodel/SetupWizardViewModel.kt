package com.mobilekinetic.agent.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the 6-page first-run setup wizard.
 *
 * Holds mutable Compose state for every form field across all wizard pages,
 * persists page data to DataStore via SettingsRepository, and marks setup
 * as complete on finish.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // ── Page 2: Identity ─────────────────────────────────────────────────────
    var userName by mutableStateOf("")
    var deviceName by mutableStateOf("")

    // ── Page 3: AI Provider ──────────────────────────────────────────────────
    var apiKey by mutableStateOf("")
    var apiKeyVisible by mutableStateOf(false)
    var selectedModel by mutableStateOf("sonnet")

    // ── Page 4: Voice / TTS ──────────────────────────────────────────────────
    var ttsServerHost by mutableStateOf("")
    var ttsServerPort by mutableStateOf("9199")
    var ttsWssUrl by mutableStateOf("")

    // ── Page 5: Integrations ─────────────────────────────────────────────────
    var haServerUrl by mutableStateOf("")
    var nasIp by mutableStateOf("")
    var switchboardUrl by mutableStateOf("")

    /**
     * Persist fields for a given wizard page to DataStore.
     * Called when the user taps Next (or Skip) to leave a page.
     */
    fun savePageData(page: Int) {
        viewModelScope.launch {
            when (page) {
                1 -> { // Identity
                    settingsRepository.setUserName(userName)
                    settingsRepository.setDeviceName(deviceName)
                }
                2 -> { // AI Provider
                    if (apiKey.isNotBlank()) {
                        val hint = if (apiKey.length > 8) {
                            apiKey.take(7) + "..." + apiKey.takeLast(4)
                        } else {
                            apiKey
                        }
                        settingsRepository.setApiKeyHint(hint)
                    }
                    settingsRepository.setModelSelection(selectedModel)
                }
                3 -> { // Voice / TTS
                    if (ttsServerHost.isNotBlank()) {
                        settingsRepository.setTtsServerHost(ttsServerHost)
                    }
                    val port = ttsServerPort.toIntOrNull() ?: 9199
                    settingsRepository.setTtsServerPort(port)
                    if (ttsWssUrl.isNotBlank()) {
                        settingsRepository.setTtsWssUrl(ttsWssUrl)
                    }
                }
                4 -> { // Integrations
                    if (haServerUrl.isNotBlank()) {
                        settingsRepository.setHaServerUrl(haServerUrl)
                    }
                    if (nasIp.isNotBlank()) {
                        settingsRepository.setNasIp(nasIp)
                    }
                    if (switchboardUrl.isNotBlank()) {
                        settingsRepository.setSwitchboardUrl(switchboardUrl)
                    }
                }
            }
        }
    }

    /**
     * Final action: persist any remaining data and mark setup complete.
     */
    fun completeSetup(onComplete: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSetupComplete(true)
            onComplete()
        }
    }

    /**
     * Summary helpers for the Review page.
     */
    val maskedApiKey: String
        get() = if (apiKey.isNotBlank()) {
            apiKey.take(7) + "..." + apiKey.takeLast(4)
        } else {
            "(not set)"
        }

    val modelDisplayName: String
        get() = when (selectedModel) {
            "haiku" -> "claude-haiku-4-5"
            "sonnet" -> "claude-sonnet-4-5"
            "opus" -> "claude-opus-4-5"
            else -> selectedModel
        }

    val ttsConfigured: Boolean
        get() = ttsServerHost.isNotBlank()

    val integrationsConfigured: Int
        get() = listOf(haServerUrl, nasIp, switchboardUrl).count { it.isNotBlank() }
}
