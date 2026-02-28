package com.mobilekinetic.agent.ui.theme

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

private val LcarsDarkColorScheme = darkColorScheme(
    primary = LcarsOrange,
    onPrimary = LcarsBlack,
    primaryContainer = LcarsBlue,
    onPrimaryContainer = LcarsBlack,
    secondary = LcarsPurple,
    onSecondary = LcarsTextPrimary,
    secondaryContainer = LcarsContainerGray,
    onSecondaryContainer = LcarsBlue,
    tertiary = LcarsPink,
    onTertiary = LcarsBlack,
    background = LcarsBlack,
    onBackground = LcarsTextPrimary,
    surface = LcarsBlack,
    onSurface = LcarsTextPrimary,
    surfaceVariant = LcarsContainerGray,
    onSurfaceVariant = LcarsTextBody,
    outline = LcarsTextSecondary,
    outlineVariant = LcarsTextSecondary
)

@OptIn(ExperimentalMaterial3Api::class)
private val LcarsRippleConfiguration = RippleConfiguration(
    color = LcarsBlue
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileKineticTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        else -> LcarsDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides LcarsRippleConfiguration,
            content = content
        )
    }
}
