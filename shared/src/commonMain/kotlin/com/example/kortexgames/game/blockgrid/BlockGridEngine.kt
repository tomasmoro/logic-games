package com.example.kortexgames.game.blockgrid

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.ResumableGameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * # Motor de "Neon Block Grid"
 *
 * Toda la lógica de reglas vive aquí, pura y sin UI: colocar piezas, detectar y
 * romper líneas simultáneas, puntuar y decidir el Game Over. El ViewModel solo
 * traduce intents→llamadas y eventos→efectos sensoriales.
 *
 * Decisiones clave:
 *  - **Feedback vía eventos de dominio** ([BlockGridEvent]), no llamadas
 *    directas a audio (mismo esquema que Tornillos Neón): la tabla
 *    evento→(sonido, háptica) queda centralizada y auditable en el ViewModel.
 *  - **[Random] inyectable**: la mano se genera al azar; con semilla fija los
 *    tests reproducen partidas deterministas sin tocar el motor.
 *  - **Limpieza en dos tiempos**: al completarse, las líneas pasan a
 *    [BoardCell.Clearing] (la UI las anima) y solo se vacían cuando la UI
 *    confirma con [onLineClearFinished]. El motor no necesita temporizadores y
 *    el jugador puede seguir colocando mientras tanto (Clearing cuenta libre).
 */
class BlockGridEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    private val random: Random = Random.Default,
) : BaseGameEngine<BlockGridGameState>(
    gameId = GameIds.NEON_BLOCK_GRID,
    difficulty = 1,
    scope = scope,
    audio = audio,
), ResumableGameEngine<BlockGridGameState> {

    private val _state = MutableStateFlow(BlockGridGameState())
    override val state: StateFlow<BlockGridGameState> = _state.asStateFlow()

    private val _events = Channel<BlockGridEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<BlockGridEvent> = _events.receiveAsFlow()

    /** Id incremental de pieza: distingue dos piezas iguales en la mano. */
    private var nextPieceId = 0

    /** Intentos de colocación (drops) y aciertos, para la precisión del resultado. */
    private var dropAttempts = 0
    private var dropsPlaced = 0

    /** Estado a restaurar en el próximo [onStart]; lo consume [resumeFrom]. */
    private var pendingResume: BlockGridGameState? = null

    /**
     * true una vez que el jugador **usó** la segunda oportunidad (vio el anuncio y
     * revivió) en esta sesión de juego. La oferta de revivir es de **una sola vez**:
     * tras consumirla no se vuelve a ofrecer, ni siquiera al empezar una partida nueva
     * ("Jugar de nuevo"). Por eso **NO** se reinicia en [onStart] — persiste mientras
     * viva el motor (la sesión en la pantalla). Rechazar la oferta NO la consume (el
     * jugador no vio el anuncio), así que una partida posterior puede volver a ofrecerla.
     */
    private var reviveConsumed = false

    override fun onStart() {
        val resume = pendingResume
        if (resume != null) {
            // nextPieceId se continúa DESPUÉS del mayor id de la mano guardada: si se
            // reiniciara a 0, la próxima mano repartiría ids que ya usa una pieza
            // reanudada, rompiendo la identidad `key(piece.id)` de la UI (dos piezas
            // con el mismo id). Los contadores de precisión sí se reinician (cuentan
            // desde la reanudación), como el resto de juegos reanudables.
            nextPieceId = (resume.hand.maxOfOrNull { it.id } ?: -1) + 1
            dropAttempts = 0
            dropsPlaced = 0
            _state.value = resume
            pendingResume = null
        } else {
            nextPieceId = 0
            dropAttempts = 0
            dropsPlaced = 0
            _state.value = BlockGridGameState(hand = dealHand())
        }
    }

    /**
     * Reanuda una corrida guardada al salir en vez de repartir mano nueva: adopta el
     * [saved] tal cual y delega en [start] para que el ciclo de vida sea idéntico al
     * de una partida normal. La restauración del contador de ids ocurre en [onStart].
     */
    override fun resumeFrom(saved: BlockGridGameState) {
        pendingResume = saved
        start()
    }

    /**
     * Fantasma de colocación para la pieza [pieceId] anclada en ([row], [col]),
     * o null si la pieza no está en la mano o el origen cae fuera de todo rango
     * dibujable. Consulta pura: no muta nada; el ViewModel la usa en cada
     * `DragMoved` para pintar la vista previa.
     *
     * Además de las celdas de la propia pieza, **simula la jugada** (escribe
     * las celdas visibles como `Filled` sobre una copia del tablero) para
     * saber qué filas/columnas quedarían completas — es la misma detección de
     * [BoardGrid.findFullLines] que usa [onDrop] al confirmar, aplicada aquí
     * solo como consulta. Así la UI puede iluminar la línea entera ANTES de
     * soltar, no solo la huella de la pieza.
     */
    fun previewFor(pieceId: Int, row: Int, col: Int): PlacementPreview? {
        val piece = _state.value.hand.firstOrNull { it.id == pieceId } ?: return null
        val origin = GridPos(row, col)
        val cells = piece.shape.absoluteCells(origin)
        // Solo se previsualizan las celdas que caen dentro: si la pieza asoma
        // fuera del tablero el fantasma se pinta parcial y en tono inválido.
        val visible = cells.filterTo(mutableSetOf()) { it.isInsideBoard }
        if (visible.isEmpty()) return null
        val isValid = _state.value.board.canPlace(piece.shape, origin)
        // Una jugada inválida no completa nada: no tiene sentido simular sobre
        // un tablero que nunca se escribiría así.
        val clearingLines = if (isValid) {
            _state.value.board.write(visible) { BoardCell.Filled(piece.accent) }.findFullLines()
        } else {
            FullLines(emptySet(), emptySet())
        }
        return PlacementPreview(cells = visible, isValid = isValid, clearingLines = clearingLines)
    }

    /**
     * Intenta anclar la pieza [pieceId] con origen en ([row], [col]). Secuencia
     * completa de una jugada:
     *
     *  1. Validar (pieza en mano + hueco libre). Si falla → [BlockGridEvent.PlacementRejected].
     *  2. Escribir los bloques y quitar la pieza de la mano.
     *  3. Detectar filas/columnas completas y marcarlas [BoardCell.Clearing].
     *  4. Puntuar (bloques + líneas, ver [scoreForLines]).
     *  5. Reponer la mano si quedó vacía.
     *  6. Comprobar Game Over: si ninguna pieza de la mano cabe, [finish].
     *
     * El orden 5→6 importa: el Game Over se evalúa contra la mano YA repuesta,
     * porque colocar la última pieza nunca debe perder si las nuevas caben.
     */
    fun onDrop(pieceId: Int, row: Int, col: Int) {
        val current = _state.value
        val piece = current.hand.firstOrNull { it.id == pieceId } ?: return
        dropAttempts++

        val origin = GridPos(row, col)
        if (!current.board.canPlace(piece.shape, origin)) {
            _events.trySend(BlockGridEvent.PlacementRejected(pieceId))
            return
        }
        dropsPlaced++

        // 2. Anclar: las celdas destino pasan a Filled con el acento de la pieza.
        val occupied = piece.shape.absoluteCells(origin)
        var board = current.board.write(occupied) { BoardCell.Filled(piece.accent) }

        // 3. Líneas completas (filas y columnas a la vez, sin doble conteo de celdas).
        val fullLines = board.findFullLines()
        if (fullLines.isNotEmpty()) {
            board = board.write(fullLines.cells()) { cell ->
                // Solo se "rompe" lo asentado; una celda no puede completar dos
                // líneas con contenidos distintos, así que el cast es seguro.
                BoardCell.Clearing((cell as BoardCell.Filled).accent)
            }
        }

        // 4. Puntuar y 5. reponer mano si se agotó.
        val lineCount = fullLines.count
        // Vaciado total: tras romper, no queda ni un bloque asentado en todo el
        // tablero. Es un logro mucho más raro que un combo grande y la UI lo
        // celebra distinto (guirnaldas), así que viaja como dato aparte del conteo.
        val isPerfectClear = lineCount > 0 && board.cells.all { row -> row.none { it is BoardCell.Filled } }
        val newHand = current.hand.filterNot { it.id == pieceId }
            .ifEmpty { dealHand().also { _events.trySend(BlockGridEvent.HandRefilled) } }

        _state.update {
            it.copy(
                board = board,
                hand = newHand,
                score = it.score + piece.shape.blockCount + scoreForLines(lineCount),
                linesCleared = it.linesCleared + lineCount,
            )
        }

        _events.trySend(BlockGridEvent.PiecePlaced)
        if (lineCount > 0) _events.trySend(BlockGridEvent.LinesCleared(lineCount, isPerfectClear))

        // 6. Game Over contra la mano repuesta. Antes de terminar, ofrece revivir (una
        //    sola vez por sesión, limpiar el tablero viendo un anuncio); si ya se
        //    consumió la segunda oportunidad, el fin de partida es definitivo.
        if (newHand.none { _state.value.board.canPlaceAnywhere(it.shape) }) {
            if (reviveConsumed) {
                _events.trySend(BlockGridEvent.GameOverReached)
                finish()
            } else {
                _events.trySend(BlockGridEvent.ReviveOffered)
            }
        }
    }

    /**
     * El anuncio recompensado concedió el trato: **limpia el tablero** (lo vacía) y la
     * corrida continúa con la mano actual —que en un tablero vacío siempre cabe—
     * conservando puntos y líneas. Marca la segunda oportunidad como **consumida**: no
     * se vuelve a ofrecer en el resto de la sesión (tampoco en una partida nueva).
     */
    fun grantRevive() {
        reviveConsumed = true
        _state.update { it.copy(board = BoardGrid()) }
    }

    /**
     * El jugador rechazó la oferta (o el anuncio se cerró / no había): fin de partida
     * real. Emite [BlockGridEvent.GameOverReached] y finaliza, publicando el resultado.
     * No consume la segunda oportunidad (no vio el anuncio): una partida posterior de
     * esta misma sesión aún podría ofrecerla.
     */
    fun declineRevive() {
        _events.trySend(BlockGridEvent.GameOverReached)
        finish()
    }

    /** La UI terminó de animar la limpieza: las celdas Clearing se vacían. */
    fun onLineClearFinished() {
        _state.update { s ->
            s.copy(board = s.board.mapCells { if (it is BoardCell.Clearing) BoardCell.Empty else it })
        }
    }

    override fun calculateScore(): Int = _state.value.score

    /**
     * Precisión = drops válidos / drops intentados. Un jugador que "tantea"
     * soltando en huecos imposibles baja su precisión; arrastrar sin soltar no
     * penaliza (el fantasma ya avisa y explorar es parte del juego).
     */
    override fun currentAccuracy(): Double =
        if (dropAttempts == 0) 100.0 else dropsPlaced * 100.0 / dropAttempts

    /** Récord de la corrida = puntaje (ENDLESS/POINTS en [GameProgressions]). */
    override fun reachedMetric(): Int = _state.value.score

    /**
     * Mano nueva de [HAND_SIZE] piezas uniformemente aleatorias.
     *
     * Se garantiza que **al menos una** cabe en el tablero actual: repartir una
     * mano 100% imposible sería un Game Over decidido por el azar en vez de por
     * el jugador — frustrante e injusto. Si tras varios repartos no se logra
     * (tablero casi lleno), se deja la última mano y el Game Over es legítimo.
     */
    private fun dealHand(): List<Polyomino> {
        val shapes = PolyominoShape.entries
        val accents = BlockAccent.entries
        repeat(MAX_DEAL_RETRIES) {
            val hand = List(HAND_SIZE) {
                Polyomino(
                    id = nextPieceId++,
                    shape = shapes.random(random),
                    accent = accents.random(random),
                )
            }
            if (hand.any { _state.value.board.canPlaceAnywhere(it.shape) }) return hand
        }
        // Tablero tan lleno que ni DOT... imposible (DOT siempre cabe si hay una
        // celda libre); si no hay celda libre el Game Over es real: mano al azar.
        return List(HAND_SIZE) {
            Polyomino(nextPieceId++, shapes.random(random), accents.random(random))
        }
    }

    private companion object {
        /** Reintentos de reparto antes de aceptar una mano sin hueco (ver [dealHand]). */
        const val MAX_DEAL_RETRIES = 10
    }
}

