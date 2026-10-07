package com.kortexgames.app.game.polarity

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * # Atracción Geométrica — arte procedural
 *
 * Funciones puras de `DrawScope` con lo que el juego dibuja a mano: el campo de gravedad del
 * fondo, el disco de sectores, las partículas y la marca de aproximación. No guardan estado ni
 * conocen las reglas: reciben geometría, color y el reloj de la pantalla, así que animan con solo
 * redibujar.
 *
 * Viven aparte de `PolarityCollisionScreen` para que la pantalla siga siendo "qué hay y dónde" y
 * el aspecto se pueda afinar aquí.
 */

/** 2π. */
private const val TAU = 2f * PI.toFloat()

/** Pseudoaleatorio determinista 0..1: mismo [n] → mismo valor en todos los frames (sin estado). */
private fun artHash(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/** Anillos y motas del campo de gravedad. */
private const val FIELD_RINGS = 4
private const val FIELD_MOTES = 46

/**
 * **Campo de gravedad**: anillos de trazos que se cierran sobre el disco y motas de polvo que
 * caen hacia él en espiral, cada vez más rápido.
 *
 * Es el fondo que le faltaba al nombre del juego: el disco *atrae*, y ahora se ve. Va justo al
 * revés que el túnel de Hypergate (allí todo sale despedido; aquí todo cae hacia dentro), para
 * que los dos juegos no se confundan aunque compartan lenguaje. Todo es determinista por índice
 * y sale del reloj, sin guardar partículas.
 *
 * @param discRadius radio del disco: el campo muere antes de tocarlo para no ensuciarlo.
 * @param speed multiplicador; la pantalla lo sube durante la lluvia de meteoros.
 * @param tint color del campo (el acento, o magenta en la lluvia).
 */
fun DrawScope.drawGravityField(center: Offset, discRadius: Float, time: Float, speed: Float, tint: Color) {
    val outer = maxOf(size.width, size.height) * 0.62f
    val inner = discRadius * 1.25f
    for (i in 0 until FIELD_RINGS) {
        val phase = (i / FIELD_RINGS.toFloat() + time * speed * 0.11f) % 1f
        // Cuadrática: lentos lejos, rápidos al caer (la gravedad acelera).
        val radius = outer + (inner - outer) * phase * phase
        val dash = radius * 0.11f
        drawCircle(
            color = tint.copy(alpha = 0.16f * sin(phase * PI.toFloat())),
            radius = radius,
            center = center,
            style = Stroke(
                width = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
                // La fase del patrón avanza: el anillo gira mientras se cierra.
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f), time * 18f * (if (i % 2 == 0) 1f else -1f)),
            ),
        )
    }
    for (i in 0 until FIELD_MOTES) {
        val phase = (artHash(i * 13 + 1) + time * speed * (0.07f + 0.10f * artHash(i * 5 + 9))) % 1f
        val radius = outer + (inner - outer) * phase * phase
        // El ángulo se enrosca al caer: espiral, no caída recta.
        val angle = artHash(i * 7 + 3) * TAU + phase * phase * 2.4f
        drawCircle(
            color = (if (i % 5 == 0) tint else LogicColors.OnDark).copy(alpha = 0.34f * sin(phase * PI.toFloat())),
            radius = (0.8f + 1.1f * phase).dp.toPx(),
            center = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius),
        )
    }
}

/**
 * El **disco de sectores**, en neón: cada sector es un cristal oscuro encendido desde su borde.
 *
 * El color vive en el aro exterior de cada sector (halo ancho → intermedio → trazo nítido →
 * núcleo blanco, §9.7) y se derrama hacia dentro, de modo que el cristal es casi negro en el eje
 * e intenso junto al borde — que es por donde llegan las partículas y donde el jugador mira. Con
 * los sectores rellenos de color pleno el disco se leía como una pelota de playa.
 *
 * Reacciona a dos cosas:
 *  - **captura** ([sectorFlash]): el sector que acaba de acertar se enciende entero un instante;
 *  - **fallo** ([hurt]): todo el disco vira a rojo y se apaga solo.
 *
 * Alrededor gira una corona de trazos y en el eje un pequeño rotor: dan vida al disco aunque el
 * jugador no lo mueva, y ayudan a percibir el giro cuando sí lo hace.
 *
 * El reparto angular debe coincidir EXACTAMENTE con el hit-test del motor
 * ([PolarityCollisionEngine]), que divide 2π entre `colors.size` empezando en [rotationRad].
 *
 * @param sectorFlash un valor 0..1 por sector (mismo orden que [colors]).
 * @param hurt 0..1, intensidad del tinte rojo de fallo.
 */
