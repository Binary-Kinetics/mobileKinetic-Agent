package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.settings.InjectionSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Memory & Context Injection settings screen.
 *
 * Exposes each injection setting as a StateFlow and provides toggle/update
 * functions that delegate to InjectionSettingsRepository.
 */
@HiltViewModel
class InjectionSettingsViewModel @Inject constructor(
    private val repository: InjectionSettingsRepository
) : ViewModel() {

    // ── Context Injection ──────────────────────────────────────────────────

    val contextInjectionEnabled: StateFlow<Boolean> = repository.contextInjectionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val tier1Enabled: StateFlow<Boolean> = repository.tier1Enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val tier2Enabled: StateFlow<Boolean> = repository.tier2Enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ── Maintenance ────────────────────────────────────────────────────────

    val napkinDabEnabled: StateFlow<Boolean> = repository.napkinDabEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val napkinDabThreshold: StateFlow<Int> = repository.napkinDabThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 50)

    val backupEnabled: StateFlow<Boolean> = repository.backupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ── Classification ─────────────────────────────────────────────────────

    val haikuClassificationEnabled: StateFlow<Boolean> = repository.haikuClassificationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoLearnEnabled: StateFlow<Boolean> = repository.autoLearnEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ── Toggle Functions ───────────────────────────────────────────────────

    fun toggleContextInjection(enabled: Boolean) {
        viewModelScope.launch { repository.setContextInjectionEnabled(enabled) }
    }

    fun toggleTier1(enabled: Boolean) {
        viewModelScope.launch { repository.setTier1Enabled(enabled) }
    }

    fun toggleTier2(enabled: Boolean) {
        viewModelScope.launch { repository.setTier2Enabled(enabled) }
    }

    fun toggleNapkinDab(enabled: Boolean) {
        viewModelScope.launch { repository.setNapkinDabEnabled(enabled) }
    }

    fun updateNapkinDabThreshold(threshold: Int) {
        viewModelScope.launch { repository.setNapkinDabThreshold(threshold) }
    }

    fun toggleBackup(enabled: Boolean) {
        viewModelScope.launch { repository.setBackupEnabled(enabled) }
    }

    fun toggleHaikuClassification(enabled: Boolean) {
        viewModelScope.launch { repository.setHaikuClassificationEnabled(enabled) }
    }

    fun toggleAutoLearn(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoLearnEnabled(enabled) }
    }
}
