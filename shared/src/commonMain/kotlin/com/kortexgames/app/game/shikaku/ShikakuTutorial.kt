package com.kortexgames.app.game.shikaku

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
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_FROM
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_TO
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawBoardSocket
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonTile
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.drawTutorialDrag
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import com.kortexgames.app.ui.components.tutorialDragFraction
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.shikaku_tutorial_step_1
import kortexgames.shared.generated.resources.shikaku_tutorial_step_2
import kortexgames.shared.generated.resources.shikaku_tutorial_step_3
import kortexgames.shared.generated.resources.shikaku_tutorial_step_4
import kortexgames.shared.generated.resources.shikaku_tutorial_step_5
import kortexgames.shared.generated.resources.shikaku_tutorial_step_6
import kortexgames.shared.generated.resources.shikaku_tutorial_step_7
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * # Neon Shikaku Matrix — tutorial animado
 *
 * Una partida entera en un tablero de 4×4 con cuatro números, trazada por un dedo fantasma.
 * Enseña el gesto (arrastrar en diagonal), la regla (un número por rectángulo, tantas celdas como
 * diga) y, cometiendo el error a propósito, qué pasa cuando no se cumple y cómo se corrige.
 *
 * ## Un guion de arrastres
 * La escena solo describe los arrastres ([DRAGS]): de qué celda a qué celda y cuándo. Todo lo
 * demás sale de las reglas del juego ([ShikakuRules]): el veredicto que colorea la selección
 * mientras crece, si el rectángulo queda sellado en verde o parpadeando en rojo, y que el trazo
 * bueno **reemplace** al malo que pisa. El tutorial no puede enseñar un resultado que el juego
 * no daría.
 *
 * Se pinta con las mismas piezas que el tablero de la partida (baldosas, fichas de número, tubo
 * de neón del rectángulo).
 */
object ShikakuTutorial {

    /** Lado del mini-tablero. */
    private const val N = 4

    private val MASK = BoardMask.full(N, N)

    /** Los cuatro números. La solución: 2×2 arriba-izq., 2×1 arriba-der., 2×3 der., 2×2 abajo-izq. */
    private val CLUES = listOf(
        ShikakuCell(1, 0, 4),
        ShikakuCell(2, 0, 2),
        ShikakuCell(3, 2, 6),
        ShikakuCell(0, 3, 4),
    )

    // Índices de los pasos del guion.
    private const val FIRST = 2
    private const val WRONG = 3
    private const val FIX = 4
    private const val REST = 5
    private const val SOLVED = 6

    /**
     * Un arrastre del dedo fantasma, de la celda ancla a la celda final.
     *
     * @property step paso del guion en el que ocurre.
     * @property from, to tramo `0..1` de ese paso que ocupa el gesto completo (entrar, apoyar,
     *   arrastrar, soltar y retirarse).
     */
    private class Drag(
        val step: Int,
        val from: Float,
        val to: Float,
        val anchorX: Int,
        val anchorY: Int,
        val endX: Int,
        val endY: Int,
    )

    private val DRAGS = listOf(
        Drag(FIRST, 0.08f, 0.92f, 0, 0, 1, 1),
        // El error: 2×2 alrededor del "2". Encierra un solo número, pero mide el doble.
        Drag(WRONG, 0.08f, 0.92f, 2, 0, 3, 1),
        // La corrección: el 2×1 bueno, trazado encima del malo.
        Drag(FIX, 0.08f, 0.92f, 2, 0, 3, 0),
        Drag(REST, 0.02f, 0.50f, 2, 1, 3, 3),
        Drag(REST, 0.50f, 0.98f, 0, 2, 1, 3),
    )

    /** El tutorial que la antesala de Shikaku inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_SHIKAKU,
        accent = CategoryPalette.SpatialVision,
        steps = listOf(
            TutorialStep(Res.string.shikaku_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.shikaku_tutorial_step_2, durationMs = 3_400),
            TutorialStep(Res.string.shikaku_tutorial_step_3, durationMs = 4_000),
            TutorialStep(Res.string.shikaku_tutorial_step_4, durationMs = 4_000),
            TutorialStep(Res.string.shikaku_tutorial_step_5, durationMs = 3_600),
            TutorialStep(Res.string.shikaku_tutorial_step_6, durationMs = 5_400),
            TutorialStep(Res.string.shikaku_tutorial_step_7, durationMs = 3_600),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Fracción del lado menor del recuadro que ocupa el tablero: deja margen arriba para el cálculo. */
    private const val BOARD_FRACTION = 0.74f

