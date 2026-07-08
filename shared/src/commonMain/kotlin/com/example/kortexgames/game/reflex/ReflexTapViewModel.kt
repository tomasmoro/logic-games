package com.example.kortexgames.game.reflex

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.audio.AudioAndHapticManager
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

/** Estado de UI de la pantalla de Reflejos. */
data class ReflexTapUiState(
    val game: ReflexTapState = ReflexTapState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

sealed interface ReflexTapIntent : UiIntent {
    data object Start : ReflexTapIntent
    data object Tap : ReflexTapIntent
    data object Pause : ReflexTapIntent
    data object Resume : ReflexTapIntent
    data object PlayAgain : ReflexTapIntent
}

sealed interface ReflexTapEffect : UiEffect

/**
 * ViewModel MVI de Reflejos. Idéntico patrón que [com.example.kortexgames.game.memory.SequenceMemoryViewModel]:
 * posee el motor, proyecta su estado y persiste el resultado + percentil al terminar.
 */
class ReflexTapViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<ReflexTapIntent, ReflexTapUiState, ReflexTapEffect>(ReflexTapUiState()) {

    private val engine = ReflexTapEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [ReflexTapIntent.Start]).
    }

    override fun onIntent(intent: ReflexTapIntent) {
        when (intent) {
            ReflexTapIntent.Start,
            ReflexTapIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
            ReflexTapIntent.Tap -> engine.onTap()
            ReflexTapIntent.Pause -> engine.pause()
            ReflexTapIntent.Resume -> engine.resume()
        }
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val percentile = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            setState { copy(gameOver = GameOverInfo(result, percentile)) }
        }
    }
}
