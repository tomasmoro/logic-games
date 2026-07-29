package com.kortexgames.app.game.memory

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Estado de UI de la pantalla de Memoria de Secuencias. */
data class SequenceMemoryUiState(
    val game: SequenceMemoryState = SequenceMemoryState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

sealed interface SequenceMemoryIntent : UiIntent {
    data object Start : SequenceMemoryIntent
    data class TapTile(val index: Int) : SequenceMemoryIntent
    data object Pause : SequenceMemoryIntent
    data object Resume : SequenceMemoryIntent
    data object PlayAgain : SequenceMemoryIntent
}

sealed interface SequenceMemoryEffect : UiEffect

/**
 * ViewModel MVI que gobierna una partida de Memoria de Secuencias. Posee el
 * [SequenceMemoryEngine] (creado con `viewModelScope`), proyecta su estado a la
 * UI y, al terminar, **guarda el resultado (local-first) y consulta el percentil**.
 */
class SequenceMemoryViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<SequenceMemoryIntent, SequenceMemoryUiState, SequenceMemoryEffect>(
    SequenceMemoryUiState(),
) {
    private val engine = SequenceMemoryEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        // outcome pasa de null a un GameResult cuando la partida termina.
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [SequenceMemoryIntent.Start]).
    }

    override fun onIntent(intent: SequenceMemoryIntent) {
        when (intent) {
            SequenceMemoryIntent.Start,
            SequenceMemoryIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
            is SequenceMemoryIntent.TapTile -> engine.onTileTapped(intent.index)
            SequenceMemoryIntent.Pause -> engine.pause()
            SequenceMemoryIntent.Resume -> engine.resume()
        }
    }

    /** Guarda el resultado y expone el percentil en el estado. */
    private fun onFinished(result: com.kortexgames.app.domain.model.GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }
}
