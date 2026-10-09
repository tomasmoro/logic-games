package com.kortexgames.app.game.neonpulse

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawEdgeFlash
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_1
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_2
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_3
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_4
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_5
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_6
import kortexgames.shared.generated.resources.neonpulse_tutorial_step_7
import kotlin.math.hypot

/**
 * # Neon Pulse — tutorial animado
 *
 * Presenta los tipos de nodo **de uno en uno**, cada cual con su consecuencia: el objetivo que se
 * toca, el que se escapa (y cuesta una vida), la trampa que NO se toca, el blindado de dos toques
 * y la bomba. En la partida llegan mezclados y deprisa; aquí cada uno tiene el lienzo para él.
 *
 * ## Un guion de nodos
 * La escena es una lista de [ScriptNode]: cuándo nace cada uno, cuánto vive, cuándo lo toca el
 * dedo y cuándo revienta. El nodo de cada instante se construye como un [Node] real (con su vida
 * restante) y lo pinta `drawNode`, la función de la partida — por eso el anillo se cierra, avisa
 * en el último tercio y el blindado pierde sus placas exactamente igual que jugando.
 *
 * Los nodos son más grandes que en la partida ([NODE_RADIUS]): el recuadro del tutorial es pequeño
 * y a su tamaño real no se distinguirían las siluetas.
 */
object NeonPulseTutorial {

    /**
     * Un instante del guion.
     *
     * @property step paso en el que cae.
     * @property at avance `0..1` dentro de ese paso.
     */
    private class Mark(val step: Int, val at: Float)

    /**
     * Un nodo del guion.
     *
     * @property x posición normalizada `0..1`, como en el motor.
     * @property born cuándo aparece.
     * @property lifeSec cuánto tarda su anillo en cerrarse.
     * @property taps toques del dedo sobre él, en orden.
     * @property bursts cuándo revienta. `null` = nadie lo revienta y expira al cerrarse el anillo:
     *   un objetivo cuesta entonces una vida; una trampa se apaga sin más.
     */
    private class ScriptNode(
        val type: NodeType,
        val x: Float,
        val y: Float,
        val born: Mark,
        val lifeSec: Float,
        val taps: List<Mark> = emptyList(),
        val bursts: Mark? = taps.lastOrNull(),
    )

    // Índices de los pasos del guion.
    private const val APPEAR = 0
    private const val TAP = 1
    private const val MISS = 2
    private const val TRAP = 3
    private const val ARMORED = 4
    private const val BOMB = 5

    /** Instante en que el dedo toca la bomba: revientan ella y sus dos escoltas. */
    private val BLAST = Mark(BOMB, 0.55f)

    private val NODES = listOf(
        // Nace en el primer paso y se toca en el segundo, con el anillo a medio cerrar.
        ScriptNode(NodeType.NORMAL, 0.32f, 0.44f, born = Mark(APPEAR, 0.15f), lifeSec = 7.5f, taps = listOf(Mark(TAP, 0.45f))),
        // Nadie lo toca: el anillo se cierra y cuesta una vida.
        ScriptNode(NodeType.NORMAL, 0.66f, 0.54f, born = Mark(MISS, 0.05f), lifeSec = 2.2f),
        // Trampa y objetivo a la vez: el dedo va solo a por el objetivo y la trampa se apaga sola.
        ScriptNode(NodeType.TRAP, 0.30f, 0.60f, born = Mark(TRAP, 0.05f), lifeSec = 3.4f),
        ScriptNode(NodeType.NORMAL, 0.68f, 0.40f, born = Mark(TRAP, 0.10f), lifeSec = 4.5f, taps = listOf(Mark(TRAP, 0.50f))),
        // Blindado: el primer toque rompe las placas, el segundo lo revienta.
        ScriptNode(NodeType.ARMORED, 0.50f, 0.48f, born = Mark(ARMORED, 0.05f), lifeSec = 5f, taps = listOf(Mark(ARMORED, 0.40f), Mark(ARMORED, 0.68f))),
        // Bomba con sus dos escoltas, como nace siempre en la partida.
        ScriptNode(NodeType.BOMB, 0.50f, 0.50f, born = Mark(BOMB, 0.05f), lifeSec = 6f, taps = listOf(BLAST)),
        ScriptNode(NodeType.NORMAL, 0.24f, 0.32f, born = Mark(BOMB, 0.12f), lifeSec = 6f, bursts = BLAST),
        ScriptNode(NodeType.NORMAL, 0.76f, 0.68f, born = Mark(BOMB, 0.12f), lifeSec = 6f, bursts = BLAST),
    )

