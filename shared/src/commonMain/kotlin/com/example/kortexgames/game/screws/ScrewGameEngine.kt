package com.example.kortexgames.game.screws

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.PI
import kotlin.random.Random

/**
 * # Motor de "Tornillos Neón" — reglas, física simplificada y generador
 *
 * Juego **LEVELED por turnos** (sin bucle de tiempo): el jugador desatornilla y
 * recoloca tornillos; las placas reaccionan según cuántos tornillos las
 * atraviesan. Gana cuando todas las placas han caído.
 *
 * ## Física simplificada (el "porqué" del algoritmo)
 * No hay motor de física: las tres preguntas del juego se responden con
 * geometría cerrada y determinista, suficiente porque las piezas solo cambian
 * en momentos discretos (cada recolocación):
 *
 *  1. **¿Qué sujeta a una placa?** — distancia punto-punto: un tornillo la
 *     atraviesa si su agujero de tablero coincide (± [HOLE_ALIGN_EPSILON]) con
 *     un agujero de la placa en su pose actual ([Plate.hasHoleAlignedWith]).
 *  2. **¿Cómo cuelga con 1 tornillo?** — trigonometría con `atan2`: en reposo,
 *     el centro de masas queda en la vertical bajo el pivote. Se calcula el
 *     ángulo actual centro→pivote y se rota la placa la diferencia hasta π/2
 *     (Y crece hacia abajo). Un péndulo real amortiguado termina exactamente
 *     ahí, así que basta con el estado final: la animación `spring` de la UI
 *     pone el balanceo intermedio.
 *  3. **¿Qué agujeros están bloqueados?** — punto-en-rectángulo-rotado
 *     ([Plate.covers]): un agujero vacío tapado por una placa sin agujero
 *     propio alineado no admite tornillos.
 *
 * ## Feedback (excepción consciente a los otros motores)
 * A petición del usuario, este juego **no** dispara audio/háptica directamente
 * con [AudioAndHapticManager]: emite eventos de dominio ([ScrewEvent]) que el
 * ViewModel traduce a `Effects` MVI y la UI consume. El `audio` heredado de
 * [BaseGameEngine] queda sin usar aquí a propósito.
 *
 * @param random solo para tests; los niveles reales se siembran con el nº de
 *   nivel para que cada nivel sea idéntico entre sesiones y dispositivos.
 */
class ScrewGameEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random? = null,
) : BaseGameEngine<ScrewGameState>(GameIds.NEON_SCREWS, difficulty, scope, audio) {

    private val _state = MutableStateFlow(ScrewGameState())
    override val state: StateFlow<ScrewGameState> = _state.asStateFlow()

    // Buffered + trySend: los eventos son feedback efímero; si el canal se
    // llenara (imposible en la práctica) perder uno es preferible a bloquear.
    private val _events = Channel<ScrewEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<ScrewEvent> = _events.receiveAsFlow()

    /** Nivel en juego (1-based); base del récord y de "Siguiente nivel". */
    private var currentLevel = 1

    /** Nº mínimo de movimientos del nivel (= tornillos que sujetan placas). */
    private var levelMinMoves = 0

    /** Arranca el nivel elegido en el selector (o el siguiente desde el game-over). */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        val level = ScrewLevelGenerator.generate(
            level = currentLevel,
            // Semilla = nivel ⇒ el nivel N es siempre el mismo (reintentable y
            // comparable entre jugadores); los tests pueden inyectar su Random.
            random = random ?: Random(currentLevel * LEVEL_SEED_SALT),
        )
        levelMinMoves = level.screws.size
        _state.value = ScrewGameState(
            boardHeight = level.boardHeight,
            holes = level.holes,
            screws = level.screws,
            plates = level.plates,
            blockedHoleIds = computeBlockedHoles(level.plates, level.screws, level.holes),
            totalPlates = level.plates.size,
        )
    }

    /** Reinicia el MISMO nivel desde cero (regenera con la misma semilla). */
    fun restart() = onStart()

    // ------------------------------------------------------------------ input

    /**
     * Toque sobre un tornillo: selecciona ("desatornilla" visualmente) o
     * deselecciona si ya estaba elegido. La selección NO altera la física
     * (recolocación atómica): un toque exploratorio nunca derriba una placa.
     */
    fun onScrewTap(screwId: Int) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING) return
        if (s.screws.none { it.id == screwId }) return

        if (s.selectedScrewId == screwId) {
            _state.value = s.copy(selectedScrewId = null)
            _events.trySend(ScrewEvent.SelectionCleared)
        } else {
            _state.value = s.copy(selectedScrewId = screwId)
            _events.trySend(ScrewEvent.ScrewSelected)
        }
    }

    /** Toque en zona muerta: suelta la selección sin mover nada. */
    fun clearSelection() {
        val s = _state.value
        if (s.selectedScrewId == null) return
        _state.value = s.copy(selectedScrewId = null)
        _events.trySend(ScrewEvent.SelectionCleared)
    }

    /**
     * Toque sobre un agujero con un tornillo seleccionado: intenta la
     * recolocación. Si el destino está ocupado o bloqueado se rechaza con
     * feedback de error y se MANTIENE la selección (el jugador reintenta con
     * otro destino sin repetir el toque de selección).
     */
    fun onHoleTap(holeId: Int) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING) return
        val screw = s.screws.firstOrNull { it.id == s.selectedScrewId } ?: return

        if (screw.holeId == holeId) { // devolverlo a su sitio = deseleccionar
            clearSelection()
            return
        }
        if (!s.isHoleAvailable(holeId)) {
            _events.trySend(ScrewEvent.PlacementRejected)
            return
        }

        // Movimiento atómico: el tornillo cambia de agujero y DESPUÉS se
        // reevalúa la física completa (las placas ven el estado ya consistente).
        val movedScrews = s.screws.map { if (it.id == screw.id) it.copy(holeId = holeId) else it }
        val (newPlates, transitions) = applyPhysics(s.plates, movedScrews, s.holes)

        _state.value = s.copy(
            screws = movedScrews,
            plates = newPlates,
            selectedScrewId = null,
            blockedHoleIds = computeBlockedHoles(newPlates, movedScrews, s.holes),
            moves = s.moves + 1,
        )

        _events.trySend(ScrewEvent.ScrewPlaced)
        transitions.forEach(_events::trySend)
    }

    /**
     * La UI terminó la animación de caída de [plateId]: se elimina del tablero.
     * Si era la última, la partida termina aquí (y no al empezar a caer) para
     * que la celebración aparezca después de ver caer la placa, no encima.
     */
    fun onPlateFallFinished(plateId: Int) {
        val s = _state.value
        val plate = s.plates.firstOrNull { it.id == plateId } ?: return
        if (plate.status != PlateStatus.FALLING) return

        val remaining = s.plates - plate
        _state.value = s.copy(
            plates = remaining,
            platesCleared = s.platesCleared + 1,
            blockedHoleIds = computeBlockedHoles(remaining, s.screws, s.holes),
        )
        if (remaining.isEmpty()) {
            _events.trySend(ScrewEvent.BoardCleared)
            finish()
        }
    }

    // ----------------------------------------------------------------- física

    /**
     * Reevalúa TODAS las placas vivas tras un movimiento y devuelve las placas
     * resultantes + los eventos de transición producidos. Reglas:
     *
     *  - 2+ tornillos → [PlateStatus.ANCHORED] (pose intacta).
     *  - 1 tornillo   → [PlateStatus.HANGING]: rota alrededor del tornillo hasta
     *    su reposo de gravedad (ver [hangingPose]). Si ya colgaba del mismo
     *    pivote no se recalcula (evita re-disparar la animación).
     *  - 0 tornillos  → [PlateStatus.FALLING] (la UI anima y luego confirma).
     *
     * Las placas FALLING se dejan tal cual: ya no interactúan.
     */
    private fun applyPhysics(
        plates: List<Plate>,
        screws: List<Screw>,
        holes: List<BoardHole>,
    ): Pair<List<Plate>, List<ScrewEvent>> {
        val holesById = holes.associateBy { it.id }
        val events = mutableListOf<ScrewEvent>()

        val result = plates.map { plate ->
            if (plate.status == PlateStatus.FALLING) return@map plate

            val supporting = screws.filter { screw ->
                val pos = holesById.getValue(screw.holeId).position
                plate.hasHoleAlignedWith(pos)
            }
            when {
                supporting.size >= 2 ->
                    // Nota: una placa que colgaba puede RE-anclarse si el jugador
                    // atornilla en otro agujero suyo que (rotado) siga alineado
                    // con el tablero. Emergente de la geometría; se conserva la
                    // pose colgada porque así quedó "montada" físicamente.
                    plate.copy(status = PlateStatus.ANCHORED, pivotHoleId = null)

                supporting.size == 1 -> {
                    val pivotHole = holesById.getValue(supporting.single().holeId)
                    if (plate.status == PlateStatus.HANGING && plate.pivotHoleId == pivotHole.id) {
                        plate // ya cuelga de ese mismo tornillo: nada que hacer
                    } else {
                        events += ScrewEvent.PlateStartedHanging
                        hangingPose(plate, pivotHole)
                    }
                }

                else -> {
                    events += ScrewEvent.PlateReleased
                    plate.copy(status = PlateStatus.FALLING, pivotHoleId = null)
                }
            }
        }
        return result to events
    }

    /**
     * Pose de reposo de una placa colgando del agujero [pivot]. El reposo de un
     * péndulo es "centro de masas en la vertical bajo el pivote": se calcula el
     * ángulo actual del vector pivote→centro con `atan2` y se rota la placa la
     * diferencia hasta π/2 (con Y hacia abajo, π/2 apunta al suelo). La rotación
     * se aplica también al centro (gira alrededor del pivote, no sobre sí misma).
     *
     * El delta se normaliza a (-π, π] para que la animación `spring` de la UI
     * balancee por el camino corto, como haría el péndulo real.
     */
    private fun hangingPose(plate: Plate, pivot: BoardHole): Plate {
        val toCenter = plate.center - pivot.position
        val delta = normalizeAngle((PI.toFloat() / 2f) - toCenter.angle())
        return plate.copy(
            center = pivot.position + toCenter.rotate(delta),
            rotation = plate.rotation + delta,
            status = PlateStatus.HANGING,
            pivotHoleId = pivot.id,
        )
    }

    /**
     * Agujeros vacíos inaccesibles: los tapa alguna placa viva sin agujero
     * propio alineado. O(agujeros × placas) por movimiento — trivial para
     * tableros de ~35 agujeros y ≤6 placas; precalcularlo aquí evita repetir
     * geometría en cada frame de dibujo.
     */
    private fun computeBlockedHoles(
        plates: List<Plate>,
        screws: List<Screw>,
        holes: List<BoardHole>,
    ): Set<Int> {
        val occupied = screws.mapTo(HashSet()) { it.holeId }
        val alive = plates.filter { it.status != PlateStatus.FALLING }
        return holes.asSequence()
            .filter { it.id !in occupied }
            .filter { hole ->
                alive.any { it.covers(hole.position) && !it.hasHoleAlignedWith(hole.position) }
            }
            .mapTo(HashSet()) { it.id }
    }

    private fun normalizeAngle(angle: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var a = angle
        while (a <= -PI.toFloat()) a += twoPi
        while (a > PI.toFloat()) a -= twoPi
        return a
    }

    // ------------------------------------------------------------- puntuación

    /**
     * Puntaje: base proporcional al nivel menos penalización por movimientos de
     * más respecto al mínimo teórico (= nº de tornillos: cada uno debe salir de
     * su placa al menos una vez). Mismo esquema que Water Sort para que los
     * puntajes entre juegos LEVELED sean comparables.
     */
    override fun calculateScore(): Int {
        val extraMoves = (_state.value.moves - levelMinMoves).coerceAtLeast(0)
        return (currentLevel * 1_000 - extraMoves * PENALTY_PER_EXTRA_MOVE).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: movimientos mínimos / movimientos reales. */
    override fun currentAccuracy(): Double {
        val moves = _state.value.moves
        if (moves == 0 || levelMinMoves == 0) return 100.0
        return (levelMinMoves.toDouble() / moves * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel completado (solo se termina al despejar el tablero). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        const val PENALTY_PER_EXTRA_MOVE = 50

        /** Sal de la semilla por nivel (nº arbitrario, fijo para siempre). */
        const val LEVEL_SEED_SALT = 7919
    }
}

/**
 * Eventos de dominio del motor: describen QUÉ pasó (semántica de juego), no cómo
 * suena o vibra — esa traducción la hace el ViewModel al mapearlos a Effects.
 */
sealed interface ScrewEvent {
    /** Se seleccionó (desatornilló visualmente) un tornillo. */
    data object ScrewSelected : ScrewEvent

    /** Se soltó la selección sin mover el tornillo. */
    data object SelectionCleared : ScrewEvent

    /** Recolocación válida aplicada. */
    data object ScrewPlaced : ScrewEvent

    /** Destino ocupado o bloqueado por una placa. */
    data object PlacementRejected : ScrewEvent

    /** Una placa pasó a colgar de su último tornillo. */
    data object PlateStartedHanging : ScrewEvent

    /** Una placa se quedó sin tornillos y empieza a caer. */
    data object PlateReleased : ScrewEvent

    /** Cayó la última placa: tablero despejado (victoria). */
    data object BoardCleared : ScrewEvent
}

/**
 * Generador paramétrico de niveles **resolubles por construcción** (sin solver).
 *
 * Estrategia: rejilla regular de agujeros + placas rectangulares que abarcan
 * 2..4 celdas con **tornillos solo en las celdas extremas** (las intermedias
 * quedan tapadas ⇒ agujeros bloqueados, que son la "sal" del puzzle). Debajo de
 * la rejilla hay una fila de **aparcamiento** que ninguna placa cubre jamás.
 *
 * La solubilidad se garantiza con dos invariantes (en vez de un solver, que
 * aquí exigiría simular rotaciones de placas colgantes):
 *
 *  1. **Aparcamiento libre**: siempre hay ≥ 2 agujeros que ninguna placa puede
 *     tapar en su pose inicial ⇒ los dos tornillos de cualquier placa caben.
 *  2. **Celdas de tornillo intocables**: ninguna placa cubre las celdas de
 *     tornillo de otra ni comparte celda de tornillo ⇒ al caer una placa, sus
 *     agujeros quedan libres e inmediatamente reutilizables.
 *
 * Con ambos, despejar el tablero placa a placa siempre es posible en cualquier
 * orden; la dificultad viene de optimizar movimientos, no de poder atascarse.
 * (Una placa COLGANTE rotada puede tapar agujeros temporalmente, pero su
 * alcance es local a su pivote: liberar su último tornillo siempre la retira.)
 *
 * Determinista dado un [Random] sembrado (el motor siembra con el nº de nivel).
 */
object ScrewLevelGenerator {

    // Rejilla: 5 columnas × 6 filas + fila de aparcamiento inferior.
    private const val COLS = 5
    private const val ROWS = 6
    private const val MARGIN_X = 160f
    private const val GRID_TOP = 150f
    private const val SPACING_X = (BOARD_WIDTH - 2 * MARGIN_X) / (COLS - 1) // 170
    private const val SPACING_Y = 150f
    private const val PARKING_Y = GRID_TOP + ROWS * SPACING_Y + 80f
    private const val BOARD_HEIGHT = PARKING_Y + 130f

    /** Grosor de la placa a cada lado de su eje (semieje transversal). */
    private const val PLATE_ACROSS_HALF = 58f

    /** Prolongación de la placa más allá de sus celdas extremas. */
    private const val PLATE_END_PADDING = 56f

    private const val MAX_PLACEMENT_ATTEMPTS = 400

    /**
     * Genera el nivel [level] (1-based). La curva de dificultad sube el nº de
     * placas y su longitud, y encoge el aparcamiento, todo acotado para que los
     * invariantes de solubilidad se mantengan.
     */
    fun generate(level: Int, random: Random): ScrewLevel {
        val l = level.coerceAtLeast(1)
        val plateCount = (6 + (l + 1) / 2).coerceAtMost(30)   // 3,3,4,4,5,5,6...
        val maxLength = (2 + l / 3).coerceAtMost(4)          // 2 → 3 → 4
        val parkingCount = (4 - (l - 1) / 4).coerceAtLeast(2) // 4 → 3 → 2

        // Celda (col, fila) → posición. La rejilla completa se perfora siempre:
        // los agujeros "de sobra" son el margen de maniobra del jugador.
        fun cellPos(col: Int, row: Int) =
            Vec2(MARGIN_X + col * SPACING_X, GRID_TOP + row * SPACING_Y)

        val plates = mutableListOf<Plate>()
        val screwCells = mutableSetOf<Pair<Int, Int>>()   // celdas con tornillo (extremos)
        val coveredCells = mutableSetOf<Pair<Int, Int>>() // toda celda bajo alguna placa

        var attempts = 0
        while (plates.size < plateCount && attempts < MAX_PLACEMENT_ATTEMPTS) {
            attempts++
            val horizontal = random.nextBoolean()
            val length = 2 + random.nextInt(maxLength - 1) // 2..maxLength
            val col = random.nextInt(if (horizontal) COLS - length + 1 else COLS)
            val row = random.nextInt(if (horizontal) ROWS else ROWS - length + 1)

            val cells = (0 until length).map {
                if (horizontal) (col + it) to row else col to (row + it)
            }
            val ends = setOf(cells.first(), cells.last())

            // Invariante 2 (ver KDoc): mis extremos no pueden estar cubiertos ni
            // usados por otra placa; mi cuerpo no puede tapar tornillos ajenos.
            val endsClear = ends.none { it in coveredCells || it in screwCells }
            val bodyClear = cells.none { it in screwCells }
            if (!endsClear || !bodyClear) continue

            val start = cellPos(cells.first().first, cells.first().second)
            val end = cellPos(cells.last().first, cells.last().second)
            val center = (start + end) * 0.5f
            val axisHalf = (length - 1) * (if (horizontal) SPACING_X else SPACING_Y) / 2f + PLATE_END_PADDING

            plates += Plate(
                id = plates.size,
                center = center,
                halfSize = if (horizontal) Vec2(axisHalf, PLATE_ACROSS_HALF) else Vec2(PLATE_ACROSS_HALF, axisHalf),
                // Agujeros solo en los extremos: las celdas intermedias quedan
                // tapadas sin acceso — son los agujeros bloqueados del puzzle.
                holeOffsets = listOf(start - center, end - center),
                zIndex = plates.size, // orden de creación = orden de apilado
                colorIndex = plates.size,
            )
            screwCells += ends
            coveredCells += cells
        }

        // Agujeros: rejilla completa + aparcamiento centrado (fuera del área de
        // placas por construcción ⇒ invariante 1).
        val holes = buildList {
            var id = 0
            for (row in 0 until ROWS) for (col in 0 until COLS) {
                add(BoardHole(id++, cellPos(col, row)))
            }
            val parkingSpan = (parkingCount - 1) * SPACING_X
            val parkingStartX = (BOARD_WIDTH - parkingSpan) / 2f
            repeat(parkingCount) { i ->
                add(BoardHole(id++, Vec2(parkingStartX + i * SPACING_X, PARKING_Y)))
            }
        }

        // Un tornillo por celda-extremo de cada placa. La búsqueda por posición
        // es exacta: las celdas de tornillo son posiciones de rejilla sin rotar.
        var screwId = 0
        val screws = plates.flatMap { plate ->
            plate.worldHoles.map { pos ->
                val hole = holes.first { it.position.distanceTo(pos) <= HOLE_ALIGN_EPSILON }
                Screw(id = screwId++, holeId = hole.id)
            }
        }

        return ScrewLevel(
            boardHeight = BOARD_HEIGHT,
            holes = holes,
            plates = plates,
            screws = screws,
        )
    }
}
