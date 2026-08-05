package com.kortexgames.app.game.grid

import kotlin.math.abs
import kotlin.random.Random

/**
 * # Caminos sobre cuadrícula: pieza compartida por los juegos de trazado
 *
 * Varios minijuegos del catálogo se apoyan en la MISMA primitiva matemática: un
 * **camino simple** sobre la rejilla (secuencia de celdas contiguas en cruz, sin
 * repetir ninguna). Lo que cambia entre juegos no es el algoritmo, sino **qué se
 * hace con el camino**:
 *
 *  - **Conectores** (`game.neoncircuit`) pide un camino que cubra el tablero
 *    entero (hamiltoniano) y lo **corta en tramos**: cada tramo es la solución de
 *    un canal y sus extremos son los nodos que ve el jugador.
 *  - **Línea Neón** (`game.neonline`) pide un camino que cubra solo un % del
 *    tablero y usa el **complemento**: las celdas que el camino NO tocó se
 *    convierten en obstáculos, y el camino pasa a ser la zona jugable.
 *
 * Por eso el algoritmo se parametriza por **longitud objetivo** ([simplePath]) en
 * vez de asumir "todas las celdas": con `length = gridSize²` es exactamente el
 * generador hamiltoniano que ya usaba Conectores, y con `length` menor sirve al
 * caso de Línea Neón sin ninguna rama extra. Un futuro juego de trazado (laberinto,
 * serpiente con recorrido fijo, ruta de patrullaje) hereda la misma pieza.
 *
 * ## Por qué este algoritmo y no otro
 *
 * Buscar un camino hamiltoniano es NP-completo en general, así que no se resuelve
 * "de frente": se hace **backtracking con la heurística de Warnsdorff** (probar
 * antes la vecina con menos salidas libres). En una rejilla esto evita el fallo
 * típico —dejar una celda descolgada sin escapatoria— y hace que en tableros de
 * hasta 9×9 el camino salga casi siempre al primer intento, sin explosión
 * combinatoria. Aun así hay tres redes de seguridad, porque un generador de
 * niveles NUNCA puede colgarse ni devolver basura:
 *
 *  1. **Presupuesto de pasos** por intento ([stepBudgetPerCell] × celdas): acota
 *     el backtracking en el peor caso.
 *  2. **Reintentos** con semillas distintas ([attempts]): otro punto de partida
 *     suele bastar.
 *  3. **Patrón serpenteante** ([serpentinePath]): hamiltoniano por construcción en
 *     cualquier tablero cuadrado, así que un prefijo suyo es siempre un camino
 *     simple válido de la longitud pedida. Determinista y sin búsqueda: es la
 *     garantía de que la función SIEMPRE devuelve un camino usable.
 *
 * ## Determinismo
 *
 * Todo depende de la `seed` que pasa el llamante (normalmente derivada del número
 * de nivel): el mismo nivel produce siempre el mismo tablero, como si viniera de
 * un catálogo fijo, pero sin diseñar niveles a mano. Por eso el orden de consumo
 * del [Random] es parte del contrato de facto: cambiarlo regenera tableros que los
 * jugadores ya conocen.
 */

/**
 * Coordenada de celda de una cuadrícula: fila y columna, ambas 0-based.
 *
 * Se usa (row, col) —y no (x, y)— para evitar la ambigüedad clásica de qué eje es
 * cuál: `row` crece hacia abajo y `col` hacia la derecha, tal como se dibuja. La
 * UI traduce px→celda (geometría de layout, suya); el dominio solo habla en celdas.
 *
 * Vive en `game.grid` (y no dentro de un juego) porque es el vocabulario común de
 * todos los juegos de rejilla y de [GridPathBuilder].
 *
 * @property row fila, 0-based.
 * @property col columna, 0-based.
 */
data class GridPosition(val row: Int, val col: Int) {

    /** ¿La celda cae dentro de un tablero cuadrado de lado [size]? */
    fun isInside(size: Int): Boolean = row in 0 until size && col in 0 until size

