package com.kortexgames.app.game.shikaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariantes del generador. No comprueban tableros concretos (cambiarían al retocar la curva de
 * dificultad) sino las propiedades que hacen que CUALQUIER nivel generado sea jugable.
 */
class ShikakuLevelGeneratorTest {

    private val levels = 1..40

    @Test
    fun laSolucionParticionaExactamenteLaMascara() {
        for (n in levels) {
            val level = ShikakuLevelGenerator.generate(n)
            val mask = level.mask
            val coverage = IntArray(mask.columns * mask.rows)
            for (rect in level.solution) {
                assertTrue(mask.covers(rect), "Nivel $n: $rect pisa celdas inhabilitadas")
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) coverage[y * mask.columns + x]++
            }
            for (y in 0 until mask.rows) for (x in 0 until mask.columns) {
                val expected = if (mask.isEnabled(x, y)) 1 else 0
                assertEquals(expected, coverage[y * mask.columns + x], "Nivel $n: cobertura de ($x,$y)")
            }
        }
    }

    @Test
    fun cadaRectanguloTieneUnaUnicaPistaConSuArea() {
        for (n in levels) {
            val level = ShikakuLevelGenerator.generate(n)
            assertEquals(level.solution.size, level.clues.size, "Nivel $n: una pista por rectángulo")
            for (rect in level.solution) {
                val inside = level.clues.filter { it in rect }
                assertEquals(1, inside.size, "Nivel $n: pistas dentro de $rect")
                assertEquals(rect.area, inside.single().number, "Nivel $n: valor de la pista de $rect")
            }
        }
    }

    @Test
    fun elMismoNivelGeneraSiempreElMismoTablero() {
        for (n in listOf(1, 2, 3, 7, 20)) {
            assertEquals(ShikakuLevelGenerator.generate(n), ShikakuLevelGenerator.generate(n), "Nivel $n")
        }
    }

    @Test
    fun laDificultadSubeDeFormaGradual() {
        val easy = ShikakuLevelGenerator.generate(1)
        assertEquals(5 to 5, easy.mask.columns to easy.mask.rows)
        assertEquals(25, easy.mask.enabledCount, "El nivel 1 no lleva máscara")
        assertTrue(easy.clues.all { it.number in 2..5 }, "Nivel 1: pistas ${easy.clues.map { it.number }}")

        // Sin saltos: de un nivel al siguiente el lado crece como mucho una celda y la pista
        // máxima como mucho dos, y ninguno de los dos retrocede.
        for (n in 1 until 40) {
            val a = ShikakuLevelGenerator.configFor(n)
            val b = ShikakuLevelGenerator.configFor(n + 1)
            assertTrue(b.columns - a.columns in 0..1, "Lado $n→${n + 1}: ${a.columns}→${b.columns}")
            assertTrue(b.maxArea - a.maxArea in 0..2, "Pista máx $n→${n + 1}: ${a.maxArea}→${b.maxArea}")
        }

        // Los cuatro primeros niveles son rejilla completa: se aprende la regla antes que la figura.
        for (n in 1..4) {
            val level = ShikakuLevelGenerator.generate(n)
            assertEquals(level.mask.columns * level.mask.rows, level.mask.enabledCount, "Nivel $n")
        }
        // El 10×10 con figura rota no llega antes del nivel 10.
        assertTrue(ShikakuLevelGenerator.configFor(9).columns < 10)
        val ten = ShikakuLevelGenerator.generate(10)
        assertEquals(10 to 10, ten.mask.columns to ten.mask.rows)
        assertTrue(ten.mask.enabledCount < 100, "El nivel 10 lleva máscara")
        assertEquals(12, ShikakuLevelGenerator.configFor(60).columns, "El tablero se topa en 12×12")
    }

    @Test
    fun losNivelesTienenSolucionUnica() {
        val ambiguous = levels.filterNot { ShikakuLevelGenerator.generate(it).hasUniqueSolution }
        assertTrue(ambiguous.isEmpty(), "Niveles sin unicidad demostrada: $ambiguous")
    }
}
