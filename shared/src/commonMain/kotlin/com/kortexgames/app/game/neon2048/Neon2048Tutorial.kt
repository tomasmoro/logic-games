package com.kortexgames.app.game.neon2048

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_DRAG_FROM
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawTutorialDrag
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.neon2048_tutorial_step_1
import kortexgames.shared.generated.resources.neon2048_tutorial_step_2
import kortexgames.shared.generated.resources.neon2048_tutorial_step_3
import kortexgames.shared.generated.resources.neon2048_tutorial_step_4
import kortexgames.shared.generated.resources.neon2048_tutorial_step_5

/**
 * # Neon Grid 2048 — tutorial animado
 *
 * Tres deslizamientos sobre un tablero casi vacío, cada uno para enseñar una cosa:
 *  1. deslizar manda TODAS las fichas al borde (y después aparece una nueva);
 *  2. dos iguales que chocan se suman;
 *  3. lo sumado se vuelve a sumar — que es como se llega a 2048.
 *
 * ## Un guion de deslizamientos sobre el motor real
 * El tablero inicial, la dirección de cada deslizamiento y dónde aparece la ficha nueva son el
 * único dato ([MOVES]). A dónde va cada ficha y cuáles se fusionan lo decide [collapse], la misma
 * función que usa la partida, al cargar el objeto ([TURNS]); la escena solo interpola entre el
 * antes y el después de cada jugada. Así el tutorial no puede enseñar una fusión que el juego no
 * haría (p. ej. que una ficha se sume dos veces en el mismo movimiento).
 *
 * La ficha nueva aparece donde dice el guion y no al azar, como en la partida: un tutorial tiene
 * que verse igual cada vez.
 */
object Neon2048Tutorial {

    private const val N = 4

    /** Fichas con las que arranca el tablero de ejemplo. */
    private val START = listOf(
        Tile(id = 1, value = 2, row = 1, col = 0),
        Tile(id = 2, value = 2, row = 2, col = 3),
        Tile(id = 3, value = 4, row = 0, col = 2),
    )

    /**
     * Un deslizamiento del guion.
     *
     * @property step paso en el que ocurre, y [at] el avance `0..1` en que las fichas echan a andar.
     * @property spawn la ficha nueva que aparece después.
     * @property spawnStep paso en el que aparece esa ficha: el mismo, salvo en el primer
     *   deslizamiento, donde se retrasa al paso siguiente para que su texto la presente.
     */
    private class Move(
        val step: Int,
        val at: Float,
        val direction: Direction,
        val spawn: Tile,
        val spawnStep: Int = step,
    )

    private val MOVES = listOf(
        Move(step = 0, at = 0.62f, direction = Direction.RIGHT, spawn = Tile(id = 4, value = 2, row = 3, col = 1), spawnStep = 1),
        Move(step = 2, at = 0.50f, direction = Direction.DOWN, spawn = Tile(id = 5, value = 2, row = 0, col = 0)),
        Move(step = 3, at = 0.50f, direction = Direction.DOWN, spawn = Tile(id = 6, value = 2, row = 1, col = 2)),
    )

    /**
     * Una jugada ya resuelta por [collapse].
     *
     * @property before fichas justo antes de deslizar, por id (de aquí sale de dónde parte cada una).
     * @property outcome el resultado del motor: fichas en su sitio final y las absorbidas.
     */
    private class Turn(val before: Map<Long, Tile>, val outcome: MoveOutcome)

    private val TURNS: List<Turn> = buildList {
        var tiles = START
        for (move in MOVES) {
            val outcome = collapse(tiles, move.direction, N)
            add(Turn(tiles.associateBy { it.id }, outcome))
            tiles = outcome.tiles + move.spawn
        }
    }

    /** El tutorial que la antesala de 2048 inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_2048,
        accent = CategoryPalette.MentalMath,
        steps = listOf(
            TutorialStep(Res.string.neon2048_tutorial_step_1, durationMs = 3_600),
            TutorialStep(Res.string.neon2048_tutorial_step_2, durationMs = 3_000),
            TutorialStep(Res.string.neon2048_tutorial_step_3, durationMs = 3_600),
            TutorialStep(Res.string.neon2048_tutorial_step_4, durationMs = 3_600),
            // Solo texto: llenar un tablero no cabe en una escena de pocos segundos.
            TutorialStep(Res.string.neon2048_tutorial_step_5, durationMs = 4_500),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Lo que tardan las fichas en deslizarse, el "pop" de la fusión y el brote de la ficha nueva (s). */
    private const val SLIDE_SEC = 0.20f
    private const val MERGE_POP_SEC = 0.18f
    private const val SPAWN_POP_SEC = 0.28f

    /** Retardo de la ficha nueva tras acabar el deslizamiento (o tras empezar su paso, si va retrasada). */
    private const val SPAWN_DELAY_SEC = 0.25f

    /** Duración del gesto completo del dedo (s). Las fichas arrancan cuando el dedo empieza a moverse. */
    private const val SWIPE_SEC = 1.5f
    private const val SWIPE_FIRES_AT = TUTORIAL_DRAG_FROM + 0.10f

