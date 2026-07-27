package com.example.kortexgames.data.local

import com.example.kortexgames.data.local.db.LogicGamesDb
import com.example.kortexgames.data.local.db.SudokuPuzzleEntity
import com.example.kortexgames.game.neonsudoku.SudokuDifficulty
import com.example.kortexgames.game.neonsudoku.SudokuPuzzle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Implementación de [LocalSudokuPuzzleDataSource] sobre SQLDelight. Común a Android
 * e iOS (solo cambia el driver, ver DatabaseDriverFactory). Mismo patrón que
 * [SqlDelightLocalSavedGameStateDataSource]: mapea entidad ↔ dominio y confina el
 * acceso a la base en [io].
 */
class SqlDelightLocalSudokuPuzzleDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalSudokuPuzzleDataSource {

    private val queries get() = db.sudokuPuzzleQueries

    override suspend fun countByDifficulty(difficulty: SudokuDifficulty): Long = withContext(io) {
        queries.countByDifficulty(difficulty.ordinal.toLong()).executeAsOne()
    }

    override suspend fun idsByDifficulty(difficulty: SudokuDifficulty): Set<String> = withContext(io) {
        queries.allIdsByDifficulty(difficulty.ordinal.toLong()).executeAsList().toSet()
    }

    override suspend fun insertAll(puzzles: List<SudokuPuzzle>): Unit = withContext(io) {
        // Una sola transacción: insertar decenas de filas una a una sin agruparlas
        // dispararía un fsync por fila (lento en el arranque que siembra el banco).
        queries.transaction {
            for (p in puzzles) {
                queries.insertIgnore(
                    id = p.id,
                    difficulty = p.difficulty.ordinal.toLong(),
                    puzzle = p.puzzle,
                    solution = p.solution,
                )
            }
        }
    }

    override suspend fun nextPuzzle(difficulty: SudokuDifficulty): SudokuPuzzle? = withContext(io) {
        queries.selectNextByDifficulty(difficulty.ordinal.toLong()).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun markServed(id: String, servedAt: Long): Unit = withContext(io) {
        queries.markServed(servedAt = servedAt, id = id)
    }

    private fun SudokuPuzzleEntity.toDomain() = SudokuPuzzle(
        id = id,
        // El ordinal se guardó dentro de rango al sembrar; si aun así llegara uno
        // corrupto, cae a FACIL en vez de reventar por índice fuera de rango.
        difficulty = SudokuDifficulty.entries.getOrElse(difficulty.toInt()) { SudokuDifficulty.FACIL },
        puzzle = puzzle,
        solution = solution,
    )
}
