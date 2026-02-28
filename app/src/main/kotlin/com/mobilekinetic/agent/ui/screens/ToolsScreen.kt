package com.mobilekinetic.agent.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsPurple
import com.mobilekinetic.agent.ui.theme.LcarsSubduedWarm
import com.mobilekinetic.agent.ui.viewmodel.ToolsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel(),
    onToolSelected: (ToolEntity) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = {
                Text("TOOLS (${uiState.toolCount})", color = LcarsOrange)
            })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("Search tools...") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = LcarsOrange
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LcarsOrange,
                    cursorColor = LcarsOrange
                )
            )

            // Category filter chips
            if (uiState.activeCategories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedCategory == null,
                            onClick = { viewModel.selectCategory(null) },
                            label = { Text("All") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LcarsOrange.copy(alpha = 0.2f),
                                selectedLabelColor = LcarsOrange
                            )
                        )
                    }
                    items(uiState.activeCategories) { category ->
                        FilterChip(
                            selected = uiState.selectedCategory == category,
                            onClick = { viewModel.selectCategory(category) },
                            label = { Text(category.replace("_", " ")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LcarsOrange.copy(alpha = 0.2f),
                                selectedLabelColor = LcarsOrange
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (uiState.toolCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Catalog loading...",
                        style = MaterialTheme.typography.titleLarge,
                        color = LcarsOrange
                    )
                }
            } else if (uiState.tools.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No tools match filter",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.tools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            onTap = { onToolSelected(tool) },
                            onToggleApproval = {
                                if (tool.isUserApproved) viewModel.revokeTool(tool.id)
                                else viewModel.approveTool(tool.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolEntity,
    onTap: () -> Unit,
    onToggleApproval: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (tool.isUserApproved)
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FF00))
        else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    tool.isUserApproved -> Color(0xFF00FF00)
                                    tool.isBuiltIn -> LcarsOrange
                                    else -> Color(0xFF606060)
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    // Tool name
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (tool.isUserApproved) Color(0xFF00FF00) else LcarsOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Approval toggle
                Switch(
                    checked = tool.isUserApproved,
                    onCheckedChange = { onToggleApproval() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF00),
                        checkedTrackColor = Color(0xFF00FF00).copy(alpha = 0.3f),
                        uncheckedThumbColor = Color(0xFF808080),
                        uncheckedTrackColor = Color(0xFF404040)
                    )
                )
            }

            Spacer(Modifier.height(4.dp))

            // Source badge + category + use count
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (tool.source) {
                        "TASKER" -> LcarsPurple
                        "RAG_SEED" -> LcarsBlue
                        else -> LcarsSubduedWarm
                    }
                )
                Text(
                    text = tool.category.replace("_", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = LcarsGreen
                )
                if (tool.useCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Used ${tool.useCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Description
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
