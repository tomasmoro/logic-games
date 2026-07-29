package com.kortexgames.app.game.starport

import kotlin.random.Random

/**
 * # Generador procedural de niveles de "Neon Starport Escape"
 *
 * Cada nivel se **genera** (no se diseña a mano) a partir de su número, usado
 * como semilla determinista: pedir el mismo [forNumber] devuelve siempre el
 * mismo tablero, igual que si viniera de un catálogo fijo, pero con progresión
 * infinita — mismo patrón que `NeonCircuitLevels`.
 *
 * ## Curva de dificultad
 *
 *  - **Hangar**: arranca en [HANGAR_SIZE] (6×6) y crece una celda de lado cada
 *    [LEVELS_PER_GRID_STEP] niveles hasta el techo de [MAX_HANGAR_SIZE] (10×10).
 *  - **Esclusa**: el borde ROTA nivel a nivel (derecha → abajo → izquierda →
 *    arriba) y su fila/columna se sortea; la puerta nunca se repite entre
 *    niveles consecutivos. La VIP nace SIEMPRE apuntando a esa esclusa (la
 *    validación de [StarportLevel] lo exige) y con el camino bloqueado por al
 *    menos un meteorito cruzado.
 *  - **Obstáculos**: su número crece dentro de cada tramo de 7 niveles y de
 *    tramo en tramo. Los tamaños grandes entran progresivamente en la mezcla:
 *    4×1 desde el nivel [XLONG_FROM_LEVEL], 2×2 desde [WIDE_FROM_LEVEL] y 3×2
 *    desde [BIG_WIDE_FROM_LEVEL].
 *
 * ## Cómo se garantiza que el nivel sea resoluble (el "porqué" del algoritmo)
 *
 * Colocar piezas al azar no garantiza nada: un tablero puede nacer imposible
 * (o trivial, con la VIP ya libre). Por eso cada candidato pasa por el
 * [StarportSolver] (BFS exhaustivo con la MISMA física del motor, ver
 * [freeAxisRange]) que responde tres preguntas de una vez: si tiene solución,
 * si la salida nace de verdad bloqueada (solución no vacía) y cuál es el
 * mínimo real de movimientos ([StarportLevel.optimalMoves] exacto, contra el
 * que puntúa el motor). Se reintenta con semillas derivadas hasta lograr un
 * candidato a la altura de la dificultad objetivo; si ninguno la alcanza se
 * publica el mejor resoluble encontrado, y como red de seguridad final existe
 * un nivel mínimo determinista ([fallback]) que siempre se puede resolver.
 *
 * Dos reglas de colocación evitan quemar reintentos en tableros sin remedio:
 *
 *  1. **Corredor protegido**: entre el morro de la VIP y la esclusa solo se
 *     admiten piezas PERPENDICULARES. Una pieza paralela ahí dentro solo puede
 *     deslizarse a lo largo del propio corredor: jamás podría apartarse y el
 *     nivel sería irresoluble por construcción.
 *  2. **Tope de densidad** ([MAX_FILL_FRACTION]): un hangar saturado no deja
 *     hueco de maniobra y el BFS lo descartaría igual; cortar antes ahorra el
 *     coste de resolverlo.
 */
object StarportLevels {

    /** Nº de niveles que dura cada lado de hangar antes de crecer una celda. */
    private const val LEVELS_PER_GRID_STEP = 7

    /** Reintentos de generación (semillas derivadas) antes de conformarse con el mejor. */
    private const val GENERATION_ATTEMPTS = 24

    /** Intentos de colocación aleatoria de obstáculos dentro de un mismo tablero. */
    private const val PLACEMENT_TRIES = 300

    /** Fracción máxima de celdas ocupadas: deja aire para maniobrar (y resolver). */
    private const val MAX_FILL_FRACTION = 0.72f

    /** Nivel a partir del cual los meteoritos 4×1 entran en la mezcla. */
    private const val XLONG_FROM_LEVEL = 5

    /** Nivel a partir del cual entran los meteoritos anchos 2×2. */
    private const val WIDE_FROM_LEVEL = 8

    /** Nivel a partir del cual entran los meteoritos grandes 3×2. */
    private const val BIG_WIDE_FROM_LEVEL = 12

    /**
     * Niveles ya generados en esta sesión. La generación es determinista (la
     * semilla es el número de nivel), así que la caché no cambia resultados:
     * solo evita repagar el BFS al reintentar/rejugar un nivel.
     */
    private val cache = HashMap<Int, StarportLevel>()

