package com.kortexgames.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.ui.components.ArcadeBrickBackground
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.logo_kortex
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// --- Línea de tiempo (ms) ---------------------------------------------------
// La splash es un gesto de marca, no una espera: cada tramo está acotado y la
// suma (~2,4 s) es el techo del arranque salvo que los datos tarden más.

/** Titubeo del tubo: chispazos que no cuajan, como un fluorescente frío. */
private const val FLICKER_MS = 1150

/** Encendido definitivo: golpe de luz con sobre-impulso. */
private const val IGNITE_MS = 170

/** Reposo a plena luz: el segundo que pide el diseño antes de ceder a la Home. */
private const val HOLD_MS = 1000L

/**
 * Duración del reloj de las chispas (ms). **Debe** cubrir el retardo máximo de
 * aparición más la vida máxima de una chispa ([buildSparks]): si el reloj parase
 * antes, las chispas vivas se quedarían congeladas en pantalla el resto de la
 * splash (el dibujo es función del tiempo, y el tiempo dejaría de avanzar).
 */
private const val SPARKS_LIFE_MS = 1450f

/** Nº de chispas del estallido de encendido. */
private const val SPARK_COUNT = 54

/** Gravedad de las chispas, en fracción de la dimensión menor por segundo². */
private const val SPARK_GRAVITY = 2.2f

/**
 * Cuánto hacia atrás en el tiempo (s) se busca el inicio de la estela: la chispa se
 * dibuja desde donde estaba hace este instante hasta donde está ahora. Es lo que
 * gobierna el LARGO de la estela — cuanto mayor, más rastro. A 0.011 s la brasa
 * apenas deja un trazo corto en vez de un cometa.
 */
private const val TRAIL_LOOKBACK_S = 0.011f

/**
 * Colores de las chispas: **solo los dos del logo**, cian y magenta. Meter más
 * tonos las convertía en confeti genérico; con la paleta del propio cerebro se
 * leen como fragmentos de luz que suelta el tubo al prender.
 */
private val SparkColors = listOf(
    LogicColors.NeonCyan,
    LogicColors.Magenta,
)

/** Índices en [SparkColors]: el lado del logo del que sale la chispa. */
private const val CYAN_SPARK = 0
private const val MAGENTA_SPARK = 1

/** Color del tubo apagado: cristal azulado sin corriente, apenas visible. */
private val UnlitTube = Color(0xFF2A3350)

/**
 * Una chispa del encendido. Nace en un punto del contorno del logo y cae.
 *
 * @param originAngleRad ángulo de nacimiento sobre la elipse del logo.
 * @param originRadius radio de nacimiento (0..1 del semieje) — reparte las chispas
 *   por toda la silueta, no solo por el borde.
 * @param vx velocidad horizontal inicial (fracción de la dimensión menor / s).
 * @param vy velocidad vertical inicial; puede ser negativa (salta hacia arriba antes de caer).
 * @param delayMs retardo de aparición: escalona la lluvia para que no salga "de golpe".
 */
private data class Spark(
    val originAngleRad: Float,
    val originRadius: Float,
    val vx: Float,
    val vy: Float,
    val colorIndex: Int,
    val sizeDp: Float,
    val lifeMs: Float,
    val delayMs: Float,
)

