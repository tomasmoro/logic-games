package com.example.kortexgames.game.defuser

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
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * # Neon Defuser — Motor de juego (ViewModel, FASE 2)
 *
 * ViewModel MVI del Buscaminas. Al igual que
 * [com.example.kortexgames.game.neonsudoku.NeonSudokuViewModel], el **cronómetro
 * vive en el propio ViewModel** vía [DefuserIntent.Tick] (el reloj es la única
 * fuente que lo emite, manteniendo el ciclo MVI unidireccional puro). El
 * cronómetro NUNCA termina la partida por sí solo: solo alimenta el HUD y penaliza
 * el puntaje final ([calculateScore]).
 *
 * Concentra los tres algoritmos del juego (el "porqué" de cada uno está en el
 * KDoc del método correspondiente):
 *
 *  1. **Siembra de minas con primer toque seguro** ([armMines]): las minas NO se
 *     colocan al crear el panel, sino en el primer [DefuserIntent.RevealCell],
 *     excluyendo la celda tocada y sus 8 vecinas. Así el primer toque nunca
 *     explota y siempre abre una zona inicial.
 *  2. **Conteo de vecinas** ([computeAdjacencies]): cada celda cuenta minas entre
 *     sus hasta 8 vecinas, reutilizando la topología de [MineBoard.neighborsOf].
 *  3. **Cascada / flood fill** ([revealCascade]): revelar una celda con 0 minas
 *     adyacentes abre en cadena toda la región conectada de ceros y su borde.
 *
 * Responsabilidades adicionales: gestos (revelar / marcar escudo), detección de
 * victoria (todas las celdas seguras reveladas) y derrota (mina revelada) —con
 * una **segunda oportunidad** por partida al pisar una mina, viendo un anuncio
 * para neutralizarla y seguir jugando (mismo patrón que
 * [com.example.kortexgames.game.neonsudoku.NeonSudokuViewModel]: ver [onReveal]/
 * [onRevive])—, persistencia local-first del [GameResult], y guardado/reanudación
 * de la partida en curso ([SavedGameStateRepository]).
 *
 * @param progress repositorio local-first para guardar el resultado + percentil.
 * @param savedGameState partida en curso guardada al salir; la antesala la reanuda
 *   con [DefuserIntent.Start]. 100% local (ver [requestExit]).
 * @param audio manager de sonido/háptica (el feedback fino se emite como [DefuserEffect]).
 * @param random inyectable para pruebas deterministas de la siembra de minas.
 */