    /**
     * ¿[other] es vecina ortogonal (arriba/abajo/izquierda/derecha)?
     *
     * La regla de oro de los juegos de trazado es "nada de diagonales": la línea
     * solo avanza a celdas contiguas en cruz. Se comprueba con distancia de
     * Manhattan == 1, que es exactamente el conjunto de las 4 vecinas ortogonales y
     * excluye las diagonales (Manhattan 2) y la propia celda (Manhattan 0).
     */
    fun isOrthogonallyAdjacentTo(other: GridPosition): Boolean =
        abs(row - other.row) + abs(col - other.col) == 1
}

/**
 * Las 4 vecinas ortogonales de la celda que caen dentro de un tablero de lado
 * [gridSize]. Extensión de paquete (no método) para que sea reutilizable sin
 * cargar [GridPosition] con la noción de "tamaño del tablero", que no es suya.
 */
fun GridPosition.orthogonalNeighbors(gridSize: Int): List<GridPosition> =
    listOf(
        GridPosition(row - 1, col),
        GridPosition(row + 1, col),
        GridPosition(row, col - 1),
        GridPosition(row, col + 1),
    ).filter { it.isInside(gridSize) }

/**
 * Las vecinas ortogonales de la celda que pertenecen a [region]. Es el "grado" de
 * la celda dentro de una zona jugable arbitraria (con obstáculos alrededor), base
 * de las métricas de dificultad de los generadores.
 */
fun GridPosition.orthogonalNeighborsIn(region: Set<GridPosition>): List<GridPosition> =
    listOf(
        GridPosition(row - 1, col),
        GridPosition(row + 1, col),
        GridPosition(row, col - 1),
        GridPosition(row, col + 1),
    ).filter { it in region }

/**
 * Orden estable (fila, luego columna) de un conjunto de celdas.
 *
 * **Obligatorio antes de cualquier operación dependiente del orden.** El orden de
 * iteración de un `HashSet` depende de los hashes y del runtime, así que en KMP un
 * mismo `Set` puede recorrerse distinto en JVM/Android que en Native/iOS. Como los
 * generadores usan estos conjuntos con un [Random] semillado, iterar el `Set` en
 * crudo generaría **tableros distintos en cada plataforma** para el mismo nivel: un
 * bug silencioso que solo se ve comparando dos móviles.
 */
fun Set<GridPosition>.inStableOrder(): List<GridPosition> =
    sortedWith(compareBy({ it.row }, { it.col }))

/**
 * Constructor de caminos simples sobre una cuadrícula cuadrada. Ver el "porqué"
 * del algoritmo y de sus redes de seguridad en el encabezado del archivo.
 */
object GridPathBuilder {

    /** Reintentos con semillas distintas antes de caer al patrón serpenteante. */
    const val DEFAULT_ATTEMPTS: Int = 24

    /** Presupuesto de pasos de backtracking por intento, proporcional al tablero. */
    const val DEFAULT_STEP_BUDGET_PER_CELL: Int = 60

    /**
     * Presupuesto por defecto de [hamiltonianPathIn], en **nodos explorados** por
     * intento y por celda.
     *
     * Es mucho mayor que [DEFAULT_STEP_BUDGET_PER_CELL] porque no mide lo mismo:
     * [simplePath] descuenta una unidad por cada rama que prueba, mientras que aquí se
     * descuenta por nodo visitado (ver el porqué dentro de la búsqueda). Con la misma
     * cifra, una región perfectamente resoluble se quedaría sin presupuesto a media
     * exploración y la función devolvería `null` por agotamiento, no por imposibilidad.
     */
    const val DEFAULT_REGION_BUDGET_PER_CELL: Int = 600

    /**
     * ¿Todas las celdas de [region] están conectadas entre sí (una sola pieza)?
     *
     * Condición necesaria para que exista un camino que las recorra todas: si la
     * región está partida en islas, ninguna línea única puede saltar de una a otra.
     */
    fun isConnected(region: Set<GridPosition>): Boolean {
        val start = region.inStableOrder().firstOrNull() ?: return false
        val seen = HashSet<GridPosition>(region.size)
        val queue = ArrayDeque<GridPosition>()
        seen.add(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            for (neighbor in queue.removeFirst().orthogonalNeighborsIn(region)) {
                if (seen.add(neighbor)) queue.add(neighbor)
            }
        }
        return seen.size == region.size
    }

