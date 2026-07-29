package com.kortexgames.app.game.starport

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del motor de "Neon Starport Escape" y de su generador procedural de
 * niveles: clamping del arrastre al intervalo libre, snap al soltar, conteo de
 * movimientos, detección de escape y las garantías de la progresión (esclusa
 * rotatoria, hangar creciente, salida bloqueada, resolubilidad por solver).
 *
 * Los niveles ahora se GENERAN (semilla = número de nivel), así que los tests
 * no asumen ningún tablero concreto: derivan sus expectativas del propio nivel
 * (vía [freeAxisRange]) o juegan la solución que devuelve el [StarportSolver].
 *
 * Se testea a través de la API pública del motor (lift/drag/release) leyendo
 * el StateFlow de forma síncrona: el motor no lanza corrutinas, así que no
 * hace falta `coroutines-test` (que no está en el catálogo de versiones).
 */
class StarportEngineTest {

    /** Doble de audio inerte: el motor no lo usa (feedback vía eventos). */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    private fun engineAtLevel(level: Int): StarportEngine =
        StarportEngine(CoroutineScope(Dispatchers.Unconfined), FakeAudio)
            .also { it.startAtLevel(level) }

    // --- Generador de niveles ---------------------------------------------------

    @Test
    fun losNivelesGeneradosSonValidosYSuOptimoEsElDelSolver() {
        for (n in 1..30) {
            // Instanciar dispara las validaciones del init de StarportLevel
            // (solapes, límites, huellas permitidas, VIP única y alineada).
            val level = StarportLevels.forNumber(n)
            assertEquals(n, level.number)
            // El solver confirma resolubilidad y que el óptimo declarado es exacto.
            val solution = StarportSolver.solve(level.hangarSize, level.exit, level.ships)
            assertNotNull(solution, "nivel $n irresoluble")
            assertTrue(solution.isNotEmpty(), "nivel $n nace con la VIP ya libre")
            assertEquals(level.optimalMoves, solution.size, "óptimo declarado ≠ BFS en nivel $n")
        }
    }

    @Test
    fun laEsclusaCambiaDeBordeEnCadaNivel() {
        for (n in 1..16) {
            assertNotEquals(
                StarportLevels.forNumber(n).exit.side,
                StarportLevels.forNumber(n + 1).exit.side,
                "la esclusa no cambió entre los niveles $n y ${n + 1}",
            )
        }
    }

    @Test
    fun elHangarCreceCadaSieteNivelesHastaDiez() {
        assertEquals(6, StarportLevels.forNumber(1).hangarSize)
        assertEquals(6, StarportLevels.forNumber(7).hangarSize)
        assertEquals(7, StarportLevels.forNumber(8).hangarSize)
        assertEquals(8, StarportLevels.forNumber(15).hangarSize)
        assertEquals(9, StarportLevels.forNumber(22).hangarSize)
        assertEquals(10, StarportLevels.forNumber(29).hangarSize)
        // Techo: más allá del nivel 29 el hangar ya no crece.
        assertEquals(10, StarportLevels.forNumber(60).hangarSize)
    }

    @Test
    fun laVipApuntaALaEsclusaYSuCaminoNaceBloqueado() {
        for (n in 1..20) {
            val level = StarportLevels.forNumber(n)
            val vip = level.ships.first { it.isVip }
            // Alineación: mismo eje y mismo carril que la esclusa (la UI ya
            // rota el sprite hacia el lado de salida a partir de esto).
            assertEquals(level.exit.requiredOrientation, vip.orientation, "nivel $n")
            assertEquals(level.exit.index, vip.lanePosition, "nivel $n")
            // Bloqueo: en el estado inicial la VIP NO puede llegar a la esclusa.
            val range = freeAxisRange(vip, level.ships, level.hangarSize)
            val flushAxis = when (level.exit.side) {
                ExitSide.LEFT, ExitSide.TOP -> 0
                ExitSide.RIGHT, ExitSide.BOTTOM -> level.hangarSize - vip.length
            }
            assertFalse(flushAxis in range, "nivel $n nace con la salida libre")
        }
    }

