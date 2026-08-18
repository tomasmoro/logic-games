package com.kortexgames.app.game.watersort

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del motor de "Ordena las Pociones" centrados en la ayuda **"tubo extra"**
 * (recompensa por anuncio): que añada un tubo vacío, respete el cupo
 * ([MAX_EXTRA_TUBES]) y que **deshacer/reiniciar conserven** el tubo obtenido, para
 * no devolverle al jugador un tablero más difícil del que ya "pagó" viendo el anuncio.
 *
 * El motor no lanza corrutinas propias, así que se prueba de forma síncrona leyendo el
 * StateFlow (mismo enfoque que [com.kortexgames.app.game.starport.StarportEngineTest]).
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
    fun elTableroSeMantieneIgualDentroDeUnTramoYCreceAlCambiarDeTramo() {
        // Tramo de 3 niveles (ver LEVELS_PER_TIER): niveles 1-3 comparten tablero
        // (mismo nº de tubos/capacidad); el nivel 4 ya pertenece al siguiente tramo y
        // debe crecer. Así la progresión no depende SOLO de agrandar el tablero en
        // cada nivel, como pidió el usuario.
        val sizesInFirstTier = (1..3).map { level ->
            val s = engineAtLevel(level).state.value
            s.tubes.size to s.capacity
        }
        assertEquals(1, sizesInFirstTier.toSet().size, "Los niveles 1-3 deberían compartir tablero")

        val fourthLevelSize = engineAtLevel(4).state.value.tubes.size
        assertTrue(
            fourthLevelSize > sizesInFirstTier.first().first,
            "El nivel 4 (nuevo tramo) debería tener más tubos que el tramo anterior",
        )
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

    /**
     * [WaterSortEngine.captureSavedState] es lo que persiste [WaterSortViewModel] al
     * salir (ver `requestExit`); debe sobrevivir un viaje de ida y vuelta por JSON
     * como cualquier guardado (mismo criterio que `HyperCubeModelTest`).
     */
    @Test
    fun elGuardadoSobreviveAlViajeDeIdaYVuelta() {
        val engine = engineAtLevel(2)
        performAnyValidPour(engine)
        assertTrue(engine.addExtraTube())

        val saved = engine.captureSavedState()
        val restored = Json.decodeFromString<WaterSortSavedState>(Json.encodeToString(saved))

        assertEquals(saved, restored)
        // El disparador de animación de un vertido no tiene sentido reanudado.
        assertNull(restored.game.lastPour)
    }

    /**
     * Reanudar debe dejar el tablero, el marcador y las ayudas ya usadas EXACTAMENTE
     * como estaban al guardar: es lo que hace que "Continuar" en la antesala no le
     * cueste nada al jugador frente a no haber salido nunca.
     */
    @Test
    fun reanudarRestauraElTableroYElMarcadorGuardados() {
        val engine = engineAtLevel(2)
        performAnyValidPour(engine)
        assertTrue(engine.addExtraTube())
        val saved = engine.captureSavedState()

        val resumed = WaterSortEngine(CoroutineScope(Dispatchers.Unconfined), FakeAudio, random = Random(seed = 2L))
        resumed.resumeFrom(saved)

        val s = resumed.state.value
        assertEquals(saved.game.tubes, s.tubes)
        assertEquals(saved.game.moves, s.moves)
        assertEquals(saved.game.extraTubesUsed, s.extraTubesUsed)
        assertEquals(saved.game.canAddTube, s.canAddTube)
    }

    /**
     * Sin restaurar el historial (pila de deshacer), "Deshacer" se quedaría mudo justo
     * tras reanudar aunque [com.kortexgames.app.game.watersort.WaterSortState.canUndo]
     * siguiera en true — el jugador vería el botón activo sin que hiciera nada.
     */
    @Test
    fun reanudarConservaElHistorialParaQueDeshacerSigaFuncionando() {
        val engine = engineAtLevel(2)
        val tuboAntesDelVertido = engine.state.value.tubes
        performAnyValidPour(engine)
        val saved = engine.captureSavedState()

        val resumed = WaterSortEngine(CoroutineScope(Dispatchers.Unconfined), FakeAudio, random = Random(seed = 2L))
        resumed.resumeFrom(saved)

        assertTrue(resumed.state.value.canUndo)
        resumed.undo()
        assertEquals(tuboAntesDelVertido, resumed.state.value.tubes)
    }

    /**
     * Sin restaurar el tablero inicial, "Reiniciar" tras reanudar dejaría el motor
     * sin tubos (el `initialTubes` por defecto está vacío) en vez de volver al
     * intento tal como empezó, con el tubo extra ya concedido incluido.
     */
    @Test
    fun reanudarConservaElTableroInicialParaQueReiniciarSigaFuncionando() {
        val engine = engineAtLevel(2)
        performAnyValidPour(engine)
        assertTrue(engine.addExtraTube())
        val saved = engine.captureSavedState()

        val resumed = WaterSortEngine(CoroutineScope(Dispatchers.Unconfined), FakeAudio, random = Random(seed = 2L))
        resumed.resumeFrom(saved)

        resumed.restart()
        val s = resumed.state.value
        assertEquals(saved.initialTubes, s.tubes)
        assertEquals(1, s.extraTubesUsed) // el tubo extra ya "pagado" no se pierde
        assertEquals(0, s.moves)
    }
}
