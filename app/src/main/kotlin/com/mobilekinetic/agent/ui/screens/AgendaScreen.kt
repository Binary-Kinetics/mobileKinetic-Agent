package com.mobilekinetic.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mobilekinetic.agent.ui.viewmodel.AgendaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    viewModel: AgendaViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agenda") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when (selectedTab) {
                    0 -> viewModel.showAddGoalDialog()
                    1 -> viewModel.showAddIntentionDialog()
                }
            }) {
                Icon(Icons.Default.Add, "Add")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Goals") }, icon = { Icon(Icons.Default.Flag, null) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Intentions") }, icon = { Icon(Icons.Default.Schedule, null) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    text = { Text("Behaviors") }, icon = { Icon(Icons.Default.Psychology, null) })
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> when (selectedTab) {
                        0 -> GoalsTab(uiState, viewModel)
                        1 -> IntentionsTab(uiState, viewModel)
                        2 -> BehaviorsTab(uiState, viewModel)
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.showAddGoalDialog) {
        AddGoalDialog(
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { desc, priority, deadline ->
                viewModel.createGoal(desc, priority, deadline)
            }
        )
    }
    if (uiState.showAddIntentionDialog) {
        AddIntentionDialog(
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { desc, triggerType, triggerValue, maxFires ->
                viewModel.setIntention(desc, triggerType, triggerValue, maxFires)
            }
        )
    }
}

@Composable
private fun GoalsTab(uiState: AgendaViewModel.AgendaUiState, viewModel: AgendaViewModel) {
    if (uiState.goals.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No goals yet. Tap + to create one.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.goals, key = { it.id }) { goal ->
                GoalCard(goal, onComplete = { viewModel.updateGoalStatus(goal.id, "completed") },
                    onDelete = { viewModel.deleteGoal(goal.id) })
            }
        }
    }
}

@Composable
private fun GoalCard(
    goal: AgendaViewModel.GoalItem,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (goal.status) {
                "completed" -> MaterialTheme.colorScheme.secondaryContainer
                "failed" -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityBadge(goal.priority)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        goal.description,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    goal.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (goal.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val completed = goal.steps.count { it.status == "completed" }
                LinearProgressIndicator(
                    progress = { completed.toFloat() / goal.steps.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "$completed/${goal.steps.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            goal.deadline?.let {
                Text("Due: $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (goal.status == "active") {
                    TextButton(onClick = onComplete) { Text("Complete") }
                }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PriorityBadge(priority: Int) {
    val color = when (priority) {
        1 -> MaterialTheme.colorScheme.error
        2 -> MaterialTheme.colorScheme.tertiary
        3 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(shape = MaterialTheme.shapes.small, color = color, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text("P$priority", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun IntentionsTab(uiState: AgendaViewModel.AgendaUiState, viewModel: AgendaViewModel) {
    if (uiState.intentions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No intentions set. Tap + to schedule one.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.intentions, key = { it.id }) { intention ->
                IntentionCard(intention, onCancel = { viewModel.cancelIntention(intention.id) })
            }
        }
    }
}

@Composable
private fun IntentionCard(
    intention: AgendaViewModel.IntentionItem,
    onCancel: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (intention.status == "fired") MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    )) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(intention.description, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Text(intention.status.uppercase(), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Icon(Icons.Default.Schedule, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("${intention.triggerType}: ${intention.triggerValue}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (intention.maxFires != 1) {
                val firesText = if (intention.maxFires == -1) "${intention.fireCount}/∞"
                else "${intention.fireCount}/${intention.maxFires}"
                Text("Fires: $firesText", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (intention.status == "pending") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun BehaviorsTab(uiState: AgendaViewModel.AgendaUiState, viewModel: AgendaViewModel) {
    if (uiState.behaviors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No learned behaviors yet.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.behaviors, key = { it.id }) { behavior ->
                BehaviorCard(behavior)
            }
        }
    }
}

@Composable
private fun BehaviorCard(behavior: AgendaViewModel.BehaviorItem) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(behavior.pattern, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LinearProgressIndicator(
                    progress = { behavior.confidence },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text("${(behavior.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("Confirmed ${behavior.timesConfirmed}x",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalDialog(onDismiss: () -> Unit, onConfirm: (String, Int, String?) -> Unit) {
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(3) }
    var deadline by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text("P$p") }
                        )
                    }
                }
                OutlinedTextField(
                    value = deadline, onValueChange = { deadline = it },
                    label = { Text("Deadline (optional, YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(description, priority, deadline.ifBlank { null }) },
                enabled = description.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddIntentionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Int) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var triggerType by remember { mutableStateOf("time") }
    var triggerValue by remember { mutableStateOf("") }
    var maxFires by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Intention") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("What to do") }, modifier = Modifier.fillMaxWidth()
                )
                Text("Trigger type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("time", "condition", "event").forEach { t ->
                        FilterChip(
                            selected = triggerType == t,
                            onClick = { triggerType = t },
                            label = { Text(t.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                OutlinedTextField(
                    value = triggerValue, onValueChange = { triggerValue = it },
                    label = {
                        Text(when (triggerType) {
                            "time" -> "When (ISO datetime)"
                            "condition" -> "Condition description"
                            else -> "Event name"
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = maxFires == 1, onClick = { maxFires = 1 },
                        label = { Text("Once") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = maxFires == -1, onClick = { maxFires = -1 },
                        label = { Text("Recurring") })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(description, triggerType, triggerValue, maxFires) },
                enabled = description.isNotBlank() && triggerValue.isNotBlank()
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
