package com.kortexgames.app.game.hypergate

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.GameTutorial
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_FROM
import com.kortexgames.app.ui.components.TUTORIAL_HOLD_TO
import com.kortexgames.app.ui.components.TUTORIAL_TAP_AT
import com.kortexgames.app.ui.components.TutorialPlayback
import com.kortexgames.app.ui.components.TutorialStep
import com.kortexgames.app.ui.components.drawTutorialHold
import com.kortexgames.app.ui.components.drawTutorialTap
import com.kortexgames.app.ui.components.rememberTutorialTapPainter
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.hypergate_tutorial_step_1
import kortexgames.shared.generated.resources.hypergate_tutorial_step_10
import kortexgames.shared.generated.resources.hypergate_tutorial_step_2
import kortexgames.shared.generated.resources.hypergate_tutorial_step_3
import kortexgames.shared.generated.resources.hypergate_tutorial_step_4
import kortexgames.shared.generated.resources.hypergate_tutorial_step_5
import kortexgames.shared.generated.resources.hypergate_tutorial_step_6
import kortexgames.shared.generated.resources.hypergate_tutorial_step_7
import kortexgames.shared.generated.resources.hypergate_tutorial_step_8
import kortexgames.shared.generated.resources.hypergate_tutorial_step_9
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Hypergate — tutorial animado
 *
 * Guion de la mini-partida que enseña las dos reglas del juego, en el orden en que aparecen:
 *  1. **Iguala el color del portal al del cometa que llega.** Dos cometas, uno de cada color,
 *     para que se vea el ciclo completo (cambiar → absorber → volver a cambiar → absorber) y
 *     quede claro que el toque *alterna*.
 *  2. **Mantén pulsado ante un meteorito rojo.** Un meteorito, el dedo que se queda apretando y
 *     el modo escudo que lo deshace.
 *
 * Se dibuja con las mismas funciones de `HypergateArt` que la partida real, así que lo que el
 * jugador ve aquí es exactamente lo que se va a encontrar al pulsar "Comenzar".
 *
 * ## Una licencia respecto a la partida, deliberada
 * **El cometa frena** al acercarse y espera al toque. En la partida no frena nunca, pero a
 * velocidad real no daría tiempo a leer "toca ahora" antes del impacto.
 *
 * ## El dedo pulsa FUERA del portal
 * En la partida vale tocar en cualquier parte de la pantalla, y el tutorial lo enseña así: el
 * dedo pulsa en una zona vacía (abajo a la derecha, lejos de las trayectorias de los cometas) y
 * es el portal el que reacciona. Pulsar sobre el centro daría a entender que hay que acertar ahí.
 */
object HypergateTutorial {

    // Índices de los pasos del guion, para que la escena no dependa de números sueltos.
    private const val APPROACH_1 = 1
    private const val TAP_1 = 2
    private const val ABSORB_1 = 3
    private const val APPROACH_2 = 4
    private const val TAP_2 = 5
    private const val ABSORB_2 = 6
    private const val METEOR_APPROACH = 7
    private const val METEOR_BARRIER = 8

    /** El tutorial que la antesala de Hypergate inyecta en el diálogo genérico. */
    val tutorial = GameTutorial(
        gameId = GameIds.HYPERGATE,
        accent = CategoryPalette.Reflexes,
        steps = listOf(
            TutorialStep(Res.string.hypergate_tutorial_step_1),
            TutorialStep(Res.string.hypergate_tutorial_step_2, durationMs = 3_000),
            TutorialStep(Res.string.hypergate_tutorial_step_3, durationMs = 2_800),
            TutorialStep(Res.string.hypergate_tutorial_step_4, durationMs = 2_800),
            TutorialStep(Res.string.hypergate_tutorial_step_5),
            TutorialStep(Res.string.hypergate_tutorial_step_6, durationMs = 2_800),
            TutorialStep(Res.string.hypergate_tutorial_step_7, durationMs = 3_000),
            TutorialStep(Res.string.hypergate_tutorial_step_8, durationMs = 3_200),
            TutorialStep(Res.string.hypergate_tutorial_step_9, durationMs = 4_200),
            // Solo texto (la escena se queda en reposo): son los límites del escudo, que se
            // entienden mejor leídos que vistos. Largo porque es el texto más denso.
            TutorialStep(Res.string.hypergate_tutorial_step_10, durationMs = 5_500),
        ),
        scene = { playback, modifier -> Scene(playback, modifier) },
    )

    /** Ángulo polar (rad) por el que llega cada cometa: uno por arriba-derecha, otro por la izquierda. */
    private const val ANGLE_1 = -0.25f * PI.toFloat()
    private const val ANGLE_2 = 0.92f * PI.toFloat()

    /** Ángulo por el que llega el meteorito: arriba-izquierda, el hueco que dejan los cometas. */
    private const val ANGLE_METEOR = -0.68f * PI.toFloat()

