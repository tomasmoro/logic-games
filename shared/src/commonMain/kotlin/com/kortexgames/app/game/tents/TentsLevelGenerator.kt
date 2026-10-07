package com.kortexgames.app.game.tents

import kotlin.random.Random

/**
 * Parámetros de generación de un nivel.
 *
 * Está separado del número de nivel para que la curva de dificultad ([TentsLevelGenerator.configFor])
 * sea una tabla que se puede retocar sin tocar el algoritmo, y para que los tests puedan pedir
 * configuraciones concretas.
 *
 * @property size lado del tablero.
 * @property tents parejas árbol-tienda que se intentan colocar. Es la palanca fina de dificultad
 *   dentro de un mismo tamaño: con pocas, casi todos los contadores son 0 o 1 y el tablero se
 *   resuelve fila a fila; con muchas, los árboles se amontonan y cada uno admite varias tiendas.
 */
data class TentsLevelConfig(val size: Int, val tents: Int) {
    init {
        require(size >= MIN_SIZE) { "Tablero demasiado pequeño: $size" }
        require(tents >= 1) { "Hace falta al menos una tienda" }
        // Cota dura del aislamiento: como mucho una tienda por bloque de 2×2.
        val cap = ((size + 1) / 2) * ((size + 1) / 2)
        require(tents <= cap) { "En un ${size}x$size no caben $tents tiendas sin tocarse (máx. $cap)" }
    }

    private companion object {
        const val MIN_SIZE = 3
    }
}

/**
 * # TentsLevelGenerator — generación procedural por colocación inversa
 *
 * ## Por qué al revés
 *
 * Decidir si un tablero de Árboles y Tiendas tiene solución es un problema de satisfacción de
 * restricciones (la variante general es NP-completa). Generar "hacia delante" —sortear árboles y
 * contadores y comprobar después si encajan— produce casi siempre tableros **contradictorios**:
 * las tres reglas se pisan entre sí (un contador pide dos tiendas en una fila donde el aislamiento
 * solo deja sitio a una, o hay más árboles que huecos legales), y la única forma de saberlo es
 * resolver el tablero entero.
 *
 * La colocación inversa evita el problema de raíz: **se fabrica primero la solución y las pistas
 * se leen de ella**.
 *
 *  1. Se plantan las tiendas una a una en casillas al azar, rechazando toda casilla que toque
 *     (ortogonal o diagonal) a una tienda ya puesta → la regla de aislamiento se cumple.
 *  2. En el mismo paso, cada tienda recibe un árbol propio en un vecino ortogonal libre → existe
 *     un emparejamiento 1:1 árbol↔tienda (el de construcción).
 *  3. Los contadores no se eligen: se **cuentan** las tiendas de cada fila y columna → coinciden
 *     por definición.
 *  4. Se borran las tiendas y quedan árboles y contadores.
 *
 * Las tres reglas se satisfacen simultáneamente con la colocación guardada, así que el nivel es
 * resoluble **por construcción**: no hay contradicción posible porque las pistas no son requisitos
 * independientes que haya que conciliar, sino tres descripciones de un mismo tablero que existe.
 *
 * ### Tienda y árbol se colocan a la vez (y no en dos pasadas)
 *
 * Plantar primero todas las tiendas y repartir después los árboles puede dejar una tienda sin
 * vecino libre (arrinconada entre el borde y árboles ajenos), lo que obliga a deshacer. Colocando
 * la pareja de una vez, una casilla sin vecino libre simplemente se descarta como candidata. Y
 * basta **una sola pasada** por las casillas barajadas: las restricciones solo crecen, de modo que
 * una casilla rechazada nunca vuelve a ser válida más adelante.
 *
 * ## Resoluble no es lo mismo que deducible: la unicidad
 *
 * Que la solución exista no garantiza que sea la única. Si hay dos, en algún punto el jugador no
 * puede deducir y tiene que adivinar — justo lo que este género promete que no ocurre. Por eso
 * cada candidato pasa por [TentsSolver], que **cuenta** soluciones por retroceso (se detiene al
 * encontrar la segunda), y se descartan los ambiguos. Los tableros son pequeños (≤ 64 casillas,
 * de las que solo son candidatas las vecinas de un árbol) y la poda por contadores es muy fuerte,
 * así que cada comprobación cuesta microsegundos y se pueden permitir cientos de intentos.
 *
 * Si se agotan los intentos se entrega el mejor candidato con [TentsLevel.hasUniqueSolution] en
 * `false`. Eso nunca rompe la partida: la victoria se valida contra las reglas, no contra la
 * solución guardada.
 *
 * ## Determinismo
 *
 * Todo el azar sale de un `Random(seed)` (algoritmo fijo y multiplataforma en Kotlin) y la semilla
 * por defecto deriva del nivel ([seedFor]). El nivel N es **el mismo tablero para todos los
 * jugadores y en cada plataforma**: los tiempos son comparables en el ranking y para reanudar una
 * partida basta con regenerarlo.
 *
 * La generación es CPU pura: llámala desde `Dispatchers.Default`, nunca desde el hilo principal.
 */
