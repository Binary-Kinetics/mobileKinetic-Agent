package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.claude.ClaudeProcessManager
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val claudeProcessManager: ClaudeProcessManager
) : ViewModel() {

    val modelSelection: StateFlow<String> = settingsRepository.modelSelection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "sonnet")

    val apiKeyHint: StateFlow<String> = settingsRepository.apiKeyHint
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Claude Process state
    val isClaudeRunning: StateFlow<Boolean> = claudeProcessManager.isRunning
    val claudeLastError: StateFlow<String?> = claudeProcessManager.lastError

    // Live model list from API
    val availableModels: StateFlow<List<ClaudeProcessManager.ModelInfo>> = claudeProcessManager.availableModels
    val isLoadingModels: StateFlow<Boolean> = claudeProcessManager.isLoadingModels

    fun refreshModels() {
        claudeProcessManager.requestModels()
    }

    // Heartbeat settings
    val heartbeatEnabled: StateFlow<Boolean> = settingsRepository.heartbeatEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val heartbeatPrompt: StateFlow<String> = settingsRepository.heartbeatPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val heartbeatIntervalMinutes: StateFlow<Int> = settingsRepository.heartbeatIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val autoUpdateAfterDecay: StateFlow<Boolean> = settingsRepository.autoUpdateAfterDecay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setModelSelection(model: String) {
        viewModelScope.launch {
            settingsRepository.setModelSelection(model)
        }
    }

    fun setApiKeyHint(hint: String) {
        viewModelScope.launch {
            settingsRepository.setApiKeyHint(hint)
        }
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHeartbeatEnabled(enabled)
        }
    }

    fun setHeartbeatPrompt(prompt: String) {
        viewModelScope.launch {
            settingsRepository.setHeartbeatPrompt(prompt)
        }
    }

    fun setHeartbeatIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setHeartbeatIntervalMinutes(minutes)
        }
    }

    fun setAutoUpdateAfterDecay(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateAfterDecay(enabled)
        }
    }

    fun startClaude() {
        claudeProcessManager.start()
    }

    fun stopClaude() {
        claudeProcessManager.stop()
    }

    fun restartClaude() {
        viewModelScope.launch {
            claudeProcessManager.stop()
            // Wait for the manager to fully report as stopped
            var waited = 0L
            while (claudeProcessManager.isRunning.value && waited < 5000L) {
                delay(100)
                waited += 100
            }
            delay(500) // Brief extra settle time after state change
            claudeProcessManager.start()
        }
    }

    fun killTerminalProcesses(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                claudeProcessManager.stop()
                // Also pkill any orphaned processes not tracked by the manager
                val process = Runtime.getRuntime().exec("pkill -f claude")
                process.waitFor()
                onResult(true, "Terminal processes killed successfully")
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }
}
