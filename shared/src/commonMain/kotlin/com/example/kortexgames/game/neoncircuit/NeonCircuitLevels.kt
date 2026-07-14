package com.example.kortexgames.game.neoncircuit

import kotlin.random.Random

/**
 * # Generador procedural de niveles de "Neon Circuit Flow"
 *
 * Cada nivel se **genera** (no se diseña a mano) a partir del número de nivel,
 * usándolo como semilla determinista: pedir el mismo [forNumber] siempre
 * devuelve el mismo tablero, igual que si viniera de un catálogo fijo, pero sin
 * tener que dibujar niveles uno a uno para cubrir una progresión infinita.
 *
 * ## Curva de dificultad
 *
 *  - **Tamaño de tablero**: arranca en [MIN_GRID_SIZE] y sube 1 casilla de lado
 *    cada [LEVELS_PER_GRID_SIZE] niveles, hasta tocar techo en [MAX_GRID_SIZE] y
 *    quedarse ahí (nunca se salta directo al máximo ni decrece).
 *  - **Nº de canales (colores)**: arranca en [BASE_PAIR_COUNT]; al llegar el
 *    tablero a [EXTRA_COLOR_GRID_SIZE]×[EXTRA_COLOR_GRID_SIZE] se añade un canal
 *    más, y al llegar al tamaño máximo se añade otro (dos saltos de dificultad
 *    "extra" bien espaciados, en vez de un color nuevo por nivel que saturaría
 *    el tablero pequeño).
 *
 * ## Cómo se garantiza que el nivel sea resoluble (el "porqué" del algoritmo)
 *
 * Generar pares de nodos al azar y esperar que exista una solución sin cruces
 * es frágil (hay que resolver el nivel para comprobarlo, y muchas
 * combinaciones no tienen solución). En su lugar se construye el nivel **al
 * revés, desde una solución garantizada**:
 *
 *  1. Se traza un **camino hamiltoniano** sobre el tablero: una ruta que visita
 *     cada celda exactamente una vez ([hamiltonianPath]). Al cubrir el 100% de
 *     las celdas, cualquier sub-tramo contiguo de ese camino es, por
 *     construcción, un cable válido sin cruces.
 *  2. Se **corta** ese camino en tantos tramos contiguos como canales necesite
 *     el nivel ([splitIntoSegments]). Cada tramo es la solución de un color.
 *  3. Los **nodos** del nivel son solo los dos extremos de cada tramo; el
 *     trazo intermedio es justo lo que el jugador debe redescubrir jugando.
 *
 * Como el camino hamiltoniano cubre el tablero entero, la solución de
 * referencia también deja el tablero 100% lleno: todo nivel generado admite
 * tanto la victoria mínima (pares conectados) como la bonus (tablero lleno).
 *
 * La búsqueda del camino usa la heurística de Warnsdorff (prioriza moverse
 * hacia la celda con menos salidas libres) para minimizar el backtracking en
 * tableros de hasta 9×9; si aun así se queda sin presupuesto de pasos en todos
 * los reintentos, cae a un patrón serpenteante ([serpentinePath]), que es
 * hamiltoniano por construcción en cualquier tablero cuadrado y actúa de red de
 * seguridad determinista.
 */
object NeonCircuitLevels {

    /** Nº de pares de nodos (canales) con los que arranca el tablero mínimo. */
    private const val BASE_PAIR_COUNT = 3

    /** Lado de tablero a partir del cual se añade un canal extra. */
    private const val EXTRA_COLOR_GRID_SIZE = 7

    /** Nº de niveles que dura cada tamaño de tablero antes de crecer un paso. */
    private const val LEVELS_PER_GRID_SIZE = 2

    /** Reintentos con semillas distintas antes de caer al patrón serpenteante. */
    private const val HAMILTONIAN_ATTEMPTS = 24

    /** Presupuesto de pasos de backtracking por intento, proporcional al tablero. */
    private const val STEP_BUDGET_PER_CELL = 60

    /**
     * Nivel [number] (1-based), generado de forma determinista.
     *
     * "Siguiente nivel" nunca revienta: la progresión es infinita (el tamaño de
     * tablero y el nº de canales se estabilizan al llegar al máximo), y la
     * puntuación sigue reflejando el nº de nivel real jugado.
     */
    fun forNumber(number: Int): CircuitLevel {
        val n = number.coerceAtLeast(1)
        val gridSize = gridSizeForLevel(n)
        val pairCount = pairCountForGridSize(gridSize)
        val seed = n.toLong()

        val path = hamiltonianPath(gridSize, seed)
        val segments = splitIntoSegments(path, pairCount, seed)
        val colors = WireColor.entries.take(pairCount).shuffled(Random(seed * 31 + 7))

        val nodes = segments.flatMapIndexed { index, segment ->
            val color = colors[index]
            listOf(Node(color, segment.first()), Node(color, segment.last()))
        }
        return CircuitLevel(number = n, gridSize = gridSize, nodes = nodes)
    }

