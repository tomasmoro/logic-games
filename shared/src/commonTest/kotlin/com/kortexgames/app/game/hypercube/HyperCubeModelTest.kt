package com.kortexgames.app.game.hypercube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del núcleo puro del Neon Hyper-Cube: el álgebra de giros, el detector de "resuelto" y el
 * formato de guardado. Sin Compose ni corrutinas.
 *
 * Se prueban sobre todo **invariantes de grupo** (cuatro giros iguales vuelven al origen, un giro
 * y su inverso se cancelan) en vez de casos concretos: son las propiedades que garantizan que
 * mezclar, deshacer y detectar la victoria son coherentes entre sí, y las que se romperían con un
 * signo mal puesto en las permutaciones.
 */
class HyperCubeModelTest {

    private val allTurns: List<LayerTurn> = buildList {
        for (axis in Axis.entries) {
            for (layer in -1..1) {
                for (direction in TurnDirection.entries) add(LayerTurn(axis, layer, direction))
            }
        }
    }

    /** Clave comparable de un cubo: posición de cada pieza con sus pegatinas ordenadas. */
    private fun CubeState.key(): List<String> = cubies
        .map { cubie ->
            val stickers = cubie.stickers
                .map { "${it.normal.x},${it.normal.y},${it.normal.z}:${it.color}" }
                .sorted()
            "${cubie.position.x},${cubie.position.y},${cubie.position.z}|$stickers"
        }
        .sorted()

    // --- Estado inicial -------------------------------------------------------

    @Test
    fun elCuboResueltoTiene27PiezasY54Pegatinas() {
        val cube = CubeState.solved()
        assertEquals(27, cube.cubies.size)
        assertEquals(54, cube.cubies.sumOf { it.stickers.size })
        assertTrue(cube.isSolved)
    }

    // --- Álgebra de giros -----------------------------------------------------

    @Test
    fun cuatroGirosIgualesDevuelvenElCuboAlOrigen() {
        val solved = CubeState.solved()
        for (turn in allTurns) {
            var cube = solved
            repeat(4) { cube = cube.applyTurn(turn) }
            assertEquals(solved.key(), cube.key(), "4 giros de $turn deberían ser la identidad")
        }
    }

    @Test
    fun ungiroYSuInversoSeCancelan() {
        val solved = CubeState.solved()
        for (turn in allTurns) {
            val cube = solved.applyTurn(turn).applyTurn(turn.inverted())
            assertEquals(solved.key(), cube.key(), "$turn + su inverso deberían cancelarse")
        }
    }

    @Test
    fun unGiroDeCaraRompeElEstadoResuelto() {
        // Si un giro de cara dejara el cubo "resuelto", el detector de victoria daría por buena
        // una partida sin resolver (y la mezcla podría nacer ya ganada).
        val solved = CubeState.solved()
        for (turn in allTurns.filter { it.layer != 0 }) {
            assertFalse(solved.applyTurn(turn).isSolved, "$turn no debería dejar el cubo resuelto")
        }
    }

    @Test
    fun deshacerUnaSecuenciaEnOrdenInversoRestauraElCubo() {
        // Es la garantía en la que se apoya el botón "Deshacer": aplicar los inversos en orden
        // inverso devuelve exactamente el estado previo, jugada a jugada.
        val solved = CubeState.solved()
        val sequence = listOf(
            LayerTurn(Axis.Y, 1, TurnDirection.CLOCKWISE),
            LayerTurn(Axis.X, -1, TurnDirection.COUNTER_CLOCKWISE),
            LayerTurn(Axis.Z, 0, TurnDirection.CLOCKWISE),
            LayerTurn(Axis.Y, -1, TurnDirection.CLOCKWISE),
        )
        var cube = solved
        for (turn in sequence) cube = cube.applyTurn(turn)
        assertFalse(cube.isSolved)
        for (turn in sequence.reversed()) cube = cube.applyTurn(turn.inverted())
        assertEquals(solved.key(), cube.key())
    }

