package com.kortexgames.app.game.crucigrama

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Valida el catálogo completo de niveles del Crucigrama Neón.
 *
 * Las reglas viven en el `init` de [CrucigramaNeonLevelSpec] (extras en mayúsculas,
 * sin repetir palabras de la rejilla, sin ser prefijo de ellas, solo con letras del
 * teclado…) y en el entrelazador. Si un nivel las incumple, la app lanza una
 * excepción al abrir el juego; este test lo detecta en CI y lista TODOS los niveles
 * rotos de una vez en lugar de fallar en el primero.
 */
class CrucigramaNeonLevelsTest {

    @Test
    fun todosLosNivelesCumplenLasReglas() {
        val errores = CrucigramaNeonGenerator.levelErrors()
        assertTrue(
            errores.isEmpty(),
            "Niveles inválidos (${errores.size}):\n" +
                errores.entries.joinToString("\n") { (nivel, msg) -> " - Nivel $nivel: $msg" },
        )
    }

    @Test
    fun todosLosNivelesSeGeneranYSonResolubles() {
        for (nivel in 1..CrucigramaNeonGenerator.levelCount) {
            val puzzle = CrucigramaNeonGenerator.generate(nivel)
            val teclado = puzzle.letters.toSet()
            assertTrue(
                puzzle.slots.all { s -> s.answer.all { it in teclado } },
                "Nivel $nivel: hay respuestas con letras fuera del teclado.",
            )
            assertTrue(
                puzzle.cells.all { it.solution in teclado },
                "Nivel $nivel: hay celdas con letras fuera del teclado.",
            )
        }
    }
}
