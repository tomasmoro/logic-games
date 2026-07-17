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
        (1..20).forEach { level ->
            CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
        }
    }

    @Test
    fun todasLasCeldasRespetanLaSolucionDelSlot() {
        (1..20).forEach { level ->
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
        (1..20).forEach { level ->
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
        (1..20).forEach { level ->
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

    /**
     * La dificultad sube con el catálogo A GRANDES RASGOS: el nº de palabras se
     * mantiene en 4..7 y el tramo final es más denso que el inicial. No se fija
     * la escalera exacta ni monotonicidad estricta — el catálogo se retoca a
     * mano y admite pequeños vaivenes de variedad entre niveles contiguos.
     */
    @Test
    fun dificultadCreceCadaCuatroNiveles() {
        val palabras = (1..20).map { level ->
            CrucigramaNeonGenerator.generate(level, Random(level.toLong())).slots.size
        }
        palabras.forEachIndexed { i, count ->
            assertTrue(count in 4..7, "Nivel ${i + 1}: nº de palabras ($count) fuera de 4..7")
        }
        val arranque = palabras.take(5).average()
        val cierre = palabras.takeLast(5).average()
        assertTrue(cierre > arranque, "el tramo final ($cierre) no es más denso que el inicial ($arranque)")
    }

    /**
     * Las palabras extra (bonus) deben ser formables con el teclado, no coincidir con
     * una palabra de la rejilla y no ser prefijo de ninguna (si no, se consumirían antes
     * de poder completar esa palabra en la rejilla).
     */
    @Test
    fun palabrasExtraSonValidasYNoColisionan() {
        (1..20).forEach { level ->
            val puzzle = CrucigramaNeonGenerator.generate(level, Random(level.toLong()))
            val bank = puzzle.letters.toSet()
            val gridWords = puzzle.slots.map { it.answer }.toSet()
            puzzle.extraWords.forEach { extra ->
                assertTrue(extra.all { it in bank }, "Nivel $level: extra '$extra' con letras fuera del teclado.")
                assertTrue(extra !in gridWords, "Nivel $level: extra '$extra' coincide con palabra de rejilla.")
                assertTrue(
                    gridWords.none { it != extra && it.startsWith(extra) },
                    "Nivel $level: extra '$extra' es prefijo de una palabra de rejilla.",
                )
            }
        }
    }
}

