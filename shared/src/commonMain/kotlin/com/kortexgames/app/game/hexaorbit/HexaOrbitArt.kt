package com.kortexgames.app.game.hexaorbit

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * # Arte de Hexa Orbit
 *
 * Todas las capas de dibujo del tablero, separadas de la pantalla para que
 * `HexaOrbitScreen` solo orqueste estado, gestos y HUD. Son funciones puras de
 * `DrawScope`: reciben el estado del motor y el reloj de frames, y no guardan nada.
 *
 * ## Idea visual: un circuito que se enciende
 *
 * El tablero es una placa oscura de piezas con relieve cuyos caminos son **tubos de
 * neón** (una ranura oscura con un filamento claro dentro). El haz proyectado es la
 * corriente: enciende esos mismos tubos con color y halo. Así "camino posible" y
 * "camino que va a tomar" se distinguen de un vistazo, que es lo que hace falta a la
 * velocidad a la que va este juego (§9.1: el neón vale porque es escaso — solo brilla
 * con color lo que informa). El haz es luz fija y el fondo no se mueve: lo único que
 * se desplaza en pantalla es el puntero.
 *
 * ## Capas de neón
 *
 * Los trazos no son contornos de tile, así que no usan `drawNeonTile` pero replican
 * su proporción de capas (halo ancho → intermedio → nítido → núcleo blanco), como
 * pide la §9.7 de `CLAUDE.md`.
 */

/** Color de identidad del juego (el mismo acento que usan su antesala y sus diálogos). */
internal val HexaAccent: Color = CategoryPalette.SpatialVision

/** `√3`: anchura de un hexágono pointy-top respecto a su radio (ver [BoardTransform.of]). */
private const val SQRT_3 = 1.7320508f

/** π en `Float`, para no promover a `Double` en cada rotación de punto. */
internal const val PI_F = 3.1415927f

/** Fracción del lienzo que ocupa el tablero; el resto es margen para el HUD y el halo. */
private const val BOARD_FILL = 0.94f

/** Altura del centro del tablero, en fracción del alto del lienzo. */
private const val BOARD_CENTER_Y = 0.55f

/** Grados que recorre el giro de una pieza (60° = un paso de arista). */
private const val SPIN_DEGREES = 60f

/** Duración del giro: dentro de los 100-250 ms de micro-feedback de la §9.4. */
private const val SPIN_DURATION_SEC = 0.22f

/**
 * Opacidad mínima del tramo más lejano del haz. Es el suelo del desvanecido: garantiza que los
 * nueve azulejos proyectados se vean, en vez de desaparecer en la cola.
 */
private const val BEAM_MIN_INTENSITY = 0.22f

/** Radio del orbe recolectable como fracción del radio del hexágono. */
private const val ORB_RADIUS_FACTOR = 0.19f

/** Radio del puntero como fracción del radio del hexágono. */
private const val POINTER_RADIUS_FACTOR = 0.13f

/** Duración del estallido de recogida. */
private const val BURST_DURATION_SEC = 0.6f

/** Alcance de las chispas, en fracción del radio del hexágono. */
private const val BURST_SPREAD_FACTOR = 1.25f

/** Chispas por estallido: suficientes para leerse como explosión, pocas para no ensuciar. */
private const val BURST_PARTICLES = 14

/** Duración de la onda que deja un toque sobre una pieza. */
private const val RIPPLE_DURATION_SEC = 0.32f

/** Cuánto tarda cada pieza en "caer" a su sitio al montar el tablero. */
private const val INTRO_TILE_SEC = 0.34f

/** Desfase de la entrada entre un anillo del tablero y el siguiente (del centro hacia fuera). */
private const val INTRO_RING_STAGGER_SEC = 0.075f

/** Fracción del hexágono que ocupa la cara de la pieza; el resto es la junta entre piezas. */
private const val TILE_INSET = 0.93f

/** Giro de la órbita de fondo, en grados por segundo: una vuelta cada ~1 minuto. */
private const val ORBIT_DEG_PER_SEC = 6f

