package com.kortexgames.app.game.crucigrama

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
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estado de UI del Crucigrama Neón.
 *
 * @property revealedHint pista visible solo tras consumir anuncio de ayuda.
 * @property savedLevel nivel de la partida guardada al salir, o null si no hay
 *   ninguna pendiente. Lo pinta la antesala como "Continuar" (ver
 *   [com.kortexgames.app.ui.components.ResumeState]).
 * @property extrasPromptDismissed el jugador ya vio el cartel de "seguir buscando
 *   extras" (tras completar la rejilla, ver [CrucigramaNeonState.gridComplete]) y
 *   eligió seguir jugando. Evita que el cartel reaparezca en cada recomposición
 *   mientras sigue buscando; se resetea al empezar/reanudar un nivel.
 */
data class CrucigramaNeonUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: CrucigramaNeonState = CrucigramaNeonState(),
    val status: GameStatus = GameStatus.IDLE,
    val revealedHint: String? = null,
    val gameOver: GameOverInfo? = null,
    val savedLevel: Int? = null,
    val extrasPromptDismissed: Boolean = false,
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

    /**
     * Respuesta al cartel de "rejilla completa, ¿seguir buscando extras?": el
     * jugador elige seguir jugando (descarta el cartel, la partida sigue viva).
     */
    data object KeepSearchingExtras : CrucigramaNeonIntent

    /**
     * Respuesta al mismo cartel: el jugador prefiere cerrar el nivel ya, sin
     * buscar las extras que falten. Cierra la partida como cualquier fin normal.
     */
    data object FinishLevel : CrucigramaNeonIntent

    /**
     * Atajo desde el **menú de pausa**, disponible mientras la rejilla está
     * completa y quedan extras sin descubrir (mismo momento que el cartel de
     * [KeepSearchingExtras]/[FinishLevel], pero el jugador pausó en vez de
     * responderle): cierra el nivel actual y avanza directo al siguiente, sin
     * pasar por el cartel de fin de partida.
     */
    data object SkipToNextLevel : CrucigramaNeonIntent

    /** Desde la antesala: retomar la partida guardada al salir (ver [CrucigramaNeonUiState.savedLevel]). */
    data object ResumeSaved : CrucigramaNeonIntent

    /** Se dispara cuando el usuario terminó de ver el anuncio de pista. */
    data object HintAdWatched : CrucigramaNeonIntent
}

sealed interface CrucigramaNeonEffect : UiEffect

/**
 * ViewModel MVI del Crucigrama Neón.
 *
 * El juego se juega escribiendo palabras y el motor las ubica automáticamente en la
 * rejilla cuando son correctas. Las pistas se revelan solo tras anuncio.
 *
 * Es el primer juego que activa el **guardado de partida al salir** (back / "SALIR"
 * del menú de pausa, ver [requestExit]): al volver a jugar el mismo nivel, reanuda
 * desde donde se dejó en vez de regenerar el puzzle (ver [playLevel] y
 * [CrucigramaNeonEngine.resumeFrom]).
 */
class CrucigramaNeonViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<CrucigramaNeonIntent, CrucigramaNeonUiState, CrucigramaNeonEffect>(CrucigramaNeonUiState()) {

    private val engine = CrucigramaNeonEngine(viewModelScope, audio)

