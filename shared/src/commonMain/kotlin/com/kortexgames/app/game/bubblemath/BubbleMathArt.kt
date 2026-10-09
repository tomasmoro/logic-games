package com.kortexgames.app.game.bubblemath

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * # Burbujas de Cálculo — arte procedural
 *
 * Funciones puras de `DrawScope` con lo que se dibuja a mano en el juego: la burbuja de jabón, su
 * estallido y el destello de borde. No guardan estado ni conocen las reglas: reciben geometría,
 * color y el reloj, así que la pantalla las anima leyendo el tiempo **solo en fase de dibujo**
 * (redibuja sin recomponer).
 *
 * Viven aparte de `BubbleMathScreen` para que la pantalla siga siendo "qué hay y dónde" y el
 * aspecto se pueda afinar aquí sin tocar la composición.
 */

/** 2π. */
private const val TAU = 2f * PI.toFloat()

/** Pseudoaleatorio determinista 0..1: mismo [n] → mismo valor en todos los frames (sin estado). */
private fun artHash(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/** Cuánto se achata la burbuja al respirar (fracción del radio). Pequeño: debe leerse, no marear. */
private const val WOBBLE_AMOUNT = 0.035f

/** Velocidad del achatado (rad/s). */
private const val WOBBLE_SPEED = 2.4f

/** Radio del reflejo especular respecto al de la burbuja: casi en el borde, lejos del texto. */
private const val GLOSS_RADIUS = 0.86f

/** Opacidad del reflejo principal. Baja: es brillo de cristal, no un trazo que compita con la cuenta. */
private const val GLOSS_ALPHA = 0.34f

/** Burbujitas satélite por burbuja. Pocas: con tres o cuatro pompas en pantalla ya suman. */
private const val SATELLITE_COUNT = 4

/**
 * Una **burbuja de jabón** de neón que ocupa todo el `DrawScope`.
 *
 * Antes era un aro de neón hueco y rígido: se leía como un botón redondo, no como algo que flota.
 * Lo que la convierte en burbuja son cuatro cosas, todas baratas:
 *  - **película**: el cristal es casi transparente en el centro y se densifica hacia el borde,
 *    como una pompa real (y deja el centro limpio para el texto);
 *  - **respiración**: se achata y se estira conservando el volumen ([WOBBLE_AMOUNT]), con una fase
 *    propia por burbuja para que no latan todas a la vez;
 *  - **reflejos**: un arco especular tenue arriba a la izquierda y su eco abajo a la derecha;
 *  - **irisación**: un arco de un segundo color sobre el borde, que gira despacio;
 *  - **satélites**: burbujitas que orbitan a su alrededor ([drawSatelliteBubbles]).
 *
 * El borde conserva la receta de capas del neón de la app (halo ancho → intermedio → trazo nítido
 * → núcleo blanco, §9.7); no se usa `drawNeonBubble` porque aquí el aro se deforma con la burbuja.
 *
 * El color es decorativo: NUNCA depende de si la burbuja es la correcta.
 *
 * @param time segundos del reloj de la pantalla.
 * @param seed fase propia de la burbuja (p. ej. derivada de su id).
 */
fun DrawScope.drawSoapBubble(color: Color, time: Float, seed: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    // Margen para que el halo y el estirado quepan dentro de los límites del composable.
    val radius = size.minDimension / 2f - 7.dp.toPx()
    val stroke = 2.6.dp.toPx()
    val wobble = sin(time * WOBBLE_SPEED + seed) * WOBBLE_AMOUNT

    scale(scaleX = 1f + wobble, scaleY = 1f - wobble, pivot = center) {
        // Fondo oscuro translúcido: sin él la operación se mezcla con el skyline y con otras
        // burbujas que pasen por detrás.
        drawCircle(LogicColors.BackgroundDark.copy(alpha = 0.55f), radius, center)
        // Película: transparente en el centro, densa en el borde.
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = 0.06f),
                0.62f to color.copy(alpha = 0.14f),
                0.88f to color.copy(alpha = 0.34f),
                1f to color.copy(alpha = 0.62f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        // Luz que entra por arriba: da volumen de esfera.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(center.x - radius * 0.30f, center.y - radius * 0.42f),
                radius = radius * 0.95f,
            ),
            radius = radius,
            center = center,
        )

        // Borde de neón.
        drawCircle(color.copy(alpha = 0.16f), radius, center, style = Stroke(stroke * 4.5f))
        drawCircle(color.copy(alpha = 0.40f), radius, center, style = Stroke(stroke * 2.1f))
        drawCircle(color, radius, center, style = Stroke(stroke))
        drawCircle(Color.White.copy(alpha = 0.50f), radius, center, style = Stroke(stroke * 0.4f))

        // Irisación: un tramo del borde vira a otro tono y gira despacio.
        val arcRadius = radius * 0.90f
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
        drawArc(
            color = lerp(color, LogicColors.NeonCyan, 0.65f).copy(alpha = 0.55f),
            startAngle = 15f + (time * 22f + seed * 57f) % 360f,
            sweepAngle = 62f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round),
        )

        // Reflejos especulares: el principal arriba-izquierda y su eco tenue enfrente.
        // Van PEGADOS al borde y a media opacidad a propósito: más adentro o más blancos se
        // leían como un trazo más de la operación (un "1" o un "–" fantasma junto a la cuenta).
        val glossRadius = radius * GLOSS_RADIUS
        val glossTopLeft = Offset(center.x - glossRadius, center.y - glossRadius)
        val glossSize = Size(glossRadius * 2f, glossRadius * 2f)
        drawArc(
            color = Color.White.copy(alpha = GLOSS_ALPHA),
            startAngle = 204f,
            sweepAngle = 44f,
            useCenter = false,
            topLeft = glossTopLeft,
            size = glossSize,
            style = Stroke(width = stroke * 0.85f, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White.copy(alpha = GLOSS_ALPHA * 0.45f),
            startAngle = 30f,
            sweepAngle = 30f,
            useCenter = false,
            topLeft = glossTopLeft,
            size = glossSize,
            style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round),
        )
    }

    // Burbujitas satélite, fuera del achatado para que no se deformen con la grande.
    drawSatelliteBubbles(center, radius, color, time, seed)
}

