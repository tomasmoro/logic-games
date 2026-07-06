package com.example.kortexgames.game.watersort

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

/** Estado de UI de la pantalla de "Ordena las Pociones". */
data class WaterSortUiState(
    val game: WaterSortState = WaterSortState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents (único punto de entrada de la UI, patrón MVI). */
sealed interface WaterSortIntent : UiIntent {
    /** El jugador tocó el tubo [index] (selección de origen o destino). */
    data class TapTube(val index: Int) : WaterSortIntent
    data object Undo : WaterSortIntent
    data object Restart : WaterSortIntent
    data object PlayAgain : WaterSortIntent
    data object Pause : WaterSortIntent
    data object Resume : WaterSortIntent
}

sealed interface WaterSortEffect : UiEffect

/**
 * ViewModel MVI de "Ordena las Pociones". Mismo patrón que
 * [com.example.kortexgames.game.reflex.ReflexTapViewModel]: posee el motor,
 * proyecta su `state`/`status`/`outcome` y persiste el resultado + percentil al
 * ganar (local-first). El sonido de **fin de partida** ([SoundEffect.LEVEL_UP])
 * se dispara aquí, coherente con los demás juegos.
 */
class WaterSortViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<WaterSortIntent, WaterSortUiState, WaterSortEffect>(WaterSortUiState()) {

    private val engine = WaterSortEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.start()
    }

    override fun onIntent(intent: WaterSortIntent) {
        when (intent) {
            is WaterSortIntent.TapTube -> engine.onTubeTap(intent.index)
            WaterSortIntent.Undo -> engine.undo()
            WaterSortIntent.Restart -> engine.restart()
            WaterSortIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
            WaterSortIntent.Pause -> engine.pause()
            WaterSortIntent.Resume -> engine.resume()
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
