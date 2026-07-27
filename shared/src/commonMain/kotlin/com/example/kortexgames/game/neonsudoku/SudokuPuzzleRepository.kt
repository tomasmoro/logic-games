package com.example.kortexgames.game.neonsudoku

/**
 * Puerto (interfaz) del banco de puzzles de Neon Sudoku Matrix.
 *
 * Sustituye a las plantillas que antes vivían embebidas en el `enum`
 * [SudokuDifficulty]: el ViewModel pide un puzzle por dificultad y no sabe (ni le
 * importa) de dónde sale — seed empaquetado, caché local o Supabase. Igual que el
 * resto de repositorios del proyecto (ver
 * [com.example.kortexgames.domain.repository.ProgressRepository]), la estrategia
 * es **local-first**: la implementación siempre puede servir offline y enriquece
 * el catálogo desde la nube cuando hay sesión.
 *
 * Es un puerto **específico del juego** (vive en su paquete, no en `domain`)
 * porque modela un concepto propio de Sudoku, no una capacidad transversal de la
 * app; el ViewModel depende de esta abstracción igual que de las de `domain`.
 */
interface SudokuPuzzleRepository {

    /**
     * Devuelve un puzzle de [difficulty] listo para jugar. Suspende porque la
     * fuente es asíncrona (recurso empaquetado y/o base local): la generación
     * quedó offline, aquí solo se lee.
     *
     * La implementación evita repetir el último puzzle servido de esa dificultad
     * cuando hay alternativas, para que dos partidas seguidas no salgan iguales
     * (retención: el banco pequeño de antes repetía en pocas sesiones).
     */
    suspend fun randomPuzzle(difficulty: SudokuDifficulty): SudokuPuzzle
}