    /** Parpadeo de un rectángulo inválido: el mismo periodo y suelo que en la partida. */
    private const val BLINK_SEC = 1.4f
    private const val BLINK_MIN = 0.45f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        val measurer = rememberTextMeasurer()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec
            val cell = size.minDimension * BOARD_FRACTION / N
            val left = (size.width - cell * N) / 2f
            val top = (size.height - cell * N) / 2f
            fun centerOf(x: Int, y: Int) = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)

            // --- Tablero de este instante: los arrastres ya soltados, con las reglas del juego ---
            var rectangles = emptyList<ShikakuRectangle>()
            val sealedAt = HashMap<Int, Float>()
            var selection: ShikakuRect? = null
            DRAGS.forEachIndexed { id, drag ->
                val start = playback.stepStartSec(drag.step) + drag.from * playback.stepDurationSec(drag.step)
                val length = (drag.to - drag.from) * playback.stepDurationSec(drag.step)
                val progress = (t - start) / length
                when {
                    progress >= TUTORIAL_HOLD_TO -> {
                        val bounds = ShikakuRect.spanning(drag.anchorX, drag.anchorY, drag.endX, drag.endY)
                        rectangles = ShikakuRules.place(rectangles, bounds, id, ShikakuRules.evaluate(bounds, MASK, CLUES))
                        sealedAt[id] = start + TUTORIAL_HOLD_TO * length
                    }
                    progress >= TUTORIAL_HOLD_FROM -> {
                        // La selección llega hasta la celda que el dedo tiene debajo ahora mismo.
                        val travelled = tutorialDragFraction(progress)
                        val x = (drag.anchorX + 0.5f + (drag.endX - drag.anchorX) * travelled).toInt()
                        val y = (drag.anchorY + 0.5f + (drag.endY - drag.anchorY) * travelled).toInt()
                        selection = ShikakuRect.spanning(drag.anchorX, drag.anchorY, x, y)
                    }
                }
            }
            val solvedAt = playback.stepStartSec(SOLVED) + 0.2f
            val sinceSolved = t - solvedAt
            fun ownerAt(x: Int, y: Int) =
                rectangles.firstOrNull { it.validity == ShikakuRectValidity.VALID && it.bounds.contains(x, y) }
            fun clueOf(rectangle: ShikakuRectangle) = CLUES.first { it in rectangle.bounds }

            inset(left, top, size.width - left - cell * N, size.height - top - cell * N) {
                val pad = 8.dp.toPx()
                inset(-pad, -pad, -pad, -pad) {
                    drawNeonBoardPlate(
                        accent = CategoryPalette.SpatialVision,
                        lit = if (sinceSolved < 0f) 0f else (1f - sinceSolved / 1.4f).coerceIn(0f, 1f),
                        litColor = LogicColors.NeonGreen,
                        corner = 18.dp,
                    )
                }
                val tile = cell - 3.dp.toPx()

                // Celdas: apagadas, o encendiéndose en onda desde el número de su rectángulo.
                for (y in 0 until N) {
                    for (x in 0 until N) {
                        val entry = boardCascade(y, x, t)
                        if (entry <= 0f) continue
                        val owner = ownerAt(x, y)
                        if (owner == null) {
                            drawBoardSocket(centerOf(x, y), tile * entry, fill = 0f, color = CategoryPalette.SpatialVision, padAlpha = 0f)
                            continue
                        }
                        val clue = clueOf(owner)
                        val distance = abs(x - clue.x) + abs(y - clue.y)
                        val local = (t - sealedAt.getValue(owner.id) - distance * LIGHT_STEP_SEC) / LIGHT_POP_SEC
                        val wave = ((sinceSolved - (abs(x - 1.5f) + abs(y - 1.5f)) * 0.03f) / 0.45f)
                            .let { if (it <= 0f || it >= 1f) 0f else sin(it * PI.toFloat()) }
                        drawSolvedCell(centerOf(x, y), tile * entry, owner.tint.toColor(), local, sheen = 0f, wave = wave)
                    }
                }

                // Rectángulos sellados: el correcto con su tubo, el inválido parpadeando en rojo.
                val blink = BLINK_MIN + (1f - BLINK_MIN) * (0.5f + 0.5f * sin(now * 2f * PI.toFloat() / BLINK_SEC))
                for (rectangle in rectangles) {
                    if (rectangle.validity == ShikakuRectValidity.VALID) {
                        val flash = (1f - (t - sealedAt.getValue(rectangle.id)) / SEAL_FLASH_SEC).coerceIn(0f, 1f)
                        drawFrame(rectangle.bounds, cell, rectangle.tint.toColor(), SEALED_GLOW + (1f - SEALED_GLOW) * flash * flash, 1f, 0f)
                    } else {
                        drawFrame(rectangle.bounds, cell, LogicColors.Error, SEALED_GLOW, blink, 0.14f)
                    }
                }

                // Selección en curso: su color es el veredicto de las reglas, en vivo.
                val verdict = selection?.let { ShikakuRules.evaluate(it, MASK, CLUES) }
                selection?.let { bounds ->
                    val ready = verdict?.validity == ShikakuRectValidity.VALID
                    val pulse = if (ready) 0.5f + 0.5f * sin(now * 9f) else 0f
                    drawFrame(
                        bounds = bounds,
                        cell = cell,
                        color = verdict!!.validity.toSelectionColor(),
                        glow = if (ready) 0.78f + 0.22f * pulse else 0.7f,
                        alpha = 1f,
                        fill = 0.16f + 0.08f * pulse,
                    )
                }

                // Chispas de cada rectángulo recién resuelto, y las de la celebración final.
                rectangles.filter { it.validity == ShikakuRectValidity.VALID }.forEach { rectangle ->
                    val clue = clueOf(rectangle)
                    drawSparkBurst(centerOf(clue.x, clue.y), rectangle.tint.toColor(), cell * 1.15f, (t - sealedAt.getValue(rectangle.id)) / SEAL_SPARKS_SEC, rectangle.id)
                }
                CLUES.forEachIndexed { i, clue ->
                    drawSparkBurst(
                        center = centerOf(clue.x, clue.y),
                        color = ownerAt(clue.x, clue.y)?.tint?.toColor() ?: CategoryPalette.SpatialVision,
                        reach = cell * 1.3f,
                        progress = (sinceSolved - 0.15f - i * 0.05f) / SEAL_SPARKS_SEC,
                        seed = 97 + i,
                    )
                }

                // Números, al final para que nada los tape.
                val clueStyle = TextStyle(fontSize = (cell * CLUE_FONT_FRACTION).toSp(), fontWeight = FontWeight.Black)
                for (clue in CLUES) {
                    val entry = boardCascade(clue.y, clue.x, t)
                    if (entry <= 0f) continue
                    val owner = ownerAt(clue.x, clue.y)
                    drawClue(
                        layout = measurer.measure(clue.number.toString(), clueStyle),
                        center = centerOf(clue.x, clue.y),
                        cell = cell,
                        entry = entry,
                        solvedColor = owner?.tint?.toColor(),
                        sinceSolved = owner?.let { t - sealedAt.getValue(it.id) } ?: 0f,
                        armed = verdict?.validity == ShikakuRectValidity.VALID && selection.contains(clue),
                    )
                }

                // El cálculo en vivo, sobre la selección, como el badge que sigue al dedo en la partida.
                selection?.let { bounds ->
                    drawAreaBadge(measurer, bounds, cell, verdict!!.validity.toSelectionColor())
                }

                // El dedo, encima de todo.
                DRAGS.forEach { drag ->
                    val start = playback.stepStartSec(drag.step) + drag.from * playback.stepDurationSec(drag.step)
                    val length = (drag.to - drag.from) * playback.stepDurationSec(drag.step)
                    drawTutorialDrag((t - start) / length, finger, ripple = CategoryPalette.SpatialVision) { fraction ->
                        Offset(
                            (drag.anchorX + 0.5f + (drag.endX - drag.anchorX) * fraction) * cell,
                            (drag.anchorY + 0.5f + (drag.endY - drag.anchorY) * fraction) * cell,
                        )
                    }
                }
            }
        }
    }

    /** Relleno translúcido + tubo de neón de un rectángulo; misma receta que el tablero de la partida. */
    private fun DrawScope.drawFrame(bounds: ShikakuRect, cell: Float, color: Color, glow: Float, alpha: Float, fill: Float) {
        val topLeft = Offset(bounds.left * cell, bounds.top * cell)
        val rectSize = Size(bounds.width * cell, bounds.height * cell)
        val gap = 1.5.dp.toPx()
        if (fill > 0f) {
            drawRoundRect(
                color = color.copy(alpha = fill * alpha),
                topLeft = topLeft + Offset(gap, gap),
                size = Size(rectSize.width - 2 * gap, rectSize.height - 2 * gap),
                cornerRadius = CornerRadius(cell * 0.18f),
            )
        }
        drawNeonTile(
            baseColor = color,
            activeAmt = glow.coerceIn(0f, 1f),
            cornerRadius = 10.dp,
            sparks = false,
            baseMargin = 3.dp,
            strokeScale = 0.7f,
            rectTopLeft = topLeft,
            rectSize = rectSize,
            alpha = alpha,
        )
    }

    /**
     * La cuenta "ancho × alto = área" de la selección, en una píldora justo encima de ella y del
     * color de su veredicto. Es una expresión aritmética, no texto traducible, y por eso no pasa
     * por el catálogo de strings.
     */
    private fun DrawScope.drawAreaBadge(measurer: TextMeasurer, bounds: ShikakuRect, cell: Float, color: Color) {
        val layout = measurer.measure(
            text = "${bounds.width} × ${bounds.height} = ${bounds.area}",
            style = TextStyle(fontSize = (cell * 0.30f).toSp(), fontWeight = FontWeight.Black),
        )
        val padX = 8.dp.toPx()
        val padY = 4.dp.toPx()
        val box = Size(layout.size.width + padX * 2f, layout.size.height + padY * 2f)
        val origin = Offset(
            x = (bounds.left + bounds.width / 2f) * cell - box.width / 2f,
            y = bounds.top * cell - box.height - 6.dp.toPx(),
        )
        val corner = CornerRadius(box.height / 2f)
        drawRoundRect(LogicColors.SurfaceDark.copy(alpha = 0.94f), origin, box, corner)
        drawRoundRect(color.copy(alpha = 0.8f), origin, box, corner, style = Stroke(1.5.dp.toPx()))
        drawText(layout, color, Offset(origin.x + padX, origin.y + padY))
    }
}