/** Frecuencia de la "respiración" del resplandor de fondo: un ciclo cada ~7 s. */
private const val GLOW_BREATH_HZ = 0.14f

/** Pseudoaleatorio determinista 0..1 (sin estado): mismo [n] → mismo valor en cada frame. */
private fun hash01(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/**
 * Conversión entre el espacio del tablero (radios de hexágono) y el `Canvas` (píxeles).
 *
 * El tablero se centra y se escala para caber entero con un margen; la escala es **uniforme**
 * (un solo [radiusPx] para los dos ejes) porque un hexágono estirado dejaría de empalmar con sus
 * vecinos y las curvas mostrarían codos en las fronteras.
 *
 * @property radiusPx radio de un hexágono en píxeles.
 * @property center centro del tablero en el `Canvas`.
 */
internal data class BoardTransform(val radiusPx: Float, val center: Offset) {

    /** Punto del mundo → punto del `Canvas`. */
    fun toScreen(point: HexPoint): Offset =
        Offset(center.x + point.x * radiusPx, center.y + point.y * radiusPx)

    /** Centro del azulejo [coord] en el `Canvas`. */
    fun screenCenter(coord: HexCoord): Offset = toScreen(HexGeometry.center(coord))

    /** Toque en el `Canvas` → celda del tablero (inversa completa, con redondeo cúbico). */
    fun hexAt(tap: Offset): HexCoord = HexGeometry.hexAt(
        HexPoint((tap.x - center.x) / radiusPx, (tap.y - center.y) / radiusPx),
    )

    /** Radio (px) del círculo que circunscribe el tablero entero. */
    val boardRadiusPx: Float get() = radiusPx * (1.5f * HexaOrbitBalance.BOARD_RADIUS + 1f)

    companion object {

        /**
         * Calcula la escala que hace caber un tablero de radio
         * [HexaOrbitBalance.BOARD_RADIUS] en un lienzo de [width] × [height].
         *
         * Las dos extensiones salen de la geometría pointy-top: a lo ancho el tablero mide
         * `√3 · (2R + 1)` radios (centros extremos a `√3·R` más media anchura de hexágono a cada
         * lado) y a lo alto `3R + 2` (centros a `1.5·R` más un radio completo arriba y abajo). Se
         * toma el mínimo de los dos ajustes para que nunca se recorte por el lado estrecho.
         */
        fun of(width: Float, height: Float): BoardTransform {
            val r = HexaOrbitBalance.BOARD_RADIUS
            val worldWidth = SQRT_3 * (2f * r + 1f)
            val worldHeight = 3f * r + 2f
            val radiusPx = min(width / worldWidth, height / worldHeight) * BOARD_FILL
            // Algo por debajo del centro: arriba vive el HUD y, centrado del todo, el tablero
            // quedaba apretado contra él con media pantalla vacía debajo.
            return BoardTransform(radiusPx, Offset(width * 0.5f, height * BOARD_CENTER_Y))
        }
    }
}

/** Estallido de partículas (recogida o fuga), anclado al mundo y al frame en que ocurrió. */
internal data class OrbBurst(val world: HexPoint, val startNanos: Long, val color: Color, val big: Boolean = false)

/** Onda que deja el toque sobre la pieza [coord], desde el frame [startNanos]. */
internal data class TapRipple(val coord: HexCoord, val startNanos: Long)

// ---------------------------------------------------------------------------------------------
// Fondo: resplandor y anillos orbitales
// ---------------------------------------------------------------------------------------------

/**
 * Ambiente tras el tablero: un resplandor del color del juego que **respira** muy despacio y una
 * única **órbita** tenue que gira alrededor de la placa.
 *
 * Es la segunda versión de este fondo. La primera (dos pistas contrarrotantes con satélites
 * brillantes, que aceleraban con la partida) se quitó a petición del usuario por llamar demasiado
 * la atención; esta se pidió "más sutil", y por eso cada decisión va en esa dirección:
 *  - **una** sola órbita, de trazo fino y alfa baja, sin halo;
 *  - giro **lento y constante** ([ORBIT_DEG_PER_SEC]): no reacciona a la velocidad de la
 *    partida, así nunca "se acelera" en la visión periférica mientras el jugador sigue al puntero;
 *  - el satélite es un punto pequeño y apagado, no una luz;
 *  - el resplandor respira con amplitud mínima y periodo largo ([GLOW_BREATH_HZ]).
 *
 * Va por DETRÁS del tablero, así que solo asoma en el margen exterior. Con la alarma de fuga
 * todo vira a rojo, igual que antes.
 *
 * @param time reloj de animación en segundos.
 * @param danger 0..1, intensidad de la alarma de fuga.
 */
internal fun DrawScope.drawBoardAmbience(transform: BoardTransform, time: Float, danger: Float) {
    val tint = lerp(HexaAccent, LogicColors.Error, danger)
    val board = transform.boardRadiusPx
    val breath = 0.5f + 0.5f * sin(time * 2f * PI_F * GLOW_BREATH_HZ)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(tint.copy(alpha = 0.19f + 0.05f * breath), Color.Transparent),
            center = transform.center,
            radius = board * 1.35f,
        ),
        radius = board * 1.35f,
        center = transform.center,
    )

    // Órbita: tres arcos largos con huecos cortos, para que el giro se perciba sin ser un
    // círculo cerrado (que no parecería moverse) ni un punteado (que parpadearía).
    val radius = board * 1.06f
    val topLeft = Offset(transform.center.x - radius, transform.center.y - radius)
    val bounds = Size(radius * 2f, radius * 2f)
    val angle = time * ORBIT_DEG_PER_SEC
    val arcs = 3
    val sweep = 360f / arcs * 0.80f
    val stroke = Stroke(width = transform.radiusPx * 0.022f, cap = StrokeCap.Round)
    for (i in 0 until arcs) {
        drawArc(tint.copy(alpha = 0.20f), angle + i * 360f / arcs, sweep, false, topLeft, bounds, style = stroke)
    }
    // Satélite en la cabeza de un arco: da un punto que seguir con la vista, sin brillar.
    val rad = (angle + sweep) * PI_F / 180f
    drawCircle(
        color = lerp(tint, Color.White, 0.35f).copy(alpha = 0.50f),
        radius = transform.radiusPx * 0.055f,
        center = Offset(transform.center.x + cos(rad) * radius, transform.center.y + sin(rad) * radius),
    )
}