    @Test
    fun girarElCuboEnteroLoDejaResuelto() {
        // El detector es invariante a la orientación: girar las tres capas de un eje reorienta el
        // esquema de colores pero no desordena nada, y el jugador lo ve resuelto. Con el criterio
        // ingenuo ("cada color en SU cara original") esto daría "sin resolver".
        var cube = CubeState.solved()
        for (layer in -1..1) {
            cube = cube.applyTurn(LayerTurn(Axis.X, layer, TurnDirection.CLOCKWISE))
        }
        assertTrue(cube.isSolved)
    }

    @Test
    fun laRebanadaCentralNoMueveLasPiezasDeLasCarasExteriores() {
        // Un giro de la capa 0 solo puede tocar cubies con esa coordenada a 0.
        val cube = CubeState.solved().applyTurn(LayerTurn(Axis.Y, 0, TurnDirection.CLOCKWISE))
        val moved = cube.cubies.filter { it.position.y != 0 }
        val original = CubeState.solved().cubies.filter { it.position.y != 0 }
        assertEquals(original.toSet(), moved.toSet())
    }

    // --- Rampa de niveles -----------------------------------------------------

    @Test
    fun laProfundidadDeMezclaCreceConElNivelYSeDetieneEnElUltimo() {
        assertEquals(2, scrambleDepthFor(1))
        assertEquals(4, scrambleDepthFor(3))
        assertEquals(MAX_LEVEL + 1, scrambleDepthFor(MAX_LEVEL))
        // Por encima del último nivel no sigue creciendo: la rampa termina ahí (ver su KDoc).
        assertEquals(scrambleDepthFor(MAX_LEVEL), scrambleDepthFor(MAX_LEVEL + 5))
        // Y un nivel inválido no revienta ni produce mezclas absurdas.
        assertEquals(2, scrambleDepthFor(0))
        assertEquals(2, scrambleDepthFor(-3))
    }

    // --- Formato de guardado --------------------------------------------------

    @Test
    fun elGuardadoSobreviveAlViajeDeIdaYVuelta() {
        var cube = CubeState.solved()
        val turns = listOf(
            LayerTurn(Axis.X, 1, TurnDirection.CLOCKWISE),
            LayerTurn(Axis.Z, -1, TurnDirection.COUNTER_CLOCKWISE),
        )
        for (turn in turns) cube = cube.applyTurn(turn)

        val saved = HyperCubeSavedState(
            cubies = cube.toSaved(),
            turns = turns.map { it.toSaved() },
            level = 3,
            isFreeMode = false,
            moves = 2,
            scrambleDepth = 4,
            elapsedMs = 12_345,
            undosUsed = 1,
            freeUndoUsed = true,
        )

        val restored = Json.decodeFromString<HyperCubeSavedState>(Json.encodeToString(saved))
        assertEquals(saved, restored)

        val restoredCube = restored.cubies.toCubeStateOrNull()
        assertNotNull(restoredCube)
        assertEquals(cube.key(), restoredCube.key())
        assertEquals(turns, restored.turns.map { it.toLayerTurnOrNull() })
    }

    @Test
    fun unGuardadoCorruptoSeDescartaEnVezDeProducirUnCuboImposible() {
        val valid = CubeState.solved().toSaved()

        // Faltan piezas.
        assertNull(valid.drop(1).toCubeStateOrNull())
        // Color inexistente (p. ej. un enum renombrado en una versión posterior).
        assertNull(
            valid.map { c ->
                c.copy(stickers = c.stickers.map { it.copy(color = "ROSA_CHICLE") })
            }.toCubeStateOrNull(),
        )
        // Normal que no es un eje unitario.
        assertNull(
            valid.map { c -> c.copy(stickers = c.stickers.map { it.copy(nx = 3) }) }
                .toCubeStateOrNull(),
        )
        // Posición fuera del retículo.
        assertNull(valid.map { it.copy(x = 7) }.toCubeStateOrNull())
        // Y un giro con nombres desconocidos tampoco se acepta.
        assertNull(SavedTurn("W", 1, "CLOCKWISE").toLayerTurnOrNull())
        assertNull(SavedTurn("X", 5, "CLOCKWISE").toLayerTurnOrNull())
    }
}
