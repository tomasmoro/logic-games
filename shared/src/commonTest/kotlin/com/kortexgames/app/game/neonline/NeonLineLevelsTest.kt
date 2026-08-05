package com.kortexgames.app.game.neonline

import com.kortexgames.app.game.grid.GridPathBuilder
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighborsIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests del generador procedural de "Línea Neón". Lo que de verdad hay que blindar
 * aquí no es la estética del tablero sino la **solubilidad**: el argumento del
 * generador es que construir el nivel desde un camino ya trazado hace imposible
 * generar un tablero irresoluble, y eso se comprueba verificando que la zona jugable
 * conserva las propiedades de un camino (conexa, ≤ 2 callejones sin salida).
 *
 * Buena parte de esas invariantes las valida ya el `init` de [NeonLineLevel], así
 * que "no lanzar" es en sí una aserción.
 */
class NeonLineLevelsTest {

    @Test
    fun elTableroCreceCada2NivelesHastaElMaximo() {
        val expected = mapOf(
            1 to 4, 2 to 4, 3 to 4, 4 to 4,
            5 to 5, 6 to 5, 7 to 5, 8 to 5,
            9 to 6, 10 to 6, 11 to 6, 12 to 6,
            13 to 7, 14 to 7, 15 to 7, 16 to 7,
            17 to 8, 18 to 8, 19 to 8, 20 to 8, 60 to 8,
        )
        expected.forEach { (number, gridSize) ->
            assertEquals(gridSize, NeonLineLevels.forNumber(number).gridSize, "nivel $number")
        }
    }

    @Test
    fun ningunNivelSaleDespejado() {
        // Un tablero sin bloques se resuelve serpenteando sin pensar y enseña la
        // mecánica equivocada: desde el nivel 1 hay algo que esquivar.
        (1..60).forEach { number ->
            assertTrue(
                NeonLineLevels.forNumber(number).obstacles.isNotEmpty(),
                "nivel $number salió despejado",
            )
        }
    }

    @Test
    fun elNumeroDeObstaculosSubePorEscalon() {
        val expected = mapOf(
            1 to 2, 2 to 2,
            3 to 4, 4 to 4,
            5 to 4, 6 to 4,
            7 to 6, 8 to 6,
            9 to 6, 10 to 6,
            11 to 7, 12 to 7,
            13 to 6, 14 to 6,
            15 to 8, 16 to 8,
            17 to 7, 18 to 7,
            19 to 8, 20 to 8, 40 to 8,
        )
        expected.forEach { (number, obstacles) ->
            assertEquals(obstacles, NeonLineLevels.forNumber(number).obstacles.size, "nivel $number")
        }
    }

