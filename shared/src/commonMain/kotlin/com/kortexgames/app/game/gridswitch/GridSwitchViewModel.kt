package com.kortexgames.app.game.gridswitch

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Neon Grid Switch". Juego **LEVELED sin selector**: arranca
 * en IDLE (antesala), [GridSwitchIntent.StartGame] empieza la etapa 1 y cada
 * etapa resuelta persiste su resultado (récord = etapa alcanzada, local-first)
 * antes de que [GridSwitchIntent.NextStage] arranque la siguiente — mismo molde
 * que `NeonLineViewModel`/`HyperCubeViewModel` (LEVELED "que nunca fallan").
 *
 * Reparto de responsabilidades:
 *  - Reglas (conmutación ortogonal, conteo de movimientos, victoria) → [GridSwitchEngine].
 *  - Feedback sensorial → Effects one-shot, con la tabla evento→(sonido, háptica)
 *    centralizada en [onEngineEvent] (mismo esquema que el resto del catálogo).
 */
class GridSwitchViewModel(
    private val progress: ProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<GridSwitchIntent, GridSwitchUiState, GridSwitchEffect>(GridSwitchUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa: todo el
    // feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = GridSwitchEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s ->
            setState {
                copy(gridSize = s.board.size, board = s.board, moveCount = s.moveCount, isCompleted = s.isSolved)
            }
        }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // No se arranca aquí: se queda en IDLE mostrando la antesala y la
        // partida empieza con StartGame (patrón del resto de juegos LEVELED).
    }

    override fun onIntent(intent: GridSwitchIntent) {
        when (intent) {
            GridSwitchIntent.StartGame -> playStage(1)
            is GridSwitchIntent.ToggleCell -> engine.onCellToggled(intent.cell)
            GridSwitchIntent.RestartStage -> engine.restartStage()
            GridSwitchIntent.NextStage -> {
                // Breakpoint de avance de etapa (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playStage(currentState.stageLevel + 1)
            }
            GridSwitchIntent.Pause -> engine.pause()
            GridSwitchIntent.Resume -> engine.resume()
            // Sin selector de nivel, "nueva partida" solo puede significar volver
            // al principio de la progresión (mismo criterio que el "Empezar de
            // nuevo" de los juegos ENDLESS, adaptado a que aquí sí hay etapas).
            GridSwitchIntent.PlayAgain -> playStage(1)
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     *
     * [GridSwitchEvent.StageCleared] no dispara nada aquí a propósito: su
     * sonido/háptica de victoria viajan en [onFinished], junto con el guardado
     * del resultado, para que acompañen la aparición del cartel en vez de
     * adelantarse a él (mismo criterio que Línea Neón).
     */
    private fun onEngineEvent(event: GridSwitchEvent) {
        when (event) {
            GridSwitchEvent.CellToggled -> {
                sendEffect(GridSwitchEffect.PlaySound(SoundEffect.TAP))
                sendEffect(GridSwitchEffect.Vibrate(HapticFeedback.LIGHT))
            }
            GridSwitchEvent.StageCleared -> Unit
        }
    }

    /** Empieza (o reempieza) la etapa [stage]: limpia el game-over y arranca el motor. */
    private fun playStage(stage: Int) {
        setState { copy(stageLevel = stage, gameOver = null) }
        engine.startAtStage(stage)
    }

    /**
     * El sonido/háptica de victoria se disparan de inmediato (no dependen de red).
     * `saveResult` emite el `gameOver` en 1 o 2 pasos: primero el resultado LOCAL
     * (récord ya conocido) para que el cartel aparezca sin esperar a Supabase, y
     * luego —si hay sesión— el percentil/ranking cuando la red responda (ver KDoc
     * de `ProgressRepository.saveResult`).
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            sendEffect(GridSwitchEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(GridSwitchEffect.Vibrate(HapticFeedback.SUCCESS))
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
