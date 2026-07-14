package com.example.kortexgames.game.neoncircuit

import kotlin.math.abs

/**
 * # Modelos de dominio de "Neon Circuit Flow" (Resolución de Problemas)
 *
 * Mecánica clásica de *Flow Free / Numberlink* con temática de placa de circuito
 * neón: una cuadrícula cuadrada con pares de "nodos" del mismo color que el
 * jugador debe unir trazando "cables" (rutas ortogonales de luz) sin que los
 * cables de distintos colores se crucen.
 *
 * ## Decisiones clave
 *
 *  - **Dominio 100% puro**: nada de Compose, px ni `Color`. El color de un cable
 *    es una *identidad de canal* ([WireColor]) — un enum semántico, no un valor
 *    de presentación. El mapeo canal→token de `LogicColors` vive en la UI
 *    (FASE 3); aquí un cable "cian" solo es el canal `CYAN`. Así el motor y los
 *    tests no arrastran dependencias de framework.
 *  - **Rutas como listas ordenadas de celdas**: un [WirePath] es la secuencia de
 *    celdas desde un nodo hasta el otro (o hasta la punta en curso). El orden
 *    importa: es lo que permite "cortar" un cable desde un punto intermedio
 *    (FASE 2) recortando la lista, y lo que la UI recorre para dibujar un `Path`
 *    continuo con codos redondeados (FASE 3).
 *  - **Inmutabilidad**: cada avance del dedo produce un [NeonCircuitGameState]
 *    nuevo (snapshots comparables para MVI, sin aliasing), igual que el resto de
 *    motores del proyecto.
 *
 * ## Detección de colisiones en O(1) (el "porqué" del diseño)
 *
 * La regla estricta del juego es que **un cable nuevo que pisa una celda ocupada
 * corta al cable antiguo desde ahí**. Comprobar esto recorriendo todas las rutas
 * en cada avance de celda sería O(nº total de celdas cableadas) por *tick* de
 * arrastre — y el arrastre dispara decenas de ticks por segundo.
 *
 * Por eso el estado expone [NeonCircuitGameState.occupancy]: un
 * `Map<GridPosition, WireColor>` que indexa **qué canal ocupa cada celda**. Con
 * él, cuando el dedo entra en una celda el motor resuelve en **O(1)** las dos
 * preguntas que importan: "¿está ocupada?" (`containsKey`) y "¿por qué canal?"
 * (`get`, para saber a quién recortar). Se prefiere un `Map` disperso a una
 * matriz 2D `Array<Array<…>>` porque el tablero está mayormente vacío durante la
 * partida y el mapa evita reservar `gridSize²` casillas y facilita iterar solo
 * sobre lo cableado. La construcción del mapa es O(celdas cableadas) una sola
 * vez por estado, no por tick.
 */

/** Lado mínimo del tablero cuadrado (nivel introductorio). */
const val MIN_GRID_SIZE: Int = 5

/** Lado máximo del tablero cuadrado (nivel avanzado). */
const val MAX_GRID_SIZE: Int = 9

/**
 * Coordenada de celda del tablero: fila y columna, ambas 0-based.
 *
 * Se usa (row, col) —y no (x, y)— para evitar la ambigüedad clásica de qué eje
 * es cuál: `row` crece hacia abajo y `col` hacia la derecha, tal como se dibuja.
 * La UI traduce px→celda (geometría de layout, suya); el dominio solo habla en
 * celdas.
 *
 * @property row fila, 0-based.
 * @property col columna, 0-based.
 */
data class GridPosition(val row: Int, val col: Int) {

    /** ¿La celda cae dentro de un tablero de lado [size]? */
    fun isInside(size: Int): Boolean = row in 0 until size && col in 0 until size

    /**
     * ¿[other] es vecina ortogonal (arriba/abajo/izquierda/derecha)?
     *
     * La regla de oro del juego es "nada de diagonales": el cable solo avanza a
     * celdas contiguas en cruz. Se comprueba con distancia de Manhattan == 1, que
     * es exactamente el conjunto de las 4 vecinas ortogonales y excluye las
     * diagonales (Manhattan 2) y la propia celda (Manhattan 0). El motor (FASE 2)
     * rechaza cualquier [NeonCircuitIntent.ExtendPath] que no cumpla esto.
     */
    fun isOrthogonallyAdjacentTo(other: GridPosition): Boolean =
        abs(row - other.row) + abs(col - other.col) == 1
}

