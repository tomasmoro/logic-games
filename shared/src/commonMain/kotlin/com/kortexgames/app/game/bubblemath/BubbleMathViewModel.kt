package com.kortexgames.app.game.bubblemath

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
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
    /** Arranca la partida desde la antesala (intro), "Comenzar". */
    data object Start : BubbleMathIntent

    /** El jugador tocó una burbuja concreta. */
    data class TapBubble(val id: Int) : BubbleMathIntent
    data object Pause : BubbleMathIntent
    data object Resume : BubbleMathIntent
    data object PlayAgain : BubbleMathIntent

    /** El anuncio recompensado concedió la vida extra: continúa la partida. */
    data object Revive : BubbleMathIntent

    /** Se rechazó la oferta de revivir (o el anuncio no se completó): fin de partida. */
    data object DeclineRevive : BubbleMathIntent
}

sealed interface BubbleMathEffect : UiEffect

/**
 * ViewModel MVI de Burbujas de Cálculo. Mismo patrón que los demás juegos
 * ([com.kortexgames.app.game.memory.SequenceMemoryViewModel]): posee el motor,
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
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [BubbleMathIntent.Start]).
    }

    override fun onIntent(intent: BubbleMathIntent) {
        when (intent) {
            is BubbleMathIntent.TapBubble -> engine.onBubbleTap(intent.id)
            BubbleMathIntent.Pause -> engine.pause()
            BubbleMathIntent.Resume -> engine.resume()
            BubbleMathIntent.Revive -> engine.grantRevive()
            BubbleMathIntent.DeclineRevive -> engine.declineRevive()
            BubbleMathIntent.Start,
            BubbleMathIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}