// ---------------------------------------------------------------------------------------------
// Tablero
// ---------------------------------------------------------------------------------------------

/** Escala 0..1(+rebote) de la pieza [coord] durante la entrada del tablero. */
private fun introScale(coord: HexCoord, introSec: Float): Float {
    val local = (introSec - coord.ring * INTRO_RING_STAGGER_SEC) / INTRO_TILE_SEC
    return when {
        local <= 0f -> 0f
        local >= 1f -> 1f
        else -> EaseOutBack.transform(local)
    }
}

/** Contorno hexagonal de radio [radius] px alrededor de [center]. */
private fun hexPath(center: Offset, radius: Float): Path = Path().apply {
    for (i in 0 until HEX_EDGES) {
        val corner = HexGeometry.corner(i)
        val x = center.x + corner.x * radius
        val y = center.y + corner.y * radius
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Las piezas del tablero: caras hexagonales con relieve, separadas por una junta oscura.
 *
 * Cada cara se dibuja algo más pequeña que su celda ([TILE_INSET]) para que entre piezas quede
 * una ranura: leídas así son *fichas que se pueden girar*, no una rejilla pintada. El relleno es
 * un degradado radial (centro algo más claro) que les da volumen sin recurrir a sombras.
 *
 * El contorno **no gira** con la pieza aunque sus caminos sí: un hexágono girado 60° ocupa el
 * mismo sitio, así que animar su borde solo produciría un parpadeo sin significado. Lo que sí
 * hace la pieza recién girada es **encenderse** un instante, para confirmar el toque aunque el
 * jugador no esté mirando ese punto exacto.
 *
 * @param introSec segundos desde que arrancó la partida: las piezas entran en cascada, del
 *   centro hacia fuera.
 */
internal fun DrawScope.drawBoardCells(
    game: HexaOrbitState,
    transform: BoardTransform,
    frameNanos: Long,
    spinStart: Map<HexCoord, Long>,
    introSec: Float,
) {
    val r = transform.radiusPx
    for (coord in game.board.tiles.keys) {
        val scale = introScale(coord, introSec)
        if (scale <= 0f) continue
        val center = transform.screenCenter(coord)
        val face = hexPath(center, r * TILE_INSET * scale)

        drawPath(
            path = face,
            brush = Brush.radialGradient(
                listOf(
                    lerp(LogicColors.SurfaceVariantDark, HexaAccent, 0.26f),
                    LogicColors.SurfaceDark,
                ),
                center = Offset(center.x, center.y - r * 0.25f),
                radius = r * 1.15f,
            ),
            alpha = 0.94f,
        )

        // `EaseOutBack` se pasa de 1 en el rebote: se acota para no dar alfas negativos.
        val flash = (1f - spinProgress(coord, frameNanos, spinStart)).coerceIn(0f, 1f)
        if (flash > 0f) drawPath(face, LogicColors.NeonCyan.copy(alpha = 0.16f * flash))
        drawPath(
            path = face,
            color = lerp(HexaAccent.copy(alpha = 0.55f), LogicColors.NeonCyan, flash),
            style = Stroke(width = r * (0.035f + 0.03f * flash)),
        )
    }
}

/**
 * Los tres caminos de cada azulejo como **tubos de neón apagados**: una ranura oscura con un
 * filamento tenue dentro. Es el "circuito impreso" sobre el que después se enciende el haz.
 *
 * El filamento es deliberadamente claro (mezcla de texto atenuado y acento) y de buen grosor:
 * aunque está "apagado" tiene que leerse sin esfuerzo, porque es lo que el jugador estudia para
 * decidir qué pieza girar.
 *
 * Aquí es donde se aplica el **retraso elástico del giro**: el motor ya dejó la rotación en su
 * valor final, así que se dibuja con un desfase de `−60°` que se consume con `EaseOutBack`. El
 * resultado es el rebote de resorte que pide la §9.4 sin una sola corrutina de animación.
 */
internal fun DrawScope.drawIdlePaths(
    game: HexaOrbitState,
    transform: BoardTransform,
    frameNanos: Long,
    spinStart: Map<HexCoord, Long>,
    introSec: Float,
) {
    val groove = LogicColors.BackgroundDark.copy(alpha = 0.95f)
    // Casi blanco, con un punto del acento: el camino apagado tiene que leerse sin esfuerzo
    // contra la pieza (el jugador decide mirándolo); el haz se distingue por su color y su
    // halo, no porque lo demás esté a oscuras.
    val filament = lerp(LogicColors.OnDark, HexaAccent, 0.22f)
    for ((coord, tile) in game.board.tiles) {
        // Los caminos aparecen cuando su pieza ya casi se ha asentado.
        val shown = ((introScale(coord, introSec) - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (shown <= 0f) continue
        val spin = spinProgress(coord, frameNanos, spinStart)
        val offsetDeg = -SPIN_DEGREES * (1f - spin)
        // Recién girada, el filamento se aviva: refuerza qué pieza acaba de cambiar.
        val lit = lerp(filament, LogicColors.NeonCyan, ((1f - spin) * 0.8f).coerceIn(0f, 1f))
        for (pair in tile.connections) {
            val path = curvePath(HexGeometry.curveFor(pair), coord, transform, offsetDeg, absolute = false)
            strokeCurve(path, transform, groove, 0.26f, shown)
            strokeCurve(path, transform, lit, 0.105f, shown)
        }
    }
}

/**
 * El haz proyectado: los azulejos que el puntero va a recorrer, encendidos con intensidad
 * decreciente.
 *
 * Es **luz fija**: no late ni lleva nada corriendo por dentro. Llegó a tener pulsos de energía
 * fluyendo por el haz y un latido en la alarma, pero a petición del usuario se quitaron — con
 * tanto movimiento dejaba de leerse por dónde va el camino, que es justo lo que el haz tiene que
 * decir. El único elemento que se mueve por los tubos es el puntero.
 *
 * Cada tramo se dibuja en cuatro capas —halo ancho, halo intermedio, trazo nítido y núcleo
 * blanco— (la escalera de `drawNeonTile`, §9.7).
 *
 * El color va de [LogicColors.NeonCyan] a [LogicColors.NeonGreen] según la profundidad, con dos
 * excepciones en rojo:
 *  - si la fuga es **inminente** ([LookaheadPath.imminent]) arde el haz entero — es la
 *    emergencia;
 *  - si la fuga cae lejos pero dentro del horizonte, solo se tiñe **el último tramo**: marca
 *    dónde está la salida sin gritar. Con nueve azulejos de horizonte esto pasa casi siempre en
 *    un tablero de tres anillos, así que teñirlo todo dejaría el haz permanentemente rojo.
 */
internal fun DrawScope.drawProjectedBeam(game: HexaOrbitState, transform: BoardTransform) {
    val steps = game.projection.steps
    if (steps.isEmpty()) return

    val imminent = game.projection.imminent
    val lastIndex = steps.size - 1
    steps.forEachIndexed { index, step ->
        val depth = index.toFloat() / (HexaOrbitBalance.LOOKAHEAD_TILES + 1).toFloat()
        // Desvanecido cuadrático **con suelo**: la caída sigue siendo más marcada al principio
        // (los primeros azulejos son donde de verdad hay que decidir), pero el suelo mantiene la
        // cola legible. Sin él, con nueve azulejos el último tramo caería a ~0.01 de opacidad.
        val falloff = (1f - depth) * (1f - depth)
        val intensity = BEAM_MIN_INTENSITY + (1f - BEAM_MIN_INTENSITY) * falloff
        val color = when {
            imminent -> LogicColors.Error
            game.projection.escapes && index == lastIndex -> LogicColors.Error
            else -> lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, depth)
        }

        val curve = HexGeometry.orientedCurve(step.coord, step.entryEdge, step.exitEdge)
        val path = curvePath(curve, step.coord, transform, 0f, absolute = true)
        strokeCurve(path, transform, color, 0.40f, 0.09f * intensity)
        strokeCurve(path, transform, color, 0.24f, 0.24f * intensity)
        strokeCurve(path, transform, color, 0.125f, 0.97f * intensity)
        strokeCurve(path, transform, Color.White, 0.04f, 0.55f * intensity)
    }
}

/**
 * Orbes de energía: una **gema** que gira dentro de un anillo con satélite, con halo que late.
 *
 * Alternan [LogicColors.Amber] y [LogicColors.Violet] según su id (paridad estable durante toda
 * su vida) para que tres orbes en pantalla se distingan de un vistazo. Cada uno late y gira con
 * un desfase propio (derivado del id), así no palpitan los tres a la vez como un semáforo.
 */
internal fun DrawScope.drawEnergyOrbs(game: HexaOrbitState, transform: BoardTransform, time: Float) {
    for (orb in game.orbs) {
        val center = transform.toScreen(orb.position)
        val color = orbColor(orb.id)
        val phase = (orb.id % 7L).toFloat()
        val pulse = 0.86f + 0.14f * sin(time * 4.2f + phase)
        val radius = transform.radiusPx * ORB_RADIUS_FACTOR * pulse

        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * pulse), Color.Transparent), center, radius * 3.4f),
            radius = radius * 3.4f,
            center = center,
        )
        // Anillo orbital + satélite: hace del orbe un "objetivo" y no un simple punto.
        val ring = radius * 1.9f
        drawCircle(color.copy(alpha = 0.55f), ring, center, style = Stroke(transform.radiusPx * 0.02f))
        val a = time * 3.1f + phase
        drawCircle(Color.White.copy(alpha = 0.9f), transform.radiusPx * 0.03f, Offset(center.x + cos(a) * ring, center.y + sin(a) * ring))

        // Gema: rombo que gira, con una faceta interior clara.
        rotate(degrees = time * 70f + phase * 40f, pivot = center) {
            drawPath(diamondPath(center, radius), Brush.verticalGradient(listOf(lerp(color, Color.White, 0.45f), color), center.y - radius, center.y + radius))
            drawPath(diamondPath(center, radius * 0.45f), Color.White.copy(alpha = 0.75f))
        }
    }
}

