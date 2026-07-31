package com.kortexgames.app.game

import com.kortexgames.app.game.defuser.MineDifficulty
import com.kortexgames.app.game.neonsudoku.SudokuDifficulty

/**
 * Qué juegos separan su **ranking mundial por dificultad** y cómo se llama cada
 * nivel de dificultad de cara al jugador.
 *
 * ## El problema que resuelve
 * En los juegos donde el jugador ELIGE la dificultad antes de empezar, una tabla
 * única premia jugar en fácil. Neon Defuser y Neon Sudoku Matrix usan el mismo
 * `BASE_SCORE = 1000` para sus cuatro dificultades y solo descuentan tiempo (y
 * errores/ayudas), así que un tablero fácil resuelto en un minuto puntúa por encima
 * de un experto resuelto en cinco: quien acepta el reto de verdad queda
 * estructuralmente fuera del top. La solución es la de toda la vida en Buscaminas y
 * Sudoku — **el récord de Experto es una tabla aparte de la de Principiante**.
 *
 * ## Por qué es una lista explícita y no automática
 * Se podría deducir del propio `difficulty_level` de cada partida, pero entonces
 * cualquier juego que empezara a variarlo partiría su tabla en cuatro sin que nadie
 * lo hubiera decidido. Separar el ranking es una decisión de diseño de cada juego,
 * así que se declara aquí, a la vista, y el backend se limita a obedecer
 * (`get_game_ranking(p_difficulty_level)`, migración 0028).
 *
 * Los rótulos salen de los propios `enum` de dificultad de cada juego, no de una
 * copia: así no pueden desincronizarse si mañana se renombra un nivel o se añade uno.
 */
object GameRankingScopes {

    /**
     * Juego → nombres de sus dificultades, en el mismo orden que su `enum`. El índice
     * dentro de la lista es `difficultyLevel - 1`, que es como ambos juegos rellenan
     * `GameResult.difficultyLevel` (`difficulty.ordinal + 1`).
     */
    private val difficultyNames: Map<String, List<String>> = mapOf(
        GameIds.NEON_DEFUSER to MineDifficulty.entries.map { it.displayName },
        GameIds.NEON_SUDOKU_MATRIX to SudokuDifficulty.entries.map { it.displayName },
    )

    /**
     * ¿El ranking de [gameId] se separa por dificultad? Si es false, todas las
     * partidas del juego compiten en una única tabla (el caso normal: la inmensa
     * mayoría arranca siempre en `difficultyLevel = 1`).
     */
    fun isRankedByDifficulty(gameId: String): Boolean = gameId in difficultyNames

    /**
     * Nombre de la dificultad [difficultyLevel] (1-based) de [gameId], para rotular
     * el ranking ("Ranking · Experto").
     *
     * @return null si el juego no separa por dificultad, o si el nivel cae fuera del
     *   enum (partidas antiguas o datos corruptos): en ese caso la UI simplemente no
     *   pone rótulo, que es preferible a inventar uno.
     */
    fun difficultyLabel(gameId: String, difficultyLevel: Int): String? =
        difficultyNames[gameId]?.getOrNull(difficultyLevel - 1)
}