/**
 * Identidad de un canal de cable = color del par de nodos que une.
 *
 * Es un enum (dominio cerrado, no strings ni ints sueltos) para que el motor y
 * la UI compartan un vocabulario tipado de canales. El nombre alude al acento
 * neón que la UI le asignará, pero **el color real vive en `LogicColors`**: aquí
 * `CYAN` es solo "el canal cian", y la UI lo resuelve a `NeonCyan` al pintar. El
 * orden del enum define un mapeo estable canal→token para que un mismo nivel se
 * vea siempre igual.
 *
 * Hay 7 canales, suficientes para el tablero máximo (8×8 admite holgadamente
 * ≤ 7 pares en los niveles diseñados).
 */
enum class WireColor {
    CYAN,
    MAGENTA,
    GREEN,
    AMBER,
    VIOLET,
    BLUE,
    CORAL,
}

/**
 * Uno de los dos terminales fijos de un canal: el "punto" que el jugador conecta.
 *
 * Los nodos no se mueven en toda la partida (a diferencia de las celdas de un
 * [WirePath], que crecen y se recortan). Un canal tiene **exactamente dos** nodos
 * del mismo [color]; conectarlos es el objetivo.
 *
 * @property color canal al que pertenece el par.
 * @property position celda donde está anclado el nodo.
 */
data class Node(
    val color: WireColor,
    val position: GridPosition,
)

/**
 * Cable trazado (o en curso de trazado) de un canal: la secuencia **ordenada** de
 * celdas que recorre, desde el nodo de origen hacia la punta que sigue al dedo.
 *
 * Invariantes que el motor (FASE 2) mantiene y de los que depende el resto:
 *  - `cells` nunca está vacía mientras el [WirePath] exista (arranca con la celda
 *    del nodo origen).
 *  - Celdas consecutivas son vecinas ortogonales (sin diagonales, sin saltos).
 *  - No hay celdas repetidas dentro de un mismo cable (no se pisa a sí mismo).
 *
 * @property color canal del cable (redundante con la clave del mapa de rutas,
 *           pero se guarda para que un [WirePath] sea autoexplicativo al pasarlo
 *           suelto a la UI).
 * @property cells celdas de origen → punta. El orden es esencial para el corte
 *           por intersección y para el dibujo del `Path` continuo.
 */
data class WirePath(
    val color: WireColor,
    val cells: List<GridPosition>,
) {

    /** Celda de origen (nodo desde el que se empezó a trazar). */
    val start: GridPosition? get() = cells.firstOrNull()

    /** Punta actual del cable: la última celda, la que sigue al dedo. */
    val head: GridPosition? get() = cells.lastOrNull()

    /** Número de celdas cableadas. */
    val length: Int get() = cells.size
}

/**
 * Definición inmutable de un nivel diseñado a mano.
 *
 * El `init` valida el nivel al construirse para que un error de diseño (nodos
 * fuera del tablero, un canal sin su par, dos nodos en la misma celda) reviente
 * en desarrollo con un mensaje claro, nunca como bug silencioso en partida.
 *
 * @property number número de nivel 1-based (orden de desbloqueo).
 * @property gridSize lado del tablero cuadrado ([MIN_GRID_SIZE]..[MAX_GRID_SIZE]).
 * @property nodes todos los nodos del nivel: exactamente 2 por cada canal presente.
 */
data class CircuitLevel(
    val number: Int,
    val gridSize: Int,
    val nodes: List<Node>,
) {

    init {
        require(gridSize in MIN_GRID_SIZE..MAX_GRID_SIZE) {
            "Nivel $number: el lado del tablero debe estar en $MIN_GRID_SIZE..$MAX_GRID_SIZE"
        }
        require(nodes.all { it.position.isInside(gridSize) }) {
            "Nivel $number: hay nodos fuera del tablero ${gridSize}x$gridSize"
        }
        val cells = nodes.map { it.position }
        require(cells.size == cells.toSet().size) {
            "Nivel $number: hay dos nodos en la misma celda"
        }
        val byColor = nodes.groupBy { it.color }
        require(byColor.isNotEmpty()) { "Nivel $number: no hay ningún par de nodos" }
        // Un canal con ≠ 2 nodos sería irresoluble (o ambiguo): la mecánica une
        // pares, ni sueltos ni tríos.
        require(byColor.values.all { it.size == 2 }) {
            "Nivel $number: cada canal debe tener exactamente 2 nodos"
        }
    }

    /** Canales presentes en el nivel (= pares a conectar). */
    val colors: Set<WireColor> get() = nodes.map { it.color }.toSet()

    /** Nº de pares a conectar; la UI lo usa para el marcador "x/y conectados". */
    val pairCount: Int get() = colors.size
}

