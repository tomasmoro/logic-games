package com.example.kortexgames.game.starport

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del motor de "Neon Starport Escape": clamping del arrastre al
 * intervalo libre, snap al soltar, conteo de movimientos, detección de escape
 * y validez del catálogo de niveles.
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

    // --- Catálogo de niveles --------------------------------------------------

    @Test
    fun todoElCatalogoConstruyeYCiclaMasAllaDelUltimoNivel() {
        // Instanciar cada nivel dispara las validaciones del init de
        // StarportLevel (solapes, límites, VIP única y alineada).
        for (n in 1..StarportLevels.count) {
            val level = StarportLevels.forNumber(n)
            assertEquals(n, level.number)
            assertTrue(level.optimalMoves > 0, "nivel $n sin óptimo")
        }
        // Más allá del catálogo se recicla la disposición pero se conserva el
        // número real (score/récord crecen con el nivel jugado).
        val wrapped = StarportLevels.forNumber(StarportLevels.count + 1)
        assertEquals(StarportLevels.count + 1, wrapped.number)
        assertEquals(StarportLevels.forNumber(1).ships, wrapped.ships)
    }

    @Test
    fun laDificultadDelCatalogoEsMonotonaCreciente() {
        val optima = (1..StarportLevels.count).map { StarportLevels.forNumber(it).optimalMoves }
        assertEquals(optima, optima.sorted(), "los óptimos deben crecer con el nivel")
    }

    // --- Clamping del arrastre ------------------------------------------------

    @Test
    fun elArrastreSeClampaContraElObstaculo() {
        // Nivel 1: VIP en (2,0)-(2,1); carguero vertical en col 4 filas 2-3.
        // La VIP solo puede avanzar hasta col 2 (su morro quedaría en col 3).
        val engine = engineAtLevel(1)
        val vip = engine.state.value.vipShip!!

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, deltaCells = 10f)

        assertEquals(2f, engine.state.value.drag?.axisOffset)
    }

    @Test
    fun elArrastreSeClampaContraElMamparo() {
        val engine = engineAtLevel(1)
        val vip = engine.state.value.vipShip!!

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, deltaCells = -10f) // hacia el muro izquierdo

        assertEquals(0f, engine.state.value.drag?.axisOffset)
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

    @Test
    fun alSoltarSeRedondeaALaCeldaMasCercana() {
        val engine = engineAtLevel(1)
        val vip = engine.state.value.vipShip!!

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, 1.4f)
        engine.onShipReleased(vip.id)

        val moved = engine.state.value.shipById(vip.id)!!
        assertEquals(1, moved.axisPosition)
        assertEquals(1, engine.state.value.moves)
        assertEquals(null, engine.state.value.drag)
    }

    @Test
    fun soltarEnLaMismaCeldaNoCuentaComoMovimiento() {
        val engine = engineAtLevel(1)
        val vip = engine.state.value.vipShip!!

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, 0.3f) // menos de media celda
        engine.onShipReleased(vip.id)

        assertEquals(0, engine.state.value.shipById(vip.id)!!.axisPosition)
        assertEquals(0, engine.state.value.moves)
    }

    // --- Invariante de no-superposición ----------------------------------------

    @Test
    fun ningunaSecuenciaDeArrastresProduceSolapes() {
        // Fuerza arrastres extremos de todas las naves del nivel más denso:
        // tras cada release, las celdas ocupadas deben seguir siendo únicas.
        val engine = engineAtLevel(10)
        repeat(3) { round ->
            for (ship in engine.state.value.ships) {
                engine.onShipLifted(ship.id)
                engine.onShipDragged(ship.id, if (round % 2 == 0) 10f else -10f)
                engine.onShipReleased(ship.id)

                val cells = engine.state.value.ships.flatMap { it.occupiedCells }
                assertEquals(cells.size, cells.toSet().size, "solape tras mover ${ship.id}")
                assertTrue(cells.all { it.isInsideHangar }, "nave fuera del hangar")
            }
        }
    }

    // --- Victoria ---------------------------------------------------------------

    @Test
    fun laVipEscapaAlQuedarPegadaALaEsclusaYLaPartidaTerminaTrasLaAnimacion() {
        // Solución óptima del nivel 1: bajar el bloqueador y sacar la VIP.
        val engine = engineAtLevel(1)
        val blocker = engine.state.value.ships.first { !it.isVip }
        val vip = engine.state.value.vipShip!!

        engine.onShipLifted(blocker.id)
        engine.onShipDragged(blocker.id, 10f) // al fondo del hangar
        engine.onShipReleased(blocker.id)
        assertFalse(engine.state.value.vipEscaped)

        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, 10f) // vía libre hasta la esclusa
        engine.onShipReleased(vip.id)

        assertTrue(engine.state.value.vipEscaped)
        assertEquals(2, engine.state.value.moves)
        // La partida NO termina hasta que la UI confirma la animación de salida.
        assertEquals(GameStatus.RUNNING, engine.status.value)

        engine.onExitAnimFinished()
        assertEquals(GameStatus.FINISHED, engine.status.value)

        val result = assertNotNull(engine.outcome.value)
        assertEquals(1, result.reachedMetric) // récord = nivel completado
        assertEquals(100.0, result.accuracyPercentage) // 2 movimientos = óptimo
        assertEquals(1_000, result.score) // sin penalización por extras
    }

    @Test
    fun losMovimientosExtraPenalizanScoreYPrecision() {
        val engine = engineAtLevel(1)
        val blocker = engine.state.value.ships.first { !it.isVip }
        val vip = engine.state.value.vipShip!!

        // Movimiento inútil (ida) + vuelta: 2 extras sobre el óptimo de 2.
        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, 1f)
        engine.onShipReleased(vip.id)
        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, -1f)
        engine.onShipReleased(vip.id)

        engine.onShipLifted(blocker.id)
        engine.onShipDragged(blocker.id, 10f)
        engine.onShipReleased(blocker.id)
        engine.onShipLifted(vip.id)
        engine.onShipDragged(vip.id, 10f)
        engine.onShipReleased(vip.id)
        engine.onExitAnimFinished()

        val result = assertNotNull(engine.outcome.value)
        assertEquals(4, engine.state.value.moves)
        assertEquals(50.0, result.accuracyPercentage) // 2 óptimos / 4 reales
        assertEquals(1_000 - 2 * 50, result.score)
    }
}
