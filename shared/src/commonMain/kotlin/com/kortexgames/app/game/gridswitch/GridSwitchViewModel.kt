package com.kortexgames.app.game.gridswitch

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Neon Grid Switch". Juego **LEVELED con selector**: arranca
 * en el selector de etapas y, al elegir una, se genera ([GridSwitchGenerator]) y
 * se juega. Cada etapa resuelta persiste su resultado (récord = etapa alcanzada,
 * local-first) antes de que [GridSwitchIntent.NextStage] arranque la siguiente —
 * mismo molde que `NeonLineViewModel`/`HyperCubeViewModel` (LEVELED "que nunca
 * fallan").
 *
 * Reparto de responsabilidades:
 *  - Reglas (conmutación ortogonal, conteo de movimientos, victoria) → [GridSwitchEngine].
 *  - Feedback sensorial → Effects one-shot, con la tabla evento→(sonido, háptica)
 *    centralizada en [onEngineEvent] (mismo esquema que el resto del catálogo).
 */
class GridSwitchViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<GridSwitchIntent, GridSwitchUiState, GridSwitchEffect>(GridSwitchUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa: todo el
    // feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = GridSwitchEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s ->
            setState {
                copy(gridSize = s.board.size, board = s.board, moveCount = s.moveCount, isCompleted = s.isSolved)
            }
        }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Etapa máxima desbloqueada (récord), reactiva y local-first. No se arranca
        // el motor: se empieza en el selector y el jugador elige.
        playerProgress.observe(GameIds.NEON_GRID_SWITCH)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: GridSwitchIntent) {
        when (intent) {
            is GridSwitchIntent.PlayStage -> playStage(intent.stage)
            is GridSwitchIntent.PreviewStage -> refreshRankingPreview(intent.stage)
            is GridSwitchIntent.ToggleCell -> engine.onCellToggled(intent.cell)
            GridSwitchIntent.RestartStage -> engine.restartStage()
            GridSwitchIntent.NextStage -> {
                // Breakpoint de avance de etapa (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playStage(currentState.stageLevel + 1)
            }
            GridSwitchIntent.Pause -> engine.pause()
            GridSwitchIntent.Resume -> engine.resume()
            // Rejugar la MISMA etapa (mismo criterio que Línea Neón/Crucigrama),
            // no volver al principio de la progresión.
            GridSwitchIntent.PlayAgain -> playStage(currentState.stageLevel)
            GridSwitchIntent.ChooseStage -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     *
     * [GridSwitchEvent.StageCleared] no dispara nada aquí a propósito: su
     * sonido/háptica de victoria viajan en [onFinished], junto con el guardado
     * del resultado, para que acompañen la aparición del cartel en vez de
     * adelantarse a él (mismo criterio que Línea Neón).
     */
    private fun onEngineEvent(event: GridSwitchEvent) {
        when (event) {
            GridSwitchEvent.CellToggled -> {
                sendEffect(GridSwitchEffect.PlaySound(SoundEffect.TAP))
                sendEffect(GridSwitchEffect.Vibrate(HapticFeedback.LIGHT))
            }
            GridSwitchEvent.StageCleared -> Unit
        }
    }

    /** Pedido en vuelo de [refreshRankingPreview]; se cancela al lanzar uno nuevo para que una
     *  respuesta vieja no pise a la actual al cambiar rápido de etapa en el carril. */
    private var rankingPreviewJob: Job? = null

    /**
     * Pide la comparativa mundial de la etapa [stage] para la antesala (ver
     * [ProgressRepository.previewRanking]): el ranking se separa por etapa (ver `GameRankingScopes`).
     */
    private fun refreshRankingPreview(stage: Int) {
        rankingPreviewJob?.cancel()
        setState { copy(rankingPreview = null, rankingPreviewLoading = true) }
        rankingPreviewJob = viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.NEON_GRID_SWITCH, stage)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }
    }

    /** Empieza (o reempieza) la etapa [stage]: entra a juego, limpia el game-over y arranca el motor. */
    private fun playStage(stage: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, stageLevel = stage, gameOver = null) }
        engine.startAtStage(stage)
    }

    /**
     * El sonido/háptica de victoria se disparan de inmediato (no dependen de red).
     * `saveResult` emite el `gameOver` en 1 o 2 pasos: primero el resultado LOCAL
     * (récord ya conocido) para que el cartel aparezca sin esperar a Supabase, y
     * luego —si hay sesión— el percentil/ranking cuando la red responda (ver KDoc
     * de `ProgressRepository.saveResult`).
     */
    private fun onFinished(original: GameResult) {
        // Se corrige `difficultyLevel` a la etapa jugada: el ranking mundial se separa por etapa
        // (ver `GameRankingScopes`), pero `BaseGameEngine.difficulty` queda fijo en 1 desde la
        // construcción del motor, así que sin esto todas las partidas caerían en la etapa 1.
        val result = original.copy(difficultyLevel = currentState.stageLevel)
        viewModelScope.launch {
            sendEffect(GridSwitchEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(GridSwitchEffect.Vibrate(HapticFeedback.SUCCESS))
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
