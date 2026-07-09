package com.example.kortexgames.game.crucigrama

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * Invariante de entrelazado: al leer cualquier fila o columna, todo tramo máximo
     * de celdas rellenas de longitud ≥ 2 debe ser exactamente una palabra declarada.
     * Es la salvaguarda contra el bug "AMORA" (dos palabras que se tocan de lado y se
     * leen como una tercera, sin sentido).
     */
    @Test
    fun ningunTramoContiguoFormaPalabraFalsa() {
        (1..6).forEach { level ->
            val puzzle = CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
            val filled = puzzle.cells.associate { (it.row to it.col) to it.solution }
            val palabras = puzzle.slots.map { it.answer }.toSet()

            // horizontal=true recorre filas; false recorre columnas.
            fun revisarTramos(horizontal: Boolean) {
                val ejes = if (horizontal) puzzle.rows else puzzle.cols
                val largo = if (horizontal) puzzle.cols else puzzle.rows
                for (eje in 0 until ejes) {
                    var i = 0
                    while (i < largo) {
                        val cell: (Int) -> Char? = { j ->
                            val r = if (horizontal) eje else j
                            val c = if (horizontal) j else eje
                            filled[r to c]
                        }
                        if (cell(i) == null) { i++; continue }
                        val sb = StringBuilder()
                        var j = i
                        while (j < largo && cell(j) != null) {
                            sb.append(cell(j))
                            j++
                        }
                        val tramo = sb.toString()
                        // Tramo de 1 letra = celda de cruce; válido. Tramo ≥2 debe ser palabra.
                        if (tramo.length >= 2 && tramo !in palabras) {
                            fail("Nivel $level: el tramo '$tramo' (${if (horizontal) "fila" else "col"} $eje) no es palabra declarada -> palabra falsa.")
                        }
                        i = j
                    }
                }
            }
            revisarTramos(horizontal = true)
            revisarTramos(horizontal = false)
        }
    }
}

