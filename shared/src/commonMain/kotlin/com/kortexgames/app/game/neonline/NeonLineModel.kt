package com.kortexgames.app.game.neonline

import com.kortexgames.app.game.grid.GridPathBuilder
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighborsIn

/**
 * # Modelos de dominio de "Línea Neón" (Resolución de Problemas)
 *
 * Rompecabezas de trazado tipo *Zip / Hamiltonian path puzzle* con estética de
 * placa de circuito: una cuadrícula con bloques inertes (obstáculos) y, con un
 * único arrastre continuo, el jugador debe dibujar **una sola línea que pase por
 * TODAS las celdas libres exactamente una vez**, sin diagonales y sin pisarse.
 *
 * Es, literalmente, encontrar un **camino hamiltoniano** del subgrafo que forman
 * las celdas libres. Que ese camino exista siempre es responsabilidad del
 * generador ([NeonLineLevels]), que construye el nivel al revés partiendo de una
 * solución (ver su KDoc).
 *
 * ## Decisiones clave
 *
 *  - **Dominio 100% puro**: nada de Compose, px ni `Color`. La UI (FASE 3) traduce
 *    px→celda y decide cómo se pinta el neón; aquí solo hay geometría y reglas.
 *  - **Coordenada compartida**: se reutiliza [GridPosition] de `game.grid`, el
 *    mismo vocabulario que consume [com.kortexgames.app.game.grid.GridPathBuilder]
 *    y que usa Conectores. Es el `Coordinate` del diseño; no se declara un tipo
 *    propio para no tener que convertir en la frontera con el generador.
 *  - **Una sola fuente de verdad para el trazo**: el estado guarda la lista
 *    ordenada [NeonLineGameState.path] y NADA más. "Qué celdas están visitadas",
 *    "cuál es la punta", "¿ganó?" son **derivados** de esa lista, no campos
 *    paralelos que puedan desincronizarse. Un solo dato que mutar por cada celda
 *    que avanza el dedo (ver el porqué del `visited` precomputado más abajo).
 *  - **Inmutabilidad**: cada avance del dedo produce un [NeonLineGameState] nuevo
 *    (snapshots comparables para MVI, sin aliasing), igual que el resto de motores
 *    del proyecto.
 */

/** Lado mínimo del tablero cuadrado (nivel introductorio). */
const val MIN_GRID_SIZE: Int = 4

/** Lado máximo del tablero cuadrado (techo de la progresión). */
const val MAX_GRID_SIZE: Int = 8

/**
 * Nº mínimo de celdas jugables que puede tener un nivel. Con menos de 2 no hay
 * "trazo" que dibujar (una línea necesita al menos un movimiento).
 */
const val MIN_PLAYABLE_CELLS: Int = 2

/**
 * Cómo se pinta una celda del tablero. Es un **estado derivado de presentación**,
 * no almacenado: la fuente de verdad son los obstáculos del nivel (fijos) y el
 * trazo actual (ver [NeonLineGameState.cellState]). Se modela como enum, y no como
 * un par de booleanos, para que la UI resuelva el aspecto de cada celda con un
 * `when` exhaustivo y sea imposible representar un estado absurdo (obstáculo
 * visitado).
 */
enum class NeonLineCellState {

    /** Libre y aún sin recorrer: el hueco que falta por llenar. */
    EMPTY,

    /** Bloque inerte del circuito: la línea nunca puede entrar aquí. */
    OBSTACLE,

    /** Ya forma parte del trazo actual del jugador. */
    VISITED,
}