/**
 * Pantalla de arranque de la app: el logo neón **prende como un tubo fluorescente**.
 *
 * Guion (y el porqué de cada tramo):
 *  1. **Apagado** — el logo se ve como cristal muerto ([UnlitTube]): el mismo dibujo,
 *     teñido plano, para que el encendido sea reconociblemente "la misma pieza".
 *  2. **Titubeo** ([FLICKER_MS]) — chispazos irregulares que no cuajan. Es lo que
 *     vende la metáfora: un neón real no enciende limpio.
 *  3. **Ignición** ([IGNITE_MS]) — golpe de luz con sobre-impulso, lluvia de chispas
 *     de colores que caen por gravedad, y el muro de ladrillos del fondo se ilumina
 *     **desde el centro hacia afuera** (foco de [ArcadeBrickBackground]).
 *  4. **Reposo** ([HOLD_MS]) — un segundo a plena luz. Aquí es donde se cobra la
 *     espera de datos ([awaitData]): si ya están listos, no cuesta nada.
 *  5. **Relevo** — se llama a [onFinished] con el logo **todavía encendido**: quien
 *     la muestra funde a la Home por encima. La splash nunca se apaga sola.
 *
 * Todo el brillo cuelga de un único `Animatable` (`lit`, 0..1) leído **dentro** de
 * los `Canvas`/`graphicsLayer`: la animación invalida solo la fase de dibujo, sin
 * recomponer el árbol en cada frame.
 *
 * @param awaitData suspende hasta que la app tenga cargado lo que necesita la Home.
 *   Se ejecuta durante el reposo, de modo que la carga se "esconde" tras la
 *   animación en vez de sumarse a ella.
 * @param onFinished se invoca al terminar el reposo, con el logo aún encendido; el
 *   llamador cambia entonces a Home o al login (idealmente con un fundido).
 */
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    awaitData: suspend () -> Unit = {},
    onFinished: () -> Unit,
) {
    // Brillo global del neón (0 = tubo muerto, 1 = a plena luz). Puede superar 1
    // momentáneamente en la ignición: el sobre-impulso es lo que la hace "golpear".
    val lit = remember { Animatable(0f) }

    // Reloj de las chispas: arranca en la ignición y muere solo (dibujo = f(t)).
    val sparkClock = remember { Animatable(0f) }
    var sparksLaunched by remember { mutableStateOf(false) }
    val sparks = remember { buildSparks() }

    LaunchedEffect(Unit) {
        // 1-2. El tubo intenta prender y no lo consigue: acaba en penumbra.
        lit.animateTo(
            targetValue = 0.10f,
            animationSpec = keyframes {
                durationMillis = FLICKER_MS
                // Pares de tiempos casi pegados = saltos de ~1 frame (chasquido
                // eléctrico). Rampas largas parecerían un fundido, no un neón.
                0f at 0 using LinearEasing
                0f at 120 using LinearEasing
                0.55f at 140 using LinearEasing      // primer chispazo, corto
                0.03f at 190 using LinearEasing
                0.03f at 330 using LinearEasing
                0.85f at 350 using LinearEasing      // segundo, más fuerte
                0.06f at 430 using LinearEasing
                0.30f at 470 using LinearEasing      // zumbido a media luz
                0.08f at 520 using LinearEasing
                0.08f at 700 using LinearEasing      // pausa: parece que no arranca
                0.95f at 730 using LinearEasing
                0.05f at 810 using LinearEasing
                0.45f at 860 using LinearEasing
                0.12f at 930 using LinearEasing
                0.10f at FLICKER_MS using LinearEasing
            },
        )

        // 3. Ignición: sobre-impulso a 1.25 y asentamiento. Las chispas salen aquí.
        sparksLaunched = true
        lit.animateTo(1.25f, tween(IGNITE_MS / 2, easing = LinearEasing))
        lit.animateTo(1f, tween(IGNITE_MS, easing = FastOutSlowInEasing))

        // 4. Reposo a plena luz + carga escondida tras él.
        delay(HOLD_MS)
        awaitData()

        // 5. Cede el paso SIN apagarse: el logo se queda encendido y es el fundido
        // cruzado del llamador el que lo lleva. Apagarlo antes daba un instante a
        // negro que rompía la continuidad hacia la Home.
        onFinished()
    }

    LaunchedEffect(sparksLaunched) {
        if (sparksLaunched) {
            sparkClock.animateTo(
                targetValue = SPARKS_LIFE_MS,
                animationSpec = tween(SPARKS_LIFE_MS.toInt(), easing = LinearEasing),
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
        contentAlignment = Alignment.Center,
    ) {
        // El logo manda el tamaño de todo lo demás (halo y chispas se derivan de él).
        val logoSize: Dp = min(maxWidth * 0.62f, 260.dp)

        // Fondo de ladrillos con foco centrado en el logo: la luz cae del centro
        // hacia afuera, tal y como la irradiaría un cartel de neón colgado del muro.
        ArcadeBrickBackground(
            modifier = Modifier.fillMaxSize(),
            accent = LogicColors.Violet,
            lightCenter = Offset(0.5f, 0.5f),
            lightColor = LogicColors.NeonCyan,
            light = { lit.value },
        )

        // Halo del aire: el resplandor que el tubo derrama alrededor. Dos paradas
        // (cian cerca, violeta lejos) porque el logo es bicolor y un halo de un solo
        // tono lo delataría como un círculo pegado detrás.
        Canvas(Modifier.fillMaxSize()) {
            val l = lit.value.coerceIn(0f, 1.25f)
            if (l <= 0.01f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = logoSize.toPx() * (1.5f + 0.25f * l)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        LogicColors.NeonCyan.copy(alpha = 0.22f * l),
                        LogicColors.Violet.copy(alpha = 0.12f * l),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        val logo = painterResource(Res.drawable.logo_kortex)

        // Capa "apagada": siempre presente. Es el cristal del tubo sin corriente;
        // se atenúa a medida que el neón real lo tapa.
        Image(
            painter = logo,
            contentDescription = "Kortex Games",
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(UnlitTube, BlendMode.SrcIn),
            modifier = Modifier
                .size(logoSize)
                .graphicsLayer { alpha = 0.85f - 0.55f * lit.value.coerceIn(0f, 1f) },
        )

        // Bloom: copia ampliada y difuminada por transparencia. Sustituye a un blur
        // real (que no rinde igual en ambas plataformas) y basta porque el propio
        // PNG ya trae su halo pintado.
        Image(
            painter = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(logoSize)
                .graphicsLayer {
                    val l = lit.value.coerceIn(0f, 1.25f)
                    alpha = 0.35f * l
                    scaleX = 1f + 0.10f * l
                    scaleY = 1f + 0.10f * l
                },
        )

        // Capa encendida: el logo con sus colores reales.
        Image(
            painter = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(logoSize)
                .graphicsLayer { alpha = lit.value.coerceIn(0f, 1f) },
        )

        // Destello de arranque: silueta blanca del propio logo, visible solo mientras
        // `lit` supera 1 (el sobre-impulso). Se hace con una copia teñida en vez de un
        // velo con `BlendMode.SrcAtop` porque el velo teñiría también todo lo ya
        // pintado debajo (fondo y halo comparten capa con el logo).
        Image(
            painter = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
            modifier = Modifier
                .size(logoSize)
                .graphicsLayer { alpha = ((lit.value - 1f) * 1.6f).coerceIn(0f, 1f) },
        )

        // Chispas: por encima de todo, cayendo desde la silueta del logo.
        if (sparksLaunched) {
            Canvas(Modifier.fillMaxSize()) {
                drawSparks(
                    sparks = sparks,
                    timeMs = sparkClock.value,
                    center = Offset(size.width / 2f, size.height / 2f),
                    logoRadiusPx = logoSize.toPx() / 2f,
                )
            }
        }
    }
}

/**
 * Genera la lluvia de chispas con [Random] sembrado: el arranque se ve **igual en
 * cada apertura**, que es lo que se espera de una animación de marca (y además
 * facilita afinarla).
 */
private fun buildSparks(seed: Int = 7): List<Spark> {
    val random = Random(seed)
    return List(SPARK_COUNT) {
        // Nacen por toda la silueta (ángulo libre), pero la velocidad vertical apenas
        // sube y la gravedad manda: salen del tubo, se abren un poco y caen. No es una
        // corona de fuegos artificiales, es una lluvia de brasas.
        val angle = random.nextFloat() * 6.2831855f
        // El color lo decide el LADO del que sale, como en el logo: hemisferio
        // izquierdo cian, derecho magenta. Repartirlos al azar delataría que las
        // chispas no salen de verdad del dibujo. El jitter en la frontera evita una
        // línea de corte perfecta por el centro (donde el logo mezcla los dos tonos).
        val leaningLeft = cos(angle) + (random.nextFloat() - 0.5f) * 0.3f < 0f
        Spark(
            originAngleRad = angle,
            originRadius = 0.35f + random.nextFloat() * 0.65f,
            vx = (random.nextFloat() - 0.5f) * 0.55f,
            vy = -0.10f + random.nextFloat() * 0.45f,
            colorIndex = if (leaningLeft) CYAN_SPARK else MAGENTA_SPARK,
            sizeDp = 1.6f + random.nextFloat() * 2.4f,
            // Vida + retardo nunca superan SPARKS_LIFE_MS (ver allí el porqué), y la
            // lluvia se apaga sola antes de que la splash empiece a apagarse.
            lifeMs = 700f + random.nextFloat() * 500f,
            delayMs = random.nextFloat() * 220f,
        )
    }
}

/**
 * Dibuja la lluvia como **función pura del tiempo** [timeMs] (sin estado por frame):
 * cada chispa cae con `y = v·t + ½·g·t²`, deja una estela corta (su posición un
 * instante antes) y se apaga en el último tercio de su vida.
 */
private fun DrawScope.drawSparks(
    sparks: List<Spark>,
    timeMs: Float,
    center: Offset,
    logoRadiusPx: Float,
) {
    val minDim = min(size.width, size.height)
    for (spark in sparks) {
        val local = timeMs - spark.delayMs
        if (local < 0f || local > spark.lifeMs) continue
        val t = local / 1000f                        // segundos, para leer la física
        val p = local / spark.lifeMs                 // 0..1 dentro de su vida

        // Nacimiento repartido por la silueta del logo (elipse ligeramente ancha).
        val ox = center.x + cos(spark.originAngleRad) * logoRadiusPx * spark.originRadius * 1.05f
        val oy = center.y + sin(spark.originAngleRad) * logoRadiusPx * spark.originRadius * 0.95f

        fun positionAt(seconds: Float) = Offset(
            ox + spark.vx * minDim * seconds,
            oy + (spark.vy * minDim * seconds) + 0.5f * SPARK_GRAVITY * minDim * seconds * seconds,
        )

        val position = positionAt(t)
        val trail = positionAt((t - TRAIL_LOOKBACK_S).coerceAtLeast(0f))

        // Aparece de golpe (es una chispa) y se apaga en el último tercio.
        val fade = if (p < 0.66f) 1f else 1f - (p - 0.66f) / 0.34f
        val color = SparkColors[spark.colorIndex]
        val r = spark.sizeDp.dp.toPx()

        drawLine(
            color = color.copy(alpha = 0.35f * fade),
            start = trail,
            end = position,
            strokeWidth = r * 0.9f,
            cap = StrokeCap.Round,
        )
        drawCircle(color.copy(alpha = 0.25f * fade), radius = r * 2.4f, center = position)
        drawCircle(color.copy(alpha = fade), radius = r, center = position)
        drawCircle(Color.White.copy(alpha = 0.75f * fade * fade), radius = r * 0.45f, center = position)
    }
}
