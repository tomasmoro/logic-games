package com.example.kortexgames.game.energyflow

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del núcleo puro de "Flujo de Energía": geometría de conectores/rotación,
 * detección de circuito cerrado y, sobre todo, el **invariante crítico** de que todo
 * nivel generado es resoluble aplicando a cada pieza sus giros mínimos. Sin
 * dependencias de framework ni corrutinas.
 */
class EnergyFlowModelTest {

    // --- Geometría de dirección / rotación -----------------------------------

    @Test
    fun elOpuestoEsElLadoContrario() {
        assertEquals(Direction.SOUTH, Direction.NORTH.opposite)
        assertEquals(Direction.WEST, Direction.EAST.opposite)
    }

    @Test
    fun girarEnHorarioAvanzaLosLados() {
        // N→E→S→O al girar 90° en horario.
        assertEquals(Direction.EAST, Direction.NORTH.rotatedClockwise(1))
        assertEquals(Direction.SOUTH, Direction.NORTH.rotatedClockwise(2))
        assertEquals(Direction.NORTH, Direction.NORTH.rotatedClockwise(4))
    }

    @Test
    fun losConectoresEfectivosSiguenLaRotacion() {
        val codo = Tile(0, 0, TileKind.PIPE, setOf(Direction.NORTH, Direction.EAST), rotation = 1)
        // Un codo N-E girado 90° horario pasa a E-S.
        assertEquals(setOf(Direction.EAST, Direction.SOUTH), codo.effectiveConnectors)
    }

    @Test
    fun laFormaDistingueRectaDeCodo() {
        val recta = Tile(0, 0, TileKind.PIPE, setOf(Direction.NORTH, Direction.SOUTH))
        val codo = Tile(0, 0, TileKind.PIPE, setOf(Direction.NORTH, Direction.EAST))
        assertEquals(TileShape.STRAIGHT, recta.shape)
        assertEquals(TileShape.CORNER, codo.shape)
    }

    // --- Giros mínimos (simetría) --------------------------------------------

    @Test
    fun laRectaSeResuelveEnComoMuchoUnGiro() {
        // Periodo 2: da igual rotación par (ya resuelta) o impar (un giro).
        assertEquals(0, Tile(0, 0, TileKind.PIPE, setOf(Direction.NORTH, Direction.SOUTH), rotation = 2).minRotationsToSolve())
        assertEquals(1, Tile(0, 0, TileKind.PIPE, setOf(Direction.NORTH, Direction.SOUTH), rotation = 3).minRotationsToSolve())
    }

    @Test
    fun laCruzSiempreEstaResuelta() {
        val cross = Tile(0, 0, TileKind.PIPE, Direction.entries.toSet(), rotation = 3)
        assertEquals(0, cross.minRotationsToSolve())
    }

    // --- Circuito cerrado -----------------------------------------------------

    @Test
    fun detectaCircuitoCerradoSinFugas() {
        // Dos celdas en fila conectadas: izquierda(SOURCE)→E, derecha(TARGET)→W.
        val a = Tile(0, 0, TileKind.SOURCE, setOf(Direction.EAST))
        val b = Tile(0, 1, TileKind.TARGET, setOf(Direction.WEST))
        val grid = EnergyGrid(rows = 1, cols = 2, tiles = listOf(a, b))

        assertFalse(grid.hasLeaks())
        assertTrue(grid.isSolved())
        assertEquals(setOf(0, 1), grid.poweredIndices())
    }

    @Test
    fun detectaFugaSiUnConectorNoTieneReciproco() {
        // La derecha apunta al ESTE (fuera del tablero): fuga → no resuelto.
        val a = Tile(0, 0, TileKind.SOURCE, setOf(Direction.EAST))
        val b = Tile(0, 1, TileKind.TARGET, setOf(Direction.EAST))
        val grid = EnergyGrid(rows = 1, cols = 2, tiles = listOf(a, b))

        assertTrue(grid.hasLeaks())
        assertFalse(grid.isSolved())
        // La fuente sí queda energizada, pero su vecino no reciproca ⇒ solo ella.
        assertEquals(setOf(0), grid.poweredIndices())
    }

    @Test
    fun girarUnaPiezaCierraElCircuito() {
        // La derecha (TARGET) apunta al ESTE; un giro horario la lleva al SUR... no.
        // Mejor: apunta al NORTE (fuga); dos giros → SUR (fuga); un giro desde NORTE
        // en horario → ESTE (fuga); tres giros → OESTE (encaja). Verificamos que
        // existe una rotación que resuelve y que rotate() la alcanza.
        val a = Tile(0, 0, TileKind.SOURCE, setOf(Direction.EAST))
        val b = Tile(0, 1, TileKind.TARGET, setOf(Direction.NORTH))
        var grid = EnergyGrid(rows = 1, cols = 2, tiles = listOf(a, b))
        assertFalse(grid.isSolved())

        // Gira la pieza 1 hasta que el conector mire al OESTE y cierre el circuito.
        var solvedAtStep = -1
        for (step in 1..4) {
            grid = grid.rotate(1)
            if (grid.isSolved()) { solvedAtStep = step; break }
        }
        assertTrue(solvedAtStep in 1..4, "Debe existir una rotación que cierre el circuito")
    }

    // --- Generador ------------------------------------------------------------

