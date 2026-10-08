package com.kortexgames.app.game.hypergate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.ui.components.drawSparkBurst
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * # Hypergate — arte procedural
 *
 * Funciones puras de `DrawScope` con todo lo que el juego dibuja a mano: el túnel de velocidad
 * del fondo, el portal central, los cometas y sus impactos. No guardan estado ni conocen las
 * reglas: reciben geometría, color y tiempos (el reloj de la pantalla, o la "edad" de un evento),
 * así que animan con solo redibujar.
 *
 * Viven aparte de `HypergateScreen` para que la pantalla siga siendo "qué hay y dónde" y el
 * aspecto se pueda afinar aquí.
 *
 * ## Forma además de color
 * Las dos polaridades se distinguen por **color y por silueta**: A es redonda y B es un rombo, en
 * los cometas y en el glifo del núcleo del portal. Verde neón y cian están cerca en el círculo
 * cromático, y a la velocidad de las últimas rondas (o para un jugador con daltonismo) el color
 * solo no basta para decidir en un cuarto de segundo.
 */

/** 2π. */
private const val TAU = 2f * PI.toFloat()

/** Pseudoaleatorio determinista 0..1: mismo [n] → mismo valor en todos los frames (sin estado). */
private fun artHash(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/** Color neón de una polaridad. Único punto donde [ShieldState] se vuelve un color del tema. */
internal fun ShieldState.toNeon(): Color =
    if (this == ShieldState.A) LogicColors.NeonGreen else LogicColors.NeonCyan

/** Estrías del túnel de velocidad. */
private const val WARP_STREAKS = 64

/**
 * **Túnel de velocidad**: estrías que nacen cerca del centro y salen despedidas hacia los bordes,
 * acelerando y alargándose, como estrellas al entrar en el hiperespacio.
 *
 * Es lo que convierte la pantalla de "un aro quieto al que le llegan puntos" en un viaje: el
 * portal avanza y los cometas vienen de frente. Cada estría es determinista por su índice (ángulo
 * y desfase fijos) y su posición sale del reloj, sin guardar partículas.
 *
 * @param speed multiplicador de velocidad; la pantalla lo sube según se agota la ronda, de modo
 *   que la urgencia del final se nota también en el fondo.
 * @param tint color del portal: unas pocas estrías lo llevan para atar el fondo a la polaridad.
 */
fun DrawScope.drawWarpField(center: Offset, time: Float, speed: Float, tint: Color) {
    val maxRadius = maxOf(size.width, size.height) * 0.62f
    for (i in 0 until WARP_STREAKS) {
        val angle = artHash(i * 7 + 3) * TAU
        // Fase 0..1 de su viaje; cada estría va a su ritmo para que no salgan en oleadas.
        val phase = (artHash(i * 13 + 1) + time * speed * (0.16f + 0.22f * artHash(i * 5 + 9))) % 1f
        // Cuadrática: lenta junto al centro y rápida al salir (perspectiva).
        val head = phase * phase * maxRadius
        val length = (6.dp.toPx() + head * 0.16f) * (0.6f + 0.4f * speed)
        val tail = (head - length).coerceAtLeast(0f)
        val dx = cos(angle)
        val dy = sin(angle)
        val color = if (i % 6 == 0) tint else LogicColors.OnDark
        drawLine(
            // Invisibles al nacer (no deben tapar el portal) y más vivas hacia el borde.
            color = color.copy(alpha = (0.32f * phase).coerceAtMost(0.32f)),
            start = Offset(center.x + dx * tail, center.y + dy * tail),
            end = Offset(center.x + dx * head, center.y + dy * head),
            strokeWidth = (0.8f + 1.4f * phase).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Duración de la onda expansiva al cambiar de polaridad (s). */
private const val TOGGLE_WAVE_SEC = 0.38f

/** Cuánto dura el portal "herido" (rojo y tembloroso) tras un choque (s). */
private const val CRASH_HURT_SEC = 0.45f

/**
 * El **portal**: un reactor de neón en vez de un aro suelto.
 *
 * De fuera a dentro: halo, dos coronas de arcos que giran en sentidos opuestos (dan vida aunque
 * no pase nada, y sensación de mecanismo), el anillo principal —la frontera real de la colisión,
 * a [radius]— como tubo de neón (halo ancho → intermedio → trazo nítido → núcleo blanco, §9.7), y
 * un núcleo oscuro con el **glifo de la polaridad** (círculo = A, rombo = B).
 *
 * Reacciona a tres cosas:
 *  - **cambio de polaridad** ([toggleAge]): rebote de escala y una onda que se expande;
 *  - **choque** ([crashAge]): vira a rojo y tiembla un instante;
 *  - **racha** ([streak]): el halo y el giro se avivan con los aciertos encadenados.
 *
 * @param radius radio del anillo principal en píxeles; debe ser el del motor para que lo dibujado
 *   coincida con donde de verdad impactan los cometas.
 * @param toggleAge segundos desde el último cambio de polaridad.
 * @param crashAge segundos desde el último choque (un valor grande si no hubo).
 * @param showGlyph si se dibuja el glifo del núcleo (la pantalla lo apaga para poner ahí la racha).
 */
fun DrawScope.drawGate(
    center: Offset,
    radius: Float,
    shield: ShieldState,
    color: Color,
    time: Float,
    toggleAge: Float,
    crashAge: Float,
    streak: Int,
    showGlyph: Boolean,
) {
    if (radius <= 0f) return
    val hurt = if (crashAge in 0f..CRASH_HURT_SEC) 1f - crashAge / CRASH_HURT_SEC else 0f
    val tone = lerp(color, LogicColors.Error, hurt)
    val heat = (streak / 8f).coerceIn(0f, 1f)
    // Rebote amortiguado al conmutar: se hunde y vuelve, sin Animatable (sale de la edad).
    val bounce = 1f - 0.16f * cos(toggleAge * 22f) * exp(-toggleAge * 9f)
    val jitter = if (hurt > 0f) sin(crashAge * 90f) * 3.dp.toPx() * hurt else 0f
    val c = Offset(center.x + jitter, center.y)
    val r = radius * bounce
    val stroke = (radius * 0.075f).coerceAtLeast(2.dp.toPx())
    val breath = 0.5f + 0.5f * sin(time * 2.6f)

    // Halo.
    val halo = r * (2.3f + 0.4f * heat)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tone.copy(alpha = 0.26f + 0.16f * heat + 0.08f * breath), tone.copy(alpha = 0.06f), Color.Transparent),
            center = c,
            radius = halo,
        ),
        radius = halo,
        center = c,
    )

    // Onda del cambio de polaridad.
    if (toggleAge in 0f..TOGGLE_WAVE_SEC) {
        val p = toggleAge / TOGGLE_WAVE_SEC
        val ease = 1f - (1f - p) * (1f - p)
        drawCircle(
            color = tone.copy(alpha = 0.7f * (1f - p)),
            radius = r * (1f + 1.5f * ease),
            center = c,
            style = Stroke(width = stroke * (1f - p) + 1f),
        )
    }

    // Coronas giratorias.
    fun crown(scale: Float, arcs: Int, sweep: Float, degPerSec: Float, alpha: Float, width: Float) {
        val cr = r * scale
        val topLeft = Offset(c.x - cr, c.y - cr)
        val box = Size(cr * 2f, cr * 2f)
        for (i in 0 until arcs) {
            drawArc(
                color = tone.copy(alpha = alpha),
                startAngle = time * degPerSec * (1f + 0.8f * heat) + i * (360f / arcs),
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = box,
                style = Stroke(width = width, cap = StrokeCap.Round),
            )
        }
    }
    crown(scale = 1.30f, arcs = 3, sweep = 62f, degPerSec = 46f, alpha = 0.50f, width = stroke * 0.45f)
    crown(scale = 1.52f, arcs = 6, sweep = 14f, degPerSec = -28f, alpha = 0.32f, width = stroke * 0.35f)
    crown(scale = 0.74f, arcs = 4, sweep = 38f, degPerSec = -84f, alpha = 0.55f, width = stroke * 0.40f)

    // Anillo principal: el tubo de neón.
    drawCircle(tone.copy(alpha = 0.20f + 0.08f * breath), r, c, style = Stroke(stroke * 4.5f))
    drawCircle(tone.copy(alpha = 0.48f), r, c, style = Stroke(stroke * 2.1f))
    drawCircle(tone, r, c, style = Stroke(stroke))
    drawCircle(Color.White.copy(alpha = 0.70f), r, c, style = Stroke(stroke * 0.4f))

    // Núcleo.
    val core = r * 0.56f
    drawCircle(LogicColors.BackgroundDark.copy(alpha = 0.90f), core, c)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tone.copy(alpha = 0.34f + 0.12f * breath), Color.Transparent),
            center = c,
            radius = core,
        ),
        radius = core,
        center = c,
    )
    if (showGlyph) {
        drawPolarityGlyph(c, core * (0.36f + 0.04f * breath), shield, tone, facingRad = -PI.toFloat() / 2f)
    }
}

