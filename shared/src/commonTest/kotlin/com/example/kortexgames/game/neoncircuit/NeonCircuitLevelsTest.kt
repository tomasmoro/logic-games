package com.example.kortexgames.game.neoncircuit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del generador procedural de niveles: curva de dificultad (tamaño de
 * tablero y nº de canales por nivel) y que cada nivel generado sea siempre
 * válido según las invariantes de [CircuitLevel] (que ya se comprueban en su
 * `init`, así que basta con no lanzar).
 */
class NeonCircuitLevelsTest {

    @Test
    fun elTableroCreceCada2NivelesHastaElMaximo() {
        val expected = mapOf(
            1 to 5, 2 to 5,
            3 to 6, 4 to 6,
            5 to 7, 6 to 7,
            7 to 8, 8 to 8,
            9 to 9, 10 to 9, 20 to 9,
        )
        expected.forEach { (number, gridSize) ->
            assertEquals(gridSize, NeonCircuitLevels.forNumber(number).gridSize, "nivel $number")
        }
    }

    @Test
    fun elNumeroDeCanalesSubeEn7x7YEnElMaximo() {
        val expected = mapOf(
            1 to 3, 4 to 3,
            5 to 4, 8 to 4,
            9 to 5, 20 to 5,
        )
        expected.forEach { (number, pairCount) ->
            assertEquals(pairCount, NeonCircuitLevels.forNumber(number).pairCount, "nivel $number")
        }
    }

    @Test
    fun todoNivelGeneradoEsValidoParaVariosTamanosYNumeros() {
        (1..24).forEach { number ->
            val level = NeonCircuitLevels.forNumber(number)
            assertEquals(number, level.number)
            assertTrue(level.nodes.all { it.position.isInside(level.gridSize) })
        }
    }
}
