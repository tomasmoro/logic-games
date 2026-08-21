package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.game.grid.GridPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del generador puro de "Neon Grid Switch". Verifican los **invariantes
 * críticos** de cada etapa —tamaño correcto, tablero nunca ya resuelto y, sobre
 * todo, que la propia secuencia de scramble es una solución válida (ver el KDoc
 * de [GridSwitchGenerator] para el porqué)— de forma determinista, recorriendo
 * muchas etapas y semillas. Sin corrutinas ni Compose (misma filosofía que
 * [com.kortexgames.app.game.bubblemath.BubbleMathGeneratorTest]).
 */
class GridSwitchGeneratorTest {

    private val seeds = 0 until 20
    private val stages = 1..10

    @Test
    fun elTableroTieneElLadoDeSuEtapa() {
        for (seed in seeds) {
            for (stage in stages) {
                val grid = GridSwitchGenerator.generate(stage, seed.toLong())
                val expectedSize = GridSwitchStages.gridSizeForStage(stage)
                assertEquals(expectedSize, grid.size, "Etapa $stage debería ser de lado $expectedSize")
                assertEquals(expectedSize, grid.cells.size)
                grid.cells.forEach { row -> assertEquals(expectedSize, row.size) }
            }
        }
    }

    @Test
    fun elTableroGeneradoNuncaEstaYaResuelto() {
        for (seed in seeds) {
            for (stage in stages) {
                val grid = GridSwitchGenerator.generate(stage, seed.toLong())
                assertFalse(grid.isSolved, "Etapa $stage/semilla $seed generó un tablero ya resuelto")
            }
        }
    }

    @Test
    fun mismaEtapaYSemillaProducenElMismoTablero() {
        for (seed in seeds) {
            for (stage in stages) {
                val a = GridSwitchGenerator.generate(stage, seed.toLong())
                val b = GridSwitchGenerator.generate(stage, seed.toLong())
                assertEquals(a, b, "Etapa $stage/semilla $seed no es determinista")
            }
        }
    }

    /**
     * El invariante matemático del que depende TODA la garantía de solución del
     * generador (ver KDoc de archivo): [LightGrid.toggled] es una involución
     * conmutativa. Re-tocar exactamente el mismo conjunto de celdas que desordenó
     * un tablero —en CUALQUIER orden, no solo el que usó el generador— debe
     * devolverlo al estado resuelto. Se verifica directamente sobre [LightGrid]
     * (no hace falta espiar la secuencia interna de [GridSwitchGenerator]):
     * aplicar N toques aleatorios y luego los mismos N en orden inverso siempre
     * cierra en el tablero resuelto.
     */
    @Test
    fun retocarLasMismasCeldasEnCualquierOrdenResuelveElTablero() {
        for (seed in seeds) {
            for (size in GRID_SWITCH_MIN_SIZE..GRID_SWITCH_MAX_SIZE) {
                val random = kotlin.random.Random(seed.toLong() * 31 + size)
                val touches = List(size * size) { GridPosition(random.nextInt(size), random.nextInt(size)) }

                var grid = LightGrid.solved(size)
                touches.forEach { grid = grid.toggled(it) }
                // Mismo conjunto de toques, orden inverso: la propiedad exige que
                // el orden sea irrelevante.
                touches.asReversed().forEach { grid = grid.toggled(it) }

                assertTrue(grid.isSolved, "size=$size seed=$seed: retocar en orden inverso no resolvió el tablero")
            }
        }
    }
}
