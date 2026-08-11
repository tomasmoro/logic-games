package com.kortexgames.app.game.neonpulse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests de la **rampa de dificultad** de Neon Pulse ([WaveSpec.forWave]).
 *
 * Es la única pieza del juego que decide si una partida infinita se siente justa:
 * el motor solo aplica lo que aquí se calcula. Por eso se prueba la forma de la
 * curva (monótona, sin saltos y con tope) y no valores mágicos horda a horda —así
 * los tests sobreviven a un reajuste de balance y siguen protegiendo lo importante:
 * que la dificultad suba **paulatinamente** y no se pase de "difícil" a "imposible".
 *
 * El ViewModel no se testea aquí: conduce un bucle en `viewModelScope` y el
 * proyecto no tiene `coroutines-test` en el catálogo de versiones.
 */
class WaveSpecTest {

    /** Hordas suficientes para pasarse de largo todos los topes de la rampa. */
    private val waves = (1..40).map { WaveSpec.forWave(it) }

    @Test
    fun primeraHordaEsLaMasSuave() {
        val first = waves.first()
        assertEquals(NeonPulseConfig.WAVE_BASE_NODES, first.nodeCount)
        assertEquals(NeonPulseConfig.SPAWN_INTERVAL_START_MS, first.spawnIntervalMs)
        assertEquals(NeonPulseConfig.NODE_LIFE_START_MS, first.nodeLifeMs)
        assertEquals(0f, first.trapChance, "sin trampas en la horda 1")
        assertEquals(0f, first.speed, "los nodos arrancan estáticos")
    }

    @Test
    fun laRampaEsMonotonaYNuncaSeRelaja() {
        waves.zipWithNext { prev, next ->
            assertTrue(next.nodeCount >= prev.nodeCount, "horda ${next.wave}: menos nodos que la anterior")
            assertTrue(next.spawnIntervalMs <= prev.spawnIntervalMs, "horda ${next.wave}: cadencia más lenta")
            assertTrue(next.nodeLifeMs <= prev.nodeLifeMs, "horda ${next.wave}: nodos más duraderos")
            assertTrue(next.trapChance >= prev.trapChance, "horda ${next.wave}: menos trampas")
            assertTrue(next.speed >= prev.speed, "horda ${next.wave}: nodos más lentos")
        }
    }

    @Test
    fun laRampaSubeEnPasosPequenos() {
        // El salto entre hordas consecutivas nunca supera un paso de la rampa: es lo
        // que garantiza que la subida se sienta "paulatina" y no como un muro.
        waves.zipWithNext { prev, next ->
            assertTrue(
                prev.spawnIntervalMs - next.spawnIntervalMs <= NeonPulseConfig.SPAWN_INTERVAL_STEP_MS,
                "horda ${next.wave}: la cadencia dio un salto mayor que un paso",
            )
            assertTrue(
                prev.nodeLifeMs - next.nodeLifeMs <= NeonPulseConfig.NODE_LIFE_STEP_MS,
                "horda ${next.wave}: la vida de los nodos dio un salto mayor que un paso",
            )
        }
    }

    @Test
    fun laDificultadSeEstabilizaEnSusTopes() {
        val last = waves.last()
        assertEquals(NeonPulseConfig.WAVE_MAX_NODES, last.nodeCount)
        assertEquals(NeonPulseConfig.SPAWN_INTERVAL_MIN_MS, last.spawnIntervalMs)
        assertEquals(NeonPulseConfig.NODE_LIFE_MIN_MS, last.nodeLifeMs)
        assertEquals(NeonPulseConfig.TRAP_CHANCE_MAX, last.trapChance)
        assertEquals(NeonPulseConfig.MOVE_SPEED_MAX, last.speed)
    }

    @Test
    fun sigueSiendoJugableEnElPeorCaso() {
        // "Difícil pero no imposible": aun en el tope, un nodo vive más de lo que
        // tarda en aparecer el siguiente, así que nunca se acumulan objetivos
        // imposibles de atender, y su vida deja margen humano de reacción.
        val last = waves.last()
        assertTrue(
            last.nodeLifeMs > last.spawnIntervalMs,
            "en el tope los nodos expiran antes de que llegue el siguiente",
        )
        assertTrue(last.nodeLifeMs >= 500L, "medio segundo es el mínimo razonable de reacción")
        assertTrue(last.trapChance <= 0.5f, "la mayoría de los nodos deben seguir siendo objetivos")
    }

    @Test
    fun lasMecanicasSeDesbloqueanEnSuHorda() {
        assertEquals(0f, WaveSpec.forWave(NeonPulseConfig.TRAP_UNLOCK_WAVE - 1).trapChance)
        assertTrue(WaveSpec.forWave(NeonPulseConfig.TRAP_UNLOCK_WAVE).trapChance > 0f)

        assertEquals(0f, WaveSpec.forWave(NeonPulseConfig.MOVE_UNLOCK_WAVE - 1).speed)
        assertTrue(WaveSpec.forWave(NeonPulseConfig.MOVE_UNLOCK_WAVE).speed > 0f)
    }

    @Test
    fun elCorazonSeOfreceCadaCincoHordas() {
        val offering = waves.filter { it.offersHeart }.map { it.wave }
        assertEquals((1..40).filter { it % NeonPulseConfig.HEART_EVERY_WAVES == 0 }, offering)
    }

    @Test
    fun elCorazonDaMasMargenQueUnNodo() {
        // Es un rescate: perderlo por milisegundos se sentiría injusto.
        assertTrue(NeonPulseConfig.HEART_LIFE_MS > NeonPulseConfig.NODE_LIFE_START_MS)
    }
}
