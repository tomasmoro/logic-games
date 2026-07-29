package com.kortexgames.app.game.watersort

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del núcleo puro de "Ordena las Pociones": reglas de vertido y, sobre
 * todo, la **garantía de que todo nivel generado es resoluble** (invariante
 * crítico del juego). Sin dependencias de framework ni corrutinas.
 */
class WaterSortModelTest {

    private val cap = 4

    // --- Reglas de vertido ---------------------------------------------------

    @Test
    fun noSePuedeVerterDeUnTuboVacio() {
        val tubes = listOf(Tube(), Tube(listOf(0)))
        assertFalse(WaterSortRules.canPour(tubes, from = 0, to = 1, cap))
    }

    @Test
    fun sePuedeVerterSobreElMismoColor() {
        val tubes = listOf(Tube(listOf(1, 1)), Tube(listOf(1)))
        assertTrue(WaterSortRules.canPour(tubes, from = 0, to = 1, cap))
    }

    @Test
    fun noSePuedeVerterSobreColorDistinto() {
        val tubes = listOf(Tube(listOf(1)), Tube(listOf(2)))
        assertFalse(WaterSortRules.canPour(tubes, from = 0, to = 1, cap))
    }

    @Test
    fun noSePuedeVerterEnUnTuboLleno() {
        val tubes = listOf(Tube(listOf(1)), Tube(listOf(1, 1, 1, 1)))
        assertFalse(WaterSortRules.canPour(tubes, from = 0, to = 1, cap))
    }

    @Test
    fun verterTuboUniformeAVacioEsInutilYSeProhibe() {
        // Un tubo uniforme (aunque no lleno) a un vacío no acerca a la solución.
        val tubes = listOf(Tube(listOf(3, 3)), Tube())
        assertFalse(WaterSortRules.canPour(tubes, from = 0, to = 1, cap))
    }

    @Test
    fun verterMueveTodoElBloqueSuperiorDelMismoColorQueQuepa() {
        // Origen: [0,1,1,1] (tres 1 arriba). Destino: [1] → caben 3.
        val tubes = listOf(Tube(listOf(0, 1, 1, 1)), Tube(listOf(1)))
        val result = WaterSortRules.pour(tubes, from = 0, to = 1, cap)

        assertEquals(3, result.count)
        assertEquals(1, result.color)
        assertEquals(listOf(0), result.tubes[0].segments)
        assertEquals(listOf(1, 1, 1, 1), result.tubes[1].segments)
        assertTrue(result.dstNowComplete)
    }

    @Test
    fun verterRespetaLaCapacidadRestanteDelDestino() {
        // Origen: [2,2,2] ; Destino: [2,2] (solo cabe 2). Se mueven 2, queda 1.
        val tubes = listOf(Tube(listOf(2, 2, 2)), Tube(listOf(2, 2)))
        val result = WaterSortRules.pour(tubes, from = 0, to = 1, cap)

        assertEquals(2, result.count)
        assertEquals(listOf(2), result.tubes[0].segments)
        assertEquals(listOf(2, 2, 2, 2), result.tubes[1].segments)
    }

    @Test
    fun detectaVictoriaCuandoTodosLosTubosEstanResueltos() {
        val solved = listOf(Tube(listOf(0, 0, 0, 0)), Tube(listOf(1, 1, 1, 1)), Tube())
        assertTrue(WaterSortRules.isSolved(solved, cap))

        val notSolved = listOf(Tube(listOf(0, 1, 0, 0)), Tube(listOf(1)))
        assertFalse(WaterSortRules.isSolved(notSolved, cap))
    }

    @Test
    fun topRunLengthCuentaSoloElBloqueSuperior() {
        assertEquals(2, Tube(listOf(1, 2, 2)).topRunLength)
        assertEquals(3, Tube(listOf(5, 5, 5)).topRunLength)
        assertEquals(0, Tube().topRunLength)
    }

    // --- Generador y solver --------------------------------------------------

    @Test
    fun elSolverEncuentraSolucionEnUnCasoCasiResuelto() {
        // Un solo vertido resuelve: [0,0,0] + [0] → [0,0,0,0].
        val tubes = listOf(Tube(listOf(0, 0, 0)), Tube(listOf(0)), Tube())
        val moves = WaterSortGenerator.solve(tubes, cap)
        assertNotNull(moves)
        assertTrue(moves >= 1)
    }

    @Test
    fun elSolverDevuelveNullSiEsIrresoluble() {
        // Dos tubos llenos de colores distintos y sin espacio: bloqueado.
        val tubes = listOf(Tube(listOf(0, 1, 0, 1)), Tube(listOf(1, 0, 1, 0)))
        assertNull(WaterSortGenerator.solve(tubes, cap))
    }

    @Test
    fun todoNivelGeneradoEsResolubleParaCadaDificultad() {
        // Invariante clave: con semilla fija, cada dificultad produce un nivel
        // que el solver puede resolver, con el nº de tubos esperado y sin estar
        // ya resuelto de salida.
        for (difficulty in 1..5) {
            val config = LevelConfig.forDifficulty(difficulty)
            val level = WaterSortGenerator.generate(config, Random(seed = 42L + difficulty))

            assertEquals(config.colorCount + config.emptyTubes, level.tubes.size)
            assertFalse(
                WaterSortRules.isSolved(level.tubes, level.capacity),
                "El nivel de dificultad $difficulty no debería nacer ya resuelto",
            )
            assertNotNull(
                WaterSortGenerator.solve(level.tubes, level.capacity),
                "El nivel de dificultad $difficulty debe ser resoluble",
            )
            assertTrue(level.minMoves >= 1)
        }
    }

    @Test
    fun laGeneracionEsDeterministaConLaMismaSemilla() {
        val config = LevelConfig.forDifficulty(3)
        val a = WaterSortGenerator.generate(config, Random(seed = 7L))
        val b = WaterSortGenerator.generate(config, Random(seed = 7L))
        assertEquals(a.tubes, b.tubes)
    }
}