    /** Lado del tablero para el nivel [number]: sube 1 cada [LEVELS_PER_GRID_SIZE] niveles. */
    private fun gridSizeForLevel(number: Int): Int {
        val tier = (number - 1) / LEVELS_PER_GRID_SIZE
        return (MIN_GRID_SIZE + tier).coerceAtMost(MAX_GRID_SIZE)
    }

    /** Nº de canales para un tablero de lado [gridSize]: ver curva de dificultad arriba. */
    private fun pairCountForGridSize(gridSize: Int): Int {
        var count = BASE_PAIR_COUNT
        if (gridSize >= EXTRA_COLOR_GRID_SIZE) count++
        if (gridSize >= MAX_GRID_SIZE) count++
        return count
    }

    /** Vecinas ortogonales de la celda dentro de un tablero de lado [gridSize]. */
    private fun GridPosition.orthogonalNeighbors(gridSize: Int): List<GridPosition> =
        listOf(
            GridPosition(row - 1, col),
            GridPosition(row + 1, col),
            GridPosition(row, col - 1),
            GridPosition(row, col + 1),
        ).filter { it.isInside(gridSize) }

    /**
     * Camino que visita cada celda del tablero [gridSize]×[gridSize] exactamente
     * una vez, mediante backtracking con heurística de Warnsdorff y varios
     * reintentos aleatorios (semillados a partir de [seed] para determinismo).
     */
    private fun hamiltonianPath(gridSize: Int, seed: Long): List<GridPosition> {
        val totalCells = gridSize * gridSize
        repeat(HAMILTONIAN_ATTEMPTS) { attempt ->
            val random = Random(seed * 1_000_003L + attempt)
            val start = GridPosition(random.nextInt(gridSize), random.nextInt(gridSize))
            val path = ArrayList<GridPosition>(totalCells)
            val visited = HashSet<GridPosition>(totalCells)
            var stepBudget = totalCells * STEP_BUDGET_PER_CELL

            fun backtrack(current: GridPosition): Boolean {
                path.add(current)
                visited.add(current)
                if (path.size == totalCells) return true

                // Warnsdorff: probar antes las vecinas con menos salidas libres,
                // para no encerrar el camino en una celda sin escapatoria.
                val neighbors = current.orthogonalNeighbors(gridSize)
                    .filter { it !in visited }
                    .shuffled(random)
                    .sortedBy { neighbor ->
                        neighbor.orthogonalNeighbors(gridSize).count { it !in visited }
                    }

                for (next in neighbors) {
                    if (--stepBudget <= 0) return false
                    if (backtrack(next)) return true
                }

                path.removeAt(path.size - 1)
                visited.remove(current)
                return false
            }

            if (backtrack(start)) return path
        }
        return serpentinePath(gridSize)
    }

    /**
     * Patrón serpenteante (izq→der, der→izq, alternando por fila): hamiltoniano
     * por construcción en cualquier tablero cuadrado. Red de seguridad si
     * [hamiltonianPath] agota sus reintentos.
     */
    private fun serpentinePath(gridSize: Int): List<GridPosition> {
        val path = ArrayList<GridPosition>(gridSize * gridSize)
        for (row in 0 until gridSize) {
            val cols = if (row % 2 == 0) 0 until gridSize else (gridSize - 1) downTo 0
            for (col in cols) path.add(GridPosition(row, col))
        }
        return path
    }

    /**
     * Corta [path] en [count] tramos contiguos (de al menos 2 celdas cada uno,
     * para que un tramo siempre tenga dos extremos distintos), repartiendo el
     * resto de celdas al azar entre los tramos según [seed].
     */
    private fun splitIntoSegments(
        path: List<GridPosition>,
        count: Int,
        seed: Long,
    ): List<List<GridPosition>> {
        val random = Random(seed * 7 + 13)
        val lengths = IntArray(count) { 2 }
        var remaining = path.size - 2 * count
        while (remaining > 0) {
            lengths[random.nextInt(count)]++
            remaining--
        }
        lengths.shuffle(random)

        val segments = ArrayList<List<GridPosition>>(count)
        var offset = 0
        for (length in lengths) {
            segments.add(path.subList(offset, offset + length))
            offset += length
        }
        return segments
    }
}
