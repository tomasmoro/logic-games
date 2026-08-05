package com.kortexgames.app.game.neoncircuit

import kotlin.test.Test
import com.kortexgames.app.game.grid.GridPosition
import kotlin.math.abs
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
    fun laDificultadDelRepartoNoDecreceDentroDelMismoEscalon() {
        // Niveles del mismo escalón comparten tablero (gridSize/pairCount); lo que
        // debe subir es la dificultad del reparto elegido (hardnessScore), no el
        // tamaño. Se comprueba en varios escalones, incluido uno más allá del techo
        // de tamaño (nivel 13+), donde la única palanca que queda es esta.
        val firstLevelOfEachTier = listOf(1, 3, 5, 7, 9, 11, 13, 15)
        firstLevelOfEachTier.forEach { first ->
            val gridSize = NeonCircuitLevels.forNumber(first).gridSize
            val pairCount = NeonCircuitLevels.forNumber(first).pairCount

            val scores = (0 until 2).map { offset ->
                NeonCircuitLevels.chosenCandidate(first + offset).hardnessScore
            }
            assertEquals(gridSize, NeonCircuitLevels.forNumber(first + 1).gridSize, "escalón de $first")
            assertEquals(pairCount, NeonCircuitLevels.forNumber(first + 1).pairCount, "escalón de $first")
            assertTrue(
                scores[0] <= scores[1],
                "nivel $first (${scores[0]}) debería ser igual o más fácil que nivel ${first + 1} (${scores[1]})",
            )
        }
    }

    @Test
    fun ningunParDeNodosNaceCercaDelOtro() {
        // El fallo que hacía triviales los niveles: un canal con sus dos nodos pegados
        // se resuelve en una esquina sin estorbar a nadie, así que ese color deja de
        // participar en el puzzle. La cota crece con el tablero porque "cerca" es
        // relativo: 3 celdas en un 5×5 separan mucho más que en un 9×9.
        (1..24).forEach { number ->
            val level = NeonCircuitLevels.forNumber(number)
            val floor = level.gridSize / 2
            level.nodes.groupBy { it.color }.forEach { (color, pair) ->
                val distance = pair[0].position.manhattanTo(pair[1].position)
                assertTrue(
                    distance >= floor,
                    "nivel $number, canal $color: nodos a $distance celdas (mínimo $floor)",
                )
            }
        }
    }

    @Test
    fun losNivelesDeUnEscalonNoRepitenReparto() {
        // Dos niveles seguidos comparten tablero y nº de canales: si además saliera el
        // mismo reparto de nodos, el jugador resolvería dos veces el mismo puzzle.
        (1..24).forEach { number ->
            val current = NeonCircuitLevels.forNumber(number).nodes.toSet()
            val next = NeonCircuitLevels.forNumber(number + 1).nodes.toSet()
            assertTrue(current != next, "los niveles $number y ${number + 1} son el mismo reparto")
        }
    }

    /** Distancia de Manhattan, la métrica de separación que usa el generador. */
    private fun GridPosition.manhattanTo(other: GridPosition): Int =
        abs(row - other.row) + abs(col - other.col)

    @Test
    fun todoNivelGeneradoEsValidoParaVariosTamanosYNumeros() {
        (1..24).forEach { number ->
            val level = NeonCircuitLevels.forNumber(number)
            assertEquals(number, level.number)
            assertTrue(level.nodes.all { it.position.isInside(level.gridSize) })
        }
    }
}
