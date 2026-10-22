package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GraphiteDarkColorScheme = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color.White,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkTextPrimary,
    secondary = AccentWhiteContour,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = CallActiveGreen,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = CallEndRed,
    onError = Color.White
)

private val SoftLightColorScheme = lightColorScheme(
    primary = AccentBlueLight,
    onPrimary = Color.White,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightTextPrimary,
    secondary = AccentSlateContour,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightTextPrimary,
    tertiary = CallActiveGreen,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = CallEndRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GraphiteDarkColorScheme else SoftLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