object TentsLevelGenerator {

    /** Candidatos que se prueban antes de conformarse con el mejor visto. */
    private const val ATTEMPTS = 400

    /** Sal de la semilla ("TENTS" en ASCII): desacopla estos tableros de otros juegos por nivel. */
    private const val SEED_SALT = 0x54454E5453L

    /**
     * Curva de dificultad. Sube una sola cosa cada vez: o crece el tablero, o se aprieta la
     * densidad dentro del mismo tamaño.
     *
     * | Niveles | Lado | Tiendas |
     * |---------|------|---------|
     * | 1       | 5    | 4       |
     * | 2–3     | 5    | 5       |
     * | 4–5     | 6    | 6       |
     * | 6–7     | 6    | 7       |
     * | 8–9     | 7    | 8       |
     * | 10–12   | 7    | 9–10    |
     * | 13–15   | 8    | 11      |
     * | 16+     | 8    | 12      |
     *
     * A partir del 16 la configuración se estabiliza y lo que cambia es el tablero (otra semilla).
     * Las densidades se quedan a propósito por debajo de la saturación (~N²/5 con colocación
     * aleatoria): pedir el máximo haría fallar la mayoría de candidatos por falta de sitio.
     */
    fun configFor(level: Int): TentsLevelConfig {
        val n = level.coerceAtLeast(1)
        return when {
            n == 1 -> TentsLevelConfig(5, 4)
            n <= 3 -> TentsLevelConfig(5, 5)
            n <= 5 -> TentsLevelConfig(6, 6)
            n <= 7 -> TentsLevelConfig(6, 7)
            n <= 9 -> TentsLevelConfig(7, 8)
            n == 10 -> TentsLevelConfig(7, 9)
            n <= 12 -> TentsLevelConfig(7, 10)
            n <= 15 -> TentsLevelConfig(8, 11)
            else -> TentsLevelConfig(8, 12)
        }
    }

    /** Semilla canónica de [level]: la que hace que todos los jugadores reciban el mismo tablero. */
    fun seedFor(level: Int): Long = level.toLong() * -7046029254386353131L xor SEED_SALT

    /**
     * Genera el tablero de [level].
     *
     * @param seed semilla del azar; por defecto la canónica del nivel. Pasar otra sirve para
     *   ofrecer "otro tablero de la misma dificultad" sin cambiar de nivel.
     */
    fun generate(level: Int, seed: Long = seedFor(level)): TentsLevel =
        generate(configFor(level), level, seed)

    /**
     * Genera un tablero con una configuración explícita.
     *
     * @param level número con el que se etiqueta el resultado; no influye en la generación.
     */
    fun generate(config: TentsLevelConfig, level: Int, seed: Long): TentsLevel {
        val rng = Random(seed)
        var best = draft(config, rng)
        var attempt = 1
        while (!best.isIdeal(config) && attempt < ATTEMPTS) {
            val other = draft(config, rng)
            if (other.isBetterThan(best)) best = other
            attempt++
        }
        return best.toLevel(level)
    }

