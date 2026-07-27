package com.example.kortexgames.data.local

import com.example.kortexgames.game.neonsudoku.SudokuDifficulty
import com.example.kortexgames.game.neonsudoku.SudokuPuzzle

/**
 * Caché local del banco de puzzles de Sudoku (réplica de solo lectura del
 * catálogo; ver `SudokuPuzzle.sq`). Local-first: es la fuente que sirve las
 * partidas, sembrada desde el seed empaquetado y enriquecida desde Supabase.
 * A diferencia de [LocalPlayerProgressDataSource] no tiene camino de subida.
 */
interface LocalSudokuPuzzleDataSource {

    /** Cuántos puzzles hay cacheados de [difficulty] (para decidir si sembrar/pedir más). */
    suspend fun countByDifficulty(difficulty: SudokuDifficulty): Long

    /** Ids ya presentes de [difficulty] (para no re-descargar lo que ya está). */
    suspend fun idsByDifficulty(difficulty: SudokuDifficulty): Set<String>

    /** Inserta puzzles ignorando duplicados por id (no pisa el `servedAt` local). */
    suspend fun insertAll(puzzles: List<SudokuPuzzle>)

    /**
     * Próximo puzzle a servir de [difficulty] con rotación "no repetir": nunca
     * servidos primero, luego los menos recientes. `null` si no hay ninguno
     * cacheado de esa dificultad todavía.
     */
    suspend fun nextPuzzle(difficulty: SudokuDifficulty): SudokuPuzzle?

    /** Marca [id] como servido ahora ([servedAt] epoch millis), para la rotación. */
    suspend fun markServed(id: String, servedAt: Long)
}
