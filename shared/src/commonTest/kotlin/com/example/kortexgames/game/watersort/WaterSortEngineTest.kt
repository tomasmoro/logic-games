package com.example.kortexgames.game.watersort

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del motor de "Ordena las Pociones" centrados en la ayuda **"tubo extra"**
 * (recompensa por anuncio): que añada un tubo vacío, respete el cupo
 * ([MAX_EXTRA_TUBES]) y que **deshacer/reiniciar conserven** el tubo obtenido, para
 * no devolverle al jugador un tablero más difícil del que ya "pagó" viendo el anuncio.
 *
 * El motor no lanza corrutinas propias, así que se prueba de forma síncrona leyendo el
 * StateFlow (mismo enfoque que [com.example.kortexgames.game.starport.StarportEngineTest]).
 */
class WaterSortEngineTest {

    /** Doble de audio inerte: el feedback no es relevante para esta lógica. */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    private fun engineAtLevel(level: Int): WaterSortEngine =
        WaterSortEngine(CoroutineScope(Dispatchers.Unconfined), FakeAudio, random = Random(seed = 1L))
            .also { it.startAtLevel(level) }

    /** Aplica el primer vertido válido que encuentre (dos toques); útil para crear historial. */
    private fun performAnyValidPour(engine: WaterSortEngine): Boolean {
        val s = engine.state.value
        for (from in s.tubes.indices) {
            for (to in s.tubes.indices) {
                if (WaterSortRules.canPour(s.tubes, from, to, s.capacity)) {
                    engine.onTubeTap(from)
                    engine.onTubeTap(to)
                    return true
                }
            }
        }
        return false
    }

    @Test
    fun anadirTuboExtraSumaUnTuboVacioYActualizaElContador() {
        val engine = engineAtLevel(1)
        val n = engine.state.value.tubes.size

        assertTrue(engine.addExtraTube())
        val s = engine.state.value
        assertEquals(n + 1, s.tubes.size)
        assertTrue(s.tubes.last().isEmpty)
        assertEquals(1, s.extraTubesUsed)
        assertFalse(s.canAddTube) // con MAX_EXTRA_TUBES = 1, tras uno ya no queda cupo
    }

    @Test
    fun anadirTuboExtraRespetaElCupoMaximo() {
        val engine = engineAtLevel(1)
        val n = engine.state.value.tubes.size

        repeat(MAX_EXTRA_TUBES) { assertTrue(engine.addExtraTube()) }

        val s = engine.state.value
        assertEquals(n + MAX_EXTRA_TUBES, s.tubes.size)
        assertEquals(MAX_EXTRA_TUBES, s.extraTubesUsed)
        assertFalse(s.canAddTube)
        // Un intento por encima del cupo no hace nada.
        assertFalse(engine.addExtraTube())
        assertEquals(n + MAX_EXTRA_TUBES, engine.state.value.tubes.size)
    }

    @Test
    fun deshacerConservaElTuboExtraAunSiElVertidoFuePrevio() {
        val engine = engineAtLevel(1)
        val n = engine.state.value.tubes.size

        // Vertido ANTES de la ayuda → el snapshot de deshacer nace con n tubos.
        assertTrue(performAnyValidPour(engine))
        assertTrue(engine.state.value.canUndo)

        assertTrue(engine.addExtraTube())
        assertEquals(n + 1, engine.state.value.tubes.size)

        // Al deshacer, el tablero restaurado sigue teniendo el tubo extra (el motor
        // hace crecer todos los snapshots del historial), y el contador se mantiene.
        engine.undo()
        assertEquals(n + 1, engine.state.value.tubes.size)
        assertEquals(1, engine.state.value.extraTubesUsed)
    }

    @Test
    fun reiniciarConservaElTuboExtra() {
        val engine = engineAtLevel(1)
        val n = engine.state.value.tubes.size
        assertTrue(engine.addExtraTube())

        engine.restart()
        val s = engine.state.value
        assertEquals(n + 1, s.tubes.size)
        assertEquals(1, s.extraTubesUsed)
        assertEquals(0, s.moves)
    }
}
