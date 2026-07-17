package com.example.kortexgames.game.starport

/**
 * # Solver BFS de "Neon Starport Escape"
 *
 * Búsqueda en anchura exhaustiva del espacio de estados de un tablero: la usa
 * el generador procedural ([StarportLevels]) para garantizar que cada nivel
 * publicado (1) tiene solución y (2) declara su [StarportLevel.optimalMoves]
 * EXACTO — la puntuación del motor penaliza contra ese mínimo, así que
 * inflarlo regalaría puntos y desinflarlo haría imposible el 100%.
 *
 * ## Codificación del estado (el "porqué" del diseño)
 *
 * Lo único mutable de una partida es la coordenada de eje de cada pieza
 * ([Ship.axisPosition], movimiento 1-D por construcción). Con ≤ [MAX_PIECES]
 * piezas y hangares de lado ≤ [MAX_HANGAR_SIZE] (posiciones 0..9), cada
 * posición cabe en un nibble y el estado COMPLETO en un único `Long`: eso hace
 * que `visited` sea un `HashSet<Long>` compacto y que comparar/almacenar
 * estados no reserve objetos — crítico porque el BFS puede tocar decenas de
 * miles de estados por nivel.
 *
 * ## Semántica de movimiento
 *
 * Un "movimiento" es deslizar UNA pieza cualquier distancia con vía libre
 * (métrica clásica de Rush Hour, la misma que cuenta el motor). Por eso cada
 * posición alcanzable deslizando en línea recta se expande como vecino a
 * profundidad +1, avanzando celda a celda hasta chocar: la física es
 * exactamente la de [freeAxisRange], compartida con el motor.
 */
internal object StarportSolver {

    /** Máx. de piezas por tablero: 15 nibbles de posición caben en un Long. */
    const val MAX_PIECES: Int = 15

    /**
     * Tope de estados visitados antes de rendirse. Un nivel cuyo espacio de
     * estados excede esto se trata como irresoluble a efectos de generación:
     * mejor descartar un candidato raro que colgar el arranque del nivel.
     */
    private const val DEFAULT_STATE_CAP = 60_000

    /** Un paso de la solución: deslizar la pieza [shipId] hasta el eje [toAxis]. */
    data class Move(val shipId: Int, val toAxis: Int)

    /**
     * Resuelve el tablero y devuelve la secuencia MÍNIMA de movimientos que
     * deja a la VIP pegada a la esclusa, o `null` si no hay solución (o se
     * excedió [stateCap]). Devuelve lista vacía si la VIP ya nace libre —
     * el generador usa ese caso para rechazar niveles sin bloqueo.
     */
    fun solve(
        hangarSize: Int,
        exit: HangarExit,
        ships: List<Ship>,
        stateCap: Int = DEFAULT_STATE_CAP,
    ): List<Move>? {
        require(ships.size <= MAX_PIECES) { "el solver codifica ≤ $MAX_PIECES piezas por Long" }
        val n = ships.size
        val vipIdx = ships.indexOfFirst { it.isVip }
        if (vipIdx < 0) return null
        val vip = ships[vipIdx]
        val goalAxis = when (exit.side) {
            ExitSide.LEFT, ExitSide.TOP -> 0
            ExitSide.RIGHT, ExitSide.BOTTOM -> hangarSize - vip.length
        }

        val startPos = IntArray(n) { ships[it].axisPosition }
        if (startPos[vipIdx] == goalAxis) return emptyList()

        val start = encode(startPos, n)
        val visited = HashSet<Long>().apply { add(start) }
        // parent + moveTaken reconstruyen el camino óptimo al alcanzar la meta.
        val parent = HashMap<Long, Long>()
        val moveTaken = HashMap<Long, Move>()
        val queue = ArrayDeque<Long>().apply { add(start) }
        val pos = IntArray(n)
        val grid = IntArray(hangarSize * hangarSize)

        while (queue.isNotEmpty()) {
            val stateKey = queue.removeFirst()
            decode(stateKey, n, pos)

            // Mapa de ocupación del estado (índice de pieza + 1; 0 = libre):
            // hace O(1) el chequeo de cada celda que la pieza va pisando.
            grid.fill(0)
            for (i in 0 until n) {
                forEachCell(ships[i], pos[i], hangarSize) { idx -> grid[idx] = i + 1 }
            }

            for (i in 0 until n) {
                val ship = ships[i]
                for (dir in intArrayOf(-1, +1)) {
                    var target = pos[i] + dir
                    // Desliza celda a celda mientras la pieza completa quepa:
                    // cada parada intermedia es un vecino válido (1 movimiento).
                    while (canPlace(ship, target, i + 1, grid, hangarSize)) {
                        val newKey = withNibble(stateKey, i, target)
                        if (visited.add(newKey)) {
                            parent[newKey] = stateKey
                            moveTaken[newKey] = Move(ship.id, target)
                            if (i == vipIdx && target == goalAxis) {
                                return reconstruct(newKey, start, parent, moveTaken)
                            }
                            queue.add(newKey)
                        }
                        if (visited.size > stateCap) return null
                        target += dir
                    }
                }
            }
        }
        return null
    }

    /** ¿La pieza (que es la nº [selfMark] del grid) cabe entera con su eje en [axis]? */
    private inline fun canPlace(
        ship: Ship,
        axis: Int,
        selfMark: Int,
        grid: IntArray,
        hangarSize: Int,
    ): Boolean {
        if (axis < 0 || axis + ship.length > hangarSize) return false
        var free = true
        forEachCell(ship, axis, hangarSize) { idx ->
            if (grid[idx] != 0 && grid[idx] != selfMark) free = false
        }
        return free
    }

    /** Recorre los índices de celda (row·size+col) de la pieza con su eje en [axis]. */
    private inline fun forEachCell(ship: Ship, axis: Int, hangarSize: Int, action: (Int) -> Unit) {
        for (i in 0 until ship.length) {
            for (w in 0 until ship.width) {
                val idx = when (ship.orientation) {
                    Orientation.HORIZONTAL -> (ship.row + w) * hangarSize + (axis + i)
                    Orientation.VERTICAL -> (axis + i) * hangarSize + (ship.col + w)
                }
                action(idx)
            }
        }
    }

    /** Empaqueta las posiciones de eje en un Long (nibble i = pieza i). */
    private fun encode(pos: IntArray, n: Int): Long {
        var key = 0L
        for (i in 0 until n) key = key or (pos[i].toLong() shl (i * 4))
        return key
    }

    /** Desempaqueta el Long de estado sobre [out] (se reutiliza el array). */
    private fun decode(key: Long, n: Int, out: IntArray) {
        for (i in 0 until n) out[i] = ((key shr (i * 4)) and 0xF).toInt()
    }

    /** Copia del estado con el nibble de la pieza [i] reemplazado por [axis]. */
    private fun withNibble(key: Long, i: Int, axis: Int): Long =
        (key and (0xFL shl (i * 4)).inv()) or (axis.toLong() shl (i * 4))

    /** Camino de movimientos desde [start] hasta [goal] recorriendo los padres. */
    private fun reconstruct(
        goal: Long,
        start: Long,
        parent: Map<Long, Long>,
        moveTaken: Map<Long, Move>,
    ): List<Move> {
        val moves = ArrayList<Move>()
        var cursor = goal
        while (cursor != start) {
            moves.add(moveTaken.getValue(cursor))
            cursor = parent.getValue(cursor)
        }
        moves.reverse()
        return moves
    }
}