    /** Candidato a nivel: una colocación con el veredicto del solver. */
    private class Draft(
        val size: Int,
        val tent: BooleanArray,
        val tree: BooleanArray,
        val unique: Boolean,
    ) {
        val tentCount: Int = tent.count { it }

        fun isIdeal(config: TentsLevelConfig): Boolean = unique && tentCount == config.tents

        /**
         * Unicidad primero, densidad después: un tablero con una tienda de menos es algo más
         * fácil de lo previsto; uno ambiguo obliga a adivinar, que es un defecto de diseño.
         */
        fun isBetterThan(other: Draft): Boolean =
            if (unique != other.unique) unique else tentCount > other.tentCount

        fun toLevel(level: Int): TentsLevel {
            fun positions(cells: BooleanArray) =
                cells.indices.filter { cells[it] }.map { TentsPosition(it % size, it / size) }
            return TentsLevel(
                level = level,
                size = size,
                trees = positions(tree),
                rowTargets = List(size) { y -> (0 until size).count { x -> tent[y * size + x] } },
                columnTargets = List(size) { x -> (0 until size).count { y -> tent[y * size + x] } },
                solution = positions(tent),
                hasUniqueSolution = unique,
            )
        }
    }

    /**
     * Fabrica un candidato: coloca parejas tienda+árbol sobre las casillas barajadas hasta llegar
     * a [TentsLevelConfig.tents] o quedarse sin sitio, y pregunta al solver si la solución es única.
     */
    private fun draft(config: TentsLevelConfig, rng: Random): Draft {
        val size = config.size
        val tent = BooleanArray(size * size)
        val tree = BooleanArray(size * size)
        val free = IntArray(4)
        var placed = 0

        for (cell in (0 until size * size).shuffled(rng)) {
            if (placed == config.tents) break
            if (tree[cell] || touchesTent(tent, size, cell)) continue
            val x = cell % size
            val y = cell / size
            // Vecinos ortogonales donde cabe el árbol de esta tienda. No hace falta excluir
            // tiendas: si un vecino lo fuera, `touchesTent` ya habría descartado la casilla.
            var freeCount = 0
            for (d in 0 until 4) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx !in 0 until size || ny !in 0 until size) continue
                if (!tree[ny * size + nx]) free[freeCount++] = ny * size + nx
            }
            if (freeCount == 0) continue
            tent[cell] = true
            tree[free[rng.nextInt(freeCount)]] = true
            placed++
        }

        val rows = IntArray(size) { y -> (0 until size).count { x -> tent[y * size + x] } }
        val columns = IntArray(size) { x -> (0 until size).count { y -> tent[y * size + x] } }
        val unique = TentsSolver(size, tree, rows, columns).countSolutions(limit = 2) == 1
        return Draft(size, tent, tree, unique)
    }

    /** `true` si alguna de las 8 casillas que rodean a [cell] ya tiene tienda. */
    private fun touchesTent(tent: BooleanArray, size: Int, cell: Int): Boolean {
        val x = cell % size
        val y = cell / size
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until size && ny in 0 until size && tent[ny * size + nx]) return true
            }
        }
        return false
    }

    private val DX = intArrayOf(0, 1, 0, -1)
    private val DY = intArrayOf(-1, 0, 1, 0)
}

/**
 * Contador de soluciones por retroceso.
 *
 * Solo sirve al generador (comprobar unicidad), por eso es `internal` y trabaja sobre arrays
 * planos en lugar de [TentsBoard].
 *
 * La búsqueda recorre en orden de filas las **casillas candidatas** —las que no son árbol y tienen
 * al menos un árbol al lado; en cualquier otra una tienda quedaría sin pareja— y en cada una
 * decide "tienda" o "nada". Tres podas mantienen el árbol diminuto:
 *
 *  - **aislamiento:** no se planta junto a una tienda ya puesta;
 *  - **contador agotado:** no se planta si la fila o la columna ya tienen las suyas;
 *  - **contador inalcanzable:** tras decidir una casilla, si a su fila o a su columna le quedan
 *    por delante menos candidatas que tiendas pendientes, la rama está muerta.
 *
 * El emparejamiento árbol↔tienda ([TentsMatching]) solo se comprueba en las hojas: es la regla
 * más cara y casi nunca es la que descarta una rama.
 *
 * @param nodeBudget tope de nodos explorados. Es una red de seguridad: si se agota, el tablero se
 *   trata como "unicidad no demostrada" en vez de dejar colgada la generación.
 */
