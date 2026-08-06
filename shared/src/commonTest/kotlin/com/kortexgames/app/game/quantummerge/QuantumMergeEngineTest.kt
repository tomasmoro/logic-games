package com.kortexgames.app.game.quantummerge

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del motor de física de Quantum Merge: **invariantes** de la simulación (contención,
 * no-solape, reposo) y reglas de fusión.
 *
 * Un motor de física no se testea comparando posiciones exactas —dependerían de cada constante de
 * tuning y el test se rompería al ajustar la gravedad—, sino comprobando las propiedades que deben
 * cumplirse *sea cual sea* el tuning: ninguna esfera sale del contenedor, ninguna pareja se queda
 * atravesada, la pila acaba quieta y una fusión produce exactamente una esfera del tier siguiente.
 *
 * El motor no lanza corrutinas para simular (la física la dirige `onFrame`), así que los tests
 * avanzan el tiempo a mano y leen el `StateFlow` de forma síncrona, igual que `StarportEngineTest`.
 */
class QuantumMergeEngineTest {

    /** Doble de audio inerte: el motor emite feedback por eventos, no llamando al manager. */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    /**
     * Banco de pruebas: un motor arrancado más un reloj de frames a 60 fps.
     *
     * Simular a 60 fps (y no al paso interno de 120 Hz) es a propósito: así se ejercita también el
     * acumulador de sub-pasos, que es donde vive la parte delicada del bucle temporal.
     */
    private class Sim(difficulty: QuantumDifficulty = QuantumDifficulty.FACIL, seed: Long = 7L) {
        val engine = QuantumMergeEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            audio = FakeAudio,
            // El motor recibe el nivel 1-based, como el resto de juegos con dificultad elegible.
            difficulty = difficulty.ordinal + 1,
            random = Random(seed),
        ).also { it.start() }

        private var nanos = 1_000_000_000L

        val state: QuantumMergeState get() = engine.state.value

        /** Avanza [seconds] segundos de juego en frames de 60 fps. */
        fun advance(seconds: Float) {
            repeat((seconds * 60f).toInt()) {
                nanos += FRAME_NANOS
                engine.onFrame(nanos)
            }
        }

        /** Espera a que el dispensador tenga esfera y la suelta en [x]. Devuelve su tier. */
        fun dropAt(x: Float): QuantumTier {
            while (state.currentDropSphere == null) advance(0.05f)
            engine.moveDropper(x)
            val tier = state.currentDropSphere!!.tier
            engine.dropSphere()
            return tier
        }

