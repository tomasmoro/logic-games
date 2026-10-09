package com.kortexgames.app.game.hexaorbit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_1
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_2
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_3
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_4
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_5
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_6
import kortexgames.shared.generated.resources.hexa_orbit_tutorial_step_7
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * # Hexa Orbit — tutorial animado
 *
 * Una partida corta en un tablero pequeño (dos anillos en vez de tres, para que las piezas se
 * lean en el recuadro del tutorial) en la que el dedo gira **dos piezas**, cada una por un motivo
 * distinto — que son los dos motivos por los que se gira en este juego:
 *  1. para llevar el haz por encima de un orbe;
 *  2. para apartarlo del borde cuando se pone rojo.
 *
 * ## Una simulación, no una animación dibujada a mano
 * El tablero ([BOARD]) y los dos toques son el único dato. El recorrido del puntero sale de
 * simularlo con las reglas del juego ([HexTile.exitEdgeFor], [HexCurveMetrics]) al cargar el
 * objeto ([SCRIPT]), y cada frame se construye un [HexaOrbitState] real que pintan las mismas
 * funciones que la partida — haz proyectado incluido, que se recalcula con [project]. Por eso el
 * haz cambia de ruta y se tiñe de rojo "solo": nadie le dice cuándo.
 *
 * Como los instantes que importan (cuándo cruza el orbe, cuándo empieza la alarma) dependen del
 * recorrido, **la duración de cada paso se deriva de la simulación** en vez de escribirse a mano:
 * así el texto de "el haz se pone rojo" entra justo cuando se pone rojo.
 *
 * El tablero no es casual: se buscó uno en el que el primer giro lleve al orbe, el recorrido
 * resultante acabe saliéndose, y el segundo giro lo deje en un circuito del que ya no se escapa.
 *
 * ## Licencia respecto a la partida
 * El puntero va a rapidez constante y bastante más lento que jugando, para que dé tiempo a leer.
 */
object HexaOrbitTutorial {

    /** Anillos del tablero del tutorial (la partida usa [HexaOrbitBalance.BOARD_RADIUS]). */
    private const val RADIUS = 2

    /** Rapidez del puntero, en radios de hexágono por segundo: ~0,4× la inicial de la partida. */
    private const val SPEED = 0.9f

    /** Segundos que el puntero espera en la salida, mientras las piezas entran en cascada. */
    private const val START_DELAY_SEC = 0.8f

    private fun tile(q: Int, r: Int, pattern: TilePattern, rotation: Int) =
        HexCoord(q, r) to HexTile(HexCoord(q, r), pattern, rotation)

    /** El tablero de ejemplo, antes de ningún giro. */
    private val BOARD = HexBoard(
        radius = RADIUS,
        tiles = mapOf(
            tile(-2, 0, TilePattern.WIDE_PAIR, 2),
            tile(-2, 1, TilePattern.WIDE_PAIR, 4),
            tile(-2, 2, TilePattern.WIDE_PAIR, 4),
            tile(-1, -1, TilePattern.TRIPLE_SHARP, 5),
            tile(-1, 0, TilePattern.TRIPLE_SHARP, 2),
            tile(-1, 1, TilePattern.TRIPLE_SHARP, 4),
            tile(-1, 2, TilePattern.SHARP_BRIDGE, 1),
            tile(0, -2, TilePattern.WIDE_PAIR, 0),
            tile(0, -1, TilePattern.TRIPLE_SHARP, 0),
            tile(0, 0, TilePattern.WIDE_PAIR, 5),
            tile(0, 1, TilePattern.TRIPLE_SHARP, 5),
            tile(0, 2, TilePattern.TRIPLE_SHARP, 1),
            tile(1, -2, TilePattern.TRIPLE_SHARP, 1),
            tile(1, -1, TilePattern.SHARP_BRIDGE, 5),
            tile(1, 0, TilePattern.WIDE_PAIR, 1),
            tile(1, 1, TilePattern.SHARP_BRIDGE, 5),
            tile(2, -2, TilePattern.TRIPLE_SHARP, 0),
            tile(2, -1, TilePattern.WIDE_PAIR, 1),
            tile(2, 0, TilePattern.SHARP_BRIDGE, 0),
        ),
    )

