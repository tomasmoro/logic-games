package com.kortexgames.app.game.hexaflux

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas del tablero: colocación, fusión en cadena, gimmicks y fin de turno. */
class HexaFluxEngineTest {

    private val origin = HexCoord.ORIGIN
    private val east = HexCoord(1, 0)
    private val west = HexCoord(-1, 0)

    /** Hexágono de radio 2 vacío, con las celdas de [setup] sustituidas. */
    private fun board(vararg setup: HexCell): Map<HexCoord, HexCell> =
        HexaLevelGenerator.cellsFor(BoardMask.CONCENTRIC, enlarged = false)
            .associateWith { HexCell(it) } + setup.associateBy { it.coord }

    private fun single(tier: FluxTier) = HexPiece(1, listOf(PieceTile(origin, tier)))

    @Test
    fun noSePuedeColocarFueraDeLaMascaraNiSobreUnaCeldaOcupada() {
        val board = board(HexCell(east, tier = FluxTier.SPARK), HexCell(west, gimmick = Gimmick.Stone))
        assertNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), HexCoord(9, 9)))
        assertNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), east))
        assertNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), west))
    }

    @Test
    fun dosFichasIgualesNoFusionan() {
        val placed = assertNotNull(
            HexaFluxEngine.place(board(HexCell(east, tier = FluxTier.SPARK)), single(FluxTier.SPARK), origin),
        )
        assertEquals(0, placed.combo)
        assertEquals(FluxTier.SPARK, placed.board.getValue(origin).tier)
    }

    @Test
    fun tresFichasIgualesSeFundenEnUnaDelTierSiguienteSobreLaSemilla() {
        val board = board(HexCell(east, tier = FluxTier.SPARK), HexCell(west, tier = FluxTier.SPARK))
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), origin))
        assertEquals(1, placed.combo)
        assertEquals(FluxTier.SPARK.points * 3, placed.scoreGained)
        assertEquals(FluxTier.PULSE, placed.board.getValue(origin).tier)
        assertEquals(CellAnim.MERGED, placed.board.getValue(origin).anim)
        assertNull(placed.board.getValue(east).tier)
        assertNull(placed.board.getValue(west).tier)
    }

    @Test
    fun laFichaFusionadaEncadenaUnaSegundaFusionConMultiplicador() {
        // SPARK×3 en el centro produce un PULSE que toca a otros dos PULSE.
        val board = board(
            HexCell(east, tier = FluxTier.SPARK),
            HexCell(west, tier = FluxTier.SPARK),
            HexCell(HexCoord(0, 1), tier = FluxTier.PULSE),
            HexCell(HexCoord(0, -1), tier = FluxTier.PULSE),
        )
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), origin))
        assertEquals(2, placed.combo)
        assertEquals(FluxTier.SPARK.points * 3 + FluxTier.PULSE.points * 3 * 2, placed.scoreGained)
        assertEquals(FluxTier.SURGE, placed.board.getValue(origin).tier)
    }

    @Test
    fun unGrupoDelTierMaximoSeDesintegra() {
        val board = board(HexCell(east, tier = FluxTier.CORE), HexCell(west, tier = FluxTier.CORE))
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.CORE), origin))
        assertTrue(listOf(origin, east, west).all { placed.board.getValue(it).tier == null })
    }

    @Test
    fun laFusionRompeLosGimmicksVecinosPeroNoElMetal() {
        val ice = HexCoord(2, 0)       // vecina de `east`
        val thick = HexCoord(-2, 0)    // vecina de `west`
        val corrupt = HexCoord(0, 1)
        val stone = HexCoord(0, -1)
        val board = board(
            HexCell(east, tier = FluxTier.SPARK),
            HexCell(west, tier = FluxTier.SPARK),
            HexCell(ice, gimmick = Gimmick.Ice()),
            HexCell(thick, gimmick = Gimmick.Ice(layers = 2)),
            HexCell(corrupt, gimmick = Gimmick.Corrupt),
            HexCell(stone, gimmick = Gimmick.Stone),
        )
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), origin))
        assertNull(placed.board.getValue(ice).gimmick)
        assertEquals(Gimmick.Ice(layers = 1), placed.board.getValue(thick).gimmick)
        assertNull(placed.board.getValue(corrupt).gimmick)
        assertEquals(Gimmick.Stone, placed.board.getValue(stone).gimmick)
        // El hielo de dos capas sigue en pie: solo cuentan los retirados del todo.
        assertEquals(2, placed.gimmicksBroken)
    }

    @Test
    fun elPortalTrasladaLaFichaASuSalidaUnTierPorEncima() {
        val exit = HexCoord(2, -2)
        val board = board(
            HexCell(origin, gimmick = Gimmick.Portal(0, exit)),
            HexCell(exit, gimmick = Gimmick.Portal(0, origin)),
        )
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), origin))
        assertTrue(placed.teleported)
        assertNull(placed.board.getValue(origin).tier)
        assertEquals(FluxTier.PULSE, placed.board.getValue(exit).tier)
    }

    @Test
    fun conLaSalidaOcupadaLaFichaSeQuedaEnElPortal() {
        val exit = HexCoord(2, -2)
        val board = board(
            HexCell(origin, gimmick = Gimmick.Portal(0, exit)),
            HexCell(exit, tier = FluxTier.NOVA, gimmick = Gimmick.Portal(0, origin)),
        )
        val placed = assertNotNull(HexaFluxEngine.place(board, single(FluxTier.SPARK), origin))
        assertFalse(placed.teleported)
        assertEquals(FluxTier.SPARK, placed.board.getValue(origin).tier)
    }

    @Test
    fun laBombaCuentaAtrasYAlDetonarFundeEnMetalSusVecinasVacias() {
        val board = board(
            HexCell(origin, gimmick = Gimmick.TurnBomb(turnsLeft = 2)),
            HexCell(east, tier = FluxTier.SPARK),
        )
        val first = HexaFluxEngine.endTurn(board, spawnCorrupt = false, random = Random(1))
        assertEquals(0, first.bombsDetonated)
        assertEquals(Gimmick.TurnBomb(turnsLeft = 1), first.board.getValue(origin).gimmick)

        val second = HexaFluxEngine.endTurn(first.board, spawnCorrupt = false, random = Random(1))
        assertEquals(1, second.bombsDetonated)
        assertEquals(Gimmick.Stone, second.board.getValue(origin).gimmick)
        assertEquals(Gimmick.Stone, second.board.getValue(west).gimmick)
        // La ficha vecina sobrevive a la detonación.
        assertNull(second.board.getValue(east).gimmick)
        assertEquals(FluxTier.SPARK, second.board.getValue(east).tier)
    }

    @Test
    fun laFichaCorruptaApareceEnUnaCeldaVacia() {
        val before = board(HexCell(origin, tier = FluxTier.SPARK))
        val after = HexaFluxEngine.endTurn(before, spawnCorrupt = true, random = Random(3)).board
        val spawned = after.values.single { it.gimmick == Gimmick.Corrupt }
        assertNull(spawned.tier)
        assertTrue(before.getValue(spawned.coord).isFree)
    }

    @Test
    fun hayJugadaSiLaPiezaSoloCabeRotada() {
        // Solo quedan libres el centro y su vecina del sureste: la pieza horizontal
        // (centro + este) no cabe tal cual, pero sí girada 60°.
        val free = setOf(origin, HexCoord(0, 1))
        val board = board().mapValues { (at, cell) -> if (at in free) cell else cell.copy(gimmick = Gimmick.Stone) }
        val duo = HexPiece(1, listOf(PieceTile(origin, FluxTier.SPARK), PieceTile(east, FluxTier.SPARK)))
        assertFalse(board.keys.any { HexaFluxEngine.canPlace(board, duo, it) })
        assertTrue(HexaFluxEngine.hasAnyMove(board, listOf(duo)))

        val trio = HexPiece(2, listOf(west, origin, east).map { PieceTile(it, FluxTier.SPARK) })
        assertFalse(HexaFluxEngine.hasAnyMove(board, listOf(trio)))
    }

    @Test
    fun laEficienciaDeSupervivenciaMideElTableroLibreYLaDelRestoLasJugadasSobrantes() {
        val survive = WinCondition.Survive(turns = 10, spawnEvery = 2)
        assertEquals(0.5, HexaFluxScoring.efficiency(survive, movesLeft = 0, moveLimit = 10, freeCells = 10, totalCells = 20))
        val target = WinCondition.TargetScore(100)
        assertEquals(1.0, HexaFluxScoring.efficiency(target, movesLeft = 6, moveLimit = 10, freeCells = 0, totalCells = 20))
        assertEquals(0.4, HexaFluxScoring.efficiency(target, movesLeft = 2, moveLimit = 10, freeCells = 0, totalCells = 20))
        // Un nivel superado con el peor resultado posible sigue valiendo más que el anterior perfecto.
        assertTrue(
            HexaFluxScoring.score(5, 20, Long.MAX_VALUE, 0.0, restarts = 99) >
                HexaFluxScoring.score(4, 20, 1, 1.0, restarts = 0) - HexaFluxScoring.LEVEL_POINTS,
        )
    }
}