/** Color estable de un orbe según la paridad de su id. */
internal fun orbColor(id: Long): Color = if (id % 2L == 0L) LogicColors.Amber else LogicColors.Violet

private fun diamondPath(center: Offset, radius: Float): Path = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius * 0.78f, center.y)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius * 0.78f, center.y)
    close()
}

/**
 * El puntero: un **cometa** — estela con halo que se desvanece hacia atrás, chispas que se
 * desprenden y una cabeza al rojo blanco con destello en cruz.
 *
 * La estela se dibuja como segmentos independientes con alfa creciente y no como un `Path`
 * único: un trazo continuo solo admite una opacidad, y es precisamente el degradado —lo viejo
 * casi transparente, la cabeza a plena luz— lo que da la sensación de velocidad.
 *
 * @param danger 0..1: con la fuga inminente el cometa vira a rojo, igual que el haz.
 */
internal fun DrawScope.drawPointer(game: HexaOrbitState, transform: BoardTransform, time: Float, danger: Float) {
    val r = transform.radiusPx
    val color = lerp(LogicColors.NeonCyan, LogicColors.Error, danger)
    val trail = game.pointer.trail
    for (i in 1 until trail.size) {
        val fade = i.toFloat() / trail.size.toFloat()
        val from = transform.toScreen(trail[i - 1])
        val to = transform.toScreen(trail[i])
        drawLine(color.copy(alpha = fade * 0.20f), from, to, strokeWidth = r * 0.34f * fade, cap = StrokeCap.Round)
        drawLine(lerp(color, Color.White, fade * 0.5f).copy(alpha = fade * 0.85f), from, to, strokeWidth = r * 0.13f * fade, cap = StrokeCap.Round)
    }
    // Chispas: puntos que tiemblan a los lados de la estela. El "tick" cambia ~30 veces por
    // segundo, así que cada chispa salta de sitio: se lee como centelleo, no como puntos fijos.
    val tick = floor(time * 30f).toInt()
    for (i in 2 until trail.size step 3) {
        val fade = i.toFloat() / trail.size.toFloat()
        val at = transform.toScreen(trail[i])
        val jx = (hash01(i * 13 + tick) - 0.5f) * r * 0.36f
        val jy = (hash01(i * 29 + tick) - 0.5f) * r * 0.36f
        drawCircle(Color.White.copy(alpha = fade * 0.8f), r * 0.028f * fade, Offset(at.x + jx, at.y + jy))
    }

    val head = transform.toScreen(game.pointer.position)
    val radius = r * POINTER_RADIUS_FACTOR * (1f + 0.10f * sin(time * 11f))
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.70f), Color.Transparent), head, radius * 4.6f),
        radius = radius * 4.6f,
        center = head,
    )
    // Destello en cruz que gira despacio: el "brillo de lente" de un punto de luz intenso.
    rotate(degrees = time * 40f, pivot = head) {
        val reach = radius * 3.0f
        val flare = Color.White.copy(alpha = 0.55f)
        drawLine(flare, Offset(head.x - reach, head.y), Offset(head.x + reach, head.y), r * 0.022f, StrokeCap.Round)
        drawLine(flare, Offset(head.x, head.y - reach), Offset(head.x, head.y + reach), r * 0.022f, StrokeCap.Round)
    }
    drawCircle(color = color, radius = radius, center = head)
    drawCircle(color = Color.White, radius = radius * 0.58f, center = head)
}

