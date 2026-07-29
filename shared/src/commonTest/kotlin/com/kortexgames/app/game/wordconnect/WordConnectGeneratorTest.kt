package com.kortexgames.app.game.wordconnect

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del generador de Palabras Conectadas.
 *
 * Protegen la invariante clave del contenido: **cada palabra objetivo debe ser
 * formable con las letras del anillo** (respetando repeticiones). Un typo en una
 * palabra la haría imposible de encontrar en juego; aquí se detecta antes.
 */
class WordConnectGeneratorTest {

    @Test
    fun generarNivelesBaseNoLanzaExcepciones() {
        // Si un nivel tuviera una palabra no formable, la validación de la spec lanzaría.
        (1..6).forEach { level ->
            WordConnectGenerator.generate(level, Random(level.toLong()))
        }
    }

    @Test
    fun todasLasPalabrasSeFormanConLasLetrasDelAnillo() {
        (1..6).forEach { level ->
            val puzzle = WordConnectGenerator.generate(level, Random(level.toLong()))
            val pool = puzzle.letters.groupingBy { it.char }.eachCount()
            puzzle.slots.forEach { slot ->
                val need = slot.answer.groupingBy { it }.eachCount()
                need.forEach { (letter, count) ->
                    assertTrue(
                        count <= (pool[letter] ?: 0),
                        "La palabra '${slot.answer}' no es formable en el nivel $level",
                    )
                }
            }
        }
    }

    @Test
    fun lasRanurasSeOrdenanDeCortaALarga() {
        (1..6).forEach { level ->
            val puzzle = WordConnectGenerator.generate(level, Random(level.toLong()))
            val lengths = puzzle.slots.map { it.answer.length }
            assertEquals(
                expected = lengths.sorted(),
                actual = lengths,
                message = "Las palabras no están ordenadas por longitud en el nivel $level",
            )
        }
    }

    @Test
    fun losIndicesDeLetraSonUnicosYConsecutivos() {
        // El trazo identifica nodos por índice; deben ser 0..n-1 sin huecos.
        val puzzle = WordConnectGenerator.generate(1, Random(0))
        assertEquals(puzzle.letters.indices.toList(), puzzle.letters.map { it.index })
    }
}
