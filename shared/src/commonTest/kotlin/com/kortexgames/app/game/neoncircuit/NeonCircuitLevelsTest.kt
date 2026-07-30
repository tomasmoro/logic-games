package com.kortexgames.app.game.neoncircuit

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
    fun elNumeroDeCanalesSubePorEscalonHastaElMaximo() {
        val expected = mapOf(
            1 to 4, 2 to 4,
            3 to 4, 4 to 4,
            5 to 5, 6 to 5,
            7 to 6, 8 to 6,
            9 to 7, 10 to 7,
            11 to 8, 12 to 8, 20 to 8,
        )
        expected.forEach { (number, pairCount) ->
            assertEquals(pairCount, NeonCircuitLevels.forNumber(number).pairCount, "nivel $number")
        }
    }

    /**
     * `forNumber` asigna un color distinto por canal tomando los `pairCount`
     * primeros [WireColor]. Si un escalón futuro pidiera más canales que colores
     * existen, `take` truncaría en silencio y la asignación reventaría con
     * `IndexOutOfBoundsException` en tiempo de juego: este test fija ese techo.
     */
    @Test
    fun ningunNivelPideMasCanalesQueColoresHay() {
        (1..24).forEach { number ->
            val pairCount = NeonCircuitLevels.forNumber(number).pairCount
            assertTrue(
                pairCount <= WireColor.entries.size,
                "nivel $number pide $pairCount canales y solo hay ${WireColor.entries.size} colores",
            )
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