/**
 * Silueta de una polaridad: disco para A, rombo para B, con halo y núcleo blanco. La comparten la
 * cabeza de los cometas y el glifo del portal, para que "esto encaja con aquello" se lea por forma.
 *
 * @param facingRad orientación del rombo (su eje largo); irrelevante para el disco.
 */
fun DrawScope.drawPolarityGlyph(center: Offset, size: Float, shield: ShieldState, color: Color, facingRad: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
            center = center,
            radius = size * 2.8f,
        ),
        radius = size * 2.8f,
        center = center,
    )
    if (shield == ShieldState.A) {
        drawCircle(color, size, center)
        drawCircle(Color.White.copy(alpha = 0.85f), size * 0.42f, center)
    } else {
        val ax = cos(facingRad)
        val ay = sin(facingRad)
        fun diamond(long: Float, short: Float) = Path().apply {
            moveTo(center.x + ax * long, center.y + ay * long)
            lineTo(center.x - ay * short, center.y + ax * short)
            lineTo(center.x - ax * long, center.y - ay * long)
            lineTo(center.x + ay * short, center.y - ax * short)
            close()
        }
        drawPath(diamond(size * 1.45f, size * 0.95f), color)
        drawPath(diamond(size * 0.62f, size * 0.40f), Color.White.copy(alpha = 0.85f))
    }
}

