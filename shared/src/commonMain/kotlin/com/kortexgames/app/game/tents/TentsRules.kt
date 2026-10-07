package com.kortexgames.app.game.tents

/**
 * # TentsRules — reglas puras del tablero
 *
 * Toda la lógica de Árboles y Tiendas que no depende de corrutinas ni de ciclo de vida: cambiar
 * una casilla, marcar las tiendas que se tocan, contar filas y columnas y decidir la victoria.
 * Vive fuera del `TentsViewModel` (mismo reparto que `ShikakuRules`) para que un test de
 * `commonTest` la ejercite sin `Dispatchers.Main` ni `viewModelScope`.
 */
object TentsRules {

    /**
     * Devuelve el tablero con ([x], [y]) puesta en [target] y los avisos de contacto recalculados.
     *
     * Si la jugada no procede —fuera de la rejilla, sobre un árbol, [target] es
     * [TentsCellType.TREE] o la casilla ya está así— devuelve **la misma instancia**: quien llama
     * distingue "no ha pasado nada" con `===` y no emite ni estado ni sonido.
     */
    fun setCell(board: TentsBoard, x: Int, y: Int, target: TentsCellType): TentsBoard {
        if (!board.isInside(x, y) || target == TentsCellType.TREE) return board
        val current = board[x, y]
        if (current.type == TentsCellType.TREE || current.type == target) return board
        return withConflicts(board.with(current.copy(type = target)))
    }

    /**
     * `true` si alguna de las 8 casillas que rodean a ([x], [y]) tiene una tienda. Es la regla de
     * aislamiento: las tiendas no se tocan ni en ortogonal ni en diagonal.
     */
    fun touchesTent(board: TentsBoard, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                if (board.typeAt(x + dx, y + dy) == TentsCellType.TENT) return true
            }
        }
        return false
    }

    /** Tiendas colocadas en cada fila (índice = `y`). */
    fun rowCounts(board: TentsBoard): List<Int> = List(board.size) { board.tentsInRow(it) }

    /** Tiendas colocadas en cada columna (índice = `x`). */
    fun columnCounts(board: TentsBoard): List<Int> = List(board.size) { board.tentsInColumn(it) }

    /**
     * Victoria: se cumplen a la vez las tres reglas.
     *
     * El orden va de la comprobación más barata a la más cara. Los contadores casi siempre
     * descartan antes de llegar al emparejamiento, que es la única que no se ve casilla a casilla
     * (ver [TentsMatching]). El pasto no interviene: es una nota del jugador.
     */
    fun isSolved(board: TentsBoard, rowTargets: List<Int>, columnTargets: List<Int>): Boolean =
        board.size > 0 &&
            rowCounts(board) == rowTargets &&
            columnCounts(board) == columnTargets &&
            board.cells.none { it.hasConflict } &&
            TentsMatching.isPerfect(board)

    /**
     * Recalcula [TentsCell.hasConflict] en todo el tablero.
     *
     * Se repasa entero (y no solo el vecindario de la casilla cambiada) porque quitar una tienda
     * también apaga el aviso de sus vecinas, y con ≤ 64 casillas el repaso completo es más barato
     * de razonar que un parche incremental. Las casillas que no cambian conservan su instancia.
     */
    private fun withConflicts(board: TentsBoard): TentsBoard = board.copy(
        cells = board.cells.map { cell ->
            val conflict = cell.type == TentsCellType.TENT && touchesTent(board, cell.x, cell.y)
            if (cell.hasConflict == conflict) cell else cell.copy(hasConflict = conflict)
        },
    )
}