    /**
     * ¿[region] pasa el **test de paridad del tablero de ajedrez**? Condición
     * necesaria (no suficiente) para que exista un camino hamiltoniano.
     *
     * ## El argumento
     *
     * Píntese la rejilla como un tablero de ajedrez: `(fila + columna) % 2` da el
     * color. Todo movimiento en cruz cambia de color —siempre, sin excepción—, así
     * que un camino que visita N celdas alterna colores de principio a fin. Eso obliga
     * a que los dos colores estén casi empatados: si N es par, exactamente N/2 y N/2;
     * si es impar, uno tiene solo uno más. Por tanto, **si una región tiene 2 o más
     * celdas de un color que del otro, no existe ningún camino que las recorra todas**,
     * por muy conectada y bonita que parezca.
     *
     * ## Por qué importa tanto aquí
     *
     * Es una criba de coste O(celdas) que descarta configuraciones de obstáculos
     * imposibles **antes** de lanzarles una búsqueda exponencial. Sin ella, el
     * generador de "Línea Neón" —que coloca los obstáculos primero y busca el camino
     * después— gastaría la mayor parte de su presupuesto explorando a fondo tableros
     * que jamás iban a tener solución. Además permite algo mejor que descartar:
     * **elegir las cuotas de obstáculos por color** para que la paridad cuadre por
     * construcción y casi ninguna configuración nazca condenada.
     */
    fun hasHamiltonianParity(region: Set<GridPosition>): Boolean {
        var even = 0
        var odd = 0
        region.forEach { if ((it.row + it.col) % 2 == 0) even++ else odd++ }
        return abs(even - odd) <= 1
    }

