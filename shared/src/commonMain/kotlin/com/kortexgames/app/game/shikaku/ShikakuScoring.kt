package com.kortexgames.app.game.shikaku

/**
 * # ShikakuScoring — puntuación de un nivel resuelto
 *
 * Misma receta que los demás juegos por niveles (ver `NeonLineEngine.calculateScore`): el
 * **nivel** domina, y sobre él se suman **eficiencia** y **velocidad** y se restan los
 * **reinicios**. Es una función pura para poder fijar el balance con tests.
 *
 * La eficiencia mide cuánto hubo que rectificar: en un Shikaku la solución tiene un número fijo
 * de rectángulos (uno por pista), así que todo trazo de más —un rectángulo borrado, reemplazado o
 * sellado sin cumplir la regla— es una corrección. Resolver "a tanteo" puntúa peor que deducir.
 */
object ShikakuScoring {

    /** Puntos por nivel: la escala dominante; los bonus matizan dentro de un mismo nivel. */
    const val LEVEL_POINTS = 1_000

    /** Bonus máximo por no rectificar. */
    const val EFFICIENCY_BONUS = 400

    /** Bonus máximo por resolver dentro del tiempo objetivo. */
    const val SPEED_BONUS = 300

    /**
     * Tiempo objetivo por celda jugable. Más holgado que en los juegos de trazo (900 ms): aquí
     * cada celda se decide razonando, no recorriéndola. Da ~1 min en 5×5 y ~5 min en 12×12.
     */
    const val TARGET_MS_PER_CELL = 2_500L

    /** Coste de cada reinicio. */
    const val RESTART_PENALTY = 60

    /**
     * Tope del castigo, 1 punto por debajo de [LEVEL_POINTS]: por mucho que se reinicie, un nivel
     * resuelto siempre puntúa por encima de la base del nivel anterior.
     */
    const val MAX_PENALTY = LEVEL_POINTS - 1

    /**
     * Fracción de trazos que fueron definitivos: `pistas / (pistas + correcciones)`.
     * `1.0` = cada rectángulo se colocó bien a la primera.
     */
    fun efficiency(clueCount: Int, corrections: Int): Double {
        val total = clueCount + corrections.coerceAtLeast(0)
        return if (total <= 0) 1.0 else clueCount.toDouble() / total
    }

    /**
     * Puntuación final.
     *
     * @param playableCells celdas habilitadas del tablero; fija el tiempo objetivo.
     * @param elapsedMs tiempo activo de juego (sin pausas).
     * @param efficiency resultado de [efficiency], en 0..1.
     */
    fun score(level: Int, playableCells: Int, elapsedMs: Long, efficiency: Double, restarts: Int): Int {
        val targetMs = playableCells.coerceAtLeast(1) * TARGET_MS_PER_CELL
        val speed = (targetMs.toDouble() / elapsedMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val penalty = (restarts * RESTART_PENALTY).coerceAtMost(MAX_PENALTY)
        val score = level * LEVEL_POINTS + EFFICIENCY_BONUS * efficiency.coerceIn(0.0, 1.0) + SPEED_BONUS * speed - penalty
        return score.toInt().coerceAtLeast(0)
    }
}
