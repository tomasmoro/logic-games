package com.example.kortexgames.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Paleta neón de los fuegos artificiales (todos de [LogicColors], §9.2). */
private val FireworkColors: List<Color> = listOf(
    LogicColors.NeonCyan,
    LogicColors.NeonGreen,
    LogicColors.Magenta,
    LogicColors.Amber,
    LogicColors.Violet,
    LogicColors.Coral,
)

/** Vida de un estallido (ms): de la ignición al desvanecimiento total de las chispas. */
private const val BURST_LIFETIME_MS = 1050f

/** Separación temporal media entre estallidos (ms); se le añade jitter por estallido. */
private const val BURST_STAGGER_MS = 300f

/** Caída (fracción de la dimensión menor) que arrastra a las chispas al final de su vida. */
private const val GRAVITY_FRAC = 0.24f

/** Una chispa de un estallido: sale del centro en [angleRad] a [speed] (fracción de pantalla). */
private data class FireworkSpark(
    val angleRad: Float,
    val speed: Float,
    val colorIndex: Int,
    val sizeDp: Float,
)

/** Un estallido: nace en (`cx`,`cy`) (fracciones 0..1 de la pantalla) en el instante [startMs]. */
private data class FireworkBurst(
    val startMs: Float,
    val cx: Float,
    val cy: Float,
    val sparks: List<FireworkSpark>,
)

/**
 * Capa de **fuegos artificiales** neón para celebrar un hito (p. ej. un nuevo récord).
 * Es puramente visual y **finita**: dispara [burstCount] estallidos escalonados, cada
 * uno una corona de chispas que salen rápido, frenan (easing) y caen con gravedad
 * mientras se desvanecen; al terminar el último, la capa deja de dibujar nada.
 *
 * El "reloj" lo lleva un único [Animatable] lineal, de modo que el dibujo es una función
 * pura del tiempo (sin estado por-frame). En paralelo, un `LaunchedEffect` invoca
 * [onBurst] en el instante de ignición de cada estallido: así el llamador sincroniza el
 * sonido/háptica arcade con cada explosión sin acoplar el audio a esta capa.
 *
 * Se usa deliberadamente puntual (una celebración), no en bucle, para no romper la regla
 * de "un solo bucle de atención" del resto de la app (§9.4): estalla, celebra y se calma.
 *
 * @param colors paleta de las chispas (neón por defecto).
 * @param burstCount número de estallidos de la secuencia.
 * @param seed semilla del patrón (posiciones/ángulos); fija ⇒ celebración reproducible.
 * @param onBurst se invoca con el índice (0-based) de cada estallido al encenderse.
 */
@Composable
fun FireworksOverlay(
    modifier: Modifier = Modifier,
    colors: List<Color> = FireworkColors,
    burstCount: Int = 6,
    seed: Int = 0,
    onBurst: ((index: Int) -> Unit)? = null,
) {
    val bursts = remember(burstCount, seed) { buildBursts(burstCount, colors.size, seed) }
    val totalMs = remember(bursts) { bursts.maxOf { it.startMs } + BURST_LIFETIME_MS }

    val clock = remember { Animatable(0f) }
    LaunchedEffect(bursts) {
        clock.snapTo(0f)
        clock.animateTo(totalMs, tween(durationMillis = totalMs.toInt(), easing = LinearEasing))
    }

    // Línea de tiempo de ignición: espera hasta cada estallido y avisa (para sonido/háptica).
    LaunchedEffect(bursts) {
        var prevMs = 0f
        bursts.forEachIndexed { index, burst ->
            val wait = (burst.startMs - prevMs).toLong().coerceAtLeast(0L)
            delay(wait)
            onBurst?.invoke(index)
            prevMs = burst.startMs
        }
    }

    Canvas(modifier = modifier) {
        val t = clock.value
        val minDim = min(size.width, size.height)

        for (burst in bursts) {
            val local = t - burst.startMs
            if (local < 0f || local > BURST_LIFETIME_MS) continue
            val p = local / BURST_LIFETIME_MS // 0..1 dentro de la vida del estallido
            val cx = burst.cx * size.width
            val cy = burst.cy * size.height

            // Destello de ignición: un flash blanco breve que crece y se apaga rápido.
            if (p < 0.14f) {
                val flash = 1f - p / 0.14f
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f * flash),
                    radius = minDim * 0.02f * (1f + p * 4f),
                    center = Offset(cx, cy),
                )
            }

            // Expansión con easeOutCubic (rápido al salir, frena al final) + caída y fundido.
            val expand = 1f - (1f - p) * (1f - p) * (1f - p)
            val fade = 1f - p
            val gravity = GRAVITY_FRAC * minDim * p * p

            for (spark in burst.sparks) {
                val dist = spark.speed * minDim * expand
                val x = cx + cos(spark.angleRad) * dist
                val y = cy + sin(spark.angleRad) * dist + gravity
                val center = Offset(x, y)
                val color = colors[spark.colorIndex]
                val r = spark.sizeDp.dp.toPx()

                // Halo translúcido + núcleo de color + corazón blanco (aspecto de brasa neón).
                drawCircle(color.copy(alpha = 0.22f * fade), radius = r * 2.6f, center = center)
                drawCircle(color.copy(alpha = fade), radius = r, center = center)
                drawCircle(Color.White.copy(alpha = 0.7f * fade * fade), radius = r * 0.5f, center = center)
            }
        }
    }
}

/**
 * Construye la secuencia de estallidos con [Random] sembrado (patrón reproducible).
 * Los estallidos nacen en la mitad superior de la pantalla (como en el cielo) y cada uno
 * es una corona de chispas repartidas por todo el círculo con jitter de ángulo/velocidad.
 */
private fun buildBursts(count: Int, paletteSize: Int, seed: Int): List<FireworkBurst> {
    val random = Random(seed)
    return List(count) { i ->
        val sparkCount = 20 + random.nextInt(12) // 20..31 chispas
        val baseColor = random.nextInt(paletteSize)
        val twoTone = random.nextFloat() < 0.35f // algunos estallidos mezclan dos colores
        val altColor = (baseColor + 1 + random.nextInt(paletteSize - 1)) % paletteSize

        val sparks = List(sparkCount) { s ->
            // Reparto uniforme del círculo + jitter → corona irregular natural.
            val angle = (s / sparkCount.toFloat()) * (2.0 * PI).toFloat() +
                (random.nextFloat() - 0.5f) * 0.35f
            FireworkSpark(
                angleRad = angle,
                speed = 0.12f + random.nextFloat() * 0.20f, // 0.12..0.32 de la dimensión menor
                colorIndex = if (twoTone && s % 2 == 0) altColor else baseColor,
                sizeDp = 2.5f + random.nextFloat() * 2.5f,
            )
        }
        FireworkBurst(
            startMs = i * BURST_STAGGER_MS + random.nextFloat() * 120f,
            cx = 0.2f + random.nextFloat() * 0.6f, // 0.2..0.8 del ancho
            cy = 0.18f + random.nextFloat() * 0.34f, // 0.18..0.52 del alto (mitad superior)
            sparks = sparks,
        )
    }
}
