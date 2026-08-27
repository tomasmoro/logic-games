package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.grid.GridPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del motor de "Neon Grid Switch": conmutación ortogonal disparada por
 * intent, conteo de movimientos, detección de victoria y transición de etapa
 * (tamaño de tablero creciente).
 *
 * Las etapas se GENERAN, así que ningún test asume un tablero concreto: la
 * solución se busca con un solver propio del test ([solve]), independiente de
 * las interioridades del generador (mismo criterio que
 * [com.kortexgames.app.game.neonline.NeonLineEngineTest]).
 *
 * Se testea a través de la API pública del motor leyendo el StateFlow de forma
 * síncrona: el motor no lanza corrutinas propias, así que no hace falta
 * `coroutines-test`.
 */
class GridSwitchEngineTest {

    /** Doble de audio inerte: el motor no lo usa (feedback vía eventos). */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun engineAtStage(stage: Int): GridSwitchEngine =
        GridSwitchEngine(scope, FakeAudio).also { it.startAtStage(stage) }

    /** Recolecta los eventos de dominio del motor (Unconfined = entrega síncrona). */
    private fun GridSwitchEngine.collectEvents(): List<GridSwitchEvent> =
        mutableListOf<GridSwitchEvent>().also { sink ->
            events.onEach(sink::add).launchIn(scope)
        }

    // --- Tamaño de etapa y generación ------------------------------------------

    @Test
    fun cadaEtapaArrancaConElTamanoDeSuProgresion() {
        // 3 etapas por tamaño (1-3 en 3×3, 4-6 en 4×4, 7-9 en 5×5) antes de crecer.
        assertEquals(3, engineAtStage(1).state.value.board.size)
        assertEquals(3, engineAtStage(3).state.value.board.size)
        assertEquals(4, engineAtStage(4).state.value.board.size)
        assertEquals(4, engineAtStage(6).state.value.board.size)
        assertEquals(5, engineAtStage(7).state.value.board.size)
        assertEquals(5, engineAtStage(9).state.value.board.size)
        // A partir de la 10, el tamaño se estabiliza en 6×6 (la dificultad sigue
        // subiendo por más desorden, no por más tablero).
        assertEquals(6, engineAtStage(10).state.value.board.size)
        assertEquals(6, engineAtStage(15).state.value.board.size)
    }

    @Test
    fun laPrimeraEtapaEsUnTutorialDeUnSoloToque() {
        // Pedido del usuario: la 1ª etapa se resuelve con un único toque (el mismo
        // que la desordenó, ver KDoc de GridSwitchGenerator).
        assertEquals(1, GridSwitchStages.scrambleTouchesForStage(1))

        val engine = engineAtStage(1)
        val solution = solve(engine.state.value.board)
        assertEquals(1, solution.size, "la etapa tutorial debería resolverse en un solo toque")
    }

    @Test
    fun laEtapaArrancaConElTableroYaDesordenado() {
        val engine = engineAtStage(1)
        assertFalse(engine.state.value.board.isSolved, "una etapa recién arrancada no debería estar ya resuelta")
        assertEquals(0, engine.state.value.moveCount)
    }

    // --- Conmutación y conteo de movimientos ------------------------------------

    @Test
    fun tocarUnaCeldaConmutaElTableroYSumaUnMovimiento() {
        val engine = engineAtStage(1)
        val before = engine.state.value.board
        val cell = GridPosition(1, 1)

        engine.onCellToggled(cell)

        assertEquals(1, engine.state.value.moveCount)
        assertEquals(before.toggled(cell), engine.state.value.board)
    }

    @Test
    fun cadaToqueEmiteElEventoDeConmutacion() {
        val engine = engineAtStage(1)
        val events = engine.collectEvents()

        engine.onCellToggled(GridPosition(0, 0))
        engine.onCellToggled(GridPosition(1, 1))

        assertEquals(listOf(GridSwitchEvent.CellToggled, GridSwitchEvent.CellToggled), events)
    }

    @Test
    fun noSeConmutaNadaSiLaPartidaNoEstaEnCurso() {
        val engine = engineAtStage(1)
        engine.pause()
        val before = engine.state.value

        engine.onCellToggled(GridPosition(0, 0))

        assertEquals(before, engine.state.value, "en pausa no debería aceptar toques")
    }

    // --- Victoria y transición de etapa ------------------------------------------

