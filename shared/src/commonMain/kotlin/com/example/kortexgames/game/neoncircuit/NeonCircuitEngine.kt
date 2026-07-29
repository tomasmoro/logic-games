package com.example.kortexgames.game.neoncircuit

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

/**
 * # Motor de "Neon Circuit Flow" — trazado, no-diagonales y corte por cruce
 *
 * Juego **LEVELED por gesto** (sin bucle de tiempo): el jugador arrastra el dedo
 * desde un nodo, celda a celda, tendiendo un cable hasta el nodo gemelo. El motor
 * es el único que decide qué hace cada celda que toca el dedo; la UI solo reporta
 * la celda cruda (px→celda es geometría de layout, suya).
 *
 * ## Máquina del trazo activo (el "porqué" del diseño)
 *
 * Todo el juego se reduce a mantener UN trazo activo ([NeonCircuitGameState.activeColor])
 * y, en cada celda nueva bajo el dedo, resolver un `when` de casos mutuamente
 * excluyentes contra la **punta** del cable activo:
 *
 *  1. **No adyacente** (diagonal o salto) → se ignora. Aquí vive la regla "sin
 *     diagonales": solo se acepta [GridPosition.isOrthogonallyAdjacentTo] (Manhattan
 *     == 1). El arrastre puede ir más rápido que un tick por celda, pero un salto
 *     no adyacente nunca crea cable — el jugador ha de recorrer la celda contigua.
 *  2. **Retroceso** (la celda es la penúltima del propio cable) → repliega un paso
 *     (deshacer natural arrastrando hacia atrás).
 *  3. **Auto-cruce** (celda ya presente en el propio cable, y no es retroceso) →
 *     se ignora: un cable no se pisa a sí mismo.
 *  4. **Nodo ajeno** (celda con un nodo de OTRO canal) → se ignora: no se puede
 *     atravesar el terminal de otro par.
 *  5. **Cruce con cable ajeno** (celda ocupada por otro canal) → se **corta** ese
 *     cable desde la intersección (regla estricta del juego) y se avanza.
 *  6. **Celda libre / nodo propio** → se avanza; si la punta llega al nodo gemelo,
 *     el par queda conectado.
 *
 * ## Detección de cruce y corte en O(1)/O(k)
 *
 * El caso 5 se resuelve consultando [NeonCircuitGameState.occupancy] (mapa
 * celda→canal, ver su "porqué" en `NeonCircuitModel.kt`): saber si una celda está
 * ocupada y por quién es O(1). El **corte** recorta el cable ajeno a
 * `cells.subList(0, i)` donde `i` es el índice de la intersección: como un
 * [WirePath] es una lista ordenada desde su nodo, truncarla deja intacto el tramo
 * "bueno" (del nodo a la intersección) y descarta el resto, que ha quedado
 * huérfano — O(k) en la longitud del cable cortado. La intersección nunca cae en
 * el nodo del cable ajeno (el caso 4 lo bloquea antes), así que el corte siempre
 * conserva al menos su celda de origen.
 *
 * ## Feedback
 * Igual que Starport: el motor emite eventos de dominio ([NeonCircuitEvent]) y el
 * ViewModel los traduce a Effects MVI (tabla única auditable). El `audio` heredado
 * de [BaseGameEngine] queda sin usar aquí a propósito.
 */
class NeonCircuitEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : BaseGameEngine<NeonCircuitGameState>(GameIds.NEON_CIRCUIT, difficulty, scope, audio) {

    private val _state = MutableStateFlow(NeonCircuitGameState())
    override val state: StateFlow<NeonCircuitGameState> = _state.asStateFlow()

    // Buffered + trySend: los eventos son feedback efímero; si el canal se llenara
    // (imposible en la práctica) perder uno es preferible a bloquear el arrastre.
    private val _events = Channel<NeonCircuitEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<NeonCircuitEvent> = _events.receiveAsFlow()

    /** Nivel en juego (1-based); base del récord y de "Siguiente nivel". */
    private var currentLevel = 1

    /**
     * Nº de cortes a cables ajenos en la partida. Es una métrica de "desorden"
     * (a más cortes, más rehacer): penaliza el score sin castigar en exceso,
     * porque cruzar es parte legítima de resolver.
     */
    private var cutCount = 0

    /**
     * ¿Ya se mostró en este nivel el aviso de "conecta todo Y rellena el tablero"?
     * Se avisa UNA sola vez por nivel: el primer momento en que el jugador une
     * todos los pares pero deja huecos vacíos (cree que ganó pero falta llenar).
     * Repetirlo en cada reintento sería ruido; la regla se aprende con verla una vez.
     */
    private var fillHintShown = false

