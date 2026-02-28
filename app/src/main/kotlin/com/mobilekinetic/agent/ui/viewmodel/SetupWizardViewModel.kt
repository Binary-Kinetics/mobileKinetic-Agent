package com.mobilekinetic.agent.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.vault.CredentialVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

/**
 * ViewModel for the 6-page first-run setup wizard.
 *
 * Holds mutable Compose state for every form field across all wizard pages,
 * persists page data to DataStore via SettingsRepository, stores the API key
 * securely in CredentialVault, and marks setup as complete on finish.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val credentialVault: CredentialVault
) : ViewModel() {

    companion object {
        private const val TAG = "SetupWizardVM"
    }

    // ── Page 2: Identity ─────────────────────────────────────────────────────
    var userName by mutableStateOf("")
    var deviceName by mutableStateOf("")

    // ── Page 3: AI Provider ──────────────────────────────────────────────────
    var apiKey by mutableStateOf("")
    var apiKeyVisible by mutableStateOf(false)
    var selectedModel by mutableStateOf("sonnet")
    var apiKeyValidationState by mutableStateOf<ApiKeyState>(ApiKeyState.IDLE)
    var apiKeyValidationMessage by mutableStateOf("")

    enum class ApiKeyState { IDLE, VALIDATING, VALID, INVALID, ERROR }

    /**
     * Validate the API key by making a minimal test call to the Anthropic API.
     * On success, stores the key in CredentialVault (AES-256-GCM, no biometric).
     */
    fun validateApiKey() {
        if (apiKey.isBlank()) {
            apiKeyValidationState = ApiKeyState.INVALID
            apiKeyValidationMessage = "API key cannot be empty"
            return
        }
        apiKeyValidationState = ApiKeyState.VALIDATING
        apiKeyValidationMessage = "Validating..."

        viewModelScope.launch {
            try {
                val (code, body) = withContext(Dispatchers.IO) {
                    val url = URL("https://api.anthropic.com/v1/messages")
                    val conn = url.openConnection() as HttpsURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("x-api-key", apiKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                    conn.setRequestProperty("content-type", "application/json")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    val reqBody = """{"model":"claude-haiku-4-5-20241022","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                    conn.outputStream.use { it.write(reqBody.toByteArray()) }
                    val responseCode = conn.responseCode
                    val responseBody = try {
                        conn.inputStream.bufferedReader().readText()
                    } catch (e: Exception) {
                        conn.errorStream?.bufferedReader()?.readText() ?: ""
                    }
                    responseCode to responseBody
                }
                when (code) {
                    200 -> {
                        storeApiKeyInVault()
                        apiKeyValidationState = ApiKeyState.VALID
                        apiKeyValidationMessage = "API key is valid"
                        Log.i(TAG, "API key validated successfully")
                    }
                    401 -> {
                        apiKeyValidationState = ApiKeyState.INVALID
                        apiKeyValidationMessage = "Invalid API key"
                        Log.w(TAG, "API key validation failed: 401")
                    }
                    else -> {
                        apiKeyValidationState = ApiKeyState.ERROR
                        apiKeyValidationMessage = "API error (HTTP $code)"
                        Log.w(TAG, "API key validation error: HTTP $code")
                    }
                }
            } catch (e: Exception) {
                apiKeyValidationState = ApiKeyState.ERROR
                apiKeyValidationMessage = "Connection error: ${e.message}"
                Log.e(TAG, "API key validation failed", e)
            }
        }
    }

    /**
     * Store the raw API key in CredentialVault for the orchestrator to read at launch.
     */
    private suspend fun storeApiKeyInVault() {
        val hint = if (apiKey.length > 8) apiKey.take(7) + "..." + apiKey.takeLast(4) else apiKey
        val description = "desc:Anthropic Claude API key|inject:env:ANTHROPIC_API_KEY|service:anthropic|contexts:orchestrator|hint:$hint"
        credentialVault.store("ANTHROPIC_API_KEY", apiKey, description)
        settingsRepository.setApiKeyHint(hint)
        Log.i(TAG, "API key stored in vault")
    }

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
                        storeApiKeyInVault()
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
