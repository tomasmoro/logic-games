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
 * Un **escalón** de dificultad cada [LEVELS_PER_GRID_SIZE] niveles; cada escalón
 * fija a la vez el lado del tablero y el nº de canales (ver [DIFFICULTY_TIERS]):
 *
 *  | Escalón | Niveles | Tablero | Canales |
 *  |--------:|:-------:|:-------:|:-------:|
 *  | 0       | 1–2     | 5×5     | 4       |
 *  | 1       | 3–4     | 6×6     | 4       |
 *  | 2       | 5–6     | 7×7     | 5       |
 *  | 3       | 7–8     | 8×8     | 6       |
 *  | 4       | 9–10    | 9×9     | 7       |
 *  | 5       | 11–12   | 9×9     | 8       |
 *
 * El tablero crece de lado en lado (nunca salta al máximo ni decrece) y los
 * canales suben de forma escalonada, no uno por nivel (saturaría los tableros
 * pequeños). Al tocar el techo (9×9 con 8 canales) la dificultad se estabiliza
 * ahí para siempre, dando una progresión infinita.
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

    /** Nº de niveles que dura cada escalón de dificultad antes de subir. */
    private const val LEVELS_PER_GRID_SIZE = 2

    /**
     * Escalones de dificultad en orden (ver tabla en el KDoc de la clase): cada
     * uno fija (lado de tablero, nº de canales). El último se mantiene para
     * siempre una vez alcanzado, garantizando una progresión infinita.
     */
    private val DIFFICULTY_TIERS = listOf(
        DifficultyTier(gridSize = 5, pairCount = 4),
        DifficultyTier(gridSize = 6, pairCount = 4),
        DifficultyTier(gridSize = 7, pairCount = 5),
        DifficultyTier(gridSize = 8, pairCount = 6),
        DifficultyTier(gridSize = 9, pairCount = 7),
        DifficultyTier(gridSize = 9, pairCount = 8),
    )

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
        val tier = tierForLevel(n)
        val gridSize = tier.gridSize
        val pairCount = tier.pairCount
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

    /**
     * Escalón de dificultad ([gridSize], [pairCount]) del nivel [number]: sube uno
     * cada [LEVELS_PER_GRID_SIZE] niveles y se estanca en el último tramo definido
     * (progresión infinita).
     */
    private fun tierForLevel(number: Int): DifficultyTier {
        val index = (number - 1) / LEVELS_PER_GRID_SIZE
        return DIFFICULTY_TIERS[index.coerceIn(0, DIFFICULTY_TIERS.lastIndex)]
    }

    /**
     * Un escalón de la curva de dificultad: lado del tablero y nº de canales que
     * comparten los niveles de ese tramo.
     *
     * @property gridSize lado del tablero cuadrado ([MIN_GRID_SIZE]..[MAX_GRID_SIZE]).
     * @property pairCount nº de pares de nodos (canales) del nivel; ≤ nº de [WireColor].
     */
    private data class DifficultyTier(val gridSize: Int, val pairCount: Int)

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
