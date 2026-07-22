package com.example.kortexgames.game.energyflow

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.MviViewModel
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.repository.PlayerProgressRepository
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Estado de UI de la pantalla de "Flujo de Energía".
 *
 * @property phase selección de nivel o partida en curso.
 * @property maxUnlocked nivel máximo ya superado (récord); define lo desbloqueado.
 * @property currentLevel nivel que se está jugando (para "Siguiente nivel").
 * @property levelTimes mejor tiempo por nivel (nivel → ms); lo muestra el selector.
 */
data class EnergyFlowUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: EnergyFlowState = EnergyFlowState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val levelTimes: Map<Int, Long> = emptyMap(),
) : UiState

/** Intents (único punto de entrada de la UI, patrón MVI). */
sealed interface EnergyFlowIntent : UiIntent {
    /** El jugador tocó la pieza [index] para girarla 90° en horario. */
    data class RotateTile(val index: Int) : EnergyFlowIntent
    data object Restart : EnergyFlowIntent

    /** Pausa la partida (menú de pausa): congela el cronómetro de la partida. */
    data object Pause : EnergyFlowIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : EnergyFlowIntent

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : EnergyFlowIntent

    /** Desde el game-over: rejugar el mismo nivel. */
    data object PlayAgain : EnergyFlowIntent

    /** Desde el game-over: avanzar al siguiente nivel. */
    data object NextLevel : EnergyFlowIntent

    /** Volver al selector de niveles (desde el game-over). */
    data object ChooseLevel : EnergyFlowIntent
}

sealed interface EnergyFlowEffect : UiEffect

/**
 * ViewModel MVI de "Flujo de Energía". Juego **LEVELED**: arranca en el selector de
 * niveles ([LeveledGamePhase.LEVEL_SELECT]); al elegir un nivel desbloqueado el
 * motor genera su rejilla paramétrica y se juega. Al cerrar el circuito, persiste
 * el resultado (local-first) y el récord de nivel; el jugador puede repetir, avanzar
 * al siguiente o volver al selector. El nivel máx desbloqueado se observa desde
 * [PlayerProgressRepository].
 */
class EnergyFlowViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<EnergyFlowIntent, EnergyFlowUiState, EnergyFlowEffect>(EnergyFlowUiState()) {

    private val engine = EnergyFlowEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        playerProgress.observe(GameIds.ENERGY_FLOW)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        playerProgress.observeLevelTimes(GameIds.ENERGY_FLOW)
            .onEach { times -> setState { copy(levelTimes = times) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: EnergyFlowIntent) {
        when (intent) {
            is EnergyFlowIntent.RotateTile -> engine.onTileRotate(intent.index)
            EnergyFlowIntent.Restart -> engine.restart()
            EnergyFlowIntent.Pause -> engine.pause()
            EnergyFlowIntent.Resume -> engine.resume()
            is EnergyFlowIntent.PlayLevel -> playLevel(intent.level)
            EnergyFlowIntent.PlayAgain -> playLevel(currentState.currentLevel)
            EnergyFlowIntent.NextLevel -> playLevel(currentState.currentLevel + 1)
            EnergyFlowIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /** Empieza (o reempieza) un nivel concreto: limpia el game-over y arranca el motor. */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        engine.startAtLevel(level)
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
