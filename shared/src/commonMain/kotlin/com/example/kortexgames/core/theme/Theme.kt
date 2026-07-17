package com.example.kortexgames.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Tema único, siempre oscuro (CLAUDE.md §9.1): la identidad "azul noche + neón"
// no tiene variante clara; ignorar el tema del sistema evita que la app se vea
// rota (colores calculados para fondo oscuro) si el usuario tiene modo claro activo.
private val DarkScheme = darkColorScheme(
    primary = LogicColors.NeonCyan,        // foco/navegación
    secondary = LogicColors.NeonGreen,     // acción principal
    tertiary = LogicColors.Violet,
    background = LogicColors.BackgroundDark,
    surface = LogicColors.SurfaceDark,
    surfaceVariant = LogicColors.SurfaceVariantDark,
    error = LogicColors.Error,
    onPrimary = LogicColors.BackgroundDark, // texto oscuro sobre neón (contraste)
    onBackground = LogicColors.OnDark,
    onSurface = LogicColors.OnDark,
    onSurfaceVariant = LogicColors.OnDarkMuted,
)

private val LogicShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun LogicGamesTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
        shapes = LogicShapes,
        typography = LogicTypography,
        content = content,
    )
}