    /**
     * Nivel [number] (1-based), generado de forma determinista. "Siguiente
     * nivel" nunca revienta: la progresión es infinita (hangar y mezcla de
     * piezas se estabilizan al llegar a sus máximos) y la puntuación sigue
     * reflejando el número real jugado.
     */
    fun forNumber(number: Int): StarportLevel {
        val n = number.coerceAtLeast(1)
        return cache.getOrPut(n) { generate(n) }
    }

    /** Lado del hangar del nivel [number]: crece cada [LEVELS_PER_GRID_STEP] niveles. */
    fun hangarSizeFor(number: Int): Int =
        (HANGAR_SIZE + (number - 1) / LEVELS_PER_GRID_STEP).coerceAtMost(MAX_HANGAR_SIZE)

    // ------------------------------------------------------------- generación

    private fun generate(n: Int): StarportLevel {
        val gridSize = hangarSizeFor(n)
        val band = (n - 1) / LEVELS_PER_GRID_STEP
        val posInBand = (n - 1) % LEVELS_PER_GRID_STEP
        // Dificultad objetivo: rango de óptimo BFS que se acepta al vuelo.
        // El mínimo sube (suave) dentro del tramo de 7 niveles y de tramo en
        // tramo, con un TECHO que baja al crecer el hangar: en tableros
        // grandes la colocación aleatoria rara vez traba óptimos profundos
        // (hay más aire) y ahí la dificultad real ya la ponen el nº de piezas
        // y las distancias — exigir óptimos de tablero denso solo quemaría
        // los reintentos (medido: ~1 s por nivel) para acabar en el fallback.
        val minOptimal = (2 + (posInBand + band) / 2)
            .coerceAtMost(4 + MAX_HANGAR_SIZE - gridSize)
            .coerceAtLeast(2)
        // Techo: sin él, un nivel temprano podía salir con un óptimo desorbitado
        // (p. ej. 10 movimientos en el nivel 3) y romper la curva para novatos.
        val maxOptimal = minOptimal + 4 + band

        // Mejor candidato fuera de rango, por si se agotan los intentos:
        // preferimos el más difícil que no rebase el techo; si todos lo
        // rebasan, el menos pasado (mejor un nivel picante que uno trivial).
        var best: StarportLevel? = null
        fun isBetter(a: StarportLevel, b: StarportLevel?): Boolean {
            if (b == null) return true
            val aOver = a.optimalMoves > maxOptimal
            val bOver = b.optimalMoves > maxOptimal
            return when {
                aOver && bOver -> a.optimalMoves < b.optimalMoves
                aOver != bOver -> bOver
                else -> a.optimalMoves > b.optimalMoves
            }
        }

        repeat(GENERATION_ATTEMPTS) { attempt ->
            val random = Random(n * 1_000_003L + attempt * 7_919L)
            val exit = exitFor(n, gridSize, random)
            val ships = placeShips(n, gridSize, exit, random) ?: return@repeat
            val solution = StarportSolver.solve(gridSize, exit, ships) ?: return@repeat
            // Solución vacía = la VIP nació libre: la esclusa DEBE nacer bloqueada.
            if (solution.isEmpty()) return@repeat
            val candidate = StarportLevel(n, exit, ships, solution.size, gridSize)
            if (candidate.optimalMoves in minOptimal..maxOptimal) return candidate
            if (isBetter(candidate, best)) best = candidate
        }
        return best ?: fallback(n, gridSize)
    }

    /**
     * Esclusa del nivel: el borde rota nivel a nivel (cambia SIEMPRE respecto
     * al anterior) y el índice se sortea lejos de las esquinas, para que
     * siempre quepan bloqueadores cruzados a ambos lados del corredor.
     */
    private fun exitFor(n: Int, gridSize: Int, random: Random): HangarExit {
        val side = ExitSide.entries[(n - 1) % ExitSide.entries.size]
        val index = 1 + random.nextInt(gridSize - 2)
        return HangarExit(side, index)
    }

    /** Huella de un obstáculo candidato (celdas a lo largo × ancho). */
    private data class Footprint(val length: Int, val width: Int)

    /**
     * Mezcla de huellas disponible en el nivel [n]. Las repeticiones son pesos:
     * las piezas clásicas dominan siempre y los tamaños grandes (4×1, 2×2,
     * 3×2) entran tarde y con moderación — son "muros" que marcan el tablero.
     */
    private fun footprintPool(n: Int): List<Footprint> = buildList {
        repeat(4) { add(Footprint(SHIP_LENGTH_SHORT, 1)) }
        repeat(3) { add(Footprint(SHIP_LENGTH_LONG, 1)) }
        if (n >= XLONG_FROM_LEVEL) repeat(2) { add(Footprint(SHIP_LENGTH_XLONG, 1)) }
        if (n >= WIDE_FROM_LEVEL) add(Footprint(SHIP_LENGTH_SHORT, 2))
        if (n >= BIG_WIDE_FROM_LEVEL) add(Footprint(SHIP_LENGTH_LONG, 2))
    }

