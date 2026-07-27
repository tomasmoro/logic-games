package com.example.kortexgames.game.neonsudoku

/**
 * Utilidades del **banco empaquetado** de puzzles: la ubicación del recurso y el
 * parseo de su formato CSV plano.
 *
 * El banco se genera offline con `tools/sudoku/generate_bank.py` y se versiona
 * como recurso en `commonMain/composeResources/files/` (misma fuente única que
 * los audios, ver [com.example.kortexgames.core.audio.SoundEffect]). Es el
 * **seed offline**: garantiza que el modo invitado 100% local siempre tenga
 * puzzles, aunque nunca haya habido conexión (la nube solo lo enriquece, FASE 2).
 *
 * Se separa el parseo (puro, sin dependencias de plataforma) de la lectura del
 * recurso (suspend, en la implementación del repositorio) para poder probarlo de
 * forma aislada.
 */
object SudokuBank {

    /** Ruta del recurso del seed, relativa a `composeResources` (ver `Res.readBytes`). */
    const val SEED_RESOURCE_PATH = "files/sudoku_bank.csv"

    /**
     * Parsea el CSV del banco a puzzles de dominio. Cada línea no vacía es
     * `ordinal,puzzle,solution,id`, donde `ordinal` es el de [SudokuDifficulty]
     * (FACIL=0..EXPERTO=3), `puzzle`/`solution` son 81 caracteres y `id` un UUID.
     *
     * Es tolerante a líneas en blanco (p. ej. el salto final del archivo) y salta
     * —sin abortar— cualquier fila malformada: un banco parcialmente corrupto debe
     * degradar a "menos puzzles", nunca tumbar el arranque del juego. La validez
     * fina (unicidad de solución) se garantiza en generación, no aquí.
     */
    fun parse(csv: String): List<SudokuPuzzle> =
        csv.lineSequence()
            .mapNotNull { line ->
                val row = line.trim()
                if (row.isEmpty()) return@mapNotNull null
                val parts = row.split(',')
                if (parts.size != 4) return@mapNotNull null
                val ordinal = parts[0].toIntOrNull() ?: return@mapNotNull null
                val difficulty = SudokuDifficulty.entries.getOrNull(ordinal) ?: return@mapNotNull null
                val puzzle = parts[1]
                val solution = parts[2]
                if (puzzle.length != NeonSudokuConfig.CELL_COUNT ||
                    solution.length != NeonSudokuConfig.CELL_COUNT
                ) {
                    return@mapNotNull null
                }
                SudokuPuzzle(
                    id = parts[3],
                    difficulty = difficulty,
                    puzzle = puzzle,
                    solution = solution,
                )
            }
            .toList()
}
