package com.mobilekinetic.agent.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobilekinetic.agent.ui.theme.LcarsBlack
import com.mobilekinetic.agent.ui.theme.LcarsBlue
import com.mobilekinetic.agent.ui.theme.LcarsContainerGray
import com.mobilekinetic.agent.ui.theme.LcarsGreen
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsRed
import com.mobilekinetic.agent.ui.theme.LcarsTextSecondary

enum class SetupPhase {
    BOOTSTRAP_INSTALL,
    COMPLETE
}

enum class StepStatus {
    PENDING, IN_PROGRESS, COMPLETE, FAILED
}

@Composable
fun SetupScreen(
    isBootstrapInstalled: Boolean,
    onSetupComplete: () -> Unit,
    onBootstrapInstall: suspend (onProgress: (String) -> Unit) -> Result<String>,
    modifier: Modifier = Modifier
) {
    var phase by remember {
        mutableStateOf(
            if (isBootstrapInstalled) SetupPhase.COMPLETE
            else SetupPhase.BOOTSTRAP_INSTALL
        )
    }
    var bootstrapStatus by remember { mutableStateOf(StepStatus.PENDING) }
    var bootstrapMessage by remember { mutableStateOf("Preparing bootstrap...") }

    // Auto-start bootstrap when phase is BOOTSTRAP_INSTALL
    LaunchedEffect(phase) {
        if (phase == SetupPhase.BOOTSTRAP_INSTALL && bootstrapStatus == StepStatus.PENDING) {
            bootstrapStatus = StepStatus.IN_PROGRESS
            bootstrapMessage = "Starting bootstrap installation..."
            val result = onBootstrapInstall { progress ->
                bootstrapMessage = progress
            }
            if (result.isSuccess) {
                bootstrapStatus = StepStatus.COMPLETE
                bootstrapMessage = result.getOrDefault("Bootstrap installed successfully")
                // Skip API key - Claude Code uses CLI subscription
                kotlinx.coroutines.delay(1000)
                onSetupComplete()
            } else {
                bootstrapStatus = StepStatus.FAILED
                bootstrapMessage = result.exceptionOrNull()?.message ?: "Bootstrap installation failed"
            }
        } else if (phase == SetupPhase.COMPLETE) {
            // Bootstrap already installed, skip straight to complete
            onSetupComplete()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LcarsBlack)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "BINARY AGENT",
            style = MaterialTheme.typography.headlineMedium,
            color = LcarsOrange,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "SYSTEM SETUP",
            style = MaterialTheme.typography.titleMedium,
            color = LcarsTextSecondary
        )

        Spacer(Modifier.height(16.dp))

        // Step 1: Bootstrap
        SetupStepCard(
            stepNumber = 1,
            title = "Bootstrap Environment",
            description = if (bootstrapStatus == StepStatus.IN_PROGRESS) bootstrapMessage
                else "Extract terminal environment and core utilities",
            status = if (isBootstrapInstalled) StepStatus.COMPLETE else bootstrapStatus
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    status: StepStatus,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LcarsContainerGray),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusIndicator(status = status)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STEP $stepNumber",
                    style = MaterialTheme.typography.labelSmall,
                    color = LcarsOrange,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LcarsTextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    status: StepStatus,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val color by animateColorAsState(
        targetValue = when (status) {
            StepStatus.PENDING -> LcarsTextSecondary
            StepStatus.IN_PROGRESS -> LcarsBlue
            StepStatus.COMPLETE -> LcarsGreen
            StepStatus.FAILED -> LcarsRed
        },
        label = "statusColor"
    )

    Box(
        modifier = modifier
            .size(32.dp)
            .alpha(if (status == StepStatus.IN_PROGRESS) pulseAlpha else 1f)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            StepStatus.COMPLETE -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Complete",
                tint = LcarsBlack,
                modifier = Modifier.size(18.dp)
            )
            StepStatus.FAILED -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Failed",
                tint = LcarsBlack,
                modifier = Modifier.size(18.dp)
            )
            else -> {}
        }
    }
}