    /**
     * Disposición candidata: VIP alineada con la esclusa + bloqueador
     * garantizado en el corredor + relleno aleatorio de obstáculos. Devuelve
     * null si el azar no logró una disposición mínima (se reintenta con otra
     * semilla).
     */
    private fun placeShips(
        n: Int,
        gridSize: Int,
        exit: HangarExit,
        random: Random,
    ): List<Ship>? {
        val band = (n - 1) / LEVELS_PER_GRID_STEP
        val posInBand = (n - 1) % LEVELS_PER_GRID_STEP
        val obstacleTarget = (3 + posInBand + band).coerceAtMost(StarportSolver.MAX_PIECES - 1)

        val occupied = HashSet<CellPos>()
        val ships = ArrayList<Ship>()

        // 1) VIP pegada (o casi) a la pared opuesta, apuntando a la esclusa:
        // maximiza el largo del corredor y deja sitio para bloquearlo.
        val vipLength = SHIP_LENGTH_SHORT
        val vipAxis = when (exit.side) {
            ExitSide.RIGHT, ExitSide.BOTTOM -> random.nextInt(2)
            ExitSide.LEFT, ExitSide.TOP -> gridSize - vipLength - random.nextInt(2)
        }
        val vip = when (exit.requiredOrientation) {
            Orientation.HORIZONTAL ->
                Ship(0, Orientation.HORIZONTAL, vipLength, row = exit.index, col = vipAxis, isVip = true)
            Orientation.VERTICAL ->
                Ship(0, Orientation.VERTICAL, vipLength, row = vipAxis, col = exit.index, isVip = true)
        }
        ships += vip
        occupied += vip.occupiedCells

        // Corredor de salida: celdas del carril de la VIP entre su morro y la esclusa.
        val corridor = corridorCells(vip, exit, gridSize)
        if (corridor.isEmpty()) return null

        // 2) Bloqueadores garantizados: piezas perpendiculares cruzando el
        // corredor. El primero es obligatorio (asegura que la salida nunca
        // nazca libre; el solver además rechaza soluciones vacías, doble
        // candado). En tramos altos se cruzan MÁS piezas: en hangares grandes
        // el relleno disperso rara vez traba nada, y cada bloqueador extra
        // sube el mínimo de movimientos alcanzable — es lo que mantiene la
        // dificultad creciendo cuando el tablero ya no puede densificarse.
        var nextId = 1
        val blockerTarget = (1 + (band + 1) / 2).coerceAtMost(corridor.size)
        for (i in 0 until blockerTarget) {
            val blocker = perpendicularBlocker(corridor, exit, gridSize, occupied, random, id = nextId)
            if (blocker == null) {
                if (i == 0) return null // sin bloqueo inicial no hay nivel
                break // los extra son best-effort: sigue con el relleno
            }
            ships += blocker
            occupied += blocker.occupiedCells
            nextId++
        }

        // 3) Relleno aleatorio hasta el objetivo de obstáculos del nivel.
        val pool = footprintPool(n)
        // Los meteoritos anchos son escasos a propósito: más de unos pocos
        // convierten el puzzle en un muro estático sin margen de maniobra.
        var wideBudget = if (n >= WIDE_FROM_LEVEL) 1 + band else 0
        val cellBudget = (gridSize * gridSize * MAX_FILL_FRACTION).toInt()
        var tries = 0
        while (ships.size - 1 < obstacleTarget && tries < PLACEMENT_TRIES) {
            tries++
            val fp = pool.random(random)
            if (fp.width > 1 && wideBudget <= 0) continue
            val orientation =
                if (random.nextBoolean()) Orientation.HORIZONTAL else Orientation.VERTICAL
            val (spanRows, spanCols) = when (orientation) {
                Orientation.HORIZONTAL -> fp.width to fp.length
                Orientation.VERTICAL -> fp.length to fp.width
            }
            if (spanRows > gridSize || spanCols > gridSize) continue
            val ship = Ship(
                id = nextId,
                orientation = orientation,
                length = fp.length,
                row = random.nextInt(gridSize - spanRows + 1),
                col = random.nextInt(gridSize - spanCols + 1),
                width = fp.width,
            )
            val cells = ship.occupiedCells
            if (occupied.size + cells.size > cellBudget) break
            if (cells.any { it in occupied }) continue
            // Regla del corredor: ahí dentro solo caben piezas perpendiculares
            // (una paralela jamás podría apartarse del camino de salida).
            if (orientation == vip.orientation && cells.any { it in corridor }) continue
            ships += ship
            occupied += cells
            nextId++
            if (fp.width > 1) wideBudget--
        }

        // Un tablero con la VIP y un único bloqueador no es un puzzle todavía.
        return ships.takeIf { it.size >= 3 }
    }

