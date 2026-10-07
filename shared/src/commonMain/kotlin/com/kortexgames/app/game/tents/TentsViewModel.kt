package com.kortexgames.app.game.tents

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * # TentsViewModel — motor de la partida
 *
 * Traduce los toques ([TentsIntent]) en cambios de tablero aplicando [TentsRules], lleva el ciclo
 * de vida de la partida (antesala → jugando ↔ pausa → terminada) y, al resolver el nivel, guarda
 * el resultado en el repositorio local-first. No contiene reglas propias: aquí solo se decide
 * **cuándo** se evalúa y **qué** se le cuenta al jugador. Misma forma que `ShikakuViewModel`, y
 * por el mismo motivo no delega en un `BaseGameEngine`: el tablero ya es un reductor puro.
 *
 * Decisiones de comportamiento:
 *
 *  - **Lo incorrecto se queda en el tablero.** Una tienda que toca a otra o desborda un contador
 *    se coloca igualmente (suena a error y se pinta en rojo): impedirla le regalaría al jugador
 *    la deducción —bastaría tantear casillas hasta que una "entre"— y a menudo es un paso
 *    intermedio mientras se mueve una tienda de sitio.
 *  - **El aviso es solo de lo que se ve.** Se avisa del contacto y del contador excedido, que son
 *    violaciones evidentes en pantalla. No se avisa de "esta tienda no es la de la solución":
 *    eso sería una pista gratuita, y además la victoria se valida contra las reglas, no contra
 *    la solución guardada.
 *  - **Rectificar cuesta, anotar no.** Cada tienda retirada cuenta como corrección y baja la
 *    eficiencia ([TentsScoring]); el pasto se pone y se quita gratis.
 *
 * @param generatorDispatcher dónde corre el generador. Es CPU pura (colocación + certificación de
 *   unicidad) y no debe ocupar el hilo principal; se inyecta para que los tests lo sustituyan.
 */
class TentsViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val adManager: AdManager,
    private val generatorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviViewModel<TentsIntent, TentsUiState, TentsEffect>(TentsUiState()) {

    /** Generación en curso; se cancela si se pide otro nivel antes de que termine. */
    private var generation: Job? = null

    // Contadores de la partida en curso. No son estado de UI (nada los pinta): solo alimentan la
    // puntuación al terminar.
    private var corrections = 0
    private var restarts = 0

    // Cronómetro de tiempo ACTIVO: `accumulated` guarda los tramos ya cerrados y `runMark` el
    // tramo abierto. Pausar cierra el tramo, así el tiempo en pausa nunca cuenta.
    private var accumulated: Duration = Duration.ZERO
    private var runMark: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        playerProgress.observe(GameIds.NEON_TENTS)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: TentsIntent) {
        when (intent) {
            is TentsIntent.PlayLevel -> playLevel(intent.level)
            is TentsIntent.CycleCell -> cycleCell(intent.x, intent.y)
            is TentsIntent.SetCellState -> setCell(intent.x, intent.y, intent.target)
            TentsIntent.RestartLevel -> restart()
            TentsIntent.Pause -> pause()
            TentsIntent.Resume -> resume()
            TentsIntent.PlayAgain -> playLevel(currentState.level)
            TentsIntent.NextLevel -> if (currentState.status == GameStatus.FINISHED) {
                // Entre niveles es el corte natural para un anuncio: nunca a mitad de tablero.
                adManager.onAdBreakpoint()
                playLevel(currentState.level + 1)
            }
            TentsIntent.ChooseLevel -> {
                generation?.cancel()
                setState { TentsUiState(maxUnlocked = maxUnlocked) }
            }
        }
    }

    /** `true` si el tablero admite toques: generado, en marcha y sin resolver. */
    private val TentsUiState.acceptsInput: Boolean get() = status == GameStatus.RUNNING && !isGenerating

    private fun cycleCell(x: Int, y: Int) {
        val board = currentState.board
        // Fuera de la rejilla no hay casilla que ciclar; el árbol lo descarta `setCell`.
        if (!board.isInside(x, y)) return
        setCell(x, y, board[x, y].type.next())
    }

    /**
     * Aplica la jugada y decide el feedback. Es el único camino por el que cambia el tablero,
     * tanto para el ciclo de toque como para el pincel directo.
     */
    private fun setCell(x: Int, y: Int, target: TentsCellType) {
        val state = currentState
        if (!state.acceptsInput) return
        val board = TentsRules.setCell(state.board, x, y, target)
        // Misma instancia = jugada sin efecto (árbol, fuera de rango, casilla ya en ese estado).
        // Se ignora en silencio: al arrastrar el pincel el dedo pasa por muchas así.
        if (board === state.board) return

        // Toda tienda que desaparece —la quite el ciclo o la pise el pincel de pasto— es una
        // rectificación: la solución no necesitaba ese toque.
        if (state.board[x, y].type == TentsCellType.TENT) corrections++

        val rowCounts = TentsRules.rowCounts(board)
        val columnCounts = TentsRules.columnCounts(board)
        val solved = TentsRules.isSolved(board, state.rowTargets, state.columnTargets)
        setState { copy(board = board, rowCounts = rowCounts, columnCounts = columnCounts, isSolved = solved) }

        if (solved) {
            finish()
            return
        }
        sendEffect(TentsEffect.Vibrate(TentsHaptic.TICK))
        when (target) {
            TentsCellType.TENT -> {
                val overflows = rowCounts[y] > state.rowTargets[y] || columnCounts[x] > state.columnTargets[x]
                val wrong = board[x, y].hasConflict || overflows
                sendEffect(TentsEffect.PlaySound(if (wrong) TentsSound.ERROR else TentsSound.PLACE_TENT))
            }

            TentsCellType.GRASS -> sendEffect(TentsEffect.PlaySound(TentsSound.PLACE_GRASS))

            // Vaciar una casilla solo vibra: no hay nada que "colocar" que merezca sonido.
            TentsCellType.EMPTY, TentsCellType.TREE -> Unit
        }
    }

    private fun restart() {
        val state = currentState
        if (!state.acceptsInput) return
        restarts++
        val board = TentsBoard(
            size = state.board.size,
            cells = state.board.cells.map { if (it.type == TentsCellType.TREE) it else TentsCell(it.x, it.y) },
        )
        setState {
            copy(
                board = board,
                rowCounts = TentsRules.rowCounts(board),
                columnCounts = TentsRules.columnCounts(board),
                isSolved = false,
            )
        }
    }

    private fun pause() {
        if (currentState.status != GameStatus.RUNNING) return
        stopClock()
        setState { copy(status = GameStatus.PAUSED) }
    }

    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        runMark = TimeSource.Monotonic.markNow()
        setState { copy(status = GameStatus.RUNNING) }
    }

    /** Cierra el tramo de cronómetro abierto, si lo hay. */
    private fun stopClock() {
        accumulated += runMark?.elapsedNow() ?: Duration.ZERO
        runMark = null
    }

    /**
     * Genera el tablero de [level] y arranca la partida. El cronómetro empieza cuando el tablero
     * ya está en pantalla, no al pulsar: el tiempo de generación no es tiempo de juego.
     */
    private fun playLevel(level: Int) {
        generation?.cancel()
        val target = level.coerceAtLeast(1)
        setState {
            TentsUiState(
                phase = LeveledGamePhase.PLAYING,
                maxUnlocked = maxUnlocked,
                level = target,
                isGenerating = true,
            )
        }
        generation = viewModelScope.launch {
            val generated = withContext(generatorDispatcher) { TentsLevelGenerator.generate(target) }
            val board = TentsBoard.from(generated)
            corrections = 0
            restarts = 0
            accumulated = Duration.ZERO
            runMark = TimeSource.Monotonic.markNow()
            setState {
                copy(
                    board = board,
                    rowTargets = generated.rowTargets,
                    columnTargets = generated.columnTargets,
                    rowCounts = TentsRules.rowCounts(board),
                    columnCounts = TentsRules.columnCounts(board),
                    isGenerating = false,
                    status = GameStatus.RUNNING,
                )
            }
        }
    }

    /**
     * Cierra la partida resuelta: para el reloj, calcula el resultado y lo guarda. El overlay de
     * fin aparece cuando el repositorio responde (trae percentil y récord), pero el estado pasa a
     * `FINISHED` de inmediato para bloquear el tablero mientras tanto.
     */
    private fun finish() {
        stopClock()
        val state = currentState
        val efficiency = TentsScoring.efficiency(state.rowTargets.sum(), corrections)
        val elapsedMs = accumulated.inWholeMilliseconds
        val result = GameResult(
            gameId = GameIds.NEON_TENTS,
            score = TentsScoring.score(state.level, state.board.cells.size, elapsedMs, efficiency, restarts),
            completionTimeMs = elapsedMs,
            accuracyPercentage = efficiency * 100,
            // El nivel viaja en `reachedMetric`, no en `difficulty_level` (escalón 1..5 de tabla
            // de ranking en el backend): este juego compite en tabla única porque el puntaje ya
            // está dominado por el nivel. Mismo criterio que Shikaku y Línea Neón.
            reachedMetric = state.level,
        )
        setState { copy(status = GameStatus.FINISHED) }
        sendEffect(TentsEffect.PlaySound(TentsSound.LEVEL_COMPLETE))
        sendEffect(TentsEffect.Vibrate(TentsHaptic.SUCCESS))
        viewModelScope.launch {
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
