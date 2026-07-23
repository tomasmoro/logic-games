package com.example.kortexgames.game.blockgrid

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.MviViewModel
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.domain.repository.SavedGameStateRepository
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val savedGameState: SavedGameStateRepository,
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
        // Corrida guardada al salir: la antesala la ofrece como "Continuar" (se
        // observa para que el botón desaparezca solo al reanudar o al terminar).
        savedGameState.observe(GameIds.NEON_BLOCK_GRID)
            .onEach { json -> setState { copy(savedScore = json?.let(::decodeSaved)?.score) } }
            .launchIn(viewModelScope)
        // No se arranca aquí: se queda en IDLE mostrando la antesala y la
        // partida empieza con StartGame / ResumeSaved (patrón del resto de juegos).
    }

    override fun onIntent(intent: BlockGridIntent) {
        when (intent) {
            // StartGame ("Empezar de nuevo") y PlayAgain arrancan de cero y descartan
            // el guardado; ResumeSaved retoma la corrida guardada. La elección es
            // siempre explícita del jugador (antesala Continuar vs. Empezar de nuevo).
            BlockGridIntent.StartGame,
            BlockGridIntent.PlayAgain -> {
                setState { copy(gameOver = null, drag = null) }
                viewModelScope.launch { savedGameState.clear(GameIds.NEON_BLOCK_GRID) }
                engine.start()
            }

            BlockGridIntent.ResumeSaved -> resumeSaved()

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

            BlockGridIntent.DragCancelled -> {
                // El dedo se soltó fuera del tablero: la pieza vuelve a su hueco.
                // Se avisa a la UI con el id ANTES de limpiar el drag, para que
                // anime el "vuelo de vuelta" (la pieza nunca salió de la mano).
                currentState.drag?.pieceId?.let { sendEffect(BlockGridEffect.AnimatePieceReturn(it)) }
                setState { copy(drag = null) }
            }

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
            is BlockGridEvent.PlacementRejected -> {
                sendEffect(BlockGridEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(BlockGridEffect.Vibrate(HapticFeedback.ERROR))
                // El drag ya se limpió al soltar; la pieza sigue en la mano. La
                // UI la hace "volar de vuelta" a su hueco en lugar de un salto seco.
                sendEffect(BlockGridEffect.AnimatePieceReturn(event.pieceId))
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

    /**
     * Retoma la corrida guardada al salir. El guardado se consume (se borra) al
     * reanudar. No-op si no hay ninguna.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.NEON_BLOCK_GRID)?.let(::decodeSaved) ?: return@launch
            savedGameState.clear(GameIds.NEON_BLOCK_GRID)
            setState { copy(gameOver = null, drag = null) }
            engine.resumeFrom(saved)
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.example.kortexgames.ui.components.GameExitGuard] o "SALIR" del menú de
     * pausa): si hay una corrida en curso ([GameStatus.RUNNING] o [GameStatus.PAUSED])
     * la guarda antes de navegar atrás. En el resto de estados no hay progreso que
     * perder, así que [onExit] se llama directo.
     */
    fun requestExit(onExit: () -> Unit) {
        val status = currentState.status
        if (status != GameStatus.RUNNING && status != GameStatus.PAUSED) {
            onExit()
            return
        }
        viewModelScope.launch {
            // Las celdas en animación de limpieza (Clearing) se vacían antes de
            // guardar: el dominio ya las dio por rotas y, sin dedo en pantalla al
            // reanudar, no habría quien dispare su animación de desaparición —
            // quedarían "encendidas" para siempre. Vaciarlas deja el tablero en el
            // estado lógico exacto que tendría tras terminar la animación.
            val snapshot = engine.state.value.let { s ->
                s.copy(board = s.board.mapCells { if (it is BoardCell.Clearing) BoardCell.Empty else it })
            }
            savedGameState.save(GameIds.NEON_BLOCK_GRID, Json.encodeToString(snapshot))
            onExit()
        }
    }

    /** Decodifica un guardado; tolerante a fallos (versión previa ⇒ "no hay partida"). */
    private fun decodeSaved(json: String): BlockGridGameState? =
        runCatching { Json.decodeFromString<BlockGridGameState>(json) }.getOrNull()

    /** Guarda el resultado (local-first) y expone el game-over con percentil. */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            // Corrida terminada: no debe quedar un guardado "fantasma" de esta partida.
            savedGameState.clear(GameIds.NEON_BLOCK_GRID)
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
