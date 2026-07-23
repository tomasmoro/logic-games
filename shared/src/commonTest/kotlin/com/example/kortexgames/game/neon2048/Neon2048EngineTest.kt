package com.example.kortexgames.game.neon2048

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del álgebra pura del tablero de Neon Grid 2048: colapso, fusión (con la
 * regla de "una fusión por ficha y turno"), conservación de identidades y
 * detección de fin de partida. Sin framework, corrutinas ni ViewModel: por eso
 * [collapse] y [hasMovesLeft] viven fuera de la clase.
 */
class Neon2048EngineTest {

    // --- Utilidades -----------------------------------------------------------

    /**
     * Construye un tablero desde una notación visual de 4 filas, donde `.` es
     * casilla vacía. Los ids se asignan por orden de lectura, así los tests
     * pueden razonar sobre identidades sin escribirlas a mano.
     */
    private fun board(vararg rows: String): List<Tile> {
        val tiles = mutableListOf<Tile>()
        var id = 0L
        rows.forEachIndexed { r, row ->
            row.trim().split(Regex("\\s+")).forEachIndexed { c, cell ->
                if (cell != ".") tiles += Tile(id = id++, value = cell.toInt(), row = r, col = c)
            }
        }
        return tiles
    }

    /** Valores del tablero fila a fila (lado [Neon2048Config.DEFAULT_BOARD_SIZE]
     *  salvo que se indique otro), con 0 en las casillas vacías. */
    private fun valuesOf(tiles: List<Tile>, boardSize: Int = Neon2048Config.DEFAULT_BOARD_SIZE): List<List<Int>> {
        val byPos = tiles.associateBy { it.position }
        return (0 until boardSize).map { r ->
            (0 until boardSize).map { c -> byPos[GridPos(r, c)]?.value ?: 0 }
        }
    }

    // --- Deslizamiento --------------------------------------------------------

