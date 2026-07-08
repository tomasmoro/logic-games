package com.example.kortexgames.game.polarity

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

/** Estado de UI de la pantalla Atracción Geométrica. */
data class PolarityCollisionUiState(
    val game: PolarityCollisionState = PolarityCollisionState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents de Atracción Geométrica (entrada única para la UI). */
sealed interface PolarityCollisionIntent : UiIntent {
    /** Actualiza el viewport jugable en píxeles para el motor de física. */
    data class UpdateViewport(val widthPx: Float, val heightPx: Float) : PolarityCollisionIntent

    /** Rota el hexágono con un delta angular (en radianes) derivado del drag. */
    data class RotateBy(val deltaRad: Float) : PolarityCollisionIntent

    /** Tick de frame recibido desde `withFrameNanos`. */
    data class Frame(val frameNanos: Long) : PolarityCollisionIntent

    /** Arranca la partida desde la antesala (intro), "Comenzar". */
    data object Start : PolarityCollisionIntent

    data object PlayAgain : PolarityCollisionIntent
}

sealed interface PolarityCollisionEffect : UiEffect

/**
 * ViewModel MVI de Atracción Geométrica.
 *
 * Mantiene la pantalla agnóstica de persistencia: cuando el engine finaliza, guarda
 * el resultado con estrategia local-first y solo entonces abre el overlay final.
 */
class PolarityCollisionViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<PolarityCollisionIntent, PolarityCollisionUiState, PolarityCollisionEffect>(
    PolarityCollisionUiState(),
) {

    private val engine = PolarityCollisionEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [PolarityCollisionIntent.Start]).
    }

    override fun onIntent(intent: PolarityCollisionIntent) {
        when (intent) {
            is PolarityCollisionIntent.UpdateViewport -> engine.updateViewport(intent.widthPx, intent.heightPx)
            is PolarityCollisionIntent.RotateBy -> engine.rotateBy(intent.deltaRad)
            is PolarityCollisionIntent.Frame -> engine.onFrame(intent.frameNanos)
            PolarityCollisionIntent.Start,
            PolarityCollisionIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
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