    @Test
    fun losBloquesNuncaSeAgrupanEnMuros() {
        // El corazón del cambio de dificultad: dos bloques pegados de lado se
        // comportan como una pared y solo recortan el tablero; sueltos, obligan a
        // rodearlos y a decidir por dónde.
        (1..60).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            level.obstacles.forEach { cell ->
                val touching = cell.orthogonalNeighborsIn(level.obstacles)
                if (touching.isNotEmpty()) {
                    fail("nivel $number: los bloques $cell y ${touching.first()} están pegados")
                }
            }
        }
    }

    @Test
    fun todosLosNivelesSalenDelMetodoDisperso() {
        // Si esto falla, la red de seguridad (complemento de un paseo, que agrupa los
        // bloques) se está usando de verdad y hay que revisar el escalón que la
        // dispara: sus niveles serán notablemente más blandos que el resto.
        (1..60).forEach { number ->
            assertTrue(
                NeonLineLevels.chosenCandidate(number).scattered,
                "nivel $number cayó en la red de seguridad",
            )
        }
    }

    @Test
    fun laZonaJugableSiempreEquilibraElTableroDeAjedrez() {
        // Condición necesaria para que exista camino: la línea alterna color en cada
        // paso, así que no puede cubrir una zona con 2 celdas de ventaja para un color.
        (1..60).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            assertTrue(
                GridPathBuilder.hasHamiltonianParity(level.playableCells),
                "nivel $number: paridad de ajedrez desequilibrada",
            )
        }
    }

    @Test
    fun todoNivelGeneradoEsValidoYResoluble() {
        // El `init` de NeonLineLevel valida conexión y callejones: si el generador
        // produjera un tablero irresoluble, construirlo lanzaría.
        (1..60).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            assertEquals(number, level.number)
            assertTrue(level.obstacles.all { it.isInside(level.gridSize) }, "nivel $number")
            assertTrue(level.playableCount >= MIN_PLAYABLE_CELLS, "nivel $number")
            assertEquals(
                level.gridSize * level.gridSize,
                level.playableCount + level.obstacles.size,
                "nivel $number: libres + obstáculos debe cubrir el tablero",
            )
        }
    }

    @Test
    fun laZonaJugableNuncaTieneMasDeDosCallejonesSinSalida() {
        // Invariante estructural que regala la construcción desde un camino: solo
        // sus dos extremos pueden quedar con una única salida. Con 3 o más, ninguna
        // línea única podría recorrer el tablero.
        (1..60).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            val deadEnds = level.playableCells.count {
                it.orthogonalNeighborsIn(level.playableCells).size == 1
            }
            assertTrue(deadEnds <= 2, "nivel $number: $deadEnds callejones sin salida")
        }
    }

    @Test
    fun laDificultadDelRepartoNoDecreceDentroDelMismoEscalon() {
        // Dentro de un escalón el tablero no cambia; lo que sube es lo enredado del
        // reparto (ver el ranking por junctionRatio del generador).
        listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19).forEach { first ->
            val a = NeonLineLevels.chosenCandidate(first)
            val b = NeonLineLevels.chosenCandidate(first + 1)
            assertEquals(
                NeonLineLevels.forNumber(first).gridSize,
                NeonLineLevels.forNumber(first + 1).gridSize,
                "escalón de $first",
            )
            assertTrue(
                b.hardnessScore >= a.hardnessScore,
                "escalón de $first: ${b.hardnessScore} < ${a.hardnessScore}",
            )
        }
    }

    @Test
    fun elGeneradorEsDeterminista() {
        (1..20).forEach { number ->
            assertEquals(
                NeonLineLevels.forNumber(number),
                NeonLineLevels.forNumber(number),
                "nivel $number debería regenerarse idéntico",
            )
        }
    }

    @Test
    fun elConstructorDeCaminosDevuelveUnCaminoSimpleDeLaLongitudPedida() {
        // Contrato del componente compartido con Conectores: longitud exacta, celdas
        // contiguas en cruz y sin repetir (con y sin cobertura total del tablero).
        listOf(4 to 16, 5 to 19, 6 to 26, 7 to 34, 8 to 42).forEach { (gridSize, length) ->
            repeat(8) { seed ->
                val path = GridPathBuilder.simplePath(gridSize, length, seed.toLong())
                assertEquals(length, path.size, "tablero $gridSize, semilla $seed")
                assertEquals(length, path.toSet().size, "tablero $gridSize: celdas repetidas")
                assertTrue(
                    path.all { it.isInside(gridSize) },
                    "tablero $gridSize: celdas fuera del tablero",
                )
                assertTrue(
                    path.zipWithNext().all { (a, b) -> a.isOrthogonallyAdjacentTo(b) },
                    "tablero $gridSize: hay un salto o una diagonal",
                )
            }
        }
    }

    @Test
    fun todoNivelGeneradoTieneCaminoDemostrable() {
        // La garantía de solubilidad del generador: un nivel solo se publica si se le
        // encontró un camino completo. Aquí se vuelve a buscar desde fuera, sin
        // conocer el que halló el generador.
        (1..40).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            val path = GridPathBuilder.hamiltonianPathIn(level.playableCells, seed = 7L)
            assertNotNull(path, "nivel $number: no se encontró camino")
            assertEquals(level.playableCount, path.size, "nivel $number")
            assertEquals(level.playableCells, path.toSet(), "nivel $number")
        }
    }

    @Test
    fun laBusquedaEnRegionRechazaLoImposible() {
        // Las tres podas de hamiltonianPathIn, cada una con su contraejemplo mínimo
        // en un 3x3 (las regiones se declaran a mano, no salen del generador).
        fun cells(vararg rc: Pair<Int, Int>) = rc.map { GridPosition(it.first, it.second) }.toSet()

        // Islas inconexas: dos celdas opuestas sin nada que las una.
        assertNull(GridPathBuilder.hamiltonianPathIn(cells(0 to 0, 2 to 2), seed = 1L))

        // Paridad rota: tres celdas del mismo color de ajedrez (las tres "esquinas
        // pares"), imposibles de recorrer alternando color.
        assertNull(GridPathBuilder.hamiltonianPathIn(cells(0 to 0, 0 to 2, 2 to 0), seed = 1L))

        // Tres callejones sin salida: una cruz. El centro tiene 4 salidas y los cuatro
        // brazos solo una, pero un camino solo admite 2 extremos.
        assertNull(
            GridPathBuilder.hamiltonianPathIn(
                cells(1 to 1, 0 to 1, 2 to 1, 1 to 0, 1 to 2),
                seed = 1L,
            ),
        )
    }
}