    @Test
    fun resolverElTableroTerminaLaPartidaYEmiteElEventoDeEtapaCompletada() {
        val engine = engineAtStage(1)
        val events = engine.collectEvents()
        val solution = solve(engine.state.value.board)

        solution.forEach(engine::onCellToggled)

        assertTrue(engine.state.value.board.isSolved)
        assertEquals(GameStatus.FINISHED, engine.status.value)
        assertTrue(GridSwitchEvent.StageCleared in events)
        assertEquals(solution.size, engine.state.value.moveCount)
    }

    @Test
    fun unaVezResueltoUnToqueMasNoHaceNada() {
        val engine = engineAtStage(1)
        solve(engine.state.value.board).forEach(engine::onCellToggled)
        val solvedState = engine.state.value

        // La etapa ya terminó (status FINISHED); un toque colado no debe mutar nada.
        engine.onCellToggled(GridPosition(0, 0))

        assertEquals(solvedState, engine.state.value)
    }

    @Test
    fun reiniciarLaEtapaVuelveAlMismoDesordenInicialYPierdeElProgreso() {
        val engine = engineAtStage(1)
        val initial = engine.state.value.board
        engine.onCellToggled(GridPosition(0, 0))
        engine.onCellToggled(GridPosition(1, 2))

        engine.restartStage()

        assertEquals(initial, engine.state.value.board, "reiniciar debería volver al MISMO desorden, no a uno nuevo")
        assertEquals(0, engine.state.value.moveCount)
    }

    @Test
    fun avanzarDeEtapaCargaElSiguienteTamanoYaDesordenado() {
        // Etapa 3 (3×3, última del tier) → etapa 4 (4×4, 1ª del siguiente tier):
        // la única transición de las primeras etapas que SÍ cruza de tamaño (ver
        // GridSwitchStages: 3 etapas por tamaño).
        val engine = engineAtStage(3)
        solve(engine.state.value.board).forEach(engine::onCellToggled)
        assertEquals(GameStatus.FINISHED, engine.status.value)

        engine.startAtStage(4)

        assertEquals(GameStatus.RUNNING, engine.status.value)
        assertEquals(4, engine.state.value.board.size)
        assertEquals(0, engine.state.value.moveCount)
        assertFalse(engine.state.value.board.isSolved)
    }

    // --- Solver de test (independiente del generador) --------------------------

    /**
     * Encuentra una secuencia de toques (celdas distintas, cada una como máximo
     * una vez) que resuelve [grid], por búsqueda en anchura sobre el espacio de
     * subconjuntos vía máscaras de bits. Solo se usa con etapas pequeñas (aquí,
     * la 1ª: 3×3 → 512 estados), donde es trivialmente barato.
     */
    private fun solve(grid: LightGrid): List<GridPosition> {
        val size = grid.size
        val cellCount = size * size
        fun indexOf(pos: GridPosition) = pos.row * size + pos.col
        fun positionOf(index: Int) = GridPosition(index / size, index % size)

        val startMask = (0 until cellCount).fold(0) { acc, i ->
            if (grid.cellAt(positionOf(i))) acc or (1 shl i) else acc
        }
        if (startMask == 0) return emptyList()

        val toggleMasks = (0 until cellCount).map { i ->
            val affected = LightGrid.solved(size).toggled(positionOf(i))
            (0 until cellCount).fold(0) { acc, j -> if (affected.cellAt(positionOf(j))) acc or (1 shl j) else acc }
        }

        // BFS estándar sobre el espacio de estados (máscaras de bits): cada arista es
        // "aplicar el toggle i", y como es autoinverso, la misma arista sirve para
        // reconstruir el camino hacia atrás desde 0. El orden final de la secuencia
        // es irrelevante para el resultado (conmutatividad, ver GridSwitchModelTest),
        // así que no hace falta invertir el camino reconstruido.
        val cameFrom = HashMap<Int, Pair<Int, Int>>() // estado -> (estado previo, índice tocado)
        val visited = HashSet<Int>(cellCount * cellCount).apply { add(startMask) }
        val queue = ArrayDeque<Int>().apply { add(startMask) }
        while (queue.isNotEmpty()) {
            val mask = queue.removeFirst()
            if (mask == 0) break
            for ((i, toggle) in toggleMasks.withIndex()) {
                val candidate = mask xor toggle
                if (visited.add(candidate)) {
                    cameFrom[candidate] = mask to i
                    queue.add(candidate)
                }
            }
        }

        val path = mutableListOf<GridPosition>()
        var state = 0
        while (state != startMask) {
            val (prev, index) = cameFrom.getValue(state)
            path.add(positionOf(index))
            state = prev
        }
        return path
    }
}
