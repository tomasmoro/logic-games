package com.kortexgames.app.game.quantummerge

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * # Arte de Quantum Merge
 *
 * Capas de dibujo del reactor y de las esferas, separadas de la pantalla para que
 * `QuantumMergeScreen` solo orqueste estado, gestos y HUD. Son funciones puras de `DrawScope`.
 *
 * ## Idea visual: canicas de energía en una cámara de vidrio
 *
 * Las esferas son **canicas macizas con luz propia**: cuerpo con volumen (la luz les llega de
 * arriba a la izquierda), aro de neón, brillo especular y un núcleo. Antes eran discos
 * translúcidos de plasma; apiladas se fundían unas con otras y costaba leer dónde acababa cada
 * una, que es justo lo que este juego obliga a mirar.
 *
 * Cada tier lleva además una **marca de núcleo** —tantos puntos en anillo como su posición en la
 * escala— para que se reconozca por algo más que el color: la escala reutiliza el cian en dos
 * tiers y, en una pila apretada, el tamaño relativo engaña.
 *
 * El contenedor es una cámara con paredes de tubo de neón tenue. Sigue la §9.7 de `CLAUDE.md`
 * (un marco intenso alrededor de un tablero lleno de objetos brillantes competiría con ellos):
 * el neón de la pared es fino y de alfa contenida, y solo se enciende de verdad —en rojo— cuando
 * hay peligro de desbordamiento, que es cuando informa de algo.
 */

/** Grosor de las paredes del contenedor como fracción del lado menor del tablero. */
private const val WALL_WIDTH_FACTOR = 0.012f

/**
 * Velocidad (unidades de mundo/s) a la que el squash-stretch de [drawEnergySphere] satura.
 * Deliberadamente moderada —muy por debajo del `MAX_SPEED` del motor— para que el efecto ya se
 * note en una caída normal y no dependa de picos de velocidad raros de alcanzar.
 */
private const val GEL_STRETCH_REF_SPEED = 200f

/** Tope del estiramiento gelatinoso: 12 % de alargamiento máximo, "mínimamente" gelatinoso. */
private const val GEL_MAX_STRETCH = 0.12f

/** Por debajo de esta velocidad no se orienta el estiramiento: evita que el ángulo tiemble en reposo. */
private const val GEL_MIN_SPEED_FOR_ANGLE = 1f

/** Motas de energía que flotan dentro de la cámara. Pocas y tenues: son ambiente, no contenido. */
private const val MOTE_COUNT = 9

/** Chispas que suelta un destello de fusión. */
private const val FLASH_SPARKS = 10

