package com.kortexgames.app.game.neoncircuit

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
 * ViewModel MVI de "Neon Circuit Flow". Juego **LEVELED**: arranca en el selector
 * de niveles y, al elegir uno, se carga del catálogo ([NeonCircuitLevels]) y se
 * juega. Al conectar todos los pares persiste el resultado (local-first) y el
 * récord de nivel máximo.
 *
 * **Feedback sensorial vía Effects:** igual que Starport, el motor emite eventos
 * de dominio ([NeonCircuitEvent]) y este ViewModel los **traduce a Effects
 * one-shot** que la pantalla consume. La tabla evento→(sonido, háptica) vive en
 * [onEngineEvent], un único sitio auditable.
 */
class NeonCircuitViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<NeonCircuitIntent, NeonCircuitUiState, NeonCircuitEffect>(NeonCircuitUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa: todo
    // el feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = NeonCircuitEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Nivel máximo desbloqueado (récord), reactivo y local-first. No se arranca
        // el motor: se empieza en el selector y el jugador elige.
        playerProgress.observe(GameIds.NEON_CIRCUIT)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: NeonCircuitIntent) {
        when (intent) {
            is NeonCircuitIntent.PlayLevel -> playLevel(intent.level)
            is NeonCircuitIntent.StartPath -> engine.onPathStarted(intent.color, intent.cell)
            is NeonCircuitIntent.ResumePath -> engine.onPathResumed(intent.color, intent.cell)
            is NeonCircuitIntent.ExtendPath -> engine.onPathExtended(intent.cell)
            NeonCircuitIntent.EndPath -> engine.onPathEnded()
            NeonCircuitIntent.Restart -> engine.restart()
            NeonCircuitIntent.Pause -> engine.pause()
            NeonCircuitIntent.Resume -> engine.resume()
            NeonCircuitIntent.PlayAgain -> playLevel(currentState.currentLevel)
            NeonCircuitIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            NeonCircuitIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     *
     * Nota: [NeonCircuitEvent.CellAdvanced] emite solo háptica: el brief pedía una
     * "estática suave" por celda, pero el catálogo [SoundEffect] aún no tiene ese
     * asset. Cuando se añada, se cablea aquí sin tocar motor ni UI.
     */
    private fun onEngineEvent(event: NeonCircuitEvent) {
        when (event) {
            NeonCircuitEvent.NodeGrabbed -> {
                sendEffect(NeonCircuitEffect.PlaySound(SoundEffect.TAP))
                sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.LIGHT))
            }
            // Tick táctil por celda; sin sonido hasta tener el SFX de estática.
            NeonCircuitEvent.CellAdvanced ->
                sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.LIGHT))
            // Retroceder no suena: no pasó nada en términos de juego.
            NeonCircuitEvent.PathRetracted ->
                sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.LIGHT))
            // Cortar es sobre todo táctil (HEAVY = "golpe"); el SFX de error
            // refuerza sin ser castigo — cruzar es información, no fallo.
            NeonCircuitEvent.WireCut -> {
                sendEffect(NeonCircuitEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.HEAVY))
            }
            is NeonCircuitEvent.PairConnected -> {
                sendEffect(NeonCircuitEffect.PlaySound(SoundEffect.SUCCESS))
                sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.SUCCESS))
                sendEffect(NeonCircuitEffect.PulsePath(event.color))
            }
            // Todos los pares unidos pero faltan casillas: abre el cartel explicativo.
            NeonCircuitEvent.BoardNotFull -> sendEffect(NeonCircuitEffect.ShowFillHint)
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
            sendEffect(NeonCircuitEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(NeonCircuitEffect.Vibrate(HapticFeedback.SUCCESS))
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}
