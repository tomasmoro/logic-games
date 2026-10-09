package com.kortexgames.app.game.tents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Reglas del tablero: ciclo de casilla, contacto entre tiendas, contadores y victoria. */
class TentsRulesTest {

    /**
     * Tablero 4×4 de base, con dos árboles (A) y la solución (t) que usan los tests de victoria:
     *
     * ```
     *      1 0 0 1      ← contadores de columna
     *  1   t A . .
     *  0   . . . A
     *  1   . . . t
     *  0   . . . .
     * ```
     */
    private val trees = listOf(TentsPosition(1, 0), TentsPosition(3, 1))

    private fun board(size: Int = 4, treeAt: List<TentsPosition> = trees) = TentsBoard(
        size = size,
        cells = List(size * size) { i ->
            val p = TentsPosition(i % size, i / size)
            TentsCell(p.x, p.y, if (p in treeAt) TentsCellType.TREE else TentsCellType.EMPTY)
        },
    )

    private fun TentsBoard.tent(x: Int, y: Int) = TentsRules.setCell(this, x, y, TentsCellType.TENT)

    @Test
    fun elCicloEsVacioPastoTiendaVacio() {
        assertEquals(TentsCellType.GRASS, TentsCellType.EMPTY.next())
        assertEquals(TentsCellType.TENT, TentsCellType.GRASS.next())
        assertEquals(TentsCellType.EMPTY, TentsCellType.TENT.next())
        assertEquals(TentsCellType.TREE, TentsCellType.TREE.next(), "El árbol no cicla")
    }

    @Test
    fun lasJugadasSinEfectoDevuelvenElMismoTablero() {
        val start = board()
        assertSame(start, TentsRules.setCell(start, 1, 0, TentsCellType.TENT), "Sobre un árbol")
        assertSame(start, TentsRules.setCell(start, 9, 9, TentsCellType.TENT), "Fuera de la rejilla")
        assertSame(start, TentsRules.setCell(start, 0, 0, TentsCellType.EMPTY), "Ya estaba vacía")
        assertSame(start, TentsRules.setCell(start, 0, 0, TentsCellType.TREE), "No se plantan árboles")
    }

    @Test
    fun dosTiendasQueSeTocanQuedanMarcadasYSeLimpianAlSepararse() {
        // En diagonal también cuenta como contacto.
        val touching = board().tent(0, 0).tent(1, 1)
        assertTrue(touching[0, 0].hasConflict)
        assertTrue(touching[1, 1].hasConflict)

        val apart = TentsRules.setCell(touching, 1, 1, TentsCellType.GRASS)
        assertFalse(apart[0, 0].hasConflict, "Al quitar la vecina se apaga el aviso de la que queda")

        val spaced = board().tent(0, 0).tent(2, 0)
        assertFalse(spaced.cells.any { it.hasConflict }, "Con una casilla de por medio no se tocan")
    }

    @Test
    fun losContadoresCuentanSoloTiendas() {
        val played = TentsRules.setCell(board().tent(0, 0).tent(2, 2), 3, 3, TentsCellType.GRASS)
        assertEquals(listOf(1, 0, 1, 0), TentsRules.rowCounts(played))
        assertEquals(listOf(1, 0, 1, 0), TentsRules.columnCounts(played))
    }

    @Test
    fun seGanaAlCumplirLasTresReglasYElPastoNoImporta() {
        val rows = listOf(1, 0, 1, 0)
        val columns = listOf(1, 0, 1, 0)
        // Tiendas en (0,0) —junto al árbol (1,0)— y (2,2)... que no toca al árbol (3,1): falla.
        assertFalse(TentsRules.isSolved(board().tent(0, 0).tent(2, 2), rows, columns), "Árbol sin tienda adyacente")

        val solvedRows = listOf(1, 0, 1, 0)
        val solvedColumns = listOf(1, 0, 0, 1)
        val solved = board().tent(0, 0).tent(3, 2)
        assertTrue(TentsRules.isSolved(solved, solvedRows, solvedColumns))
        assertTrue(
            TentsRules.isSolved(TentsRules.setCell(solved, 2, 3, TentsCellType.GRASS), solvedRows, solvedColumns),
            "El pasto es una nota: no cambia la victoria",
        )
        assertFalse(TentsRules.isSolved(board().tent(0, 0), solvedRows, solvedColumns), "Faltan tiendas")
    }

    @Test
    fun contadoresCorrectosNoBastanSiLasTiendasSeTocan() {
        // Árboles en (0,0) y (2,1); tiendas en (1,0) y (2,0): cada una junto a su árbol y los
        // contadores cuadran, pero son vecinas.
        val start = board(size = 3, treeAt = listOf(TentsPosition(0, 0), TentsPosition(2, 1)))
        val played = start.tent(1, 0).tent(2, 0)
        assertTrue(TentsMatching.isPerfect(played))
        assertFalse(TentsRules.isSolved(played, listOf(2, 0, 0), listOf(0, 1, 1)))
    }

    @Test
    fun unNivelGeneradoSeGanaConSuSolucion() {
        for (n in listOf(1, 5, 9, 16)) {
            val level = TentsLevelGenerator.generate(n)
            var played = TentsBoard.from(level)
            assertFalse(TentsRules.isSolved(played, level.rowTargets, level.columnTargets), "Nivel $n: recién empezado")
            for (tent in level.solution) played = played.tent(tent.x, tent.y)
            assertTrue(TentsRules.isSolved(played, level.rowTargets, level.columnTargets), "Nivel $n")
        }
    }
}
