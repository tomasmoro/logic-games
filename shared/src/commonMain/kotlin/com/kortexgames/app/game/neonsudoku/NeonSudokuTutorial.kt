package com.kortexgames.app.game.neonsudoku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawBoardSocket
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.sudoku_tutorial_step_1
import kortexgames.shared.generated.resources.sudoku_tutorial_step_2
import kortexgames.shared.generated.resources.sudoku_tutorial_step_3
import kortexgames.shared.generated.resources.sudoku_tutorial_step_4
import kortexgames.shared.generated.resources.sudoku_tutorial_step_5
import kortexgames.shared.generated.resources.sudoku_tutorial_step_6
import kortexgames.shared.generated.resources.sudoku_tutorial_step_7
import kortexgames.shared.generated.resources.sudoku_tutorial_step_8
import kotlin.math.sin

/**
 * # Neon Sudoku Matrix — tutorial animado
 *
 * Una partida completa en un **Sudoku de 4×4** (números del 1 al 4, recuadros de 2×2). El 9×9
 * real asusta a quien no lo conoce; el 4×4 enseña exactamente la misma regla con cuatro números
 * que se abarcan de un vistazo. Orden de la explicación:
 *  1. el objetivo (rellenar con 1–4);
 *  2. la única regla, **una vez por cada sitio**: fila, columna y recuadro, cada uno resaltado
 *     por separado para que se vea qué es "un recuadro";
 *  3. cómo se razona una casilla ("¿qué falta aquí?") y cómo se escribe (casilla + tecla);
 *  4. qué pasa al equivocarse (rojo) y que se corrige;
 *  5. el final en verde.
 *
 * ## Un guion de jugadas, no de fotogramas
 * Igual que `TentsTutorial`: la escena describe las **jugadas** ([PLAYS]) y el tablero de cada
 * instante sale de aplicar las ya ocurridas. Los conflictos (rojo) se calculan con la regla real
 * (repetido en fila, columna o recuadro), no se pintan "a mano", así que el tutorial no puede
 * enseñar un rojo que el juego no daría.
 */
object NeonSudokuTutorial {

    /** Lado del mini-tablero y lado de sus recuadros. */
    private const val N = 4
    private const val BOX = 2

    /** Tablero inicial; 0 = hueco. La solución completa está en [SOLUTION]. */
    private val GIVENS = intArrayOf(
        1, 2, 3, 0,
        3, 4, 0, 2,
        2, 0, 4, 3,
        4, 3, 2, 0,
    )

    private fun at(row: Int, col: Int) = row * N + col

    // Pasos del guion.
    private const val ROW = 1
    private const val COLUMN = 2
    private const val SQUARE = 3
    private const val FIRST_PLAY = 4
    private const val MISTAKE = 5
    private const val REST = 6
    private const val SOLVED = 7

    /** Casillas que se resaltan en cada paso de explicación de la regla. */
    private val HIGHLIGHT: Map<Int, List<Int>> = mapOf(
        ROW to (0 until N).map { at(0, it) },
        COLUMN to (0 until N).map { at(it, 3) },
        SQUARE to listOf(at(0, 2), at(0, 3), at(1, 2), at(1, 3)),
        // Al razonar la primera jugada se vuelve a señalar la fila: "mira lo que ya tiene".
        FIRST_PLAY to (0 until N).map { at(0, it) },
    )

    /**
     * Una jugada: toca la casilla [cell] y luego la tecla [value] del teclado.
     *
     * @property step paso del guion en el que empieza.
     * @property at avance `0..1` de ese paso en el que se toca la casilla.
     */
    private class Play(val step: Int, val at: Float, val cell: Int, val value: Int)

    private val PLAYS = listOf(
        Play(FIRST_PLAY, 0.22f, at(0, 3), 4),
        // Error a propósito: otro 3 en una fila (y columna) que ya tiene uno.
        Play(MISTAKE, 0.10f, at(1, 2), 3),
        Play(MISTAKE, 0.58f, at(1, 2), 1),
        Play(REST, 0.08f, at(2, 1), 1),
        Play(REST, 0.55f, at(3, 3), 1),
    )

    /** Segundos entre tocar la casilla y tocar la tecla. */
    private const val KEY_GAP_SEC = 0.8f

    /** Duración del gesto del dedo, y del brote de un número al escribirse. */
    private const val TAP_WINDOW_SEC = 0.85f
    private const val POP_SEC = 0.30f