    /**
     * Camino que recorre **todas** las celdas de [region] exactamente una vez (camino
     * hamiltoniano del subgrafo que forman), o `null` si no encuentra ninguno.
     *
     * A diferencia de [simplePath] —que trabaja sobre el tablero entero y siempre
     * devuelve algo— aquí la región la fija el llamante, así que puede sencillamente
     * no tener solución: el `null` es parte del contrato, no un fallo.
     *
     * ## Cómo evita la explosión combinatoria
     *
     * Encontrar un camino hamiltoniano es NP-completo, así que todo está en podar
     * antes de buscar:
     *
     *  1. **Condiciones necesarias baratas** (O(celdas)): región conexa, paridad de
     *     ajedrez ([hasHamiltonianParity]) y como mucho 2 callejones sin salida —una
     *     celda con una sola vecina libre solo puede ser un EXTREMO del camino, y un
     *     camino tiene exactamente dos—. Las tres descartan de un plumazo la inmensa
     *     mayoría de regiones imposibles.
     *  2. **Arranque forzado**: si hay callejones sin salida, el camino TIENE que
     *     empezar en uno de ellos. Eso reduce los puntos de partida de "cualquiera de
     *     las N celdas" a "una de estas dos", que es la poda más rentable de todas.
     *  3. **Warnsdorff + presupuesto de pasos** dentro de la búsqueda, igual que en
     *     [simplePath].
     *
     * @param region celdas a cubrir; el llamante garantiza que no está vacía.
     * @param seed semilla determinista (mismo par (region, seed) → mismo resultado).
     */
    fun hamiltonianPathIn(
        region: Set<GridPosition>,
        seed: Long,
        attempts: Int = DEFAULT_ATTEMPTS,
        stepBudgetPerCell: Int = DEFAULT_REGION_BUDGET_PER_CELL,
    ): List<GridPosition>? {
        val cells = region.inStableOrder()
        if (cells.isEmpty()) return null
        if (cells.size == 1) return cells

        // Poda 1: condiciones necesarias baratas (ver KDoc).
        if (!hasHamiltonianParity(region)) return null
        if (!isConnected(region)) return null
        val deadEnds = cells.filter { it.orthogonalNeighborsIn(region).size <= 1 }
        if (deadEnds.size > 2) return null

        // Poda 2: el arranque no es libre. Con callejones sin salida, el camino TIENE
        // que empezar en uno de ellos (solo pueden ser extremos). Y aun sin callejones,
        // empezar por las celdas de menor grado —esquinas y bordes— es mucho mejor que
        // al azar: son las más fáciles de dejar aisladas si se visitan tarde, así que
        // atacarlas primero evita la mayoría de los callejones sin salida de la
        // búsqueda. Cuando hay solución suele aparecer en los primeros intentos.
        val starts = deadEnds.ifEmpty {
            val random = Random(seed)
            cells.map { cell -> cell to (cell.orthogonalNeighborsIn(region).size to random.nextDouble()) }
                .sortedWith(compareBy({ it.second.first }, { it.second.second }))
                .map { it.first }
        }

        repeat(attempts) { attempt ->
            val random = Random(seed * 1_000_003L + attempt)
            val start = starts[attempt % starts.size]

            val path = ArrayList<GridPosition>(cells.size)
            val visited = HashSet<GridPosition>(cells.size)
            var stepBudget = cells.size * stepBudgetPerCell

            fun backtrack(current: GridPosition): Boolean {
                // El presupuesto se cobra por NODO explorado, no por rama probada: con
                // la poda de conectividad activa, muchas ramas mueren sin llegar a
                // probarse y cobrar solo por rama dejaría al buscador visitar
                // muchísimos más nodos —cada uno con su recorrido en anchura— para el
                // mismo presupuesto nominal. Medido: cobrar por rama disparaba la
                // generación de un nivel de ~0,3 s a ~4 s.
                if (--stepBudget <= 0) return false
                path.add(current)
                visited.add(current)
                if (path.size == cells.size) return true

                // Poda 3: lo que queda por visitar tiene que seguir siendo alcanzable
                // desde la punta. En cuanto un movimiento parte el resto del tablero en
                // dos islas, esa rama está muerta —habrá que abandonar una isla— y no
                // tiene sentido explorarla hasta agotar el presupuesto. Cuesta O(celdas
                // restantes) por paso y lo devuelve con creces: sin ella, verificar un
                // reparto SIN solución consumía el presupuesto entero antes de rendirse,
                // que era el grueso del tiempo de generación de un nivel.
                //
                // Solo vale aquí, no en [simplePath]: esta poda asume que hay que
                // visitarlo TODO, mientras que un camino de longitud parcial puede
                // permitirse dejar celdas fuera.
                if (!remainingIsReachable(current, region, visited, cells.size - path.size)) {
                    path.removeAt(path.size - 1)
                    visited.remove(current)
                    return false
                }

                val neighbors = current.orthogonalNeighborsIn(region)
                    .filter { it !in visited }
                    .shuffled(random)
                    .sortedBy { neighbor ->
                        neighbor.orthogonalNeighborsIn(region).count { it !in visited }
                    }

                for (next in neighbors) {
                    if (backtrack(next)) return true
                    if (stepBudget <= 0) return false
                }

                path.removeAt(path.size - 1)
                visited.remove(current)
                return false
            }

            if (backtrack(start)) return path
        }
        return null
    }

    /**
     * ¿Desde [from] se alcanzan todavía las [remaining] celdas sin visitar de [region]?
     * Recorrido en anchura sobre lo no visitado; ver su porqué en [hamiltonianPathIn].
     */
    private fun remainingIsReachable(
        from: GridPosition,
        region: Set<GridPosition>,
        visited: Set<GridPosition>,
        remaining: Int,
    ): Boolean {
        if (remaining == 0) return true
        val seen = HashSet<GridPosition>(remaining)
        val queue = ArrayDeque<GridPosition>()
        for (neighbor in from.orthogonalNeighborsIn(region)) {
            if (neighbor !in visited && seen.add(neighbor)) queue.add(neighbor)
        }
        var reached = seen.size
        while (queue.isNotEmpty() && reached < remaining) {
            for (neighbor in queue.removeFirst().orthogonalNeighborsIn(region)) {
                if (neighbor !in visited && seen.add(neighbor)) {
                    queue.add(neighbor)
                    reached++
                }
            }
        }
        return reached == remaining
    }