    @Test
    fun losObstaculosCrecenDentroDeCadaTramoDeSieteNiveles() {
        for (band in 0..2) {
            val primero = StarportLevels.forNumber(band * 7 + 1).ships.size
            val ultimo = StarportLevels.forNumber(band * 7 + 7).ships.size
            assertTrue(
                ultimo > primero,
                "tramo $band: el nivel final ($ultimo naves) no supera al inicial ($primero)",
            )
        }
    }

    @Test
    fun losMeteoritosGrandesEntranEnLaMezclaConElProgreso() {
        val piezas = (1..30).flatMap { StarportLevels.forNumber(it).ships }
        assertTrue(piezas.any { it.length == SHIP_LENGTH_XLONG }, "nunca aparece un 4×1")
        assertTrue(piezas.any { it.width == 2 }, "nunca aparece un meteorito ancho")
        // Y los primeros niveles conservan solo las piezas clásicas del tutorial.
        val tempranas = (1..4).flatMap { StarportLevels.forNumber(it).ships }
        assertTrue(tempranas.all { it.width == 1 && it.length <= SHIP_LENGTH_LONG })
    }

    // --- Clamping del arrastre ------------------------------------------------

    @Test
    fun elArrastreSeClampaAlIntervaloLibreEnAmbosSentidos() {
        val engine = engineAtLevel(1)
        val s = engine.state.value
        val vip = s.vipShip!!
        val range = freeAxisRange(vip, s.ships, s.hangarSize)

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, deltaCells = 50f) // empuja contra el bloqueo
        assertEquals((range.last - vip.axisPosition).toFloat(), engine.state.value.drag?.axisOffset)

