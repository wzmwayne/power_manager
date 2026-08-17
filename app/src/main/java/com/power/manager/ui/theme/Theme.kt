package com.power.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FCB7F),
    onPrimary = Color(0xFF003300),
    primaryContainer = Color(0xFF1E4A1E),
    onPrimaryContainer = Color(0xFF9FDE9F),
    secondary = Color(0xFF90A4AE),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2A3A42),
    onSecondaryContainer = Color(0xFFC9DCE4),
    tertiary = Color(0xFFCE9A6B),
    onTertiary = Color(0xFF2B1A08),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF8A8A8A),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFB4A8),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2A2A2A)
)

@Composable
fun PowerManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}