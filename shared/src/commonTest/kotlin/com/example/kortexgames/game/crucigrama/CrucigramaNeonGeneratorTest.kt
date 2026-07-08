package com.example.kortexgames.game.crucigrama

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del generador de crucigramas entrelazados.
 *
 * Protegen las invariantes de rejilla para evitar crashes en inicialización de
 * niveles cuando hay cruces incompatibles entre palabras.
 */
class CrucigramaNeonGeneratorTest {

    @Test
    fun generarNivelesBaseNoLanzaExcepciones() {
        // Si un nivel tuviera un cruce inválido, esta llamada lanzaría IllegalArgumentException.
        (1..6).forEach { level ->
            CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
        }
    }

    @Test
    fun todasLasCeldasRespetanLaSolucionDelSlot() {
        (1..6).forEach { level ->
            val puzzle = CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
            puzzle.slots.forEach { slot ->
                slot.cellIndices.forEachIndexed { idx, cellIndex ->
                    val cell = puzzle.cells[cellIndex]
                    assertEquals(
                        expected = slot.answer[idx],
                        actual = cell.solution,
                        message = "Cruce inconsistente en nivel $level, pista ${slot.number}, celda $cellIndex",
                    )
                }
            }
        }
    }

    @Test
    fun todasLasLetrasDelPuzzleEstanEnElBancoInferior() {
        (1..6).forEach { level ->
            val puzzle = CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
            val bank = puzzle.letters.toSet()
            assertTrue(
                puzzle.cells.all { it.solution in bank },
                "Hay letras fuera del banco en el nivel $level",
            )
        }
    }
}