/**
 * Estallido de partículas. Devuelve `false` cuando ya se apagó, y la lista de estallidos usa ese
 * valor para podarse: así no hace falta un temporizador aparte que limpie.
 *
 * Las chispas salen en radial con velocidades distintas y se **frenan** con el tiempo: una
 * explosión que se expande a ritmo uniforme se lee como artificial, mientras que frenar imita la
 * resistencia y remata el momento. Un destello central y una onda de choque hacen que la
 * recogida se lea aunque las chispas queden sobre un tramo ya iluminado del haz.
 */
internal fun DrawScope.drawBurst(burst: OrbBurst, transform: BoardTransform, frameNanos: Long): Boolean {
    val elapsed = (frameNanos - burst.startNanos) / 1_000_000_000f
    val duration = BURST_DURATION_SEC * if (burst.big) 1.5f else 1f
    if (elapsed < 0f || elapsed > duration) return false

    val t = elapsed / duration
    val r = transform.radiusPx
    val center = transform.toScreen(burst.world)
    val ease = 1f - (1f - t) * (1f - t) * (1f - t)
    val reach = r * BURST_SPREAD_FACTOR * if (burst.big) 2.2f else 1f
    val alpha = (1f - t) * (1f - t)
    val seed = (burst.startNanos % 1000L).toInt()

    // Destello central: solo en el primer tercio.
    val flash = (1f - t * 3f).coerceAtLeast(0f)
    if (flash > 0f) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * flash), burst.color.copy(alpha = 0.4f * flash), Color.Transparent), center, reach * 0.8f),
            radius = reach * 0.8f,
            center = center,
        )
    }
    val count = if (burst.big) BURST_PARTICLES * 2 else BURST_PARTICLES
    for (i in 0 until count) {
        val angle = (i + hash01(seed + i) * 0.7f) * (2f * PI_F / count)
        val dist = reach * (0.45f + 0.55f * hash01(seed + i * 7)) * ease
        val pos = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist)
        val color = (if (i % 3 == 0) Color.White else burst.color).copy(alpha = alpha)
        if (i % 2 == 0) {
            // Chispa alargada en la dirección de salida: da sensación de velocidad.
            val tail = r * 0.16f * (1f - t)
            drawLine(color, pos, Offset(pos.x - cos(angle) * tail, pos.y - sin(angle) * tail), r * 0.04f, StrokeCap.Round)
        } else {
            drawCircle(color, r * 0.05f * (1f - t), pos)
        }
    }
    drawCircle(burst.color.copy(alpha = alpha * 0.7f), reach * ease, center, style = Stroke(width = r * 0.05f * (1f - t) + 1f))
    return true
}

