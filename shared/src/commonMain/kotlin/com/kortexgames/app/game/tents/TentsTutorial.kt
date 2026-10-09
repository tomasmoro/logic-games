package com.kortexgames.app.game.tents

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
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
import com.kortexgames.app.ui.components.drawNeonGrass
import com.kortexgames.app.ui.components.drawNeonPine
import com.kortexgames.app.ui.components.drawNeonTent
import com.kortexgames.app.ui.components.drawNeonWire
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.tents_tutorial_step_1
import kortexgames.shared.generated.resources.tents_tutorial_step_2
import kortexgames.shared.generated.resources.tents_tutorial_step_3
import kortexgames.shared.generated.resources.tents_tutorial_step_4
import kortexgames.shared.generated.resources.tents_tutorial_step_5
import kortexgames.shared.generated.resources.tents_tutorial_step_6
import kortexgames.shared.generated.resources.tents_tutorial_step_7
import kortexgames.shared.generated.resources.tents_tutorial_step_8
import kotlin.math.abs
import kotlin.math.sin

/**
 * # Neon Trees & Tents — tutorial animado
 *
 * Una partida entera en un tablero de 4×4, jugada por un dedo fantasma. Enseña, en el orden en
 * que un jugador las necesita:
 *  1. el objetivo (una tienda junto a cada árbol) y qué son los números;
 *  2. el ciclo de toque: uno marca pasto, dos plantan la tienda, tres limpian;
 *  3. la regla que no se ve mirando una casilla suelta —las tiendas no se tocan— **cometiendo
 *     el error a propósito** y deshaciéndolo: ver las dos tiendas en rojo se entiende antes que
 *     leer "ni siquiera en diagonal";
 *  4. cómo se ve el final (todo en verde).
 *
 * ## Un guion de toques, no de fotogramas
 * La escena no describe "en el segundo 12 la casilla 5 es una tienda". Describe **los toques**
 * ([TAPS]) y el tablero de cada instante sale de aplicar, con las reglas reales del juego
 * ([TentsCellType.next]), los que ya ocurrieron. Así el tutorial no puede enseñar algo que el
 * juego no haga (p. ej. plantar una tienda de un solo toque), y cambiar el guion es mover una
 * línea de [TAPS].
 *
 * Se dibuja con los mismos glifos y el mismo kit de tablero que `TentsScreen`, así que es el
 * tablero real en pequeño.
 */
object TentsTutorial {

    /** Lado del mini-tablero. 4 es el mínimo en el que caben tres tiendas y una que estorbe. */
    private const val N = 4

    /** Casilla (x, y) → índice. */
    private fun at(x: Int, y: Int) = y * N + x

    /** Árboles del tablero de ejemplo. */
    private val TREES = setOf(at(1, 0), at(3, 0), at(1, 2))

    /** Tienda → árbol al que queda atada con la cuerda de luz (la solución del ejemplo). */
    private val ROPES = mapOf(at(0, 0) to at(1, 0), at(3, 1) to at(3, 0), at(1, 3) to at(1, 2))

    /** Tiendas que lleva cada fila / columna en la solución: los números del borde. */
    private val ROW_TARGETS = intArrayOf(1, 1, 0, 1)
    private val COLUMN_TARGETS = intArrayOf(1, 1, 0, 1)

    // Índices de los pasos del guion.
    private const val COUNTERS = 1
    private const val GRASS = 2
    private const val FIRST_TENT = 3
    private const val CONFLICT = 4
    private const val REMOVE = 5
    private const val REST = 6
    private const val SOLVED = 7

    /**
     * Un toque del dedo fantasma.
     *
     * @property step paso del guion en el que ocurre.
     * @property at avance `0..1` de ese paso en el que el dedo pulsa.
     * @property cell casilla pulsada.
     */
    private class Tap(val step: Int, val at: Float, val cell: Int)

