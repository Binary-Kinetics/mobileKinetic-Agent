package com.mobilekinetic.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import com.mobilekinetic.agent.data.rag.ToolMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolsUiState(
    val tools: List<ToolEntity> = emptyList(),
    val activeCategories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val toolCount: Int = 0
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val toolMemory: ToolMemory,
    private val toolDao: ToolDao
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")

    val uiState: StateFlow<ToolsUiState> = combine(
        toolDao.getAllTools(),
        toolDao.getActiveCategories(),
        _selectedCategory,
        _searchQuery
    ) { allTools, categories, category, query ->
        val filtered = allTools
            .filter { tool ->
                (category == null || tool.category == category) &&
                (query.isBlank() || tool.name.contains(query, ignoreCase = true) ||
                    tool.description.contains(query, ignoreCase = true) ||
                    tool.technicalName.contains(query, ignoreCase = true))
            }
        ToolsUiState(
            tools = filtered,
            activeCategories = categories,
            selectedCategory = category,
            searchQuery = query,
            toolCount = allTools.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ToolsUiState())

    fun selectCategory(category: String?) {
        _selectedCategory.update { category }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.update { query }
    }

    fun approveTool(id: String) {
        viewModelScope.launch { toolMemory.approveTool(id) }
    }

    fun revokeTool(id: String) {
        viewModelScope.launch { toolMemory.revokeTool(id) }
    }

    fun deleteTool(id: String) {
        viewModelScope.launch { toolMemory.deleteTool(id) }
    }
}
