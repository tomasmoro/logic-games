package com.kortexgames.app.game.bubblemath

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del generador de la **nube de ecuación** (el reto relámpago que devuelve una
 * vida). Verifican los invariantes de los que depende que el reto sea justo, sobre
 * muchas rondas y semillas y sin corrutinas ni Compose.
 *
 * El invariante crítico es la **unicidad de la solución**: el motor da por buena la
 * respuesta comparando etiquetas contra `solution`, así que si las fichas admitieran
 * otra combinación matemáticamente correcta el juego rechazaría un acierto legítimo.
 */
class EquationCloudGeneratorTest {

    /** Recorre un rango amplio de rondas con varias semillas y valida cada nube. */
    private inline fun forEachPuzzle(action: (EquationPuzzle) -> Unit) {
        for (seed in 0 until 25) {
            val random = Random(seed.toLong())
            for (round in 1..40) {
                action(EquationCloudGenerator.generate(round, random))
            }
        }
    }

    /** Todas las fichas de la solución están realmente en la bandeja. */
    @Test
    fun laSolucionEstaEntreLasFichasOfrecidas() {
        forEachPuzzle { puzzle ->
            val tray = puzzle.options.map { it.label }.toMutableList()
            puzzle.solution.forEach { label ->
                assertTrue(tray.remove(label), "Falta la ficha '$label' en la bandeja de ${puzzle.slots}")
            }
        }
    }

    /** Hay tantos huecos en la ecuación como etiquetas en la solución, y sin repetir índice. */
    @Test
    fun losHuecosCuadranConLaSolucion() {
        forEachPuzzle { puzzle ->
            val blanks = puzzle.slots.filterIsInstance<EquationSlot.Blank>().map { it.index }
            assertEquals(puzzle.solution.size, blanks.size, "Nº de huecos distinto del de la solución")
            assertEquals(blanks.sorted(), blanks.indices.toList(), "Índices de hueco no consecutivos: $blanks")
        }
    }

    /**
     * A una ecuación le faltan **o solo símbolos o solo números**, nunca ambos: es la
     * regla de diseño del reto (7 segundos no dan para un rompecabezas mixto).
     */
    @Test
    fun losHuecosSonTodosDelMismoTipo() {
        forEachPuzzle { puzzle ->
            val symbols = MathOp.entries.map { it.symbol.toString() }
            val allSymbols = puzzle.solution.all { it in symbols }
            val allNumbers = puzzle.solution.all { it.toIntOrNull() != null }
            when (puzzle.kind) {
                EquationBlankKind.SYMBOL -> assertTrue(allSymbols, "Se esperaban solo símbolos: ${puzzle.solution}")
                EquationBlankKind.NUMBER -> assertTrue(allNumbers, "Se esperaban solo números: ${puzzle.solution}")
            }
            // Y las fichas de la bandeja son del mismo tipo que los huecos: ofrecer un
            // número donde falta un símbolo delataría la respuesta.
            puzzle.options.forEach { token ->
                val isSymbol = token.label in symbols
                assertEquals(puzzle.kind == EquationBlankKind.SYMBOL, isSymbol, "Ficha de otro tipo: ${token.label}")
            }
        }
    }

    /**
     * Invariante crítico: con las fichas ofrecidas **solo hay una** combinación que
     * satisface la ecuación. Se comprueba por fuerza bruta reconstruyendo la ecuación
     * desde los `slots` (es decir, desde lo que el jugador ve, no desde el candidato
     * interno del generador).
     */
    @Test
    fun laSolucionEsUnica() {
        forEachPuzzle { puzzle ->
            val valid = mutableSetOf<List<String>>()
            val labels = puzzle.options.map { it.label }
            picks(labels.size, puzzle.blankCount).forEach { pick ->
                val attempt = pick.map { labels[it] }
                if (satisfies(puzzle, attempt)) valid += attempt
            }
            assertEquals(
                setOf(puzzle.solution),
                valid,
                "La ecuación ${describe(puzzle)} no tiene solución única",
            )
        }
    }

    /** El resultado de la ecuación resuelta nunca es negativo (dominio del juego). */
    @Test
    fun elResultadoEsNoNegativo() {
        forEachPuzzle { puzzle ->
            val (numbers, ops) = fill(puzzle, puzzle.solution)
            val value = evalChain(numbers, ops)
            assertTrue(value != null && value >= 0, "Ecuación inválida: ${describe(puzzle)}")
        }
    }

    // --- Utilidades -----------------------------------------------------------

    /** Todas las asignaciones ordenadas de [k] fichas distintas de entre [size]. */
    private fun picks(size: Int, k: Int): List<List<Int>> {
        if (k == 0) return listOf(emptyList())
        return picks(size, k - 1).flatMap { prefix ->
            (0 until size).filter { it !in prefix }.map { prefix + it }
        }
    }

    /**
     * Reconstruye `numbers` y `ops` a partir de los [EquationPuzzle.slots] rellenando
     * los huecos con [attempt]. Los paréntesis y el `=` se ignoran: la evaluación es de
     * izquierda a derecha, que es justo lo que esos paréntesis representan.
     */
    private fun fill(puzzle: EquationPuzzle, attempt: List<String>): Pair<List<Int>, List<MathOp>> {
        val numbers = mutableListOf<Int>()
        val ops = mutableListOf<MathOp>()
        // El último Fixed numérico es el resultado; se excluye al partir por el "=".
        val body = puzzle.slots.takeWhile { !(it is EquationSlot.Fixed && it.text == "=") }
        body.forEach { slot ->
            val text = when (slot) {
                is EquationSlot.Fixed -> slot.text
                is EquationSlot.Blank -> attempt[slot.index]
            }
            when {
                text == "(" || text == ")" -> Unit
                text.toIntOrNull() != null -> numbers += text.toInt()
                else -> MathOp.entries.firstOrNull { it.symbol.toString() == text }?.let { ops += it }
            }
        }
        return numbers to ops
    }

    /** ¿La ecuación cuadra con [attempt] en los huecos? */
    private fun satisfies(puzzle: EquationPuzzle, attempt: List<String>): Boolean {
        val expected = (puzzle.slots.last() as EquationSlot.Fixed).text.toInt()
        val (numbers, ops) = fill(puzzle, attempt)
        if (numbers.size != ops.size + 1) return false
        return evalChain(numbers, ops) == expected
    }

    /** Representación legible de la ecuación, para los mensajes de error. */
    private fun describe(puzzle: EquationPuzzle): String =
        puzzle.slots.joinToString(" ") { slot ->
            when (slot) {
                is EquationSlot.Fixed -> slot.text
                is EquationSlot.Blank -> "[${puzzle.solution[slot.index]}]"
            }
        } + " · fichas=" + puzzle.options.map { it.label }
}
