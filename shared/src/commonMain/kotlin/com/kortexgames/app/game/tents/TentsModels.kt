package com.kortexgames.app.game.tents

/**
 * # Neon Trees & Tents — modelos de dominio
 *
 * Tipos puros (sin Compose ni plataforma) que describen un tablero de "Árboles y Tiendas": qué
 * hay en cada casilla ([TentsCell]), la rejilla completa ([TentsBoard]), el estado de un contador
 * de borde ([TentsLineStatus]) y el nivel tal como sale del generador ([TentsLevel]).
 *
 * Decisiones que condicionan el resto del juego:
 *
 *  1. **Todo en coordenadas de celda, nunca en píxeles.** `x` crece hacia la derecha e `y` hacia
 *     abajo, con origen en la esquina superior izquierda (misma convención que Shikaku). La
 *     pantalla convierte el toque a celda en el borde de la UI.
 *  2. **El pasto es una nota del jugador, no una regla.** [TentsCellType.GRASS] y
 *     [TentsCellType.EMPTY] son equivalentes para la validación: ninguno de los dos es una tienda.
 *     Por eso la victoria no exige rellenar de pasto lo que sobra — obligar a ello convertiría el
 *     final de cada nivel en un trámite de toques sin deducción.
 *  3. **"Una tienda por árbol" es un emparejamiento, no una vecindad.** No basta con que cada
 *     árbol tenga alguna tienda al lado: dos árboles no pueden compartir la misma tienda. La regla
 *     real es que exista una asignación 1:1 entre árboles y tiendas ortogonalmente adyacentes
 *     (ver [TentsMatching]). Con solo la comprobación de vecindad se aceptarían tableros falsos.
 */

/**
 * Qué contiene una casilla.
 *
 * El orden `EMPTY → GRASS → TENT` es el del ciclo de toque: el pasto va ANTES que la tienda porque
 * descartar es, con diferencia, la jugada más frecuente del género (hay muchas más casillas sin
 * tienda que con ella), así que debe costar un solo toque.
 */
enum class TentsCellType {
    /** Sin marcar. */
    EMPTY,

    /** Árbol: pista fija del nivel. Nunca cambia ni admite toques. */
    TREE,

    /** Tienda colocada por el jugador. */
    TENT,

    /** Descarte: el jugador anota que aquí no va tienda. No cuenta para ninguna regla. */
    GRASS,
    ;

    /** Siguiente estado del ciclo de toque. Un árbol se devuelve a sí mismo: es inmutable. */
    fun next(): TentsCellType = when (this) {
        EMPTY -> GRASS
        GRASS -> TENT
        TENT -> EMPTY
        TREE -> TREE
    }
}

/** Coordenada de una casilla. Tipo propio (y no `Pair`) para que las firmas digan qué es `x` y qué `y`. */
data class TentsPosition(val x: Int, val y: Int)

/**
 * Casilla del tablero.
 *
 * @property x columna (0-based).
 * @property y fila (0-based).
 * @property type contenido actual.
 * @property hasConflict `true` si es una tienda que toca a otra (en ortogonal o diagonal). Vive
 *   en el modelo, y no se deduce en la UI, porque es el ViewModel quien recorre el tablero tras
 *   cada jugada; la pantalla solo decide cómo pintar el aviso.
 */
data class TentsCell(
    val x: Int,
    val y: Int,
    val type: TentsCellType = TentsCellType.EMPTY,
    val hasConflict: Boolean = false,
)

/**
 * Rejilla cuadrada de [size] × [size] casillas.
 *
 * Es `data class` sobre una `List` inmutable (y no una matriz de arrays) porque vive dentro del
 * `UiState`: debe comparar por valor para que `StateFlow` descarte emisiones idénticas. Las celdas
 * se guardan aplanadas por filas, de modo que cambiar una es copiar una lista de ≤ 64 elementos.
 *
 * @property size lado del tablero, en casillas.
 * @property cells casillas en orden de filas (`índice = y * size + x`).
 */
data class TentsBoard(val size: Int, val cells: List<TentsCell>) {

    init {
        require(size >= 0) { "Lado negativo: $size" }
        require(cells.size == size * size) {
            "El tablero trae ${cells.size} casillas y un ${size}x$size necesita ${size * size}"
        }
    }