    /**
     * El guion. Separados al menos [TAP_WINDOW_SEC] entre sí: cada toque tiene su propio gesto
     * de dedo y dos solapados se verían como dos manos.
     */
    private val TAPS = listOf(
        // La fila del 0: todo pasto (salvo el árbol, que no admite toques).
        Tap(GRASS, 0.24f, at(0, 2)),
        Tap(GRASS, 0.50f, at(2, 2)),
        Tap(GRASS, 0.76f, at(3, 2)),
        // Primera tienda: un toque (pasto) y otro (tienda).
        Tap(FIRST_TENT, 0.30f, at(0, 0)),
        Tap(FIRST_TENT, 0.60f, at(0, 0)),
        // El error: junto a un árbol, pero en diagonal con la tienda anterior.
        Tap(CONFLICT, 0.22f, at(1, 1)),
        Tap(CONFLICT, 0.46f, at(1, 1)),
        // Tercer toque: la quita.
        Tap(REMOVE, 0.42f, at(1, 1)),
        // Las dos que faltan, bien puestas.
        Tap(REST, 0.14f, at(3, 1)),
        Tap(REST, 0.34f, at(3, 1)),
        Tap(REST, 0.60f, at(1, 3)),
        Tap(REST, 0.80f, at(1, 3)),
    )

    /** El tutorial que la antesala de Trees & Tents inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_TENTS,
        accent = CategoryPalette.Logic,
        steps = listOf(
            TutorialStep(Res.string.tents_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.tents_tutorial_step_2, durationMs = 3_400),
            TutorialStep(Res.string.tents_tutorial_step_3, durationMs = 3_800),
            TutorialStep(Res.string.tents_tutorial_step_4, durationMs = 4_000),
            TutorialStep(Res.string.tents_tutorial_step_5, durationMs = 4_400),
            TutorialStep(Res.string.tents_tutorial_step_6, durationMs = 3_000),
            TutorialStep(Res.string.tents_tutorial_step_7, durationMs = 5_200),
            TutorialStep(Res.string.tents_tutorial_step_8, durationMs = 3_600),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Duración del gesto de dedo de cada toque (s), centrado en el instante en que pulsa. */
    private const val TAP_WINDOW_SEC = 0.85f

    /** Duración del brote de una marca y del estallido al plantar; los mismos que en la partida. */
    private const val POP_SEC = 0.30f
    private const val BURST_SEC = 0.45f

    /** Franja de los números como fracción de la casilla, y lado de la baldosa; como en la partida. */
    private const val HEADER_FRACTION = 0.72f
    private const val SOCKET_FRACTION = 0.90f

    /** Marca de tiempo de algo que no ha ocurrido. */
    private const val NEVER = -1_000f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        val measurer = rememberTextMeasurer()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec

            // --- Tablero de este instante: los toques ya ocurridos, con las reglas del juego ----
            val types = Array(N * N) { if (it in TREES) TentsCellType.TREE else TentsCellType.EMPTY }
            val changedAt = FloatArray(N * N) { NEVER }
            for (tap in TAPS) {
                val tapAt = playback.stepStartSec(tap.step) + tap.at * playback.stepDurationSec(tap.step)
                if (t < tapAt) continue
                types[tap.cell] = types[tap.cell].next()
                changedAt[tap.cell] = tapAt
            }
            fun isTent(index: Int) = types[index] == TentsCellType.TENT
            // Dos tiendas chocan si se tocan, también en diagonal.
            val conflict = BooleanArray(N * N) { i ->
                isTent(i) && (0 until N * N).any { j ->
                    j != i && isTent(j) && abs(i % N - j % N) <= 1 && abs(i / N - j / N) <= 1
                }
            }
            val solvedAt = playback.stepStartSec(SOLVED) + 0.3f
            val solved = ((t - solvedAt) / 0.45f).coerceIn(0f, 1f)

            // --- Composición: números arriba y a la izquierda, tablero cuadrado centrado -------
            val cell = minOf(size.height * 0.90f, size.width * 0.90f) / (N + HEADER_FRACTION)
            val header = cell * HEADER_FRACTION
            val board = cell * N
            val left = (size.width - board - header) / 2f + header
            val top = (size.height - board - header) / 2f + header

