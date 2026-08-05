package com.kortexgames.app.game.neonline

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Línea Neón". Juego **LEVELED**: arranca en el selector de
 * niveles y, al elegir uno, se genera ([NeonLineLevels]) y se juega. Al cubrir el
 * tablero persiste el resultado (local-first) y el récord de nivel máximo.
 *
 * **Feedback sensorial vía Effects:** igual que Conectores y Starport, el motor
 * emite eventos de dominio ([NeonLineEvent]) y este ViewModel los **traduce a
 * Effects one-shot** que la pantalla consume. La tabla evento→(sonido, háptica) vive
 * en [onEngineEvent], un único sitio auditable.
 */
class NeonLineViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<NeonLineIntent, NeonLineUiState, NeonLineEffect>(NeonLineUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa: todo el
    // feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = NeonLineEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Nivel máximo desbloqueado (récord), reactivo y local-first. No se arranca el
        // motor: se empieza en el selector y el jugador elige.
        playerProgress.observe(GameIds.NEON_LINE)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: NeonLineIntent) {
        when (intent) {
            is NeonLineIntent.PlayLevel -> playLevel(intent.level)
            is NeonLineIntent.StartPath -> engine.onPathStarted(intent.cell)
            is NeonLineIntent.UpdatePath -> engine.onPathUpdated(intent.cell)
            NeonLineIntent.ReleasePath -> engine.onPathEnded()
            NeonLineIntent.RestartLevel -> engine.restart()
            NeonLineIntent.Pause -> engine.pause()
            NeonLineIntent.Resume -> engine.resume()
            NeonLineIntent.PlayAgain -> playLevel(currentState.currentLevel)
            NeonLineIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            NeonLineIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     *
     * La decisión de diseño que manda aquí es que **avanzar una celda solo vibra**.
     * En un tablero de hasta 48 celdas libres, un SFX por celda sería un traqueteo
     * constante; el tick háptico, en cambio, es justo la textura "ASMR" que hace
     * agradable el arrastre y confirma el avance sin obligar a mirar. El sonido se
     * reserva a los hitos: empezar, chocar y completar.
     */
    private fun onEngineEvent(event: NeonLineEvent) {
        when (event) {
            NeonLineEvent.PathStarted -> {
                sendEffect(NeonLineEffect.PlaySound(SoundEffect.TAP))
                sendEffect(NeonLineEffect.Vibrate(HapticFeedback.LIGHT))
            }
            // El tick por celda: el corazón táctil del juego (ver KDoc del método).
            NeonLineEvent.CellAdvanced ->
                sendEffect(NeonLineEffect.Vibrate(HapticFeedback.LIGHT))
            // Retroceder es una corrección legítima, no un fallo: mismo tick suave,
            // sin sonido de error que la castigue.
            NeonLineEvent.PathRetracted ->
                sendEffect(NeonLineEffect.Vibrate(HapticFeedback.LIGHT))
            is NeonLineEvent.MoveRejected -> {
                sendEffect(NeonLineEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(NeonLineEffect.Vibrate(HapticFeedback.ERROR))
                sendEffect(NeonLineEffect.RejectMove(event.cell))
            }
            // El barrido de luz por la línea; el sonido/háptica de victoria los emite
            // onFinished, junto con el guardado del resultado.
            NeonLineEvent.Solved -> sendEffect(NeonLineEffect.CircuitCompleted)
        }
    }

    /** Empieza (o reempieza) un nivel: limpia el game-over y arranca el motor. */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        engine.startAtLevel(level)
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            sendEffect(NeonLineEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(NeonLineEffect.Vibrate(HapticFeedback.SUCCESS))
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}
