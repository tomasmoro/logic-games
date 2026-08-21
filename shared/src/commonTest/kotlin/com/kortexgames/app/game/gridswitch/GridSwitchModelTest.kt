package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.game.grid.GridPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests de [LightGrid]: la conmutación ortogonal (centro/borde/esquina afectan a
 * un nº distinto de vecinas) y las consultas de victoria. Es la base de la que
 * depende TODO lo demás (generador y motor), así que se prueba de forma aislada
 * y explícita, con tableros pequeños donde el resultado se puede verificar a mano.
 */
class GridSwitchModelTest {

    @Test
    fun tocarElCentroConmutaLasCincoCeldas() {
        // Tablero 3×3: el centro (1,1) tiene 4 vecinas ortogonales, las 5 celdas
        // afectadas (ella + vecinas) forman una cruz.
        val grid = LightGrid.solved(3).toggled(GridPosition(1, 1))
        val expectedLit = setOf(
            GridPosition(1, 1), GridPosition(0, 1), GridPosition(2, 1),
            GridPosition(1, 0), GridPosition(1, 2),
        )
        assertEquals(5, grid.litCount)
        expectedLit.forEach { assertTrue(grid.cellAt(it), "$it debería estar encendida") }
        assertFalse(grid.cellAt(GridPosition(0, 0)), "la esquina no debería verse afectada")
    }

    @Test
    fun tocarUnBordeConmutaCuatroCeldas() {
        // (0,1) en un 3×3: borde superior, 3 vecinas ortogonales existentes (no hay
        // fila -1) + ella misma = 4.
        val grid = LightGrid.solved(3).toggled(GridPosition(0, 1))
        assertEquals(4, grid.litCount)
        assertTrue(grid.cellAt(GridPosition(0, 1)))
        assertTrue(grid.cellAt(GridPosition(0, 0)))
        assertTrue(grid.cellAt(GridPosition(0, 2)))
        assertTrue(grid.cellAt(GridPosition(1, 1)))
    }

    @Test
    fun tocarUnaEsquinaConmutaTresCeldas() {
        // (0,0): solo 2 vecinas ortogonales existentes (no hay fila -1 ni columna -1).
        val grid = LightGrid.solved(4).toggled(GridPosition(0, 0))
        assertEquals(3, grid.litCount)
        assertTrue(grid.cellAt(GridPosition(0, 0)))
        assertTrue(grid.cellAt(GridPosition(0, 1)))
        assertTrue(grid.cellAt(GridPosition(1, 0)))
    }

    @Test
    fun tocarLaMismaCeldaDosVecesLaDejaComoEstaba() {
        // Involución: dos toques a la misma celda se autocancelan (base de la
        // garantía de solución del generador, ver su KDoc).
        val original = LightGrid.solved(3).toggled(GridPosition(2, 2))
        val twice = original.toggled(GridPosition(1, 0)).toggled(GridPosition(1, 0))
        assertEquals(original, twice)
    }

    @Test
    fun elOrdenDeLosToquesNoImportaParaElResultadoFinal() {
        val a = LightGrid.solved(3)
            .toggled(GridPosition(0, 0))
            .toggled(GridPosition(2, 2))
            .toggled(GridPosition(1, 1))
        val b = LightGrid.solved(3)
            .toggled(GridPosition(1, 1))
            .toggled(GridPosition(0, 0))
            .toggled(GridPosition(2, 2))
        assertEquals(a, b)
    }

    @Test
    fun elTableroResueltoNoTieneLucesEncendidas() {
        val grid = LightGrid.solved(5)
        assertTrue(grid.isSolved)
        assertEquals(0, grid.litCount)
    }

    @Test
    fun unTableroConAlgunaLuzEncendidaNoEstaResuelto() {
        val grid = LightGrid.solved(4).toggled(GridPosition(2, 3))
        assertFalse(grid.isSolved)
    }

    @Test
    fun cellAtFueraDelTableroDevuelveFalseSinLanzar() {
        val grid = LightGrid.solved(3)
        assertFalse(grid.cellAt(GridPosition(-1, 0)))
        assertFalse(grid.cellAt(GridPosition(0, 3)))
    }
}
