package com.kortexgames.app.game.neonsudoku

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
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
 * ViewModel MVI del Sudoku 9x9. Igual que [com.kortexgames.app.game.neonpulse.NeonPulseViewModel],
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
 *  - **Validar cada celda** comparándola contra la solución del puzzle
 *    ([solutionDigitAt]) al escribir un valor definitivo.
 *  - Gestionar la regla de errores: a [NeonSudokuConfig.MAX_ERRORS] ofrecer la
 *    segunda oportunidad (anuncio) una vez, o terminar en derrota.
 *  - Ofrecer **pistas** ([NeonSudokuIntent.RequestHint]): a cambio de un anuncio
 *    recompensado, revela el dígito de la solución en la celda elegida por el
 *    jugador (tantas veces por partida como anuncios vea).
 *  - Detectar la victoria (tablero completo sin errores) y persistir el
 *    [GameResult] con estrategia local-first.
 *  - **Guardar/reanudar** la partida en curso al salir (mismo mecanismo que Neon
 *    Grid 2048, vía [SavedGameStateRepository]).
 *
 * ## Validación contra la solución, no contra duplicados
 * La primera versión marcaba [SudokuCell.hasConflict] con la regla clásica de
 * Sudoku: un valor "choca" si se repite en su fila, columna o bloque 3x3. El
 * problema es que esa regla es **relacional y tardía**: un dígito mal colocado en
 * una celda que aún no tiene ningún vecino repetido no se marca hasta que el
 * jugador completa el resto del grupo con los valores correctos — en ese punto ya
 * jugó varias celdas más y el error real puede quedar enterrado varios movimientos
 * atrás, obligándolo a peinar el tablero entero para encontrarlo.
 *
 * Como el banco de puzzles ya trae la solución única calculada offline
 * ([SudokuPuzzle.solution]), no hace falta esa regla: [solutionDigitAt] compara
 * cada valor escrito directamente contra su dígito correcto. Es a la vez más
 * simple (sin conteos por fila/columna/bloque, sin recorrer el tablero completo
 * en cada tecla) y da feedback inmediato y localizado — el jugador nunca tiene
 * que deshacer partidas enteras para corregir un solo número.
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

    /** Solución del puzzle en curso (81 dígitos `1-9`, fila a fila), la misma
     *  cadena de [SudokuPuzzle.solution]. Fuente de verdad de [solutionDigitAt]:
     *  vive aquí (no en el [NeonSudokuUiState]) porque es un dato de simulación
     *  que la UI no dibuja directamente, igual que [totalInputs]. Se restaura al
     *  reanudar una partida guardada (ver [resumeFrom] y [NeonSudokuSavedState.solution]). */
    private var solution: String = ""

    // Estadísticas para la precisión final (accuracy) del GameResult.
    private var totalInputs = 0      // dígitos definitivos escritos (no notas)
    private var conflictInputs = 0   // de esos, cuántos generaron un choque

    /** Si la segunda oportunidad (revivir con anuncio) ya se ofreció en esta
     *  partida: se limita a una por partida, como en "Burbujas de Cálculo". */
    private var reviveOffered = false

    /** Pistas gastadas en la partida. No hay tope de uso (a diferencia del escáner
     *  del Buscaminas), así que es el CASTIGO en puntos el único freno que impide
     *  escalar en la tabla mundial a base de anuncios; ver [calculateScore]. Vive
     *  aquí y no en el estado de UI porque la pantalla no lo dibuja, igual que
     *  [totalInputs], pero SÍ se persiste ([NeonSudokuSavedState.hintsUsed]) para
     *  que salir y reanudar no borre lo ya gastado. */
    private var hintsUsed = 0

    /** Celda pedida como pista al pulsar "Pista" ([onRequestHint]), capturada en
     *  el momento de la pulsación. A diferencia de [awaitingRevive], el anuncio
     *  de la pista NO bloquea el tablero (se lanza directo, sin diálogo de
     *  confirmación — ver `NeonSudokuScreen`), así que el jugador puede seguir
     *  tocando/escribiendo mientras se resuelve. Guardar la celda aquí (en vez de
     *  releer [NeonSudokuUiState.selectedCell] al resolver el anuncio) evita que
     *  un cambio de selección durante la espera revele el número en una celda
     *  distinta de la que el jugador realmente eligió. */
    private var hintTargetPosition: CellPosition? = null

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
            NeonSudokuIntent.RequestHint -> onRequestHint()
            NeonSudokuIntent.ConfirmHint -> onConfirmHint()
            NeonSudokuIntent.CancelHint -> onCancelHint()
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
        hintsUsed = saved.hintsUsed
        solution = saved.solution
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
                hintsUsed = 0
                celebratedDigits.clear()
                solution = puzzle.solution
                // Sin recompute defensivo: las pistas fijas de la plantilla nacen con
                // hasConflict = false (ver Board.fromTemplate) y, al no comparase entre
                // sí sino contra `solution` (ver KDoc de clase), no hay ningún estado
                // relacional que recalcular al arrancar.
                val board = Board.fromTemplate(puzzle.puzzle)
                setState { NeonSudokuUiState(board = board, difficulty = difficulty, status = GameStatus.RUNNING) }
                startLoop()
            } finally {
                startingPuzzle = false
            }
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.kortexgames.app.ui.components.GameExitGuard] o "SALIR" del menú de
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
            hintsUsed = hintsUsed,
            totalInputs = totalInputs,
            conflictInputs = conflictInputs,
            solution = solution,
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
     * [com.kortexgames.app.game.neonpulse.NeonPulseViewModel.startLoop] para
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
     * Al escribir un valor definitivo (fuera de Modo Notas) se compara
     * directamente contra [solutionDigitAt] (ver KDoc de clase: por qué no se
     * valida por duplicados de fila/columna/bloque). Solo se penaliza (choque +
     * sonido de error + sacudida) si el dígito escrito no es el correcto para
     * esa celda; un acierto delega la celebración/victoria en [applyCorrectPlacement].
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
        val correct = number == solutionDigitAt(position)
        val written = cell.copy(value = number, notes = emptySet(), hasConflict = !correct)
        val newBoard = s.board.replacing(position, written)

        if (!correct) {
            conflictInputs++
            val newErrorCount = s.errorCount + 1
            setState { copy(board = newBoard, errorCount = newErrorCount) }
            sendEffect(NeonSudokuEffect.PlaySound.Error)
            sendEffect(NeonSudokuEffect.Vibrate.Heavy)
            sendEffect(NeonSudokuEffect.ShakeCell(position))
            // Regla "3 strikes": al agotar los errores permitidos, o bien se ofrece
            // la segunda oportunidad (una vez por partida) o se pierde. Se comprueba
            // antes de la victoria porque una jugada errónea nunca completa bien.
            if (newErrorCount >= NeonSudokuConfig.MAX_ERRORS) {
                if (reviveOffered) finish(won = false) else offerRevive()
            }
            return
        }

        applyCorrectPlacement(newBoard, position, number)
    }

    /**
     * Dígito `1..9` de la solución del puzzle en [position]. Única lectura de
     * [solution] del ViewModel: tanto [onInputNumber] (¿el dígito escrito es el
     * correcto?) como [onConfirmHint] (¿cuál es el correcto, para revelarlo?)
     * pasan por aquí.
     */
    private fun solutionDigitAt(position: CellPosition): Int =
        solution[position.row * NeonSudokuConfig.BOARD_SIZE + position.col].digitToInt()

    /**
     * Aplica a [board] un dígito ya confirmado como **correcto** en [position]
     * (venga de que el jugador lo escribió bien en [onInputNumber], o de que una
     * pista lo reveló en [onConfirmHint]) y dispara el mismo feedback/celebración
     * en ambos casos: no hay motivo para que el juego reaccione distinto a un
     * acierto según de dónde vino el dígito.
     */
    private fun applyCorrectPlacement(newBoard: Board, position: CellPosition, digit: Int) {
        setState { copy(board = newBoard) }

        // Celebración de unidad completada: pisa al feedback de acierto normal
        // (mismo criterio que Neon Grid 2048 con la fusión sobre el movimiento),
        // para no encadenar dos sonidos en 100 ms. Comparten familia sonora —ambos
        // son un acierto—, y lo que marca el escalón es la háptica (medio → patrón
        // de logro) más la onda visual que solo dispara la unidad completada.
        val completedCells = completedUnitsAt(newBoard, position)
        if (completedCells.isNotEmpty()) {
            sendEffect(NeonSudokuEffect.PlaySound.UnitComplete)
            sendEffect(NeonSudokuEffect.Vibrate.Success)
            sendEffect(NeonSudokuEffect.UnitsCompleted(completedCells, position))
        } else {
            sendEffect(NeonSudokuEffect.PlaySound.Correct)
            sendEffect(NeonSudokuEffect.Vibrate.Correct)
        }

        // Dígito agotado: sus 9 apariciones ya están en el tablero y ninguna es
        // errónea, así que no queda ninguna instancia más por colocar. Es un hito
        // de la partida entera (no de una unidad puntual), así que la celebración
        // es aparte y más grande (onda expansiva sobre las 9 apariciones + fuegos
        // artificiales de pantalla completa, ver NeonSudokuScreen) en vez de
        // sumarse al destello local de arriba.
        //
        // Solo puede volverse cierto para [digit]: borrar SIEMPRE reduce el
        // recuento de un dígito (nunca lo completa) y escribir/revelar una celda
        // solo puede aumentar el recuento del dígito que se ACABA de colocar,
        // nunca el de un tercero que la jugada no tocó.
        if (digit !in celebratedDigits && isDigitComplete(newBoard, digit)) {
            celebratedDigits += digit
            sendEffect(
                NeonSudokuEffect.DigitCompleted(
                    digit = digit,
                    // Las 9 celdas se resuelven aquí, contra el tablero recién
                    // escrito, para que la onda ilumine exactamente el estado que
                    // provocó el hito (ver KDoc del efecto).
                    cells = newBoard.cellsWithValue(digit).map { it.position },
                    origin = position,
                ),
            )
        }

        if (newBoard.isComplete && !newBoard.hasAnyConflict) finish(won = true)
    }

    /**
     * ¿Ya no queda ninguna instancia de [digit] por colocar? Es decir: sus 9
     * apariciones están en el tablero y todas coinciden con la solución (ninguna
     * está mal colocada). Dispara la celebración de "dígito agotado"
     * ([NeonSudokuEffect.DigitCompleted]).
     */
    private fun isDigitComplete(board: Board, digit: Int): Boolean {
        val cells = board.cellsWithValue(digit)
        return cells.size == NeonSudokuConfig.BOARD_SIZE && cells.none { it.hasConflict }
    }

    /**
     * Celdas de las unidades (fila, columna y/o bloque 3x3) que la jugada sobre
     * [position] acaba de **completar**: llenas y sin ningún valor erróneo.
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
     *  ya vacías. A diferencia de la primera versión, borrar ya NO puede afectar
     *  a ninguna otra celda: con la validación contra [solution] (ver KDoc de
     *  clase) la corrección de cada celda es independiente de sus vecinas, así
     *  que basta con limpiar la propia. */
    private fun onEraseCell() {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return
        val position = s.selectedCell ?: return
        val cell = s.board.cellAt(position)
        if (cell.isFixed) return
        if (cell.value == null && cell.notes.isEmpty()) return

        val erased = cell.copy(value = null, notes = emptySet(), hasConflict = false)
        setState { copy(board = s.board.replacing(position, erased)) }
        sendEffect(NeonSudokuEffect.PlaySound.Input)
    }

    // ---------------------------------------------------------------------------
    // Pista (anuncio recompensado)
    // ---------------------------------------------------------------------------

    /**
     * El jugador pulsó "Pista": si [NeonSudokuUiState.hintAvailable] es `true`
     * captura la celda elegida ([hintTargetPosition]), congela el cronómetro
     * (igual que [offerRevive]) y marca [NeonSudokuUiState.awaitingHint] para que
     * la UI lance el anuncio recompensado DIRECTAMENTE — a diferencia de
     * "revivir", pulsar el botón ya es la confirmación del jugador, así que no
     * hay diálogo de por medio (ver `NeonSudokuScreen`). Fuera de esas
     * condiciones, no-op — la UI ya debería tener el botón deshabilitado (ver
     * KDoc del intent).
     */
    private fun onRequestHint() {
        val s = currentState
        if (!s.hintAvailable) return
        hintTargetPosition = s.selectedCell
        loopJob?.cancel()
        loopJob = null
        setState { copy(awaitingHint = true) }
    }

    /**
     * El anuncio se vio completo: revela en [hintTargetPosition] (la celda
     * capturada al pedir la pista, NO [NeonSudokuUiState.selectedCell] — el
     * tablero sigue interactivo mientras se resuelve el anuncio, así que la
     * selección pudo cambiar mientras tanto) el dígito de [solutionDigitAt], y
     * reutiliza [applyCorrectPlacement] para que una pista dispare exactamente
     * el mismo feedback/celebración/chequeo de victoria que acertar a mano.
     *
     * Vuelve a comprobar la celda contra el tablero ACTUAL antes de revelar
     * nada: si el jugador la corrigió (o la sobrescribió) por su cuenta mientras
     * esperaba el anuncio, ya no hay nada que revelar ahí.
     *
     * Siempre reanuda el cronómetro salvo que la pista complete el tablero: en
     * ese caso [applyCorrectPlacement] ya llamó a [finish], que lo dejó parado.
     */
    private fun onConfirmHint() {
        if (!currentState.awaitingHint) return
        setState { copy(awaitingHint = false) }

        val position = hintTargetPosition
        hintTargetPosition = null
        if (position != null) {
            val s = currentState
            val cell = s.board.cellAt(position)
            if (!cell.isFixed && (cell.value == null || cell.hasConflict)) {
                val digit = solutionDigitAt(position)
                val revealed = cell.copy(value = digit, notes = emptySet(), hasConflict = false)
                val newBoard = s.board.replacing(position, revealed)
                // Se cuenta aquí, dentro del if, y no al pedir la pista: si el jugador
                // resolvió la celda por su cuenta mientras corría el anuncio no se
                // revela nada, así que tampoco hay ayuda que cobrar en el puntaje.
                hintsUsed++
                // El feedback lo emite [applyCorrectPlacement] (acierto o unidad
                // completada). Antes se disparaba también aquí un sonido propio de
                // pista, pero desde que colocar bien un dígito suena a acierto serían
                // dos sonidos idénticos encadenados.
                applyCorrectPlacement(newBoard, position, digit)
            }
        }

        if (currentState.status == GameStatus.RUNNING) startLoop()
    }

    /** El anuncio de la pista se cerró antes de terminar, o no había disponible:
     *  no revela nada y reanuda el cronómetro. */
    private fun onCancelHint() {
        if (!currentState.awaitingHint) return
        hintTargetPosition = null
        setState { copy(awaitingHint = false) }
        startLoop()
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
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }

    /**
     * Puntaje final: [NeonSudokuConfig.BASE_SCORE] menos los choques, el tiempo y
     * **las ayudas por anuncio** (pistas y revivir), sin bajar de 0.
     *
     * Las ayudas restan porque son exactamente lo que el ranking mundial pretende
     * medir: una pista resuelve por ti la celda que costaba deducir. Como las pistas
     * NO tienen tope de uso, sin castigo la forma óptima de encabezar la tabla sería
     * rellenar el tablero a base de anuncios. Los importes y su calibración están en
     * [NeonSudokuConfig.HINT_SCORE_PENALTY] y [NeonSudokuConfig.REVIVE_SCORE_PENALTY].
     *
     * Ambos contadores se persisten en [NeonSudokuSavedState], así que salir y
     * reanudar no limpia las ayudas ya cobradas.
     */
    private fun calculateScore(elapsedMs: Long, errorCount: Int): Int {
        val elapsedSeconds = elapsedMs / 1_000
        val penalty = errorCount * NeonSudokuConfig.ERROR_SCORE_PENALTY +
            elapsedSeconds * NeonSudokuConfig.TIME_SCORE_PENALTY_PER_SEC +
            hintsUsed.toLong() * NeonSudokuConfig.HINT_SCORE_PENALTY +
            (if (reviveOffered) NeonSudokuConfig.REVIVE_SCORE_PENALTY.toLong() else 0L)
        return (NeonSudokuConfig.BASE_SCORE - penalty).coerceAtLeast(0L).toInt()
    }

    private companion object {
        /** Periodo objetivo del cronómetro (~1 fps de resolución de reloj basta;
         *  se comparte el mismo mecanismo de delta real que Neon Pulse por
         *  consistencia, no por necesitar 60 fps aquí). */
        const val FRAME_MS = 200L
    }
}