            drawCounters(playback, measurer, types, left, top, cell, header)

            inset(left, top, size.width - left - board, size.height - top - board) {
                fun centerOf(index: Int) = Offset((index % N + 0.5f) * cell, (index / N + 0.5f) * cell)
                fun popOf(index: Int): Float {
                    val p = ((t - changedAt[index]) / POP_SEC).coerceIn(0f, 1f)
                    return 0.4f + 0.6f * EaseOutBack.transform(p)
                }
                fun tentColor(index: Int) = if (conflict[index]) {
                    LogicColors.Error
                } else {
                    lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, solved)
                }
                // Un árbol se enciende cuando su tienda está plantada.
                fun treeGlow(tree: Int): Float {
                    val tent = ROPES.entries.firstOrNull { it.value == tree }?.key ?: return 0f
                    return if (isTent(tent)) ((t - changedAt[tent]) / POP_SEC).coerceIn(0f, 1f) else 0f
                }

                drawNeonBoardPlate(accent = CategoryPalette.Logic, lit = solved, litColor = LogicColors.NeonGreen, corner = 16.dp)

                // Baldosas, con la misma entrada en cascada que al abrir un nivel.
                for (index in 0 until N * N) {
                    val cascade = boardCascade(index / N, index % N, t)
                    val (fill, color) = when (types[index]) {
                        TentsCellType.TENT -> popOf(index) to tentColor(index)
                        TentsCellType.TREE -> (0.30f + 0.45f * treeGlow(index)) to LogicColors.NeonGreen
                        TentsCellType.EMPTY, TentsCellType.GRASS -> 0f to LogicColors.NeonCyan
                    }
                    drawBoardSocket(centerOf(index), cell * SOCKET_FRACTION * cascade, fill, color, padAlpha = 0f)
                }

                // Cuerdas de luz: cada tienda bien puesta, atada a su árbol.
                for ((tent, tree) in ROPES) {
                    if (!isTent(tent)) continue
                    val from = centerOf(tree)
                    val to = centerOf(tent)
                    val grow = ((t - changedAt[tent]) / POP_SEC).coerceIn(0f, 1f)
                    val reach = 0.30f + 0.40f * grow
                    drawNeonWire(
                        path = Path().apply {
                            moveTo(from.x + (to.x - from.x) * 0.30f, from.y + (to.y - from.y) * 0.30f)
                            lineTo(from.x + (to.x - from.x) * reach, from.y + (to.y - from.y) * reach)
                        },
                        color = lerp(LogicColors.NeonGreen, LogicColors.NeonCyan, 0.5f * (1f - solved)),
                        strokeWidth = cell * 0.045f,
                        glow = 0.9f,
                        core = 0.25f,
                        flowPhase = now,
                    )
                }

                // Contenido de cada casilla.
                for (index in 0 until N * N) {
                    val center = centerOf(index)
                    val cascade = boardCascade(index / N, index % N, t)
                    when (types[index]) {
                        TentsCellType.EMPTY -> Unit
                        TentsCellType.TREE -> drawNeonPine(
                            center = center,
                            side = cell,
                            color = LogicColors.NeonGreen,
                            glow = (0.28f + 0.72f * treeGlow(index)).coerceIn(0f, 1f),
                            sway = 2.4f * sin(now * 1.05f + index * 1.9f),
                            scale = cascade,
                        )
                        TentsCellType.GRASS -> drawNeonGrass(
                            center = center,
                            side = cell,
                            color = lerp(LogicColors.OnDarkMuted, LogicColors.NeonGreen, 0.22f),
                            alpha = 0.78f,
                            sway = 0.035f * sin(now * 1.6f + index * 1.3f),
                            scale = popOf(index),
                        )
                        TentsCellType.TENT -> {
                            val settle = ((t - changedAt[index]) / 0.5f).coerceIn(0f, 1f)
                            drawNeonTent(
                                center = center,
                                side = cell,
                                color = tentColor(index),
                                glow = (0.55f + 0.45f * (1f - settle) + 0.45f * solved).coerceIn(0f, 1f),
                                // Las tiendas en conflicto parpadean, como en la partida.
                                alpha = if (conflict[index]) 0.55f + 0.45f * sin(now * 9f) else 1f,
                                scale = popOf(index),
                                ember = if (conflict[index]) 0f else 0.72f + 0.16f * sin(now * 5.3f + index),
                                emberColor = LogicColors.Amber,
                            )
                        }
                    }
                }