fun DrawScope.drawPolarityDisc(
    center: Offset,
    radius: Float,
    rotationRad: Float,
    colors: List<Color>,
    time: Float,
    sectorFlash: FloatArray,
    hurt: Float,
) {
    val count = colors.size
    if (count == 0 || radius <= 0f) return
    val sectorAngle = 360f / count
    val startDeg = rotationRad * 180f / PI.toFloat()
    val topLeft = Offset(center.x - radius, center.y - radius)
    val box = Size(radius * 2f, radius * 2f)
    val breath = 0.5f + 0.5f * sin(time * 2.2f)

    // Halo del disco.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(LogicColors.OnDark.copy(alpha = 0.10f + 0.04f * breath), Color.Transparent),
            center = center,
            radius = radius * 1.9f,
        ),
        radius = radius * 1.9f,
        center = center,
    )

    // Cristal de cada sector: oscuro en el eje, encendido hacia el borde.
    repeat(count) { index ->
        val base = colors[index]
        val flash = sectorFlash.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        drawArc(
            brush = Brush.radialGradient(
                0f to lerp(LogicColors.BackgroundDark, base, 0.10f + 0.30f * flash),
                0.55f to lerp(LogicColors.BackgroundDark, base, 0.20f + 0.40f * flash),
                1f to lerp(LogicColors.BackgroundDark, base, 0.58f + 0.30f * flash),
                center = center,
                radius = radius,
            ),
            startAngle = startDeg + sectorAngle * index,
            sweepAngle = sectorAngle,
            useCenter = true,
            topLeft = topLeft,
            size = box,
        )
        if (flash > 0f) {
            drawArc(
                color = Color.White.copy(alpha = 0.30f * flash),
                startAngle = startDeg + sectorAngle * index,
                sweepAngle = sectorAngle,
                useCenter = true,
                topLeft = topLeft,
                size = box,
            )
        }
    }

    // Costuras entre sectores: un corte oscuro con un hilo de luz.
    val sectorRad = TAU / count
    repeat(count) { index ->
        val angle = rotationRad + sectorRad * index
        val outer = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        drawLine(LogicColors.BackgroundDark, center, outer, strokeWidth = radius * 0.05f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.40f), center, outer, strokeWidth = radius * 0.010f, cap = StrokeCap.Round)
    }

    // Tubo de neón del borde, por sector.
    val insetDeg = 3.5f
    repeat(count) { index ->
        val base = colors[index]
        val flash = sectorFlash.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val start = startDeg + sectorAngle * index + insetDeg
        val sweep = sectorAngle - insetDeg * 2f
        fun rim(color: Color, width: Float) = drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
        rim(base.copy(alpha = 0.22f + 0.20f * flash), radius * (0.20f + 0.10f * flash))
        rim(base.copy(alpha = 0.50f), radius * 0.095f)
        rim(base, radius * 0.045f)
        rim(Color.White.copy(alpha = 0.60f + 0.35f * flash), radius * 0.016f)
    }

    // Corona exterior: trazos que giran despacio, en sentido contrario al rotor del eje.
    val crown = radius * 1.20f
    val dash = crown * 0.16f
    drawCircle(
        color = LogicColors.OnDark.copy(alpha = 0.22f),
        radius = crown,
        center = center,
        style = Stroke(
            width = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.4f), time * 14f),
        ),
    )

    // Eje: núcleo oscuro con un rotor de cuatro arcos.
    val hub = radius * 0.24f
    drawCircle(LogicColors.BackgroundDark, hub, center)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(LogicColors.OnDark.copy(alpha = 0.30f + 0.15f * breath), Color.Transparent),
            center = center,
            radius = hub,
        ),
        radius = hub,
        center = center,
    )
    val rotor = hub * 0.72f
    for (i in 0 until 4) {
        drawArc(
            color = LogicColors.OnDark.copy(alpha = 0.75f),
            startAngle = -time * 90f + i * 90f,
            sweepAngle = 46f,
            useCenter = false,
            topLeft = Offset(center.x - rotor, center.y - rotor),
            size = Size(rotor * 2f, rotor * 2f),
            style = Stroke(width = radius * 0.022f, cap = StrokeCap.Round),
        )
    }
    drawCircle(LogicColors.OnDark.copy(alpha = 0.5f), hub, center, style = Stroke(1.dp.toPx()))

    // Fallo: el disco entero se tiñe de rojo un instante.
    val h = hurt.coerceIn(0f, 1f)
    if (h > 0f) {
        drawCircle(LogicColors.Error.copy(alpha = 0.34f * h), radius, center)
        drawCircle(LogicColors.Error.copy(alpha = 0.9f * h), radius * (1f + 0.25f * (1f - h)), center, style = Stroke(3.dp.toPx() * h + 0.5f))
    }
}

