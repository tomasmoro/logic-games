package com.kortexgames.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.floor

/**
 * Fondo decorativo de **muro de ladrillos "neo-retro"** para pantallas de juego.
 * Dibuja un aparejo a soga (filas desplazadas media pieza, como un muro real) con
 * el color de acento a alfa muy baja sobre el fondo oscuro de la app: se percibe
 * como una textura ambiental, no como un patrón protagonista.
 *
 * Decisiones de diseño (CLAUDE.md §9.1 "superficie mayormente oscura, acento
 * escaso"):
 *  - Los ladrillos son un relleno tenue del acento; la "junta" es el propio fondo
 *    oscuro que asoma por el hueco entre piezas → da el look de mampostería sin
 *    pintar líneas extra.
 *  - Un **borde superior más claro** por ladrillo simula el bisel de píxel de una
 *    consola arcade (relieve barato pero reconocible).
 *  - Una **viñeta radial** oscurece los bordes de la pantalla para que la textura
 *    respire y el contenido central (la rejilla) siga mandando la atención.
 *
 * Por defecto es estático y sin estado: se pinta una vez y sirve de lienzo. Colócalo
 * como primer hijo de un `Box` a pantalla completa, debajo del contenido.
 *
 * **Foco de luz opcional** ([light]): simula que algo del primer plano —el logo de
 * la splash— ilumina el muro. La luz cae del centro hacia afuera (cuadráticamente),
 * subiendo la opacidad del ladrillo y tirándolo hacia el blanco, de modo que la
 * mampostería se "revela" cerca del foco y sigue en penumbra en los bordes. Se pasa
 * como **lambda** a propósito: al leerse dentro del `Canvas`, animarla invalida solo
 * la fase de dibujo y no recompone nada.
 *
 * @param accent color del ladrillo (por defecto morado Memoria). Se aplica a alfa
 *   baja internamente; pasa el color pleno de la categoría.
 * @param intensity 0f..1f multiplica la opacidad global para afinar cuán sutil se
 *   ve (1 = valor de diseño; baja para difuminarlo aún más).
 * @param lightCenter centro del foco en **fracciones** del tamaño (0..1), para que
 *   sea independiente de la resolución.
 * @param lightColor color hacia el que se tira el ladrillo iluminado (el del neón
 *   que ilumina). Blanco puro quemaría el muro: se mezcla parcialmente.
 * @param light 0f..1f intensidad del foco; 0 = comportamiento clásico sin luz.
 */
@Composable
fun ArcadeBrickBackground(
    modifier: Modifier = Modifier,
    accent: Color = LogicColors.Violet,
    intensity: Float = 1f,
    lightCenter: Offset = Offset(0.5f, 0.42f),
    lightColor: Color = LogicColors.NeonCyan,
    light: () -> Float = { 0f },
) {
    Canvas(modifier = modifier) {
        val lit = light().coerceIn(0f, 1f)
        val focus = Offset(size.width * lightCenter.x, size.height * lightCenter.y)
        // Alcance del foco: algo menor que la pantalla para que los bordes queden
        // claramente más oscuros que el centro (jerarquía de luz pedida por diseño).
        val lightRadius = size.maxDimension * 0.62f
        val brickW = 66.dp.toPx()
        val brickH = 30.dp.toPx()
        val gap = 3.dp.toPx()          // junta: deja ver el fondo oscuro
        val bevel = 2.dp.toPx()        // grosor del bisel claro superior

        // Alfas de diseño: deliberadamente bajas para que sea ambiente, no motivo.
        val faceA = 0.055f * intensity
        val faceAltA = 0.085f * intensity
        val bevelA = 0.10f * intensity

        val face = accent.copy(alpha = faceA)
        val faceAlt = accent.copy(alpha = faceAltA)
        val bevelColor = accent.copy(alpha = bevelA)

        var row = 0
        var y = 0f
        while (y < size.height) {
            // Aparejo a soga: filas impares corridas media pieza a la izquierda.
            val rowOffset = if (row % 2 == 0) 0f else -brickW / 2f
            var x = rowOffset
            while (x < size.width) {
                val col = floor((x - rowOffset) / brickW).toInt()
                // Variación pseudo-aleatoria estable: algunos ladrillos algo más
                // vivos rompen la uniformidad plana del muro.
                val livelier = (row * 31 + col * 17) % 5 == 0

                val left = x + gap / 2f
                val top = y + gap / 2f
                val w = brickW - gap
                val h = brickH - gap

                // Caída de luz por ladrillo: 1 en el foco → 0 en el borde del alcance.
                // Se eleva al cuadrado para que el gradiente sea marcado (el centro
                // manda) en vez de una subida plana por toda la pantalla.
                val glow = if (lit <= 0f) {
                    0f
                } else {
                    val d = (Offset(left + w / 2f, top + h / 2f) - focus).getDistance() / lightRadius
                    val falloff = (1f - d).coerceIn(0f, 1f)
                    lit * falloff * falloff
                }

                drawRect(
                    color = (if (livelier) faceAlt else face).illuminate(glow, lightColor),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                )
                // Bisel superior: fina franja más clara para el relieve de píxel.
                drawRect(
                    color = bevelColor.illuminate(glow, lightColor),
                    topLeft = Offset(left, top),
                    size = Size(w, bevel),
                )
                x += brickW
            }
            y += brickH
            row++
        }

        // Viñeta: oscurece bordes hacia el fondo para no competir con el contenido.
        // Con el foco encendido se centra en él y se refuerza (hasta 0.96): así el
        // contraste centro/borde crece con la luz en vez de aplanarse.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    LogicColors.BackgroundDark.copy(alpha = 0.9f + 0.06f * lit),
                ),
                center = if (lit > 0f) focus else Offset(size.width / 2f, size.height * 0.42f),
                radius = size.maxDimension * 0.62f,
            ),
        )
    }
}

/**
 * Mezcla un color de ladrillo hacia [lightColor] y le sube la opacidad según
 * [glow] (0..1). Es lo que convierte "muro de fondo" en "muro iluminado":
 *  - la opacidad se multiplica hasta ~4,5× → el ladrillo emerge de la penumbra;
 *  - el tono se tira solo un 45 % hacia la luz → conserva su identidad de color
 *    en vez de blanquearse (un muro blanco competiría con el logo).
 */
private fun Color.illuminate(glow: Float, lightColor: Color): Color {
    if (glow <= 0f) return this
    val tint = lerp(this, lightColor, glow * 0.45f)
    return tint.copy(alpha = (alpha * (1f + glow * 3.5f)).coerceAtMost(0.65f))
}