    /** Recorrido del dedo, como fracción del lado del tablero. */
    private const val SWIPE_REACH = 0.26f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        val measurer = rememberTextMeasurer()
        val numberStyle = MaterialTheme.typography.displayLarge
        Canvas(modifier) {
            val t = playback.timelineSec
            fun firesAt(move: Move) = playback.stepStartSec(move.step) + move.at * playback.stepDurationSec(move.step)
            fun spawnsAt(move: Move) = if (move.spawnStep == move.step) {
                firesAt(move) + SLIDE_SEC + SPAWN_DELAY_SEC
            } else {
                playback.stepStartSec(move.spawnStep) + SPAWN_DELAY_SEC
            }

            // --- Composición: placa cuadrada centrada, casillas con hueco entre ellas ----------
            val side = size.minDimension * 0.86f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val pad = 8.dp.toPx()
            val gap = 6.dp.toPx()
            val cell = (side - pad * 2f - gap * (N - 1)) / N
            fun cornerOf(row: Float, col: Float) = Offset(left + pad + col * (cell + gap), top + pad + row * (cell + gap))

            inset(left, top, size.width - left - side, size.height - top - side) {
                drawNeonBoardPlate(accent = CategoryPalette.MentalMath)
            }
            for (row in 0 until N) {
                for (col in 0 until N) drawSocket(cornerOf(row.toFloat(), col.toFloat()), cell)
            }

            fun tile(value: Int, row: Float, col: Float, scale: Float = 1f) =
                drawTile(measurer, numberStyle, value, cornerOf(row, col), cell, scale)

            // --- Fichas: la última jugada disparada, a medio camino entre su antes y su después --
            val current = MOVES.indexOfLast { t >= firesAt(it) }
            if (current < 0) {
                START.forEach { tile(it.value, it.row.toFloat(), it.col.toFloat()) }
            } else {
                val move = MOVES[current]
                val turn = TURNS[current]
                val since = t - firesAt(move)
                val slide = (since / SLIDE_SEC).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val arrived = slide >= 1f

                // Las absorbidas viajan hasta el punto de fusión y desaparecen al llegar. Van
                // debajo, como en la partida, para quedar tapadas por la que sobrevive.
                if (!arrived) {
                    for (ghost in turn.outcome.ghosts) {
                        tile(
                            value = ghost.value,
                            row = ghost.from.row + (ghost.to.row - ghost.from.row) * slide,
                            col = ghost.from.col + (ghost.to.col - ghost.from.col) * slide,
                        )
                    }
                }
                for (after in turn.outcome.tiles) {
                    val before = turn.before.getValue(after.id)
                    // La que se fusiona enseña su valor viejo hasta que llega; entonces cambia y salta.
                    val pop = if (after.isMerged && arrived) {
                        val p = ((since - SLIDE_SEC) / MERGE_POP_SEC).coerceIn(0f, 1f)
                        1f + 0.2f * kotlin.math.sin(p * 3.1416f)
                    } else {
                        1f
                    }
                    tile(
                        value = if (arrived) after.value else before.value,
                        row = before.row + (after.row - before.row) * slide,
                        col = before.col + (after.col - before.col) * slide,
                        scale = pop,
                    )
                }
                // La ficha nueva brota con rebote.
                val born = (t - spawnsAt(move)) / SPAWN_POP_SEC
                if (born > 0f) {
                    tile(move.spawn.value, move.spawn.row.toFloat(), move.spawn.col.toFloat(), EaseOutBack.transform(born.coerceAtMost(1f)))
                }
            }

            // --- El dedo: un deslizamiento por jugada, cruzando el centro del tablero ----------
            val center = Offset(left + side / 2f, top + side / 2f)
            for (move in MOVES) {
                val start = firesAt(move) - SWIPE_FIRES_AT * SWIPE_SEC
                drawTutorialDrag((t - start) / SWIPE_SEC, finger, ripple = CategoryPalette.MentalMath) { fraction ->
                    val along = (fraction - 0.5f) * 2f * SWIPE_REACH * side
                    Offset(center.x + move.direction.deltaCol * along, center.y + move.direction.deltaRow * along)
                }
            }
        }
    }

    /** Casilla vacía: el mismo zócalo hundido del tablero de la partida. */
    private fun DrawScope.drawSocket(topLeft: Offset, side: Float) {
        val corner = CornerRadius(side * 0.22f)
        drawRoundRect(LogicColors.BackgroundDark.copy(alpha = 0.55f), topLeft, Size(side, side), corner)
        drawRoundRect(LogicColors.SurfaceVariantDark.copy(alpha = 0.70f), topLeft, Size(side, side), corner, style = Stroke(1.dp.toPx()))
    }

    /**
     * Una ficha completa: el cristal de la partida ([drawTileGlass]) y su número encima, con el
     * mismo halo y la misma proporción de fuente que `TileFace`.
     *
     * @param scale escala alrededor del centro de la casilla (brote, "pop" de fusión).
     */
    private fun DrawScope.drawTile(
        measurer: TextMeasurer,
        numberStyle: TextStyle,
        value: Int,
        topLeft: Offset,
        side: Float,
        scale: Float,
    ) {
        if (scale <= 0f) return
        val accent = tileAccent(value.countTrailingZeroBits())
        val layout = measurer.measure(
            text = value.toString(),
            style = numberStyle.copy(
                // Misma regla que `fontSizeFor`; en el tutorial no pasa de dos cifras.
                fontSize = (side * 0.42f).toSp(),
                shadow = Shadow(color = accent.copy(alpha = 0.90f), offset = Offset.Zero, blurRadius = 18f),
            ),
        )
        val center = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)
        scale(scale, scale, pivot = center) {
            drawTileGlass(value, topLeft, side)
            drawText(
                textLayoutResult = layout,
                color = lerp(LogicColors.OnDark, accent, TILE_TEXT_TINT),
                topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
            )
        }
    }
}
