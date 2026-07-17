package com.example.kortexgames.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta "Azul noche + Neón" (CLAUDE.md §9.2). Fondo profundo azul-negro para
 * concentración ("Lógica") y acentos neón saturados con halo para gamificación
 * ("Juego"). El neón es escaso a propósito: brilla porque es raro.
 */
object LogicColors {
    // --- Acentos neón / "Juego" ---------------------------------------------
    /** Verde neón: acción principal (PLAY NOW), progreso, acierto. */
    val NeonGreen = Color(0xFF25E17E)
    val NeonGreenDeep = Color(0xFF00B894)

    /** Cian eléctrico: energía, foco activo (navegación), inicio del anillo. */
    val NeonCyan = Color(0xFF00E5FF)
    val Electric = NeonCyan            // alias histórico usado en el código

    /** Morado neón: categoría Memoria y marca secundaria. */
    val Violet = Color(0xFF9D4EDD)
    val VioletDark = Color(0xFF7B2CBF)

    /** Azul neón: categoría Lógica. */
    val Blue = Color(0xFF3D9BFF)

    val Magenta = Color(0xFFFF3D8B)
    val Coral = Color(0xFFFF7A2F)      // Naranja coral: categoría Reflejos
    val StreakOrange = Color(0xFFFF7A3D) // Fuego de la racha
    val Amber = Color(0xFFFFC24B)      // Amarillo eléctrico (recompensa)
    val Lime = Color(0xFFB2FF59)       // Verde lima

    // --- Feedback de juego (semántico) --------------------------------------
    val Success = Color(0xFF25E17E)
    val Error = Color(0xFFFF4D5E)

    // --- Superficies (tema oscuro navy, por defecto) ------------------------
    val BackgroundDark = Color(0xFF0A0E1A)
    val SurfaceDark = Color(0xFF141B2E)
    val SurfaceVariantDark = Color(0xFF1E2740)
    val OnDark = Color(0xFFF2F5FF)
    val OnDarkMuted = Color(0xFF9AA3BE)
}

/**
 * Color representativo de cada categoría cognitiva. Da identidad visual estable a
 * las tarjetas del catálogo: el usuario asocia "morado = memoria", etc.
 *
 * Se centraliza aquí (UNA sola fuente de verdad); la enum `GameCategory` del
 * catálogo consume estos valores. Tonos neón bien separados en el círculo cromático
 * para distinguir las 11 categorías sin colisiones perceptibles.
 */
object CategoryPalette {
    val Memory = LogicColors.Violet                 // morado
    val Logic = LogicColors.Blue                    // azul
    val ProblemSolving = LogicColors.Amber          // ámbar
    val Reflexes = LogicColors.Coral                // naranja
    val MentalSpeed = LogicColors.NeonCyan          // cian
    val Attention = Color(0xFFFF5DA2)               // rosa
    val SpatialVision = Color(0xFF7C6CFF)           // índigo
    val MentalMath = LogicColors.NeonGreen          // verde
    val Language = LogicColors.Magenta              // magenta
    val CognitiveFlexibility = Color(0xFF3DE0D0)    // turquesa
    val PatternRecognition = LogicColors.Lime       // lima
}

/** Degradados reutilizables para botones/anillos/fondos con gancho visual. */
object LogicGradients {
    /** Acción principal (PLAY NOW): verde neón. */
    val play = listOf(LogicColors.NeonGreen, LogicColors.NeonGreenDeep)

    /** Anillo de progreso: cian → verde (como el mockup). */
    val ring = listOf(LogicColors.NeonCyan, LogicColors.NeonGreen)

    val primary = listOf(LogicColors.Violet, LogicColors.Magenta)
    val energy = listOf(LogicColors.NeonCyan, LogicColors.Blue)
    val reward = listOf(LogicColors.Amber, LogicColors.Coral)
    val success = listOf(LogicColors.Lime, LogicColors.Success)
}
