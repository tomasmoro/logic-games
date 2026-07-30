package com.kortexgames.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kortexgames.app.core.theme.LogicColors

/**
 * Fondo espacial decorativo para pantallas arcade.
 *
 * Mantiene la estética de la app (base oscura + acentos neón): primero pinta un
 * degradado profundo, luego estrellas tenues y dos planetas con glow suave para dar
 * atmósfera sin competir con el contenido jugable.
 */
@Composable
fun SpaceBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    LogicColors.BackgroundDark,
                    Color(0xFF090F22),
                    Color(0xFF0E1733),
                ),
            ),
        )

        drawStars()

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    LogicColors.BackgroundDark.copy(alpha = 0.74f),
                ),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = size.maxDimension * 0.72f,
            ),
        )
    }
}

/** Estrellas pseudoaleatorias deterministas para evitar parpadeos entre recomposiciones. */
private fun DrawScope.drawStars() {
    val stars = 150
    repeat(stars) { index ->
        val x = hash(index * 97 + 11) * size.width
        val y = hash(index * 53 + 29) * size.height
        val radius = (0.8f + hash(index * 71 + 7) * 2.1f)
        val alpha = 0.16f + hash(index * 13 + 5) * 0.58f
        val tint = if (index % 9 == 0) LogicColors.NeonCyan else LogicColors.OnDark
        drawCircle(
            color = tint.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y),
        )
    }
}

/** Planeta simple con degradado radial + halo exterior, suficiente para lectura temática. */
private fun DrawScope.drawPlanet(
    center: Offset,
    radius: Float,
    core: Color,
    rim: Color = Color.Transparent,
    glowAlpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(core, rim),
            center = center - Offset(radius * 0.26f, radius * 0.22f),
            radius = radius * 1.15f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = core.copy(alpha = glowAlpha),
        radius = radius * 1.9f,
        center = center,
    )
}

/** Hash entero->[0f,1f) estable, útil para patrones visuales sin estado global. */
private fun hash(input: Int): Float {
    var x = input * 374761393 + 668265263
    x = (x xor (x shr 13)) * 1274126177
    x = x xor (x shr 16)
    return (x and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
}

