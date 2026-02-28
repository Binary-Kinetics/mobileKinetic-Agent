package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.db.dao.BlacklistDao
import com.mobilekinetic.agent.data.db.entity.BlacklistRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlacklistUiState(
    val rules: List<BlacklistRuleEntity> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class BlacklistViewModel @Inject constructor(
    private val blacklistDao: BlacklistDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlacklistUiState())
    val uiState: StateFlow<BlacklistUiState> = _uiState.asStateFlow()

    init {
        loadRules()
    }

    fun loadRules() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            blacklistDao.getAllRules().collect { rules ->
                _uiState.value = BlacklistUiState(rules = rules, isLoading = false)
            }
        }
    }

    fun addRule(category: String, pattern: String, matchType: String, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blacklistDao.insertRule(
                BlacklistRuleEntity(
                    ruleType = category,
                    value = pattern,
                    action = matchType,
                    description = description.ifBlank { null }
                )
            )
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blacklistDao.deleteById(id)
        }
    }
}