/**
 * Burbujitas que acompañan a una burbuja grande: orbitan despacio alrededor de ella, cada una
 * con su tamaño, su distancia y un leve cabeceo propio.
 *
 * Son lo que termina de vender "esto es espuma que flota": una pompa sola parece una canica;
 * rodeada de pequeñas, parece recién soplada. Se dibujan **fuera** del borde de la grande y sin
 * relleno opaco, de modo que nunca tapan la operación, y son deterministas por [seed] (se
 * recalculan por frame, sin estado).
 */
private fun DrawScope.drawSatelliteBubbles(center: Offset, radius: Float, color: Color, time: Float, seed: Float) {
    val key = (seed * 31f).toInt()
    for (i in 0 until SATELLITE_COUNT) {
        val h1 = artHash(key + i * 7)
        val h2 = artHash(key + i * 13 + 5)
        // Reparto alrededor + deriva lenta; sentido alterno para que no giren en bloque.
        val direction = if (i % 2 == 0) 1f else -1f
        val angle = (i + h1 * 0.7f) * (TAU / SATELLITE_COUNT) + direction * time * (0.22f + 0.18f * h2) + seed
        val distance = radius * (1.20f + 0.20f * h2 + 0.05f * sin(time * 1.7f + i * 2.1f + seed))
        val position = Offset(center.x + cos(angle) * distance, center.y + sin(angle) * distance)
        val size = radius * (0.075f + 0.075f * h1)
        drawCircle(color.copy(alpha = 0.16f), size * 1.7f, position)
        drawCircle(color.copy(alpha = 0.14f), size, position)
        drawCircle(color.copy(alpha = 0.85f), size, position, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = size * 0.22f,
            center = Offset(position.x - size * 0.35f, position.y - size * 0.35f),
        )
    }
}

/** Gotas que suelta una burbuja al reventar bien / al fallar. */
private const val POP_DROPS_SUCCESS = 14
private const val POP_DROPS_FAIL = 6

/**
 * **Estallido** de una burbuja: la película se rompe en un anillo doble que se abre, un fogonazo
 * corto y gotas que salen despedidas, frenan y caen un poco por su peso.
 *
 * Complementa (no sustituye) a las chispas de la pantalla: aquellas son luz; esto es la burbuja
 * deshaciéndose. Las gotas son deterministas por [seed]: se recalculan por frame a partir del
 * avance, sin guardar una partícula por gota.
 *
 * @param center punto donde reventó.
 * @param radius radio que tenía la burbuja.
 * @param progress avance 0..1; fuera de ese rango no dibuja nada.
 * @param success acierto (muchas gotas, del color de la burbuja) o fallo (pocas, rojas).
 */
fun DrawScope.drawBubblePop(
    center: Offset,
    radius: Float,
    color: Color,
    progress: Float,
    success: Boolean,
    seed: Int,
) {
    if (progress <= 0f || progress >= 1f) return
    val fade = 1f - progress
    // Sale rápido y frena, como una película que se rasga.
    val ease = 1f - fade * fade * fade

    // Fogonazo del primer cuarto.
    val flash = (1f - progress * 4f).coerceAtLeast(0f)
    if (flash > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.75f * flash), color.copy(alpha = 0.30f * flash), Color.Transparent),
                center = center,
                radius = radius * 1.4f,
            ),
            radius = radius * 1.4f,
            center = center,
        )
    }

    // Película rota: dos anillos que se abren a ritmos distintos y adelgazan.
    val reach = radius * if (success) 1.5f else 0.8f
    drawCircle(
        color = color.copy(alpha = 0.70f * fade),
        radius = radius + ease * reach,
        center = center,
        style = Stroke(width = 3.dp.toPx() * fade + 0.5f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.45f * fade * fade),
        radius = radius * 0.7f + ease * reach * 0.6f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx() * fade + 0.5f),
    )

    // Gotas.
    val drops = if (success) POP_DROPS_SUCCESS else POP_DROPS_FAIL
    val gravity = radius * 0.9f * progress * progress
    for (i in 0 until drops) {
        val angle = (i + artHash(seed * 13 + i) * 0.8f) * (TAU / drops)
        val distance = radius * (0.9f + 1.5f * artHash(seed * 7 + i)) * ease * (if (success) 1f else 0.6f)
        val position = Offset(
            x = center.x + cos(angle) * distance,
            y = center.y + sin(angle) * distance + gravity,
        )
        val size = radius * (0.05f + 0.07f * artHash(seed * 3 + i)) * (0.5f + 0.5f * fade)
        drawCircle(color.copy(alpha = 0.30f * fade), size * 2.2f, position)
        drawCircle(lerp(color, Color.White, 0.35f).copy(alpha = fade), size, position)
    }
}
