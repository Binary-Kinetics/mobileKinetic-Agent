package com.mobilekinetic.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes using Kotlin Serialization.
 * Each route corresponds to a screen in the app.
 */
@Serializable
sealed class Route {
    @Serializable data object Chat : Route()
    @Serializable data object Terminal : Route()
    @Serializable data object Tools : Route()
    @Serializable data object Settings : Route()
    @Serializable data object Setup : Route()
    @Serializable data object Vault : Route()
}

/**
 * Bottom navigation destinations.
 * Setup is intentionally excluded from bottom nav as it is a one-time flow.
 */
enum class Destination(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
    val contentDescription: String,
    val route: Route
) {
    Chat(
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
        label = "Chat",
        contentDescription = "Claude conversation",
        route = Route.Chat
    ),
    Terminal(
        selectedIcon = Icons.Filled.Terminal,
        unselectedIcon = Icons.Outlined.Terminal,
        label = "Terminal",
        contentDescription = "Terminal access",
        route = Route.Terminal
    ),
    Tools(
        selectedIcon = Icons.Filled.Build,
        unselectedIcon = Icons.Outlined.Build,
        label = "Tools",
        contentDescription = "Tool management",
        route = Route.Tools
    ),
    Settings(
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        label = "Settings",
        contentDescription = "App settings and credentials",
        route = Route.Settings
    )
}