    /** Arista por la que el puntero entra en la pieza central al empezar. */
    private const val START_EDGE = 3

    /** Primer giro: lo hace el dedo cuando el puntero va por la mitad de su tramo nº [ORB_TAP_STEP]. */
    private val ORB_TILE = HexCoord(0, 2)
    private const val ORB_TAP_STEP = 5

    /** El orbe: en la arista por la que el recorrido sale de su tramo nº [ORB_STEP], tras ese giro. */
    private const val ORB_STEP = 8

    /** Segundo giro (el que evita la fuga): con el puntero a dos tramos del último antes del borde. */
    private val RESCUE_TILE = HexCoord(0, -1)
    private const val RESCUE_TILES_BEFORE_EDGE = 2

    /** Id del orbe. Par → ámbar (ver [orbColor]). */
    private const val ORB_ID = 2L

    /**
     * El recorrido ya simulado y sus instantes clave (segundos de guion).
     *
     * @property steps tramos que recorre el puntero, en orden.
     * @property startsAt instante en que empieza cada tramo, más el final del último.
     * @property orbTapAt, rescueTapAt cuándo pulsa el dedo cada pieza.
     * @property orbAt cuándo el puntero cruza la arista del orbe.
     * @property alarmAt cuándo la fuga pasa a ser inminente (el haz se pone rojo).
     * @property initialBoard, boardAfterOrbTap, boardAfterRescue el tablero antes de tocar nada
     *   y tras cada uno de los dos giros.
     *
     * `internal` (junto con [SCRIPT]) solo para `HexaOrbitTutorialTest`, que comprueba que el
     * tablero sigue contando la historia del guion si alguien retoca el balance del juego.
     */
    internal class Script(
        val steps: List<TraversalStep>,
        val startsAt: FloatArray,
        val orbTapAt: Float,
        val orbAt: Float,
        val alarmAt: Float,
        val rescueTapAt: Float,
        val orb: EnergyOrb,
        val initialBoard: HexBoard,
        val boardAfterOrbTap: HexBoard,
        val boardAfterRescue: HexBoard,
    )

    private fun durationOf(step: TraversalStep) = HexCurveMetrics.lengthOf(step.entryEdge, step.exitEdge) / SPEED

    /** Recorre [board] desde [from] sin límite de horizonte, hasta [limit] tramos o hasta salirse. */
    private fun walk(board: HexBoard, from: HexCoord, entryEdge: Int, limit: Int): List<TraversalStep> =
        board.project(from, entryEdge, maxTiles = limit - 1).steps

    internal val SCRIPT: Script = run {
        // 1) Sin tocar nada, hasta el tramo en que el dedo hace el primer giro.
        val untouched = walk(BOARD, HexCoord.ORIGIN, START_EDGE, limit = ORB_TAP_STEP + 2)
        val afterOrbTap = BOARD.withTileRotated(ORB_TILE)
        // 2) Con la pieza del orbe girada: este recorrido acaba saliéndose del tablero.
        val next = untouched[ORB_TAP_STEP + 1]
        val doomed = untouched.take(ORB_TAP_STEP + 1) + walk(afterOrbTap, next.coord, next.entryEdge, limit = 40)
        val lastOnBoard = doomed.lastIndex
        val rescueStep = lastOnBoard - RESCUE_TILES_BEFORE_EDGE
        // 3) Con la segunda pieza girada: un circuito del que ya no se sale.
        val afterRescue = afterOrbTap.withTileRotated(RESCUE_TILE)
        val saved = doomed[rescueStep + 1]
        val steps = doomed.take(rescueStep + 1) + walk(afterRescue, saved.coord, saved.entryEdge, limit = 40)

        val startsAt = FloatArray(steps.size + 1)
        startsAt[0] = START_DELAY_SEC
        steps.forEachIndexed { i, step -> startsAt[i + 1] = startsAt[i] + durationOf(step) }
        fun middleOf(step: Int) = (startsAt[step] + startsAt[step + 1]) / 2f

        val orbStep = steps[ORB_STEP]
        Script(
            steps = steps,
            startsAt = startsAt,
            orbTapAt = middleOf(ORB_TAP_STEP),
            orbAt = startsAt[ORB_STEP + 1],
            // La alarma salta cuando al borde le quedan ESCAPE_ALERT_TILES piezas por delante.
            alarmAt = startsAt[lastOnBoard - HexaOrbitBalance.ESCAPE_ALERT_TILES],
            rescueTapAt = middleOf(rescueStep),
            orb = EnergyOrb(ORB_ID, orbStep.coord, HexGeometry.edgeMidpoint(orbStep.exitEdge)),
            initialBoard = BOARD,
            boardAfterOrbTap = afterOrbTap,
            boardAfterRescue = afterRescue,
        )
    }