    /** Distancia (en radios del portal) a la que el meteorito espera; fuera de la burbuja del escudo. */
    private const val METEOR_HOLD_FROM = 3.6f
    private const val METEOR_HOLD_TO = 3.2f

    /** Tramo del paso del escudo en el que el meteorito se lanza, y avance en el que toca la burbuja. */
    private const val METEOR_DIVE_FROM = 0.38f
    private const val METEOR_IMPACT_AT = 0.52f

    /** Fundido de la burbuja al encender/apagar, en fracción del paso. */
    private const val BARRIER_FADE = 0.035f

    /** Distancia (en radios del portal) a la que el cometa frena y espera al toque. */
    private const val HOLD_FROM = 3.1f
    private const val HOLD_TO = 2.5f

    /**
     * Dónde pulsa el dedo, en radios del portal desde el centro: abajo a la derecha, el cuadrante
     * por el que no llega ningún cometa del guion (ver [ANGLE_1] y [ANGLE_2]).
     */
    private const val TAP_OFFSET_X = 2.3f
    private const val TAP_OFFSET_Y = 2.0f

    /** Fracción del paso de absorción en la que el cometa toca el anillo. */
    private const val IMPACT_AT = 0.28f

    /** Duración del fundido de color del portal al conmutar (s); la misma que en la partida. */
    private const val COLOR_FADE_SEC = 0.14f

    /**
     * La escena: función pura del guion. Todo lo que "pasa" sale de [TutorialPlayback.timelineSec];
     * lo decorativo (túnel, giro del portal), de [TutorialPlayback.clockSec].
     */
    @Composable
    private fun Scene(playback: TutorialPlayback, modifier: Modifier) {
        val finger = rememberTutorialTapPainter()
        Canvas(modifier) {
            val t = playback.timelineSec
            val now = playback.clockSec
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val radius = size.minDimension * 0.12f

            // Polaridad: azul al empezar, y cada toque del dedo la alterna.
            val tap1 = playback.stepStartSec(TAP_1) + TUTORIAL_TAP_AT * playback.stepDurationSec(TAP_1)
            val tap2 = playback.stepStartSec(TAP_2) + TUTORIAL_TAP_AT * playback.stepDurationSec(TAP_2)
            val shield = if (t >= tap1 && t < tap2) ShieldState.A else ShieldState.B
            val toggleAge = when {
                t >= tap2 -> t - tap2
                t >= tap1 -> t - tap1
                else -> NEVER
            }
            val color = lerp(
                shield.toggled().toNeon(),
                shield.toNeon(),
                (toggleAge / COLOR_FADE_SEC).coerceIn(0f, 1f),
            )

            drawWarpField(center, now, speed = 1f, tint = color)

            drawScriptedComet(playback, center, radius, ANGLE_1, ShieldState.A, APPROACH_1)
            drawScriptedComet(playback, center, radius, ANGLE_2, ShieldState.B, APPROACH_2)

            drawGate(
                center = center,
                radius = radius,
                shield = shield,
                color = color,
                time = now,
                toggleAge = toggleAge,
                crashAge = NEVER,
                streak = 0,
                showGlyph = true,
            )

            drawScriptedMeteor(playback, center, radius, now)

            drawScriptedImpact(playback, center, radius, ANGLE_1, ShieldState.A, ABSORB_1, seed = 1)
            drawScriptedImpact(playback, center, radius, ANGLE_2, ShieldState.B, ABSORB_2, seed = 2)

            // El dedo, encima de todo. `drawTutorialTap` ya ignora los pasos que no están en curso.
            // Modo escudo: encendido justo mientras el dedo aprieta, y gastándose a su ritmo real.
            val hold = playback.stepProgress(METEOR_BARRIER)
            val barrier = minOf(
                (hold - TUTORIAL_HOLD_FROM) / BARRIER_FADE,
                (TUTORIAL_HOLD_TO - hold) / BARRIER_FADE,
            ).coerceIn(0f, 1f)
            val heldSec = (hold - TUTORIAL_HOLD_FROM) * playback.stepDurationSec(METEOR_BARRIER)
            drawBarrier(center, radius, amount = barrier, heldFraction = heldSec * 1_000f / BARRIER_MAX_HOLD_MS, time = now)
            drawBarrierDeflect(
                center = center,
                gateRadius = radius,
                angleRad = ANGLE_METEOR,
                color = LogicColors.Error,
                age = (hold - METEOR_IMPACT_AT) * playback.stepDurationSec(METEOR_BARRIER),
                seed = 3,
            )

            val tapAt = Offset(center.x + radius * TAP_OFFSET_X, center.y + radius * TAP_OFFSET_Y)
            drawTutorialHold(tapAt, hold, finger, ripple = BarrierColor, time = now)
            drawTutorialTap(tapAt, playback.stepProgress(TAP_1), finger, ripple = ShieldState.A.toNeon())
            drawTutorialTap(tapAt, playback.stepProgress(TAP_2), finger, ripple = ShieldState.B.toNeon())
        }
    }