    /** `true` si ([x], [y]) cae dentro de la rejilla. */
    fun isInside(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size

    /** Casilla en ([x], [y]). Lanza si está fuera: quien llama debe haber recortado el toque antes. */
    operator fun get(x: Int, y: Int): TentsCell {
        require(isInside(x, y)) { "($x,$y) fuera de un tablero ${size}x$size" }
        return cells[y * size + x]
    }

    /** Contenido de ([x], [y]), o `null` fuera de la rejilla (cómodo al mirar vecinos del borde). */
    fun typeAt(x: Int, y: Int): TentsCellType? = if (isInside(x, y)) cells[y * size + x].type else null

    /** Copia del tablero con [cell] sustituyendo a la que ocupa su misma posición. */
    fun with(cell: TentsCell): TentsBoard {
        require(isInside(cell.x, cell.y)) { "(${cell.x},${cell.y}) fuera de un tablero ${size}x$size" }
        return copy(cells = cells.toMutableList().also { it[cell.y * size + cell.x] = cell })
    }

    /** Tiendas colocadas en la fila [y]. */
    fun tentsInRow(y: Int): Int = (0 until size).count { cells[y * size + it].type == TentsCellType.TENT }

    /** Tiendas colocadas en la columna [x]. */
    fun tentsInColumn(x: Int): Int = (0 until size).count { cells[it * size + x].type == TentsCellType.TENT }

    companion object {
        /** Tablero sin casillas: valor inicial del estado antes de generar el primer nivel. */
        val EMPTY: TentsBoard = TentsBoard(0, emptyList())

        /** Tablero de partida de [level]: solo los árboles, todo lo demás sin marcar. */
        fun from(level: TentsLevel): TentsBoard {
            val trees = level.trees.toSet()
            return TentsBoard(
                size = level.size,
                cells = List(level.size * level.size) { index ->
                    val x = index % level.size
                    val y = index / level.size
                    TentsCell(x, y, if (TentsPosition(x, y) in trees) TentsCellType.TREE else TentsCellType.EMPTY)
                },
            )
        }
    }
}

/**
 * Estado de un contador de borde frente a su objetivo. Se distinguen los tres casos (y no un
 * booleano "cumple") porque la UI reacciona distinto a cada uno: atenuado mientras falta, encendido
 * al clavarlo y en rojo parpadeante al pasarse — pasarse es el único error que la fila delata sola.
 */
enum class TentsLineStatus {
    /** Faltan tiendas. */
    UNDER,

    /** Tiene exactamente las que pide. */
    EXACT,

    /** Tiene más de las que pide. */
    OVER,
    ;

    companion object {
        /** Clasifica [current] tiendas colocadas frente a [target] requeridas. */
        fun of(current: Int, target: Int): TentsLineStatus = when {
            current < target -> UNDER
            current == target -> EXACT
            else -> OVER
        }
    }
}

/**
 * Nivel listo para jugar, tal como lo entrega [TentsLevelGenerator].
 *
 * @property level número de nivel (1-based) del que salió.
 * @property size lado del tablero.
 * @property trees posiciones de los árboles: lo único que el jugador ve en la rejilla al empezar.
 * @property rowTargets tiendas que debe haber en cada fila (índice = `y`).
 * @property columnTargets tiendas que debe haber en cada columna (índice = `x`).
 * @property solution tiendas con las que se construyó el nivel. **No es el criterio de victoria**:
 *   la partida se gana cumpliendo las reglas, coincida o no con esta lista (ver
 *   [hasUniqueSolution]). Se conserva para pistas/ayudas y para los tests.
 * @property hasUniqueSolution `true` si el generador DEMOSTRÓ que [solution] es la única
 *   colocación válida. `false` significa "no demostrado", no necesariamente que haya varias; el
 *   nivel es resoluble en cualquier caso.
 */
data class TentsLevel(
    val level: Int,
    val size: Int,
    val trees: List<TentsPosition>,
    val rowTargets: List<Int>,
    val columnTargets: List<Int>,
    val solution: List<TentsPosition>,
    val hasUniqueSolution: Boolean,
)

/**
 * Emparejamiento árbol↔tienda: la parte de la regla 1 que no se ve mirando casilla a casilla.
 *
 * Lo comparten el solver del generador (para contar soluciones) y la detección de victoria del
 * ViewModel: si cada uno tuviera su propia versión, el generador podría dar por única una solución
 * que la partida no acepta, o al revés.
 */
object TentsMatching {

