package com.kortexgames.app.game.memory

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Estado de UI de la pantalla de Memoria de Secuencias.
 *
 * @property rankingPreview comparativa mundial del jugador, para pintar en la
 *   antesala el mismo panel que el diálogo de fin de partida ANTES de jugar (ver
 *   [com.kortexgames.app.domain.repository.ProgressRepository.previewRanking]).
 *   Tabla única (el juego no separa por dificultad): `null` mientras se resuelve
 *   ([rankingPreviewLoading]) o si no hay comparativa que mostrar (invitado, sin
 *   red, o sin ninguna marca todavía).
 * @property rankingPreviewLoading `true` mientras se pide [rankingPreview] tras
 *   entrar en la antesala. Arranca en `true` (no en `false`) para no enseñar el
 *   aviso de "sin comparativa" un instante antes de que llegue.
 */
data class SequenceMemoryUiState(
    val game: SequenceMemoryState = SequenceMemoryState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = true,
) : UiState

sealed interface SequenceMemoryIntent : UiIntent {
    data object Start : SequenceMemoryIntent
    data class TapTile(val index: Int) : SequenceMemoryIntent
    data object Pause : SequenceMemoryIntent
    data object Resume : SequenceMemoryIntent
    data object PlayAgain : SequenceMemoryIntent
}

sealed interface SequenceMemoryEffect : UiEffect

/**
 * ViewModel MVI que gobierna una partida de Memoria de Secuencias. Posee el
 * [SequenceMemoryEngine] (creado con `viewModelScope`), proyecta su estado a la
 * UI y, al terminar, **guarda el resultado (local-first) y consulta el percentil**.
 */
class SequenceMemoryViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<SequenceMemoryIntent, SequenceMemoryUiState, SequenceMemoryEffect>(
    SequenceMemoryUiState(),
) {
    private val engine = SequenceMemoryEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        // outcome pasa de null a un GameResult cuando la partida termina.
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [SequenceMemoryIntent.Start]).

        // Comparativa mundial para la antesala (ver KDoc de `rankingPreview`). Un único
        // pedido basta: el juego no vuelve a IDLE tras jugar dentro de la misma visita
        // (empezar de nuevo salta directo a RUNNING), así que no hay que refrescarlo.
        viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.SEQUENCE_MEMORY)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }
    }

    override fun onIntent(intent: SequenceMemoryIntent) {
        when (intent) {
            SequenceMemoryIntent.Start,
            SequenceMemoryIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
            is SequenceMemoryIntent.TapTile -> engine.onTileTapped(intent.index)
            SequenceMemoryIntent.Pause -> engine.pause()
            SequenceMemoryIntent.Resume -> engine.resume()
        }
    }

    /**
     * Guarda el resultado y expone el percentil en el estado. `saveResult` emite en
     * 1 o 2 pasos: local primero (el cartel no espera a Supabase) y, con sesión, el
     * percentil real después (ver KDoc de `ProgressRepository.saveResult`).
     */
    private fun onFinished(result: com.kortexgames.app.domain.model.GameResult) {
        viewModelScope.launch {
            audio.playSound(SoundEffect.LEVEL_UP)
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
