package com.example.kortexgames.game.blockgrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del núcleo puro de "Neon Block Grid": geometría de piezas, reglas de
 * colocación, detección de líneas simultáneas (con intersección sin duplicar)
 * y la primitiva del Game Over. Sin framework ni corrutinas.
 */
class BlockGridModelTest {

    /** Tablero con solo [filled] ocupadas (Filled con acento arbitrario). */
    private fun boardWith(filled: Set<GridPos>): BoardGrid =
        BoardGrid().write(filled) { BoardCell.Filled(BlockAccent.CYAN) }

    private fun row(r: Int, cols: IntRange = 0 until BOARD_SIZE): Set<GridPos> =
        cols.mapTo(mutableSetOf()) { GridPos(r, it) }

    private fun col(c: Int, rows: IntRange = 0 until BOARD_SIZE): Set<GridPos> =
        rows.mapTo(mutableSetOf()) { GridPos(it, c) }

    // --- Invariantes del catálogo de formas ----------------------------------

    @Test
    fun todaFormaEstaNormalizadaAlOrigenDeSuBoundingBox() {
        // Invariante documentado en PolyominoShape: offsets >= 0 y al menos un
        // bloque en la fila 0 y otro en la columna 0. Si se rompe, el anclaje
        // y el fantasma del drag quedan desplazados.
        for (shape in PolyominoShape.entries) {
            assertTrue(shape.cells.all { it.dRow >= 0 && it.dCol >= 0 }, "$shape con offset negativo")
            assertTrue(shape.cells.any { it.dRow == 0 }, "$shape sin bloque en fila 0")
            assertTrue(shape.cells.any { it.dCol == 0 }, "$shape sin bloque en columna 0")
        }
    }

    // --- Reglas de colocación -------------------------------------------------

    @Test
    fun noSePuedeColocarFueraDelTablero() {
        val board = BoardGrid()
        // LINE5_H anclada en la columna 4: llegaría a la columna 8 (fuera).
        assertFalse(board.canPlace(PolyominoShape.LINE5_H, GridPos(0, 4)))
        assertTrue(board.canPlace(PolyominoShape.LINE5_H, GridPos(0, 3)))
    }

    @Test
    fun noSePuedeColocarSobreCeldasOcupadas() {
        val board = boardWith(setOf(GridPos(1, 1)))
        assertFalse(board.canPlace(PolyominoShape.SQUARE_2, GridPos(0, 0)))
        assertTrue(board.canPlace(PolyominoShape.SQUARE_2, GridPos(2, 2)))
    }

    @Test
    fun lasCeldasEnLimpiezaCuentanComoLibres() {
        // Regla documentada en BoardCell.Clearing: el dominio ya las rompió y
        // el jugador no debe esperar a la animación para seguir jugando.
        val board = BoardGrid().write(setOf(GridPos(0, 0))) { BoardCell.Clearing(BlockAccent.GREEN) }
        assertTrue(board.canPlace(PolyominoShape.DOT, GridPos(0, 0)))
    }

    // --- Detección de líneas completas -----------------------------------------

    @Test
    fun detectaFilaYColumnaSimultaneasSinDuplicarLaInterseccion() {
        val board = boardWith(row(3) + col(5))
        val lines = board.findFullLines()

        assertEquals(setOf(3), lines.rows)
        assertEquals(setOf(5), lines.cols)
        assertEquals(2, lines.count)
        // 8 + 8 celdas menos la intersección (3,5) contada una vez = 15.
        assertEquals(15, lines.cells().size)
    }

    @Test
    fun unaLineaConCeldasEnLimpiezaNoEstaCompleta() {
        val board = boardWith(row(0, 1 until BOARD_SIZE))
            .write(setOf(GridPos(0, 0))) { BoardCell.Clearing(BlockAccent.CYAN) }
        assertEquals(0, board.findFullLines().count)
    }

    @Test
    fun unaFilaIncompletaNoSeDetecta() {
        val board = boardWith(row(2, 0 until BOARD_SIZE - 1))
        assertEquals(0, board.findFullLines().count)
    }

    // --- Puntuación -------------------------------------------------------------

    @Test
    fun elComboSimultaneoPuntuaCuadratico() {
        assertEquals(0, scoreForLines(0))
        assertEquals(10, scoreForLines(1))
        assertEquals(40, scoreForLines(2))
        assertEquals(90, scoreForLines(3))
    }

    // --- Game Over (canPlaceAnywhere) --------------------------------------------

    @Test
    fun enTableroVacioTodaFormaCabe() {
        val board = BoardGrid()
        for (shape in PolyominoShape.entries) {
            assertTrue(board.canPlaceAnywhere(shape), "$shape no cabe en tablero vacío")
        }
    }

    @Test
    fun conUnSoloHuecoSoloCabeElMonomino() {
        // Todo lleno menos (4, 4): DOT cabe, cualquier pieza de 2+ bloques no.
        val allButOne = buildSet {
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) add(GridPos(r, c))
            remove(GridPos(4, 4))
        }
        val board = boardWith(allButOne)

        assertTrue(board.canPlaceAnywhere(PolyominoShape.DOT))
        assertFalse(board.canPlaceAnywhere(PolyominoShape.LINE2_H))
        assertFalse(board.canPlaceAnywhere(PolyominoShape.SQUARE_2))
    }

    @Test
    fun tableroLlenoNoAdmiteNada() {
        val full = buildSet {
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) add(GridPos(r, c))
        }
        val board = boardWith(full)
        assertFalse(board.canPlaceAnywhere(PolyominoShape.DOT))
    }

    // --- Primitiva de escritura ---------------------------------------------------

    @Test
    fun writeSoloTocaLasCeldasIndicadas() {
        val board = BoardGrid().write(setOf(GridPos(0, 0), GridPos(7, 7))) {
            BoardCell.Filled(BlockAccent.VIOLET)
        }
        assertEquals(BoardCell.Filled(BlockAccent.VIOLET), board.cellAt(GridPos(0, 0)))
        assertEquals(BoardCell.Filled(BlockAccent.VIOLET), board.cellAt(GridPos(7, 7)))
        assertEquals(BoardCell.Empty, board.cellAt(GridPos(3, 3)))
    }
}
