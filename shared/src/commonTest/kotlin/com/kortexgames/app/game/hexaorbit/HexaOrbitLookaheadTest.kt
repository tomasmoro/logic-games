package com.kortexgames.app.game.hexaorbit

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests de la **matriz hexagonal y el algoritmo de trazado** de Hexa Orbit (FASE 1).
 *
 * Cubren las cuatro garantías sobre las que se apoyarán el motor (FASE 2) y el renderizado
 * (FASE 3):
 *
 *  1. La rotación de un azulejo es una permutación coherente y reversible de sus caminos.
 *  2. Los azulejos vecinos empalman: la arista de salida de uno es la de entrada del otro, y
 *     ambos puntos medios caen en el mismo lugar del plano.
 *  3. El trazado ilumina exactamente 4 azulejos futuros y detecta la fuga por la frontera.
 *  4. Un circuito cerrado no cuelga el algoritmo.
 */
class HexaOrbitLookaheadTest {

    /** Tablero de radio 3 con todas las piezas en una orientación conocida. */
    private fun uniformBoard(pattern: TilePattern, rotation: Int = 0): HexBoard = HexBoard(
        radius = HexaOrbitBalance.BOARD_RADIUS,
        tiles = HexBoard.coordsWithin(HexaOrbitBalance.BOARD_RADIUS)
            .associateWith { HexTile(it, pattern, rotation) },
    )

    // --- 1. Rotación --------------------------------------------------------------------------

    @Test
    fun `los cuatro patrones emparejan las seis aristas sin dejar ninguna suelta`() {
        for (pattern in TilePattern.entries) {
            val covered = pattern.connections.flatMap { listOf(it.a, it.b) }.toSet()
            assertEquals((0 until HEX_EDGES).toSet(), covered, "Patrón incompleto: $pattern")
            assertEquals(3, pattern.connections.size, "Un azulejo tiene 3 caminos: $pattern")
        }
    }

    @Test
    fun `girar seis veces devuelve el azulejo a su orientación original`() {
        for (pattern in TilePattern.entries) {
            var tile = HexTile(HexCoord.ORIGIN, pattern, rotation = 0)
            repeat(HEX_EDGES) { tile = tile.rotatedClockwise() }
            assertEquals(0, tile.rotation)
            assertEquals(pattern.connections.toSet(), tile.connections.toSet())
        }
    }

    @Test
    fun `girar un paso desplaza cada salida exactamente una arista en horario`() {
        // Es el invariante del que depende la legibilidad del juego: el jugador debe poder
        // predecir el efecto de un tap sin recalcular el patrón mentalmente.
        for (pattern in TilePattern.entries) {
            val base = HexTile(HexCoord.ORIGIN, pattern, rotation = 0)
            val turned = base.rotatedClockwise()
            for (entry in 0 until HEX_EDGES) {
                val expected = (base.exitEdgeFor((entry - 1).mod(HEX_EDGES)) + 1).mod(HEX_EDGES)
                assertEquals(expected, turned.exitEdgeFor(entry), "$pattern entrando por $entry")
            }
        }
    }

    @Test
    fun `los caminos son bidireccionales en cualquier rotación`() {
        for (pattern in TilePattern.entries) {
            for (rotation in 0 until HEX_EDGES) {
                val tile = HexTile(HexCoord.ORIGIN, pattern, rotation)
                for (entry in 0 until HEX_EDGES) {
                    val exit = tile.exitEdgeFor(entry)
                    assertEquals(entry, tile.exitEdgeFor(exit), "$pattern/$rotation arista $entry")
                }
            }
        }
    }

    // --- 2. Empalme entre azulejos vecinos ----------------------------------------------------

    @Test
    fun `la arista de salida y la de entrada del vecino son la misma frontera`() {
        for (edge in 0 until HEX_EDGES) {
            val direction = HexDirection.ofEdge(edge)
            val neighbour = HexCoord.ORIGIN + direction
            val entryOnNeighbour = oppositeEdge(edge)

            // Mismo punto del plano visto desde los dos azulejos: si no coincidiera, las curvas
            // de dos piezas contiguas se dibujarían con un salto en la frontera.
            val fromHere = HexGeometry.center(HexCoord.ORIGIN) + HexGeometry.edgeMidpoint(edge)
            val fromThere = HexGeometry.center(neighbour) + HexGeometry.edgeMidpoint(entryOnNeighbour)
            assertTrue(fromHere.distanceTo(fromThere) < 1e-4f, "Arista $edge no empalma")
        }
    }