/**
 * Definición inmutable de un nivel: el tablero y dónde están los bloques.
 *
 * El `init` valida el nivel al construirse para que un error del generador (o de
 * un nivel escrito a mano en un test) reviente en desarrollo con un mensaje claro,
 * nunca como un nivel irresoluble en manos del jugador.
 *
 * Ojo con lo que NO valida: que exista un camino hamiltoniano sobre las celdas
 * libres es exactamente el problema NP-completo que el juego plantea, así que
 * comprobarlo aquí costaría tanto como resolver el nivel. En su lugar se validan
 * dos condiciones **necesarias** y baratas —la zona jugable es conexa y tiene como
 * mucho 2 callejones sin salida— que atrapan la práctica totalidad de los tableros
 * rotos; la garantía real de solubilidad viene de cómo se construye el nivel
 * ([NeonLineLevels]).
 *
 * @property number número de nivel 1-based (orden de desbloqueo).
 * @property gridSize lado del tablero cuadrado ([MIN_GRID_SIZE]..[MAX_GRID_SIZE]).
 * @property obstacles celdas bloqueadas. El resto del tablero es zona jugable.
 */
data class NeonLineLevel(
    val number: Int,
    val gridSize: Int,
    val obstacles: Set<GridPosition>,
) {

    /**
     * Celdas libres que el jugador debe cubrir. Se calcula una vez al construir el
     * nivel (no en cada acceso) porque el motor la consulta en cada avance del dedo
     * y el nivel es inmutable. Queda fuera de `equals`/`hashCode` por ser un
     * derivado de [obstacles], que sí participa.
     */
    val playableCells: Set<GridPosition> = buildSet {
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val cell = GridPosition(row, col)
                if (cell !in obstacles) add(cell)
            }
        }
    }

    /** Longitud exacta que debe alcanzar el trazo para ganar. */
    val playableCount: Int get() = playableCells.size

    init {
        require(gridSize in MIN_GRID_SIZE..MAX_GRID_SIZE) {
            "Nivel $number: el lado del tablero debe estar en $MIN_GRID_SIZE..$MAX_GRID_SIZE"
        }
        require(obstacles.all { it.isInside(gridSize) }) {
            "Nivel $number: hay obstáculos fuera del tablero ${gridSize}x$gridSize"
        }
        require(playableCount >= MIN_PLAYABLE_CELLS) {
            "Nivel $number: solo $playableCount celdas jugables (mínimo $MIN_PLAYABLE_CELLS)"
        }
        // Condición necesaria 1: la zona jugable es de una pieza. Si estuviera
        // partida en dos islas, ninguna línea única podría recorrerlas ambas.
        require(GridPathBuilder.isConnected(playableCells)) {
            "Nivel $number: la zona jugable está partida en islas inconexas"
        }
        // Condición necesaria 2: como mucho 2 callejones sin salida. Una celda con
        // una sola vecina libre solo puede ser un EXTREMO del trazo, y un camino
        // tiene exactamente dos extremos: con 3 o más, el nivel es irresoluble.
        require(deadEndCount() <= 2) {
            "Nivel $number: ${deadEndCount()} callejones sin salida (un camino admite 2 extremos)"
        }
        // Condición necesaria 3: paridad del tablero de ajedrez. Ver el argumento
        // completo en [GridPathBuilder.hasHamiltonianParity]; en corto, la línea
        // alterna colores en cada paso, así que no puede cubrir una zona con 2 celdas
        // de ventaja para un color.
        require(GridPathBuilder.hasHamiltonianParity(playableCells)) {
            "Nivel $number: la zona jugable desequilibra el tablero de ajedrez (sin camino posible)"
        }
    }

    /** ¿[cell] es un bloque inerte? */
    fun isObstacle(cell: GridPosition): Boolean = cell in obstacles

    /** ¿Se puede pisar [cell]? (dentro del tablero y no bloqueada). */
    fun isPlayable(cell: GridPosition): Boolean = cell in playableCells

    /** Celdas libres con una única vecina libre (ver la validación del `init`). */
    private fun deadEndCount(): Int =
        playableCells.count { it.orthogonalNeighborsIn(playableCells).size == 1 }
}

