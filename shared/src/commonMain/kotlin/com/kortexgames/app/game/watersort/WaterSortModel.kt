package com.kortexgames.app.game.watersort

import kotlin.random.Random

/**
 * # Modelo puro de "Ordena las Pociones" (Water Sort)
 *
 * Responsabilidad: representar el estado del tablero (tubos con segmentos de
 * color) y las reglas de vertido, **sin ninguna dependencia de framework** para
 * que sea 100% testeable y compartible. La UI mapea el índice de color a un
 * `Color` de `LogicColors`; aquí un color es solo un `Int` estable (0..n-1).
 *
 * Decisiones clave:
 *  - Un tubo es una **pila**: `segments[0]` es el fondo y `segments.last()` es la
 *    parte superior (lo próximo que se vierte). Inmutable: cada vertido produce
 *    tubos nuevos, encaja con el estilo `data class` inmutable de la app.
 *  - La generación de niveles es **resoluble por verificación**: se baraja y se
 *    comprueba con un solver DFS; así garantizamos que SIEMPRE hay solución sin
 *    depender de heurísticas frágiles (ver [WaterSortGenerator]).
 */

/** Capacidad por defecto de un tubo (segmentos de color). El clásico usa 4. */
const val TUBE_CAPACITY: Int = 4

/**
 * Un tubo de ensayo: pila de segmentos de color de abajo (`first`) a arriba
 * (`last`). Vacío = sin segmentos; lleno = [capacity] segmentos.
 *
 * @property segments colores apilados; `last()` es el color superior vertible.
 */
data class Tube(val segments: List<Int> = emptyList()) {

    /** Color de la parte superior (lo próximo a verter), o null si está vacío. */
    val topColor: Int? get() = segments.lastOrNull()

    val isEmpty: Boolean get() = segments.isEmpty()

    fun isFull(capacity: Int): Boolean = segments.size == capacity

    /**
     * ¿El tubo está "resuelto"? Está vacío o lleno de un único color. Es la
     * condición que deben cumplir todos los tubos para ganar.
     */
    fun isComplete(capacity: Int): Boolean =
        isEmpty || (segments.size == capacity && segments.all { it == segments.first() })

    /**
     * Nº de segmentos consecutivos del mismo color contando desde arriba. Es la
     * cantidad que se vierte de una sola vez (regla clásica: "cae todo el bloque
     * del color superior que quepa").
     */
    val topRunLength: Int
        get() {
            val top = topColor ?: return 0
            var count = 0
            for (i in segments.indices.reversed()) {
                if (segments[i] == top) count++ else break
            }
            return count
        }
}

/**
 * Un nivel: la lista de tubos y la capacidad común. [minMoves] es la longitud de
 * una solución encontrada por el generador (no necesariamente la óptima), usada
 * para estimar la precisión y puntuar la eficiencia del jugador.
 */
data class WaterSortLevel(
    val tubes: List<Tube>,
    val capacity: Int = TUBE_CAPACITY,
    val minMoves: Int = 0,
)

/**
 * Reglas de vertido, puras y sin estado. Separadas del motor para poder testear
 * la lógica del juego de forma aislada.
 */
object WaterSortRules {

    /**
     * ¿Se puede verter de [from] a [to]? Requisitos (regla clásica):
     *  - índices distintos y [from] no vacío,
     *  - [to] no está lleno,
     *  - [to] está vacío **o** su color superior coincide con el de [from].
     *
     * No permite verter un tubo ya "resuelto" (uniforme) a otro vacío: sería un
     * movimiento inútil que solo alarga la partida.
     */
    fun canPour(tubes: List<Tube>, from: Int, to: Int, capacity: Int): Boolean {
        if (from == to) return false
        val src = tubes[from]
        val dst = tubes[to]
        if (src.isEmpty || dst.isFull(capacity)) return false
        if (dst.isEmpty) {
            // Verter a un vacío es inútil si el origen ya es uniforme: solo movería
            // el bloque de tubo sin acercarnos a la solución. Podarlo preserva la
            // solubilidad (ese movimiento nunca es necesario) y acelera el solver.
            val srcUniform = src.topRunLength == src.segments.size
            return !srcUniform
        }
        return dst.topColor == src.topColor
    }

    /**
     * Aplica el vertido [from]→[to] (asume [canPour] cierto). Vierte el bloque
     * superior del mismo color que quepa en el destino y devuelve la nueva lista
     * de tubos y cuántos segmentos se movieron.
     */
    fun pour(tubes: List<Tube>, from: Int, to: Int, capacity: Int): PourResult {
        val src = tubes[from]
        val dst = tubes[to]
        val color = src.topColor!!
        val movable = minOf(src.topRunLength, capacity - dst.segments.size)

        val newSrc = Tube(src.segments.dropLast(movable))
        val newDst = Tube(dst.segments + List(movable) { color })

        val result = tubes.toMutableList()
        result[from] = newSrc
        result[to] = newDst
        return PourResult(result, color, movable, dstNowComplete = newDst.isComplete(capacity))
    }

    /** ¿Están todos los tubos resueltos? (condición de victoria). */
    fun isSolved(tubes: List<Tube>, capacity: Int): Boolean =
        tubes.all { it.isComplete(capacity) }
}

/**
 * Resultado de un vertido: el nuevo tablero y metadatos para el feedback/animación.
 *
 * @property color color vertido (para pintar el chorro con su tono neón).
 * @property count segmentos movidos.
 * @property dstNowComplete el destino quedó completo con este vertido (bonus SFX).
 */
