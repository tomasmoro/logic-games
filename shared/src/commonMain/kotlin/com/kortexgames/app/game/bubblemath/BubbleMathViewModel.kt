package com.kortexgames.app.game.bubblemath

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Estado de UI de la pantalla de Burbujas de Cálculo.
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
data class BubbleMathUiState(
    val game: BubbleMathState = BubbleMathState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = true,
) : UiState

sealed interface BubbleMathIntent : UiIntent {
    /** Arranca la partida desde la antesala (intro), "Comenzar". */
    data object Start : BubbleMathIntent

    /** El jugador tocó una burbuja concreta. */
    data class TapBubble(val id: Int) : BubbleMathIntent

    /** Ficha de la bandeja de la nube de ecuación: va al primer hueco libre. */
    data class TapCloudToken(val id: Int) : BubbleMathIntent

    /** Hueco ya relleno de la nube: devuelve su ficha a la bandeja. */
    data class TapCloudBlank(val index: Int) : BubbleMathIntent

    data object Pause : BubbleMathIntent
    data object Resume : BubbleMathIntent
    data object PlayAgain : BubbleMathIntent

    /** El anuncio recompensado concedió la vida extra: continúa la partida. */
    data object Revive : BubbleMathIntent

    /** Se rechazó la oferta de revivir (o el anuncio no se completó): fin de partida. */
    data object DeclineRevive : BubbleMathIntent
}

sealed interface BubbleMathEffect : UiEffect

/**
 * ViewModel MVI de Burbujas de Cálculo. Mismo patrón que los demás juegos
 * ([com.kortexgames.app.game.memory.SequenceMemoryViewModel]): posee el motor,
 * proyecta su `state`/`status`/`outcome` y, al terminar, persiste el resultado
 * (local-first) obteniendo el percentil frente al resto de jugadores.
 */
class BubbleMathViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : MviViewModel<BubbleMathIntent, BubbleMathUiState, BubbleMathEffect>(BubbleMathUiState()) {

    private val engine = BubbleMathEngine(viewModelScope, audio, difficulty)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        // No arrancamos aquí: el juego queda en IDLE y muestra la antesala (intro). La
        // partida empieza al pulsar "Comenzar" (intent [BubbleMathIntent.Start]).

        // Comparativa mundial para la antesala (ver KDoc de `rankingPreview`). Un único
        // pedido basta: el juego no vuelve a IDLE tras jugar dentro de la misma visita
        // (empezar de nuevo salta directo a RUNNING), así que no hay que refrescarlo.
        viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.BUBBLE_MATH)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }
    }

    override fun onIntent(intent: BubbleMathIntent) {
        when (intent) {
            is BubbleMathIntent.TapBubble -> engine.onBubbleTap(intent.id)
            is BubbleMathIntent.TapCloudToken -> engine.onCloudTokenTap(intent.id)
            is BubbleMathIntent.TapCloudBlank -> engine.onCloudBlankTap(intent.index)
            BubbleMathIntent.Pause -> engine.pause()
            BubbleMathIntent.Resume -> engine.resume()
            BubbleMathIntent.Revive -> engine.grantRevive()
            BubbleMathIntent.DeclineRevive -> engine.declineRevive()
            BubbleMathIntent.Start,
            BubbleMathIntent.PlayAgain -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
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
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
