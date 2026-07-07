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
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Estado de UI de la pantalla de "Flujo de Energía". */
data class EnergyFlowUiState(
    val game: EnergyFlowState = EnergyFlowState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents (único punto de entrada de la UI, patrón MVI). */
sealed interface EnergyFlowIntent : UiIntent {
    /** El jugador tocó la pieza [index] para girarla 90° en horario. */
    data class RotateTile(val index: Int) : EnergyFlowIntent
    data object Restart : EnergyFlowIntent
    data object PlayAgain : EnergyFlowIntent
}

sealed interface EnergyFlowEffect : UiEffect

/**
 * ViewModel MVI de "Flujo de Energía". Mismo patrón que los demás juegos
 * ([com.example.kortexgames.game.watersort.WaterSortViewModel]): posee el motor,
 * proyecta su `state`/`status`/`outcome` y, al cerrar el circuito, persiste el
 * resultado (local-first) obteniendo el percentil frente al resto de jugadores. El
 * sonido de **fin de partida** ([SoundEffect.LEVEL_UP]) se dispara aquí.
 */
class EnergyFlowViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<EnergyFlowIntent, EnergyFlowUiState, EnergyFlowEffect>(EnergyFlowUiState()) {

    private val engine = EnergyFlowEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.start()
    }

    override fun onIntent(intent: EnergyFlowIntent) {
        when (intent) {
            is EnergyFlowIntent.RotateTile -> engine.onTileRotate(intent.index)
            EnergyFlowIntent.Restart -> engine.restart()
            EnergyFlowIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val percentile = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = GameOverInfo(result, percentile)) }
        }
    }
}