        engine.onShipDragged(vip.id, deltaCells = -100f) // empuja contra el mamparo
        assertEquals((range.first - vip.axisPosition).toFloat(), engine.state.value.drag?.axisOffset)
    }

    @Test
    fun noSePuedeArrastrarSinLevantarNiLevantarDosNavesALaVez() {
        val engine = engineAtLevel(1)
        val vip = engine.state.value.vipShip!!
        val other = engine.state.value.ships.first { !it.isVip }

        engine.onShipDragged(vip.id, 1f) // sin lift previo: ignorado
        assertEquals(null, engine.state.value.drag)

        engine.onShipLifted(vip.id)
        engine.onShipLifted(other.id) // segundo dedo: ignorado
        assertEquals(vip.id, engine.state.value.drag?.shipId)
    }

    // --- Snap y conteo de movimientos ------------------------------------------

    /**
     * Una pieza NO VIP con hueco libre en algún sentido, y el signo de ese
     * hueco. Excluye la VIP para que un +1 accidental no dispare el escape.
     */
    private fun movableObstacle(s: StarportGameState): Pair<Ship, Int> {
        for (ship in s.ships) {
            if (ship.isVip) continue
            val range = freeAxisRange(ship, s.ships, s.hangarSize)
            if (range.last > ship.axisPosition) return ship to +1
            if (range.first < ship.axisPosition) return ship to -1
        }
        error("el nivel no tiene ningún obstáculo móvil (¿tablero irresoluble?)")
    }

    @Test
    fun alSoltarSeRedondeaALaCeldaMasCercana() {
        val engine = engineAtLevel(1)
        val (ship, sign) = movableObstacle(engine.state.value)

        engine.onShipLifted(ship.id)
        engine.onShipDragged(ship.id, 1.4f * sign) // se redondea a 1 celda
        engine.onShipReleased(ship.id)

        val moved = engine.state.value.shipById(ship.id)!!
        assertEquals(ship.axisPosition + sign, moved.axisPosition)
        assertEquals(1, engine.state.value.moves)
        assertEquals(null, engine.state.value.drag)
    }

    @Test
    fun soltarEnLaMismaCeldaNoCuentaComoMovimiento() {
        val engine = engineAtLevel(1)
        val (ship, sign) = movableObstacle(engine.state.value)

        engine.onShipLifted(ship.id)
        engine.onShipDragged(ship.id, 0.3f * sign) // menos de media celda
        engine.onShipReleased(ship.id)

        assertEquals(ship.axisPosition, engine.state.value.shipById(ship.id)!!.axisPosition)
        assertEquals(0, engine.state.value.moves)
    }

    // --- Invariante de no-superposición ----------------------------------------

    @Test
    fun ningunaSecuenciaDeArrastresProduceSolapes() {
        // Fuerza arrastres extremos de todas las naves en un nivel avanzado
        // (hangar grande CON meteoritos anchos): tras cada release, las celdas
        // ocupadas deben seguir siendo únicas y dentro del hangar.
        val engine = engineAtLevel(25)
        val hangarSize = engine.state.value.hangarSize
        repeat(3) { round ->
            for (ship in engine.state.value.ships) {
                engine.onShipLifted(ship.id)
                engine.onShipDragged(ship.id, if (round % 2 == 0) 20f else -20f)
                engine.onShipReleased(ship.id)

                val cells = engine.state.value.ships.flatMap { it.occupiedCells }
                assertEquals(cells.size, cells.toSet().size, "solape tras mover ${ship.id}")
                assertTrue(cells.all { it.isInside(hangarSize) }, "nave fuera del hangar")
            }
        }
    }

    // --- Victoria ---------------------------------------------------------------

    /** Ejecuta sobre el motor la solución óptima calculada por el solver. */
    private fun playOptimalSolution(engine: StarportEngine, level: StarportLevel) {
        val solution = StarportSolver.solve(level.hangarSize, level.exit, level.ships)!!
        for (move in solution) {
            val current = engine.state.value.shipById(move.shipId)!!
            engine.onShipLifted(move.shipId)
            engine.onShipDragged(move.shipId, (move.toAxis - current.axisPosition).toFloat())
            engine.onShipReleased(move.shipId)
        }
    }

    @Test
    fun jugarLaSolucionDelSolverEscapaLaVipYPuntuaAlCien() {
        val level = StarportLevels.forNumber(1)
        val engine = engineAtLevel(1)

        playOptimalSolution(engine, level)

        assertTrue(engine.state.value.vipEscaped)
        assertEquals(level.optimalMoves, engine.state.value.moves)
        // La partida NO termina hasta que la UI confirma la animación de salida.
        assertEquals(GameStatus.RUNNING, engine.status.value)

        engine.onExitAnimFinished()
        assertEquals(GameStatus.FINISHED, engine.status.value)

        val result = assertNotNull(engine.outcome.value)
        assertEquals(1, result.reachedMetric) // récord = nivel completado
        assertEquals(100.0, result.accuracyPercentage) // jugado al óptimo
        assertEquals(1_000, result.score) // sin penalización por extras
    }

    @Test
    fun losMovimientosExtraPenalizanScoreYPrecision() {
        val level = StarportLevels.forNumber(1)
        val engine = engineAtLevel(1)
        val (ship, sign) = movableObstacle(engine.state.value)

        // Ida y vuelta inútiles: 2 movimientos extra que dejan el tablero
        // idéntico, así la solución óptima sigue siendo aplicable después.
        engine.onShipLifted(ship.id)
        engine.onShipDragged(ship.id, 1f * sign)
        engine.onShipReleased(ship.id)
        engine.onShipLifted(ship.id)
        engine.onShipDragged(ship.id, -1f * sign)
        engine.onShipReleased(ship.id)

        playOptimalSolution(engine, level)
        engine.onExitAnimFinished()

        val result = assertNotNull(engine.outcome.value)
        assertEquals(level.optimalMoves + 2, engine.state.value.moves)
        assertEquals(
            level.optimalMoves.toDouble() / (level.optimalMoves + 2) * 100,
            result.accuracyPercentage,
        )
        assertEquals(1_000 - 2 * 50, result.score)
    }
}
