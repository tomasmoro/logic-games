package com.kortexgames.app.game.energyflow

import kotlin.math.abs
import kotlin.random.Random

/**
 * # Modelo puro de "Flujo de Energía"
 *
 * Responsabilidad: representar el tablero de tuberías (cada celda es una pieza con
 * conectores que apuntan a lados concretos) y las reglas de conexión, **sin ninguna
 * dependencia de framework** para que sea 100% testeable y compartible. La UI mapea
 * cada pieza a su dibujo en `Canvas`; aquí una pieza es solo un conjunto de
 * [Direction] y una rotación entera.
 *
 * Mecánica: hay una **fuente** (batería) y un **destino** (bombilla). El jugador
 * rota las piezas (toque = giro de 90° en sentido horario) hasta que **la energía
 * llega a la bombilla** (se gana en cuanto el destino queda alimentado desde la
 * fuente; las tuberías que queden sueltas actúan como distractores): ver
 * [EnergyGrid.isSolved]. El nivel se genera como un **árbol de expansión** que cubre
 * TODAS las celdas, garantizando que siempre existe un camino fuente→destino.
 *
 * Decisiones clave:
 *  - **Sin colores múltiples ni piezas especiales (puentes, mezcladores, bloqueos)**
 *    en esta primera versión: son ampliaciones del roadmap. El núcleo se centra en
 *    la rotación y el "circuito cerrado", que es lo que da la sensación adictiva.
 *  - La generación garantiza **resoluble por construcción** (parte de un árbol y solo
 *    baraja rotaciones), sin necesidad de un solver de backtracking: rotar una pieza
 *    no afecta a la validez de las demás, luego la orientación correcta de cada celda
 *    es independiente (ver [Tile.minRotationsToSolve]).
 *  - Inmutable: cada giro produce un tablero nuevo ([EnergyGrid.rotate]), coherente
 *    con el estilo `data class` de la app (igual que "Ordena las Pociones").
 */

/**
 * Los cuatro lados de una celda. El orden N→E→S→O es **horario**, de modo que
 * "rotar 90° en sentido horario" es avanzar una posición en la enum ([rotatedClockwise]).
 */
enum class Direction {
    NORTH, EAST, SOUTH, WEST;

    /** Desplazamiento de fila hacia este lado (arriba = -1). */
    val deltaRow: Int get() = when (this) { NORTH -> -1; SOUTH -> 1; else -> 0 }

    /** Desplazamiento de columna hacia este lado (izquierda = -1). */
    val deltaCol: Int get() = when (this) { WEST -> -1; EAST -> 1; else -> 0 }

    /** Lado opuesto (el conector con el que debe casar el vecino de ese lado). */
    val opposite: Direction get() = entries[(ordinal + 2) % 4]

    /** Este lado tras girar la pieza [steps] cuartos de vuelta en sentido horario. */
    fun rotatedClockwise(steps: Int): Direction = entries[(ordinal + steps).mod(4)]
}

/** Papel de una celda en la red. La fuente y el destino son piezas de un solo conector. */
enum class TileKind {
    /** Batería: origen de la energía (raíz del árbol). */
    SOURCE,

    /** Bombilla: hay que alimentarla cerrando el circuito. */
    TARGET,

    /** Tubería intermedia (recta, codo, T o cruz según su nº de conectores). */
    PIPE,
}

/**
 * Forma de una pieza según sus conectores (independiente de la rotación). Sirve
 * para dibujarla y, sobre todo, para conocer su **periodo de rotación**: cuántos
 * giros de 90° distintos tiene antes de repetir orientación. Una recta y una cruz
 * tienen simetría, así que "resolverlas" requiere menos giros.
 */
enum class TileShape(val rotationPeriod: Int) {
    /** Un conector (extremo): 4 orientaciones distintas. */
    END(4),

    /** Dos conectores opuestos (recta ─ o │): se repite cada 2 giros. */
    STRAIGHT(2),

    /** Dos conectores en ángulo (codo └): 4 orientaciones. */
    CORNER(4),