/**
 * Estado puro del tablero en un instante. Es lo que el motor emite y la UI
 * dibuja; **no contiene nada de presentación** (colores reales, px, animaciones)
 * — solo geometría y relaciones. Inmutable: cada avance del dedo produce un
 * estado nuevo.
 *
 * @property gridSize lado del tablero (copiado del [CircuitLevel] al iniciar, para
 *           que la UI lea todo lo que necesita del estado sin conocer el nivel).
 * @property nodes nodos fijos del nivel (los "puntos" a conectar).
 * @property paths cables actuales indexados por canal. Un canal ausente del mapa
 *           = aún sin trazar. Se usa `Map<WireColor, WirePath>` (y no lista)
 *           porque cada canal tiene a lo sumo un cable y así el motor lo localiza
 *           en O(1) al reanudar o cortar un trazo.
 * @property activeColor canal que el jugador está trazando ahora mismo, o null en
 *           reposo. Vive en el estado (no como estado local del composable) porque
 *           lo decide el MOTOR: la UI reporta celdas crudas y el motor valida si
 *           extienden el trazo activo.
 * @property solved true cuando todos los pares están conectados: la UI lanza la
 *           celebración y confirma el nivel superado.
 */
data class NeonCircuitGameState(
    val gridSize: Int = MIN_GRID_SIZE,
    val nodes: List<Node> = emptyList(),
    val paths: Map<WireColor, WirePath> = emptyMap(),
    val activeColor: WireColor? = null,
    val solved: Boolean = false,
) {

    /**
     * Índice celda→canal ocupante, base de la detección de colisiones en O(1)
     * (ver el "porqué" en el encabezado del archivo). Incluye TODAS las celdas de
     * TODOS los cables actuales; los nodos no cableados no ocupan (aún se puede
     * pasar por su celda). Al ser una `val` computada, cada estado la reconstruye
     * una vez y el motor la consulta muchas veces por arrastre.
     */
    val occupancy: Map<GridPosition, WireColor>
        get() = buildMap {
            paths.values.forEach { path ->
                path.cells.forEach { cell -> put(cell, path.color) }
            }
        }

    /** Nodo anclado en [pos], o null si esa celda no es un terminal. */
    fun nodeAt(pos: GridPosition): Node? = nodes.firstOrNull { it.position == pos }

    /**
     * ¿El canal [color] está conectado? Lo está cuando su cable existe y sus dos
     * extremos coinciden con los dos nodos del canal (en cualquier sentido). La
     * contigüidad de las celdas intermedias es un invariante que garantiza el
     * motor al construir el [WirePath], así que aquí basta comparar los extremos.
     */
    fun isColorConnected(color: WireColor): Boolean {
        val path = paths[color] ?: return false
        val endpoints = nodes.filter { it.color == color }.map { it.position }.toSet()
        if (endpoints.size != 2) return false
        return path.start != null && path.head != null &&
            setOf(path.start, path.head) == endpoints
    }

    /** Nº de pares ya conectados; alimenta el marcador "x/y" y la condición de victoria. */
    val connectedCount: Int
        get() = nodes.map { it.color }.toSet().count { isColorConnected(it) }

    /** Nº total de pares del nivel. */
    val pairCount: Int get() = nodes.map { it.color }.toSet().size

    /**
     * ¿Todas las celdas del tablero están cubiertas por algún cable? Es la
     * condición de victoria **bonus** (tablero lleno) además de conectar todos los
     * pares. La victoria mínima solo exige [connectedCount] == [pairCount].
     */
    val isBoardFull: Boolean get() = occupancy.size == gridSize * gridSize

    companion object {

        /** Construye el estado inicial (sin cables) a partir de un nivel diseñado. */
        fun from(level: CircuitLevel): NeonCircuitGameState = NeonCircuitGameState(
            gridSize = level.gridSize,
            nodes = level.nodes,
        )
    }
}