/**
 * Estado puro del juego en un instante: lo que el motor emite y la UI dibuja.
 * Sin nociones de arrastre (interacción de UI, vive en el contrato) ni de
 * colores concretos. Inmutable: cada jugada produce un snapshot nuevo.
 *
 * @property board tablero 8×8.
 * @property hand piezas disponibles (1..[HAND_SIZE]).
 * @property score puntos acumulados.
 * @property linesCleared líneas rotas en total (métrica para logros).
 */
@Serializable
data class BlockGridGameState(
    val board: BoardGrid = BoardGrid(),
    val hand: List<Polyomino> = emptyList(),
    val score: Int = 0,
    val linesCleared: Int = 0,
)

/**
 * Eventos de dominio del motor. El ViewModel los traduce a Effects sensoriales
 * (sonido/háptica/animación); mantener el mapeo fuera del motor deja las reglas
 * de juego libres de decisiones de "textura".
 */
sealed interface BlockGridEvent {
    /** Una pieza quedó anclada al tablero. */
    data object PiecePlaced : BlockGridEvent

    /**
     * El jugador soltó la pieza [pieceId] en un hueco inválido; vuelve a la mano.
     * Lleva el id para que la UI anime el "vuelo de vuelta" de esa pieza concreta.
     */
    data class PlacementRejected(val pieceId: Int) : BlockGridEvent

