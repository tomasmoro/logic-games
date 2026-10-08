package com.kortexgames.app.game.hexaflux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariantes del generador de niveles. No comprueban tableros concretos (eso
 * congelaría el equilibrio del juego) sino las garantías de las que depende el motor.
 */
class HexaLevelGeneratorTest {

    private val levels = 1..80

    @Test
    fun elMismoNivelGeneraSiempreElMismoTablero() {
        levels.forEach { assertEquals(HexaLevelGenerator.generate(it), HexaLevelGenerator.generate(it)) }
    }

    @Test
    fun lasMascarasTienenElNumeroDeCeldasEsperado() {
        assertEquals(19, HexaLevelGenerator.cellsFor(BoardMask.CONCENTRIC, enlarged = false).size)
        assertEquals(37, HexaLevelGenerator.cellsFor(BoardMask.CONCENTRIC, enlarged = true).size)
        assertEquals(18, HexaLevelGenerator.cellsFor(BoardMask.DONUT, enlarged = false).size)
        assertEquals(30, HexaLevelGenerator.cellsFor(BoardMask.DONUT, enlarged = true).size)
        assertEquals(39, HexaLevelGenerator.cellsFor(BoardMask.BUTTERFLY, enlarged = false).size)
        assertEquals(21, HexaLevelGenerator.cellsFor(BoardMask.ISLANDS, enlarged = false).size)
        assertEquals(28, HexaLevelGenerator.cellsFor(BoardMask.ISLANDS, enlarged = true).size)
    }

    @Test
    fun laOlaSubeHastaElJefeYSeRelajaEnLaCatarsis() {
        // Se mira la segunda ola (niveles 6..10) para no depender del arranque.
        val d = (6..10).map { HexaLevelGenerator.difficulty(it) }
        assertTrue(d[0] < d[1] && d[1] < d[2] && d[2] < d[3], "N1..N4 debe ser creciente: $d")
        assertTrue(d[4] < d[0], "La catarsis debe quedar por debajo de la introducción: $d")
        assertTrue(HexaLevelGenerator.difficulty(9) > HexaLevelGenerator.difficulty(4), "Cada ola sube un escalón")
    }

    @Test
    fun todoNivelArrancaJugable() {
        levels.forEach { level ->
            val config = HexaLevelGenerator.generate(level)
            val free = config.initialBoard.values.count { it.isFree }
            assertTrue(free * 2 >= config.initialBoard.size, "Nivel $level: solo $free celdas libres")
            assertTrue(config.moveLimit > 0, "Nivel $level sin jugadas")
            assertTrue(config.initialProgress().goal > 0, "Nivel $level nace con el objetivo cumplido")
        }
    }

    @Test
    fun losPortalesVanEnParejasSimetricas() {
        levels.forEach { level ->
            val board = HexaLevelGenerator.generate(level).initialBoard
            board.values.forEach { cell ->
                val portal = cell.gimmick as? Gimmick.Portal ?: return@forEach
                val exit = board[portal.exit]?.gimmick as? Gimmick.Portal
                assertEquals(cell.coord, exit?.exit, "Nivel $level: portal sin pareja en ${cell.coord}")
            }
        }
    }

    @Test
    fun enLasIslasLosPortalesComunicanTodasLasZonas() {
        (31..50).forEach { level ->
            val board = HexaLevelGenerator.generate(level).initialBoard
            // Alcanzable = por adyacencia o cruzando un portal.
            val seen = mutableSetOf(board.keys.first())
            val queue = ArrayDeque(seen)
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                val links = at.neighbors() + listOfNotNull((board[at]?.gimmick as? Gimmick.Portal)?.exit)
                links.filter { it in board && seen.add(it) }.forEach(queue::add)
            }
            assertEquals(board.size, seen.size, "Nivel $level: hay islas incomunicadas")
        }
    }

    @Test
    fun rotarSeisVecesDevuelveLaPiezaOriginal() {
        val piece = HexaLevelGenerator.nextPiece(PieceBag(FluxTier.entries, 3), kotlin.random.Random(7), id = 1)
        var rotated = piece
        repeat(6) { rotated = rotated.rotatedClockwise() }
        assertEquals(piece, rotated)
    }

    @Test
    fun elPasoAPixelesYSuInversaSonCoherentes() {
        HexaLevelGenerator.cellsFor(BoardMask.BUTTERFLY, enlarged = false).forEach { cell ->
            assertEquals(cell, HexCoord.fromUnit(cell.unitX(), cell.unitY()))
            // Un punto cerca del borde de la celda (80 % del apotema) sigue siendo de esa celda.
            HexCoord.DIRECTIONS.forEach { dir ->
                val x = cell.unitX() + dir.unitX() * 0.4f
                val y = cell.unitY() + dir.unitY() * 0.4f
                assertEquals(cell, HexCoord.fromUnit(x, y), "Punto ($x, $y) hacia $dir")
            }
        }
    }
}
