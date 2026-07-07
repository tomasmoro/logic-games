package com.example.kortexgames.game.bubblemath

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

/** Estado de UI de la pantalla de Burbujas de Cálculo. */
data class BubbleMathUiState(
    val game: BubbleMathState = BubbleMathState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

sealed interface BubbleMathIntent : UiIntent {
    /** El jugador tocó una burbuja concreta. */
    data class TapBubble(val id: Int) : BubbleMathIntent
    data object Pause : BubbleMathIntent
    data object Resume : BubbleMathIntent
    data object PlayAgain : BubbleMathIntent
}

sealed interface BubbleMathEffect : UiEffect

/**
 * ViewModel MVI de Burbujas de Cálculo. Mismo patrón que los demás juegos
 * ([com.example.kortexgames.game.reflex.ReflexTapViewModel]): posee el motor,
 * proyecta su `state`/`status`/`outcome` y, al terminar, persiste el resultado
 * (local-first) obteniendo el percentil frente al resto de jugadores.
 */
class BubbleMathViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<BubbleMathIntent, BubbleMathUiState, BubbleMathEffect>(BubbleMathUiState()) {

    private val engine = BubbleMathEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.start()
    }

    override fun onIntent(intent: BubbleMathIntent) {
        when (intent) {
            is BubbleMathIntent.TapBubble -> engine.onBubbleTap(intent.id)
            BubbleMathIntent.Pause -> engine.pause()
            BubbleMathIntent.Resume -> engine.resume()
            BubbleMathIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
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