/** Pseudoaleatorio determinista 0..1 (sin estado): mismo [n] → mismo valor en cada frame. */
private fun hash01(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/**
 * Traduce el acento semántico del tier al token de color del sistema de diseño.
 *
 * El mapa vive en la UI (y no en el `enum` de dominio) igual que en Bloques Neón: el motor de física
 * no conoce `Color`, y así el sistema de diseño mantiene UNA sola fuente de color (§9.2).
 */
internal fun TierAccent.color(): Color = when (this) {
    TierAccent.CYAN -> LogicColors.NeonCyan
    TierAccent.GREEN -> LogicColors.NeonGreen
    TierAccent.LIME -> LogicColors.Lime
    TierAccent.AMBER -> LogicColors.Amber
    TierAccent.CORAL -> LogicColors.Coral
    TierAccent.MAGENTA -> LogicColors.Magenta
    TierAccent.VIOLET -> LogicColors.Violet
    TierAccent.BLUE -> LogicColors.Blue
    // El "blanco incandescente" del tier máximo es el blanco de la paleta, no un hex suelto.
    TierAccent.WHITE_HOT -> LogicColors.OnDark
}

/**
 * La cámara del reactor: interior con profundidad, **tres** paredes de neón (izquierda, derecha y
 * suelo) y unas motas de energía que suben despacio.
 *
 * Que el borde superior no exista no es un olvido: la caja está abierta por arriba, exactamente
 * como el AABB de tres lados que resuelve el motor, y dejarlo abierto comunica al jugador por dónde
 * puede desbordar. Cuando hay peligro, la boca se tiñe con un degradado de [LogicColors.Error] y
 * las paredes viran a rojo.
 *
 * Las paredes se trazan SOBRE el borde exacto del lienzo: su cara interior cae en el límite que
 * usa el motor. Si se dibujaran por dentro, una esfera apoyada aparecería incrustada en la pared —
 * el motor la detiene cuando su borde llega a la coordenada 0, no cuando llega al muro pintado—.
 *
 * @param accent color de identidad del juego (paredes en reposo).
 * @param time reloj de animación en segundos (solo mueve las motas).
 */
internal fun DrawScope.drawReactor(accent: Color, dangerProgress: Float, glowPulse: Float, time: Float) {
    val wall = size.minDimension * WALL_WIDTH_FACTOR

    // Interior: más oscuro arriba (la boca, vacío) y con un baño del acento hacia el fondo, donde
    // se acumula la energía. Da profundidad sin textura.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                LogicColors.BackgroundDark.copy(alpha = 0.55f),
                lerp(LogicColors.SurfaceDark, accent, 0.14f).copy(alpha = 0.92f),
            ),
        ),
        cornerRadius = CornerRadius(wall * 2f),
    )

    // Motas de energía: suben muy despacio y titilan. Deliberadamente tenues y pocas (el usuario
    // pidió ambientes sutiles): dan vida a la cámara vacía sin distraer del apilado.
    for (j in 0 until MOTE_COUNT) {
        val k = j * 17 + 3
        val f = time * (0.018f + 0.02f * hash01(k)) + hash01(k + 1)
        val rise = f - floor(f)
        val x = (0.08f + 0.84f * hash01(k + 2)) * size.width + sin(time * 0.5f + j) * wall * 2f
        val twinkle = 0.5f + 0.5f * sin(time * 1.3f + j * 2.3f)
        drawCircle(
            color = accent.copy(alpha = (0.06f + 0.12f * twinkle) * sin(rise * PI.toFloat())),
            radius = wall * (0.5f + 0.6f * hash01(k + 3)),
            center = Offset(x, size.height * (1f - rise)),
        )
    }

    if (dangerProgress > 0f) {
        // Degradado de alarma que baja desde la boca: cuanto más cerca la derrota, más presente.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(LogicColors.Error.copy(alpha = 0.24f * dangerProgress * glowPulse), Color.Transparent),
                endY = size.height * 0.35f,
            ),
            size = Size(size.width, size.height * 0.35f),
        )
    }

    // Paredes: una "U" de tubo de neón (halo ancho → intermedio → nítido, §9.7), de alfa contenida.
    val wallColor = lerp(accent, LogicColors.Error, dangerProgress)
    val walls = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, size.height)
        lineTo(size.width, size.height)
        lineTo(size.width, 0f)
    }
    val lit = 0.75f + 0.25f * dangerProgress
    // Halo en varias capas finas que se solapan: con dos capas anchas se veían los escalones
    // como marcos concéntricos por dentro de la cámara.
    val haloLayers = 6
    for (i in 1..haloLayers) {
        drawPath(
            walls,
            wallColor.copy(alpha = 0.045f * lit),
            style = Stroke(wall * (1.5f + 4.5f * i / haloLayers), join = StrokeJoin.Round),
        )
    }
    drawPath(
        walls,
        lerp(wallColor, Color.White, 0.20f).copy(alpha = 0.85f),
        style = Stroke(wall * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Línea de peligro: trazo discontinuo a la altura de [QuantumDifficulty.dangerLineY].
 *
 * En reposo es casi invisible (una guía, no una alarma) y va **tomando el rojo de error** conforme
 * [dangerProgress] avanza —ganando además un halo—, de modo que el mismo elemento informa de la
 * regla y de su inminencia sin añadir un segundo indicador que compita por la atención.
 */
internal fun DrawScope.drawDangerLine(scale: Float, dangerLineY: Float, dangerProgress: Float, glowPulse: Float) {
    val y = dangerLineY * scale
    val color = lerp(LogicColors.OnDarkMuted, LogicColors.Error, dangerProgress)
    val alpha = 0.38f + 0.62f * dangerProgress * glowPulse
    val dash = size.minDimension * 0.022f

    if (dangerProgress > 0f) {
        drawLine(
            color = LogicColors.Error.copy(alpha = 0.22f * dangerProgress * glowPulse),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.minDimension * 0.03f,
        )
    }
    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = size.minDimension * 0.006f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.4f)),
    )
}