    /** Fracciones de casilla: alto del teclado y separación bajo el tablero. */
    private const val PAD_HEIGHT = 0.80f
    private const val PAD_GAP = 0.30f

    /** El tutorial que la antesala de Sudoku inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_SUDOKU_MATRIX,
        accent = CategoryPalette.Logic,
        steps = listOf(
            TutorialStep(Res.string.sudoku_tutorial_step_1, durationMs = 3_200),
            TutorialStep(Res.string.sudoku_tutorial_step_2, durationMs = 3_600),
            TutorialStep(Res.string.sudoku_tutorial_step_3, durationMs = 3_400),
            TutorialStep(Res.string.sudoku_tutorial_step_4, durationMs = 3_600),
            TutorialStep(Res.string.sudoku_tutorial_step_5, durationMs = 4_200),
            TutorialStep(Res.string.sudoku_tutorial_step_6, durationMs = 6_000),
            TutorialStep(Res.string.sudoku_tutorial_step_7, durationMs = 5_000),
            TutorialStep(Res.string.sudoku_tutorial_step_8, durationMs = 3_600),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Instantes (s del guion) de tocar la casilla y la tecla de [play]. */
    private fun cellTapAt(playback: TutorialPlayback, play: Play) =
        playback.stepStartSec(play.step) + play.at * playback.stepDurationSec(play.step)

    /** `true` si en [values] la casilla [i] repite número en su fila, columna o recuadro. */
    private fun conflicts(values: IntArray, i: Int): Boolean {
        val v = values[i]
        if (v == 0) return false
        return (0 until N * N).any { j ->
            j != i && values[j] == v && (
                j / N == i / N || j % N == i % N ||
                    (j / N / BOX == i / N / BOX && j % N / BOX == i % N / BOX)
                )
        }
    }

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        val measurer = rememberTextMeasurer()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec

            // --- Tablero de este instante: las jugadas cuya tecla ya se tocó -------------------
            val values = GIVENS.copyOf()
            val placedAt = FloatArray(N * N) { -1_000f }
            var selected = -1
            for (play in PLAYS) {
                val cellAt = cellTapAt(playback, play)
                val keyAt = cellAt + KEY_GAP_SEC
                if (t >= cellAt && t < keyAt + 0.25f) selected = play.cell
                if (t >= keyAt) {
                    values[play.cell] = play.value
                    placedAt[play.cell] = keyAt
                }
            }
            val bad = BooleanArray(N * N) { conflicts(values, it) }
            val solvedAt = playback.stepStartSec(SOLVED) + 0.3f
            val solved = ((t - solvedAt) / 0.45f).coerceIn(0f, 1f)

            // --- Composición: tablero cuadrado + teclado debajo, todo centrado -----------------
            val cell = minOf(size.width * 0.92f / N, size.height * 0.94f / (N + PAD_GAP + PAD_HEIGHT))
            val board = cell * N
            val left = (size.width - board) / 2f
            val top = (size.height - board - cell * (PAD_GAP + PAD_HEIGHT)) / 2f
            val padTop = top + board + cell * PAD_GAP

            fun center(i: Int) = Offset(left + (i % N + 0.5f) * cell, top + (i / N + 0.5f) * cell)
            fun keyCenter(v: Int) = Offset(left + (v - 0.5f) * cell, padTop + cell * PAD_HEIGHT / 2f)

            val highlighted = HIGHLIGHT[playback.stepIndex].orEmpty()
            val beat = 0.5f + 0.5f * sin(now * 5f)
            val accent = CategoryPalette.Logic

            inset(left, top, size.width - left - board, size.height - top - board) {
                drawNeonBoardPlate(accent = accent, lit = solved, litColor = LogicColors.NeonGreen, corner = 16.dp)
            }

            val digit = TextStyle(fontSize = (cell * 0.50f).toSp(), fontWeight = FontWeight.Black)

