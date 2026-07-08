package com.example.kortexgames.game.crucigrama

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
 * Estado de UI del Crucigrama Neón.
 *
 * @property revealedHint pista visible solo tras consumir anuncio de ayuda.
 */
data class CrucigramaNeonUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: CrucigramaNeonState = CrucigramaNeonState(),
    val status: GameStatus = GameStatus.IDLE,
    val revealedHint: String? = null,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents del Crucigrama Neón. */
sealed interface CrucigramaNeonIntent : UiIntent {
    data object Start : CrucigramaNeonIntent
    data class TapLetter(val letter: Char) : CrucigramaNeonIntent
    data object Backspace : CrucigramaNeonIntent
    data object ClearWord : CrucigramaNeonIntent
    data object Pause : CrucigramaNeonIntent
    data object Resume : CrucigramaNeonIntent
    data object PlayAgain : CrucigramaNeonIntent
    data object NextLevel : CrucigramaNeonIntent
    data object ChooseLevel : CrucigramaNeonIntent
    data class PlayLevel(val level: Int) : CrucigramaNeonIntent

    /** Se dispara cuando el usuario terminó de ver el anuncio de pista. */
    data object HintAdWatched : CrucigramaNeonIntent
}

sealed interface CrucigramaNeonEffect : UiEffect

/**
 * ViewModel MVI del Crucigrama Neón.
 *
 * El juego se juega escribiendo palabras y el motor las ubica automáticamente en la
 * rejilla cuando son correctas. Las pistas se revelan solo tras anuncio.
 */
class CrucigramaNeonViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<CrucigramaNeonIntent, CrucigramaNeonUiState, CrucigramaNeonEffect>(CrucigramaNeonUiState()) {

    private val engine = CrucigramaNeonEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        playerProgress.observe(GameIds.CRUCIGRAMA_NEON)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: CrucigramaNeonIntent) {
        when (intent) {
            CrucigramaNeonIntent.Start,
            CrucigramaNeonIntent.PlayAgain -> playLevel(currentState.currentLevel)
            is CrucigramaNeonIntent.PlayLevel -> playLevel(intent.level)
            is CrucigramaNeonIntent.TapLetter -> engine.tapLetter(intent.letter)
            CrucigramaNeonIntent.Backspace -> engine.backspace()
            CrucigramaNeonIntent.ClearWord -> engine.clearBuffer()
            CrucigramaNeonIntent.Pause -> engine.pause()
            CrucigramaNeonIntent.Resume -> engine.resume()
            CrucigramaNeonIntent.NextLevel -> playLevel(currentState.currentLevel + 1)
            CrucigramaNeonIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null, revealedHint = null)
            }
            CrucigramaNeonIntent.HintAdWatched -> {
                setState { copy(revealedHint = engine.nextHint()) }
            }
        }
    }

    private fun playLevel(level: Int) {
        setState {
            copy(
                phase = LeveledGamePhase.PLAYING,
                currentLevel = level,
                gameOver = null,
                revealedHint = null,
            )
        }
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