/**
 * Estado puro del tablero en un instante. Es lo que el motor emite y la UI dibuja;
 * **no contiene nada de presentación** (colores reales, px, animaciones).
 * Inmutable: cada celda que avanza el dedo produce un estado nuevo.
 *
 * @property gridSize lado del tablero (copiado del nivel al iniciar, para que la UI
 *           lea del estado todo lo que necesita sin conocer el [NeonLineLevel]).
 * @property obstacles bloques inertes del nivel; no cambian durante la partida.
 * @property path trazo actual, **ordenado** desde la celda donde el jugador posó el
 *           dedo hasta la punta. El orden es lo que permite retroceder (recortar la
 *           cola) en FASE 2 y dibujar un `Path` continuo con codos en FASE 3.
 * @property isTracing true mientras el dedo sigue apoyado. Vive en el estado (y no
 *           como estado local del composable) porque lo decide el MOTOR: la UI
 *           reporta gestos crudos y el motor valida qué es un trazo en curso.
 */
data class NeonLineGameState(
    val gridSize: Int = MIN_GRID_SIZE,
    val obstacles: Set<GridPosition> = emptySet(),
    val path: List<GridPosition> = emptyList(),
    val isTracing: Boolean = false,
) {

    /**
     * Celdas del trazo como conjunto, para responder "¿ya pasé por aquí?" en O(1).
     *
     * El motor hace esa pregunta en CADA tick de arrastre (decenas por segundo) y
     * recorrer la lista sería O(longitud del trazo) cada vez, justo cuando el trazo
     * es más largo. Al ser una `val` computada en el constructor, cada estado la
     * arma una sola vez —O(n)— y el motor la consulta muchas.
     */
    val visited: Set<GridPosition> = path.toSet()

    /** Punta del trazo: la celda bajo el dedo. `null` si aún no se empezó. */
    val head: GridPosition? get() = path.lastOrNull()

    /**
     * Celda anterior a la punta. Es la única celda a la que un retroceso puede
     * volver (regla de "undo táctil" de FASE 2): arrastrar el dedo hacia ella
     * desmarca la punta.
     */
    val previous: GridPosition? get() = path.getOrNull(path.lastIndex - 1)

    /** Nº de celdas libres del tablero: la longitud exacta que gana la partida. */
    val playableCount: Int get() = gridSize * gridSize - obstacles.size

    /**
     * ¿Nivel resuelto? Se **deriva** del tamaño del trazo en vez de guardarse como
     * flag: el trazo es un camino simple por invariante del motor (celdas contiguas
     * y sin repetir), así que cubrir tantas celdas como libres hay equivale a
     * haberlas cubierto todas. Derivarlo hace imposible que el flag y el trazo se
     * desincronicen tras un retroceso o un reinicio.
     */
    val isSolved: Boolean get() = path.size == playableCount

    /** Avance 0f..1f para la barra de progreso de la UI. */
    val progress: Float
        get() = if (playableCount == 0) 0f else path.size.toFloat() / playableCount

    /** Cómo debe pintarse [cell] (ver [NeonLineCellState]). */
    fun cellState(cell: GridPosition): NeonLineCellState = when {
        cell in obstacles -> NeonLineCellState.OBSTACLE
        cell in visited -> NeonLineCellState.VISITED
        else -> NeonLineCellState.EMPTY
    }

    /**
     * Posición de [cell] dentro del trazo (0-based), o `null` si no está trazada.
     * La UI la usa para escalonar la animación de encendido del circuito y el motor
     * para recortar el trazo al retroceder.
     */
    fun indexInPath(cell: GridPosition): Int? = path.indexOf(cell).takeIf { it >= 0 }

    companion object {

        /** Construye el estado inicial (tablero limpio, sin trazo) de un nivel. */
        fun from(level: NeonLineLevel): NeonLineGameState = NeonLineGameState(
            gridSize = level.gridSize,
            obstacles = level.obstacles,
        )
    }
}
