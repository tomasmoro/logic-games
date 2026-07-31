package com.kortexgames.app.game.wordsearch

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ViewModel MVI de "Neon Lexicon" (Sopa de Letras Neón).
 *
 * Juego **LEVELED**: arranca en la antesala (selección de nivel) y, al jugar,
 * delega toda la lógica y la geometría en [NeonLexiconEngine]. Reparto de
 * responsabilidades (igual que `blockgrid` / Crucigrama):
 *  - Reglas + "snap" del trazo + validación → [NeonLexiconEngine].
 *  - Feedback sensorial → Effects one-shot, con la tabla evento→(sonido, háptica)
 *    centralizada en [onEngineEvent].
 *  - Ciclo de nivel y persistencia del resultado (local-first) → aquí.
 */
class NeonLexiconViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<NeonLexiconIntent, NeonLexiconUiState, NeonLexiconEffect>(NeonLexiconUiState()) {

    private val engine = NeonLexiconEngine(viewModelScope, audio)

    init {
        // El estado del motor (rejilla + palabras + selección) alimenta el UiState.
        engine.state.onEach { s ->
            setState { copy(grid = s.grid, words = s.words, selection = s.selection) }
        }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Nivel máximo desbloqueado (récord LEVELED) para pintar el carril de niveles.
        playerProgress.observe(GameIds.NEON_LEXICON)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        // Partida guardada al salir: la antesala la ofrece como "Continuar" (se
        // observa para que el botón desaparezca solo al reanudar o completar nivel).
        savedGameState.observe(GameIds.NEON_LEXICON)
            .onEach { json -> setState { copy(savedLevel = json?.let(::decodeSaved)?.level) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: NeonLexiconIntent) {
        when (intent) {
            NeonLexiconIntent.Start,
            NeonLexiconIntent.PlayAgain -> playLevel(currentState.currentLevel)
            is NeonLexiconIntent.PlayLevel -> playLevel(intent.level)
            NeonLexiconIntent.ResumeSaved -> resumeSaved()
            NeonLexiconIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }

            is NeonLexiconIntent.StartDrag -> engine.beginSelection(intent.row, intent.col)
            is NeonLexiconIntent.UpdateDrag -> engine.moveSelection(intent.row, intent.col)
            NeonLexiconIntent.EndDrag -> engine.commitSelection()
            NeonLexiconIntent.CancelDrag -> engine.cancelSelection()

            NeonLexiconIntent.Pause -> engine.pause()
            NeonLexiconIntent.Resume -> engine.resume()

            NeonLexiconIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null, selection = null)
            }
        }
    }

    /**
     * Arranca [level] **desde cero**, descartando cualquier partida guardada:
     * llegar aquí es una decisión explícita ("Empezar de nuevo", rejugar o pasar de
     * nivel), y dejar el guardado vivo lo reofrecería como "Continuar". Para
     * retomarlo está [resumeSaved].
     */
    private fun playLevel(level: Int) {
        setState {
            copy(
                phase = LeveledGamePhase.PLAYING,
                currentLevel = level,
                gameOver = null,
                selection = null,
            )
        }
        viewModelScope.launch { savedGameState.clear(GameIds.NEON_LEXICON) }
        engine.startAtLevel(level)
    }

    /**
     * Retoma la partida guardada al salir, en su nivel original. El guardado se
     * consume al reanudar. No-op si no hay ninguna.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.NEON_LEXICON)?.let(::decodeSaved) ?: return@launch
            savedGameState.clear(GameIds.NEON_LEXICON)
            setState {
                copy(
                    phase = LeveledGamePhase.PLAYING,
                    currentLevel = saved.level,
                    gameOver = null,
                    selection = null,
                )
            }
            engine.resumeFrom(saved)
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.kortexgames.app.ui.components.GameExitGuard] o "SALIR" del menú de
     * pausa): si hay partida en curso la guarda antes de navegar atrás. Fuera de
     * [LeveledGamePhase.PLAYING] (antesala, fin de nivel) no hay progreso que perder.
     */
    fun requestExit(onExit: () -> Unit) {
        if (currentState.phase != LeveledGamePhase.PLAYING) {
            onExit()
            return
        }
        viewModelScope.launch {
            // El trazo en curso (selection) no debe persistirse: al reanudar no hay
            // dedo sobre la pantalla, así que se guarda el tablero sin selección.
            val snapshot = engine.state.value.copy(selection = null)
            savedGameState.save(GameIds.NEON_LEXICON, Json.encodeToString(snapshot))
            onExit()
        }
    }

    /** Decodifica un guardado; tolerante a fallos (versión previa ⇒ "no hay partida"). */
    private fun decodeSaved(json: String): NeonLexiconGameState? =
        runCatching { Json.decodeFromString<NeonLexiconGameState>(json) }.getOrNull()

    /**
     * Tabla única evento de dominio → feedback sensorial "ASMR". Mantenerla junta
     * (y no repartida por el motor) hace trivial ajustar la "textura" del juego.
     */
    private fun onEngineEvent(event: NeonLexiconEvent) {
        when (event) {
            // "Cremallera": cada letra que cruza el trazo suena seca + vibración corta.
            NeonLexiconEvent.LetterCrossed -> {
                sendEffect(NeonLexiconEffect.PlaySound(SoundEffect.TAP))
                sendEffect(NeonLexiconEffect.Vibrate(HapticFeedback.LIGHT))
            }
            // Palabra acertada: refuerzo positivo inmediato.
            NeonLexiconEvent.WordFound -> {
                sendEffect(NeonLexiconEffect.PlaySound(SoundEffect.SUCCESS))
                sendEffect(NeonLexiconEffect.Vibrate(HapticFeedback.SUCCESS))
            }
            // Trazo fallido: aviso suave (no listado en el enunciado, pero cierra el
            // feedback loop; el trazo se desvanece por el `selection = null` del motor).
            NeonLexiconEvent.WrongSelection -> {
                sendEffect(NeonLexiconEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(NeonLexiconEffect.Vibrate(HapticFeedback.ERROR))
            }
            // El sonido de victoria se emite en onFinished (tras persistir), para
            // que acompañe a la aparición del overlay de resultados (patrón blockgrid).
            NeonLexiconEvent.AllWordsFound -> Unit
        }
    }

    /** Guarda el resultado (local-first) y expone el game-over con percentil. */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            // Nivel completado: no debe quedar un guardado "fantasma" de esta partida.
            savedGameState.clear(GameIds.NEON_LEXICON)
            val outcome = progress.saveResult(result)
            sendEffect(NeonLexiconEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(NeonLexiconEffect.Vibrate(HapticFeedback.SUCCESS))
            setState {
                copy(
                    selection = null,
                    gameOver = outcome.toGameOverInfo(result),
                )
            }
        }
    }
}
