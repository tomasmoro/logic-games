package com.kortexgames.app.game.polarity

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawTutorialDrag
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import com.kortexgames.app.ui.components.tutorialDragFraction
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.polarity_tutorial_step_1
import kortexgames.shared.generated.resources.polarity_tutorial_step_2
import kortexgames.shared.generated.resources.polarity_tutorial_step_3
import kortexgames.shared.generated.resources.polarity_tutorial_step_4
import kortexgames.shared.generated.resources.polarity_tutorial_step_5
import kortexgames.shared.generated.resources.polarity_tutorial_step_6
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Atracción Geométrica — tutorial animado
 *
 * Enseña la regla del juego con sus dos desenlaces, uno detrás de otro:
 *  1. una pieza llega al sector equivocado → el dedo **gira el disco** → la pieza cae en su color
 *     y se captura;
 *  2. otra pieza llega y nadie gira → choca contra otro color → se pierde una vida.
 *
 * Ver primero el acierto y luego el fallo, con el mismo disco, deja claro que lo único que cambia
 * el resultado es hacia dónde mira cada sector cuando la pieza llega.
 *
 * Se dibuja con el arte de la partida (`PolarityArt` y el estallido de impacto de la pantalla).
 *
 * ## Licencias respecto a la partida, deliberadas
 *  - **La primera pieza frena** y espera a que el disco gire. En la partida no frena, pero a
 *    velocidad real no daría tiempo a leer el gesto antes del impacto.
 *  - **El disco gira lo mismo que el dedo.** En la partida el giro va multiplicado (un gesto corto
 *    da mucha vuelta); aquí ir a la par hace evidente que es el dedo quien lo mueve.
 */
object PolarityTutorial {

    // Índices de los pasos del guion.
    private const val APPROACH = 1
    private const val DRAG = 2
    private const val CAPTURE = 3
    private const val MISS = 4

    /** El tutorial que la antesala de Atracción Geométrica inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.POLARITY_COLLISION,
        accent = CategoryPalette.SpatialVision,
        steps = listOf(
            TutorialStep(Res.string.polarity_tutorial_step_1, durationMs = 3_000),
            TutorialStep(Res.string.polarity_tutorial_step_2, durationMs = 3_000),
            TutorialStep(Res.string.polarity_tutorial_step_3, durationMs = 3_600),
            TutorialStep(Res.string.polarity_tutorial_step_4, durationMs = 2_800),
            TutorialStep(Res.string.polarity_tutorial_step_5, durationMs = 3_600),
            // Solo texto: la lluvia de meteoros es una fase entera, no cabe en un gesto.
            TutorialStep(Res.string.polarity_tutorial_step_6, durationMs = 5_000),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    private const val DEG = PI.toFloat() / 180f

    /** Índices en la paleta de sectores: los tres colores con los que empieza una partida. */
    private const val CYAN = 0
    private const val GREEN = 1
    private const val AMBER = 2
    private const val SECTORS = 3

    /**
     * Rotación del disco antes y después del gesto. El sector `i` ocupa `[rot + 120°·i, +120°)`:
     * al principio "arriba" (270°) cae en mitad del cian; tras girar −120°, en mitad del verde.
     */
    private const val ROTATION_START = 210f * DEG
    private const val ROTATION_END = 90f * DEG

    /** Por dónde llega cada pieza: la verde por arriba, la ámbar por la izquierda. */
    private const val ANGLE_GREEN = 270f * DEG
    private const val ANGLE_AMBER = 180f * DEG

    /** Distancia (en radios del disco) a la que la pieza verde espera al giro. */
    private const val HOLD_FROM = 2.75f
    private const val HOLD_TO = 2.45f

    /** Fracción de su paso en la que cada pieza toca el borde del disco. */
    private const val CAPTURE_AT = 0.26f
    private const val MISS_AT = 0.50f

    /** El dedo recorre un arco bajo el disco, en el mismo sentido en que este gira. */
    private const val DRAG_ANGLE_FROM = 150f * DEG
    private const val DRAG_ANGLE_TO = 30f * DEG
    private const val DRAG_RADIUS = 2.15f

