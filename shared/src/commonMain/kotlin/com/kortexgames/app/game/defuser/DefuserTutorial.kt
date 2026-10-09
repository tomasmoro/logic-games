package com.kortexgames.app.game.defuser

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_FROM
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_TO
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawTutorialHold
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.defuser_tutorial_step_1
import kortexgames.shared.generated.resources.defuser_tutorial_step_2
import kortexgames.shared.generated.resources.defuser_tutorial_step_3
import kortexgames.shared.generated.resources.defuser_tutorial_step_4
import kortexgames.shared.generated.resources.defuser_tutorial_step_5
import kortexgames.shared.generated.resources.defuser_tutorial_step_6
import kortexgames.shared.generated.resources.defuser_tutorial_step_7
import kortexgames.shared.generated.resources.defuser_tutorial_step_8
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * # Neon Defuser (Buscaminas) — tutorial animado
 *
 * Una partida entera en un panel de 5×4 con dos minas, jugada por un dedo fantasma. No enseña
 * solo los gestos (tocar destapa, mantener marca): enseña **a razonar**, que es lo que nadie
 * deduce solo en un buscaminas. El panel está elegido para que cada jugada del guion sea una
 * deducción cierta, nunca una apuesta:
 *  1. el primer toque abre una zona en cascada;
 *  2. queda un `1` al que solo le rodea UNA casilla tapada → ahí está la mina → escudo;
 *  3. el `1` de al lado ya tiene su mina marcada → su otra casilla tapada es segura → se destapa;
 *  4. lo mismo con el resto, y el panel queda desactivado.
 *
 * ## Un guion de jugadas sobre un panel real
 * Las minas ([MINES]) son el único dato: los números, y qué casillas arrastra cada cascada, se
 * calculan con las reglas del juego al cargar el objeto ([NUMBERS], [REVEALS]). El guion solo
 * dice dónde y cuándo pulsa el dedo. Así el tutorial no puede enseñar un número mal contado ni
 * una cascada que el juego no haría.
 *
 * Las casillas se pintan con la misma función que el panel de la partida (`drawCell`).
 */
object DefuserTutorial {

    private const val ROWS = 4
    private const val COLUMNS = 5

    private fun at(row: Int, col: Int) = row * COLUMNS + col
    private fun rowOf(index: Int) = index / COLUMNS
    private fun colOf(index: Int) = index % COLUMNS

    /** Las dos minas del panel de ejemplo. La de la esquina nunca llega a marcarse: no hace falta. */
    private val MINES = setOf(at(0, 4), at(3, 2))

    /** Vecinas (hasta 8) de una casilla. */
    private fun neighbors(index: Int): List<Int> = (0 until ROWS * COLUMNS).filter {
        it != index && abs(rowOf(it) - rowOf(index)) <= 1 && abs(colOf(it) - colOf(index)) <= 1
    }

    /** Número de cada casilla: cuántas minas la rodean. */
    private val NUMBERS = IntArray(ROWS * COLUMNS) { index -> neighbors(index).count { it in MINES } }

    // Índices de los pasos del guion.
    private const val FIRST_TAP = 1
    private const val NUMBERS_STEP = 2
    private const val DEDUCE = 3
    private const val FLAG = 4
    private const val SAFE = 5
    private const val FINISH = 6
    private const val WON = 7

    /** Casillas protagonistas del razonamiento. */
    private val CLUE = at(2, 1)        // el 1 con una sola casilla tapada alrededor
    private val MINE = at(3, 2)        // esa casilla: la mina que se marca
    private val SECOND_CLUE = at(2, 2) // el 1 vecino, que ya "tiene" su mina
    private val SAFE_CELL = at(3, 3)   // su otra casilla tapada: segura

    /**
     * Un toque que destapa.
     *
     * @property step paso del guion en el que ocurre.
     * @property at avance `0..1` de ese paso en el que el dedo pulsa.
     */
    private class Tap(val step: Int, val at: Float, val cell: Int)

    private val TAPS = listOf(
        Tap(FIRST_TAP, 0.40f, at(0, 0)),
        Tap(SAFE, 0.62f, SAFE_CELL),
        Tap(FINISH, 0.40f, at(2, 4)),
    )

