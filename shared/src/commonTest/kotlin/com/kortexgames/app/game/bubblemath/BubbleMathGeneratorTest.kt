package com.kortexgames.app.game.bubblemath

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del generador puro de "Burbujas de Cálculo". Verifican los **invariantes
 * críticos** de cada ronda —de los que depende que el juego sea jugable y justo—
 * de forma determinista, recorriendo muchas rondas y semillas. Sin corrutinas ni
 * Compose (misma filosofía que [com.kortexgames.app.game.watersort.WaterSortModelTest]).
 */
class BubbleMathGeneratorTest {

    /** Recorre un rango amplio de rondas con varias semillas y valida cada una. */
    private inline fun forEachRound(action: (RoundSpec, round: Int) -> Unit) {
        for (seed in 0 until 25) {
            val random = Random(seed.toLong())
            for (round in 1..30) {
                action(BubbleMathGenerator.generateRound(round, random), round)
            }
        }
    }

    @Test
    fun cadaOperacionResuelveASuValor() {
        forEachRound { spec, _ ->
            spec.options.forEach { e ->
                val expected = when (e.op) {
                    MathOp.ADD -> e.a + e.b
                    MathOp.SUB -> e.a - e.b
                    MathOp.MUL -> e.a * e.b
                    MathOp.DIV -> e.a / e.b
                }
                assertEquals(expected, e.value, "La operación ${e.text} no da ${e.value}")
                // Las divisiones deben ser exactas (no truncadas).
                if (e.op == MathOp.DIV) assertEquals(0, e.a % e.b, "División inexacta: ${e.text}")
            }
        }
    }

    @Test
    fun exactamenteUnaOpcionCoincideConElObjetivo() {
        forEachRound { spec, _ ->
            val matches = spec.options.count { it.value == spec.target }
            assertEquals(1, matches, "Debe haber exactamente una burbuja correcta (había $matches)")
        }
    }

    @Test
    fun losValoresDeLasOpcionesSonUnicos() {
        forEachRound { spec, _ ->
            val values = spec.options.map { it.value }
            assertEquals(values.size, values.toSet().size, "Hay valores de burbuja repetidos: $values")
        }
    }

    @Test
    fun nuncaHayResultadosNegativos() {
        forEachRound { spec, _ ->
            spec.options.forEach { assertTrue(it.value >= 0, "Resultado negativo: ${it.text}") }
        }
    }

    @Test
    fun elNumeroDeBurbujasCoincideConLaDificultad() {
        forEachRound { spec, round ->
            assertEquals(BubbleMathGenerator.optionCount(round), spec.options.size)
        }
    }
}
