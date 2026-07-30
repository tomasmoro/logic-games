package com.kortexgames.app.game.neonsudoku

/**
 * Un puzzle del banco de Neon Sudoku Matrix: pistas + solución, ya **rateado** a
 * una [SudokuDifficulty] en tiempo de generación (offline), no en el cliente.
 *
 * ## Por qué el banco y no generación en el cliente
 * Generar un Sudoku con solución única y, sobre todo, **ratear su dificultad de
 * verdad** (qué técnicas lógicas exige, no cuántas pistas tiene) es trabajo caro
 * y crítico: se hace una sola vez, offline, con la herramienta
 * `tools/sudoku/generate_bank.py`, que garantiza unicidad y asigna la tier. El
 * cliente solo consume puzzles ya validados vía [SudokuPuzzleRepository] — nunca
 * arriesga arrancar una partida con un tablero irresoluble o mal clasificado.
 *
 * @property id identificador estable del puzzle (UUID). Sirve para deduplicar
 *   entre el seed empaquetado y el banco remoto (FASE 2, Supabase) y para llevar
 *   la rotación "no repetir el último" sin comparar los 81 caracteres.
 * @property difficulty tier a la que el rater lo asignó (banco y selector).
 * @property puzzle las 81 celdas iniciales, fila a fila: dígitos `1-9` son pistas
 *   fijas, `0` celdas vacías. Es lo que consume [Board.fromTemplate].
 * @property solution la solución completa (81 dígitos `1-9`), única. Es la fuente
 *   de verdad que usa [com.kortexgames.app.game.neonsudoku.NeonSudokuViewModel]
 *   para: (1) marcar [SudokuCell.hasConflict] comparando cada valor escrito
 *   directamente contra su dígito de solución (no por duplicados de fila/columna/
 *   bloque), y (2) revelar el dígito correcto de una celda al conceder una pista
 *   (anuncio recompensado) — ninguno de los dos re-resuelve el tablero en el
 *   cliente, ambos leen esta cadena ya calculada offline.
 */
data class SudokuPuzzle(
    val id: String,
    val difficulty: SudokuDifficulty,
    val puzzle: String,
    val solution: String,
)
