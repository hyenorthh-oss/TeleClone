package com.teleclone.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkUndergroundScheme = darkColorScheme(
    primary = AccentRed,
    onPrimary = TextPrimary,
    primaryContainer = AccentRedDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentRed,
    onSecondary = TextPrimary,
    background = BackgroundDeep,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle,
    error = AccentRed,
    onError = TextPrimary,
)

@Composable
fun TeleCloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkUndergroundScheme,
        typography = TeleCloneTypography,
        content = content,
    )
}
