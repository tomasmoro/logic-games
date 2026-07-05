package com.example.kortexgames.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = LogicColors.Violet,
    secondary = LogicColors.Electric,
    tertiary = LogicColors.Magenta,
    background = LogicColors.BackgroundDark,
    surface = LogicColors.SurfaceDark,
    surfaceVariant = LogicColors.SurfaceVariantDark,
    error = LogicColors.Error,
    onPrimary = LogicColors.OnDark,
    onBackground = LogicColors.OnDark,
    onSurface = LogicColors.OnDark,
    onSurfaceVariant = LogicColors.OnDarkMuted,
)

private val LightScheme = lightColorScheme(
    primary = LogicColors.Violet,
    secondary = LogicColors.Electric,
    tertiary = LogicColors.Magenta,
    background = LogicColors.BackgroundLight,
    surface = LogicColors.SurfaceLight,
    error = LogicColors.Error,
    onPrimary = LogicColors.OnDark,
    onBackground = LogicColors.OnLight,
    onSurface = LogicColors.OnLight,
)

private val LogicShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun LogicGamesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = LogicShapes,
        typography = LogicTypography,
        content = content,
    )
}
