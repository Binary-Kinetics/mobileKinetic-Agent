package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.claude.AgendaManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val agendaManager: AgendaManager
) : ViewModel() {

    data class GoalItem(
        val id: String,
        val description: String,
        val priority: Int,
        val status: String,
        val steps: List<StepItem>,
        val deadline: String?,
        val lastTouched: String
    )

    data class StepItem(val description: String, val status: String, val result: String?)

    data class IntentionItem(
        val id: String,
        val description: String,
        val triggerType: String,
        val triggerValue: String,
        val status: String,
        val fireCount: Int,
        val maxFires: Int
    )

    data class BehaviorItem(
        val id: String,
        val pattern: String,
        val confidence: Float,
        val timesConfirmed: Int
    )

    data class AgendaUiState(
        val goals: List<GoalItem> = emptyList(),
        val intentions: List<IntentionItem> = emptyList(),
        val behaviors: List<BehaviorItem> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val showAddGoalDialog: Boolean = false,
        val showAddIntentionDialog: Boolean = false
    )

    private val _uiState = MutableStateFlow(AgendaUiState())
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val goals = agendaManager.listGoals(status = "all").map { it.toUiItem() }
                val intentions = agendaManager.getDueIntentions().map { it.toUiItem() }
                // Also get pending intentions for the UI
                val allIntentions = intentions // agenda_get_due returns both due and pending
                val behaviors = agendaManager.getBehaviors().map { it.toUiItem() }
                _uiState.update { it.copy(
                    goals = goals,
                    intentions = allIntentions,
                    behaviors = behaviors,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load agenda") }
            }
        }
    }

    fun createGoal(description: String, priority: Int, deadline: String?) {
        viewModelScope.launch {
            agendaManager.createGoal(description, priority, deadline)
            dismissDialog()
            refresh()
        }
    }

    fun updateGoalStatus(goalId: String, status: String) {
        viewModelScope.launch {
            agendaManager.updateGoal(goalId, status = status)
            refresh()
        }
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            agendaManager.deleteGoal(goalId)
            refresh()
        }
    }

    fun setIntention(description: String, triggerType: String, triggerValue: String, maxFires: Int) {
        viewModelScope.launch {
            agendaManager.setIntention(description, triggerType, triggerValue, maxFires)
            dismissDialog()
            refresh()
        }
    }

    fun cancelIntention(intentionId: String) {
        viewModelScope.launch {
            agendaManager.cancelIntention(intentionId)
            refresh()
        }
    }

    fun showAddGoalDialog() { _uiState.update { it.copy(showAddGoalDialog = true) } }
    fun showAddIntentionDialog() { _uiState.update { it.copy(showAddIntentionDialog = true) } }
    fun dismissDialog() { _uiState.update { it.copy(showAddGoalDialog = false, showAddIntentionDialog = false) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }

    // --- Mappers ---

    private fun AgendaManager.Goal.toUiItem() = GoalItem(
        id = id, description = description, priority = priority, status = status,
        steps = steps.map { StepItem(it.description, it.status, it.result) },
        deadline = deadline, lastTouched = lastTouched
    )

    private fun AgendaManager.Intention.toUiItem() = IntentionItem(
        id = id, description = description, triggerType = triggerType,
        triggerValue = triggerValue, status = status,
        fireCount = fireCount, maxFires = maxFires
    )

    private fun AgendaManager.LearnedBehavior.toUiItem() = BehaviorItem(
        id = id, pattern = pattern, confidence = confidence,
        timesConfirmed = timesConfirmed
    )
}