    /**
     * Un cometa del guion a lo largo de sus tres pasos consecutivos: **llega** frenando
     * ([approachStep]), **espera** al toque derivando despacio (el siguiente) y **entra** en el
     * portal acelerando (el tercero). Antes de su primer paso y tras el impacto no se dibuja.
     */
    private fun DrawScope.drawScriptedComet(
        playback: TutorialPlayback,
        center: Offset,
        radius: Float,
        angleRad: Float,
        required: ShieldState,
        approachStep: Int,
    ) {
        if (playback.timelineSec < playback.stepStartSec(approachStep)) return
        val absorb = playback.stepProgress(approachStep + 2)
        if (absorb >= IMPACT_AT) return

        // Nace fuera del recuadro (la escena va recortada) sea cual sea su proporción.
        val far = maxOf(size.width, size.height) * 0.80f
        val arrive = playback.stepProgress(approachStep)
        val hold = playback.stepProgress(approachStep + 1)
        val dive = absorb / IMPACT_AT
        val distance = when {
            absorb > 0f -> radius * (HOLD_TO - (HOLD_TO - 1f) * dive * dive)
            hold > 0f -> radius * (HOLD_FROM - (HOLD_FROM - HOLD_TO) * hold)
            // Desacelera: entra rápido y se va parando, para que el ojo lo siga hasta el portal.
            else -> far - (far - radius * HOLD_FROM) * (1f - (1f - arrive) * (1f - arrive))
        }
        val head = Offset(center.x + cos(angleRad) * distance, center.y + sin(angleRad) * distance)
        drawImpactMarker(
            center = center,
            radius = radius,
            head = head,
            angleRad = angleRad,
            color = required.toNeon(),
            urgency = 1f - (distance - radius) / (far - radius),
        )
        // La estela se acorta mientras espera y se alarga al lanzarse: la velocidad se ve.
        val speedFactor = when {
            absorb > 0f -> 0.6f + 0.9f * dive
            hold > 0f -> 0.25f
            else -> 1.1f - 0.85f * arrive
        }
        drawComet(head = head, angleRad = angleRad, required = required, speedFactor = speedFactor)
    }

    /**
     * El meteorito del guion: **llega** frenando ([METEOR_APPROACH]) y, ya con el escudo encendido
     * ([METEOR_BARRIER]), **se lanza** y se deshace contra la burbuja.
     */
    private fun DrawScope.drawScriptedMeteor(playback: TutorialPlayback, center: Offset, radius: Float, time: Float) {
        if (playback.timelineSec < playback.stepStartSec(METEOR_APPROACH)) return
        val hold = playback.stepProgress(METEOR_BARRIER)
        if (hold >= METEOR_IMPACT_AT) return

        val far = maxOf(size.width, size.height) * 0.80f
        val arrive = playback.stepProgress(METEOR_APPROACH)
        val dive = ((hold - METEOR_DIVE_FROM) / (METEOR_IMPACT_AT - METEOR_DIVE_FROM)).coerceIn(0f, 1f)
        val wait = (hold / METEOR_DIVE_FROM).coerceIn(0f, 1f)
        val distance = when {
            dive > 0f -> radius * (METEOR_HOLD_TO - (METEOR_HOLD_TO - BARRIER_RADIUS_FACTOR) * dive * dive)
            hold > 0f -> radius * (METEOR_HOLD_FROM - (METEOR_HOLD_FROM - METEOR_HOLD_TO) * wait)
            else -> far - (far - radius * METEOR_HOLD_FROM) * (1f - (1f - arrive) * (1f - arrive))
        }
        val head = Offset(center.x + cos(ANGLE_METEOR) * distance, center.y + sin(ANGLE_METEOR) * distance)
        drawImpactMarker(
            center = center,
            radius = radius,
            head = head,
            angleRad = ANGLE_METEOR,
            color = LogicColors.Error,
            urgency = 1f - (distance - radius) / (far - radius),
        )
        val speedFactor = when {
            dive > 0f -> 0.6f + 0.9f * dive
            hold > 0f -> 0.25f
            else -> 1.1f - 0.85f * arrive
        }
        drawMeteor(head, ANGLE_METEOR, speedFactor, time, seed = 5)
    }

    /** El destello de absorción del cometa de [absorbStep], desde el instante en que toca el anillo. */
    private fun DrawScope.drawScriptedImpact(
        playback: TutorialPlayback,
        center: Offset,
        radius: Float,
        angleRad: Float,
        required: ShieldState,
        absorbStep: Int,
        seed: Int,
    ) {
        val sinceImpact = (playback.stepProgress(absorbStep) - IMPACT_AT) * playback.stepDurationSec(absorbStep)
        // `drawGateImpact` descarta por sí mismo las edades fuera de la vida del efecto.
        drawGateImpact(center, radius, angleRad, required.toNeon(), success = true, age = sinceImpact, seed = seed)
    }

    /** "Edad" de algo que no ha ocurrido: lo bastante grande para que ningún efecto la dibuje. */
    private const val NEVER = 10f
}
