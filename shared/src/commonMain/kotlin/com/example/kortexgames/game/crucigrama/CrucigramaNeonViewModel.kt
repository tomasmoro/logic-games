package com.example.kortexgames.game.crucigrama

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
import com.example.kortexgames.domain.repository.SavedGameStateRepository
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
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
 *   [com.example.kortexgames.ui.components.ResumeState]).
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
                )
            }
            engine.resumeFrom(saved)
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía [com.example.kortexgames.ui.components.GameExitGuard]
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

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            // Nivel completado: un guardado de esta partida (si quedó alguno) es
            // "fantasma" a partir de aquí, ya se registró el resultado final.
            savedGameState.clear(GameIds.CRUCIGRAMA_NEON)
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }
}
