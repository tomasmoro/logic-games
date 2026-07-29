package com.kortexgames.app.game.hypergate

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * # HypergateViewModel — puente MVI del Hypergate (Fase 2)
 *
 * Une el [HypergateEngine] (física/colisiones, portable y testeable) con el contrato MVI de la
 * pantalla. Sigue el mismo molde que `PolarityCollisionViewModel`:
 *  - refleja `engine.state`/`engine.status` en el [HypergateUiState],
 *  - al finalizar la partida guarda el resultado (local-first) antes de abrir el overlay final.
 *
 * ## Traducción de efectos de impacto
 * El motor emite eventos semánticos ([HypergateEffect]); este ViewModel los colecta y hace **dos**
 * cosas por cada uno: (1) dispara el feedback concreto de plataforma (sonido + háptica) y (2) lo
 * reenvía por el canal de efectos MVI para que la UI pueda reaccionar (p. ej. un destello del
 * escudo). Mantener la traducción aquí deja al motor libre de dependencias de plataforma.
 */
class HypergateViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<HypergateIntent, HypergateUiState, HypergateEffect>(
    HypergateUiState(),
) {

    private val engine = HypergateEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.effects.onEach(::onImpactEffect).launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La partida
        // empieza al pulsar "Comenzar" (intent [HypergateIntent.Start]).
    }

    override fun onIntent(intent: HypergateIntent) {
        when (intent) {
            is HypergateIntent.UpdateViewport -> engine.updateViewport(intent.widthPx, intent.heightPx)
            HypergateIntent.ToggleShield -> engine.toggleShield()
            is HypergateIntent.Tick -> engine.onFrame(intent.frameNanos)
            HypergateIntent.Pause -> engine.pause()
            HypergateIntent.Resume -> engine.resume()
            HypergateIntent.Start,
            HypergateIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    /**
     * Traduce un efecto de impacto del motor a feedback de plataforma y lo reenvía a la UI.
     *
     * El mapeo a los enums de audio es intencional: `ABSORB→SUCCESS`, `CRASH→ERROR` (sonidos), y
     * `SUCCESS→LIGHT` (un pulso corto y ágil por absorción, no invasivo) frente a `ERROR` (patrón
     * de error marcado) en háptica.
     */
    private fun onImpactEffect(effect: HypergateEffect) {
        when (effect) {
            is HypergateEffect.PlaySound -> audio.playSound(
                when (effect.cue) {
                    HypergateEffect.PlaySound.Cue.ABSORB -> SoundEffect.SUCCESS
                    HypergateEffect.PlaySound.Cue.CRASH -> SoundEffect.ERROR
                },
            )
            is HypergateEffect.Vibrate -> audio.hapticFeedback(
                when (effect.cue) {
                    HypergateEffect.Vibrate.Cue.SUCCESS -> HapticFeedback.LIGHT
                    HypergateEffect.Vibrate.Cue.ERROR -> HapticFeedback.ERROR
                },
            )
        }
        // Reenvío one-shot para que la UI (Fase 3) pueda animar el impacto si lo desea.
        sendEffect(effect)
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }
}
