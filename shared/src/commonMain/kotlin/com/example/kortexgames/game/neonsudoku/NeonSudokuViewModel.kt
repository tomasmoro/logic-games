package com.example.kortexgames.game.neonsudoku

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.MviViewModel
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.domain.repository.SavedGameStateRepository
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * # Neon Sudoku Matrix — Motor de juego (ViewModel, FASE 2)
 *
 * ViewModel MVI del Sudoku 9x9. Igual que [com.example.kortexgames.game.neonpulse.NeonPulseViewModel],
 * el **cronómetro vive en el propio ViewModel** vía [NeonSudokuIntent.Tick]: el
 * reloj es la única fuente que lo emite, manteniendo el ciclo MVI unidireccional
 * puro (a diferencia del resto de juegos, que delegan en un `BaseGameEngine`).
 * A diferencia de Neon Pulse, aquí el cronómetro NUNCA termina la partida por sí
 * solo — el Sudoku no es contrarreloj —, solo alimenta el HUD y penaliza el
 * puntaje final (ver [calculateScore]).
 *
 * Responsabilidades:
 *  - Cargar una plantilla (según [SudokuDifficulty]) y arrancar/pausar/reanudar.
 *  - **Reducir** la selección de celda, la entrada de dígitos (valor o nota) y el
 *    borrado, respetando el "Modo Notas" y las celdas fijas.
 *  - **Validar choques** tras cada escritura/borrado ([recomputeConflicts]).
 *  - Gestionar la regla de errores: a [NeonSudokuConfig.MAX_ERRORS] ofrecer la
 *    segunda oportunidad (anuncio) una vez, o terminar en derrota.
 *  - Detectar la victoria (tablero completo sin choques) y persistir el
 *    [GameResult] con estrategia local-first.
 *  - **Guardar/reanudar** la partida en curso al salir (mismo mecanismo que Neon
 *    Grid 2048, vía [SavedGameStateRepository]).
 *
 * @param progress repositorio local-first para guardar el resultado + percentil.
 * @param puzzles banco de puzzles (local-first): de aquí sale la plantilla de cada
 *   partida nueva. Sustituye a las plantillas que antes vivían en el `enum`
 *   [SudokuDifficulty] (ver [SudokuPuzzleRepository]).
 * @param savedGameState partida en curso guardada al salir (back / "SALIR"): la
 *   antesala la reanuda con [NeonSudokuIntent.Start]. 100% local (ver [requestExit]).
 * @param audio manager de sonido/háptica (el feedback fino se emite como [NeonSudokuEffect]).
 */