    /** Tres conectores (T): 4 orientaciones. */
    TEE(4),

    /** Cuatro conectores (cruz ┼): simétrica, una sola orientación. */
    CROSS(1);

    companion object {
        /** Deduce la forma a partir del conjunto de conectores (en cualquier rotación). */
        fun of(connectors: Set<Direction>): TileShape = when (connectors.size) {
            1 -> END
            2 -> if (connectors.any { it.opposite in connectors }) STRAIGHT else CORNER
            3 -> TEE
            else -> CROSS
        }
    }
}

/**
 * Una pieza del tablero en su celda ([row], [col]).
 *
 * @property connectors conectores en la **orientación resuelta** (rotación 0). La
 *           orientación real se obtiene rotándolos [rotation] cuartos de vuelta.
 * @property rotation nº de giros de 90° en horario aplicados. **Crece sin acotar**
 *           (cada toque suma 1): la UI lo usa para animar el giro siempre en el mismo
 *           sentido; la lógica usa `rotation.mod(4)` para el estado efectivo.
 */
data class Tile(
    val row: Int,
    val col: Int,
    val kind: TileKind,
    val connectors: Set<Direction>,
    val rotation: Int = 0,
) {
    /** Forma de la pieza (constante frente a la rotación). */
    val shape: TileShape get() = TileShape.of(connectors)

    /** Conectores en la orientación actual (los resueltos girados [rotation] veces). */
    val effectiveConnectors: Set<Direction>
        get() = connectors.mapTo(HashSet(connectors.size)) { it.rotatedClockwise(rotation) }

    /** Devuelve la pieza tras un giro de 90° en sentido horario (input del juego). */
    fun rotatedClockwise(): Tile = copy(rotation = rotation + 1)

    /**
     * Giros mínimos (en horario) para dejar la pieza en una orientación resuelta.
     * Tiene en cuenta la simetría de la forma ([TileShape.rotationPeriod]): una recta
     * ya está bien a 0° o 180°, y una cruz siempre lo está. Como la validez de una
     * pieza no depende de las demás, la suma de estos mínimos es el óptimo exacto
     * del nivel (referencia para puntuar la eficiencia).
     */
    fun minRotationsToSolve(): Int {
        val period = shape.rotationPeriod
        return (period - rotation.mod(period)) % period
    }
}

/**
 * Tablero: rejilla [rows]×[cols] de [tiles] indexadas por fila (índice = `row*cols+col`).
 * Inmutable; cada giro produce un tablero nuevo. Expone lo necesario para pintar
 * (piezas + celdas energizadas) y para saber si está resuelto.
 */
