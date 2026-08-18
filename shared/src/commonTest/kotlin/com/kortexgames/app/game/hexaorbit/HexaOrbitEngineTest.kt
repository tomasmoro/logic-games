package com.kortexgames.app.game.hexaorbit

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del **bucle de juego y la física** de Hexa Orbit (FASE 2): reparametrización por longitud
 * de arco, rampa de rapidez, bloqueo del azulejo ocupado, recogida sin *tunneling* y respawn.
 *
 * Se prueba a través de la API pública del motor (arrancar y alimentar frames) y no de sus
 * funciones privadas: así el test valida lo que el jugador recibe de verdad y no se rompe si el
 * paso de física se reorganiza por dentro.
 */
class HexaOrbitEngineTest {

    /** Audio de usar y tirar: al motor le basta con que exista, estos tests no miran el sonido. */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    private fun engineWith(seed: Int): HexaOrbitEngine = HexaOrbitEngine(
        scope = CoroutineScope(Dispatchers.Default),
        audio = FakeAudio,
        random = Random(seed),
    ).also { it.start() }

    /** Alimenta [frames] frames de 16 ms (60 FPS) desde un reloj monotónico simulado. */
    private fun HexaOrbitEngine.runFrames(frames: Int, frameMs: Long = 16L) {
        var nanos = 1_000_000_000L
        repeat(frames + 1) {
            onFrame(nanos)
            nanos += frameMs * 1_000_000L
        }
    }

    // --- Reparametrización por longitud de arco -----------------------------------------------

    @Test
    fun `la recta mide lo que separa dos aristas opuestas`() {
        // Control de cordura de la tabla de arco: la recta cruza el hexágono de lado a lado, es
        // decir dos apotemas. Si esto fallara, TODA la física iría escalada.
        val expected = 2f * HexGeometry.APOTHEM
        val measured = HexCurveMetrics.lengthOf(PathCurvature.STRAIGHT)
        assertTrue(abs(measured - expected) < 1e-3f, "Recta mide $measured, esperado $expected")
    }

    @Test
    fun `un giro cerrado es mas corto que uno amplio y este mas que la recta`() {
        // Es la razón de ser de la rapidez lineal: a igual rapidez, un giro cerrado se atraviesa
        // antes. Si el orden se invirtiera, la curva dibujada mentiría sobre lo que va a tardar.
        val sharp = HexCurveMetrics.lengthOf(PathCurvature.SHARP)
        val wide = HexCurveMetrics.lengthOf(PathCurvature.WIDE)
        val straight = HexCurveMetrics.lengthOf(PathCurvature.STRAIGHT)
        assertTrue(sharp < wide, "SHARP=$sharp no es menor que WIDE=$wide")
        assertTrue(wide < straight, "WIDE=$wide no es menor que STRAIGHT=$straight")
    }

    @Test
    fun `el avance normalizado es proporcional a la distancia recorrida, no al parametro t`() {
        // La mitad del avance normalizado debe caer a la mitad de la LONGITUD de la curva. En el
        // giro cerrado, que es el más deformado, evaluar la Bézier en t = 0.5 daría otro punto.
        val pair = EdgePair(0, 1)
        val curve = HexGeometry.curveFor(pair)
        val half = curve.pointAt(HexCurveMetrics.parameterAt(PathCurvature.SHARP, 0.5f))

        val total = HexCurveMetrics.lengthOf(PathCurvature.SHARP)
        var walked = 0f
        var previous = curve.pointAt(0f)
        var t = 0f
        while (walked < total / 2f && t < 1f) {
            t += 0.001f
            val point = curve.pointAt(t)
            walked += previous.distanceTo(point)
            previous = point
        }
        assertTrue(half.distanceTo(previous) < 0.02f, "El punto medio en arco no coincide")
    }

    // --- Bucle y rampa de rapidez -------------------------------------------------------------

    @Test
    fun `el primer frame no mueve el puntero`() {
        // El FrameClock no tiene referencia previa: integrar ahí daría un dt basura.
        val engine = engineWith(seed = 1)
        val start = engine.state.value.pointer.position
        engine.onFrame(1_000_000_000L)
        assertEquals(start, engine.state.value.pointer.position)
        assertEquals(0f, engine.state.value.elapsedSeconds)
    }