/**
 * Guía de puntería: marca la columna por la que caerá la esfera sostenida.
 *
 * Sin ella el jugador tiene que estimar la vertical desde la boca del contenedor, que es justo la
 * fricción que arruina una mecánica de precisión. Son dos capas: una **banda** tenue del ancho
 * exacto de la esfera (enseña qué hueco ocupa, no solo su eje) y la línea discontinua central.
 * Las dos a baja opacidad, para ayudar sin robar protagonismo a las esferas.
 */
internal fun DrawScope.drawAimGuide(sphere: Sphere, scale: Float, glowPulse: Float) {
    val x = sphere.x * scale
    val radius = sphere.radius * scale
    val top = (sphere.y + sphere.radius) * scale
    val color = sphere.tier.accent.color()
    val dash = size.minDimension * 0.03f

    drawRect(
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = 0.10f), color.copy(alpha = 0.02f)),
            startY = top,
            endY = size.height,
        ),
        topLeft = Offset(x - radius, top),
        size = Size(radius * 2f, size.height - top),
    )
    drawLine(
        color = color.copy(alpha = 0.26f + 0.14f * glowPulse),
        start = Offset(x, top),
        end = Offset(x, size.height),
        strokeWidth = size.minDimension * 0.008f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.8f)),
    )
}

/**
 * Dibuja una **esfera de energía**: una canica maciza con luz propia.
 *
 * De fuera hacia dentro:
 *  1. **Halo** radial tenue: el resplandor que derrama sobre el fondo (es lo único que late).
 *  2. **Cuerpo**: degradado radial con el foco desplazado arriba-izquierda, de casi blanco a su
 *     color y a una sombra en el borde opuesto. Es lo que le da volumen de bola.
 *  3. **Aro de neón** con su halo ceñido: la define contra el fondo y contra sus vecinas.
 *  4. **Marca de núcleo** ([marks]): un punto central y, alrededor, tantos puntos como tiers lleva
 *     por delante. Identifica el tier sin depender del color.
 *  5. **Brillo especular**: la media luna de luz que la remata como esfera pulida.
 *
 * Todas las medidas son fracciones del radio, así que la misma función sirve para una esfera del
 * tablero y para la miniatura del previsor.
 *
 * El estiramiento "gelatinoso" (squash-stretch según la velocidad) se aplica al cuerpo, pero la
 * marca y el brillo se dibujan sin girar: si rotaran con la dirección del movimiento, los puntos
 * del núcleo darían vueltas cada vez que la esfera cambia de rumbo.
 *
 * Se probó a deformarlas como globos blandos que se aplastan contra lo que tocan (caras planas en
 * cada contacto) y se descartó a petición del usuario: apiladas dejaban de leerse como esferas.
 * Son siempre redondas, igual que los círculos que simula el motor.
 *
 * @param glowPulse factor del latido ambiental (0.78..1); modula solo el halo, nunca el cuerpo,
 *   para que el tablero respire sin que parpadeen los objetos.
 * @param marks posición del tier en la escala (1 = el más pequeño); 0 = sin marca.
 * @param appear 0..1(+rebote): escala de aparición de una esfera recién nacida de una fusión.
 */