    /**
     * `true` si se puede asignar a cada árbol una tienda distinta ortogonalmente adyacente, sin
     * que sobre ninguna tienda.
     *
     * @param size lado del tablero.
     * @param trees índices aplanados (`y * size + x`) de los árboles.
     * @param isTent consulta de tienda por índice aplanado.
     * @param tentCount tiendas que hay en el tablero; si no coincide con los árboles no hay 1:1.
     */
    fun isPerfect(size: Int, trees: IntArray, tentCount: Int, isTent: (Int) -> Boolean): Boolean =
        trees.size == tentCount && match(size, trees, requireAll = true, isTent) != null

    /** Versión sobre el tablero de la UI, para la detección de victoria. */
    fun isPerfect(board: TentsBoard): Boolean {
        val tents = board.cells.count { it.type == TentsCellType.TENT }
        return isPerfect(board.size, treesOf(board), tents) { board.cells[it].type == TentsCellType.TENT }
    }

    /**
     * Parejas del emparejamiento **máximo** del tablero tal como está ahora, aunque esté a medio
     * resolver: `índice del árbol → índice de su tienda` (aplanados). Los árboles aún sin tienda
     * no aparecen, y una tienda que no figura como valor no ha podido asignarse a ningún árbol.
     *
     * Es lo que pinta la pantalla como "cuerda de luz" entre cada tienda y su árbol: hace visible
     * la única regla que no se ve mirando casillas sueltas. Al ser máximo (y no voraz), nunca
     * deja suelta una tienda que sí tenía pareja posible.
     */
    fun pairs(board: TentsBoard): Map<Int, Int> {
        val trees = treesOf(board)
        val owner = match(board.size, trees, requireAll = false) { board.cells[it].type == TentsCellType.TENT }
            ?: return emptyMap()
        val result = HashMap<Int, Int>()
        for (tent in owner.indices) if (owner[tent] != -1) result[trees[owner[tent]]] = tent
        return result
    }

    private fun treesOf(board: TentsBoard): IntArray =
        board.cells.indices.filter { board.cells[it].type == TentsCellType.TREE }.toIntArray()

    /**
     * Emparejamiento bipartito árbol↔tienda por caminos de aumento (Kuhn).
     *
     * Un voraz ("a cada árbol su primera tienda libre") falla en configuraciones legítimas: si el
     * árbol A puede usar las tiendas 1 y 2 y el B solo la 1, darle la 1 a A deja a B sin pareja
     * aunque el tablero sea correcto. Con ≤ 16 árboles y ≤ 4 vecinos por árbol el coste es nulo.
     *
     * @param requireAll si es `true`, corta y devuelve `null` en cuanto un árbol se queda sin
     *   tienda (lo que necesita el solver, que llama a esto en cada hoja de su búsqueda).
     * @return `owner[celda]` = posición en [trees] del árbol dueño de la tienda de esa celda, o
     *   `-1` si la celda no es una tienda asignada.
     */
    private fun match(size: Int, trees: IntArray, requireAll: Boolean, isTent: (Int) -> Boolean): IntArray? {
        val owner = IntArray(size * size) { -1 }
        val visited = BooleanArray(size * size)

        fun assign(tree: Int): Boolean {
            val cell = trees[tree]
            val x = cell % size
            val y = cell / size
            for (d in 0 until 4) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx !in 0 until size || ny !in 0 until size) continue
                val tent = ny * size + nx
                if (!isTent(tent) || visited[tent]) continue
                visited[tent] = true
                // La tienda está libre, o su dueño actual puede mudarse a otra: se la queda este.
                if (owner[tent] == -1 || assign(owner[tent])) {
                    owner[tent] = tree
                    return true
                }
            }
            return false
        }

        for (tree in trees.indices) {
            visited.fill(false)
            if (!assign(tree) && requireAll) return null
        }
        return owner
    }

    private val DX = intArrayOf(0, 1, 0, -1)
    private val DY = intArrayOf(-1, 0, 1, 0)
}