            for (i in 0 until N * N) {
                val cascade = boardCascade(i / N, i % N, t)
                val glow = if (i in highlighted) 0.30f + 0.20f * beat else 0f
                drawBoardSocket(center(i), cell * 0.90f * cascade, glow, accent, padAlpha = 0f)
                if (i == selected) {
                    drawRoundRect(
                        color = LogicColors.NeonCyan.copy(alpha = 0.55f + 0.35f * beat),
                        topLeft = Offset(center(i).x - cell * 0.45f, center(i).y - cell * 0.45f),
                        size = Size(cell * 0.90f, cell * 0.90f),
                        cornerRadius = CornerRadius(cell * 0.2f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                val v = values[i]
                if (v != 0) {
                    val given = GIVENS[i] != 0
                    val pop = if (given) 1f else (0.4f + 0.6f * ((t - placedAt[i]) / POP_SEC).coerceIn(0f, 1f))
                    val color = when {
                        bad[i] -> lerp(LogicColors.Error, Color.White, 0.25f * (0.5f + 0.5f * sin(now * 9f)))
                        solved > 0f -> lerp(if (given) LogicColors.OnDark else LogicColors.NeonCyan, LogicColors.NeonGreen, solved)
                        given -> LogicColors.OnDark
                        else -> LogicColors.NeonCyan
                    }
                    drawDigit(measurer, digit, v, center(i), color, cascade * pop)
                } else if (i in highlighted) {
                    // El hueco que hay que razonar late como un "?".
                    drawDigit(measurer, digit, -1, center(i), LogicColors.Amber, 0.8f + 0.2f * beat)
                }
            }

            drawBoxLines(left, top, cell, accent)

            // Teclado de números, como el de la partida: se ilumina al tocar la tecla.
            for (v in 1..N) {
                val c = keyCenter(v)
                val pressed = PLAYS.any { it.value == v && (t - (cellTapAt(playback, it) + KEY_GAP_SEC)) in 0f..0.3f }
                val keyW = cell * 0.82f
                val keyH = cell * PAD_HEIGHT
                val topLeft = Offset(c.x - keyW / 2f, c.y - keyH / 2f)
                drawRoundRect(
                    color = (if (pressed) accent else LogicColors.SurfaceVariantDark).copy(alpha = if (pressed) 0.55f else 0.85f),
                    topLeft = topLeft, size = Size(keyW, keyH), cornerRadius = CornerRadius(cell * 0.2f),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = topLeft, size = Size(keyW, keyH), cornerRadius = CornerRadius(cell * 0.2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawDigit(measurer, TextStyle(fontSize = (cell * 0.40f).toSp(), fontWeight = FontWeight.Bold), v, c, LogicColors.OnDark, 1f)
            }

            // Victoria: chispas en las casillas que se rellenaron.
            PLAYS.map { it.cell }.distinct().forEachIndexed { order, cellIndex ->
                drawSparkBurst(
                    center = center(cellIndex),
                    color = LogicColors.NeonGreen,
                    reach = cell * 0.9f,
                    progress = (t - solvedAt - order * 0.1f) / 0.6f,
                    seed = cellIndex + 31,
                )
            }

            // El dedo: uno para la casilla y otro para la tecla de cada jugada.
            for (play in PLAYS) {
                val cellAt = cellTapAt(playback, play)
                drawTutorialTap(center(play.cell), (t - cellAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT, finger, LogicColors.NeonCyan)
                drawTutorialTap(keyCenter(play.value), (t - cellAt - KEY_GAP_SEC) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT, finger, LogicColors.NeonCyan)
            }
        }
    }

    /** Dibuja [value] centrado en [at] (`-1` = "?"), con [scale] para el brote de entrada. */
    private fun DrawScope.drawDigit(
        measurer: TextMeasurer,
        style: TextStyle,
        value: Int,
        at: Offset,
        color: Color,
        scale: Float,
    ) {
        if (scale <= 0f) return
        val layout = measurer.measure(if (value < 0) "?" else value.toString(), style)
        withTransform({
            scale(scale, scale, pivot = at)
        }) {
            drawText(layout, color, Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
        }
    }

    /** Las líneas gruesas que separan los recuadros de 2×2: lo que hace visible "el recuadro". */
    private fun DrawScope.drawBoxLines(left: Float, top: Float, cell: Float, accent: Color) {
        val width = 2.5.dp.toPx()
        val color = accent.copy(alpha = 0.85f)
        val mid = cell * BOX
        val pad = cell * 0.06f
        drawLine(color, Offset(left + mid, top + pad), Offset(left + mid, top + cell * N - pad), width)
        drawLine(color, Offset(left + pad, top + mid), Offset(left + cell * N - pad, top + mid), width)
    }
}