    /** Celdas del carril de la VIP entre su morro y la esclusa (el camino de escape). */
    private fun corridorCells(vip: Ship, exit: HangarExit, gridSize: Int): Set<CellPos> =
        when (exit.side) {
            ExitSide.RIGHT -> ((vip.col + vip.length) until gridSize).map { CellPos(exit.index, it) }
            ExitSide.LEFT -> (0 until vip.col).map { CellPos(exit.index, it) }
            ExitSide.BOTTOM -> ((vip.row + vip.length) until gridSize).map { CellPos(it, exit.index) }
            ExitSide.TOP -> (0 until vip.row).map { CellPos(it, exit.index) }
        }.toSet()

    /**
     * Busca sitio para una pieza clásica (1 de ancho) PERPENDICULAR al corredor
     * que cruce alguna de sus celdas: el bloqueo inicial garantizado de la
     * esclusa. Prueba celdas y anclajes barajados hasta encontrar hueco.
     */
    private fun perpendicularBlocker(
        corridor: Set<CellPos>,
        exit: HangarExit,
        gridSize: Int,
        occupied: Set<CellPos>,
        random: Random,
        id: Int,
    ): Ship? {
        val orientation = when (exit.requiredOrientation) {
            Orientation.HORIZONTAL -> Orientation.VERTICAL
            Orientation.VERTICAL -> Orientation.HORIZONTAL
        }
        for (target in corridor.shuffled(random)) {
            val length = if (random.nextBoolean()) SHIP_LENGTH_SHORT else SHIP_LENGTH_LONG
            // Anclajes que hacen pasar la pieza por la celda objetivo.
            val anchors = (0 until length).map { offset ->
                when (orientation) {
                    Orientation.VERTICAL -> CellPos(target.row - offset, target.col)
                    Orientation.HORIZONTAL -> CellPos(target.row, target.col - offset)
                }
            }.shuffled(random)
            for (anchor in anchors) {
                val ship = Ship(id, orientation, length, anchor.row, anchor.col)
                val cells = ship.occupiedCells
                if (!cells.all { it.isInside(gridSize) }) continue
                if (cells.any { it in occupied }) continue
                return ship
            }
        }
        return null
    }

    /**
     * Red de seguridad determinista si TODOS los reintentos fallan (en la
     * práctica inalcanzable): VIP contra la pared opuesta y un único
     * bloqueador perpendicular a mitad de corredor. Resoluble siempre.
     */
    private fun fallback(n: Int, gridSize: Int): StarportLevel {
        val exit = HangarExit(ExitSide.entries[(n - 1) % ExitSide.entries.size], gridSize / 2)
        val vip = when (exit.requiredOrientation) {
            Orientation.HORIZONTAL -> Ship(
                0, Orientation.HORIZONTAL, SHIP_LENGTH_SHORT,
                row = exit.index,
                col = if (exit.side == ExitSide.LEFT) gridSize - SHIP_LENGTH_SHORT else 0,
                isVip = true,
            )
            Orientation.VERTICAL -> Ship(
                0, Orientation.VERTICAL, SHIP_LENGTH_SHORT,
                row = if (exit.side == ExitSide.TOP) gridSize - SHIP_LENGTH_SHORT else 0,
                col = exit.index,
                isVip = true,
            )
        }
        val blocker = when (exit.side) {
            ExitSide.RIGHT -> Ship(1, Orientation.VERTICAL, SHIP_LENGTH_SHORT, row = exit.index, col = gridSize - 2)
            ExitSide.LEFT -> Ship(1, Orientation.VERTICAL, SHIP_LENGTH_SHORT, row = exit.index, col = 1)
            ExitSide.BOTTOM -> Ship(1, Orientation.HORIZONTAL, SHIP_LENGTH_SHORT, row = gridSize - 2, col = exit.index)
            ExitSide.TOP -> Ship(1, Orientation.HORIZONTAL, SHIP_LENGTH_SHORT, row = 1, col = exit.index)
        }
        val ships = listOf(vip, blocker)
        val optimal = StarportSolver.solve(gridSize, exit, ships)?.size ?: 2
        return StarportLevel(n, exit, ships, optimal, gridSize)
    }
}