    /** Arranca el nivel elegido en el selector (o el siguiente desde el resultado). */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        val level = NeonCircuitLevels.forNumber(currentLevel)
        cutCount = 0
        fillHintShown = false
        _state.value = NeonCircuitGameState.from(level)
    }

    /** Reinicia el MISMO nivel: borra todos los cables. */
    fun restart() = onStart()

    // ------------------------------------------------------------------ input

    /**
     * El jugador posó el dedo sobre el nodo del canal [color] en [cell]: empieza
     * (o REEMPIEZA) el trazo de ese canal desde ese terminal. Retomar un color
     * descarta su cable anterior (regla clásica de Flow: tocar un color lo
     * reinicia). Se valida que [cell] sea de verdad un nodo de [color] para no
     * arrancar trazos "en el aire".
     */
    fun onPathStarted(color: WireColor, cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.solved) return
        val node = s.nodeAt(cell)
        if (node == null || node.color != color) return

        // Reinicia el cable de este canal desde el nodo tocado y lo marca activo.
        val newPaths = s.paths.toMutableMap().apply {
            put(color, WirePath(color, listOf(cell)))
        }
        _state.value = s.copy(paths = newPaths, activeColor = color)
        _events.trySend(NeonCircuitEvent.NodeGrabbed)
    }

    /**
     * El jugador posó el dedo sobre una celda ya cableada de [color] (no un nodo)
     * para **retomar** ese trazo a medias. Se recorta el cable hasta [cell] —que
     * pasa a ser la nueva punta— y se reactiva el canal, para poder seguir
     * tendiéndolo desde ahí sin reempezar desde el nodo.
     *
     * Recortar en el punto agarrado (en vez de solo continuar desde la punta)
     * unifica dos gestos en uno: agarrar por la punta deja el cable intacto (la
     * sublista es el cable entero) y agarrar por un punto intermedio descarta el
     * tramo sobrante — igual que en Flow. Si [cell] no pertenece al cable de
     * [color] (estado ya cambiado), se ignora para no trazar "en el aire".
     */
    fun onPathResumed(color: WireColor, cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.solved) return
        val path = s.paths[color] ?: return
        val i = path.cells.indexOf(cell)
        if (i < 0) return

        // subList(0, i + 1): conserva de origen a la celda agarrada (incluida).
        val resumed = path.copy(cells = path.cells.subList(0, i + 1).toList())
        _state.value = s.copy(paths = s.paths + (color to resumed), activeColor = color)
        _events.trySend(NeonCircuitEvent.NodeGrabbed)
    }

    /**
     * El dedo entró en [cell]. Resuelve el `when` de casos descrito en el
     * encabezado de la clase. Solo actúa sobre el canal activo; si no hay trazo en
     * curso o la partida no está RUNNING, se ignora.
     */
    fun onPathExtended(cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.solved) return
        val color = s.activeColor ?: return
        val path = s.paths[color] ?: return
        val head = path.head ?: return

        if (cell == head) return                                   // misma celda: nada
        if (!head.isOrthogonallyAdjacentTo(cell)) return           // caso 1: diagonal/salto

        // Caso 2: retroceso sobre la penúltima celda → repliega un paso.
        if (path.length >= 2 && cell == path.cells[path.length - 2]) {
            val shorter = path.copy(cells = path.cells.dropLast(1))
            _state.value = s.copy(paths = s.paths + (color to shorter))
            _events.trySend(NeonCircuitEvent.PathRetracted)
            return
        }
        // Caso 2b: la punta YA es el nodo gemelo (par cerrado) → el arrastre no
        // sigue "de largo" más allá del objetivo. Sin esto, un dedo rápido que no
        // se detiene justo en el nodo seguía añadiendo celdas al cable ya conectado,
        // dejando un tramo de cable colgando sin ningún terminal al final.
        if (s.isColorConnected(color)) return
        if (cell in path.cells) return                             // caso 3: auto-cruce

        // Caso 4: no se atraviesa el nodo de otro canal (incluye el gemelo ajeno).
        val nodeHere = s.nodeAt(cell)
        if (nodeHere != null && nodeHere.color != color) return

        // Caso 5: la celda la ocupa otro cable → cortar desde la intersección.
        val owner = s.occupancy[cell]
        val newPaths = s.paths.toMutableMap()
        val cut = owner != null && owner != color
        if (cut) {
            val victim = s.paths.getValue(owner!!)
            val i = victim.cells.indexOf(cell)
            newPaths[owner] = victim.copy(cells = victim.cells.subList(0, i).toList())
            cutCount++
        }

        // Caso 6: avanzar la punta a la celda.
        newPaths[color] = path.copy(cells = path.cells + cell)
        val next = s.copy(paths = newPaths)
        _state.value = next

        // Un cruce es sobre todo táctil (HEAVY); el avance simple es un tick suave.
        _events.trySend(if (cut) NeonCircuitEvent.WireCut else NeonCircuitEvent.CellAdvanced)

        // ¿La punta cerró el par? (la celda de avance es el nodo gemelo).
        if (next.isColorConnected(color)) {
            _events.trySend(NeonCircuitEvent.PairConnected(color))
            // Victoria: todos los pares conectados Y además el tablero debe estar
            // completamente cableado (cada celda ocupada). Esto evita terminar una
            // partida donde solo los pares están unidos pero quedan huecos libres.
            // El tablero lleno sigue influyendo en el score (bonus) pero aquí lo
            // hacemos parte de la condición de fin para exigir soluciones "completas".
            if (next.connectedCount == next.pairCount) {
                if (next.isBoardFull) {
                    _state.value = next.copy(solved = true, activeColor = null)
                    finish()
                } else if (!fillHintShown) {
                    // Unió todos los pares pero quedan celdas vacías: no es victoria.
                    // Se avisa (una vez, ver [fillHintShown]) para que entienda que
                    // debe rellenar TODO el tablero, no solo conectar los pares.
                    fillHintShown = true
                    _events.trySend(NeonCircuitEvent.BoardNotFull)
                }
            }
        }
    }

    /**
     * El jugador levantó el dedo: consolida el trazo tal cual quedó (un cable a
     * medias es válido y se conserva) y suelta el canal activo. No hay snap ni
     * penalización: en Flow un trazo incompleto simplemente sigue ahí para
     * retomarlo.
     */
    fun onPathEnded() {
        val s = _state.value
        if (s.activeColor == null) return
        _state.value = s.copy(activeColor = null)
    }

    // ------------------------------------------------------------- puntuación

    /**
     * Puntaje: base proporcional al nivel, bonus por tablero lleno (la meta de
     * excelencia de Flow) y penalización suave por cortes (rehacer). Mismo esquema
     * multiplicativo por nivel que el resto de juegos LEVELED para que los
     * puntajes sean comparables entre juegos.
     */
    override fun calculateScore(): Int {
        val fullBonus = if (_state.value.isBoardFull) BOARD_FULL_BONUS else 0
        return (currentLevel * 1_000 + fullBonus - cutCount * PENALTY_PER_CUT)
            .coerceAtLeast(0)
    }

    /**
     * Precisión = cobertura del tablero al terminar (celdas cableadas / totales).
     * En Flow "hacerlo bien" es llenar el tablero, así que la cobertura es la
     * medida natural de calidad: 100% cuando la solución es perfecta (lleno).
     */
    override fun currentAccuracy(): Double {
        val s = _state.value
        val total = s.gridSize * s.gridSize
        if (total == 0) return 100.0
        return (s.occupancy.size.toDouble() / total * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel completado (solo se termina al conectar todos los pares). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        /** Bonus por dejar el tablero completamente cableado (victoria bonus). */
        const val BOARD_FULL_BONUS = 500

        /** Penalización por cada corte a un cable ajeno. */
        const val PENALTY_PER_CUT = 25
    }
}

/**
 * Eventos de dominio del motor: describen QUÉ pasó (semántica de juego), no cómo
 * suena o vibra — esa traducción la hace el ViewModel al mapearlos a Effects
 * (tabla única auditable en `NeonCircuitViewModel.onEngineEvent`).
 */
sealed interface NeonCircuitEvent {
    /** Se agarró un nodo: empieza un trazo. */
    data object NodeGrabbed : NeonCircuitEvent

    /** El cable avanzó a una celda libre (el "tick" con textura del arrastre). */
    data object CellAdvanced : NeonCircuitEvent

    /** El cable se replegó un paso (retroceso). */
    data object PathRetracted : NeonCircuitEvent

    /** El cable pisó y cortó a otro canal (feedback fuerte). */
    data object WireCut : NeonCircuitEvent

    /** Un par quedó conectado: la UI hace palpitar ese cable. */
    data class PairConnected(val color: WireColor) : NeonCircuitEvent

    /**
     * Todos los pares están conectados pero el tablero NO está lleno: la UI muestra
     * el cartel que explica que hay que rellenar todas las casillas para ganar.
     */
    data object BoardNotFull : NeonCircuitEvent
}
