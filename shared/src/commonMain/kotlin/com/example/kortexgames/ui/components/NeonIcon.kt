package com.example.kortexgames.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Iconos de la app: **vectoriales Material reales**, nunca emojis (requisito de
 * diseño). Se centralizan con nombres semánticos para desacoplar la UI del set
 * concreto y poder cambiarlos en un solo sitio. Se usa la variante `Rounded`,
 * coherente con el lenguaje "amigable y redondeado" de la app.
 */
object KortexIcons {
    val Home: ImageVector = Icons.Rounded.Home
    val Games: ImageVector = Icons.Rounded.SportsEsports
    val Profile: ImageVector = Icons.Rounded.Person
    val Streak: ImageVector = Icons.Rounded.LocalFireDepartment
    val Play: ImageVector = Icons.Rounded.PlayArrow
    val Settings: ImageVector = Icons.Rounded.Settings
    val ChevronRight: ImageVector = Icons.Rounded.ChevronRight
    val Dice: ImageVector = Icons.Rounded.Casino

    /** Trofeo: fin de partida / recompensa / logro. */
    val Trophy: ImageVector = Icons.Rounded.EmojiEvents

    /** Deshacer el último movimiento (acción de juego). */
    val Undo: ImageVector = Icons.AutoMirrored.Rounded.Undo

    /** Reiniciar el nivel actual (acción de juego). */
    val Refresh: ImageVector = Icons.Rounded.Refresh
}

/**
 * Icono con **halo neón**: dibuja un resplandor radial del color de acento detrás
 * del icono, tal como el mockup (iconos "encendidos" sobre fondo oscuro). El halo
 * es puramente estático aquí; las animaciones (latido, brillo) las aplica quien lo
 * usa vía [Modifier.pulse]/[Modifier.softGlow] si procede.
 *
 * @param tint color del icono y del resplandor.
 * @param size tamaño del glifo (el halo ocupa ~1.9x).
 * @param glow si false, dibuja el icono sin resplandor (p. ej. estado inactivo).
 */
@Composable
fun NeonIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    glow: Boolean = true,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (glow) {
            Box(
                modifier = Modifier
                    .size(size * 1.9f)
                    .background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.38f), Color.Transparent),
                        ),
                    ),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
