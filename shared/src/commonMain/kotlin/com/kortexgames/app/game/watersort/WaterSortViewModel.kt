package com.kortexgames.app.game.watersort

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
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

    /**
     * El jugador pulsó "Deshacer". El primero del intento ([FREE_UNDOS]) se aplica al
     * momento; los siguientes disparan [WaterSortEffect.ShowUndoAd] y solo se aplican
     * cuando la UI devuelve [UndoRewarded]. La UI no decide el precio: manda un único
     * intent y el ViewModel resuelve si toca cobrar.
     */
    data object Undo : WaterSortIntent

    /** La UI confirma que el anuncio del deshacer terminó → se deshace el vertido. */
    data object UndoRewarded : WaterSortIntent

    /**
     * El jugador pulsó "Tubo extra": pide ver un anuncio recompensado. NO añade el
     * tubo aún; solo dispara [WaterSortEffect.ShowRewardedAd]. El tubo se concede al
     * completarse el anuncio, cuando la UI envía [ExtraTubeRewarded].
     */
    data object WatchAdForExtraTube : WaterSortIntent

    /** La UI confirma que el anuncio recompensado terminó → se concede el tubo extra. */
    data object ExtraTubeRewarded : WaterSortIntent

    /**
     * El jugador pulsó "Reiniciar": pide ver un anuncio antes de rehacer el nivel.
     * Dispara [WaterSortEffect.ShowRestartAd]; el reinicio real ([Restart]) lo envía
     * la UI al terminar el anuncio.
     */
    data object WatchAdForRestart : WaterSortIntent

    /** Reinicia de verdad el nivel. La UI lo envía tras completarse el anuncio. */
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

sealed interface WaterSortEffect : UiEffect {
    /**
     * Pide a la UI mostrar un **anuncio recompensado**. Al terminar, la UI debe
     * devolver [WaterSortIntent.ExtraTubeRewarded] para que se conceda el tubo extra.
     * Es un evento one-shot (no estado): se modela como efecto para no reemitirse en
     * recomposición (CLAUDE.md §4, patrón MVI).
     */
    data object ShowRewardedAd : WaterSortEffect

    /**
     * Pide a la UI mostrar un anuncio **antes de reiniciar** el nivel. Al terminar, la
     * UI devuelve [WaterSortIntent.Restart] para rehacer la partida. Mismo mecanismo
     * one-shot que [ShowRewardedAd].
     */
    data object ShowRestartAd : WaterSortEffect

    /**
     * Pide a la UI mostrar un anuncio **antes de deshacer** (a partir del segundo
     * deshacer del intento; ver [FREE_UNDOS]). Al terminar, la UI devuelve
     * [WaterSortIntent.UndoRewarded]. Mismo mecanismo one-shot que [ShowRewardedAd].
     */
    data object ShowUndoAd : WaterSortEffect
}

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
    private val adManager: AdManager,
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
            WaterSortIntent.Undo -> requestUndo()
            WaterSortIntent.UndoRewarded -> engine.undo()
            WaterSortIntent.WatchAdForRestart -> sendEffect(WaterSortEffect.ShowRestartAd)
            WaterSortIntent.Restart -> engine.restart()
            WaterSortIntent.Pause -> engine.pause()
            WaterSortIntent.Resume -> engine.resume()
            WaterSortIntent.WatchAdForExtraTube -> requestExtraTubeAd()
            WaterSortIntent.ExtraTubeRewarded -> engine.addExtraTube()
            is WaterSortIntent.PlayLevel -> playLevel(intent.level)
            WaterSortIntent.PlayAgain -> playLevel(currentState.currentLevel)
            WaterSortIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            WaterSortIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Resuelve el precio del deshacer: el primero del intento es gratis y se aplica al
     * momento; a partir de ahí se pide el anuncio recompensado y el vertido se deshace
     * al volver con [WaterSortIntent.UndoRewarded].
     *
     * Se comprueba `canUndo` antes de pedir el anuncio (defensa en profundidad, aparte
     * de que la UI deshabilita el botón) para no gastarle un anuncio al jugador y luego
     * no deshacer nada.
     */
    private fun requestUndo() {
        val game = currentState.game
        if (!game.canUndo) return
        if (game.nextUndoIsFree) engine.undo() else sendEffect(WaterSortEffect.ShowUndoAd)
    }

    /**
     * Solicita el anuncio recompensado para el tubo extra. Comprueba el cupo aquí
     * (defensa en profundidad, aparte de que la UI oculta el botón) para no gastar un
     * anuncio si ya no queda margen o la partida terminó.
     */
    private fun requestExtraTubeAd() {
        if (!currentState.game.canAddTube) return
        sendEffect(WaterSortEffect.ShowRewardedAd)
    }

    /** Empieza (o reempieza) un nivel concreto: limpia el game-over y arranca el motor. */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        engine.startAtLevel(level)
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}