/**
 * Onda de un toque: un contorno hexagonal que se abre desde la pieza tocada y se apaga. Es el
 * feedback inmediato del gesto (§9.4) y se ve incluso si el giro no cambia ningún camino a la
 * vista (piezas simétricas). Devuelve `false` al terminar, para podar la lista.
 */
internal fun DrawScope.drawTapRipple(ripple: TapRipple, transform: BoardTransform, frameNanos: Long): Boolean {
    val elapsed = (frameNanos - ripple.startNanos) / 1_000_000_000f
    if (elapsed < 0f || elapsed > RIPPLE_DURATION_SEC) return false
    val t = elapsed / RIPPLE_DURATION_SEC
    val ease = 1f - (1f - t) * (1f - t)
    val r = transform.radiusPx
    val path = hexPath(transform.screenCenter(ripple.coord), r * (TILE_INSET + 0.42f * ease))
    drawPath(path, LogicColors.NeonCyan.copy(alpha = 0.28f * (1f - t)), style = Stroke(r * 0.16f * (1f - t) + 1f))
    drawPath(path, Color.White.copy(alpha = 0.85f * (1f - t)), style = Stroke(r * 0.035f))
    return true
}

/**
 * Viñeta de alarma: los bordes de la pantalla arden en rojo cuando la fuga es inminente.
 *
 * El jugador tiene la vista clavada en el puntero, no en el HUD: la alarma tiene que llegarle
 * por la visión periférica. Late deprisa (es una emergencia) pero solo en los bordes, sin tapar
 * el tablero, que es justo donde tiene que actuar.
 *
 * @param amount 0..1 intensidad (animada por la pantalla al entrar/salir de la alarma).
 */