    @Test
    fun `la rapidez sigue la rampa lineal y nunca supera el techo`() {
        val engine = engineWith(seed = 3)
        engine.runFrames(frames = 30)
        val game = engine.state.value

        assertTrue(game.speed > HexaOrbitBalance.INITIAL_SPEED, "La rampa no arrancó: ${game.speed}")
        val expected = HexaOrbitBalance.INITIAL_SPEED +
            HexaOrbitBalance.SPEED_RAMP_PER_SEC * game.elapsedSeconds
        assertTrue(abs(game.speed - expected) < 1e-4f, "Rampa no lineal: ${game.speed} vs $expected")

        // El techo no se alcanza jugando (harían falta ~72 s de supervivencia y aquí nadie gira
        // piezas), así que lo que se comprueba es la cota: la rapidez jamás lo rebasa.
        for (seed in 0 until 20) {
            val other = engineWith(seed)
            other.runFrames(frames = 600)
            assertTrue(other.state.value.speed <= HexaOrbitBalance.MAX_SPEED, "Semilla $seed")
        }
    }

    @Test
    fun `la partida termina cuando el puntero cruza la frontera`() {
        // Un tablero de rectas sin girar nada: el puntero sale por el borde en pocos segundos.
        val engine = engineWith(seed = 5)
        engine.runFrames(frames = 600)

        assertEquals(GameStatus.FINISHED, engine.status.value)
        assertTrue(engine.state.value.escaped)
        val result = engine.outcome.value
        assertNotNull(result)
        assertTrue(result.score >= 0)
    }

    @Test
    fun `el puntero nunca sale del tablero sin marcar la fuga`() {
        for (seed in 0 until 25) {
            val engine = engineWith(seed)
            engine.runFrames(frames = 400)
            val game = engine.state.value
            assertTrue(
                game.pointer.coord in game.board,
                "Semilla $seed: el puntero acabó en una celda inexistente",
            )
        }
    }

    // --- Rotación ------------------------------------------------------------------------------

    @Test
    fun `girar una pieza cambia su rotacion y reescribe el haz proyectado`() {
        val engine = engineWith(seed = 11)
        val before = engine.state.value
        // Se gira el segundo azulejo proyectado: está por delante del puntero, así que el haz
        // tiene que cambiar aunque el puntero no se haya movido.
        val target = before.projection.upcoming.first().coord

        engine.rotateTile(target)
        val after = engine.state.value

        assertEquals(
            (before.board.tiles.getValue(target).rotation + 1) % HEX_EDGES,
            after.board.tiles.getValue(target).rotation,
        )
        assertEquals(before.pointer.position, after.pointer.position, "El puntero no debe moverse")
        assertTrue(
            after.projection.steps != before.projection.steps,
            "El haz debería recalcularse en el mismo frame del tap",
        )
    }

    @Test
    fun `el azulejo que ocupa el puntero esta bloqueado al giro`() {
        // Si se pudiera girar, el orbe saltaría a otra curva y quedaría separado de su estela.
        val engine = engineWith(seed = 13)
        val before = engine.state.value
        engine.rotateTile(before.pointer.coord)
        val after = engine.state.value

        assertEquals(
            before.board.tiles.getValue(before.pointer.coord).rotation,
            after.board.tiles.getValue(before.pointer.coord).rotation,
        )
        assertEquals(before.pointer.exitEdge, after.pointer.exitEdge)
    }

    @Test
    fun `girar fuera del tablero o con la partida parada no hace nada`() {
        val engine = engineWith(seed = 17)
        val before = engine.state.value
        engine.rotateTile(HexCoord(99, -99))
        assertEquals(before.board, engine.state.value.board)

        engine.pause()
        val paused = engine.state.value
        engine.rotateTile(paused.projection.upcoming.first().coord)
        assertEquals(paused.board, engine.state.value.board)
    }

