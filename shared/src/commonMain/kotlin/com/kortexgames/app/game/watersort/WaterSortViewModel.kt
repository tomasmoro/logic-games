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
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estado de UI de la pantalla de "Ordena las Pociones".
 *
 * @property phase selección de nivel o partida en curso.
 * @property maxUnlocked nivel máximo ya superado (récord); define lo desbloqueado.
 * @property currentLevel nivel que se está jugando (para "Siguiente nivel").
 * @property savedLevel nivel de la partida guardada al salir, o null si no hay
 *   ninguna pendiente. Lo pinta la antesala como "Continuar" (ver
 *   [com.kortexgames.app.ui.components.ResumeState]).
 * @property rankingPreview comparativa mundial del nivel elegido en el selector,
 *   ANTES de jugarlo (ver [WaterSortIntent.PreviewLevel]); null mientras carga, si
 *   falló, o si el jugador es invitado/offline (ver `ProgressRepository.previewRanking`).
 * @property rankingPreviewLoading true mientras se resuelve el pedido de
 *   [rankingPreview] en curso.
 */
data class WaterSortUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: WaterSortState = WaterSortState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val savedLevel: Int? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = false,
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

    /** El jugador pulsó "Reiniciar": rehace el nivel al momento, sin anuncio. */
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

    /** Desde la antesala: retomar la partida guardada al salir (ver [WaterSortUiState.savedLevel]). */
    data object ResumeSaved : WaterSortIntent

    /**
     * El jugador cambió el nivel resaltado en el carril de la antesala (o esta se
     * acaba de abrir): pide la comparativa mundial de ESE nivel para
     * [WaterSortUiState.rankingPreview], sin haberlo jugado todavía.
     */
    data class PreviewLevel(val level: Int) : WaterSortIntent
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
 *
 * También activa el **guardado de partida al salir** (back / "SALIR" del menú de
 * pausa, ver [requestExit]): al volver a jugar el mismo nivel, reanuda desde donde se
 * dejó en vez de regenerar el tablero (mismo mecanismo que Crucigrama Neón y Neon
 * Hyper-Cube; ver [WaterSortSavedState]).
 *
 * El ranking mundial se separa por nivel (ver `GameRankingScopes`), así que la
 * antesala puede mostrar "cómo le va" al jugador en el nivel resaltado del carril
 * ANTES de jugarlo (ver [WaterSortUiState.rankingPreview] y
 * [WaterSortIntent.PreviewLevel]; mismo mecanismo que Neon Grid 2048).
 */
class WaterSortViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<WaterSortIntent, WaterSortUiState, WaterSortEffect>(WaterSortUiState()) {

    private val engine = WaterSortEngine(viewModelScope, audio)

    /** Pedido en vuelo de [refreshRankingPreview]; se cancela al lanzar uno nuevo para
     *  que un cambio rápido de nivel en el carril no deje que una respuesta vieja pise
     *  a la actual (condición de carrera de red). */
    private var rankingPreviewJob: Job? = null

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // Nivel máx desbloqueado (récord). No arrancamos el motor: se empieza en el
        // selector y el jugador elige el nivel.
        playerProgress.observe(GameIds.WATER_SORT)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        // Partida guardada al salir: la antesala la ofrece como "Continuar". Se
        // observa (en vez de leerla una vez) para que el botón desaparezca solo al
        // reanudarla o al completar el nivel, que es cuando se borra la fila.
        savedGameState.observe(GameIds.WATER_SORT)
            .onEach { json -> setState { copy(savedLevel = json?.let(::decodeSaved)?.game?.round) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: WaterSortIntent) {
        when (intent) {
            is WaterSortIntent.TapTube -> engine.onTubeTap(intent.index)
            WaterSortIntent.Undo -> requestUndo()
            WaterSortIntent.UndoRewarded -> engine.undo()
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
            WaterSortIntent.ResumeSaved -> resumeSaved()
            is WaterSortIntent.PreviewLevel -> refreshRankingPreview(intent.level)
        }
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
            val ranking = progress.previewRanking(GameIds.WATER_SORT, level)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
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

    /**
     * Empieza (o reempieza) [level] **desde cero**: limpia el game-over y arranca el
     * motor. Descarta cualquier partida guardada: llegar aquí siempre es una decisión
     * explícita del jugador (elegir nivel, "Reiniciar" desde el selector, rejugar o
     * pasar de nivel tras el resultado), y dejar el guardado vivo haría que
     * reapareciese como "Continuar" una partida que ya abandonó. Para retomarla está
     * [resumeSaved].
     */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        viewModelScope.launch { savedGameState.clear(GameIds.WATER_SORT) }
        engine.startAtLevel(level)
    }

    /**
     * Retoma la partida guardada al salir, en su nivel original. El guardado se
     * consume (se borra) al reanudar: a partir de ahí la partida vuelve a estar viva
     * y el próximo guardado será el de esta sesión. No-op si no hay ninguna.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.WATER_SORT)?.let(::decodeSaved) ?: return@launch
            savedGameState.clear(GameIds.WATER_SORT)
            setState {
                copy(phase = LeveledGamePhase.PLAYING, currentLevel = saved.game.round, gameOver = null)
            }
            engine.resumeFrom(saved)
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.kortexgames.app.ui.components.GameExitGuard] o "SALIR" del menú de
     * pausa): si hay partida en curso la guarda antes de navegar atrás. Fuera de
     * [LeveledGamePhase.PLAYING] (antesala, fin de nivel) no hay progreso que perder,
     * así que [onExit] se llama directo.
     */
    fun requestExit(onExit: () -> Unit) {
        if (currentState.phase != LeveledGamePhase.PLAYING) {
            onExit()
            return
        }
        viewModelScope.launch {
            savedGameState.save(GameIds.WATER_SORT, Json.encodeToString(engine.captureSavedState()))
            onExit()
        }
    }

    /**
     * Decodifica un guardado. Tolerante a fallos a propósito: si el JSON quedó de una
     * versión anterior del estado, se trata como "no hay partida" en vez de romper la
     * pantalla — el jugador solo pierde ese guardado.
     */
    private fun decodeSaved(json: String): WaterSortSavedState? =
        runCatching { Json.decodeFromString<WaterSortSavedState>(json) }.getOrNull()

    /**
     * `saveResult` emite en 1 o 2 pasos: local primero (el cartel no espera a
     * Supabase) y, con sesión, el percentil real después (ver KDoc de
     * `ProgressRepository.saveResult`).
     *
     * Antes de persistir se corrige `difficultyLevel` al nivel jugado: el ranking
     * mundial de Water Sort se separa por nivel (ver `GameRankingScopes`, mismo
     * criterio que Hyper-Cube), así que solo compiten entre sí partidas del MISMO
     * nivel. Se corrige aquí porque `BaseGameEngine.difficulty` queda fijo desde la
     * construcción del motor (siempre 1) y el nivel real se elige partida a partida
     * en el selector.
     */
    private fun onFinished(result: GameResult) {
        val corrected = result.copy(difficultyLevel = currentState.currentLevel)
        viewModelScope.launch {
            // Nivel completado: un guardado de esta partida (si quedó alguno) es
            // "fantasma" a partir de aquí, ya se registró el resultado final.
            savedGameState.clear(GameIds.WATER_SORT)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            progress.saveResult(corrected).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(corrected)) }
            }
        }
    }
}