        private companion object {
            const val FRAME_NANOS = 16_666_667L
        }
    }

    // --- Invariantes de la simulación -------------------------------------------------------

    @Test
    fun unaEsferaSoltadaCaeYSeAsientaEnElSuelo() {
        val sim = Sim()
        sim.dropAt(50f)
        sim.advance(seconds = 3f)

        val sphere = sim.state.activeSpheres.single()
        assertEquals(QuantumWorld.HEIGHT - sphere.radius, sphere.y, absoluteTolerance = 0.5f)
        assertTrue(abs(sphere.vy) < 1f, "la esfera debería estar en reposo, vy=${sphere.vy}")
    }

    @Test
    fun ningunaEsferaSaleDelContenedor() {
        val sim = Sim()
        // Lanzamientos repartidos por todo el ancho, incluidos los extremos.
        repeat(24) { i ->
            sim.dropAt(if (i % 2 == 0) 2f else QuantumWorld.WIDTH - 2f)
            sim.advance(0.4f)
        }
        sim.advance(4f)

        sim.state.activeSpheres.forEach { s ->
            assertTrue(s.x >= s.radius - TOLERANCE, "se salió por la izquierda: $s")
            assertTrue(s.x <= QuantumWorld.WIDTH - s.radius + TOLERANCE, "se salió por la derecha: $s")
            assertTrue(s.y <= QuantumWorld.HEIGHT - s.radius + TOLERANCE, "atravesó el suelo: $s")
        }
    }

    @Test
    fun lasEsferasNoSeQuedanAtravesadasUnaDentroDeOtra() {
        val sim = Sim()
        repeat(20) {
            sim.dropAt(35f + (it % 5) * 8f)
            sim.advance(0.5f)
        }
        sim.advance(4f)

        val spheres = sim.state.activeSpheres
        for (i in spheres.indices) {
            for (j in i + 1 until spheres.size) {
                val penetration = spheres[i].penetrationWith(spheres[j])
                assertTrue(
                    penetration < spheres[i].radius,
                    "solape excesivo (${penetration}) entre ${spheres[i]} y ${spheres[j]}",
                )
            }
        }
    }

    @Test
    fun laPilaAcabaQuietaEnVezDeVibrarParaSiempre() {
        val sim = Sim()
        repeat(12) {
            sim.dropAt(50f)
            sim.advance(0.5f)
        }
        sim.advance(6f)

        val moving = sim.state.activeSpheres.filter { abs(it.vx) > 3f || abs(it.vy) > 3f }
        assertTrue(moving.isEmpty(), "esferas que siguen moviéndose tras asentarse: $moving")
    }

    // --- Fusión ------------------------------------------------------------------------------

    @Test
    fun dosEsferasDelMismoTierSeFusionanEnLaSiguiente() {
        val sim = Sim()

        // Se apila a la izquierda una primera esfera y se espera a que el dispensador ofrezca otra
        // de su mismo tier; las que no coinciden se descartan al extremo opuesto.
        val target = sim.dropAt(LEFT_X)
        sim.advance(1.5f)
        val before = sim.state.activeSpheres.size

        var matched = false
        repeat(30) {
            if (matched) return@repeat
            while (sim.state.currentDropSphere == null) sim.advance(0.05f)
            if (sim.state.currentDropSphere!!.tier == target) {
                sim.dropAt(LEFT_X)
                matched = true
            } else {
                sim.dropAt(RIGHT_X)
            }
            sim.advance(0.6f)
        }
        assertTrue(matched, "el dispensador nunca repitió el tier $target")
        sim.advance(2f)

        val merged = sim.state.activeSpheres.firstOrNull {
            it.tier == target.next() && abs(it.x - LEFT_X) < 20f
        }
        assertNotNull(merged, "no nació la esfera del tier siguiente: ${sim.state.activeSpheres}")
        assertTrue(sim.state.merges >= 1)
        assertTrue(sim.state.score >= target.next()!!.mergeScore)
        assertTrue(
            sim.state.bestTier.ordinal >= target.next()!!.ordinal,
            "bestTier no registró el tier alcanzado",
        )
        // Dos madres consumidas, una hija: la pila de la izquierda no crece con la fusión.
        assertTrue(sim.state.activeSpheres.count { abs(it.x - LEFT_X) < 20f } <= before)
    }

    @Test
    fun laFusionProduceTiersQueElDispensadorNoPuedeEntregar() {
        val sim = Sim(difficulty = QuantumDifficulty.FACIL)
        val spawnable = QuantumTier.SPAWN_POOL.take(3).toSet()

        // Todo al mismo carril: la torre se fusiona sola hacia arriba en la escala.
        repeat(40) {
            sim.dropAt(50f)
            sim.advance(0.45f)
        }
        sim.advance(3f)

        assertTrue(
            sim.state.bestTier !in spawnable,
            "sin fusiones encadenadas el mejor tier seguiría siendo uno de los lanzables",
        )
        assertTrue(sim.state.merges > 0)
    }

    // --- Derrota -----------------------------------------------------------------------------

    @Test
    fun elContenedorDesbordadoTerminaLaPartida() {
        val sim = Sim(difficulty = QuantumDifficulty.DIFICIL)

        // Lanzamientos alternos a izquierda y derecha para que casi nada empareje: es la forma
        // más rápida de llenar el contenedor sin tocar el estado interno del motor.
        var guard = 0
        while (sim.engine.status.value == GameStatus.RUNNING && guard < 400) {
            sim.dropAt(if (guard % 2 == 0) 12f else QuantumWorld.WIDTH - 12f)
            sim.advance(0.4f)
            guard++
        }

        assertEquals(GameStatus.FINISHED, sim.engine.status.value)
        assertNotNull(sim.engine.outcome.value)
    }

    // --- Dificultad --------------------------------------------------------------------------

    @Test
    fun elNivelViajaEnElEstadoDesdeAntesDeArrancar() {
        // La antesala marca la ficha elegida leyendo el estado, así que un motor recién construido
        // ya tiene que publicar SU nivel: si esperara a `start()`, cambiar de dificultad no se
        // vería reflejado en el selector.
        QuantumDifficulty.entries.forEach { level ->
            val engine = QuantumMergeEngine(
                scope = CoroutineScope(Dispatchers.Unconfined),
                audio = FakeAudio,
                difficulty = level.ordinal + 1,
            )
            assertEquals(level, engine.state.value.difficulty)
        }
    }

    @Test
    fun subirLaDificultadAgrandaLasEsferasYBajaElTecho() {
        val facil = Sim(difficulty = QuantumDifficulty.FACIL)
        val dificil = Sim(difficulty = QuantumDifficulty.DIFICIL)

        val radioFacil = facil.state.currentDropSphere!!.tier.radiusFor(QuantumDifficulty.FACIL.radiusScale)
        val radioDificil = facil.state.currentDropSphere!!.tier.radiusFor(QuantumDifficulty.DIFICIL.radiusScale)
        assertTrue(radioDificil > radioFacil, "las esferas no crecen con la dificultad")
        assertTrue(
            dificil.state.difficulty.stackHeight < facil.state.difficulty.stackHeight,
            "el techo no baja con la dificultad",
        )
        // Invariante que sostiene el juego: la última fusión debe seguir cabiendo incluso en el
        // nivel más duro, o el tier máximo sería inalcanzable por geometría.
        val mayor = QuantumTier.SINGULARITY.radiusFor(QuantumDifficulty.DIFICIL.radiusScale)
        assertTrue(2f * mayor < QuantumWorld.WIDTH, "dos singularidades no caben lado a lado")
    }

    @Test
    fun laMiraNuncaDejaLaEsferaFueraDeLasParedes() {
        val sim = Sim()
        sim.engine.moveDropper(-500f)
        val left = sim.state.currentDropSphere!!
        assertEquals(left.radius, left.x, absoluteTolerance = 0.001f)

        sim.engine.moveDropper(9_999f)
        val right = sim.state.currentDropSphere!!
        assertEquals(QuantumWorld.WIDTH - right.radius, right.x, absoluteTolerance = 0.001f)
    }

    private companion object {
        /** Holgura admitida en los invariantes: el solver corrige con un `slop` deliberado. */
        const val TOLERANCE = 0.5f

        const val LEFT_X = 18f
        const val RIGHT_X = 82f
    }
}
