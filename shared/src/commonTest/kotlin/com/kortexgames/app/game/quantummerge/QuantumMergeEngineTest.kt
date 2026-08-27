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
    private class Sim(difficulty: QuantumDifficulty = QuantumDifficulty.PEQUENO, seed: Long = 7L) {
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

        /**
         * Espera a que el dispensador tenga esfera y la suelta en [x]. Devuelve su tier, o `null`
         * si el motor deja de avanzar mientras se espera —partida terminada o congelada por la
         * oferta de revivir ([QuantumMergeState.awaitingRevive])— porque entonces ninguna esfera
         * nueva va a aparecer jamás.
         *
         * Sin esta salida, un desbordamiento que ocurriera durante uno de estos micro-`advance`
         * (puede pasar en CUALQUIER sub-paso de física, no solo entre lanzamientos) colgaría el
         * test para siempre: `onFrame` deja de avanzar en cuanto `status` o `awaitingRevive`
         * cambian, así que `currentDropSphere` nunca volvería a dejar de ser `null`. El `guard` es
         * el último resguardo por si algún día hay una regresión real en el dispensador.
         */
        fun dropAt(x: Float): QuantumTier? {
            var guard = 0
            while (state.currentDropSphere == null) {
                if (engine.status.value != GameStatus.RUNNING || state.awaitingRevive) return null
                advance(0.05f)
                check(guard++ < 4_000) { "dropAt: el dispensador nunca recargó una esfera nueva" }
            }
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
        assertNotNull(target, "la partida no debería terminar en el primer lanzamiento")
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
        val sim = Sim(difficulty = QuantumDifficulty.PEQUENO)
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

    /**
     * Llena el contenedor hasta el primer desbordamiento. Devuelve tras `advance` una vez que
     * [QuantumMergeState.awaitingRevive] se activa (o si el `guard` se agota antes, en cuyo caso
     * las aserciones del llamador fallarán con un mensaje claro).
     */
    private fun Sim.fillUntilFirstOverflow(guardLimit: Int = 400) {
        var guard = 0
        while (!state.awaitingRevive && guard < guardLimit) {
            // `dropAt` puede devolver `null` si el desbordamiento llegó DURANTE su propia espera
            // (ver su KDoc): en ese caso ya no hay nada más que hacer aquí, se sale sin el
            // `advance` final.
            if (dropAt(if (guard % 2 == 0) 12f else QuantumWorld.WIDTH - 12f) == null) break
            advance(0.4f)
            guard++
        }
    }

    @Test
    fun elPrimerDesbordamientoOfreceRevivirEnVezDeTerminar() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        sim.fillUntilFirstOverflow()

        assertTrue(sim.state.awaitingRevive, "el primer desbordamiento debería ofrecer revivir")
        // La partida no ha terminado de verdad: sigue en RUNNING y sin `outcome`.
        assertEquals(GameStatus.RUNNING, sim.engine.status.value)
        assertEquals(null, sim.engine.outcome.value)
    }

    @Test
    fun laFisicaQuedaCongeladaMientrasSeOfreceRevivir() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        sim.fillUntilFirstOverflow()
        assertTrue(sim.state.awaitingRevive)

        val before = sim.state.activeSpheres
        sim.advance(2f)
        assertEquals(before, sim.state.activeSpheres, "el tablero no debería cambiar con la oferta en pantalla")
    }

    @Test
    fun aceptarRevivirDisparaElLaserYReanudaLaPartida() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        sim.fillUntilFirstOverflow()
        assertTrue(sim.state.awaitingRevive)

        sim.engine.reviveWithLaser()

        assertTrue(!sim.state.awaitingRevive, "la oferta debería cerrarse al aceptar")
        assertEquals(GameStatus.RUNNING, sim.engine.status.value)
        assertEquals(0f, sim.state.dangerProgress)
        assertTrue(
            sim.state.activeSpheres.none { it.tier in QuantumTier.LASER_TARGETS },
            "el láser debería haber eliminado toda esfera de un tier objetivo",
        )
        // La física vuelve a avanzar tras aceptar.
        val before = sim.state.activeSpheres
        sim.advance(1f)
        assertTrue(before != sim.state.activeSpheres || sim.state.activeSpheres.isEmpty())
    }

    @Test
    fun rechazarRevivirTerminaLaPartidaDeVerdad() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        sim.fillUntilFirstOverflow()
        assertTrue(sim.state.awaitingRevive)

        sim.engine.declineRevive()

        assertEquals(GameStatus.FINISHED, sim.engine.status.value)
        assertNotNull(sim.engine.outcome.value)
    }

    @Test
    fun unSegundoDesbordamientoYaNoOfreceRevivir() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        sim.fillUntilFirstOverflow()
        assertTrue(sim.state.awaitingRevive)
        sim.engine.reviveWithLaser()

        // Vuelve a llenar el contenedor tras el respiro del láser; esta vez debe terminar.
        var guard = 0
        while (sim.engine.status.value == GameStatus.RUNNING && guard < 400) {
            if (sim.dropAt(if (guard % 2 == 0) 12f else QuantumWorld.WIDTH - 12f) == null) break
            sim.advance(0.4f)
            guard++
        }

        assertEquals(GameStatus.FINISHED, sim.engine.status.value)
        assertNotNull(sim.engine.outcome.value)
    }

    @Test
    fun elLaserDelHudEliminaTodasLasEsferasDeLosTiersMasPequenos() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)
        // El dispensador solo entrega QUARK..PROTON (ver [QuantumTier.SPAWN_POOL]) y los cuatro
        // más pequeños son objetivo del láser ([QuantumTier.LASER_TARGETS]): unos pocos
        // lanzamientos ya bastan para tener algo que eliminar, sin necesidad de acercarse al
        // desbordamiento. Esto es justo lo que pide "el láser debería estar siempre disponible".
        repeat(6) {
            sim.dropAt(if (it % 2 == 0) 20f else QuantumWorld.WIDTH - 20f)
            sim.advance(0.5f)
        }
        assertTrue(
            sim.state.activeSpheres.any { it.tier in QuantumTier.LASER_TARGETS },
            "el montaje del test no dejó ninguna esfera objetivo en el tablero",
        )

        val fired = sim.engine.fireLaser()

        assertTrue(fired, "el láser debería haber eliminado al menos una esfera")
        assertTrue(
            sim.state.activeSpheres.none { it.tier in QuantumTier.LASER_TARGETS },
            "quedó una esfera de un tier objetivo sin eliminar",
        )
        assertEquals(GameStatus.RUNNING, sim.engine.status.value)
    }

    @Test
    fun cadaDisparoDeLaserPenalizaElPuntajeFinal() {
        val sim = Sim(difficulty = QuantumDifficulty.GRANDE)

        // Dispara el láser del HUD en cuanto haya algo que eliminar (sin esperar a la oferta de
        // revivir, para no consumirla: [declineRevive] solo termina la partida si la oferta sigue
        // en pie).
        repeat(6) {
            sim.dropAt(if (it % 2 == 0) 20f else QuantumWorld.WIDTH - 20f)
            sim.advance(0.5f)
        }
        val scoreBeforeLaser = sim.state.score
        assertTrue(sim.engine.fireLaser())
        // El marcador publicado ya viene descontado: se nota en caliente, no solo en el cartel.
        assertTrue(
            sim.state.score <= scoreBeforeLaser,
            "usar el láser no debería subir el marcador mostrado",
        )

        // Sigue jugando hasta el desbordamiento real y rechaza la segunda oportunidad para forzar
        // el fin de partida.
        sim.fillUntilFirstOverflow()
        assertTrue(sim.state.awaitingRevive)
        sim.engine.declineRevive()

        val outcome = sim.engine.outcome.value
        assertNotNull(outcome)
        assertEquals(sim.state.score, outcome.score)
    }

    // --- Dificultad --------------------------------------------------------------------------

    @Test
    fun elEscalonInicialEsGrandeYElUltimoEnAbrirsePequeno() {
        // El orden del `enum` es la fuente de verdad de qué se abre primero (ver KDoc de
        // [QuantumDifficulty]): decisión de producto de arrancar en el escalón más exigente y
        // premiar con más margen (Mediano, luego Pequeño) conforme se demuestra dominio.
        assertEquals(QuantumDifficulty.GRANDE, QuantumDifficulty.entries.first())
        assertEquals(QuantumDifficulty.PEQUENO, QuantumDifficulty.entries.last())
        assertEquals(0, QuantumDifficulty.GRANDE.ordinal, "difficultyLevel 1 = ordinal 0 = Grande")
        assertEquals(QuantumDifficulty.GRANDE, QuantumDifficulty.fromLevel(1))
    }

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
        val pequeno = Sim(difficulty = QuantumDifficulty.PEQUENO)
        val grande = Sim(difficulty = QuantumDifficulty.GRANDE)

        val radioPequeno = pequeno.state.currentDropSphere!!.tier.radiusFor(QuantumDifficulty.PEQUENO.radiusScale)
        val radioGrande = pequeno.state.currentDropSphere!!.tier.radiusFor(QuantumDifficulty.GRANDE.radiusScale)
        assertTrue(radioGrande > radioPequeno, "las esferas no crecen con la dificultad")
        assertTrue(
            grande.state.difficulty.stackHeight < pequeno.state.difficulty.stackHeight,
            "el techo no baja con la dificultad",
        )
        // Invariante que sostiene el juego: la última fusión debe seguir cabiendo incluso en el
        // nivel más duro, o el tier máximo sería inalcanzable por geometría.
        val mayor = QuantumTier.SINGULARITY.radiusFor(QuantumDifficulty.GRANDE.radiusScale)
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