    /**
     * Marca que [SkipToNextLevel] dejó pendiente: [onFinished] la consume para
     * saltarse el cartel de resultado y encadenar directo el siguiente nivel. Vive
     * fuera del [StateFlow] porque es un paso interno de una sola corrutina, no
     * algo que la UI necesite observar (a diferencia de [CrucigramaNeonUiState.gameOver]).
     */
    private var pendingAutoAdvance = false

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        playerProgress.observe(GameIds.CRUCIGRAMA_NEON)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        // Partida guardada al salir: la antesala la ofrece como "Continuar". Se
        // observa (en vez de leerla una vez) para que el botón desaparezca solo al
        // reanudarla o al completar el nivel, que es cuando se borra la fila.
        savedGameState.observe(GameIds.CRUCIGRAMA_NEON)
            .onEach { json -> setState { copy(savedLevel = json?.let(::decodeSaved)?.level) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: CrucigramaNeonIntent) {
        when (intent) {
            CrucigramaNeonIntent.Start,
            CrucigramaNeonIntent.PlayAgain -> playLevel(currentState.currentLevel)
            is CrucigramaNeonIntent.PlayLevel -> playLevel(intent.level)
            CrucigramaNeonIntent.ResumeSaved -> resumeSaved()
            is CrucigramaNeonIntent.TapLetter -> engine.tapLetter(intent.letter)
            CrucigramaNeonIntent.Backspace -> engine.backspace()
            CrucigramaNeonIntent.ClearWord -> engine.clearBuffer()
            CrucigramaNeonIntent.Pause -> engine.pause()
            CrucigramaNeonIntent.Resume -> engine.resume()
            CrucigramaNeonIntent.NextLevel -> {
                // Breakpoint de "avanzar de nivel": único momento (aparte de salir del
                // juego, ver App.kt) donde se cobra un intersticial pendiente sin cortar
                // la partida. Solo aplica a juegos LEVELED como este; los ENDLESS no
                // avanzan de nivel. onAdBreakpoint es no-op si no hay anuncio pendiente.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            CrucigramaNeonIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null, revealedHint = null)
            }
            CrucigramaNeonIntent.HintAdWatched -> {
                setState { copy(revealedHint = engine.nextHint()) }
            }
            CrucigramaNeonIntent.KeepSearchingExtras -> setState { copy(extrasPromptDismissed = true) }
            CrucigramaNeonIntent.FinishLevel -> engine.finish()
            CrucigramaNeonIntent.SkipToNextLevel -> {
                // No encadena engine.finish() + playLevel() en el sitio: onFinished
                // guarda el resultado de forma asíncrona (suspend), y arrancar el
                // siguiente nivel antes de que termine reiniciaría el motor a mitad
                // de ese guardado. La bandera pospone el avance hasta que confirme.
                pendingAutoAdvance = true
                engine.finish()
            }
        }
    }

    /**
     * Arranca [level] **desde cero**. Descarta cualquier partida guardada: llegar
     * aquí siempre es una decisión explícita del jugador ("Empezar de nuevo" en la
     * antesala, rejugar o pasar de nivel tras el resultado), y dejar el guardado
     * vivo haría que reapareciese como "Continuar" una partida que ya abandonó.
     * Para retomarla está [resumeSaved].
     */
    private fun playLevel(level: Int) {
        setState {
            copy(
                phase = LeveledGamePhase.PLAYING,
                currentLevel = level,
                gameOver = null,
                revealedHint = null,
                extrasPromptDismissed = false,
            )
        }
        viewModelScope.launch { savedGameState.clear(GameIds.CRUCIGRAMA_NEON) }
        engine.startAtLevel(level)
    }

    /**
     * Retoma la partida guardada al salir, en su nivel original. El guardado se
     * consume (se borra) al reanudar: a partir de ahí la partida vuelve a estar
     * viva y el próximo guardado será el de esta sesión. No-op si no hay ninguna.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.CRUCIGRAMA_NEON)?.let(::decodeSaved) ?: return@launch
            savedGameState.clear(GameIds.CRUCIGRAMA_NEON)
            setState {
                copy(
                    phase = LeveledGamePhase.PLAYING,
                    currentLevel = saved.level,
                    gameOver = null,
                    revealedHint = null,
                    extrasPromptDismissed = false,
                )
            }
            engine.resumeFrom(saved)
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía [com.kortexgames.app.ui.components.GameExitGuard]
     * o "SALIR" del menú de pausa): si hay partida en curso la guarda antes de
     * navegar atrás. Fuera de [LeveledGamePhase.PLAYING] (antesala, fin de nivel) no
     * hay progreso que perder, así que [onExit] se llama directo.
     */
    fun requestExit(onExit: () -> Unit) {
        if (currentState.phase != LeveledGamePhase.PLAYING) {
            onExit()
            return
        }
        viewModelScope.launch {
            savedGameState.save(GameIds.CRUCIGRAMA_NEON, Json.encodeToString(engine.state.value))
            onExit()
        }
    }

    /**
     * Decodifica un guardado. Tolerante a fallos a propósito: si el JSON quedó de
     * una versión anterior del estado, se trata como "no hay partida" en vez de
     * romper la pantalla — el jugador solo pierde ese guardado.
     */
    private fun decodeSaved(json: String): CrucigramaNeonState? =
        runCatching { Json.decodeFromString<CrucigramaNeonState>(json) }.getOrNull()

    /**
     * `saveResult` emite en 1 o 2 pasos: local primero (el cartel no espera a
     * Supabase) y, con sesión, el percentil real después (ver KDoc de
     * `ProgressRepository.saveResult`).
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            // Nivel completado: un guardado de esta partida (si quedó alguno) es
            // "fantasma" a partir de aquí, ya se registró el resultado final.
            savedGameState.clear(GameIds.CRUCIGRAMA_NEON)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            if (pendingAutoAdvance) {
                // Atajo desde pausa (SkipToNextLevel): no hay cartel de resultado que
                // pintar, así que no hace falta esperar el percentil remoto (2ª
                // emisión); alcanza con que el guardado local ya esté confirmado.
                pendingAutoAdvance = false
                progress.saveResult(result).first()
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            } else {
                progress.saveResult(result).collect { outcome ->
                    setState { copy(gameOver = outcome.toGameOverInfo(result)) }
                }
            }
        }
    }
}