    @Test
    fun todoNivelGeneradoEsResolubleParaCadaDificultad() {
        // Invariante clave: con semilla fija, cada dificultad de la campaña produce
        // un nivel que (1) no nace ya resuelto y (2) se resuelve aplicando a cada
        // pieza sus giros mínimos (la validez de cada celda es independiente).
        for (difficulty in 1..3) {
            val config = EnergyFlowGenerator.configForDifficulty(difficulty)
            val level = EnergyFlowGenerator.generate(config, Random(seed = 42L + difficulty))
            val grid = level.grid

            assertEquals(config.rows * config.cols, grid.tiles.size)
            assertEquals(1, grid.tiles.count { it.kind == TileKind.SOURCE })
            assertEquals(1, grid.tiles.count { it.kind == TileKind.TARGET })
            assertFalse(grid.isSolved(), "El nivel de dificultad $difficulty no debería nacer resuelto")

            // Aplica los giros mínimos a cada pieza → debe quedar resuelto.
            val solvedTiles = grid.tiles.map { it.copy(rotation = it.rotation + it.minRotationsToSolve()) }
            val solvedGrid = grid.copy(tiles = solvedTiles)
            assertTrue(solvedGrid.isSolved(), "El nivel de dificultad $difficulty debe ser resoluble")
            assertEquals(
                solvedGrid.tiles.size,
                solvedGrid.poweredIndices().size,
                "Resuelto ⇒ toda la red energizada desde la fuente",
            )
        }
    }

    @Test
    fun elDestinoSiempreEsUnaHojaRealDelArbolEnMuchasSemillas() {
        // Regresión: forzar el destino a "la esquina más lejana" (probado antes) podía
        // asignarle una celda que no era hoja del árbol de expansión, generando niveles
        // sin solución real. El destino debe ser SIEMPRE una hoja auténtica (grado 1 en
        // los conectores resueltos), sea cual sea la semilla o el tamaño.
        for (n in 4..7) {
            val config = EnergyFlowGenerator.Config(n, n)
            for (seed in 0L until 30L) {
                val level = EnergyFlowGenerator.generate(config, Random(seed = seed))
                val grid = level.grid
                val target = grid.tiles[grid.targetIndex]
                assertEquals(
                    1, target.connectors.size,
                    "n=$n seed=$seed: el destino debe tener un único conector (hoja del árbol)",
                )

                // Y el nivel completo debe seguir siendo resoluble aplicando los giros mínimos.
                val solvedTiles = grid.tiles.map { it.copy(rotation = it.rotation + it.minRotationsToSolve()) }
                val solvedGrid = grid.copy(tiles = solvedTiles)
                assertTrue(solvedGrid.isSolved(), "n=$n seed=$seed: el nivel debe ser resoluble")
            }
        }
    }

    @Test
    fun lasTresDificultadesUsanLasCuadriculasPedidas() {
        assertEquals(EnergyFlowGenerator.Config(4, 4), EnergyFlowGenerator.configForDifficulty(1))
        assertEquals(EnergyFlowGenerator.Config(5, 5), EnergyFlowGenerator.configForDifficulty(2))
        assertEquals(EnergyFlowGenerator.Config(6, 6), EnergyFlowGenerator.configForDifficulty(3))
    }

    @Test
    fun elOptimoEsPositivoYNoSuperaElSolveCompleto() {
        // El óptimo solo cuenta las piezas del camino fuente→destino, así que es
        // positivo (el nivel no nace resuelto) y nunca peor que resolver TODO el
        // tablero pieza a pieza.
        val config = EnergyFlowGenerator.configForDifficulty(3)
        val level = EnergyFlowGenerator.generate(config, Random(seed = 7L))
        val fullSolve = level.grid.tiles.sumOf { it.minRotationsToSolve() }
        assertTrue(level.optimalRotations >= 1)
        assertTrue(level.optimalRotations <= fullSolve)
    }

    @Test
    fun seGanaCuandoLaEnergiaLlegaAlDestinoAunqueQuedenTuberiasSueltas() {
        // 2×2: fuente(E)→destino(W) ya conectados arriba; abajo quedan dos tuberías
        // sueltas (apuntan al norte, sin recíproco). Hay fugas pero el destino recibe
        // energía ⇒ victoria (basta con encontrar un camino, no dejar todo perfecto).
        val source = Tile(0, 0, TileKind.SOURCE, setOf(Direction.EAST))
        val target = Tile(0, 1, TileKind.TARGET, setOf(Direction.WEST))
        val loose1 = Tile(1, 0, TileKind.PIPE, setOf(Direction.NORTH))
        val loose2 = Tile(1, 1, TileKind.PIPE, setOf(Direction.NORTH))
        val grid = EnergyGrid(rows = 2, cols = 2, tiles = listOf(source, target, loose1, loose2))

        assertTrue(grid.hasLeaks())
        assertTrue(grid.isSolved())
    }

    @Test
    fun laGeneracionEsDeterministaConLaMismaSemilla() {
        val config = EnergyFlowGenerator.configForDifficulty(3)
        val a = EnergyFlowGenerator.generate(config, Random(seed = 7L))
        val b = EnergyFlowGenerator.generate(config, Random(seed = 7L))
        assertEquals(a.grid.tiles, b.grid.tiles)
    }

}
