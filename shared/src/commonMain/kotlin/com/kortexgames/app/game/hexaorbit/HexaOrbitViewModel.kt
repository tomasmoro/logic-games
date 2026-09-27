package com.kortexgames.app.game.hexaorbit

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.EventsRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.EventGameOverInfo
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
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
 * ## Modo torneo
 *
 * Si [event] no es null, la corrida pertenece a un torneo: el resultado se envía además a
 * `submit_event_result` y salir a mitad gasta el intento (por eso [requestExit] pide
 * confirmación). Hexa Orbit no fija tablero —es ENDLESS y no hay semilla que clavar—, pero el
 * reto ya es idéntico para todos por construcción: la curva de dificultad vive dentro de la
 * partida y sube igual para cualquiera.
 *
 * El revive por anuncio se deja tal cual en torneo: es **uno por corrida** y además
 * [HexaOrbitBalance.REVIVE_PENALTY] descuenta puntos, así que no convierte la tabla en un
 * concurso de ver publicidad.
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
    private val event: GameEvent? = null,
    private val events: EventsRepository? = null,
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
            // Torneo: después de guardar, no en paralelo. El historial local es la
            // fuente de verdad y no debe depender de que el torneo acepte; el cartel
            // ya está en pantalla y el puesto se rellena solo un instante después.
            submitToEvent(result)
        }
    }

    /**
     * Envía la corrida al torneo, si la había. Se manda también la derrota —en un ENDLESS
     * terminar ES perder— porque un intento gastado es un intento gastado.
     *
     * El fallo no se traga: viaja al cartel en [EventGameOverInfo] para que el jugador sepa
     * que su marca no entró. Silenciarlo sería lo peor posible: acaba de jugar creyendo que
     * competía.
     */
    private suspend fun submitToEvent(result: GameResult) {
        val event = event ?: return
        val events = events ?: return
        val outcome = events.submitResult(
            event = event,
            score = result.score,
            completionTimeMs = result.completionTimeMs,
            accuracyPercentage = result.accuracyPercentage,
            difficultyLevel = result.difficultyLevel,
        )
        setState { copy(gameOver = gameOver?.copy(event = EventGameOverInfo.from(event, outcome))) }
    }

    /**
     * Salida "en juego" (menú de pausa o atrás del sistema).
     *
     * Fuera de un torneo se sale directo, como siempre: Hexa Orbit es ENDLESS y no guarda
     * corridas a medias. En torneo, salir GASTA el intento (migración 0056), así que primero
     * se pregunta.
     */
    fun requestExit(onExit: () -> Unit) {
        val inRun = currentState.status == GameStatus.RUNNING || currentState.status == GameStatus.PAUSED
        if (event == null || !inRun) {
            onExit()
            return
        }
        // La física se congela mientras el jugador decide: leer un aviso no puede costarle la
        // partida. Se recuerda si estaba corriendo para no "reanudar" algo que ya estaba en
        // pausa cuando el aviso salió del propio menú de pausa.
        resumeAfterExitPrompt = currentState.status == GameStatus.RUNNING
        if (resumeAfterExitPrompt) onIntent(HexaOrbitIntent.Pause)
        setState { copy(showEventExitConfirm = true) }
    }

    /** Confirma el abandono: gasta el intento en el servidor y sale. */
    fun confirmEventExit(onExit: () -> Unit) {
        setState { copy(showEventExitConfirm = false) }
        event?.let { events?.abandonAttempt(it) }
        onExit()
    }

    /** El jugador se queda: se cierra el aviso y la partida sigue donde estaba. */
    fun dismissEventExit() {
        setState { copy(showEventExitConfirm = false) }
        if (resumeAfterExitPrompt) onIntent(HexaOrbitIntent.Resume)
        resumeAfterExitPrompt = false
    }

    /** Si al cerrar el aviso de abandono hay que reanudar (ver [requestExit]). */
    private var resumeAfterExitPrompt = false
}