    @Test
    fun `en pausa la simulacion no avanza`() {
        val engine = engineWith(seed = 19)
        engine.runFrames(frames = 10)
        engine.pause()
        val frozen = engine.state.value

        var nanos = 50_000_000_000L
        repeat(20) {
            engine.onFrame(nanos)
            nanos += 16_000_000L
        }
        assertEquals(frozen.pointer.position, engine.state.value.pointer.position)
        assertEquals(frozen.elapsedSeconds, engine.state.value.elapsedSeconds)
    }

    // --- Orbes ---------------------------------------------------------------------------------

    @Test
    fun `la partida arranca con tres orbes, separados del puntero y en celdas distintas`() {
        for (seed in 0 until 30) {
            val game = engineWith(seed).state.value
            assertEquals(HexaOrbitBalance.MAX_ORBS, game.orbs.size, "Semilla $seed")
            assertEquals(
                game.orbs.size,
                game.orbs.map { it.coord }.toSet().size,
                "Semilla $seed: dos orbes comparten celda",
            )
            for (orb in game.orbs) {
                assertTrue(orb.coord in game.board, "Semilla $seed: orbe fuera del tablero")
                assertTrue(
                    orb.coord.distanceTo(game.pointer.coord) >= 2,
                    "Semilla $seed: orbe regalado justo delante del puntero",
                )
            }
        }
    }

    @Test
    fun `el tablero mantiene siempre tres orbes vivos durante la partida`() {
        // Invariante del respawn: recoger no debe dejar al jugador sin objetivos que perseguir.
        for (seed in 0 until 10) {
            val engine = engineWith(seed)
            repeat(20) {
                engine.runFrames(frames = 20)
                assertEquals(HexaOrbitBalance.MAX_ORBS, engine.state.value.orbs.size, "Semilla $seed")
            }
        }
    }

    @Test
    fun `recoger un orbe suma puntos y lo sustituye por otro distinto`() {
        // Se coloca el puntero sobre un orbe "a mano" no se puede (el estado es privado), así que
        // se juega hasta que alguna semilla recoge y se comprueba la contabilidad.
        val engine = (0 until 60).firstNotNullOfOrNull { seed ->
            engineWith(seed).takeIf { candidate ->
                candidate.runFrames(frames = 500)
                candidate.state.value.collected > 0
            }
        }
        assertNotNull(engine, "Ninguna semilla recogió un orbe en 500 frames")

        val game = engine.state.value
        assertTrue(game.score >= game.collected * HexaOrbitBalance.POINTS_PER_ORB)
        assertEquals(HexaOrbitBalance.MAX_ORBS, game.orbs.size)
        // Ids monotónicos: los orbes repuestos son objetos NUEVOS para la UI, no reposicionados.
        assertTrue(game.orbs.any { it.id > HexaOrbitBalance.MAX_ORBS })
    }

    @Test
    fun `girar una pieza no mueve los orbes que contiene`() {
        // Los orbes están anclados al espacio, no a la pieza: es lo que obliga a encaminar el haz
        // por encima en vez de limitarse a pisar la casilla.
        val engine = engineWith(seed = 23)
        val before = engine.state.value
        val orb = before.orbs.first { it.coord != before.pointer.coord }

        engine.rotateTile(orb.coord)
        val after = engine.state.value.orbs.first { it.id == orb.id }
        assertEquals(orb.position, after.position)
    }

    // --- Estela y resultado ---------------------------------------------------------------------

    @Test
    fun `la estela crece hasta su tope y no mas`() {
        val engine = engineWith(seed = 29)
        engine.runFrames(frames = 5)
        val short = engine.state.value.pointer.trail.size
        assertTrue(short in 1..HexaOrbitBalance.TRAIL_POINTS)

        engine.runFrames(frames = 200)
        assertTrue(engine.state.value.pointer.trail.size <= HexaOrbitBalance.TRAIL_POINTS)
    }

    @Test
    fun `la precision mide el tiempo con el circuito a salvo`() {
        val engine = engineWith(seed = 31)
        engine.runFrames(frames = 600)
        val result = engine.outcome.value
        assertNotNull(result)
        assertTrue(result.accuracyPercentage in 0.0..100.0, "Precisión fuera de rango")
        // La partida termina siempre con el haz apuntando al vacío, así que nunca es 100 %.
        assertFalse(result.accuracyPercentage == 100.0)
    }
}