internal fun DrawScope.drawDangerVignette(amount: Float, time: Float) {
    if (amount <= 0f) return
    val throb = 0.65f + 0.35f * sin(time * 13f)
    // Se pinta DESBORDANDO el lienzo: el juego vive dentro del área segura y, como la
    // viñeta es más intensa justo en los bordes, recortada ahí dejaba a la vista una
    // franja recta arriba y abajo. Sobrada, llega al borde físico de la pantalla.
    val bleed = size.maxDimension * 0.5f
    drawRect(
        brush = Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to LogicColors.Error.copy(alpha = 0.42f * amount * throb),
            center = center,
            radius = size.maxDimension * 0.62f,
        ),
        topLeft = Offset(-bleed, -bleed),
        size = Size(size.width + bleed * 2f, size.height + bleed * 2f),
    )
}

// ---------------------------------------------------------------------------------------------
// Utilidades de dibujo
// ---------------------------------------------------------------------------------------------

/**
 * Construye el `Path` de una [HexCurve] sobre el `Canvas`. Se separa de su trazado
 * ([strokeCurve]) para construirlo UNA vez y pintarlo en varias capas: con 37 piezas × 3 caminos
 * × varias capas por frame, reconstruir el `Path` en cada capa era el grueso de las asignaciones.
 *
 * @param coord azulejo al que pertenece; da el desplazamiento y el pivote del giro.
 * @param rotationDeg giro extra (grados, horario) que se aplica alrededor del centro del
 *   azulejo. Es lo que permite dibujar la pieza "llegando" a su rotación final.
 * @param absolute `true` si la curva ya viene en coordenadas del tablero
 *   ([HexGeometry.orientedCurve]); `false` si es local al azulejo ([HexGeometry.curveFor]).
 */
