package com.kortexgames.app.game.shikaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras del tablero: evaluación, solapes y condición de victoria. */
class ShikakuRulesTest {

    /** 3×2 sin la esquina superior derecha: pistas 2 en (0,0) y 3 en (1,1). */
    private val mask = BoardMask.of(3, 2) { x, y -> !(x == 2 && y == 0) }
    private val clues = listOf(ShikakuCell(0, 0, 2), ShikakuCell(1, 1, 3))

    private fun verdict(left: Int, top: Int, w: Int, h: Int) =
        ShikakuRules.evaluate(ShikakuRect(left, top, w, h), mask, clues)

    @Test
    fun evaluaCadaMotivoDeInvalidez() {
        assertEquals(ShikakuVerdict(ShikakuRectValidity.VALID, 2), verdict(0, 0, 2, 1))
        assertEquals(ShikakuVerdict(ShikakuRectValidity.WRONG_AREA, 3), verdict(1, 1, 2, 1))
        assertEquals(ShikakuVerdict(ShikakuRectValidity.NO_NUMBER), verdict(2, 1, 1, 1))
        assertEquals(ShikakuVerdict(ShikakuRectValidity.MULTIPLE_NUMBERS), verdict(0, 0, 2, 2))
        // Pisa el agujero de (2,0) y se sale por la derecha, respectivamente.
        assertEquals(ShikakuVerdict(ShikakuRectValidity.OUT_OF_MASK), verdict(0, 0, 3, 1))
        assertEquals(ShikakuVerdict(ShikakuRectValidity.OUT_OF_MASK), verdict(2, 1, 2, 1))
    }

    @Test
    fun elRectanguloNuevoReemplazaALosQuePisa() {
        var board = emptyList<ShikakuRectangle>()
        board = ShikakuRules.place(board, ShikakuRect(0, 0, 1, 2), 1, verdict(0, 0, 1, 2))
        board = ShikakuRules.place(board, ShikakuRect(2, 1, 1, 1), 2, verdict(2, 1, 1, 1))
        // Pisa al 1 (columna 0) pero solo toca por el borde al 2.
        board = ShikakuRules.place(board, ShikakuRect(0, 0, 2, 1), 3, verdict(0, 0, 2, 1))

        assertEquals(listOf(2, 3), board.map { it.id })
        for (a in board) for (b in board) if (a !== b) assertFalse(a.bounds.overlaps(b.bounds))
    }

    @Test
    fun losVecinosNoCompartenTinteMientrasHayaTintesLibres() {
        var board = emptyList<ShikakuRectangle>()
        board = ShikakuRules.place(board, ShikakuRect(0, 0, 2, 1), 1, verdict(0, 0, 2, 1))
        board = ShikakuRules.place(board, ShikakuRect(0, 1, 3, 1), 2, verdict(0, 1, 3, 1))
        assertTrue(board[0].tint != board[1].tint)
    }

    @Test
    fun ganaSoloConTodoCubiertoYTodoValido() {
        val empty = ShikakuRules.validate(mask, emptyList())
        assertEquals(5, empty.totalCells)
        assertFalse(empty.isSolved)

        var board = ShikakuRules.place(emptyList(), ShikakuRect(0, 0, 2, 1), 1, verdict(0, 0, 2, 1))
        assertFalse(ShikakuRules.validate(mask, board).isSolved, "Cubierto a medias")

        // Todo cubierto pero con piezas inválidas: 1×2 sobre el "2" (válido), 1×2 con el "3"
        // (área incorrecta) y la celda suelta (sin número).
        val wrong = listOf(
            ShikakuRectangle(1, ShikakuRect(0, 0, 1, 2), ShikakuTint.CYAN, ShikakuRectValidity.VALID),
            ShikakuRectangle(2, ShikakuRect(1, 0, 1, 2), ShikakuTint.MAGENTA, ShikakuRectValidity.WRONG_AREA),
            ShikakuRectangle(3, ShikakuRect(2, 1, 1, 1), ShikakuTint.GREEN, ShikakuRectValidity.NO_NUMBER),
        )
        val wrongVerdict = ShikakuRules.validate(mask, wrong)
        assertEquals(5, wrongVerdict.coveredCells)
        assertFalse(wrongVerdict.isSolved)

        board = ShikakuRules.place(board, ShikakuRect(0, 1, 3, 1), 2, verdict(0, 1, 3, 1))
        assertTrue(ShikakuRules.validate(mask, board).isSolved)
    }

    @Test
    fun unSolapeNoCuentaDosVecesLaMismaCelda() {
        val doubled = listOf(
            ShikakuRectangle(1, ShikakuRect(0, 0, 2, 1), ShikakuTint.CYAN, ShikakuRectValidity.VALID),
            ShikakuRectangle(2, ShikakuRect(0, 0, 2, 1), ShikakuTint.CYAN, ShikakuRectValidity.VALID),
        )
        assertEquals(2, ShikakuRules.validate(mask, doubled).coveredCells)
    }

    @Test
    fun laSolucionDelGeneradorGanaLaPartida() {
        for (n in listOf(1, 2, 3, 9)) {
            val level = ShikakuLevelGenerator.generate(n)
            var board = emptyList<ShikakuRectangle>()
            level.solution.forEachIndexed { i, rect ->
                val v = ShikakuRules.evaluate(rect, level.mask, level.clues)
                assertEquals(ShikakuRectValidity.VALID, v.validity, "Nivel $n: $rect")
                board = ShikakuRules.place(board, rect, i, v)
            }
            assertTrue(ShikakuRules.validate(level.mask, board).isSolved, "Nivel $n")
            assertNull(ShikakuRules.rectangleAt(board, -1, 0))
        }
    }
}
