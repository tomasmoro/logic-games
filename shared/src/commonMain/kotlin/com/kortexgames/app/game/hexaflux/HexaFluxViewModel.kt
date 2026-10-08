package com.kortexgames.app.game.hexaflux

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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * # HexaFluxViewModel — motor de la partida
 *
 * Orquesta un nivel de Neon Hexa Flux: traduce los [HexaFluxIntent] en llamadas al
 * reductor puro [HexaFluxEngine], decide tras cada jugada si el objetivo se cumplió
 * o el nivel se perdió, lleva el ciclo de vida (antesala → jugando ↔ pausa →
 * terminada) y guarda el resultado en el repositorio local-first. Misma forma que
 * `TentsViewModel`, y por el mismo motivo no delega en un `BaseGameEngine`: el
 * tablero ya es un reductor puro.
 *
 * Decisiones de comportamiento:
 *
 *  - **Ganar corta el turno.** Si la jugada cumple el objetivo no se ejecuta la
 *    respuesta del tablero ([HexaFluxEngine.endTurn]): una bomba que detona en el
 *    mismo instante de la victoria solo ensuciaría la pantalla de resultado.
 *  - **Se comprueba la victoria antes que la derrota.** La última jugada disponible
 *    puede ser la que gana; en supervivencia, de hecho, *siempre* lo es.
 *  - **La derrota no guarda resultado.** El ranking compara niveles superados; un
 *    intento fallido no tiene marca que aportar y solo ofrece reintentar.
 *  - **La bandeja se repone en el mismo hueco.** La pieza usada se sustituye in situ
 *    en lugar de desplazar las demás: el jugador ya tiene localizadas las otras dos
 *    y moverlas de sitio le obligaría a releer la bandeja en cada turno.
 *  - **Partida reproducible.** Las piezas y las apariciones salen de un [Random]
 *    sembrado con el nivel, que se vuelve a crear al reiniciar: mismo nivel, mismas
 *    piezas. Es lo que hace comparable la marca entre jugadores, y por eso reiniciar
 *    penaliza (ver [HexaFluxIntent.RestartLevel]).
 */
class HexaFluxViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val adManager: AdManager,
) : MviViewModel<HexaFluxIntent, HexaFluxUiState, HexaFluxEffect>(HexaFluxUiState()) {

    // Contadores de la partida en curso. No son estado de UI (nada los pinta): solo
    // alimentan las reglas de turno y la puntuación final.
    private var turnsPlayed = 0
    private var restarts = 0
    private var nextPieceId = 0L
    private var random = Random(0)

    // Cronómetro de tiempo ACTIVO: `accumulated` guarda los tramos ya cerrados y
    // `runMark` el tramo abierto. Pausar cierra el tramo, así la pausa nunca cuenta.
    private var accumulated: Duration = Duration.ZERO
    private var runMark: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        playerProgress.observe(GameIds.NEON_HEXA_FLUX)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: HexaFluxIntent) {
        when (intent) {
            is HexaFluxIntent.PlayLevel -> playLevel(intent.level)
            is HexaFluxIntent.PlacePiece -> place(HexCoord(intent.q, intent.r), intent.piece.id)
            is HexaFluxIntent.RotatePiece -> rotate(intent.pieceIndex)
            HexaFluxIntent.RestartLevel -> restart()
            HexaFluxIntent.NextLevel -> if (currentState.isLevelCleared) {
                // Entre niveles es el corte natural para un anuncio: nunca a mitad de tablero.
                adManager.onAdBreakpoint()
                playLevel(currentState.level + 1)
            }
            HexaFluxIntent.Pause -> pause()
            HexaFluxIntent.Resume -> resume()
            HexaFluxIntent.ChooseLevel -> setState { HexaFluxUiState(maxUnlocked = maxUnlocked) }
        }
    }

    /** Arranca [level] desde cero: tablero, bandeja, cronómetro y penalizaciones. */
    private fun playLevel(level: Int) {
        restarts = 0
        accumulated = Duration.ZERO
        deal(HexaLevelGenerator.generate(level.coerceAtLeast(1)))
    }

    private fun restart() {
        val state = currentState
        // Se permite con el nivel perdido (es el "reintentar"), pero no tras ganarlo:
        // el resultado ya está guardado y rehacerlo es un PlayLevel nuevo.
        if (state.phase != LeveledGamePhase.PLAYING || state.isLevelCleared) return
        if (state.status == GameStatus.PAUSED) return
        restarts++
        stopClock()
        deal(state.levelConfig)
    }

    /**
     * Reparte el nivel: tablero inicial, bandeja nueva y reloj en marcha. Es el único
     * sitio donde se siembra [random], para que empezar y reiniciar no puedan divergir.
     */
    private fun deal(config: HexaLevelConfig) {
        turnsPlayed = 0
        nextPieceId = 0L
        // Semilla distinta de la del generador (que usa el nivel con otra sal) para
        // que las piezas no estén correlacionadas con la disposición del tablero.
        random = Random(config.level * PIECE_SEED_FACTOR)
        val tray = List(HexaFluxConfig.TRAY_SIZE) { drawPiece(config.pieceBag) }
        runMark = TimeSource.Monotonic.markNow()
        setState {
            HexaFluxUiState(
                phase = LeveledGamePhase.PLAYING,
                maxUnlocked = maxUnlocked,
                status = GameStatus.RUNNING,
                levelConfig = config,
                nextPieces = tray,
            )
        }
    }

    private fun drawPiece(bag: PieceBag): HexPiece = HexaLevelGenerator.nextPiece(bag, random, nextPieceId++)

    private fun rotate(index: Int) {
        val state = currentState
        if (state.status != GameStatus.RUNNING) return
        val piece = state.nextPieces.getOrNull(index) ?: return
        // Una ficha suelta es idéntica en las seis orientaciones: girarla no cambia
        // nada y dar feedback sería mentirle al jugador.
        if (piece.tiles.size == 1) return
        setState { copy(nextPieces = nextPieces.toMutableList().also { it[index] = piece.rotatedClockwise() }) }
        sendEffect(HexaFluxEffect.Vibrate.Light)
    }

    /**
     * Resuelve un turno completo. Es el único camino por el que cambia el tablero.
     *
     * @param pieceId id de la pieza a colocar; se busca en la bandeja del estado (ver
     *   [HexaFluxIntent.PlacePiece.piece]).
     */
    private fun place(anchor: HexCoord, pieceId: Long) {
        val state = currentState
        if (state.status != GameStatus.RUNNING) return
        val index = state.nextPieces.indexOfFirst { it.id == pieceId }
        if (index < 0) return
        val placed = HexaFluxEngine.place(state.boardState, state.nextPieces[index], anchor)
        if (placed == null) {
            sendEffect(HexaFluxEffect.PlaySound.Invalid)
            sendEffect(HexaFluxEffect.Vibrate.Error)
            return
        }

        turnsPlayed++
        val config = state.levelConfig
        val win = config.winCondition
        val score = state.score + placed.scoreGained
        val movesLeft = state.movesLeft - 1
        val goal = state.progress.goal
        val progress = ObjectiveProgress(
            current = when (win) {
                is WinCondition.TargetScore -> score
                WinCondition.ClearBoard -> goal - placed.board.values.count { it.isClearTarget }
                is WinCondition.Survive -> turnsPlayed
            }.coerceAtMost(goal),
            goal = goal,
        )
        val cleared = progress.isComplete

        // La respuesta del tablero solo llega si la jugada no ganó (ver KDoc de clase).
        val turnEnd = if (cleared) {
            null
        } else {
            val spawn = win is WinCondition.Survive && turnsPlayed % win.spawnEvery == 0
            HexaFluxEngine.endTurn(placed.board, spawn, random)
        }
        val board = turnEnd?.board ?: placed.board
        val tray = state.nextPieces.toMutableList().also { it[index] = drawPiece(config.pieceBag) }
        // Sin jugadas, o con jugadas pero sin hueco para ninguna pieza en ninguna rotación.
        val lost = !cleared && (movesLeft <= 0 || !HexaFluxEngine.hasAnyMove(board, tray))

        setState {
            copy(
                boardState = board,
                nextPieces = tray,
                progress = progress,
                score = score,
                movesLeft = movesLeft,
                combo = placed.combo,
                isLevelCleared = cleared,
                isGameOver = lost,
                status = if (cleared || lost) GameStatus.FINISHED else status,
            )
        }

        sendEffect(HexaFluxEffect.PlaySound.Place)
        sendEffect(HexaFluxEffect.Vibrate.Light)
        if (placed.combo > 0) sendEffect(HexaFluxEffect.PlaySound.Merge)
        if (placed.gimmicksBroken > 0) sendEffect(HexaFluxEffect.PlaySound.BreakIce)
        if ((turnEnd?.bombsDetonated ?: 0) > 0) sendEffect(HexaFluxEffect.Vibrate.Heavy)
        when {
            cleared -> finish(board)
            lost -> {
                stopClock()
                sendEffect(HexaFluxEffect.PlaySound.Invalid)
                sendEffect(HexaFluxEffect.Vibrate.Error)
            }
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
     * Cierra el nivel superado: para el reloj, calcula el resultado y lo guarda. El
     * overlay de fin aparece cuando el repositorio responde (trae percentil y
     * récord); el estado ya está en `FINISHED`, así que el tablero queda bloqueado
     * mientras tanto.
     */
    private fun finish(board: Map<HexCoord, HexCell>) {
        stopClock()
        val state = currentState
        val config = state.levelConfig
        val efficiency = HexaFluxScoring.efficiency(
            win = config.winCondition,
            movesLeft = state.movesLeft,
            moveLimit = config.moveLimit,
            freeCells = board.values.count { it.isFree },
            totalCells = board.size,
        )
        val elapsedMs = accumulated.inWholeMilliseconds
        val result = GameResult(
            gameId = GameIds.NEON_HEXA_FLUX,
            score = HexaFluxScoring.score(config.level, config.moveLimit, elapsedMs, efficiency, restarts),
            completionTimeMs = elapsedMs,
            accuracyPercentage = efficiency * 100,
            // El nivel viaja en `reachedMetric`, no en `difficulty_level` (escalón 1..5
            // de tabla de ranking en el backend): el juego compite en tabla única
            // porque el puntaje ya está dominado por el nivel. Mismo criterio que
            // Tents, Shikaku y Línea Neón.
            reachedMetric = config.level,
        )
        sendEffect(HexaFluxEffect.PlaySound.LevelComplete)
        sendEffect(HexaFluxEffect.Vibrate.Success)
        viewModelScope.launch {
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }

    private companion object {
        /** Primo que dispersa la semilla de piezas respecto a la del tablero. */
        const val PIECE_SEED_FACTOR = 7919
    }
}
