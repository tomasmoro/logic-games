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
 * Estado de UI de la pantalla de "Ordena las Pociones".
 *
 * @property phase selección de nivel o partida en curso.
 * @property maxUnlocked nivel máximo ya superado (récord); define lo desbloqueado.
 * @property currentLevel nivel que se está jugando (para "Siguiente nivel").
 */
data class WaterSortUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
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
    data object Pause : WaterSortIntent
    data object Resume : WaterSortIntent

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : WaterSortIntent

    /** Desde el game-over: rejugar el mismo nivel. */
    data object PlayAgain : WaterSortIntent

    /** Desde el game-over: avanzar al siguiente nivel. */
    data object NextLevel : WaterSortIntent

    /** Volver al selector de niveles (desde el game-over). */
    data object ChooseLevel : WaterSortIntent
}

sealed interface WaterSortEffect : UiEffect

/**
 * ViewModel MVI de "Ordena las Pociones". Juego **LEVELED**: arranca en el selector
 * de niveles ([LeveledGamePhase.LEVEL_SELECT]); al elegir un nivel desbloqueado el
 * motor lo genera de forma paramétrica y se juega. Al resolverlo, persiste el
 * resultado (local-first) y el récord de nivel; el jugador puede repetir, avanzar
 * al siguiente o volver al selector.
 *
 * El nivel máximo desbloqueado ([WaterSortUiState.maxUnlocked]) se observa desde
 * [PlayerProgressRepository] (fuente de verdad local-first), así que sube en cuanto
 * se completa un nivel nuevo.
 */
class WaterSortViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<WaterSortIntent, WaterSortUiState, WaterSortEffect>(WaterSortUiState()) {

    private val engine = WaterSortEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // Nivel máx desbloqueado (récord). No arrancamos el motor: se empieza en el
        // selector y el jugador elige el nivel.
        playerProgress.observe(GameIds.WATER_SORT)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: WaterSortIntent) {
        when (intent) {
            is WaterSortIntent.TapTube -> engine.onTubeTap(intent.index)
            WaterSortIntent.Undo -> engine.undo()
            WaterSortIntent.Restart -> engine.restart()
            WaterSortIntent.Pause -> engine.pause()
            WaterSortIntent.Resume -> engine.resume()
            is WaterSortIntent.PlayLevel -> playLevel(intent.level)
            WaterSortIntent.PlayAgain -> playLevel(currentState.currentLevel)
            WaterSortIntent.NextLevel -> playLevel(currentState.currentLevel + 1)
            WaterSortIntent.ChooseLevel -> setState {
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
            val percentile = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = GameOverInfo(result, percentile)) }
        }
    }
}