    /**
     * Camino que visita **exactamente [length] celdas distintas** de un tablero
     * `gridSize × gridSize`, moviéndose solo en cruz y sin repetir celda.
     *
     * Garantías del resultado (invariantes de los que dependen los generadores):
     *  - `size == length` acotado a `1..gridSize²`.
     *  - celdas consecutivas son vecinas ortogonales;
     *  - no hay celdas repetidas;
     *  - **nunca lanza ni devuelve vacío**: si la búsqueda agota su presupuesto,
     *    cae al prefijo serpenteante ([serpentinePath]).
     *
     * Corolario que usa Línea Neón: como el camino recorre su propio conjunto de
     * celdas de punta a punta, ES un camino hamiltoniano del subgrafo que forman
     * esas celdas. Tomarlo como zona jugable y declarar obstáculo todo lo demás
     * produce, por construcción, un nivel resoluble.
     *
     * @param gridSize lado del tablero cuadrado (> 0).
     * @param length nº de celdas a cubrir; se acota a `1..gridSize²`. Por defecto,
     *        todas: el caso hamiltoniano clásico ([hamiltonianPath]).
     * @param seed semilla determinista (el mismo par (gridSize, seed) da el mismo camino).
     * @param attempts reintentos con distinto punto de partida antes del fallback.
     * @param stepBudgetPerCell tope de pasos de backtracking por intento y por celda.
     */
    fun simplePath(
        gridSize: Int,
        length: Int = gridSize * gridSize,
        seed: Long,
        attempts: Int = DEFAULT_ATTEMPTS,
        stepBudgetPerCell: Int = DEFAULT_STEP_BUDGET_PER_CELL,
    ): List<GridPosition> {
        require(gridSize > 0) { "gridSize debe ser > 0, era $gridSize" }
        val totalCells = gridSize * gridSize
        val target = length.coerceIn(1, totalCells)

        repeat(attempts) { attempt ->
            val random = Random(seed * 1_000_003L + attempt)
            val start = GridPosition(random.nextInt(gridSize), random.nextInt(gridSize))
            val path = ArrayList<GridPosition>(target)
            val visited = HashSet<GridPosition>(target)
            var stepBudget = totalCells * stepBudgetPerCell

            fun backtrack(current: GridPosition): Boolean {
                path.add(current)
                visited.add(current)
                if (path.size == target) return true

                // Warnsdorff: probar antes las vecinas con menos salidas libres,
                // para no encerrar el camino en una celda sin escapatoria. El
                // `shuffled` previo desempata al azar entre vecinas con el mismo
                // nº de salidas (`sortedBy` es estable), que es lo que da variedad
                // de trazados sin renunciar a la heurística.
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
        // Red de seguridad determinista: un prefijo de la serpentina es un camino
        // simple válido de cualquier longitud pedida (ver encabezado del archivo).
        return serpentinePath(gridSize).take(target)
    }

    /**
     * Camino que visita TODAS las celdas del tablero exactamente una vez (camino
     * hamiltoniano). Azúcar sobre [simplePath] con la longitud máxima: es el caso
     * que necesita Conectores para poder trocearlo en canales.
     */
    fun hamiltonianPath(
        gridSize: Int,
        seed: Long,
        attempts: Int = DEFAULT_ATTEMPTS,
        stepBudgetPerCell: Int = DEFAULT_STEP_BUDGET_PER_CELL,
    ): List<GridPosition> = simplePath(
        gridSize = gridSize,
        length = gridSize * gridSize,
        seed = seed,
        attempts = attempts,
        stepBudgetPerCell = stepBudgetPerCell,
    )

    /**
     * Patrón serpenteante (izq→der, der→izq, alternando por fila): hamiltoniano por
     * construcción en cualquier tablero cuadrado, y por tanto cualquier prefijo suyo
     * es un camino simple válido. Es el fallback determinista de [simplePath].
     */
    fun serpentinePath(gridSize: Int): List<GridPosition> {
        val path = ArrayList<GridPosition>(gridSize * gridSize)
        for (row in 0 until gridSize) {
            val cols = if (row % 2 == 0) 0 until gridSize else (gridSize - 1) downTo 0
            for (col in cols) path.add(GridPosition(row, col))
        }
        return path
    }
}