    @Test
    fun deslizarCompactaLosHuecosSinFusionar() {
        val result = collapse(
            board(
                ".  2  .  4",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )

        assertEquals(listOf(2, 4, 0, 0), valuesOf(result.tiles)[0])
        assertTrue(result.moved)
        assertEquals(0, result.mergeCount)
        assertEquals(0, result.gainedScore)
    }

    @Test
    fun deslizarHaciaCadaParedLlevaLasFichasASuBorde() {
        val start = board(
            ".  .  2  .",
            ".  .  .  .",
            ".  .  .  .",
            ".  .  .  .",
        )
        val n = Neon2048Config.DEFAULT_BOARD_SIZE
        assertEquals(GridPos(0, 3), collapse(start, Direction.RIGHT, n).tiles.single().position)
        assertEquals(GridPos(0, 0), collapse(start, Direction.LEFT, n).tiles.single().position)
        assertEquals(GridPos(0, 2), collapse(start, Direction.UP, n).tiles.single().position)
        assertEquals(GridPos(3, 2), collapse(start, Direction.DOWN, n).tiles.single().position)
    }

    @Test
    fun swipeQueNoCambiaNadaNoCuentaComoJugada() {
        // Fila ya pegada a la pared izquierda y sin parejas: LEFT no hace nada.
        val result = collapse(
            board(
                "2  4  .  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )
        assertFalse(result.moved)
    }

    // --- Fusión ---------------------------------------------------------------

    @Test
    fun dosFichasIgualesSeFusionanYPuntuanElValorResultante() {
        val result = collapse(
            board(
                "2  2  .  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )

        assertEquals(listOf(4, 0, 0, 0), valuesOf(result.tiles)[0])
        assertEquals(1, result.mergeCount)
        assertEquals(4, result.gainedScore)   // regla clásica: suma el valor creado
        assertEquals(4, result.highestMerge)
        assertTrue(result.tiles.single().isMerged, "la resultante debe pedir el 'pop'")
    }

    @Test
    fun cadaFichaSoloSeFusionaUnaVezPorTurno() {
        // [2,2,4,.] hacia la izquierda debe dar [4,4,.,.] y NO [8,.,.,.]:
        // el 4 recién creado no puede volver a fusionarse en el mismo turno.
        val result = collapse(
            board(
                "2  2  4  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )

        assertEquals(listOf(4, 4, 0, 0), valuesOf(result.tiles)[0])
        assertEquals(1, result.mergeCount)
        assertEquals(4, result.gainedScore)
    }

    @Test
    fun cuatroIgualesDanDosFusionesIndependientes() {
        val result = collapse(
            board(
                "2  2  2  2",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )

        assertEquals(listOf(4, 4, 0, 0), valuesOf(result.tiles)[0])
        assertEquals(2, result.mergeCount)
        assertEquals(8, result.gainedScore)
    }

    @Test
    fun laFusionSeResuelveHaciaLaParedDeDestino() {
        // [4,2,2,.] hacia la DERECHA: se fusionan los dos 2 (los más cercanos a la
        // pared), no el 2 con nada más. Resultado [.,.,4,4].
        val result = collapse(
            board(
                "4  2  2  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.RIGHT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )
        assertEquals(listOf(0, 0, 4, 4), valuesOf(result.tiles)[0])
    }

    @Test
    fun laFusionConservaLaIdentidadDeLaFichaJuntoALaPared() {
        // Identidades: id 0 en (0,0) y id 1 en (0,1). Al deslizar a la izquierda
        // sobrevive el id 0 (el que "espera" junto a la pared) y el 1 queda como
        // fantasma en la casilla destino, para animar el encuentro en vez de
        // desvanecerse donde estaba.
        val result = collapse(
            board(
                "2  2  .  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )

        assertEquals(0L, result.tiles.single().id)
        val ghost = result.ghosts.single()
        assertEquals(1L, ghost.id)
        assertEquals(GridPos(0, 1), ghost.from, "el fantasma parte de SU casilla original")
        assertEquals(GridPos(0, 0), ghost.to, "el fantasma viaja al destino")
        assertEquals(2, ghost.value, "el fantasma conserva su valor original")
    }

    @Test
    fun elFantasmaNuncaNaceYaEnElDestino() {
        // Regresión: si `from == to`, la UI (Neon2048GhostView) siembra el
        // Animatable ya en el destino y la ficha absorbida se "teletransporta"
        // sin animar, quedando plantada y visible hasta que la superviviente la
        // tapa al llegar — el bug reportado ("el 4 sigue viéndose detrás"). Un
        // fantasma con recorrido de al menos una casilla no puede tener from==to.
        val result = collapse(
            board(
                "2  2  .  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )
        val ghost = result.ghosts.single()
        assertTrue(ghost.from != ghost.to, "el fantasma debe recorrer distancia, no nacer ya en el destino")
    }

    @Test
    fun lasBanderasDeAnimacionSeLimpianEnLaSiguienteJugada() {
        val first = collapse(
            board(
                "2  2  .  .",
                ".  .  .  .",
                ".  .  .  .",
                ".  .  .  .",
            ),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )
        // Segunda jugada sobre el resultado: el "pop" ya se consumió.
        val second = collapse(
            first.tiles + Tile(id = 99, value = 4, row = 0, col = 3),
            Direction.LEFT,
            Neon2048Config.DEFAULT_BOARD_SIZE,
        )
        assertTrue(second.tiles.none { !it.isMerged && it.isNew }, "no debe quedar isNew colgado")
        assertEquals(1, second.tiles.count { it.isMerged }, "solo la fusión de ESTA jugada")
    }

    // --- Fin de partida -------------------------------------------------------

    @Test
    fun quedanJugadasMientrasHayaHuecos() {
        assertTrue(
            hasMovesLeft(
                board(
                    "2  4  8  16",
                    "4  8  16 32",
                    "8  16 32 64",
                    "16 32 64 .",
                ),
                Neon2048Config.DEFAULT_BOARD_SIZE,
            ),
        )
    }

    @Test
    fun tableroLlenoConVecinasIgualesTodaviaTieneJugada() {
        assertTrue(
            hasMovesLeft(
                board(
                    "2  4  8  16",
                    "4  8  16 32",
                    "8  16 32 64",
                    "16 32 64 64",   // pareja adyacente en la última fila
                ),
                Neon2048Config.DEFAULT_BOARD_SIZE,
            ),
        )
    }

    @Test
    fun tableroLlenoSinVecinasIgualesEsGameOver() {
        assertFalse(
            hasMovesLeft(
                board(
                    "2   4   8   16",
                    "4   8   16  32",
                    "8   16  32  64",
                    "16  32  64  128",
                ),
                Neon2048Config.DEFAULT_BOARD_SIZE,
            ),
        )
    }

    // --- Tamaño de tablero variable --------------------------------------------

    @Test
    fun collapseFuncionaEnUnTableroDeOtroTamano() {
        // Regresión de la parametrización de boardSize: un 6×6 debe colapsar
        // hasta SU pared (columna 5), no hasta la del 4×4 (columna 3).
        val result = collapse(
            board(
                "2  2  .  .  .  .",
                ".  .  .  .  .  .",
                ".  .  .  .  .  .",
                ".  .  .  .  .  .",
                ".  .  .  .  .  .",
                ".  .  .  .  .  .",
            ),
            Direction.RIGHT,
            6,
        )
        assertEquals(GridPos(0, 5), result.tiles.single().position)
        assertEquals(1, result.mergeCount)
    }

    @Test
    fun hasMovesLeftRespetaElTamanoDeTablero() {
        // Mismas 16 fichas (el patrón sin parejas adyacentes de
        // `tableroLlenoSinVecinasIgualesEsGameOver`), interpretadas con dos
        // tamaños de tablero distintos: para 4×4 llenan el tablero entero (game
        // over); para 5×5 solo ocupan 16 de 25 casillas, así que sobra sitio
        // aunque los valores sean idénticos.
        val sixteenTiles = board(
            "2   4   8   16",
            "4   8   16  32",
            "8   16  32  64",
            "16  32  64  128",
        )
        assertFalse(hasMovesLeft(sixteenTiles, 4))
        assertTrue(hasMovesLeft(sixteenTiles, 5))
    }

    // --- Modelo ---------------------------------------------------------------

    @Test
    fun elExponenteDeLaFichaEsExactoParaTodaLaRampaDeColor() {
        // power alimenta la rampa neón de la UI: un desfase de 1 pintaría toda la
        // partida con el color equivocado.
        var value = 2
        var expected = 1
        while (value <= 4096) {
            assertEquals(expected, Tile(id = 0, value = value, row = 0, col = 0).power, "value=$value")
            value *= 2
            expected++
        }
    }
}
