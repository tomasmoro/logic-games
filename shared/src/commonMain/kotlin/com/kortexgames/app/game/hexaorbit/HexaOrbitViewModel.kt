package com.kortexgames.app.game.hexaorbit

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
 * # HexaOrbitViewModel — puente MVI de Hexa Orbit (FASE 2)
 *
 * Une el [HexaOrbitEngine] (bucle de juego, portable y testeable) con el contrato MVI de la
 * pantalla, siguiendo el mismo molde que `LegionViewModel`:
 *  - refleja `engine.state`/`engine.status` en el [HexaOrbitUiState],
 *  - al terminar la partida guarda el resultado (local-first) antes de abrir el overlay final.
 *
 * ## Dificultad única, tabla única
 *
 * Hexa Orbit no tiene selector de dificultad: es un ENDLESS cuya curva vive dentro de la propia
 * partida (la rapidez sube sola con el tiempo), así que todas las corridas compiten en el MISMO
 * reto y una tabla única de ranking es justa por construcción. Por eso no toca
 * `GameRankingScopes` ni `DifficultyUnlocks`, y el motor se crea una sola vez.
 *
 * ## El bucle lo dirige la PANTALLA
 *
 * El ViewModel no arranca ninguna corrutina de temporización: es la pantalla quien emite
 * [HexaOrbitIntent.Tick] desde `withFrameNanos` (FASE 3). Así la simulación queda atada al ritmo
 * real de dibujo —se detiene sola si la vista deja de componerse— en vez de correr en paralelo
 * y desincronizarse de lo que el jugador ve.
 *
 * ## El REVIVE se enruta, no se decide aquí
 *
 * [HexaOrbitIntent.Revive]/[HexaOrbitIntent.DeclineRevive] llegan tras la decisión del jugador en
 * `ReviveAdOverlay` (que vive en la pantalla y habla directo con el `AdManager`); este ViewModel
 * solo los reenvía al motor. Ver el KDoc de [HexaOrbitEngine] para el reparto completo.
 */
class HexaOrbitViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<HexaOrbitIntent, HexaOrbitUiState, HexaOrbitEffect>(
    HexaOrbitUiState(),
) {

    private val engine = HexaOrbitEngine(viewModelScope, audio)

    init {
        // `awaitingRevive` se espeja del dominio al UiState en la misma suscripción que `game`:
        // es el motor quien abre/cierra la oferta (ver KDoc de HexaOrbitState.awaitingRevive).
        engine.state
            .onEach { game -> setState { copy(game = game, awaitingRevive = game.awaitingRevive) } }
            .launchIn(viewModelScope)
        engine.status.onEach { status -> setState { copy(status = status) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.effects.onEach(::onGameEffect).launchIn(viewModelScope)

        // Comparativa mundial para la antesala. Sin selector de dificultad no hay que re-pedirla
        // al cambiar de escalón: una única carga al entrar basta (tabla única).
        viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.HEXA_ORBIT)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }

        // No se arranca aquí: el juego queda en IDLE y muestra la antesala (intro). La partida
        // empieza al pulsar "Comenzar" (intent [HexaOrbitIntent.Start]).
    }

    override fun onIntent(intent: HexaOrbitIntent) {
        when (intent) {
            is HexaOrbitIntent.Tick -> engine.onFrame(intent.frameNanos)
            is HexaOrbitIntent.RotateTile -> engine.rotateTile(intent.coord)
            HexaOrbitIntent.Revive -> engine.grantRevive()
            HexaOrbitIntent.DeclineRevive -> engine.declineRevive()
            HexaOrbitIntent.Pause -> engine.pause()
            HexaOrbitIntent.Resume -> engine.resume()
            HexaOrbitIntent.Start,
            HexaOrbitIntent.RestartGame -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    /**
     * Traduce un efecto semántico del motor a feedback de plataforma y lo reenvía a la UI.
     *
     * El mapeo sonoro está elegido por **frecuencia e intensidad**, no solo por semántica: el
     * giro es el gesto que más se repite (varias veces por segundo en una partida rápida), así
     * que se lleva [SoundEffect.MERGE_POP] —el clic más discreto del catálogo— en vez de un
     * [SoundEffect.TAP] que a ese ritmo ensuciaría. La recogida y el fin de partida sí son
     * momentos contados y usan los cues fuertes.
     *
     * En háptica, el giro va con [HapticFeedback.LIGHT] por el mismo motivo (un patrón largo
     * repetido cansaría la mano) y la fuga con [HapticFeedback.ERROR], marcado: ocurre una sola
     * vez por partida y debe sentirse.
     */
    private fun onGameEffect(effect: HexaOrbitEffect) {
        when (effect) {
            is HexaOrbitEffect.PlaySound -> audio.playSound(
                when (effect.cue) {
                    HexaOrbitEffect.PlaySound.Cue.ROTATE -> SoundEffect.MERGE_POP
                    HexaOrbitEffect.PlaySound.Cue.COLLECT_POINT -> SoundEffect.SUCCESS
                    HexaOrbitEffect.PlaySound.Cue.GAME_OVER -> SoundEffect.ERROR
                },
            )

            is HexaOrbitEffect.Vibrate -> audio.hapticFeedback(
                when (effect.cue) {
                    HexaOrbitEffect.Vibrate.Cue.LIGHT -> HapticFeedback.LIGHT
                    HexaOrbitEffect.Vibrate.Cue.SUCCESS -> HapticFeedback.SUCCESS
                    HexaOrbitEffect.Vibrate.Cue.ERROR -> HapticFeedback.ERROR
                },
            )
        }
        // Reenvío one-shot para que la pantalla (FASE 3) pueda animar el momento: el destello de
        // la partícula al recoger y el fogonazo de la fuga se disparan desde aquí.
        sendEffect(effect)
    }

    /**
     * Guarda el resultado y abre el overlay de fin. `saveResult` emite en 1 o 2 pasos: local
     * primero (el cartel no espera a Supabase) y, con sesión, el percentil/ranking real después
     * (ver KDoc de `ProgressRepository.saveResult`).
     *
     * No suena fanfarria al terminar: en Hexa Orbit terminar es **perder** el puntero, y ese
     * momento ya sonó como [HexaOrbitEffect.PlaySound.Cue.GAME_OVER]. Celebrar encima de la
     * derrota mandaría el mensaje contrario.
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
