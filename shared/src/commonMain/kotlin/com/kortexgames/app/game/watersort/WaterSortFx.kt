package com.kortexgames.app.game.watersort

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * # Efectos ambientales y de celebración de "Ordena las Pociones"
 *
 * Reloj de animación compartido, motas flotantes del "laboratorio" y estallidos de
 * chispas. Igual que el arte del frasco, todo es función del tiempo y de datos
 * deterministas (sin estado mutable por partícula): un estallido es solo "dónde y
 * cuándo nació", y su aspecto en cada frame se deriva de `time - start`.
 */

/**
 * Reloj de animación en **segundos** para todo lo que se mueve "solo" (olas,
 * burbujas, motas, chispas). Un único reloj para toda la pantalla, en vez de un
 * `rememberInfiniteTransition` por frasco, por dos motivos:
 *  - se lee **solo en la fase de dibujo** (dentro de los `Canvas`/`graphicsLayer`),
 *    así que avanzar el tiempo redibuja pero no recompone nada;
 *  - al pausar ([running] = false) se congela todo a la vez sin tocar cada efecto.
 */
@Composable
internal fun rememberPotionClock(running: Boolean): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Tope por frame: si la app vuelve de segundo plano, no "salta" el tiempo
            // acumulado (las chispas en curso desaparecerían de golpe).
            seconds.floatValue += ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
        }
    }
    return seconds
}

/**
 * Ambiente de laboratorio sobre el muro: un foco de luz tenue tras la estantería y
 * motas de color que suben despacio y titilan. Deliberadamente sutil (§9.1: el
 * acento es escaso) — da vida al fondo sin competir con los frascos.
 *
 * @param clock reloj compartido (ver [rememberPotionClock]).
 * @param accent color del foco de luz (el de la categoría).
 */
@Composable
internal fun PotionLabAmbience(clock: State<Float>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val time = clock.value
        val focus = Offset(size.width / 2f, size.height * 0.50f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                center = focus,
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = focus,
        )
        for (j in 0 until MOTE_COUNT) {
            val k = j * 13 + 5
            val speed = 0.012f + 0.022f * hash01(k)
            val f = time * speed + hash01(k + 1)
            val rise = f - kotlin.math.floor(f)
            val x = hash01(k + 2) * size.width + sin(time * 0.35f + j) * 12.dp.toPx()
            val y = size.height * (1f - rise)
            val twinkle = 0.5f + 0.5f * sin(time * 1.6f + j * 2.1f)
            // Fundido en los extremos del recorrido: nacen y mueren sin "pop".
            val edgeFade = sin(rise * PI.toFloat())
            val color = PotionColors[j % PotionColors.size]
            drawCircle(
                color = color.copy(alpha = (0.10f + 0.28f * twinkle) * edgeFade),
                radius = (1.dp + 1.4.dp * hash01(k + 3)).toPx(),
                center = Offset(x, y),
            )
        }
    }
}

private const val MOTE_COUNT = 22

/**
 * Un estallido de chispas de celebración (frasco completado / nivel resuelto).
 *
 * @property start instante de nacimiento en el reloj compartido (segundos). Puede
 *   estar en el futuro: así se escalonan varios estallidos sin corrutinas.
 * @property center centro en coordenadas del `Canvas` que lo pinta.
 * @property seed semilla que fija ángulos/tamaños de sus chispas.
 */
internal data class PotionBurst(val start: Float, val center: Offset, val color: Color, val seed: Int)

/** Vida de un estallido en segundos; pasado ese tiempo ya no pinta nada. */
internal const val BURST_LIFETIME_S = 0.95f

private const val SPARKS_PER_BURST = 16

/**
 * Pinta los [bursts] vivos en el instante [time]: un anillo que se expande y
 * chispas (estrellas de 4 puntas y puntos) que salen despedidas, frenan y caen.
 */
internal fun DrawScope.drawPotionBursts(bursts: List<PotionBurst>, time: Float) {
    bursts.forEach { burst ->
        val f = (time - burst.start) / BURST_LIFETIME_S
        if (f <= 0f || f >= 1f) return@forEach

        // Anillo de choque: se abre rápido y se desvanece en el primer tercio.
        val ringF = (f * 2.2f).coerceAtMost(1f)
        val ringEase = 1f - (1f - ringF) * (1f - ringF)
        drawCircle(
            color = burst.color.copy(alpha = 0.7f * (1f - ringF)),
            radius = 46.dp.toPx() * ringEase,
            center = burst.center,
            style = Stroke(width = 2.5.dp.toPx() * (1f - ringF) + 0.5f),
        )

        // Frenado cúbico: salen rápido y se detienen; la gravedad las arrastra al final.
        val ease = 1f - (1f - f) * (1f - f) * (1f - f)
        for (k in 0 until SPARKS_PER_BURST) {
            val n = burst.seed * 97 + k * 11
            val angle = 2f * PI.toFloat() * (k + hash01(n) * 0.8f) / SPARKS_PER_BURST
            val speed = (34.dp + 62.dp * hash01(n + 1)).toPx()
            val pos = Offset(
                burst.center.x + cos(angle) * speed * ease,
                burst.center.y + sin(angle) * speed * ease + 46.dp.toPx() * f * f,
            )
            val size = (2.4.dp + 3.2.dp * hash01(n + 2)).toPx() * (1f - f * 0.8f)
            val color = (if (k % 3 == 0) Color.White else burst.color).copy(alpha = 1f - f * f)
            if (k % 2 == 0) {
                // Estrella de 4 puntas: dos trazos cruzados, gira mientras vuela.
                val a = angle + f * 3f
                val dx = cos(a) * size
                val dy = sin(a) * size
                val w = 1.5.dp.toPx()
                drawLine(color, Offset(pos.x - dx, pos.y - dy), Offset(pos.x + dx, pos.y + dy), w, StrokeCap.Round)
                drawLine(color, Offset(pos.x + dy, pos.y - dx), Offset(pos.x - dy, pos.y + dx), w, StrokeCap.Round)
            } else {
                drawCircle(color, size * 0.5f, pos)
            }
        }
    }
}