data class EnergyGrid(
    val rows: Int,
    val cols: Int,
    val tiles: List<Tile>,
) {
    /** Índice de la fuente (batería), o -1 si el tablero está vacío. */
    val sourceIndex: Int get() = tiles.indexOfFirst { it.kind == TileKind.SOURCE }

    /** Índice del destino (bombilla), o -1 si el tablero está vacío. */
    val targetIndex: Int get() = tiles.indexOfFirst { it.kind == TileKind.TARGET }

    /** Índice del vecino de [index] hacia [dir], o null si cae fuera del tablero. */
    fun neighborIndex(index: Int, dir: Direction): Int? {
        val tile = tiles[index]
        val r = tile.row + dir.deltaRow
        val c = tile.col + dir.deltaCol
        return if (r in 0 until rows && c in 0 until cols) r * cols + c else null
    }

    /**
     * Celdas **energizadas**: alcanzables desde la fuente por conexiones válidas
     * (ambas piezas tienen un conector recíproco en el lado que las une). BFS/DFS
     * desde la fuente; la UI lo usa para iluminar la red conectada en tiempo real,
     * dando el feedback de "la energía fluye".
     */
    fun poweredIndices(): Set<Int> {
        val source = sourceIndex
        val powered = HashSet<Int>()
        if (source < 0) return powered

        val stack = ArrayDeque<Int>()
        powered.add(source)
        stack.addLast(source)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            for (dir in tiles[i].effectiveConnectors) {
                val n = neighborIndex(i, dir) ?: continue
                if (n in powered) continue
                // Conexión válida solo si el vecino ofrece el conector recíproco.
                if (dir.opposite in tiles[n].effectiveConnectors) {
                    powered.add(n)
                    stack.addLast(n)
                }
            }
        }
        return powered
    }

    /**
     * ¿Hay algún **conector suelto**? Un conector es una fuga si apunta fuera del
     * tablero o a un vecino que no ofrece el conector recíproco. Utilidad para
     * pruebas/depuración; **no** es la condición de victoria (ver [isSolved]).
     */
    fun hasLeaks(): Boolean = tiles.indices.any { i ->
        tiles[i].effectiveConnectors.any { dir ->
            val n = neighborIndex(i, dir)
            n == null || dir.opposite !in tiles[n].effectiveConnectors
        }
    }

    /**
     * ¿Está resuelto el nivel? Se gana **en cuanto la energía llega a la bombilla**
     * (el destino queda alimentado desde la batería), aunque queden tuberías "basura"
     * sueltas fuera del camino: basta con encontrar UNA forma de conectar
     * fuente→destino. Esas piezas sobrantes actúan como distractores del puzle.
     */
    fun isSolved(): Boolean {
        val target = targetIndex
        return target >= 0 && target in poweredIndices()
    }

    /** Devuelve un tablero nuevo con la pieza [index] girada 90° en horario. */
    fun rotate(index: Int): EnergyGrid = copy(
        tiles = tiles.toMutableList().also { it[index] = it[index].rotatedClockwise() },
    )
}

/**
 * Un nivel generado: el [grid] barajado listo para jugar y [optimalRotations], el
 * nº mínimo de giros para resolverlo (suma de los mínimos por pieza). Se usa para
 * estimar la precisión y puntuar la eficiencia del jugador.
 */
data class EnergyLevel(
    val grid: EnergyGrid,
    val optimalRotations: Int,
)

/**
 * Generador de niveles **garantizados resolubles**. Estrategia:
 *  1. Construye un **árbol de expansión** aleatorio que cubre todas las celdas
 *     (algoritmo de "backtracker recursivo", iterativo aquí para no desbordar pila).
 *     Cada arista del árbol añade conectores recíprocos a las dos celdas que une.
 *  2. Marca la celda inicial como fuente y la **hoja más lejana** como destino, para
 *     que el recorrido del circuito sea largo e interesante, exigiendo además una
 *     separación física mínima ([minSourceTargetDistance]) para que sus conectores
 *     no queden pegados; si ninguna hoja la cumple, se relaja a la hoja más lejana
 *     sin más (el destino siempre es una hoja real del árbol, nunca una celda forzada,
 *     para no arriesgar niveles sin solución).
 *  3. **Baraja las rotaciones** de todas las piezas (sin tocar sus conectores), de
 *     modo que el jugador deba reorientarlas. Se reintenta si el barajado quedara ya
 *     resuelto de casualidad.
 *
 * Es determinista dado un [Random] sembrado → los tests pueden fijar semilla.
 */
object EnergyFlowGenerator {

    /** Tamaño de rejilla (cuadrada) por dificultad 1..3: de 4×4 a 6×6. */
    data class Config(val rows: Int, val cols: Int)

    fun configForDifficulty(difficulty: Int): Config {
        val n = when (difficulty.coerceIn(1, 3)) {
            1 -> 4
            2 -> 5
            else -> 6
        }
        return Config(n, n)
    }

    /** Tope defensivo de rebarajados para no quedar ya resuelto (nunca debería agotarse). */
    private const val MAX_SCRAMBLE_ATTEMPTS = 50

