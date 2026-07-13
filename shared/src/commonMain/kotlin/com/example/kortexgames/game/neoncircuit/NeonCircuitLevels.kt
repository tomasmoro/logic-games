package com.example.kortexgames.game.neoncircuit

/**
 * # Catálogo de niveles de "Neon Circuit Flow"
 *
 * En FASE 1 solo hay un **nivel de prueba quemado** para poder ejercitar dominio,
 * motor (FASE 2) y render (FASE 3) sin depender aún de un catálogo completo ni de
 * un generador. Los niveles definitivos (5×5 → 8×8, dificultad creciente,
 * verificados por solver) se añadirán más adelante, igual que en Starport.
 */
object NeonCircuitLevels {

    /** Azúcar para declarar un nodo sin repetir `GridPosition(...)`. */
    private fun node(color: WireColor, row: Int, col: Int) =
        Node(color, GridPosition(row, col))

    /**
     * Nivel de prueba 5×5 con 3 pares (cian, magenta, verde).
     *
     * Está diseñado a mano para que sea **resoluble y teselable al 100%**: existe
     * una solución que conecta los 3 pares llenando las 25 celdas, lo que permite
     * probar tanto la victoria mínima (todos conectados) como la bonus (tablero
     * lleno). Una solución de referencia:
     *
     * ```
     *   col →   0   1   2   3   4
     *   row 0   C   C   C   M   M
     *       1   C   G   G   M   M
     *       2   C   G   G   M   M
     *       3   C   G   G   M   M
     *       4   C   C   C   M   M
     * ```
     *
     * - **Cian** (`(0,2)`↔`(4,2)`): baja bordeando por la columna 0 en forma de "C".
     * - **Verde** (`(1,1)`↔`(3,2)`): serpentea el bloque central 3×2.
     * - **Magenta** (`(0,3)`↔`(4,4)`): serpentea el bloque derecho 5×2.
     *
     * Los nodos son solo los extremos; el trazo intermedio lo dibuja el jugador.
     */
    val test5x5: CircuitLevel = CircuitLevel(
        number = 1,
        gridSize = 5,
        nodes = listOf(
            node(WireColor.CYAN, 0, 2),
            node(WireColor.CYAN, 4, 2),
            node(WireColor.GREEN, 1, 1),
            node(WireColor.GREEN, 3, 2),
            node(WireColor.MAGENTA, 0, 3),
            node(WireColor.MAGENTA, 4, 4),
        ),
    )

    /** Todos los niveles diseñados (de momento solo el de prueba). */
    private val levels: List<CircuitLevel> = listOf(test5x5)

    /** Nº de niveles diseñados. La UI lo usa para dimensionar el selector. */
    val count: Int get() = levels.size

    /**
     * Nivel [number] (1-based). Más allá del catálogo se **cicla** sobre los
     * niveles existentes (igual que Starport): así "Siguiente nivel" nunca
     * revienta mientras el catálogo crece, y la puntuación sigue subiendo con el
     * número de nivel real jugado.
     */
    fun forNumber(number: Int): CircuitLevel {
        val n = number.coerceAtLeast(1)
        val base = levels[(n - 1) % levels.size]
        // Se re-numera para que score/récord reflejen el nivel REAL jugado.
        return base.copy(number = n)
    }
}
