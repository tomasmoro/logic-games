package com.example.kortexgames.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
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
 * Es puramente estático y sin estado: se pinta una vez y sirve de lienzo. Colócalo
 * como primer hijo de un `Box` a pantalla completa, debajo del contenido.
 *
 * @param accent color del ladrillo (por defecto morado Memoria). Se aplica a alfa
 *   baja internamente; pasa el color pleno de la categoría.
 * @param intensity 0f..1f multiplica la opacidad global para afinar cuán sutil se
 *   ve (1 = valor de diseño; baja para difuminarlo aún más).
 */
@Composable
fun ArcadeBrickBackground(
    modifier: Modifier = Modifier,
    accent: Color = LogicColors.Violet,
    intensity: Float = 1f,
) {
    Canvas(modifier = modifier) {
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

                drawRect(
                    color = if (livelier) faceAlt else face,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                )
                // Bisel superior: fina franja más clara para el relieve de píxel.
                drawRect(
                    color = bevelColor,
                    topLeft = Offset(left, top),
                    size = Size(w, bevel),
                )
                x += brickW
            }
            y += brickH
            row++
        }

        // Viñeta: oscurece bordes hacia el fondo para no competir con el contenido.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    LogicColors.BackgroundDark.copy(alpha = 0.9f),
                ),
                center = Offset(size.width / 2f, size.height * 0.42f),
                radius = size.maxDimension * 0.62f,
            ),
        )
    }
}