    /** El tutorial que la antesala de Neon Pulse inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.NEON_PULSE,
        accent = CategoryPalette.Reflexes,
        steps = listOf(
            TutorialStep(Res.string.neonpulse_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.neonpulse_tutorial_step_2, durationMs = 3_000),
            TutorialStep(Res.string.neonpulse_tutorial_step_3, durationMs = 3_600),
            TutorialStep(Res.string.neonpulse_tutorial_step_4, durationMs = 4_200),
            TutorialStep(Res.string.neonpulse_tutorial_step_5, durationMs = 4_000),
            TutorialStep(Res.string.neonpulse_tutorial_step_6, durationMs = 4_200),
            // Solo texto: el combo y las vidas viven en el HUD, que la escena no reproduce.
            TutorialStep(Res.string.neonpulse_tutorial_step_7, durationMs = 4_500),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Radio de los nodos del tutorial (normalizado al lado menor): el doble que en la partida. */
    private const val NODE_RADIUS = NeonPulseConfig.NODE_RADIUS * 2f

    /** Duración del gesto de dedo de un toque (s), centrado en el instante en que pulsa. */
    private const val TAP_WINDOW_SEC = 0.85f

    /** Duración del estallido de un nodo, del chispazo del blindaje y del aviso de vida perdida (s). */
    private const val BURST_SEC = 0.50f
    private const val CRACK_SEC = 0.30f
    private const val MISS_SEC = 0.60f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec
            val minDim = size.minDimension
            fun timeOf(mark: Mark) = playback.stepStartSec(mark.step) + mark.at * playback.stepDurationSec(mark.step)

            drawPulseField(time = now, tempo = 0.6f, tint = CategoryPalette.Reflexes, intensity = 0.45f)

            NODES.forEachIndexed { index, node ->
                val born = timeOf(node.born)
                if (t < born) return@forEachIndexed
                val center = Offset(node.x * size.width, node.y * size.height)
                val core = NODE_RADIUS * minDim
                val end = node.bursts?.let(::timeOf) ?: (born + node.lifeSec)

                if (t < end) {
                    drawNode(
                        node = Node(
                            id = index.toLong(),
                            type = node.type,
                            x = node.x,
                            y = node.y,
                            radius = NODE_RADIUS,
                            totalLifeMs = (node.lifeSec * 1_000f).toLong(),
                            remainingMs = ((node.lifeSec - (t - born)) * 1_000f).toLong(),
                            // Un blindado pierde un toque por cada pulsación que ya recibió.
                            hitsLeft = if (node.type == NodeType.ARMORED) {
                                NeonPulseConfig.ARMORED_HITS - node.taps.count { t >= timeOf(it) }
                            } else {
                                1
                            },
                        ),
                        minDim = minDim,
                        time = now,
                    )
                    // Chispazo de cada toque que aún no lo revienta (el blindaje al romperse).
                    node.taps.dropLast(1).forEach { tap ->
                        drawSparkBurst(center, Color.White, reach = core * 1.8f, progress = (t - timeOf(tap)) / CRACK_SEC, seed = index)
                    }
                    return@forEachIndexed
                }

                val since = t - end
                when {
                    // Reventado por un toque (o por la bomba): chispas de su color.
                    node.bursts != null ->
                        drawSparkBurst(center, node.type.accent(), reach = core * 3f, progress = since / BURST_SEC, seed = index + 11)
                    // Objetivo que se escapó: vida perdida. Rojo en el sitio y por los bordes.
                    node.type == NodeType.NORMAL -> drawMiss(center, core, since / MISS_SEC)
                    // Una trampa que se apaga sola no hace nada: ese es el mensaje.
                    else -> Unit
                }
            }

            drawBlast(t - timeOf(BLAST))

            // El dedo, encima de todo: un gesto por toque.
            NODES.forEach { node ->
                node.taps.forEach { tap ->
                    drawTutorialTap(
                        at = Offset(node.x * size.width, node.y * size.height),
                        progress = (t - timeOf(tap)) / TAP_WINDOW_SEC + TUTORIAL_TAP_AT,
                        finger = finger,
                        ripple = node.type.accent(),
                    )
                }
            }
        }
    }

    /** Vida perdida: un aro rojo que se abre donde estaba el nodo y un destello por los bordes. */
    private fun DrawScope.drawMiss(center: Offset, core: Float, progress: Float) {
        if (progress < 0f || progress >= 1f) return
        val fade = 1f - progress
        drawCircle(
            color = LogicColors.Error.copy(alpha = 0.85f * fade),
            radius = core * (1f + 1.6f * progress),
            center = center,
            style = Stroke(width = 3.dp.toPx() * fade + 0.5f),
        )
        drawEdgeFlash(LogicColors.Error, fade)
    }

    /** La onda de la bomba: un fogonazo y un aro que barre el lienzo entero desde su centro. */
    private fun DrawScope.drawBlast(sinceSec: Float) {
        val p = sinceSec / 0.7f
        if (p < 0f || p >= 1f) return
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val reach = hypot(size.width, size.height) * (1f - (1f - p) * (1f - p))
        val fade = 1f - p
        if (p < 0.25f) drawRect(Color.White.copy(alpha = 0.35f * (1f - p / 0.25f)))
        drawCircle(LogicColors.Amber.copy(alpha = 0.30f * fade), reach, center, style = Stroke(18.dp.toPx() * fade + 1f))
        drawCircle(Color.White.copy(alpha = 0.85f * fade), reach, center, style = Stroke(3.dp.toPx() * fade + 1f))
    }
}
