package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.db.dao.BlacklistDao
import com.mobilekinetic.agent.data.db.entity.BlacklistRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val blacklistDao: BlacklistDao
) : ViewModel() {

    val rules = blacklistDao.getAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addRule(ruleType: String, value: String, action: String = "REDACT", description: String? = null) {
        viewModelScope.launch {
            blacklistDao.insertRule(
                BlacklistRuleEntity(
                    ruleType = ruleType,
                    value = value,
                    action = action,
                    description = description
                )
            )
        }
    }

    fun toggleRule(id: String, enabled: Boolean) {
        viewModelScope.launch {
            blacklistDao.setEnabled(id, enabled)
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            blacklistDao.deleteById(id)
        }
    }
}