    /**
     * Separación mínima (distancia Manhattan en celdas) exigida entre la fuente y el
     * destino, para que sus conectores no queden pegados. Escala con el tablero: **lado
     * de la rejilla − 2** (4×4 → 2 celdas, 6×6 → 4 celdas), así una rejilla más grande
     * exige una separación proporcionalmente mayor en vez de un mínimo fijo.
     */
    private fun minSourceTargetDistance(rows: Int, cols: Int): Int =
        (minOf(rows, cols) - 2).coerceAtLeast(1)

    /** Distancia Manhattan (en celdas) entre dos índices de la rejilla. */
    private fun gridDistance(a: Int, b: Int, cols: Int): Int {
        val ar = a / cols
        val ac = a % cols
        val br = b / cols
        val bc = b % cols
        return abs(ar - br) + abs(ac - bc)
    }

    /**
     * Genera un nivel resoluble para la [config] dada usando [random] como fuente
     * de aleatoriedad (sembrable para tests deterministas).
     */
    fun generate(config: Config, random: Random = Random.Default): EnergyLevel {
        val rows = config.rows
        val cols = config.cols
        val n = rows * cols

        // 1) Árbol de expansión: conectores resueltos por celda.
        val connectors = Array(n) { mutableSetOf<Direction>() }
        val visited = BooleanArray(n)
        val start = random.nextInt(n)
        val stack = ArrayDeque<Int>()
        visited[start] = true
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val cur = stack.last()
            val r = cur / cols
            val c = cur % cols
            // Busca un vecino sin visitar (en orden aleatorio) y "abre pared" hacia él.
            val next = Direction.entries.shuffled(random).firstNotNullOfOrNull { dir ->
                val nr = r + dir.deltaRow
                val nc = c + dir.deltaCol
                if (nr in 0 until rows && nc in 0 until cols) {
                    val ni = nr * cols + nc
                    if (!visited[ni]) Pair(dir, ni) else null
                } else null
            }
            if (next == null) {
                stack.removeLast() // callejón sin salida: retrocede
            } else {
                val (dir, ni) = next
                connectors[cur].add(dir)
                connectors[ni].add(dir.opposite)
                visited[ni] = true
                stack.addLast(ni)
            }
        }

        // 2) Fuente = celda inicial; destino = hoja (grado 1) más lejana de la fuente,
        // exigiendo además una separación física mínima ([minSourceTargetDistance],
        // que crece con el tamaño de la rejilla) para que los conectores de entrada y
        // salida no queden pegados. Si ninguna hoja la cumple, se relaja a la hoja más
        // lejana sin más (nunca se fuerza una celda ajena al árbol: forzar una esquina
        // podía dejar el nivel sin solución si esa celda no era parte de un camino válido).
        val distance = bfsDistances(start, connectors, rows, cols)
        val leaves = (0 until n).filter { it != start && connectors[it].size == 1 }
        val minDistance = minSourceTargetDistance(rows, cols)
        val farLeaves = leaves.filter { gridDistance(start, it, cols) >= minDistance }
        val target = when {
            farLeaves.isNotEmpty() -> farLeaves.maxByOrNull { distance[it] }!!
            leaves.isNotEmpty() -> leaves.maxByOrNull { distance[it] }!!
            else -> (0 until n).filter { it != start }.maxByOrNull { distance[it] }!!
        }

        val solvedTiles = (0 until n).map { i ->
            val kind = when (i) {
                start -> TileKind.SOURCE
                target -> TileKind.TARGET
                else -> TileKind.PIPE
            }
            Tile(i / cols, i % cols, kind, connectors[i].toSet(), rotation = 0)
        }

        // 3) Baraja rotaciones hasta que el nivel NO nazca ya resuelto.
        var scrambled = solvedTiles
        var attempts = 0
        do {
            scrambled = solvedTiles.map { it.copy(rotation = random.nextInt(4)) }
            attempts++
        } while (EnergyGrid(rows, cols, scrambled).isSolved() && attempts < MAX_SCRAMBLE_ATTEMPTS)