private fun curvePath(curve: HexCurve, coord: HexCoord, transform: BoardTransform, rotationDeg: Float, absolute: Boolean): Path {
    val origin = HexGeometry.center(coord)
    val pivot = transform.toScreen(origin)
    val rad = rotationDeg * PI_F / 180f
    val cosR = cos(rad)
    val sinR = sin(rad)

    fun project(point: HexPoint): Offset {
        val screen = transform.toScreen(if (absolute) point else point + origin)
        if (rotationDeg == 0f) return screen
        // Rotación en pantalla alrededor del centro del azulejo (Y hacia abajo → horario).
        val dx = screen.x - pivot.x
        val dy = screen.y - pivot.y
        return Offset(pivot.x + dx * cosR - dy * sinR, pivot.y + dx * sinR + dy * cosR)
    }

    val start = project(curve.start)
    val c1 = project(curve.control1)
    val c2 = project(curve.control2)
    val end = project(curve.end)
    return Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
}

/** Traza [path] con grosor [widthFactor] (fracción del radio del hexágono) y opacidad [alpha]. */
private fun DrawScope.strokeCurve(path: Path, transform: BoardTransform, color: Color, widthFactor: Float, alpha: Float) {
    if (alpha <= 0.004f) return
    drawPath(path, color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)), style = Stroke(width = transform.radiusPx * widthFactor, cap = StrokeCap.Round))
}

/**
 * Progreso `0..1` del giro de la pieza [coord], con la elasticidad ya aplicada. Vale `1f`
 * (reposo) si la pieza no ha girado nunca o si su animación ya terminó.
 */
internal fun spinProgress(coord: HexCoord, frameNanos: Long, spinStart: Map<HexCoord, Long>): Float {
    val start = spinStart[coord] ?: return 1f
    val elapsed = (frameNanos - start) / 1_000_000_000f
    if (elapsed <= 0f) return 0f
    if (elapsed >= SPIN_DURATION_SEC) return 1f
    return EaseOutBack.transform(elapsed / SPIN_DURATION_SEC)
}