    /** Margen de texto antes y después de cada toque del dedo (s): el toque cae en mitad de su paso. */
    private const val AROUND_TAP_SEC = 1.7f

    /** Lo que el segundo toque tarda en llegar dentro de su paso: antes va el aviso "gira ya". */
    private const val BEFORE_RESCUE_SEC = 1.5f

    private fun ms(seconds: Float) = (seconds.coerceAtLeast(2f) * 1_000f).roundToInt()

    /**
     * El tutorial que la antesala de Hexa Orbit inyecta en el diálogo genérico. Las duraciones
     * salen de [SCRIPT]: cada paso empieza donde ocurre lo que su texto cuenta.
     */
    val tutorial: GameTutorial = run {
        val intro = (SCRIPT.orbTapAt - AROUND_TAP_SEC) / 2f
        val orbRunStart = SCRIPT.orbTapAt + AROUND_TAP_SEC
        GameTutorial(
            gameId = GameIds.HEXA_ORBIT,
            accent = CategoryPalette.SpatialVision,
            steps = listOf(
                TutorialStep(Res.string.hexa_orbit_tutorial_step_1, durationMs = ms(intro)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_2, durationMs = ms(intro)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_3, durationMs = ms(AROUND_TAP_SEC * 2f)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_4, durationMs = ms(SCRIPT.alarmAt - orbRunStart)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_5, durationMs = ms(SCRIPT.rescueTapAt - BEFORE_RESCUE_SEC - SCRIPT.alarmAt)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_6, durationMs = ms(BEFORE_RESCUE_SEC + AROUND_TAP_SEC)),
                TutorialStep(Res.string.hexa_orbit_tutorial_step_7, durationMs = 4_500),
            ),
            scene = { playback, modifier -> Scene(playback, modifier) },
        )
    }

    /** Separación entre muestras de la estela del puntero (s). */
    private const val TRAIL_STEP_SEC = 0.03f

    /** Lo que tarda la alarma en encenderse y apagarse (s). */
    private const val ALARM_FADE_SEC = 0.25f

    /** Duración del gesto de dedo de un toque (s), centrado en el instante en que pulsa. */
    private const val TAP_WINDOW_SEC = 0.9f

    /** Tramo en el que está el puntero en el instante [t] (el último, si el guion se alarga). */
    private fun stepIndexAt(t: Float): Int {
        var i = 0
        while (i < SCRIPT.steps.lastIndex && t >= SCRIPT.startsAt[i + 1]) i++
        return i
    }

    /** Posición del puntero en el instante [t]. */
    private fun positionAt(t: Float): HexPoint {
        val i = stepIndexAt(t)
        val length = SCRIPT.startsAt[i + 1] - SCRIPT.startsAt[i]
        return pointOnStep(SCRIPT.steps[i], ((t - SCRIPT.startsAt[i]) / length).coerceIn(0f, 1f))
    }

