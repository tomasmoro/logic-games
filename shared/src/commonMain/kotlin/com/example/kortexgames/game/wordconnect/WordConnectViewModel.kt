package com.example.kortexgames.game.wordconnect

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.ads.AdManager
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

/** Estado de UI de Palabras Conectadas. */
data class WordConnectUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: WordConnectState = WordConnectState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents de Palabras Conectadas. */
sealed interface WordConnectIntent : UiIntent {
    data object Start : WordConnectIntent
    data object BeginTrace : WordConnectIntent
    data class ExtendTrace(val letterIndex: Int) : WordConnectIntent
    data object EndTrace : WordConnectIntent
    data object Pause : WordConnectIntent
    data object Resume : WordConnectIntent
    data object PlayAgain : WordConnectIntent
    data object NextLevel : WordConnectIntent
    data object ChooseLevel : WordConnectIntent
    data class PlayLevel(val level: Int) : WordConnectIntent
}

sealed interface WordConnectEffect : UiEffect

/**
 * ViewModel MVI de Palabras Conectadas.
 *
 * Delega la lógica del trazo en [WordConnectEngine] y solo orquesta las fases
 * (selección de nivel ↔ partida), el guardado local-first del resultado y el récord
 * de nivel máximo desbloqueado. Copia fiel del patrón del resto de juegos LEVELED.
 */
class WordConnectViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<WordConnectIntent, WordConnectUiState, WordConnectEffect>(WordConnectUiState()) {

    private val engine = WordConnectEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        playerProgress.observe(GameIds.WORD_CONNECT)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: WordConnectIntent) {
        when (intent) {
            WordConnectIntent.Start,
            WordConnectIntent.PlayAgain -> playLevel(currentState.currentLevel)
            is WordConnectIntent.PlayLevel -> playLevel(intent.level)
            WordConnectIntent.BeginTrace -> engine.beginTrace()
            is WordConnectIntent.ExtendTrace -> engine.extendTrace(intent.letterIndex)
            WordConnectIntent.EndTrace -> engine.endTrace()
            WordConnectIntent.Pause -> engine.pause()
            WordConnectIntent.Resume -> engine.resume()
            WordConnectIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            WordConnectIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    private fun playLevel(level: Int) {
        setState {
            copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null)
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
