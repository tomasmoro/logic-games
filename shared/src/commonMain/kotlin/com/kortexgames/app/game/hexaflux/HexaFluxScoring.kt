package com.kortexgames.app.game.hexaflux

/**
 * # HexaFluxScoring — puntuación de un nivel superado
 *
 * Misma receta que los demás juegos por niveles (ver `TentsScoring`): el **nivel**
 * domina, y sobre él se suman **eficiencia** y **velocidad** y se restan los
 * **reinicios**. Función pura, para poder fijar el balance con tests.
 *
 * Los puntos que se ven durante la partida ([HexaFluxUiState.score]) **no** entran
 * aquí directamente. Son la moneda del objetivo de puntuación y el termómetro de los
 * combos, pero su escala depende del tipo de objetivo y del tamaño del tablero; si
 * se sumaran al resultado, un nivel de puntuación valdría siempre más que uno de
 * limpieza del mismo número y el ranking premiaría el tipo de nivel, no al jugador.
 */
object HexaFluxScoring {

    /** Puntos por nivel: la escala dominante; los bonus matizan dentro de un mismo nivel. */
    const val LEVEL_POINTS = 1_000

    /** Bonus máximo por eficiencia. */
    const val EFFICIENCY_BONUS = 400

    /** Bonus máximo por resolver dentro del tiempo objetivo. */
    const val SPEED_BONUS = 300

    /**
     * Tiempo objetivo por jugada disponible. Cada jugada implica elegir pieza,
     * rotarla y buscarle hueco, de ahí que sea más holgado que el de un puzzle de
     * un solo toque por casilla.
     */
    const val TARGET_MS_PER_MOVE = 4_000L

    /** Coste de cada reinicio. */
    const val RESTART_PENALTY = 60

    /**
     * Tope del castigo, 1 punto por debajo de [LEVEL_POINTS]: por mucho que se
     * reinicie, un nivel superado siempre puntúa por encima de la base del anterior.
     */
    const val MAX_PENALTY = LEVEL_POINTS - 1

    /**
     * Eficiencia `0..1` de un nivel superado.
     *
     * No puede medirse igual en los tres objetivos: en supervivencia se gastan todas
     * las jugadas por definición, así que "jugadas sobrantes" valdría siempre cero.
     * Ahí se mide lo despejado que quedó el tablero —aguantar con medio tablero libre
     * es dominar la inundación; aguantar con una celda, sobrevivir de milagro—.
     *
     * En los otros dos, sobrar la mitad de las jugadas ya es eficiencia plena: pedir
     * más obligaría a tener suerte con las piezas, no a jugar mejor.
     *
     * @param freeCells celdas libres al terminar.
     * @param totalCells celdas habilitadas del tablero.
     */
    fun efficiency(win: WinCondition, movesLeft: Int, moveLimit: Int, freeCells: Int, totalCells: Int): Double =
        when (win) {
            is WinCondition.Survive -> freeCells.toDouble() / totalCells.coerceAtLeast(1)
            else -> movesLeft * 2.0 / moveLimit.coerceAtLeast(1)
        }.coerceIn(0.0, 1.0)

    /**
     * Puntuación final.
     *
     * @param moveLimit jugadas del nivel; fija el tiempo objetivo.
     * @param elapsedMs tiempo activo de juego (sin pausas).
     * @param efficiency resultado de [efficiency], en 0..1.
     */
    fun score(level: Int, moveLimit: Int, elapsedMs: Long, efficiency: Double, restarts: Int): Int {
        val targetMs = moveLimit.coerceAtLeast(1) * TARGET_MS_PER_MOVE
        val speed = (targetMs.toDouble() / elapsedMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val penalty = (restarts * RESTART_PENALTY).coerceAtMost(MAX_PENALTY)
        val score = level * LEVEL_POINTS + EFFICIENCY_BONUS * efficiency.coerceIn(0.0, 1.0) + SPEED_BONUS * speed - penalty
        return score.toInt().coerceAtLeast(0)
    }
}
