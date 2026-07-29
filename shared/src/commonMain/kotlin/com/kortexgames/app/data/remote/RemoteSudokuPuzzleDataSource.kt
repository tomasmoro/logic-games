package com.kortexgames.app.data.remote

import com.kortexgames.app.game.neonsudoku.SudokuDifficulty
import com.kortexgames.app.game.neonsudoku.SudokuPuzzle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto al catálogo de puzzles `sudoku_puzzles` en Supabase (ver la
 * migración `0023_*`). Es un **catálogo de solo lectura**: el cliente solo lee
 * (RLS permite `SELECT` a `authenticated`; la escritura es vía `service_role`),
 * para enriquecer la caché local con más variedad de la que trae el seed
 * empaquetado. No sube nada, a diferencia de [RemoteProgressDataSource].
 */
class RemoteSudokuPuzzleDataSource(
    private val client: SupabaseClient,
) {

    /** Fila de `sudoku_puzzles` tal cual la devuelve PostgREST. */
    @Serializable
    private data class PuzzleRow(
        val id: String,
        val difficulty: Int,
        val puzzle: String,
        val solution: String,
    )

    /**
     * Descarga hasta [limit] puzzles de [difficulty]. El orden lo deja el servidor
     * (no importa: la rotación/variedad la resuelve la caché local con `servedAt`).
     * Devuelve lista vacía ante cualquier problema de red para que el llamador siga
     * sirviendo desde el seed local sin romperse (local-first).
     */
    suspend fun fetchByDifficulty(difficulty: SudokuDifficulty, limit: Int): List<SudokuPuzzle> =
        client.postgrest.from("sudoku_puzzles")
            .select {
                filter { eq("difficulty", difficulty.ordinal) }
                limit(limit.toLong())
            }
            .decodeList<PuzzleRow>()
            .map { r ->
                SudokuPuzzle(
                    id = r.id,
                    difficulty = SudokuDifficulty.entries.getOrElse(r.difficulty) { SudokuDifficulty.FACIL },
                    puzzle = r.puzzle,
                    solution = r.solution,
                )
            }
}