class NeonSudokuViewModel(
    private val progress: ProgressRepository,
    private val puzzles: SudokuPuzzleRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<NeonSudokuIntent, NeonSudokuUiState, NeonSudokuEffect>(NeonSudokuUiState()) {

    // --- Estado interno de la simulación (NO es estado de UI) --------------------
    // No se dibuja nada a partir de esto; meterlo en el State solo forzaría
    // recomposiciones inútiles (mismo criterio que NeonPulseViewModel).

    /** Corrutina del cronómetro; cancelable en pausa y al terminar. */
    private var loopJob: Job? = null

    /** Marca monotónica del último tick procesado (ver [TimeSource.Monotonic]:
     *  precisa, monótona e inmune a cambios de hora/zona horaria). */
    private var lastMark: TimeSource.Monotonic.ValueTimeMark? = null

    // Estadísticas para la precisión final (accuracy) del GameResult.
    private var totalInputs = 0      // dígitos definitivos escritos (no notas)
    private var conflictInputs = 0   // de esos, cuántos generaron un choque

    /** Si la segunda oportunidad (revivir con anuncio) ya se ofreció en esta
     *  partida: se limita a una por partida, como en "Burbujas de Cálculo". */
    private var reviveOffered = false

    /** Guardia de re-entrada mientras [startGame] resuelve un puzzle (asíncrono):
     *  evita que un doble toque en "Comenzar" arranque dos partidas a la vez. */
    private var startingPuzzle = false

    /** Dígitos (`1..9`) cuya celebración de "agotado" ([NeonSudokuEffect.DigitCompleted])
     *  ya se disparó en esta partida. Sin este control, reescribir una celda que
     *  ya tenía el dígito completo (redundante, pero el reducer lo permite) o
     *  cualquier otra recomputación repetiría los fuegos artificiales. */
    private val celebratedDigits = mutableSetOf<Int>()

    init {
        // La antesala ofrece "Continuar" (CTA principal, ver ResumeState) si hay
        // partida guardada: se observa (reactivo) para que el resumen desaparezca
        // solo al reanudar/terminar, igual que `savedScore` en Neon Grid 2048.
        savedGameState.observe(GameIds.NEON_SUDOKU_MATRIX)
            .onEach { json ->
                val summary = json
                    ?.let { runCatching { Json.decodeFromString<NeonSudokuSavedState>(it) }.getOrNull() }
                    ?.let(::describeSaved)
                setState { copy(savedSummary = summary) }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: NeonSudokuIntent) {
        when (intent) {
            NeonSudokuIntent.Start -> startGame(currentState.difficulty)
            NeonSudokuIntent.ResumeSaved -> resumeSaved()
            NeonSudokuIntent.PlayAgain -> startGame(currentState.difficulty)
            is NeonSudokuIntent.SelectDifficulty -> onSelectDifficulty(intent.difficulty)
            NeonSudokuIntent.Revive -> onRevive()
            NeonSudokuIntent.DeclineRevive -> finish(won = false)
            NeonSudokuIntent.Pause -> pause()
            NeonSudokuIntent.Resume -> resume()
            is NeonSudokuIntent.SelectCell -> onSelectCell(intent.row, intent.col)
            is NeonSudokuIntent.InputNumber -> onInputNumber(intent.number)
            NeonSudokuIntent.ToggleNotesMode -> onToggleNotesMode()
            NeonSudokuIntent.EraseCell -> onEraseCell()
            is NeonSudokuIntent.Tick -> onTick(intent.deltaMillis)
        }
    }

    // ---------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------

    /** Cambia la dificultad elegida en la antesala; no-op fuera de IDLE (cambiarla
     *  a mitad de partida no tiene sentido). Mismo criterio que `SelectBoardSize`
     *  en Neon Grid 2048. */
    private fun onSelectDifficulty(difficulty: SudokuDifficulty) {
        if (currentState.status != GameStatus.IDLE) return
        setState { copy(difficulty = difficulty) }
    }

    /**
     * Punto de entrada del CTA "Continuar" ([NeonSudokuIntent.ResumeSaved]): carga
     * la partida guardada y la consume (se borra, para que el próximo guardado sea
     * el de esta sesión). Mismo patrón que `ResumeSaved` en Neon Grid 2048.
     *
     * Si el guardado ya no está —se limpió desde otro sitio entre que la antesala
     * ofreció "Continuar" y que el jugador pulsó— cae a una partida nueva en vez de
     * dejar el botón sin reaccionar.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.NEON_SUDOKU_MATRIX)
                ?.let { runCatching { Json.decodeFromString<NeonSudokuSavedState>(it) }.getOrNull() }
            if (saved != null) {
                savedGameState.clear(GameIds.NEON_SUDOKU_MATRIX)
                resumeFrom(saved)
            } else {
                startGame(currentState.difficulty)
            }
        }
    }

    /** Restaura una partida guardada tal cual (tablero, contadores y flag de revivir). */
    private fun resumeFrom(saved: NeonSudokuSavedState) {
        totalInputs = saved.totalInputs
        conflictInputs = saved.conflictInputs
        reviveOffered = saved.reviveOffered
        val board = Board(saved.cells)
        // Los dígitos ya agotados en el guardado no deben volver a festejarse: se
        // reconstruye el set a partir del propio tablero en vez de serializarlo
        // aparte (es un dato derivado, no una fuente de verdad nueva).
        celebratedDigits.clear()
        for (digit in NeonSudokuConfig.MIN_DIGIT..NeonSudokuConfig.MAX_DIGIT) {
            if (isDigitComplete(board, digit)) celebratedDigits += digit
        }
        val selected = if (saved.selectedRow >= 0 && saved.selectedCol >= 0) {
            CellPosition(saved.selectedRow, saved.selectedCol)
        } else {
            null
        }
        setState {
            NeonSudokuUiState(
                board = board,
                selectedCell = selected,
                notesMode = saved.notesMode,
                errorCount = saved.errorCount,
                elapsedMs = saved.elapsedMs,
                difficulty = saved.difficulty,
                status = GameStatus.RUNNING,
            )
        }
        startLoop()
    }

    /**
     * Resumen legible de [saved] para el CTA "Continuar" de la antesala (ver
     * KDoc de [NeonSudokuUiState.savedSummary]): dificultad + progreso de
     * relleno, para que el jugador sepa qué retoma antes de pulsar.
     */
    private fun describeSaved(saved: NeonSudokuSavedState): String {
        val filled = saved.cells.count { it.value != null }
        return "${saved.difficulty.displayName} · $filled/${NeonSudokuConfig.CELL_COUNT} celdas"
    }

    /**
     * Pide un puzzle de [difficulty] al banco, reinicia todo y arranca una partida
     * limpia. Es **asíncrono** porque el banco es local-first ([SudokuPuzzleRepository]):
     * la primera lectura toca el recurso empaquetado; las siguientes salen de caché
     * en memoria (imperceptible). El guardia [startingPuzzle] descarta un segundo
     * "Comenzar" mientras el primero aún resuelve el puzzle.
     */
    private fun startGame(difficulty: SudokuDifficulty) {
        if (startingPuzzle) return
        startingPuzzle = true
        viewModelScope.launch {
            // try/finally: pase lo que pase al pedir el puzzle, el guardia se libera
            // para no dejar "Comenzar" bloqueado de forma permanente.
            try {
                val puzzle = puzzles.randomPuzzle(difficulty)
                totalInputs = 0
                conflictInputs = 0
                reviveOffered = false
                celebratedDigits.clear()
                // recomputeConflicts es defensivo: un puzzle bien formado nunca trae
                // choques entre sus pistas, pero recalcular es gratis (ver su KDoc) y nos
                // protege de un puzzle corrupto en vez de arrancar en un estado inválido.
                val board = recomputeConflicts(Board.fromTemplate(puzzle.puzzle))
                setState { NeonSudokuUiState(board = board, difficulty = difficulty, status = GameStatus.RUNNING) }
                startLoop()
            } finally {
                startingPuzzle = false
            }
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.example.kortexgames.ui.components.GameExitGuard] o "SALIR" del menú de
     * pausa): si hay una corrida en curso ([GameStatus.RUNNING] o [GameStatus.PAUSED])
     * la guarda antes de navegar atrás. En el resto de estados (antesala, fin de
     * partida, o mientras se decide revivir) no se guarda: [onExit] va directo.
     *
     * No se guarda durante [NeonSudokuUiState.awaitingRevive] a propósito: esa
     * partida ya está en su punto de derrota; persistirla dejaría al jugador
     * reanudar justo en el borde del game over, sin sentido.
     */
    fun requestExit(onExit: () -> Unit) {
        val s = currentState
        val inPlay = s.status == GameStatus.RUNNING || s.status == GameStatus.PAUSED
        if (!inPlay || s.awaitingRevive) {
            onExit()
            return
        }
        loopJob?.cancel()
        loopJob = null
        val saved = NeonSudokuSavedState(
            cells = s.board.allCells,
            selectedRow = s.selectedCell?.row ?: -1,
            selectedCol = s.selectedCell?.col ?: -1,
            notesMode = s.notesMode,
            errorCount = s.errorCount,
            elapsedMs = s.elapsedMs,
            difficulty = s.difficulty,
            reviveOffered = reviveOffered,
            totalInputs = totalInputs,
            conflictInputs = conflictInputs,
        )
        viewModelScope.launch {
            savedGameState.save(GameIds.NEON_SUDOKU_MATRIX, Json.encodeToString(saved))
            onExit()
        }
    }

    /** Congela el cronómetro y marca PAUSED. */
    private fun pause() {
        if (currentState.status != GameStatus.RUNNING) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.PAUSED) }
    }

    /** Reanuda tras pausa. Reinicia [lastMark] para que el primer delta NO
     *  incluya el tiempo que el juego estuvo pausado. */
    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        setState { copy(status = GameStatus.RUNNING) }
        startLoop()
    }

    /**
     * Cronómetro de partida: una sola corrutina en `viewModelScope` que mide el
     * delta real con [TimeSource.Monotonic] (nunca asume 16 ms exactos, ver
     * [com.example.kortexgames.game.neonpulse.NeonPulseViewModel.startLoop] para
     * el detalle de por qué) y lo emite como intención [NeonSudokuIntent.Tick].
     * `viewModelScope` despacha en `Main.immediate`, así que el tick y los
     * intents del jugador quedan confinados al mismo hilo y se serializan sin
     * necesidad de locks ni estructuras concurrentes.
     */
    private fun startLoop() {
        loopJob?.cancel()
        lastMark = TimeSource.Monotonic.markNow()
        loopJob = viewModelScope.launch {
            while (isActive) {
                delay(FRAME_MS)
                val delta = lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                lastMark = TimeSource.Monotonic.markNow()
                if (delta > 0L) onIntent(NeonSudokuIntent.Tick(delta))
            }
        }
    }

    private fun onTick(deltaMillis: Long) {
        if (currentState.status != GameStatus.RUNNING) return
        setState { copy(elapsedMs = elapsedMs + deltaMillis) }
    }

    // ---------------------------------------------------------------------------
    // Selección e input
    // ---------------------------------------------------------------------------

    private fun onSelectCell(row: Int, col: Int) {
        if (currentState.status != GameStatus.RUNNING) return
        setState { copy(selectedCell = CellPosition(row, col)) }
        sendEffect(NeonSudokuEffect.Vibrate.Tick)
    }

    /** Alterna el "Modo Notas". No modifica ninguna celda por sí solo. */
    private fun onToggleNotesMode() {
        if (currentState.status != GameStatus.RUNNING) return
        setState { copy(notesMode = !notesMode) }
        sendEffect(NeonSudokuEffect.PlaySound.Input)
    }

    /**
     * Escribe [number] en la celda seleccionada, o lo alterna como nota de lápiz
     * si [NeonSudokuUiState.notesMode] está activo. Ignora la pulsación (no-op)
     * si: no hay celda seleccionada, la celda es fija, o [number] está fuera de
     * `1..9` — este último es el único chequeo de rango porque [InputNumber]
     * cruza el límite UI → dominio (viene del `Numpad`, FASE 3) y un valor fuera
     * de rango ahí solo puede deberse a un bug de la UI, no a una jugada válida
     * que debamos rechazar con feedback.
     *
     * Al escribir un valor definitivo (fuera de Modo Notas) se recalculan los
     * choques de **todo el tablero** ([recomputeConflicts]): un solo dígito puede
     * resolver o crear conflictos en celdas que no son la tocada (p. ej. al
     * corregir un valor que antes chocaba). Solo se penaliza (choque + sonido de
     * error + sacudida) si la celda recién escrita queda marcada con choque.
     */
    private fun onInputNumber(number: Int) {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return
        if (number !in NeonSudokuConfig.MIN_DIGIT..NeonSudokuConfig.MAX_DIGIT) return
        val position = s.selectedCell ?: return
        val cell = s.board.cellAt(position)
        if (cell.isFixed) return

        if (s.notesMode) {
            // Las notas solo tienen sentido sobre celdas vacías (ver KDoc de
            // SudokuCell.hasNotes); sobre una celda ya rellena, se ignora.
            if (!cell.isEmpty) return
            val newNotes = if (number in cell.notes) cell.notes - number else cell.notes + number
            val newBoard = s.board.replacing(position, cell.copy(notes = newNotes))
            setState { copy(board = newBoard) }
            sendEffect(NeonSudokuEffect.PlaySound.Input)
            return
        }

        totalInputs++
        val written = cell.copy(value = number, notes = emptySet())
        val recomputedBoard = recomputeConflicts(s.board.replacing(position, written))
        val conflict = recomputedBoard.cellAt(position).hasConflict

        if (conflict) {
            conflictInputs++
            val newErrorCount = s.errorCount + 1
            setState { copy(board = recomputedBoard, errorCount = newErrorCount) }
            sendEffect(NeonSudokuEffect.PlaySound.Error)
            sendEffect(NeonSudokuEffect.Vibrate.Heavy)
            sendEffect(NeonSudokuEffect.ShakeCell(position))
            // Regla "3 strikes": al agotar los errores permitidos, o bien se ofrece
            // la segunda oportunidad (una vez por partida) o se pierde. Se comprueba
            // antes de la victoria porque una jugada que choca nunca completa bien.
            if (newErrorCount >= NeonSudokuConfig.MAX_ERRORS) {
                if (reviveOffered) finish(won = false) else offerRevive()
            }
            return
        }

        setState { copy(board = recomputedBoard) }

        // Celebración de unidad completada: pisa al feedback de escritura normal
        // (mismo criterio que Neon Grid 2048 con la fusión sobre el movimiento),
        // para no encadenar dos sonidos en 100 ms.
        val completedCells = completedUnitsAt(recomputedBoard, position)
        if (completedCells.isNotEmpty()) {
            sendEffect(NeonSudokuEffect.PlaySound.UnitComplete)
            sendEffect(NeonSudokuEffect.Vibrate.Success)
            sendEffect(NeonSudokuEffect.UnitsCompleted(completedCells, position))
        } else {
            sendEffect(NeonSudokuEffect.PlaySound.Input)
            sendEffect(NeonSudokuEffect.Vibrate.Tick)
        }

        // Dígito agotado: sus 9 apariciones ya están en el tablero sin chocar entre
        // sí, así que no queda ninguna instancia más por colocar. Es un hito de la
        // partida entera (no de una unidad puntual), así que la celebración es
        // aparte y más grande (fuegos artificiales de pantalla completa, ver
        // NeonSudokuScreen) en vez de sumarse al destello local de arriba.
        //
        // Solo puede volverse cierto para [number]: borrar SIEMPRE reduce el
        // recuento de un dígito (nunca lo completa) y sobrescribir una celda con
        // un valor distinto solo puede aumentar el recuento del dígito que se
        // ACABA de escribir, nunca el de un tercero que la jugada no tocó.
        if (number !in celebratedDigits && isDigitComplete(recomputedBoard, number)) {
            celebratedDigits += number
            sendEffect(NeonSudokuEffect.DigitCompleted(number))
        }

        if (recomputedBoard.isComplete && !recomputedBoard.hasAnyConflict) finish(won = true)
    }

    /**
     * ¿Ya no queda ninguna instancia de [digit] por colocar? Es decir: sus 9
     * apariciones están en el tablero y ninguna choca entre sí. Dispara la
     * celebración de "dígito agotado" ([NeonSudokuEffect.DigitCompleted]).
     */
    private fun isDigitComplete(board: Board, digit: Int): Boolean {
        val cells = board.cellsWithValue(digit)
        return cells.size == NeonSudokuConfig.BOARD_SIZE && cells.none { it.hasConflict }
    }

    /**
     * Celdas de las unidades (fila, columna y/o bloque 3x3) que la jugada sobre
     * [position] acaba de **completar**: llenas y sin ningún choque.
     *
     * Solo se miran las tres unidades que contienen [position]: son las únicas
     * que la jugada pudo cambiar. Y no hace falta comparar con el estado
     * anterior para saber si la compleción es *nueva*: antes de escribir, esa
     * celda estaba vacía, así que ninguna de sus tres unidades podía estar
     * completa. Cualquiera que lo esté ahora acaba de cerrarse con esta jugada.
     *
     * @return la unión sin repetidos de las celdas de las unidades completadas
     *   (una jugada puede cerrar fila y bloque a la vez, que comparten celdas);
     *   lista vacía si no se completó ninguna.
     */
    private fun completedUnitsAt(board: Board, position: CellPosition): List<CellPosition> {
        val units = listOf(
            board.cellsInRow(position.row),
            board.cellsInColumn(position.col),
            board.cellsInBlock(position.blockIndex),
        )
        return units
            .filter { unit -> unit.none { it.isEmpty || it.hasConflict } }
            .flatten()
            .map { it.position }
            .distinct()
    }

    /**
     * Ofrece la segunda oportunidad: congela el cronómetro (la partida sigue en
     * RUNNING, no FINISHED, para poder continuar sin recrear estado) y marca
     * [NeonSudokuUiState.awaitingRevive] para que la UI muestre el overlay de
     * anuncio compartido. Mismo patrón que "Burbujas de Cálculo".
     */
    private fun offerRevive() {
        loopJob?.cancel()
        loopJob = null
        setState { copy(awaitingRevive = true) }
    }

    /**
     * El jugador vio el anuncio: se le devuelve margen de errores
     * ([NeonSudokuConfig.REVIVE_ERROR_GRANT]) y la partida continúa. La oferta no
     * se repetirá ([reviveOffered] queda a `true`).
     */
    private fun onRevive() {
        if (!currentState.awaitingRevive) return
        reviveOffered = true
        setState {
            copy(
                awaitingRevive = false,
                errorCount = (NeonSudokuConfig.MAX_ERRORS - NeonSudokuConfig.REVIVE_ERROR_GRANT)
                    .coerceAtLeast(0),
            )
        }
        startLoop()
    }

    /** Borra valor y notas de la celda seleccionada. No-op sobre celdas fijas o
     *  ya vacías. Recalcula choques: borrar puede resolver el choque de OTRA
     *  celda de la misma fila/columna/bloque, no solo el de la propia. */
    private fun onEraseCell() {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return
        val position = s.selectedCell ?: return
        val cell = s.board.cellAt(position)
        if (cell.isFixed) return
        if (cell.value == null && cell.notes.isEmpty()) return

        val erased = cell.copy(value = null, notes = emptySet(), hasConflict = false)
        val recomputedBoard = recomputeConflicts(s.board.replacing(position, erased))
        setState { copy(board = recomputedBoard) }
        sendEffect(NeonSudokuEffect.PlaySound.Input)
    }

    // ---------------------------------------------------------------------------
    // Motor de validación de choques
    // ---------------------------------------------------------------------------

    /**
     * Recalcula [SudokuCell.hasConflict] para las 81 celdas del tablero.
     *
     * ## La estructura: conteos por grupo, no comparación por pares
     *
     * La forma ingenua sería, por cada celda rellena, comparar su valor contra
     * las otras 20 celdas de su fila+columna+bloque (`O(N)` por celda, `O(N²)`
     * sobre las 81) — y repetirlo en cada tecla pulsada.
     *
     * En su lugar se hace **una sola pasada** sobre el tablero construyendo tres
     * tablas de conteo `grupo -> (dígito -> nº de apariciones)`:
     * [rowCounts], [colCounts] y [blockCounts], cada una `Array(9) { IntArray(10) }`
     * (índice `0` de cada `IntArray` sin usar; los dígitos son `1..9`). Esa pasada
     * es `O(81)` = `O(N)`.
     *
     * Con las tablas ya construidas, decidir si la celda `(row, col)` con valor
     * `v` choca es una lectura directa — `O(1)` — en cada una de las tres tablas:
     * `rowCounts[row][v] > 1 || colCounts[col][v] > 1 || blockCounts[block][v] > 1`.
     * Una segunda pasada `O(81)` aplica esa lectura a las 81 celdas para construir
     * el tablero resultante. Total: `O(N)` con `N` = nº de celdas, en vez de
     * `O(N²)`, y sin más estructura que dos arrays de enteros por dimensión —
     * no hace falta un `Set`/`Map` por grupo ni recorrer vecinos celda a celda.
     *
     * Se recalculan las 81 celdas (y no solo la tocada) porque un choque es una
     * propiedad **relacional**: escribir o borrar un dígito puede resolver o
     * crear un choque en una celda que no es la que cambió (ver KDoc de
     * [onEraseCell]/[onInputNumber]).
     */
    private fun recomputeConflicts(board: Board): Board {
        val rowCounts = Array(NeonSudokuConfig.BOARD_SIZE) { IntArray(NeonSudokuConfig.BOARD_SIZE + 1) }
        val colCounts = Array(NeonSudokuConfig.BOARD_SIZE) { IntArray(NeonSudokuConfig.BOARD_SIZE + 1) }
        val blockCounts = Array(NeonSudokuConfig.BOARD_SIZE) { IntArray(NeonSudokuConfig.BOARD_SIZE + 1) }

        for (row in 0 until NeonSudokuConfig.BOARD_SIZE) {
            for (col in 0 until NeonSudokuConfig.BOARD_SIZE) {
                val value = board.cellAt(row, col).value ?: continue
                val block = CellPosition(row, col).blockIndex
                rowCounts[row][value]++
                colCounts[col][value]++
                blockCounts[block][value]++
            }
        }

        val newCells = (0 until NeonSudokuConfig.CELL_COUNT).map { index ->
            val row = index / NeonSudokuConfig.BOARD_SIZE
            val col = index % NeonSudokuConfig.BOARD_SIZE
            val cell = board.cellAt(row, col)
            val value = cell.value
            val block = CellPosition(row, col).blockIndex
            val conflict = value != null &&
                (rowCounts[row][value] > 1 || colCounts[col][value] > 1 || blockCounts[block][value] > 1)
            if (cell.hasConflict == conflict) cell else cell.copy(hasConflict = conflict)
        }
        return Board(newCells)
    }

    // ---------------------------------------------------------------------------
    // Fin de partida (local-first)
    // ---------------------------------------------------------------------------

    /**
     * Cierra la partida, detiene el cronómetro y persiste el resultado.
     * Idempotente: si ya está FINISHED no hace nada.
     *
     * @param won `true` si el tablero se completó bien (victoria); `false` si se
     *   perdió por agotar los errores. Solo la victoria dispara la onda de luz y
     *   el sonido de "nivel completado"; la derrota cierra sin celebración.
     */
    private fun finish(won: Boolean) {
        if (currentState.status == GameStatus.FINISHED) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.FINISHED, awaitingRevive = false) }

        val elapsed = currentState.elapsedMs
        val result = GameResult(
            gameId = GameIds.NEON_SUDOKU_MATRIX,
            // Una derrota no puntúa: el tablero quedó incompleto. Solo la victoria
            // aplica el baremo de precisión/velocidad de [calculateScore].
            score = if (won) calculateScore(elapsed, currentState.errorCount) else 0,
            completionTimeMs = elapsed,
            accuracyPercentage = if (totalInputs == 0) {
                100.0
            } else {
                (totalInputs - conflictInputs).toDouble() / totalInputs * 100.0
            },
            // difficultyLevel 1..5 a partir del nivel elegido (enum ordinal + 1).
            difficultyLevel = currentState.difficulty.ordinal + 1,
            reachedMetric = currentState.errorCount,
        )
        viewModelScope.launch {
            // Partida terminada: cualquier guardado pendiente es "fantasma" desde
            // aquí (ya se registró el resultado final), igual que en Neon Grid 2048.
            savedGameState.clear(GameIds.NEON_SUDOKU_MATRIX)
            val outcome = progress.saveResult(result)
            if (won) {
                audio.playSound(SoundEffect.LEVEL_UP)
                sendEffect(NeonSudokuEffect.SweepVictory)
            }
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }

    /** Puntaje final: [NeonSudokuConfig.BASE_SCORE] menos una penalización por
     *  cada choque cometido y por cada segundo transcurrido, sin bajar de 0.
     *  Recompensa precisión y velocidad sin que el reloj termine la partida. */
    private fun calculateScore(elapsedMs: Long, errorCount: Int): Int {
        val elapsedSeconds = elapsedMs / 1_000
        val penalty = errorCount * NeonSudokuConfig.ERROR_SCORE_PENALTY +
            elapsedSeconds * NeonSudokuConfig.TIME_SCORE_PENALTY_PER_SEC
        return (NeonSudokuConfig.BASE_SCORE - penalty).coerceAtLeast(0L).toInt()
    }

    private companion object {
        /** Periodo objetivo del cronómetro (~1 fps de resolución de reloj basta;
         *  se comparte el mismo mecanismo de delta real que Neon Pulse por
         *  consistencia, no por necesitar 60 fps aquí). */
        const val FRAME_MS = 200L
    }
}
