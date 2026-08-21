package com.kortexgames.app.game.energyflow

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Estado de UI de la pantalla de "Flujo de Energía".
 *
 * @property phase selección de nivel o partida en curso.
 * @property maxUnlocked nivel máximo ya superado (récord); define lo desbloqueado.
 * @property currentLevel nivel que se está jugando (para "Siguiente nivel").
 * @property levelTimes mejor tiempo por nivel (nivel → ms); lo muestra el selector.
 * @property rankingPreview comparativa mundial del nivel resaltado en el selector,
 *   ANTES de jugarlo (ver [EnergyFlowIntent.PreviewLevel]); null mientras carga, si
 *   falló, o si el jugador es invitado/offline (ver `ProgressRepository.previewRanking`).
 * @property rankingPreviewLoading true mientras se resuelve el pedido de
 *   [rankingPreview] en curso.
 */
data class EnergyFlowUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: EnergyFlowState = EnergyFlowState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val levelTimes: Map<Int, Long> = emptyMap(),
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = false,
) : UiState

/** Intents (único punto de entrada de la UI, patrón MVI). */
sealed interface EnergyFlowIntent : UiIntent {
    /** El jugador tocó la pieza [index] para girarla 90° en horario. */
    data class RotateTile(val index: Int) : EnergyFlowIntent
    data object Restart : EnergyFlowIntent

    /** Pausa la partida (menú de pausa): congela el cronómetro de la partida. */
    data object Pause : EnergyFlowIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : EnergyFlowIntent

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : EnergyFlowIntent

    /** Desde el game-over: rejugar el mismo nivel. */
    data object PlayAgain : EnergyFlowIntent

    /** Desde el game-over: avanzar al siguiente nivel. */
    data object NextLevel : EnergyFlowIntent

    /** Volver al selector de niveles (desde el game-over). */
    data object ChooseLevel : EnergyFlowIntent

    /**
     * El jugador cambió el nivel resaltado en el carril de la antesala (o esta se
     * acaba de abrir): pide la comparativa mundial de ESE nivel para
     * [EnergyFlowUiState.rankingPreview], sin haberlo jugado todavía.
     */
    data class PreviewLevel(val level: Int) : EnergyFlowIntent
}

sealed interface EnergyFlowEffect : UiEffect

/**
 * ViewModel MVI de "Flujo de Energía". Juego **LEVELED**: arranca en el selector de
 * niveles ([LeveledGamePhase.LEVEL_SELECT]); al elegir un nivel desbloqueado el
 * motor genera su rejilla paramétrica y se juega. Al cerrar el circuito, persiste
 * el resultado (local-first) y el récord de nivel; el jugador puede repetir, avanzar
 * al siguiente o volver al selector. El nivel máx desbloqueado se observa desde
 * [PlayerProgressRepository].
 *
 * El ranking mundial se separa por nivel (ver `GameRankingScopes`), así que la
 * antesala puede mostrar "cómo le va" al jugador en el nivel resaltado del carril
 * ANTES de jugarlo (ver [EnergyFlowUiState.rankingPreview] y
 * [EnergyFlowIntent.PreviewLevel]; mismo mecanismo que Water Sort).
 */
class EnergyFlowViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<EnergyFlowIntent, EnergyFlowUiState, EnergyFlowEffect>(EnergyFlowUiState()) {

    private val engine = EnergyFlowEngine(viewModelScope, audio)

    /** Pedido en vuelo de [refreshRankingPreview]; se cancela al lanzar uno nuevo para
     *  que un cambio rápido de nivel en el carril no deje que una respuesta vieja pise
     *  a la actual (condición de carrera de red). */
    private var rankingPreviewJob: Job? = null

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        playerProgress.observe(GameIds.ENERGY_FLOW)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        playerProgress.observeLevelTimes(GameIds.ENERGY_FLOW)
            .onEach { times -> setState { copy(levelTimes = times) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: EnergyFlowIntent) {
        when (intent) {
            is EnergyFlowIntent.RotateTile -> engine.onTileRotate(intent.index)
            EnergyFlowIntent.Restart -> engine.restart()
            EnergyFlowIntent.Pause -> engine.pause()
            EnergyFlowIntent.Resume -> engine.resume()
            is EnergyFlowIntent.PlayLevel -> playLevel(intent.level)
            EnergyFlowIntent.PlayAgain -> playLevel(currentState.currentLevel)
            EnergyFlowIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            EnergyFlowIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
            is EnergyFlowIntent.PreviewLevel -> refreshRankingPreview(intent.level)
        }
    }

    /** Empieza (o reempieza) un nivel concreto: limpia el game-over y arranca el motor. */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        engine.startAtLevel(level)
    }

    /**
     * Pide la comparativa mundial del nivel [level] (1-based) para la antesala —
     * mismo panel que el diálogo de fin de nivel
     * ([com.kortexgames.app.ui.components.WorldRankingPreviewPanel]), pero sin haber
     * jugado esta partida (ver [ProgressRepository.previewRanking]). Cancela
     * cualquier pedido anterior en vuelo (ver [rankingPreviewJob]).
     */
    private fun refreshRankingPreview(level: Int) {
        rankingPreviewJob?.cancel()
        setState { copy(rankingPreview = null, rankingPreviewLoading = true) }
        rankingPreviewJob = viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.ENERGY_FLOW, level)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }
    }

    /**
     * `saveResult` emite en 1 o 2 pasos: local primero (el cartel no espera a
     * Supabase) y, con sesión, el percentil real después (ver KDoc de
     * `ProgressRepository.saveResult`).
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