    @Test
    fun `la distancia entre centros de vecinos es siempre la anchura del hexágono`() {
        val expected = 2f * HexGeometry.APOTHEM
        for (direction in HexDirection.entries) {
            val distance = HexGeometry.center(HexCoord.ORIGIN)
                .distanceTo(HexGeometry.center(HexCoord.ORIGIN + direction))
            assertTrue(abs(distance - expected) < 1e-4f, "Vecino $direction a distancia $distance")
        }
    }

    @Test
    fun `las curvas rectas degeneran en el segmento que cruza el centro`() {
        // Es lo que permite no tener un caso especial para las rectas al dibujar (§ curveFor).
        val curve = HexGeometry.curveFor(EdgePair(0, 3))
        assertTrue(curve.control1.distanceTo(curve.start * (1f / 3f)) < 1e-4f)
        assertTrue(abs(curve.start.y) < 1e-4f && abs(curve.end.y) < 1e-4f)
        assertTrue(abs(curve.start.x + curve.end.x) < 1e-4f, "Aristas opuestas: extremos simétricos")
    }

    // --- 2b. Píxel → celda (el acierto del tap) -----------------------------------------------

    @Test
    fun `el centro de cada celda se resuelve a esa misma celda`() {
        for (coord in HexBoard.coordsWithin(HexaOrbitBalance.BOARD_RADIUS)) {
            assertEquals(coord, HexGeometry.hexAt(HexGeometry.center(coord)), "Centro de $coord")
        }
    }

    @Test
    fun `un punto junto a un vertice cae en una de las tres celdas que lo comparten`() {
        // Es el caso que rompe el redondeo ingenuo de q y r por separado: tres celdas se tocan en
        // cada vértice y el dedo aterriza ahí con frecuencia.
        for (coord in HexBoard.coordsWithin(2)) {
            val center = HexGeometry.center(coord)
            for (i in 0 until HEX_EDGES) {
                val corner = HexGeometry.corner(i)
                // Se acerca el punto un 8 % hacia el centro: justo dentro de ESTA celda.
                val probe = center + HexPoint(corner.x * 0.92f, corner.y * 0.92f)
                assertEquals(coord, HexGeometry.hexAt(probe), "Vértice $i de $coord")
            }
        }
    }

    @Test
    fun `un punto pasado el punto medio de una arista cae en el vecino`() {
        for (edge in 0 until HEX_EDGES) {
            val mid = HexGeometry.edgeMidpoint(edge)
            // Un 15 % más allá de la arista: ya es territorio del vecino de esa dirección.
            val probe = HexPoint(mid.x * 1.15f, mid.y * 1.15f)
            assertEquals(
                HexCoord.ORIGIN + HexDirection.ofEdge(edge),
                HexGeometry.hexAt(probe),
                "Arista $edge",
            )
        }
    }

    // --- 3. Trazado proyectado ----------------------------------------------------------------

    @Test
    fun `el trazado ilumina el azulejo actual mas cuatro futuros`() {
        // Tablero de rectas recorrido de oeste a este. Se arranca en (-1, 0) y no en el centro
        // porque desde el centro solo quedan 3 azulejos hasta el borde: el horizonte completo
        // necesita 4 por delante.
        val board = uniformBoard(TilePattern.TRIPLE_STRAIGHT)
        val start = HexCoord(-1, 0)
        val path = board.project(from = start, entryEdge = HexDirection.WEST.edgeIndex)

        assertEquals(HexaOrbitBalance.LOOKAHEAD_TILES, path.upcoming.size)
        assertEquals(HexaOrbitBalance.LOOKAHEAD_TILES + 1, path.steps.size)
        assertEquals(start, path.current?.coord)
    }