    /**
     * Se completaron [count] líneas a la vez (≥2 = combo).
     *
     * @property isPerfectClear true si tras romperlas no queda ningún bloque
     *           asentado en el tablero (vaciado total, no solo la línea).
     */
    data class LinesCleared(val count: Int, val isPerfectClear: Boolean) : BlockGridEvent

    /** La mano se agotó y se repartieron piezas nuevas. */
    data object HandRefilled : BlockGridEvent

    /**
     * Ninguna pieza de la mano cabe y **aún no se consumió** la segunda oportunidad:
     * el motor pausa el desenlace y ofrece revivir viendo un anuncio (limpiar el
     * tablero). El ViewModel muestra el overlay; se sale por [grantRevive] o
     * [declineRevive]. La partida NO termina aquí (a diferencia de [GameOverReached]).
     */
    data object ReviveOffered : BlockGridEvent

    /** Ninguna pieza cabe y la segunda oportunidad ya se consumió (o no aplica): fin de partida. */
    data object GameOverReached : BlockGridEvent
}

/**
 * Filas y columnas completas detectadas tras una jugada. Se separan (en vez de
 * un set plano de celdas) porque la UI anima por línea y el scoring cuenta
 * líneas, pero [cells] las une sin duplicar la intersección fila∩columna.
 */
data class FullLines(val rows: Set<Int>, val cols: Set<Int>) {
    val count: Int get() = rows.size + cols.size

    fun isNotEmpty(): Boolean = count > 0

    /** Celdas afectadas, con las intersecciones contadas una sola vez (es un Set). */
    fun cells(): Set<GridPos> = buildSet {
        rows.forEach { r -> (0 until BOARD_SIZE).forEach { c -> add(GridPos(r, c)) } }
        cols.forEach { c -> (0 until BOARD_SIZE).forEach { r -> add(GridPos(r, c)) } }
    }
}

/**
 * Puntos por romper [lines] líneas simultáneas: 10·n² (10, 40, 90...).
 * Cuadrático a propósito: premia desproporcionadamente la jugada planificada
 * de varias líneas de golpe frente a limpiarlas una a una — el "momento wow"
 * que engancha en este género.
 */
internal fun scoreForLines(lines: Int): Int = 10 * lines * lines

/**
 * Filas/columnas completas del tablero. Solo cuentan celdas [BoardCell.Filled]:
 * las [BoardCell.Clearing] ya se rompieron (están animándose) y no pueden
 * volver a completar una línea.
 */
internal fun BoardGrid.findFullLines(): FullLines {
    val rows = (0 until BOARD_SIZE).filterTo(mutableSetOf()) { r ->
        (0 until BOARD_SIZE).all { c -> cells[r][c] is BoardCell.Filled }
    }
    val cols = (0 until BOARD_SIZE).filterTo(mutableSetOf()) { c ->
        (0 until BOARD_SIZE).all { r -> cells[r][c] is BoardCell.Filled }
    }
    return FullLines(rows, cols)
}

/**
 * Copia del tablero con las celdas de [positions] transformadas por [transform]
 * (recibe el contenido actual). Única primitiva de escritura: mantener la
 * mutación en un solo lugar garantiza que el tablero siga siendo inmutable
 * hacia fuera.
 */
internal fun BoardGrid.write(
    positions: Set<GridPos>,
    transform: (BoardCell) -> BoardCell,
): BoardGrid {
    val byRow = positions.groupBy { it.row }
    return BoardGrid(
        cells = cells.mapIndexed { r, rowCells ->
            val touched = byRow[r] ?: return@mapIndexed rowCells
            val colsToWrite = touched.mapTo(mutableSetOf()) { it.col }
            rowCells.mapIndexed { c, cell -> if (c in colsToWrite) transform(cell) else cell }
        },
    )
}

/** Copia del tablero transformando TODAS las celdas (p. ej. vaciar las Clearing). */
internal fun BoardGrid.mapCells(transform: (BoardCell) -> BoardCell): BoardGrid =
    BoardGrid(cells = cells.map { row -> row.map(transform) })
