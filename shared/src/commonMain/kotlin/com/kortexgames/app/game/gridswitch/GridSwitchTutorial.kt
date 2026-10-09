package com.kortexgames.app.game.gridswitch

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighbors
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawNeonTile
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.grid_switch_tutorial_step_1
import kortexgames.shared.generated.resources.grid_switch_tutorial_step_2
import kortexgames.shared.generated.resources.grid_switch_tutorial_step_3
import kortexgames.shared.generated.resources.grid_switch_tutorial_step_4
import kortexgames.shared.generated.resources.grid_switch_tutorial_step_5
import kotlin.math.sin

/**
 * # Neon Grid Switch — tutorial animado
 *
 * Una etapa de 3×3 resuelta en dos toques. El primero está elegido para que NO sea un acierto
 * limpio: apaga una luz y enciende dos. Es la única idea del juego que no se intuye —"cambiar" no
 * es "apagar"— y verla pasar se entiende mejor que leerla. El segundo toque sí lo apaga todo.
 *
 * El tablero de cada instante sale de aplicar al inicial los toques ya dados con la regla del
 * juego ([LightGrid.toggled]); la escena solo dice dónde y cuándo pulsa el dedo.
 */
object GridSwitchTutorial {

    private const val N = 3

    /** Los dos toques del guion: una esquina y el centro. */
    private val FIRST_TAP = GridPosition(row = 0, col = 2)
    private val SECOND_TAP = GridPosition(row = 1, col = 1)

    /**
     * Tablero inicial: el apagado con esos mismos dos toques dados. Como cada toque se deshace
     * repitiéndolo, repetirlos es por construcción la solución.
     */
    private val START = LightGrid.solved(N).toggled(FIRST_TAP).toggled(SECOND_TAP)

    // Índices de los pasos del guion.
    private const val TOUCH = 1
    private const val EXPLAIN = 2
    private const val SOLVE = 3
    private const val SOLVED = 4

    /** Avance de su paso en el que el dedo da cada toque. */
    private const val FIRST_TAP_AT = 0.55f
    private const val SECOND_TAP_AT = 0.55f

    /** El tutorial que la antesala de Grid Switch inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_GRID_SWITCH,
        accent = CategoryPalette.PatternRecognition,
        steps = listOf(
            TutorialStep(Res.string.grid_switch_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.grid_switch_tutorial_step_2, durationMs = 3_600),
            // Sin gesto: deja mirar el resultado del primer toque con las celdas cambiadas marcadas.
            TutorialStep(Res.string.grid_switch_tutorial_step_3, durationMs = 3_600),
            TutorialStep(Res.string.grid_switch_tutorial_step_4, durationMs = 3_600),
            TutorialStep(Res.string.grid_switch_tutorial_step_5, durationMs = 3_600),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Lo que tarda una luz en encenderse o apagarse, y el destello del toque (s). */
    private const val FADE_SEC = 0.16f
    private const val FLASH_SEC = TOGGLE_FLASH_MS / 1_000f

    /** Duración del gesto de dedo de un toque (s), centrado en el instante en que pulsa. */
    private const val TAP_WINDOW_SEC = 0.9f

    /** Celdas que cambia un toque en [center]: ella y sus vecinas ortogonales. */
    private fun affectedBy(center: GridPosition): Set<GridPosition> = (center.orthogonalNeighbors(N) + center).toSet()

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec
            val firstAt = playback.stepStartSec(TOUCH) + FIRST_TAP_AT * playback.stepDurationSec(TOUCH)
            val secondAt = playback.stepStartSec(SOLVE) + SECOND_TAP_AT * playback.stepDurationSec(SOLVE)

            // El tablero de antes y de después del último toque dado: entre los dos se funde.
            val afterFirst = START.toggled(FIRST_TAP)
            val (before, after, lastTap, lastAt) = when {
                t >= secondAt -> Change(afterFirst, afterFirst.toggled(SECOND_TAP), SECOND_TAP, secondAt)
                t >= firstAt -> Change(START, afterFirst, FIRST_TAP, firstAt)
                else -> Change(START, START, null, 0f)
            }
            val since = t - lastAt
            val fade = (since / FADE_SEC).coerceIn(0f, 1f)
            val flash = if (lastTap == null) 0f else (1f - since / FLASH_SEC).coerceIn(0f, 1f)
            val flashed = lastTap?.let(::affectedBy).orEmpty()

            val side = size.minDimension * 0.84f
            val cell = side / N
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            fun centerOf(pos: GridPosition) = Offset(left + (pos.col + 0.5f) * cell, top + (pos.row + 0.5f) * cell)

            inset(left, top, size.width - left - side, size.height - top - side) { drawBoardBackdrop() }

            for (row in 0 until N) {
                for (col in 0 until N) {
                    val pos = GridPosition(row, col)
                    val was = if (before.cellAt(pos)) 1f else 0f
                    val isNow = if (after.cellAt(pos)) 1f else 0f
                    val touched = pos in flashed
                    drawNeonTile(
                        baseColor = LitAccent,
                        activeAmt = was + (isNow - was) * fade,
                        cornerRadius = (cell * 0.22f).toDp(),
                        sparks = false,
                        baseMargin = (cell * 0.07f).toDp(),
                        strokeScale = 0.85f,
                        rectTopLeft = Offset(left + col * cell, top + row * cell),
                        rectSize = Size(cell, cell),
                        pressAmt = if (touched) flash else 0f,
                        scale = if (touched) 1f + 0.12f * flash else 1f,
                    )
                }
            }

            // Mientras se explica el primer toque, sus celdas quedan marcadas: "estas han cambiado".
            val explaining = t >= firstAt && t < playback.stepStartSec(SOLVE)
            if (explaining) {
                val beat = 0.5f + 0.5f * sin(now * 5f)
                for (pos in affectedBy(FIRST_TAP)) {
                    drawRoundRect(
                        color = LogicColors.OnDark.copy(alpha = 0.45f + 0.40f * beat),
                        topLeft = Offset(left + pos.col * cell, top + pos.row * cell),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.24f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // Tablero apagado: un marco verde que se enciende y se va (el verde es celebración, §9.2).
            val solved = (t - secondAt - 0.25f) / 1.4f
            if (solved > 0f && solved < 1f) {
                drawRoundRect(
                    color = LogicColors.NeonGreen.copy(alpha = sin(solved * 3.1416f)),
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            drawTutorialTap(centerOf(FIRST_TAP), (t - firstAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT, finger, ripple = LitAccent)
            drawTutorialTap(centerOf(SECOND_TAP), (t - secondAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT, finger, ripple = LitAccent)
        }
    }

    /** El último cambio del tablero: de [before] a [after], por el toque en [tap] en el instante [at]. */
    private data class Change(val before: LightGrid, val after: LightGrid, val tap: GridPosition?, val at: Float)
}