/** Tramos de la estela de una partícula. */
private const val ORB_TRAIL_STEPS = 5

/**
 * Una partícula como **orbe de neón** con estela.
 *
 * Todas dejan estela en sentido contrario a su velocidad —corta las normales, larga los meteoros
 * de la lluvia— para que se vea de dónde vienen y cómo de rápido. El cuerpo es un aro de luz con
 * núcleo claro sobre un cristal teñido, no un disco plano de color.
 *
 * Las **magnéticas** (las que curvan su trayectoria hacia el disco) llevan dos arcos que giran a
 * su alrededor: el jugador distingue de un vistazo cuáles no van a seguir en línea recta.
 *
 * @param particle la partícula del motor (posición, velocidad, radio y tipo).
 * @param time segundos del reloj de la pantalla (giro de los arcos de las magnéticas).
 */
fun DrawScope.drawNeonOrb(particle: PolarityParticle, color: Color, time: Float) {
    val center = Offset(particle.x, particle.y)
    val radius = particle.radius
    val speed = hypot(particle.vx, particle.vy)
    if (speed > 1f) {
        val dx = -particle.vx / speed
        val dy = -particle.vy / speed
        val length = radius * (if (particle.meteor) 6.5f else 3.2f)
        for (i in 0 until ORB_TRAIL_STEPS) {
            val t0 = i / ORB_TRAIL_STEPS.toFloat()
            val t1 = (i + 1) / ORB_TRAIL_STEPS.toFloat()
            val fade = 1f - t0
            drawLine(
                color = color.copy(alpha = 0.60f * fade * fade),
                start = Offset(center.x + dx * length * t0, center.y + dy * length * t0),
                end = Offset(center.x + dx * length * t1, center.y + dy * length * t1),
                strokeWidth = radius * 1.5f * fade + 1f,
                cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = if (particle.magnetic) 0.55f else 0.42f), Color.Transparent),
            center = center,
            radius = radius * 2.6f,
        ),
        radius = radius * 2.6f,
        center = center,
    )
    drawCircle(lerp(LogicColors.BackgroundDark, color, 0.45f), radius, center)
    drawCircle(color, radius * 0.88f, center, style = Stroke(width = radius * 0.26f))
    drawCircle(lerp(color, Color.White, 0.75f), radius * 0.36f, center)

    if (particle.magnetic) {
        val ring = radius * 1.75f
        for (i in 0 until 2) {
            drawArc(
                color = color.copy(alpha = 0.85f),
                startAngle = time * 260f + i * 180f + (particle.id % 7) * 40f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(center.x - ring, center.y - ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Marca en el borde del disco **por dónde va a entrar** la partícula más cercana: un arco de su
 * color justo por fuera del aro. Es la que el jugador tiene que resolver ahora; destacarla ordena
 * una pantalla con varias en vuelo y le dice hacia dónde girar sin seguir la bola con la vista.
 *
 * @param angleRad ángulo de la partícula respecto al centro del disco.
 * @param urgency 0..1, cercanía al impacto (1 = encima): aviva y ensancha la marca.
 */
fun DrawScope.drawApproachMarker(center: Offset, discRadius: Float, angleRad: Float, color: Color, urgency: Float) {
    val u = urgency.coerceIn(0f, 1f)
    val mark = discRadius * 1.12f
    drawArc(
        color = color.copy(alpha = 0.30f + 0.65f * u),
        startAngle = angleRad * 180f / PI.toFloat() - 11f,
        sweepAngle = 22f,
        useCenter = false,
        topLeft = Offset(center.x - mark, center.y - mark),
        size = Size(mark * 2f, mark * 2f),
        style = Stroke(width = (2f + 3f * u).dp.toPx(), cap = StrokeCap.Round),
    )
}