internal class TentsSolver(
    private val size: Int,
    private val tree: BooleanArray,
    rowTargets: IntArray,
    columnTargets: IntArray,
    private val nodeBudget: Int = 200_000,
) {
    private val candidates: IntArray = (0 until size * size).filter { isCandidate(it) }.toIntArray()

    /** Para cada candidata, cuántas candidatas posteriores comparten su fila / su columna. */
    private val rowAhead = IntArray(candidates.size)
    private val columnAhead = IntArray(candidates.size)

    private val rowLeft = rowTargets.copyOf()
    private val columnLeft = columnTargets.copyOf()
    private val trees: IntArray = tree.indices.filter { tree[it] }.toIntArray()
    private val tent = BooleanArray(size * size)
    private var tentCount = 0
    private var nodes = 0
    private var found = 0
    private var limit = 0

    init {
        val rowSeen = IntArray(size)
        val columnSeen = IntArray(size)
        for (i in candidates.indices.reversed()) {
            val x = candidates[i] % size
            val y = candidates[i] / size
            rowAhead[i] = rowSeen[y]++
            columnAhead[i] = columnSeen[x]++
        }
    }

    /**
     * Cuenta soluciones hasta [limit] (con 2 basta para saber si es única).
     *
     * @return el número encontrado, o `-1` si se agotó el presupuesto sin terminar la búsqueda.
     */
    fun countSolutions(limit: Int): Int {
        this.limit = limit
        // Una fila que pide tiendas y no tiene ninguna candidata no la detectaría la poda por
        // casilla (nunca se visita): se descarta aquí.
        val rowCandidates = IntArray(size)
        val columnCandidates = IntArray(size)
        for (c in candidates) {
            rowCandidates[c / size]++
            columnCandidates[c % size]++
        }
        for (i in 0 until size) {
            if (rowLeft[i] > rowCandidates[i] || columnLeft[i] > columnCandidates[i]) return 0
        }
        search(0)
        return if (nodes > nodeBudget && found < limit) -1 else found
    }

    private fun search(i: Int) {
        if (found >= limit || ++nodes > nodeBudget) return
        if (i == candidates.size) {
            // Los contadores ya cuadran (la poda de "inalcanzable" los fuerza a 0 al pasar la
            // última candidata de cada línea); solo falta la regla del emparejamiento.
            if (TentsMatching.isPerfect(size, trees, tentCount) { tent[it] }) found++
            return
        }
        val cell = candidates[i]
        val x = cell % size
        val y = cell / size

        if (rowLeft[y] > 0 && columnLeft[x] > 0 && !touchesPlaced(x, y)) {
            tent[cell] = true
            tentCount++
            rowLeft[y]--
            columnLeft[x]--
            if (reachable(i, x, y)) search(i + 1)
            tent[cell] = false
            tentCount--
            rowLeft[y]++
            columnLeft[x]++
        }
        if (reachable(i, x, y)) search(i + 1)
    }

    /** ¿Quedan candidatas suficientes por delante para completar la fila y la columna de `i`? */
    private fun reachable(i: Int, x: Int, y: Int): Boolean =
        rowAhead[i] >= rowLeft[y] && columnAhead[i] >= columnLeft[x]

    /**
     * Solo mira las casillas ya decididas: la de la izquierda y las tres de la fila de arriba. Las
     * otras cuatro vecinas van después en el recorrido y serán ellas las que miren hacia atrás.
     */
    private fun touchesPlaced(x: Int, y: Int): Boolean {
        if (x > 0 && tent[y * size + x - 1]) return true
        if (y == 0) return false
        val above = (y - 1) * size
        return tent[above + x] || (x > 0 && tent[above + x - 1]) || (x < size - 1 && tent[above + x + 1])
    }

    private fun isCandidate(cell: Int): Boolean {
        if (tree[cell]) return false
        val x = cell % size
        val y = cell / size
        return (x > 0 && tree[cell - 1]) || (x < size - 1 && tree[cell + 1]) ||
            (y > 0 && tree[cell - size]) || (y < size - 1 && tree[cell + size])
    }
}