/** Tramos en que se parte la estela de un cometa para afinarla hacia la cola. */
private const val COMET_TRAIL_STEPS = 6

/**
 * Un proyectil como **cometa**: cabeza con la silueta de su polaridad y una estela que se afina y
 * se apaga hacia atrás. La estela es más larga cuanto más rápido va, así la velocidad se ve.
 *
 * La cola se dibuja "hacia afuera" con el mismo ángulo polar (viaja hacia el centro), sin guardar
 * posiciones anteriores.
 *
 * @param speedFactor velocidad relativa del proyectil (≈1 al principio de la ronda).
 */
fun DrawScope.drawComet(head: Offset, angleRad: Float, required: ShieldState, speedFactor: Float) {
    val color = required.toNeon()
    val headSize = 6.5.dp.toPx()
    val length = 30.dp.toPx() * (0.7f + 0.6f * speedFactor)
    val dx = cos(angleRad)
    val dy = sin(angleRad)
    for (i in 0 until COMET_TRAIL_STEPS) {
        val t0 = i / COMET_TRAIL_STEPS.toFloat()
        val t1 = (i + 1) / COMET_TRAIL_STEPS.toFloat()
        val fade = 1f - t0
        drawLine(
            color = color.copy(alpha = 0.75f * fade * fade),
            start = Offset(head.x + dx * length * t0, head.y + dy * length * t0),
            end = Offset(head.x + dx * length * t1, head.y + dy * length * t1),
            strokeWidth = headSize * 1.5f * fade + 1f,
            cap = StrokeCap.Round,
        )
    }
    drawPolarityGlyph(head, headSize, required, color, facingRad = angleRad)
}

