package com.kortexgames.app.game.tents

/**
 * # TentsScoring — puntuación de un nivel resuelto
 *
 * Misma receta que los demás juegos por niveles (ver `ShikakuScoring`): el **nivel** domina, y
 * sobre él se suman **eficiencia** y **velocidad** y se restan los **reinicios**. Es una función
 * pura para poder fijar el balance con tests.
 *
 * La eficiencia mide cuánto hubo que rectificar: la solución tiene un número fijo de tiendas (una
 * por árbol), así que toda tienda que se planta y luego se quita es una corrección. El pasto no
 * cuenta en ningún sentido: es la libreta del jugador, y penalizarlo castigaría justo a quien
 * razona con método. Resolver "a tanteo" puntúa peor que deducir.
 */
object TentsScoring {

    /** Puntos por nivel: la escala dominante; los bonus matizan dentro de un mismo nivel. */
    const val LEVEL_POINTS = 1_000

    /** Bonus máximo por no rectificar. */
    const val EFFICIENCY_BONUS = 400

    /** Bonus máximo por resolver dentro del tiempo objetivo. */
    const val SPEED_BONUS = 300

    /**
     * Tiempo objetivo por casilla. Algo más holgado que en Shikaku (2 500 ms): aquí casi todas
     * las casillas piden un toque (pasto o tienda), no solo las de la solución. Da ~1 min 15 s en
     * 5×5 y ~3 min en 8×8.
     */
    const val TARGET_MS_PER_CELL = 3_000L

    /** Coste de cada reinicio. */
    const val RESTART_PENALTY = 60

    /**
     * Tope del castigo, 1 punto por debajo de [LEVEL_POINTS]: por mucho que se reinicie, un nivel
     * resuelto siempre puntúa por encima de la base del nivel anterior.
     */
    const val MAX_PENALTY = LEVEL_POINTS - 1

    /**
     * Fracción de tiendas que fueron definitivas: `tiendas / (tiendas + correcciones)`.
     * `1.0` = ninguna tienda plantada tuvo que retirarse.
     */
    fun efficiency(tentCount: Int, corrections: Int): Double {
        val total = tentCount + corrections.coerceAtLeast(0)
        return if (total <= 0) 1.0 else tentCount.toDouble() / total
    }

    /**
     * Puntuación final.
     *
     * @param cells casillas del tablero (lado²); fija el tiempo objetivo.
     * @param elapsedMs tiempo activo de juego (sin pausas).
     * @param efficiency resultado de [efficiency], en 0..1.
     */
    fun score(level: Int, cells: Int, elapsedMs: Long, efficiency: Double, restarts: Int): Int {
        val targetMs = cells.coerceAtLeast(1) * TARGET_MS_PER_CELL
        val speed = (targetMs.toDouble() / elapsedMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val penalty = (restarts * RESTART_PENALTY).coerceAtMost(MAX_PENALTY)
        val score = level * LEVEL_POINTS + EFFICIENCY_BONUS * efficiency.coerceIn(0.0, 1.0) + SPEED_BONUS * speed - penalty
        return score.toInt().coerceAtLeast(0)
    }
}
