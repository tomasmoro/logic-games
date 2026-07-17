package com.example.kortexgames.game.blockgrid

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.MviViewModel
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.game.GameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Neon Block Grid". Juego **ENDLESS por puntaje**: arranca en
 * IDLE (antesala/intro), la partida dura hasta que ninguna pieza cabe y al
 * terminar persiste el resultado (local-first) y consulta el percentil.
 *
 * Reparto de responsabilidades:
 *  - Reglas (colocar, romper líneas, Game Over) → [BlockGridEngine].
 *  - **Arrastre** → aquí: es estado de interacción, no de reglas. En cada
 *    `DragMoved` se consulta al motor por el fantasma ([PlacementPreview]) y se
 *    publica en el estado; la UI solo pinta.
 *  - Feedback sensorial → Effects one-shot, con la tabla evento→(sonido,
 *    háptica) centralizada en [onEngineEvent] (mismo esquema que Tornillos Neón).
 */
class BlockGridViewModel(
    private val progress: ProgressRepository,
    audio: AudioAndHapticManager,
) : MviViewModel<BlockGridIntent, BlockGridUiState, BlockGridEffect>(BlockGridUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa:
    // todo el feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = BlockGridEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s ->
            setState {
                copy(board = s.board, hand = s.hand, score = s.score, linesCleared = s.linesCleared)
            }
        }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // No se arranca aquí: se queda en IDLE mostrando la antesala y la
        // partida empieza con el intent StartGame (botón "Comenzar").
    }

    override fun onIntent(intent: BlockGridIntent) {
        when (intent) {
            BlockGridIntent.StartGame,
            BlockGridIntent.PlayAgain -> {
                setState { copy(gameOver = null, drag = null) }
                engine.start()
            }

            is BlockGridIntent.DragStarted -> {
                setState { copy(drag = DragState(pieceId = intent.pieceId)) }
                // Levantar la pieza responde al instante (resorte + tick suave),
                // sin esperar a que el dedo llegue al tablero.
                sendEffect(BlockGridEffect.PlaySound(SoundEffect.TAP))
                sendEffect(BlockGridEffect.Vibrate(HapticFeedback.LIGHT))
            }

            is BlockGridIntent.DragMoved -> setState {
                // Ignora movimientos de un drag ya cancelado o de otra pieza
                // (gestos zombis tras un game-over o multitouch accidental).
                if (drag?.pieceId != intent.pieceId) this
                else copy(
                    drag = drag.copy(
                        preview = engine.previewFor(intent.pieceId, intent.row, intent.col),
                    ),
                )
            }

            is BlockGridIntent.DropPiece -> {
                setState { copy(drag = null) }
                engine.onDrop(intent.pieceId, intent.row, intent.col)
            }

            BlockGridIntent.DragCancelled -> setState { copy(drag = null) }

            BlockGridIntent.LineClearFinished -> engine.onLineClearFinished()

            BlockGridIntent.Pause -> engine.pause()
            BlockGridIntent.Resume -> engine.resume()
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     */
    private fun onEngineEvent(event: BlockGridEvent) {
        when (event) {
            BlockGridEvent.PiecePlaced -> {
                // "Clac" seco de anclaje: sonido corto + vibración media.
                sendEffect(BlockGridEffect.PlaySound(SoundEffect.TAP))
                sendEffect(BlockGridEffect.Vibrate(HapticFeedback.MEDIUM))
            }
            BlockGridEvent.PlacementRejected -> {
                sendEffect(BlockGridEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(BlockGridEffect.Vibrate(HapticFeedback.ERROR))
            }
            is BlockGridEvent.LinesCleared -> {
                sendEffect(BlockGridEffect.PlaySound(SoundEffect.SUCCESS))
                sendEffect(BlockGridEffect.Vibrate(HapticFeedback.HEAVY))
                // Guirnaldas solo en combos de línea (5+) o vaciado total: si
                // cayeran en cada línea suelta, perderían su peso de "gran hito".
                val showGarlands = event.isPerfectClear || event.count >= GARLAND_COMBO_THRESHOLD
                sendEffect(BlockGridEffect.ShowComboAnim(event.count, showGarlands))
            }
            // La reposición es consecuencia natural de colocar la 3.ª pieza; un
            // SFX propio competiría con el de la colocación que la causó.
            BlockGridEvent.HandRefilled -> Unit
            // El sonido de fin de partida se emite en onFinished, tras persistir:
            // así acompaña a la aparición del overlay de resultados.
            BlockGridEvent.GameOverReached -> Unit
        }
    }

    private companion object {
        /** Nº de líneas simultáneas a partir del cual el combo "gana" guirnaldas. */
        const val GARLAND_COMBO_THRESHOLD = 5
    }

    /** Guarda el resultado (local-first) y expone el game-over con percentil. */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            sendEffect(BlockGridEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(BlockGridEffect.Vibrate(HapticFeedback.SUCCESS))
            setState {
                copy(
                    drag = null,
                    gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord),
                )
            }
        }
    }
}