    /** El estado de partida del instante [t], tal como lo publicaría el motor. */
    private fun stateAt(t: Float): HexaOrbitState {
        val board = when {
            t >= SCRIPT.rescueTapAt -> SCRIPT.boardAfterRescue
            t >= SCRIPT.orbTapAt -> SCRIPT.boardAfterOrbTap
            else -> BOARD
        }
        val i = stepIndexAt(t)
        val step = SCRIPT.steps[i]
        val length = SCRIPT.startsAt[i + 1] - SCRIPT.startsAt[i]
        val collected = t >= SCRIPT.orbAt
        return HexaOrbitState(
            board = board,
            pointer = PointerState(
                coord = step.coord,
                entryEdge = step.entryEdge,
                exitEdge = step.exitEdge,
                progress = ((t - SCRIPT.startsAt[i]) / length).coerceIn(0f, 1f),
                position = positionAt(t),
                trail = List(HexaOrbitBalance.TRAIL_POINTS) { k ->
                    positionAt(t - (HexaOrbitBalance.TRAIL_POINTS - 1 - k) * TRAIL_STEP_SEC)
                },
            ),
            // El haz no está guionizado: es el del juego, sobre el tablero de este instante.
            projection = board.project(step.coord, step.entryEdge),
            orbs = if (collected) emptyList() else listOf(SCRIPT.orb),
            speed = SPEED,
            collected = if (collected) 1 else 0,
        )
    }

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        Canvas(modifier) {
            val t = playback.timelineSec
            val nanos = (t * 1_000_000_000f).toLong()
            fun nanosOf(seconds: Float) = (seconds * 1_000_000_000f).toLong()

            // Misma receta que `BoardTransform.of`, para un tablero de RADIUS anillos y centrado
            // (aquí no hay HUD encima que obligue a bajarlo).
            val transform = BoardTransform(
                radiusPx = min(size.width / (1.7320508f * (2f * RADIUS + 1f)), size.height / (3f * RADIUS + 2f)) * 0.94f,
                center = Offset(size.width * 0.5f, size.height * 0.5f),
            )
            val game = stateAt(t)
            val danger = when {
                t >= SCRIPT.rescueTapAt -> 1f - (t - SCRIPT.rescueTapAt) / ALARM_FADE_SEC
                else -> (t - SCRIPT.alarmAt) / ALARM_FADE_SEC
            }.coerceIn(0f, 1f)
            // Giros ya hechos: de su instante sale el rebote elástico de los caminos.
            val spins = buildMap {
                if (t >= SCRIPT.orbTapAt) put(ORB_TILE, nanosOf(SCRIPT.orbTapAt))
                if (t >= SCRIPT.rescueTapAt) put(RESCUE_TILE, nanosOf(SCRIPT.rescueTapAt))
            }

            drawBoardCells(game, transform, nanos, spins, introSec = t)
            drawIdlePaths(game, transform, nanos, spins, introSec = t)
            drawTapRipple(TapRipple(ORB_TILE, nanosOf(SCRIPT.orbTapAt)), transform, nanos)
            drawTapRipple(TapRipple(RESCUE_TILE, nanosOf(SCRIPT.rescueTapAt)), transform, nanos)
            drawProjectedBeam(game, transform)
            drawEnergyOrbs(game, transform, playback.clockSec)
            drawPointer(game, transform, playback.clockSec, danger)
            drawBurst(OrbBurst(SCRIPT.orb.position, nanosOf(SCRIPT.orbAt), orbColor(ORB_ID)), transform, nanos)
            drawDangerVignette(danger, playback.clockSec)

            drawTutorialTap(
                at = transform.screenCenter(ORB_TILE),
                progress = (t - SCRIPT.orbTapAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT,
                finger = finger,
                ripple = CategoryPalette.SpatialVision,
            )
            drawTutorialTap(
                at = transform.screenCenter(RESCUE_TILE),
                progress = (t - SCRIPT.rescueTapAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT,
                finger = finger,
                ripple = CategoryPalette.SpatialVision,
            )
        }
    }
}
