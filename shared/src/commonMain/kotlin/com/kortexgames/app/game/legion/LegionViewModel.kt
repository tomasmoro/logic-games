package com.kortexgames.app.game.legion

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * # LegionViewModel — puente MVI de Neon Legion (Fase 4)
 *
 * Une el [LegionEngine] (carrera, láseres y combate, portable y testeable) con el contrato MVI
 * de la pantalla. Sigue el mismo molde que `HypergateViewModel`:
 *  - refleja `engine.state`/`engine.status` en el [LegionUiState],
 *  - al terminar la partida guarda el resultado (local-first) antes de abrir el overlay final.
 *
 * ## Dificultad única, tabla única
 * Neon Legion no tiene selector de dificultad: es un ENDLESS cuya curva vive dentro de la propia
 * partida (velocidad, filas, láseres y enemigo escalan con la ronda), así que todas las corridas
 * compiten en el MISMO reto y una tabla única de ranking es justa por construcción. Por eso este
 * ViewModel no toca `GameRankingScopes` ni `DifficultyUnlocks`, y el motor se crea una sola vez
 * (a diferencia de `QuantumMergeViewModel`, que reconstruye el suyo por escalón).
 *
 * ## El flujo del REVIVE vive repartido en tres piezas, a propósito
 * (1) El MOTOR decide cuándo se ofrece (combate perdido con revive disponible) y congela la
 * simulación; (2) la PANTALLA muestra `ReviveAdOverlay` y ejecuta el anuncio recompensado (el
 * `AdManager` es una dependencia de UI, como en Neon 2048); (3) este ViewModel solo enruta los
 * intents resultantes ([LegionIntent.Revive] tras `EARNED`, [LegionIntent.DeclineRevive] si no).
 * Así el motor nunca conoce el AdManager y el revive es testeable sin anuncios.
 */
class LegionViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<LegionIntent, LegionUiState, LegionEffect>(
    LegionUiState(),
) {

    private val engine = LegionEngine(viewModelScope, audio)

    init {
        // `awaitingRevive` se espeja del dominio al UiState en la misma suscripción que `game`:
        // es el motor quien abre/cierra la oferta (ver KDoc de LegionState.awaitingRevive).
        engine.state
            .onEach { s -> setState { copy(game = s, awaitingRevive = s.awaitingRevive) } }
            .launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.effects.onEach(::onGameEffect).launchIn(viewModelScope)

        // Comparativa mundial para la antesala. Sin selector de dificultad no hay que
        // re-pedirla al cambiar de escalón: una única carga al entrar basta (tabla única).
        viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.NEON_LEGION)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }

        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La partida
        // empieza al pulsar "Comenzar" (intent [LegionIntent.Start]).
    }

    override fun onIntent(intent: LegionIntent) {
        when (intent) {
            is LegionIntent.Tick -> engine.onFrame(intent.frameNanos)
            is LegionIntent.AimAt -> engine.aimAt(intent.laneX)
            is LegionIntent.AnswerQuiz -> engine.answerQuiz(intent.optionIndex)
            LegionIntent.Revive -> engine.grantRevive()
            LegionIntent.DeclineRevive -> engine.declineRevive()
            LegionIntent.Pause -> engine.pause()
            LegionIntent.Resume -> engine.resume()
            LegionIntent.Start,
            LegionIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    /**
     * Traduce un efecto semántico del motor a feedback de plataforma y lo reenvía a la UI.
     *
     * El mapeo sonoro está elegido por **frecuencia e intensidad**, no solo por semántica:
     *  - Los cues que suenan muchas veces por ronda usan los assets discretos del catálogo:
     *    el cambio de carril lleva [SoundEffect.MERGE_POP] (el clic más suave, confirma sin
     *    ensuciar) y el láser esquivado un [SoundEffect.TAP] neutro (es ambiente, no castigo).
     *  - Los momentos de premio/castigo reales llevan los cues fuertes: puerta buena/mala
     *    ([SoundEffect.SUCCESS]/[SoundEffect.ERROR]), impacto de láser y fallo del examen
     *    (ERROR), y el combate ganado se lleva el [SoundEffect.LEVEL_UP] — es el "subiste de
     *    ronda" literal del juego.
     *
     * En háptica, `SUCCESS→LIGHT` (pulso corto: los aciertos son frecuentes y un patrón largo
     * cansaría) y `ERROR→ERROR` (marcado: los castigos son raros y deben sentirse).
     */
    private fun onGameEffect(effect: LegionEffect) {
        when (effect) {
            is LegionEffect.PlaySound -> audio.playSound(
                when (effect.cue) {
                    LegionEffect.PlaySound.Cue.GATE_GAIN -> SoundEffect.SUCCESS
                    LegionEffect.PlaySound.Cue.GATE_LOSS -> SoundEffect.ERROR
                    LegionEffect.PlaySound.Cue.LANE_SWITCH -> SoundEffect.MERGE_POP
                    LegionEffect.PlaySound.Cue.LASER_FIRE -> SoundEffect.TAP
                    LegionEffect.PlaySound.Cue.LASER_HIT -> SoundEffect.ERROR
                    LegionEffect.PlaySound.Cue.QUIZ_CORRECT -> SoundEffect.SUCCESS
                    LegionEffect.PlaySound.Cue.QUIZ_WRONG -> SoundEffect.ERROR
                    LegionEffect.PlaySound.Cue.COMBAT_WIN -> SoundEffect.LEVEL_UP
                    LegionEffect.PlaySound.Cue.COMBAT_LOSS -> SoundEffect.ERROR
                },
            )

            is LegionEffect.Vibrate -> audio.hapticFeedback(
                when (effect.cue) {
                    LegionEffect.Vibrate.Cue.SUCCESS -> HapticFeedback.LIGHT
                    LegionEffect.Vibrate.Cue.ERROR -> HapticFeedback.ERROR
                },
            )
        }
        // Reenvío one-shot para que la UI (Fase 5) pueda animar el momento si lo desea.
        sendEffect(effect)
    }

    /**
     * Guarda el resultado y abre el overlay de fin. `saveResult` emite en 1 o 2 pasos: local
     * primero (el cartel no espera a Supabase) y, con sesión, el percentil/ranking real después
     * (ver KDoc de `ProgressRepository.saveResult`).
     *
     * A diferencia de Hypergate, aquí NO suena fanfarria al terminar: en Neon Legion terminar
     * es PERDER el combate, y ese momento ya sonó como [LegionEffect.PlaySound.Cue.COMBAT_LOSS]
     * en el choque. Celebrar encima de la derrota mandaría el mensaje contrario.
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