/**
 * Marca en el anillo **dónde va a impactar** un cometa: un arco de su color justo por fuera del
 * portal y una guía muy tenue hasta él. Solo se usa para el más cercano: es el que el jugador
 * tiene que resolver ahora, y destacarlo ordena una pantalla con varios en vuelo.
 *
 * @param urgency 0..1, cuánto falta para el impacto (1 = encima): aviva la marca.
 */
fun DrawScope.drawImpactMarker(center: Offset, radius: Float, head: Offset, angleRad: Float, color: Color, urgency: Float) {
    val u = urgency.coerceIn(0f, 1f)
    val mark = radius * 1.14f
    drawArc(
        color = color.copy(alpha = 0.35f + 0.60f * u),
        startAngle = angleRad * 180f / PI.toFloat() - 13f,
        sweepAngle = 26f,
        useCenter = false,
        topLeft = Offset(center.x - mark, center.y - mark),
        size = Size(mark * 2f, mark * 2f),
        style = Stroke(width = (2f + 2.5f * u).dp.toPx(), cap = StrokeCap.Round),
    )
    drawLine(
        color = color.copy(alpha = 0.10f + 0.12f * u),
        start = Offset(center.x + cos(angleRad) * mark, center.y + sin(angleRad) * mark),
        end = head,
        strokeWidth = 1.dp.toPx(),
    )
}

/** Vida de un impacto en pantalla (s). */
internal const val IMPACT_LIFE_SEC = 0.6f

/**
 * El **impacto** de un cometa en el portal.
 *
 * - **Absorbido**: un tramo del anillo destella en blanco donde entró, una ráfaga corta de su
 *   color y un pulso que recorre el aro. Se lee como energía que el portal se traga.
 * - **Choque**: estallido rojo grande y una grieta de luz que se abre hacia fuera. El portal
 *   entero reacciona aparte (ver [drawGate]).
 *
 * @param age segundos desde el impacto; fuera de `0..IMPACT_LIFE_SEC` no dibuja nada.
 */
fun DrawScope.drawGateImpact(center: Offset, radius: Float, angleRad: Float, color: Color, success: Boolean, age: Float, seed: Int) {
    val p = age / IMPACT_LIFE_SEC
    if (p <= 0f || p >= 1f) return
    val fade = 1f - p
    val point = Offset(center.x + cos(angleRad) * radius, center.y + sin(angleRad) * radius)
    val degrees = angleRad * 180f / PI.toFloat()
    if (success) {
        val spread = 22f + 70f * p
        drawArc(
            color = Color.White.copy(alpha = 0.9f * fade * fade),
            startAngle = degrees - spread,
            sweepAngle = spread * 2f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = radius * 0.11f * fade + 1f, cap = StrokeCap.Round),
        )
        drawCircle(color.copy(alpha = 0.5f * fade), radius * (1f + 0.35f * p), center, style = Stroke(2.dp.toPx() * fade + 0.5f))
        drawSparkBurst(point, color, reach = radius * 0.75f, progress = p * 1.3f, seed = seed)
    } else {
        val red = LogicColors.Error
        drawSparkBurst(point, red, reach = radius * 1.5f, progress = p, seed = seed)
        drawCircle(red.copy(alpha = 0.55f * fade), radius * (1f + 0.9f * p), center, style = Stroke(4.dp.toPx() * fade + 0.5f))
        // Grieta: un rayo quebrado que sale del punto de impacto.
        val reach = radius * (0.5f + 1.1f * p)
        var from = point
        for (i in 1..4) {
            val wobble = (artHash(seed * 11 + i) - 0.5f) * 0.9f
            val a = angleRad + wobble
            val to = Offset(point.x + cos(a) * reach * i / 4f, point.y + sin(a) * reach * i / 4f)
            drawLine(Color.White.copy(alpha = 0.8f * fade), from, to, strokeWidth = 2.dp.toPx() * fade + 0.5f, cap = StrokeCap.Round)
            from = to
        }
    }
}