                // Reacción al toque: onda y, si nace una tienda, chispas.
                for (index in 0 until N * N) {
                    val since = t - changedAt[index]
                    if (since < 0f || since > BURST_SEC) continue
                    if (isTent(index)) {
                        drawSparkBurst(
                            center = centerOf(index),
                            color = tentColor(index),
                            reach = cell * 0.72f,
                            progress = since / BURST_SEC,
                            seed = index,
                        )
                    }
                }
                // Victoria: las tiendas estallan una tras otra.
                ROPES.keys.forEachIndexed { order, tent ->
                    drawSparkBurst(
                        center = centerOf(tent),
                        color = LogicColors.NeonGreen,
                        reach = cell * 0.95f,
                        progress = (t - solvedAt - order * 0.1f) / 0.6f,
                        seed = tent + 97,
                    )
                }

                // El dedo, encima de todo: un gesto por toque, centrado en su instante.
                for (tap in TAPS) {
                    val tapAt = playback.stepStartSec(tap.step) + tap.at * playback.stepDurationSec(tap.step)
                    drawTutorialTap(
                        at = centerOf(tap.cell),
                        progress = (t - tapAt) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT,
                        finger = finger,
                        ripple = LogicColors.NeonCyan,
                    )
                }
            }
        }
    }

    /**
     * Los números de filas y columnas, con el mismo código de color que en la partida: apagado si
     * faltan tiendas, verde al clavarlo, rojo si sobran. Durante el paso que los presenta
     * ([COUNTERS]) laten con un aro, para llevar la vista hasta ellos.
     *
     * @param left borde izquierdo del tablero; los números de fila van justo antes.
     * @param top borde superior del tablero; los de columna, justo encima.
     */
    private fun DrawScope.drawCounters(
        playback: TutorialPlayback,
        measurer: TextMeasurer,
        types: Array<TentsCellType>,
        left: Float,
        top: Float,
        cell: Float,
        header: Float,
    ) {
        val style = TextStyle(fontSize = (cell * 0.38f).toSp(), fontWeight = FontWeight.Black)
        // Solo mientras dura el paso (el avance vale 0 antes y 1 después).
        val introducing = playback.stepProgress(COUNTERS).let { it > 0f && it < 1f }
        val beat = 0.5f + 0.5f * sin(playback.clockSec * 6f)

        fun counter(center: Offset, target: Int, current: Int) {
            val color = when (TentsLineStatus.of(current, target)) {
                TentsLineStatus.UNDER -> LogicColors.OnDarkMuted
                TentsLineStatus.EXACT -> LogicColors.NeonGreen
                TentsLineStatus.OVER -> LogicColors.Error
            }
            if (introducing) {
                val box = header * (0.80f + 0.08f * beat)
                drawRoundRect(
                    color = CategoryPalette.Logic.copy(alpha = 0.45f + 0.45f * beat),
                    topLeft = Offset(center.x - box / 2f, center.y - box / 2f),
                    size = Size(box, box),
                    cornerRadius = CornerRadius(box * 0.28f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            val layout = measurer.measure(target.toString(), style)
            drawText(layout, color, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
        }

        for (i in 0 until N) {
            val inRow = (0 until N).count { types[i * N + it] == TentsCellType.TENT }
            val inColumn = (0 until N).count { types[it * N + i] == TentsCellType.TENT }
            counter(Offset(left - header / 2f, top + (i + 0.5f) * cell), ROW_TARGETS[i], inRow)
            counter(Offset(left + (i + 0.5f) * cell, top - header / 2f), COLUMN_TARGETS[i], inColumn)
        }
    }
}