data class PourResult(
    val tubes: List<Tube>,
    val color: Int,
    val count: Int,
    val dstNowComplete: Boolean,
)

/**
 * Parámetros de un nivel según la dificultad 1..5. Más colores = más difícil;
 * siempre 2 tubos vacíos de maniobra (equilibrio estándar del género).
 */
data class LevelConfig(val colorCount: Int, val emptyTubes: Int, val capacity: Int) {
    companion object {
        /** Mapea dificultad 1..5 a nº de colores; capacidad y vacíos constantes. */
        fun forDifficulty(difficulty: Int): LevelConfig {
            val colors = when (difficulty.coerceIn(1, 5)) {
                1 -> 3
                2 -> 4
                3 -> 5
                4 -> 6
                else -> 8
            }
            return LevelConfig(colorCount = colors, emptyTubes = 2, capacity = TUBE_CAPACITY)
        }
    }
}

/**
 * Generador de niveles **garantizados resolubles**. Estrategia: repartir todos
 * los segmentos barajados y **verificar con un solver DFS**; si el reparto no
 * tiene solución, se vuelve a barajar. Con 2 tubos vacíos la probabilidad de
 * solubilidad es alta, así que basta con pocos intentos.
 *
 * Es determinista dado un [Random] sembrado → los tests pueden fijar semilla.
 */
object WaterSortGenerator {

    /** Nº máximo de barajados antes de rendirse (defensivo; nunca debería agotarse). */
    private const val MAX_SHUFFLE_ATTEMPTS = 200

    /**
     * Genera un nivel resoluble para la [config] dada, con dificultad **relativa** dentro
     * de un grupo de niveles que comparten tablero.
     *
     * Un solo reparto solventable no distingue "fácil" de "difícil": con la MISMA [config]
     * el reparto puede salir casi ya ordenado o muy enredado. Eso es justo lo que hace falta
     * para poder subir la dificultad SIN agrandar el tablero, necesario en cuanto
     * `colorCount`/`capacity` tocan su tope (ver [com.kortexgames.app.game.watersort.WaterSortEngine.configForLevel]):
     * se generan [hardnessPoolSize] repartos candidatos, se ordenan por
     * [WaterSortLevel.minMoves] (proxy de dificultad: más vertidos en la solución hallada =
     * tablero más enredado) y se devuelve el que ocupa la posición [hardnessRank] (0 = el
     * más fácil del grupo). Con los valores por defecto (`hardnessPoolSize = 1`) se comporta
     * como antes de introducir este parámetro: un único reparto, sin discriminar dificultad.
     *
     * @throws IllegalStateException si algún candidato no logra un reparto resoluble (improbable).
     */
    fun generate(
        config: LevelConfig,
        random: Random = Random.Default,
        hardnessRank: Int = 0,
        hardnessPoolSize: Int = 1,
    ): WaterSortLevel {
        val pool = List(hardnessPoolSize.coerceAtLeast(1)) { generateOne(config, random) }
        val rank = hardnessRank.coerceIn(0, pool.size - 1)
        return pool.sortedBy { it.minMoves }[rank]
    }

    /** Un único reparto barajado y verificado resoluble (sin noción de dificultad relativa). */
    private fun generateOne(config: LevelConfig, random: Random): WaterSortLevel {
        repeat(MAX_SHUFFLE_ATTEMPTS) {
            // Bolsa con `capacity` segmentos de cada color, barajada.
            val bag = buildList {
                for (color in 0 until config.colorCount) repeat(config.capacity) { add(color) }
            }.shuffled(random)

            val filled = bag.chunked(config.capacity).map { Tube(it) }
            val tubes = filled + List(config.emptyTubes) { Tube() }

            val solutionLength = solve(tubes, config.capacity)
            if (solutionLength != null) {
                return WaterSortLevel(tubes, config.capacity, minMoves = solutionLength)
            }
        }
        error("No se pudo generar un nivel resoluble tras $MAX_SHUFFLE_ATTEMPTS intentos")
    }

    /**
     * Solver DFS con memoización de estados visitados. Devuelve la longitud de la
     * PRIMERA solución hallada (no garantiza mínimo) o null si es irresoluble.
     * Suficiente para (1) verificar solubilidad y (2) estimar `minMoves`.
     *
     * Los estados se normalizan (tubos ordenados) para que permutaciones
     * equivalentes se traten como el mismo nodo y no se re-exploren.
     */
    fun solve(tubes: List<Tube>, capacity: Int): Int? {
        val visited = HashSet<String>()
        return dfs(tubes, capacity, visited, depth = 0, maxDepth = tubes.size * capacity * 4)
    }

    private fun dfs(
        tubes: List<Tube>,
        capacity: Int,
        visited: HashSet<String>,
        depth: Int,
        maxDepth: Int,
    ): Int? {
        if (WaterSortRules.isSolved(tubes, capacity)) return depth
        if (depth >= maxDepth) return null
        if (!visited.add(normalize(tubes))) return null

        var best: Int? = null
        for (from in tubes.indices) {
            for (to in tubes.indices) {
                if (from == to) continue
                if (!WaterSortRules.canPour(tubes, from, to, capacity)) continue
                val next = WaterSortRules.pour(tubes, from, to, capacity).tubes
                val found = dfs(next, capacity, visited, depth + 1, maxDepth)
                if (found != null && (best == null || found < best)) best = found
            }
        }
        return best
    }

    /** Clave canónica de un tablero: tubos serializados y ordenados. */
    private fun normalize(tubes: List<Tube>): String =
        tubes.map { it.segments.joinToString(",") }.sorted().joinToString("|")
}