    /** Cuánto dura encendido el sector que captura, y el tinte rojo del fallo (s). */
    private const val FLASH_SEC = 0.40f
    private const val HURT_SEC = 0.45f

    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        Canvas(modifier) {
            val now = playback.clockSec
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val radius = size.minDimension * 0.16f
            val far = maxOf(size.width, size.height) * 0.80f
            val colors = SectorPalette.take(SECTORS)

            // El disco gira exactamente lo que el dedo lleva recorrido.
            val dragProgress = playback.stepProgress(DRAG)
            val turned = tutorialDragFraction(dragProgress)
            val rotation = ROTATION_START + (ROTATION_END - ROTATION_START) * turned

            // --- Pieza verde: llega, espera al giro y entra en su sector ---------------------
            val capture = playback.stepProgress(CAPTURE)
            val sinceCapture = (capture - CAPTURE_AT) * playback.stepDurationSec(CAPTURE)
            val greenDistance = when {
                playback.timelineSec < playback.stepStartSec(APPROACH) || capture >= CAPTURE_AT -> null
                capture > 0f -> (capture / CAPTURE_AT).let { radius * (HOLD_TO - (HOLD_TO - 1f) * it * it) }
                dragProgress > 0f -> radius * (HOLD_FROM - (HOLD_FROM - HOLD_TO) * dragProgress)
                else -> playback.stepProgress(APPROACH).let { far - (far - radius * HOLD_FROM) * (1f - (1f - it) * (1f - it)) }
            }

            // --- Pieza ámbar: llega de un tirón y choca (nadie gira) -------------------------
            val miss = playback.stepProgress(MISS)
            val sinceMiss = (miss - MISS_AT) * playback.stepDurationSec(MISS)
            val amberDistance = if (miss > 0f && miss < MISS_AT) far - (far - radius) * (miss / MISS_AT) else null

            drawGravityField(center, radius, now, speed = 1f, tint = CategoryPalette.SpatialVision)

            greenDistance?.let {
                drawApproachMarker(center, radius, ANGLE_GREEN, colors[GREEN], urgency = 1f - (it - radius) / (far - radius))
            }
            amberDistance?.let {
                drawApproachMarker(center, radius, ANGLE_AMBER, colors[AMBER], urgency = 1f - (it - radius) / (far - radius))
            }

            drawPolarityDisc(
                center = center,
                radius = radius,
                rotationRad = rotation,
                colors = colors,
                time = now,
                sectorFlash = FloatArray(SECTORS) { index ->
                    if (index == GREEN && sinceCapture >= 0f) (1f - sinceCapture / FLASH_SEC).coerceIn(0f, 1f) else 0f
                },
                hurt = if (sinceMiss >= 0f) (1f - sinceMiss / HURT_SEC).coerceIn(0f, 1f) else 0f,
            )

            greenDistance?.let { drawOrb(center, radius, ANGLE_GREEN, it, GREEN, id = 1L, time = now) }
            amberDistance?.let { drawOrb(center, radius, ANGLE_AMBER, it, AMBER, id = 2L, time = now) }

            drawImpact(center, radius, ANGLE_GREEN, GREEN, success = true, sinceSec = sinceCapture, id = 1L)
            drawImpact(center, radius, ANGLE_AMBER, AMBER, success = false, sinceSec = sinceMiss, id = 2L)

            drawTutorialDrag(dragProgress, finger, ripple = CategoryPalette.SpatialVision) { fraction ->
                val angle = DRAG_ANGLE_FROM + (DRAG_ANGLE_TO - DRAG_ANGLE_FROM) * fraction
                Offset(center.x + cos(angle) * radius * DRAG_RADIUS, center.y + sin(angle) * radius * DRAG_RADIUS)
            }
        }
    }

    /** Una pieza del guion a [distance] del centro por el ángulo [angleRad], cayendo hacia el disco. */
    private fun DrawScope.drawOrb(
        center: Offset,
        discRadius: Float,
        angleRad: Float,
        distance: Float,
        colorIndex: Int,
        id: Long,
        time: Float,
    ) {
        val dx = cos(angleRad)
        val dy = sin(angleRad)
        drawNeonOrb(
            particle = PolarityParticle(
                id = id,
                x = center.x + dx * distance,
                y = center.y + dy * distance,
                // Solo importa la dirección: de ella sale hacia dónde apunta la estela.
                vx = -dx * 100f,
                vy = -dy * 100f,
                mass = 1f,
                colorIndex = colorIndex,
                magnetic = false,
                radius = discRadius * 0.17f,
            ),
            color = SectorPalette[colorIndex],
            time = time,
        )
    }

    /** El estallido de una pieza al tocar el borde, [sinceSec] segundos después (negativo = aún no). */
    private fun DrawScope.drawImpact(
        center: Offset,
        discRadius: Float,
        angleRad: Float,
        colorIndex: Int,
        success: Boolean,
        sinceSec: Float,
        id: Long,
    ) {
        val ageMs = (sinceSec * 1_000f).toLong()
        if (sinceSec < 0f || ageMs >= IMPACT_LIFETIME_MS) return
        drawImpactBurst(
            impact = PolarityImpact(
                id = id,
                x = center.x + cos(angleRad) * discRadius,
                y = center.y + sin(angleRad) * discRadius,
                colorIndex = colorIndex,
                success = success,
                ageMs = ageMs,
            ),
            sectorColor = SectorPalette[colorIndex],
        )
    }
}