    @Test
    fun `los tramos consecutivos encajan entrada con salida`() {
        val board = HexBoard.random(HexaOrbitBalance.BOARD_RADIUS, Random(7))
        val path = board.project(from = HexCoord.ORIGIN, entryEdge = 0)

        path.steps.zipWithNext { previous, next ->
            assertEquals(previous.nextCoord, next.coord, "El tramo salta a otra celda")
            assertEquals(oppositeEdge(previous.exitEdge), next.entryEdge, "Entrada incoherente")
        }
    }

    @Test
    fun `entrar por el oeste en un tablero de rectas lleva al puntero hacia el este`() {
        val board = uniformBoard(TilePattern.TRIPLE_STRAIGHT)
        val path = board.project(from = HexCoord(-3, 0), entryEdge = HexDirection.WEST.edgeIndex)

        assertEquals(
            listOf(HexCoord(-3, 0), HexCoord(-2, 0), HexCoord(-1, 0), HexCoord(0, 0), HexCoord(1, 0)),
            path.steps.map { it.coord },
        )
    }

    @Test
    fun `marca la fuga cuando el recorrido proyectado alcanza la frontera`() {
        // Desde el borde este entrando por el oeste, la recta sale del tablero en un solo paso.
        val board = uniformBoard(TilePattern.TRIPLE_STRAIGHT)
        val border = HexCoord(HexaOrbitBalance.BOARD_RADIUS, 0)
        val path = board.project(from = border, entryEdge = HexDirection.WEST.edgeIndex)

        assertTrue(path.escapes, "El haz debería avisar de la fuga")
        assertEquals(1, path.steps.size, "Solo cabe el tramo del azulejo de borde")
        assertFalse(board.contains(path.steps.first().nextCoord))
    }

    @Test
    fun `no marca fuga mientras el peligro quede fuera del horizonte visible`() {
        val board = uniformBoard(TilePattern.TRIPLE_STRAIGHT)
        val path = board.project(from = HexCoord(-3, 0), entryEdge = HexDirection.WEST.edgeIndex)
        assertFalse(path.escapes, "Con 4 azulejos de horizonte aún no se ve la frontera opuesta")
    }

    // --- 4. Circuitos cerrados ----------------------------------------------------------------

    @Test
    fun `un circuito cerrado se traza sin colgarse y repite azulejos`() {
        // Tres giros cerrados alrededor de un vértice forman un anillo por el que el puntero da
        // vueltas: es una jugada legal (sirve para "aparcar" el puntero), así que el trazado
        // debe terminar por el tope de pasos, no por salirse ni por detectar el ciclo.
        val board = uniformBoard(TilePattern.TRIPLE_SHARP)
        val path = board.project(from = HexCoord.ORIGIN, entryEdge = 0, maxTiles = 40)

        assertEquals(41, path.steps.size, "El tope de pasos es lo único que corta un bucle")
        assertFalse(path.escapes)
        assertTrue(
            path.steps.map { it.coord }.toSet().size < path.steps.size,
            "Un bucle vuelve a pisar celdas ya visitadas y esos tramos NO se deduplican",
        )
    }

    @Test
    fun `el trazado nunca se sale del tablero sin marcar la fuga`() {
        // Barrido sobre semillas y puntos de partida: cualquier tramo emitido cae dentro del
        // tablero, y solo un trazado con escapes = true termina apuntando al vacío.
        for (seed in 0 until 50) {
            val board = HexBoard.random(HexaOrbitBalance.BOARD_RADIUS, Random(seed))
            for (coord in board.tiles.keys) {
                for (entry in 0 until HEX_EDGES) {
                    val path = board.project(coord, entry)
                    assertTrue(path.steps.all { board.contains(it.coord) })
                    val last = path.steps.lastOrNull()
                    assertNotNull(last)
                    if (!board.contains(last.nextCoord)) {
                        // Solo es fuga confirmada si el horizonte llegó hasta ahí.
                        assertTrue(
                            path.escapes || path.steps.size == HexaOrbitBalance.LOOKAHEAD_TILES + 1,
                        )
                    }
                }
            }
        }
    }
}