class DefuserViewModel(
    private val progress: ProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val random: Random = Random.Default,
) : MviViewModel<DefuserIntent, DefuserUiState, DefuserEffect>(DefuserUiState()) {

    // --- Estado interno de la simulación (NO es estado de UI) --------------------
    // No se dibuja nada a partir de esto; meterlo en el State solo forzaría
    // recomposiciones inútiles (mismo criterio que Neon Sudoku / Neon Pulse).

    /** Corrutina del cronómetro; cancelable en pausa y al terminar. */
    private var loopJob: Job? = null

    /** Marca monotónica del último tick procesado ([TimeSource.Monotonic]: precisa,
     *  monótona e inmune a cambios de hora/zona horaria). */
    private var lastMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** Si la segunda oportunidad (revivir con anuncio) ya se ofreció en esta
     *  partida: se limita a una por partida, como en Neon Sudoku. */
    private var reviveOffered = false

    /** Posición de la mina que disparó la oferta de revivir en curso; `null` fuera
     *  de [DefuserUiState.awaitingRevive]. La guarda [onReveal] al ofrecer y la
     *  consume [onRevive]/[onDeclineRevive] para saber qué celda neutralizar o
     *  terminar de exponer. */
    private var pendingMine: CellPosition? = null

    init {
        // La antesala ofrece "Continuar" si hay una partida guardada: se observa el
        // guardado (reactivo) para que el flag desaparezca solo al reanudar/terminar.
        savedGameState.observe(GameIds.NEON_DEFUSER)
            .onEach { saved -> setState { copy(hasSavedGame = saved != null) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: DefuserIntent) {
        when (intent) {
            DefuserIntent.Start -> startOrResume()
            DefuserIntent.RestartGame -> startGame(currentState.difficulty)
            is DefuserIntent.SelectDifficulty -> onSelectDifficulty(intent.difficulty)
            is DefuserIntent.RevealCell -> onReveal(intent.position)
            is DefuserIntent.ToggleFlag -> onToggleFlag(intent.position)
            DefuserIntent.Revive -> onRevive()
            DefuserIntent.DeclineRevive -> onDeclineRevive()
            DefuserIntent.RequestScan -> onRequestScan()
            DefuserIntent.ConfirmScan -> onConfirmScan()
            DefuserIntent.CancelScan -> onCancelScan()
            DefuserIntent.Pause -> pause()
            DefuserIntent.Resume -> resume()
            is DefuserIntent.Tick -> onTick(intent.deltaMillis)
        }
    }

    // ---------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------

    /** Cambia la dificultad elegida en la antesala; no-op fuera de IDLE (cambiarla
     *  a mitad de partida no tiene sentido). Al cambiarla se refresca el panel vacío
     *  para que la antesala previsualice el tamaño correcto. */
    private fun onSelectDifficulty(difficulty: MineDifficulty) {
        if (currentState.status != GameStatus.IDLE) return
        setState { copy(difficulty = difficulty, board = MineBoard.blank(difficulty)) }
    }

    /**
     * Punto de entrada de la antesala ("Comenzar"): reanuda la partida guardada si
     * existe (y la consume, para que el próximo guardado sea el de esta sesión), o
     * arranca una nueva de la dificultad elegida. Mismo patrón que Neon Sudoku.
     */
    private fun startOrResume() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.NEON_DEFUSER)
                ?.let { runCatching { Json.decodeFromString<DefuserSavedState>(it) }.getOrNull() }
            if (saved != null) {
                savedGameState.clear(GameIds.NEON_DEFUSER)
                resumeFrom(saved)
            } else {
                startGame(currentState.difficulty)
            }
        }
    }

    /** Restaura una partida guardada tal cual (panel con minas ya sembradas y
     *  cronómetro acumulado). Solo se relanza el reloj si las minas estaban
     *  armadas: un guardado sin armar no debería existir (ver [requestExit]), pero
     *  el chequeo lo hace robusto. */
    private fun resumeFrom(saved: DefuserSavedState) {
        reviveOffered = saved.reviveOffered
        pendingMine = null
        setState {
            DefuserUiState(
                board = saved.board,
                phase = MinePhase.PLAYING,
                status = GameStatus.RUNNING,
                minesArmed = saved.minesArmed,
                elapsedMs = saved.elapsedMs,
                difficulty = saved.difficulty,
                scanUsesRemaining = saved.scanUsesRemaining,
            )
        }
        if (saved.minesArmed) startLoop()
    }

    /**
     * Arranca una partida limpia de [difficulty]: panel vacío (sin minas todavía),
     * cronómetro a cero y minas **desarmadas**. El reloj NO se lanza aquí: como en
     * el Buscaminas clásico, el tiempo empieza a contar en el primer toque (cuando
     * [armMines] siembra el campo), no al entrar en la pantalla.
     */
    private fun startGame(difficulty: MineDifficulty) {
        loopJob?.cancel()
        loopJob = null
        reviveOffered = false
        pendingMine = null
        setState {
            DefuserUiState(
                board = MineBoard.blank(difficulty),
                difficulty = difficulty,
                status = GameStatus.RUNNING,
                minesArmed = false,
            )
        }
    }

    /**
     * Punto único de salida "en juego" (back del sistema o "SALIR" del menú de
     * pausa): si hay una partida **ya empezada** ([GameStatus.RUNNING]/`PAUSED` con
     * minas armadas) la guarda antes de navegar atrás. Si aún no se armó el campo
     * (nadie ha tocado nada) no se guarda: no hay progreso que preservar y recrear
     * el panel vacío es trivial desde la antesala.
     *
     * No se guarda durante [DefuserUiState.awaitingRevive] a propósito (mismo
     * criterio que Neon Sudoku): esa partida ya está en su punto de derrota;
     * persistirla dejaría al jugador reanudar justo en el borde del game over, con
     * la mina ya expuesta pero sin haber decidido revivir, sin sentido.
     */
    fun requestExit(onExit: () -> Unit) {
        val s = currentState
        val inPlay = s.status == GameStatus.RUNNING || s.status == GameStatus.PAUSED
        if (!inPlay || !s.minesArmed || s.awaitingRevive) {
            onExit()
            return
        }
        loopJob?.cancel()
        loopJob = null
        val saved = DefuserSavedState(
            board = s.board,
            minesArmed = s.minesArmed,
            elapsedMs = s.elapsedMs,
            difficulty = s.difficulty,
            reviveOffered = reviveOffered,
            scanUsesRemaining = s.scanUsesRemaining,
        )
        viewModelScope.launch {
            savedGameState.save(GameIds.NEON_DEFUSER, Json.encodeToString(saved))
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

    /** Reanuda tras pausa. Solo relanza el reloj si las minas estaban armadas (si
     *  el jugador pausó antes del primer toque, el tiempo sigue sin correr).
     *  Reiniciar [startLoop] pone [lastMark] a "ahora", así el primer delta no
     *  incluye el tiempo en pausa. */
    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        setState { copy(status = GameStatus.RUNNING) }
        if (currentState.minesArmed) startLoop()
    }

    /**
     * Cronómetro de partida: una sola corrutina en `viewModelScope` que mide el
     * delta real con [TimeSource.Monotonic] (nunca asume 16 ms exactos) y lo emite
     * como intención [DefuserIntent.Tick]. `viewModelScope` despacha en
     * `Main.immediate`, así que tick y gestos del jugador quedan confinados al mismo
     * hilo y se serializan sin locks (mismo mecanismo que Neon Sudoku).
     */
    private fun startLoop() {
        loopJob?.cancel()
        lastMark = TimeSource.Monotonic.markNow()
        loopJob = viewModelScope.launch {
            while (isActive) {
                delay(FRAME_MS)
                val delta = lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                lastMark = TimeSource.Monotonic.markNow()
                if (delta > 0L) onIntent(DefuserIntent.Tick(delta))
            }
        }
    }

    private fun onTick(deltaMillis: Long) {
        if (currentState.status != GameStatus.RUNNING) return
        setState { copy(elapsedMs = elapsedMs + deltaMillis) }
    }

    // ---------------------------------------------------------------------------
    // Gestos: revelar y marcar escudo
    // ---------------------------------------------------------------------------

    /**
     * **Tap corto** sobre [position]. Ignora (no-op) toques sobre celdas que no
     * estén ocultas: revelar una celda ya abierta no hace nada, y una celda con
     * escudo se protege deliberadamente de un revelado accidental (hay que quitar
     * el escudo antes) — salvo la mina neutralizada por [onRevive], que queda
     * marcada [MineCell.isDefused] y ni siquiera se puede desmarcar (ver
     * [onToggleFlag]), así que tampoco puede volver a revelarse.
     *
     * Flujo:
     *  1. Si es el **primer toque** de la partida, siembra las minas dejando esta
     *     celda segura ([armMines]) y arranca el cronómetro.
     *  2. Si la celda armada oculta una mina → explosión, y ofrece revivir
     *     ([onMineHit]) o termina en derrota si la segunda oportunidad ya se usó.
     *  3. Si es segura → cascada ([revealCascade]); si tras revelar no quedan
     *     celdas seguras ocultas, victoria.
     */
    private fun onReveal(position: CellPosition) {
        val s = currentState
        // Modo escáner: el toque inspecciona la celda en vez de jugarla (ver
        // [onScanReveal]). Se comprueba ANTES de los guardas de abajo porque durante
        // el escaneo la partida sigue en RUNNING y es justo cuando debe interceptar.
        if (s.scanning) {
            onScanReveal(position)
            return
        }
        // Se ignora el toque mientras se decide revivir o mientras corre el anuncio
        // del escáner: en ambos casos hay un anuncio/overlay por encima del tablero.
        if (s.status != GameStatus.RUNNING || s.awaitingRevive || s.awaitingScanAd) return
        val target = s.board.cellAt(position) ?: return
        if (target.state != MineCellState.HIDDEN) return

        // 1) Primer toque: sembrar minas (garantiza que 'position' sea segura) y
        //    arrancar el reloj. A partir de aquí trabajamos sobre 'workingBoard'.
        val firstTap = !s.minesArmed
        val workingBoard = if (firstTap) armMines(s.board, position) else s.board
        if (firstTap) {
            setState { copy(minesArmed = true) }
            startLoop()
        }

        // 2) ¿Mina? (imposible en el primer toque por construcción de armMines).
        val armedCell = workingBoard.cellAt(position) ?: return
        if (armedCell.hasMine) {
            onMineHit(workingBoard, position)
            return
        }

        // 3) Celda segura: cascada. El nº de celdas nuevas decide el sonido (una
        //    sola celda = tap; varias = onda de cascada).
        val revealedBoard = revealCascade(workingBoard, position)
        val newlyRevealed = revealedBoard.revealedCount - workingBoard.revealedCount
        setState { copy(board = revealedBoard) }
        sendEffect(if (newlyRevealed > 1) DefuserEffect.PlaySound.Cascade else DefuserEffect.PlaySound.Reveal)
        sendEffect(DefuserEffect.Vibrate.Light)

        if (revealedBoard.isCleared) win(revealedBoard)
    }

    /**
     * **Long press** sobre [position]: alterna un escudo (bandera). Solo aplica a
     * celdas ocultas o ya marcadas; sobre una celda revelada no hace nada. No
     * consume el "primer toque" (marcar no siembra minas ni arranca el reloj): el
     * jugador puede planear marcas antes de revelar nada.
     *
     * Excepción: una mina **neutralizada** al revivir ([MineCell.isDefused]) es un
     * escudo permanente y no se puede quitar. Si se pudiera desmarcar, el jugador
     * podría revelarla de nuevo y volvería a explotar — rompiendo la garantía de
     * "esta mina ya no puede matarte" que es justo el punto de la segunda
     * oportunidad.
     */
    private fun onToggleFlag(position: CellPosition) {
        val s = currentState
        // Sin escudos mientras corre el anuncio del escáner o se está eligiendo celda
        // a inspeccionar: en modo escáner el toque prolongado no debe marcar nada.
        if (s.status != GameStatus.RUNNING || s.awaitingRevive || s.awaitingScanAd || s.scanning) return
        val cell = s.board.cellAt(position) ?: return
        if (cell.state == MineCellState.REVEALED || cell.isDefused) return

        val toggled = cell.copy(
            state = if (cell.isFlagged) MineCellState.HIDDEN else MineCellState.FLAGGED,
        )
        setState { copy(board = board.replacing(toggled)) }
        sendEffect(DefuserEffect.PlaySound.Flag)
        sendEffect(DefuserEffect.Vibrate.Light)
    }

    // ---------------------------------------------------------------------------
    // Algoritmo 1 — Siembra de minas con primer toque seguro
    // ---------------------------------------------------------------------------

    /**
     * Siembra [MineDifficulty.mineCount] minas sobre [board] evitando la celda del
     * primer toque ([firstTap]) **y sus 8 vecinas**, y calcula las adyacencias.
     *
     * ## Por qué se excluye también la vecindad (no solo la celda tocada)
     * Si solo se protegiera la celda tocada, el primer toque podría revelar un "1"
     * o "2" y no abrir nada más, un arranque pobre. Al dejar libre el anillo de 8
     * vecinas, la celda tocada queda garantizada con **0 minas adyacentes**, así
     * que el primer toque siempre dispara una cascada y descubre una zona inicial
     * (comportamiento estándar del Buscaminas moderno). [MineDifficulty] valida en
     * su `init` que la densidad de minas permita reservar esa zona.
     *
     * ## Cómo se eligen las celdas minadas
     * Se toma el conjunto de coordenadas candidatas (todas menos la zona segura),
     * se baraja con el [random] inyectado (determinista en tests) y se cogen las
     * primeras [MineDifficulty.mineCount]. Barajar-y-tomar reparte las minas de
     * forma uniforme sin sesgo posicional y sin el bucle de "reintentar si ya había
     * mina" del muestreo ingenuo.
     */
    private fun armMines(board: MineBoard, firstTap: CellPosition): MineBoard {
        // Zona segura: la celda tocada + sus 8 vecinas. Las que caen fuera del panel
        // no molestan (no coincidirán con ninguna candidata).
        val safeZone = buildSet {
            add(firstTap)
            addAll(firstTap.neighbors())
        }
        val minePositions = board.cells
            .map { it.position }
            .filterNot { it in safeZone }
            .shuffled(random)
            .take(currentState.difficulty.mineCount)
            .toHashSet()

        val minedBoard = board.mapCells { cell ->
            if (cell.position in minePositions) cell.copy(hasMine = true) else cell
        }
        return computeAdjacencies(minedBoard)
    }

    /**
     * Algoritmo 2 — **conteo de vecinas**. Rellena [MineCell.adjacentMines] de cada
     * celda **segura** con el número de minas entre sus vecinas.
     *
     * La topología ya está resuelta por el modelo: [MineBoard.neighborsOf] devuelve
     * las vecinas reales (las 8 contiguas intersectadas con el panel, de modo que
     * bordes y esquinas devuelven 5 y 3 respectivamente) sin que aquí haya que
     * comprobar límites. Solo contamos cuántas de ellas son mina. Las celdas mina se
     * dejan intactas (su [MineCell.adjacentMines] no se muestra nunca). Se recorre
     * sobre `minedBoard` —ya con las minas puestas— para que `neighborsOf(...).hasMine`
     * refleje la siembra recién hecha.
     */
    private fun computeAdjacencies(minedBoard: MineBoard): MineBoard =
        minedBoard.mapCells { cell ->
            if (cell.hasMine) {
                cell
            } else {
                cell.copy(adjacentMines = minedBoard.neighborsOf(cell.position).count { it.hasMine })
            }
        }

    // ---------------------------------------------------------------------------
    // Algoritmo 3 — Cascada (flood fill)
    // ---------------------------------------------------------------------------

    /**
     * Revela [origin] y, si es una celda vacía (0 minas adyacentes), propaga la
     * revelación en cadena por toda la región conectada de ceros y su borde de
     * números — el clásico *flood fill* del Buscaminas.
     *
     * ## Reglas de propagación
     *  - Solo se cruzan celdas **ocultas**: las reveladas ya están hechas y las que
     *    tienen escudo se respetan (el flood fill NUNCA destapa una bandera del
     *    jugador; hay que quitarla a mano para revelar esa celda).
     *  - Solo las celdas con [MineCell.adjacentMines] `== 0` "expanden" hacia sus
     *    vecinas. Una celda numerada (borde de la región) se revela pero corta la
     *    propagación: es la pared de la cascada.
     *  - Ninguna mina entra en la cascada: por definición, un 0 no tiene minas
     *    vecinas, así que expandir desde ceros nunca alcanza una mina.
     *
     * ## Por qué iterativo (pila explícita) y no recursión de llamadas
     * Es el mismo algoritmo DFS que se suele describir de forma recursiva, pero se
     * implementa con una **pila explícita** ([ArrayDeque]): una región de ceros
     * grande puede encadenar cientos de celdas y la recursión de llamadas
     * arriesgaría un desbordamiento de pila en paneles amplios. La pila explícita
     * evita ese riesgo sin coste extra. El conjunto [toReveal] evita reprocesar
     * celdas y garantiza terminación.
     *
     * @return un nuevo [MineBoard] con todas las celdas alcanzadas puestas en
     *   [MineCellState.REVEALED] (el resto intacto, patrón inmutable).
     */
    private fun revealCascade(board: MineBoard, origin: CellPosition): MineBoard {
        val toReveal = HashSet<CellPosition>()
        val pending = ArrayDeque<CellPosition>()
        pending.addLast(origin)

        while (pending.isNotEmpty()) {
            val position = pending.removeLast()
            if (position in toReveal) continue
            val cell = board.cellAt(position) ?: continue
            // Solo se revela lo oculto: reveladas ya están, y las banderas se respetan.
            if (cell.state != MineCellState.HIDDEN) continue

            toReveal.add(position)

            // Una celda vacía (0) empuja a sus 8 vecinas; una numerada corta aquí.
            if (!cell.hasMine && cell.adjacentMines == 0) {
                for (neighbor in position.neighbors()) {
                    if (neighbor !in toReveal) pending.addLast(neighbor)
                }
            }
        }

        return board.mapCells { cell ->
            if (cell.position in toReveal) cell.copy(state = MineCellState.REVEALED) else cell
        }
    }

    // ---------------------------------------------------------------------------
    // Fin de partida (local-first)
    // ---------------------------------------------------------------------------

    /**
     * El jugador reveló una mina: explosión inmediata (foco del halo rojo, FASE 3,
     * + feedback fuerte de sonido/vibración) y, a partir de ahí, dos caminos:
     *
     *  - Si la segunda oportunidad **ya se usó** en esta partida ([reviveOffered]),
     *    la partida termina de verdad ([loseGame]).
     *  - Si no, se **ofrece revivir**: se congela el reloj y se marca
     *    [DefuserUiState.awaitingRevive] para que la UI muestre el overlay de
     *    anuncio, exactamente igual que Neon Sudoku. La partida sigue en
     *    [GameStatus.RUNNING] (no FINISHED) mientras se decide.
     *
     * Solo esta mina concreta se revela aquí; el resto del campo permanece oculto
     * porque, si el jugador acepta revivir, la partida continúa y exponer todas las
     * minas de golpe le regalaría la solución.
     */
    private fun onMineHit(board: MineBoard, mine: CellPosition) {
        val exploded = board.replacing(
            board.cellAt(mine)!!.copy(state = MineCellState.REVEALED, isDetonated = true),
        )
        setState { copy(board = exploded) }
        sendEffect(DefuserEffect.PlaySound.Explosion)
        sendEffect(DefuserEffect.Vibrate.Heavy)
        sendEffect(DefuserEffect.ExplodeAt(mine))

        if (reviveOffered) {
            loseGame(exploded)
        } else {
            pendingMine = mine
            loopJob?.cancel()
            loopJob = null
            setState { copy(awaitingRevive = true) }
        }
    }

    /**
     * El jugador aceptó ver el anuncio: la mina pendiente ([pendingMine]) queda
     * **neutralizada** —pasa a escudo permanente, [MineCell.isDefused]— y la
     * partida continúa. Se reutiliza [MineCellState.FLAGGED] en vez de inventar un
     * estado nuevo para heredar gratis su dibujo (tubo neón violeta) y su cómputo
     * en el HUD de minas restantes, exactamente como el resto de banderas.
     *
     * No se recalculan las adyacencias del resto del panel: los números ya
     * contaban esta mina cuando se generaron y **siguen contando**, igual que si
     * fuera una mina real sin detonar; solo cambia que esta celda concreta ya no
     * puede volver a explotar. Es la simplificación estándar de la mecánica de
     * "revivir" del Buscaminas: no se reescribe la partida, solo se perdona un
     * pisotón.
     */
    private fun onRevive() {
        if (!currentState.awaitingRevive) return
        val mine = pendingMine ?: return
        reviveOffered = true
        pendingMine = null
        val defused = currentState.board.replacing(
            currentState.board.cellAt(mine)!!.copy(
                state = MineCellState.FLAGGED,
                isDetonated = false,
                isDefused = true,
            ),
        )
        setState { copy(board = defused, awaitingRevive = false) }
        startLoop()
    }

    /** El jugador rechazó la segunda oportunidad (o se agotó su cuenta atrás en
     *  [com.example.kortexgames.ui.components.ReviveAdOverlay]): la partida termina
     *  de verdad. */
    private fun onDeclineRevive() {
        if (!currentState.awaitingRevive) return
        pendingMine = null
        setState { copy(awaitingRevive = false) }
        loseGame(currentState.board)
    }

    // ---------------------------------------------------------------------------
    // Escáner de minas (rewarded): inspeccionar una celda a elección
    // ---------------------------------------------------------------------------

    /**
     * El jugador pulsó el botón del escáner: solicita el anuncio recompensado que le
     * dará una inspección. Aquí solo se marca [DefuserUiState.awaitingScanAd] para que
     * la UI lance el rewarded (mismo patrón directo que la pista de Neon Sudoku, sin
     * overlay intermedio: pulsar el botón YA es la confirmación). Revalida
     * [DefuserUiState.canRequestScan] para que un doble toque o un estado inválido no
     * cuele una segunda petición. NO descuenta usos todavía: el uso se cobra solo si
     * el anuncio se ve completo ([onConfirmScan]).
     */
    private fun onRequestScan() {
        if (!currentState.canRequestScan) return
        setState { copy(awaitingScanAd = true) }
    }

    /**
     * El anuncio del escáner se vio completo: se descuenta un uso y se entra en **modo
     * selección** ([DefuserUiState.scanning]). A partir de aquí, el próximo
     * [DefuserIntent.RevealCell] sobre una celda oculta la inspecciona ([onScanReveal]).
     * El cronómetro sigue corriendo (igual que con la pista de Neon Sudoku): la ayuda
     * cuesta un anuncio, no congela la partida.
     */
    private fun onConfirmScan() {
        if (!currentState.awaitingScanAd) return
        setState {
            copy(
                awaitingScanAd = false,
                scanning = true,
                scanUsesRemaining = (scanUsesRemaining - 1).coerceAtLeast(0),
            )
        }
        sendEffect(DefuserEffect.PlaySound.ScanArmed)
        sendEffect(DefuserEffect.Vibrate.Light)
    }

    /**
     * Cancela el escáner. Cubre dos casos con la misma limpieza de estado:
     *  - El anuncio se cerró antes de recompensar o no había disponible: se estaba en
     *    [DefuserUiState.awaitingScanAd] y **no** se llegó a descontar uso.
     *  - El jugador abandonó el modo selección tras ver el anuncio: se estaba en
     *    [DefuserUiState.scanning] y el uso ya se gastó (el anuncio se vio); no se
     *    reembolsa, porque la recompensa —la inspección— ya se concedió aunque no la
     *    aprovechara.
     */
    private fun onCancelScan() {
        if (!currentState.awaitingScanAd && !currentState.scanning) return
        setState { copy(awaitingScanAd = false, scanning = false) }
    }

    /**
     * Inspecciona la celda [position] elegida por el jugador en modo escáner. Solo
     * actúa sobre celdas **ocultas** (una ya revelada no tiene nada que inspeccionar,
     * y una con escudo ya es una sospecha del propio jugador): un toque sobre otra
     * cosa se ignora y el modo selección sigue activo para reintentar.
     *
     * Dos desenlaces, ambos cierran el modo selección:
     *  - **Mina:** queda neutralizada —escudo permanente [MineCell.isDefused]— con la
     *    misma mecánica que [onRevive] (no puede volver a explotar ni desmarcarse),
     *    así el jugador convierte un anuncio en una amenaza retirada del tablero.
     *  - **Segura:** se abre con la cascada normal ([revealCascade]); si con eso se
     *    despejan todas las celdas seguras, es victoria, igual que un toque normal.
     */
    private fun onScanReveal(position: CellPosition) {
        val target = currentState.board.cellAt(position)
        if (target == null || target.state != MineCellState.HIDDEN) return

        setState { copy(scanning = false) }

        if (target.hasMine) {
            // Misma neutralización que revivir: escudo permanente que ya no mata.
            val defused = currentState.board.replacing(
                target.copy(state = MineCellState.FLAGGED, isDefused = true),
            )
            setState { copy(board = defused) }
            sendEffect(DefuserEffect.PlaySound.ScanMine)
            sendEffect(DefuserEffect.Vibrate.Medium)
        } else {
            val revealedBoard = revealCascade(currentState.board, position)
            val newlyRevealed = revealedBoard.revealedCount - currentState.board.revealedCount
            setState { copy(board = revealedBoard) }
            sendEffect(if (newlyRevealed > 1) DefuserEffect.PlaySound.Cascade else DefuserEffect.PlaySound.Reveal)
            sendEffect(DefuserEffect.Vibrate.Light)
            if (revealedBoard.isCleared) win(revealedBoard)
        }
    }

    /**
     * Derrota definitiva: **revela todas las minas** restantes para que el jugador
     * vea el campo completo (la mina que detonó ya está revelada desde
     * [onMineHit]) y cierra la partida.
     */
    private fun loseGame(board: MineBoard) {
        val exposed = board.mapCells { cell ->
            if (cell.hasMine && cell.state != MineCellState.REVEALED) {
                cell.copy(state = MineCellState.REVEALED)
            } else {
                cell
            }
        }
        setState { copy(board = exposed, phase = MinePhase.LOST) }
        finish(won = false)
    }

    /**
     * El jugador despejó todas las celdas seguras: victoria. Marca con escudo las
     * minas que queden sin marcar (cierre visual: el panel queda "desactivado") y
     * cierra la partida con celebración.
     */
    private fun win(board: MineBoard) {
        val secured = board.mapCells { cell ->
            if (cell.hasMine && !cell.isFlagged) cell.copy(state = MineCellState.FLAGGED) else cell
        }
        setState { copy(board = secured, phase = MinePhase.WON) }
        finish(won = true)
    }

    /**
     * Cierra la partida, detiene el cronómetro y persiste el resultado
     * (local-first). Idempotente: si ya está FINISHED no hace nada. Solo la
     * victoria puntúa y celebra (onda de luz + sonido de nivel completado); la
     * derrota registra un resultado de 0 puntos (cuenta la partida jugada, sin
     * celebración — el feedback de la explosión ya lo dio [onMineHit]).
     *
     * @param won `true` si se despejó el campo; `false` si se pisó una mina.
     */
    private fun finish(won: Boolean) {
        if (currentState.status == GameStatus.FINISHED) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.FINISHED) }

        val elapsed = currentState.elapsedMs
        val result = GameResult(
            gameId = GameIds.NEON_DEFUSER,
            score = if (won) calculateScore(elapsed) else 0,
            completionTimeMs = elapsed,
            // Buscaminas se juega a "un error y fuera": la precisión útil es binaria
            // (despejaste o explotaste), así que 100% al ganar y 0% al perder.
            accuracyPercentage = if (won) 100.0 else 0.0,
            // difficultyLevel 1..N a partir del nivel elegido (enum ordinal + 1).
            difficultyLevel = currentState.difficulty.ordinal + 1,
        )
        viewModelScope.launch {
            // Partida terminada: cualquier guardado pendiente es "fantasma" desde
            // aquí (ya se registró el resultado final), igual que en Neon Sudoku.
            savedGameState.clear(GameIds.NEON_DEFUSER)
            val outcome = progress.saveResult(result)
            if (won) {
                audio.playSound(SoundEffect.LEVEL_UP)
                sendEffect(DefuserEffect.VictoryFireworks)
            }
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }

    /** Puntaje final de una victoria: [DefuserConfig.BASE_SCORE] menos una
     *  penalización por cada segundo transcurrido, sin bajar de 0. Recompensa
     *  desactivar rápido sin que el reloj pueda terminar la partida. */
    private fun calculateScore(elapsedMs: Long): Int {
        val elapsedSeconds = elapsedMs / 1_000
        val penalty = elapsedSeconds * DefuserConfig.TIME_SCORE_PENALTY_PER_SEC
        return (DefuserConfig.BASE_SCORE - penalty).coerceAtLeast(0L).toInt()
    }

    private companion object {
        /** Periodo objetivo del cronómetro (~5 fps de resolución de reloj basta para
         *  el HUD de tiempo; se comparte el mecanismo de delta real de Neon Sudoku
         *  por consistencia). */
        const val FRAME_MS = 200L
    }
}