/** Color de un proyectil según su clase: rojo de peligro para el meteorito, su polaridad para el cometa. */
internal fun Projectile.neon(): Color =
    if (kind == ProjectileKind.METEOR) LogicColors.Error else required.toNeon()

/**
 * Color del **modo escudo**. Morado a propósito: tiene que distinguirse a la vez de las dos
 * polaridades (verde y cian) y del rojo de los meteoritos, porque significa otra cosa — "ahora
 * mismo nada te afecta" — y no debe leerse como una tercera polaridad.
 */
internal val BarrierColor: Color get() = LogicColors.Violet

/** Vértices del contorno irregular de un meteorito. */
private const val METEOR_VERTICES = 8

/**
 * Un **meteorito rojo**: roca de contorno irregular que gira, con cola de fuego.
 *
 * Igual que las polaridades se distinguen por silueta además de por color (ver cabecera), el
 * meteorito tiene la suya —un polígono quebrado, ni disco ni rombo— porque exige una respuesta
 * distinta (mantener pulsado) y hay que reconocerlo de un vistazo, también sin distinguir el rojo.
 *
 * @param seed semilla estable (el id del proyectil): cada meteorito tiene su propio contorno.
 */
fun DrawScope.drawMeteor(head: Offset, angleRad: Float, speedFactor: Float, time: Float, seed: Int) {
    val color = LogicColors.Error
    val rock = 9.dp.toPx()
    val length = 38.dp.toPx() * (0.7f + 0.6f * speedFactor)
    val dx = cos(angleRad)
    val dy = sin(angleRad)
    // Cola de fuego: más ancha y corta que la estela de un cometa, y parpadea.
    for (i in 0 until COMET_TRAIL_STEPS) {
        val t0 = i / COMET_TRAIL_STEPS.toFloat()
        val t1 = (i + 1) / COMET_TRAIL_STEPS.toFloat()
        val fade = 1f - t0
        val flicker = 0.8f + 0.2f * sin(time * 30f + i * 1.7f + seed)
        drawLine(
            color = lerp(color, LogicColors.Amber, 0.55f * fade).copy(alpha = 0.70f * fade * fade * flicker),
            start = Offset(head.x + dx * length * t0, head.y + dy * length * t0),
            end = Offset(head.x + dx * length * t1, head.y + dy * length * t1),
            strokeWidth = rock * 2.1f * fade + 1f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.60f), Color.Transparent),
            center = head,
            radius = rock * 2.8f,
        ),
        radius = rock * 2.8f,
        center = head,
    )
    val spin = time * 1.8f + seed
    fun contour(scale: Float) = Path().apply {
        for (i in 0 until METEOR_VERTICES) {
            val a = spin + i * TAU / METEOR_VERTICES
            val r = rock * scale * (0.78f + 0.44f * artHash(seed * 31 + i))
            val x = head.x + cos(a) * r
            val y = head.y + sin(a) * r
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(contour(1f), color)
    // Núcleo oscuro: lo vuelve "roca" y lo separa de los cometas, cuyo núcleo es blanco.
    drawPath(contour(0.55f), LogicColors.BackgroundDark.copy(alpha = 0.55f))
    drawPath(contour(1f), Color.White.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
}

/** Fracción del aguante a partir de la que la burbuja del modo escudo avisa de que se agota. */
private const val BARRIER_WARN_FROM = 0.66f

/**
 * La burbuja del **modo escudo** alrededor del portal.
 *
 * Es un tubo de neón (halo ancho → intermedio → trazo nítido → núcleo blanco, §9.7) con un velo
 * tenue por dentro, y sobre el aro un **arco blanco que se va consumiendo**: es el aguante que
 * queda. El jugador mira al portal, no al HUD, así que el contador de 3 s vive aquí. En el último
 * tercio el aro vira a rojo y parpadea para avisar de que está a punto de agotarse.
 *
 * @param gateRadius radio del anillo del portal; la burbuja mide [BARRIER_RADIUS_FACTOR] veces eso,
 *   igual que la frontera de colisión del motor.
 * @param amount opacidad 0..1 (la pantalla la anima para que encender y apagar no sean un corte).
 * @param heldFraction 0..1, parte del aguante ya gastada.
 */
fun DrawScope.drawBarrier(center: Offset, gateRadius: Float, amount: Float, heldFraction: Float, time: Float) {
    if (gateRadius <= 0f || amount <= 0f) return
    val r = gateRadius * BARRIER_RADIUS_FACTOR
    val held = heldFraction.coerceIn(0f, 1f)
    val warn = ((held - BARRIER_WARN_FROM) / (1f - BARRIER_WARN_FROM)).coerceIn(0f, 1f)
    val blink = if (warn > 0f) 0.65f + 0.35f * sin(time * 26f) else 1f
    val tone = lerp(BarrierColor, LogicColors.Error, warn)
    val a = amount * blink
    val stroke = (gateRadius * 0.05f).coerceAtLeast(1.5.dp.toPx())

    drawCircle(
        brush = Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to tone.copy(alpha = 0.22f * a),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
    drawCircle(tone.copy(alpha = 0.18f * a), r, center, style = Stroke(stroke * 4.5f))
    drawCircle(tone.copy(alpha = 0.42f * a), r, center, style = Stroke(stroke * 2.1f))
    drawCircle(tone.copy(alpha = 0.85f * a), r, center, style = Stroke(stroke))
    // Aguante restante: arranca arriba y se consume en sentido horario.
    drawArc(
        color = Color.White.copy(alpha = 0.90f * a),
        startAngle = -90f,
        sweepAngle = 360f * (1f - held),
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke * 0.6f, cap = StrokeCap.Round),
    )
}

/**
 * **Recarga** del modo escudo tras agotarlo: un aro apagado, a la altura de la burbuja, que se va
 * completando. Gris y sin halo a propósito: dice "todavía no" sin competir con el portal.
 *
 * @param fraction 0..1, parte de la recarga ya cumplida.
 */
fun DrawScope.drawBarrierRecharge(center: Offset, gateRadius: Float, fraction: Float) {
    if (gateRadius <= 0f) return
    val r = gateRadius * BARRIER_RADIUS_FACTOR
    val width = 2.dp.toPx()
    drawCircle(LogicColors.OnDarkMuted.copy(alpha = 0.14f), r, center, style = Stroke(width))
    drawArc(
        color = LogicColors.OnDarkMuted.copy(alpha = 0.70f),
        startAngle = -90f,
        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/**
 * Un proyectil **deshecho contra el modo escudo**: un tramo de la burbuja destella y saltan unas
 * chispas del color de lo que llegó. Más corto y apagado que una absorción ([drawGateImpact]): no
 * es un acierto que celebrar.
 *
 * @param color color del proyectil deshecho.
 * @param age segundos desde el impacto; fuera de `0..IMPACT_LIFE_SEC` no dibuja nada.
 */
fun DrawScope.drawBarrierDeflect(center: Offset, gateRadius: Float, angleRad: Float, color: Color, age: Float, seed: Int) {
    val p = age / IMPACT_LIFE_SEC
    if (p <= 0f || p >= 1f) return
    val fade = 1f - p
    val r = gateRadius * BARRIER_RADIUS_FACTOR
    val spread = 14f + 30f * p
    drawArc(
        color = Color.White.copy(alpha = 0.85f * fade * fade),
        startAngle = angleRad * 180f / PI.toFloat() - spread,
        sweepAngle = spread * 2f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = gateRadius * 0.10f * fade + 1f, cap = StrokeCap.Round),
    )
    drawSparkBurst(
        center = Offset(center.x + cos(angleRad) * r, center.y + sin(angleRad) * r),
        color = color,
        reach = gateRadius * 0.6f,
        progress = p * 1.4f,
        seed = seed,
    )
}