internal fun DrawScope.drawEnergySphere(
    center: Offset,
    radius: Float,
    color: Color,
    glowPulse: Float,
    marks: Int = 0,
    vx: Float = 0f,
    vy: Float = 0f,
    appear: Float = 1f,
) {
    val r = radius * appear
    if (r <= 0f) return
    // Squash-stretch "gelatinoso": una esfera rápida se alarga en la dirección del movimiento y
    // se aplana en la perpendicular, como un cuerpo blando que aún no ha absorbido su propia
    // inercia. Deliberadamente sutil (tope [GEL_MAX_STRETCH] de solo 12 %).
    val speed = hypot(vx, vy)
    val stretch = (speed / GEL_STRETCH_REF_SPEED).coerceIn(0f, 1f) * GEL_MAX_STRETCH
    val angleDeg = if (speed > GEL_MIN_SPEED_FOR_ANGLE) atan2(vy, vx) * (180f / PI.toFloat()) else 0f

    rotate(degrees = angleDeg, pivot = center) {
        scale(scaleX = 1f + stretch, scaleY = 1f - stretch, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.34f * glowPulse), Color.Transparent), center, r * 1.75f),
                radius = r * 1.75f,
                center = center,
            )
        }
    }
    // El cuerpo se pinta con el foco de luz fijo (arriba-izquierda en PANTALLA): si girara con
    // la esfera, la luz parecería venir de un sitio distinto en cada bola.
    scale(scaleX = 1f + stretch * 0.6f, scaleY = 1f - stretch * 0.6f, pivot = center) {
        val light = Offset(center.x - r * 0.34f, center.y - r * 0.38f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to lerp(color, Color.White, 0.70f),
                0.38f to color,
                1f to lerp(color, LogicColors.BackgroundDark, 0.62f),
                center = light,
                radius = r * 1.45f,
            ),
            radius = r,
            center = center,
        )
        drawCircle(color.copy(alpha = 0.26f), r * 0.98f, center, style = Stroke(width = r * 0.30f))
        drawCircle(lerp(color, Color.White, 0.30f), r * 0.94f, center, style = Stroke(width = r * 0.10f))
    }

    // Marca de núcleo: punto central + anillo de (marks − 1) puntos.
    if (marks > 0) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.90f), Color.Transparent), center, r * 0.26f),
            radius = r * 0.26f,
            center = center,
        )
        val ring = marks - 1
        if (ring > 0) {
            val dot = r * min(0.075f, 0.42f / ring)
            for (i in 0 until ring) {
                val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.82f),
                    radius = dot,
                    center = Offset(center.x + cos(a) * r * 0.52f, center.y + sin(a) * r * 0.52f),
                )
            }
        }
    }

    // Brillo especular: una media luna arriba-izquierda. Sin punto de luz suelto: se confundía
    // con los puntos de la marca de núcleo, que son los que identifican el tier.
    rotate(degrees = -32f, pivot = Offset(center.x - r * 0.36f, center.y - r * 0.50f)) {
        drawOval(
            color = Color.White.copy(alpha = 0.42f),
            topLeft = Offset(center.x - r * 0.62f, center.y - r * 0.62f),
            size = Size(r * 0.52f, r * 0.24f),
        )
    }
}

/**
 * Destello de fusión: un fogonazo, un anillo que se expande y un puñado de chispas.
 *
 * El [progress] llega del estado (lo avanza el tick de la física), no de un `animate*AsState`: así
 * el destello sigue el mismo reloj que el resto de la simulación y no se descuelga si el motor va
 * en cámara lenta tras un frame largo. El anillo y las chispas avanzan con frenado (rápido al
 * principio) porque es como se percibe una onda expansiva real.
 *
 * @param seed semilla estable del destello (su id): fija los ángulos de sus chispas.
 */
internal fun DrawScope.drawMergeFlash(center: Offset, radius: Float, color: Color, progress: Float, seed: Int) {
    val fade = 1f - progress
    val ease = 1f - fade * fade
    val ringRadius = radius * (0.65f + 1.5f * ease)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.60f * fade), color.copy(alpha = 0.38f * fade), Color.Transparent),
            center = center,
            radius = ringRadius * 1.2f,
        ),
        radius = ringRadius * 1.2f,
        center = center,
    )
    drawCircle(
        color = lerp(color, Color.White, 0.45f).copy(alpha = 0.85f * fade),
        radius = ringRadius,
        center = center,
        style = Stroke(width = radius * 0.2f * fade + 1f),
    )
    // Chispas: trazos cortos que salen en radial más allá del anillo y se afinan.
    for (i in 0 until FLASH_SPARKS) {
        val angle = (i + hash01(seed * 31 + i) * 0.8f) * (2f * PI.toFloat() / FLASH_SPARKS)
        val dist = radius * (0.9f + (1.3f + 0.9f * hash01(seed * 17 + i)) * ease)
        val length = radius * 0.28f * fade
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = (if (i % 3 == 0) Color.White else color).copy(alpha = fade),
            start = Offset(center.x + dx * dist, center.y + dy * dist),
            end = Offset(center.x + dx * (dist + length), center.y + dy * (dist + length)),
            strokeWidth = radius * 0.07f * fade + 1f,
            cap = StrokeCap.Round,
        )
    }
}
