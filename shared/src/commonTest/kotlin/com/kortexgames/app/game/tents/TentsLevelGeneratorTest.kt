package com.kortexgames.app.game.tents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Invariantes del generador. No comprueban tableros concretos (cambiarían al retocar la curva de
 * dificultad) sino las propiedades que hacen que CUALQUIER nivel generado sea jugable.
 */
class TentsLevelGeneratorTest {

    private val levels = 1..40

    @Test
    fun lasTiendasDeLaSolucionNoSeTocan() {
        for (n in levels) {
            val tents = TentsLevelGenerator.generate(n).solution
            for (a in tents) for (b in tents) {
                if (a == b) continue
                assertFalse(abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1, "Nivel $n: $a toca a $b")
            }
        }
    }

    @Test
    fun losContadoresSalenDeLaSolucion() {
        for (n in levels) {
            val level = TentsLevelGenerator.generate(n)
            for (i in 0 until level.size) {
                assertEquals(level.solution.count { it.y == i }, level.rowTargets[i], "Nivel $n: fila $i")
                assertEquals(level.solution.count { it.x == i }, level.columnTargets[i], "Nivel $n: columna $i")
            }
        }
    }

    @Test
    fun laSolucionEmparejaCadaArbolConUnaTienda() {
        for (n in levels) {
            val level = TentsLevelGenerator.generate(n)
            assertEquals(level.trees.size, level.solution.size, "Nivel $n: tantas tiendas como árboles")
            assertTrue(level.trees.none { it in level.solution }, "Nivel $n: tienda sobre un árbol")
            var board = TentsBoard.from(level)
            for (tent in level.solution) board = board.with(TentsCell(tent.x, tent.y, TentsCellType.TENT))
            assertTrue(TentsMatching.isPerfect(board), "Nivel $n: emparejamiento árbol↔tienda")
        }
    }

    @Test
    fun todosLosNivelesTienenSolucionUnica() {
        for (n in levels) {
            assertTrue(TentsLevelGenerator.generate(n).hasUniqueSolution, "Nivel $n: solución ambigua")
        }
    }

    @Test
    fun elMismoNivelGeneraSiempreElMismoTablero() {
        for (n in listOf(1, 2, 3, 7, 20)) {
            assertEquals(TentsLevelGenerator.generate(n), TentsLevelGenerator.generate(n), "Nivel $n")
        }
    }

    @Test
    fun laDificultadSubeDeFormaGradual() {
        assertEquals(5, TentsLevelGenerator.configFor(1).size)
        assertEquals(8, TentsLevelGenerator.configFor(60).size, "El tablero se topa en 8×8")
        // Sin saltos: de un nivel al siguiente el lado y las tiendas crecen como mucho en uno,
        // y ninguno de los dos retrocede.
        for (n in 1 until 40) {
            val a = TentsLevelGenerator.configFor(n)
            val b = TentsLevelGenerator.configFor(n + 1)
            assertTrue(b.size - a.size in 0..1, "Lado $n→${n + 1}: ${a.size}→${b.size}")
            assertTrue(b.tents - a.tents in 0..1, "Tiendas $n→${n + 1}: ${a.tents}→${b.tents}")
        }
        // El generador entrega la densidad pedida, no un tablero más vacío de lo previsto.
        for (n in levels) {
            assertEquals(TentsLevelGenerator.configFor(n).tents, TentsLevelGenerator.generate(n).solution.size, "Nivel $n")
        }
    }

    @Test
    fun elEmparejamientoRechazaDosArbolesQueCompartenTienda() {
        // Fila "árbol · tienda · árbol": los dos árboles tienen una tienda al lado, pero es la misma.
        val shared = TentsBoard(
            size = 3,
            cells = List(9) { i ->
                val type = when (i) {
                    3, 5 -> TentsCellType.TREE
                    4 -> TentsCellType.TENT
                    else -> TentsCellType.EMPTY
                }
                TentsCell(i % 3, i / 3, type)
            },
        )
        assertFalse(TentsMatching.isPerfect(shared))
    }
}