        // Óptimo = giros mínimos para **alimentar el destino** (no para dejar el
        // tablero perfecto): solo cuentan las piezas del camino fuente→destino.
        val optimalRotations = optimalRotationsForPath(connectors, scrambled, start, target, rows, cols)
        return EnergyLevel(EnergyGrid(rows, cols, scrambled), optimalRotations)
    }

    /**
     * Giros mínimos para que la energía llegue al destino: como el tablero es un
     * árbol, el camino fuente→destino es **único**; se orienta cada pieza del camino
     * lo justo para que enlace con sus vecinos del camino (las piezas fuera de él no
     * hacen falta). La validez de cada pieza es independiente, así que la suma de los
     * mínimos por pieza es el óptimo exacto.
     */
    private fun optimalRotationsForPath(
        connectors: Array<MutableSet<Direction>>,
        scrambled: List<Tile>,
        start: Int,
        target: Int,
        rows: Int,
        cols: Int,
    ): Int {
        val path = pathBetween(connectors, start, target, rows, cols)
        return path.indices.sumOf { pi ->
            val idx = path[pi]
            val required = buildSet {
                if (pi > 0) add(directionBetween(idx, path[pi - 1], cols))
                if (pi < path.lastIndex) add(directionBetween(idx, path[pi + 1], cols))
            }
            minRotationsToInclude(scrambled[idx].connectors, scrambled[idx].rotation, required)
        }
    }

    /** Camino de índices de [start] a [target] recorriendo el árbol (BFS con padres). */
    private fun pathBetween(
        connectors: Array<MutableSet<Direction>>,
        start: Int,
        target: Int,
        rows: Int,
        cols: Int,
    ): List<Int> {
        val parent = IntArray(rows * cols) { -1 }
        val queue = ArrayDeque<Int>()
        parent[start] = start
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val r = cur / cols
            val c = cur % cols
            for (dir in connectors[cur]) {
                val ni = (r + dir.deltaRow) * cols + (c + dir.deltaCol)
                if (parent[ni] == -1) {
                    parent[ni] = cur
                    queue.addLast(ni)
                }
            }
        }
        val path = ArrayList<Int>()
        var c = target
        while (c != start) {
            path.add(c)
            c = parent[c]
        }
        path.add(start)
        path.reverse()
        return path
    }

    /** Lado de la celda [from] hacia su vecina adyacente [to]. */
    private fun directionBetween(from: Int, to: Int, cols: Int): Direction {
        val fromRow = from / cols
        val fromCol = from % cols
        val toRow = to / cols
        val toCol = to % cols
        return when {
            toRow < fromRow -> Direction.NORTH
            toRow > fromRow -> Direction.SOUTH
            toCol < fromCol -> Direction.WEST
            else -> Direction.EAST
        }
    }

    /**
     * Giros mínimos (en horario) desde [currentRotation] para que la pieza con
     * conectores [base] incluya todas las direcciones [required]. Siempre existe una
     * solución porque las direcciones requeridas provienen del propio árbol.
     */
    private fun minRotationsToInclude(base: Set<Direction>, currentRotation: Int, required: Set<Direction>): Int {
        for (steps in 0..3) {
            val effective = base.mapTo(HashSet<Direction>(base.size)) { it.rotatedClockwise(currentRotation + steps) }
            if (effective.containsAll(required)) return steps
        }
        return 0
    }

    /** Distancias (nº de aristas del árbol) desde [start] a cada celda, por BFS. */
    private fun bfsDistances(
        start: Int,
        connectors: Array<MutableSet<Direction>>,
        rows: Int,
        cols: Int,
    ): IntArray {
        val dist = IntArray(rows * cols) { -1 }
        val queue = ArrayDeque<Int>()
        dist[start] = 0
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val r = cur / cols
            val c = cur % cols
            for (dir in connectors[cur]) {
                val ni = (r + dir.deltaRow) * cols + (c + dir.deltaCol)
                if (dist[ni] == -1) {
                    dist[ni] = dist[cur] + 1
                    queue.addLast(ni)
                }
            }
        }
        return dist
    }
}