    /**
     * Una casilla destapada por el guion.
     *
     * @property tap índice en [TAPS] del toque que la destapa.
     * @property distance a cuántas casillas del toque está: el retardo con que le llega la cascada.
     */
    private class Reveal(val tap: Int, val distance: Int)

    /**
     * Qué toque destapa cada casilla (`null` = sigue tapada al final: las minas). Se obtiene
     * jugando [TAPS] en orden con la regla del juego: destapar un 0 destapa a sus vecinas.
     */
    private val REVEALS: Array<Reveal?> = arrayOfNulls<Reveal>(ROWS * COLUMNS).also { reveals ->
        TAPS.forEachIndexed { tapIndex, tap ->
            val queue = ArrayDeque(listOf(tap.cell))
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                if (reveals[cell] != null || cell in MINES) continue
                val distance = max(abs(rowOf(cell) - rowOf(tap.cell)), abs(colOf(cell) - colOf(tap.cell)))
                reveals[cell] = Reveal(tapIndex, distance)
                if (NUMBERS[cell] == 0) queue.addAll(neighbors(cell))
            }
        }
    }

    /** El tutorial que la antesala del Buscaminas inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_DEFUSER,
        accent = CategoryPalette.Attention,
        steps = listOf(
            TutorialStep(Res.string.defuser_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.defuser_tutorial_step_2, durationMs = 3_400),
            TutorialStep(Res.string.defuser_tutorial_step_3, durationMs = 3_800),
            TutorialStep(Res.string.defuser_tutorial_step_4, durationMs = 3_800),
            TutorialStep(Res.string.defuser_tutorial_step_5, durationMs = 3_600),
            TutorialStep(Res.string.defuser_tutorial_step_6, durationMs = 4_200),
            TutorialStep(Res.string.defuser_tutorial_step_7, durationMs = 3_400),
            TutorialStep(Res.string.defuser_tutorial_step_8, durationMs = 3_600),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Duración del gesto de dedo de un toque (s), centrado en el instante en que pulsa. */
    private const val TAP_WINDOW_SEC = 0.9f

    /** Retardo de la cascada por casilla de distancia, y lo que tarda cada una en abrirse (s). */
    private const val CASCADE_STEP_SEC = 0.07f
    private const val REVEAL_SEC = 0.28f

    /** Segundos de pulsación mantenida hasta que aparece el escudo, como el toque largo real. */
    private const val LONG_PRESS_SEC = 0.45f

    /** Tamaño del escudo respecto a la casilla; el mismo que en la partida. */
    private const val FLAG_FRACTION = 0.72f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        val shield = rememberVectorPainter(KortexIcons.Shield)
        val measurer = rememberTextMeasurer()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec
            fun tapTime(tap: Tap) = playback.stepStartSec(tap.step) + tap.at * playback.stepDurationSec(tap.step)

            // El escudo aparece tras mantener pulsado un instante, no al apoyar el dedo.
            val flagAt = playback.stepStartSec(FLAG) +
                TUTORIAL_HOLD_FROM * playback.stepDurationSec(FLAG) + LONG_PRESS_SEC
            val flagged = t >= flagAt
            val wonAt = playback.stepStartSec(WON) + 0.2f
            val won = ((t - wonAt) / 0.5f).coerceIn(0f, 1f)

            // Casilla que el dedo tiene hundida ahora mismo (su tecla se dibuja pulsada).
            val holdProgress = playback.stepProgress(FLAG)
            val pressed = when {
                holdProgress > TUTORIAL_HOLD_FROM && holdProgress < TUTORIAL_HOLD_TO && !flagged -> MINE
                else -> TAPS.firstOrNull { abs(t - tapTime(it)) < 0.07f }?.cell ?: -1
            }

            // --- Composición: panel centrado sobre su placa ---------------------------------
            val pad = 6.dp.toPx()
            val cell = minOf((size.width * 0.94f - pad * 2f) / COLUMNS, (size.height * 0.92f - pad * 2f) / ROWS)
            val left = (size.width - cell * COLUMNS) / 2f
            val top = (size.height - cell * ROWS) / 2f
            fun topLeftOf(index: Int) = Offset(left + colOf(index) * cell, top + rowOf(index) * cell)
            fun centerOf(index: Int) = topLeftOf(index) + Offset(cell / 2f, cell / 2f)

            inset(left - pad, top - pad, size.width - left - cell * COLUMNS - pad, size.height - top - cell * ROWS - pad) {
                drawNeonBoardPlate(accent = CategoryPalette.Attention, lit = won, litColor = LogicColors.NeonGreen, corner = 14.dp)
            }

            for (index in 0 until ROWS * COLUMNS) {
                val reveal = REVEALS[index]
                val revealAt = reveal?.let { tapTime(TAPS[it.tap]) + it.distance * CASCADE_STEP_SEC }
                val revealed = revealAt != null && t >= revealAt
                val mineCell = MineCell(
                    position = CellPosition(rowOf(index), colOf(index)),
                    hasMine = index in MINES,
                    adjacentMines = NUMBERS[index],
                    state = when {
                        revealed -> MineCellState.REVEALED
                        index == MINE && flagged -> MineCellState.FLAGGED
                        else -> MineCellState.HIDDEN
                    },
                )
                val entry = boardCascade(rowOf(index), colOf(index), t)
                if (entry <= 0f) continue
                val paint: DrawScope.() -> Unit = {
                    drawCell(
                        cell = mineCell,
                        topLeft = topLeftOf(index),
                        side = cell,
                        revealAlpha = if (revealAt == null) 0f else (t - revealAt) / REVEAL_SEC,
                        detonateProgress = 0f,
                        measurer = measurer,
                        pressed = index == pressed,
                        scanPulse = 0f,
                    )
                }
                if (entry == 1f) paint() else scale(entry, entry, pivot = centerOf(index)) { paint() }
            }

            // Escudo sobre la mina marcada: entra con rebote, como al clavarlo en la partida.
            if (flagged) {
                val pop = EaseOutBack.transform(((t - flagAt) / 0.3f).coerceIn(0f, 1f))
                val side = cell * FLAG_FRACTION * pop
                val center = centerOf(MINE)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(LogicColors.Violet.copy(alpha = 0.45f), Color.Transparent),
                        center = center,
                        radius = cell * 0.6f,
                    ),
                    radius = cell * 0.6f,
                    center = center,
                )
                translate(center.x - side / 2f, center.y - side / 2f) {
                    with(shield) { draw(Size(side, side), colorFilter = ColorFilter.tint(LogicColors.Violet)) }
                }
            }

            // --- Focos del razonamiento: qué casilla mirar en cada paso ----------------------
            val beat = 0.5f + 0.5f * sin(now * 6f)
            fun focus(index: Int, color: Color) = drawRoundRect(
                color = color.copy(alpha = 0.55f + 0.45f * beat),
                topLeft = topLeftOf(index),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx()),
            )
            fun during(step: Int) = playback.stepProgress(step).let { it > 0f && it < 1f }
            when {
                // "Las casillas que lo rodean": el bloque de 3×3 alrededor del número.
                during(NUMBERS_STEP) -> {
                    val from = topLeftOf(at(rowOf(CLUE) - 1, colOf(CLUE) - 1))
                    drawRoundRect(
                        color = LogicColors.OnDark.copy(alpha = 0.35f + 0.25f * beat),
                        topLeft = from,
                        size = Size(cell * 3f, cell * 3f),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    focus(CLUE, LogicColors.NeonCyan)
                }
                during(DEDUCE) -> {
                    focus(CLUE, LogicColors.NeonCyan)
                    focus(MINE, LogicColors.Error)
                }
                // Hasta que el dedo la destapa.
                during(SAFE) && t < tapTime(TAPS[1]) -> {
                    focus(SECOND_CLUE, LogicColors.NeonCyan)
                    focus(SAFE_CELL, LogicColors.Success)
                }
            }

            // --- El dedo, encima de todo -------------------------------------------------------
            for (tap in TAPS) {
                drawTutorialTap(
                    at = centerOf(tap.cell),
                    progress = (t - tapTime(tap)) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT,
                    finger = finger,
                    ripple = CategoryPalette.Attention,
                )
            }
            drawTutorialHold(centerOf(MINE), holdProgress, finger, ripple = LogicColors.Violet, time = now)
        }
    }
}
