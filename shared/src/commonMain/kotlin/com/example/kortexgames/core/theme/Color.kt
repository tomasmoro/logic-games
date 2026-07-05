package com.example.kortexgames.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta "vibrante y adictiva". Colores saturados de alto contraste con
 * acentos de feedback (verde acierto / rojo error) usados en toda la app.
 */
object LogicColors {
    // Marca / acentos principales
    val Violet = Color(0xFF7C4DFF)
    val VioletDark = Color(0xFF5E35D9)
    val Electric = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF3D8B)
    val Amber = Color(0xFFFFC107)
    val Lime = Color(0xFFB2FF59)

    // Feedback inmediato de juego
    val Success = Color(0xFF00E676)
    val Error = Color(0xFFFF1744)

    // Superficies (tema oscuro por defecto: más inmersivo para juegos)
    val BackgroundDark = Color(0xFF0E0B1E)
    val SurfaceDark = Color(0xFF1A1633)
    val SurfaceVariantDark = Color(0xFF262041)
    val OnDark = Color(0xFFF5F3FF)
    val OnDarkMuted = Color(0xFFB9B2E0)

    // Superficies (tema claro)
    val BackgroundLight = Color(0xFFF7F5FF)
    val SurfaceLight = Color(0xFFFFFFFF)
    val OnLight = Color(0xFF1A1633)
}

/** Degradados reutilizables para botones/fondos con gancho visual. */
object LogicGradients {
    val primary = listOf(LogicColors.Violet, LogicColors.Magenta)
    val energy = listOf(LogicColors.Electric, LogicColors.Violet)
    val reward = listOf(LogicColors.Amber, LogicColors.Magenta)
    val success = listOf(LogicColors.Lime, LogicColors.Success)
}
