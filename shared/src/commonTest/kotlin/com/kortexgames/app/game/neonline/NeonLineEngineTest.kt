package com.kortexgames.app.game.neonline

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighborsIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del motor de "Línea Neón": la validación estricta del trazo (sin diagonales,
 * sin saltos, sin pisar obstáculos ni pisarse a sí misma), el retroceso táctil, la
 * detección de victoria y las reglas de puntuación.
 *
 * Los niveles se GENERAN, así que ningún test asume un tablero concreto: la solución
 * se busca con un solver propio del test ([solve]), independiente de las interioridades
 * del generador. Que ese solver encuentre solución en todos los niveles probados es, de
 * paso, la comprobación end-to-end de que la construcción "desde una solución" cumple
 * lo que promete.
 *
 * Se testea a través de la API pública del motor leyendo el StateFlow de forma
 * síncrona: el motor no lanza corrutinas, así que no hace falta `coroutines-test`
 * (que no está en el catálogo de versiones).
 */
class NeonLineEngineTest {

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

    private fun engineAtLevel(level: Int): NeonLineEngine =
        NeonLineEngine(scope, FakeAudio).also { it.startAtLevel(level) }

    /** Recolecta los eventos de dominio del motor (Unconfined = entrega síncrona). */
    private fun NeonLineEngine.collectEvents(): List<NeonLineEvent> =
        mutableListOf<NeonLineEvent>().also { sink ->
            events.onEach(sink::add).launchIn(scope)
        }

    // --- Validación del trazo ---------------------------------------------------

    @Test
    fun elTrazoNoAvanzaEnDiagonalNiSaltandoCeldas() {
        val engine = engineAtLevel(1) // 4x4 despejado
        engine.onPathStarted(GridPosition(0, 0))

        engine.onPathUpdated(GridPosition(1, 1)) // diagonal
        assertEquals(1, engine.state.value.path.size, "una diagonal no debería avanzar")

        engine.onPathUpdated(GridPosition(0, 2)) // salto de una celda
        assertEquals(1, engine.state.value.path.size, "un salto no debería avanzar")

        engine.onPathUpdated(GridPosition(0, 1)) // adyacente: sí avanza
        assertEquals(listOf(GridPosition(0, 0), GridPosition(0, 1)), engine.state.value.path)
    }

    @Test
    fun elTrazoNoSePisaASiMismo() {
        // Se busca un cuadrado 2x2 libre en el tablero generado (en vez de dar por hecho
        // unas coordenadas): recorrerlo entero deja la punta pegada a la celda inicial,
        // que es el auto-cruce más limpio que se puede provocar.
        val level = NeonLineLevels.forNumber(1)
        val square = assertNotNull(freeSquare(level), "el nivel 1 no tiene ningún 2x2 libre")
        val engine = engineAtLevel(1)

        square.forEachIndexed { i, cell ->
            if (i == 0) engine.onPathStarted(cell) else engine.onPathUpdated(cell)
        }
        assertEquals(4, engine.state.value.path.size)

        // La celda inicial es adyacente a la punta pero ya está trazada: auto-cruce.
        engine.onPathUpdated(square.first())
        assertEquals(4, engine.state.value.path.size, "no debería poder cerrarse sobre sí misma")
    }

    /**
     * Cuatro celdas libres en cuadrado, devueltas en orden de recorrido (arriba-izq →
     * arriba-der → abajo-der → abajo-izq), o null si el tablero no tiene ninguno.
     */
    private fun freeSquare(level: NeonLineLevel): List<GridPosition>? {
        for (row in 0 until level.gridSize - 1) {
            for (col in 0 until level.gridSize - 1) {
                val square = listOf(
                    GridPosition(row, col),
                    GridPosition(row, col + 1),
                    GridPosition(row + 1, col + 1),
                    GridPosition(row + 1, col),
                )
                if (square.all(level::isPlayable)) return square
            }
        }
        return null
    }

    @Test
    fun elTrazoNoEntraEnLosObstaculos() {
        // Nivel 3: primer escalón con obstáculos (4x4 con 3 bloques).
        val level = NeonLineLevels.forNumber(3)
        val engine = engineAtLevel(3)
        val obstacle = level.obstacles.first()
        // Se arranca desde una celda libre pegada al obstáculo para que el intento
        // sea adyacente (y por tanto deliberado).
        val start = obstacle.orthogonalNeighborsIn(level.playableCells).first()

        engine.onPathStarted(start)
        engine.onPathUpdated(obstacle)
        assertEquals(listOf(start), engine.state.value.path, "un obstáculo no es pisable")
    }

    @Test
    fun noSePuedeEmpezarElTrazoSobreUnObstaculo() {
        val engine = engineAtLevel(3)
        engine.onPathStarted(NeonLineLevels.forNumber(3).obstacles.first())
        assertTrue(engine.state.value.path.isEmpty(), "no se traza en el aire")
    }

    // --- Retroceso táctil -------------------------------------------------------

    @Test
    fun desandarElDedoBorraLaPunta() {
        val engine = engineAtLevel(1)
        engine.onPathStarted(GridPosition(0, 0))
        engine.onPathUpdated(GridPosition(0, 1))
        engine.onPathUpdated(GridPosition(0, 2))
        assertEquals(3, engine.state.value.path.size)

        engine.onPathUpdated(GridPosition(0, 1)) // vuelve sobre la anterior
        assertEquals(listOf(GridPosition(0, 0), GridPosition(0, 1)), engine.state.value.path)

        // Y la celda liberada vuelve a poder pisarse: el retroceso no la "quema".
        engine.onPathUpdated(GridPosition(0, 2))
        assertEquals(3, engine.state.value.path.size)
    }

    @Test
    fun elRetrocesoSoloBorraUnPasoAunqueElDedoSalteAtras() {
        // Las primeras celdas de una solución real: contiguas por construcción, sin
        // depender de que el tablero generado tenga una fila despejada.
        val solution = assertNotNull(solve(NeonLineLevels.forNumber(1)))
        val engine = engineAtLevel(1)
        solution.take(4).forEachIndexed { i, cell ->
            if (i == 0) engine.onPathStarted(cell) else engine.onPathUpdated(cell)
        }
        assertEquals(4, engine.state.value.path.size)

        // Saltar el dedo a la celda inicial NO recorta medio trazo: o no es adyacente a
        // la punta (se ignora) o lo es pero ya está trazada (auto-cruce). En ningún caso
        // se borra el tramo intermedio de golpe (ver KDoc del motor).
        engine.onPathUpdated(solution.first())
        assertEquals(4, engine.state.value.path.size)
    }

    // --- Feedback de rechazo ----------------------------------------------------

    @Test
    fun losSaltosYDiagonalesNoDisparanFeedbackDeError() {
        val engine = engineAtLevel(1)
        val events = engine.collectEvents()
        engine.onPathStarted(GridPosition(0, 0))
        engine.onPathUpdated(GridPosition(1, 1)) // diagonal
        engine.onPathUpdated(GridPosition(3, 3)) // salto largo

        assertTrue(
            events.none { it is NeonLineEvent.MoveRejected },
            "el arrastre rápido no debe sonar a error: $events",
        )
    }

    @Test
    fun elRechazoNoSeRepiteMientrasElDedoSigueEnLaMismaCelda() {
        val level = NeonLineLevels.forNumber(3)
        val engine = engineAtLevel(3)
        val events = engine.collectEvents()
        val obstacle = level.obstacles.first()
        val start = obstacle.orthogonalNeighborsIn(level.playableCells).first()

        engine.onPathStarted(start)
        repeat(5) { engine.onPathUpdated(obstacle) }

        assertEquals(
            1,
            events.count { it is NeonLineEvent.MoveRejected },
            "mantener el dedo sobre un obstáculo debe avisar una sola vez",
        )
    }

    // --- Victoria ---------------------------------------------------------------

    @Test
    fun cubrirTodasLasCeldasLibresGanaElNivel() {
        (1..8).forEach { number ->
            val level = NeonLineLevels.forNumber(number)
            val solution = assertNotNull(solve(level), "nivel $number: el solver no encontró camino")
            val engine = engineAtLevel(number)

            solution.forEachIndexed { i, cell ->
                if (i == 0) engine.onPathStarted(cell) else engine.onPathUpdated(cell)
            }

            val s = engine.state.value
            assertEquals(level.playableCount, s.path.size, "nivel $number")
            assertTrue(s.isSolved, "nivel $number no se marcó resuelto")
            assertFalse(s.isTracing, "nivel $number: el trazo debería soltarse al ganar")
            assertEquals(GameStatus.FINISHED, engine.status.value, "nivel $number")
            assertEquals(number, engine.outcome.value?.reachedMetric, "nivel $number")
        }
    }

    @Test
    fun unNivelIncompletoNoPuntua() {
        val engine = engineAtLevel(1)
        engine.onPathStarted(GridPosition(0, 0))
        engine.onPathUpdated(GridPosition(0, 1))
        assertEquals(0, engine.calculateScore(), "medio tablero no vale puntos")
    }

    // --- Puntuación -------------------------------------------------------------

    @Test
    fun reiniciarPenalizaPeroNuncaPorDebajoDelNivelAnterior() {
        val level = NeonLineLevels.forNumber(2)
        val solution = assertNotNull(solve(level))

        fun scoreWithRestarts(restarts: Int): Int {
            val engine = engineAtLevel(2)
            repeat(restarts) { engine.restart() }
            solution.forEachIndexed { i, cell ->
                if (i == 0) engine.onPathStarted(cell) else engine.onPathUpdated(cell)
            }
            return engine.calculateScore()
        }

        val clean = scoreWithRestarts(0)
        val messy = scoreWithRestarts(3)
        assertTrue(messy < clean, "reiniciar debería costar puntos ($messy vs $clean)")

        // El tope de penalización garantiza que ni con 100 reinicios el nivel 2 caiga
        // por debajo de la base del nivel 1: el orden por nivel manda en el ranking.
        assertTrue(
            scoreWithRestarts(100) >= 1_000,
            "la penalización no debe invertir el orden por nivel",
        )
    }

    @Test
    fun retrocederBajaLaEficienciaYPorTantoElPuntaje() {
        val level = NeonLineLevels.forNumber(2)
        val solution = assertNotNull(solve(level))

        fun play(withBacktracking: Boolean): NeonLineEngine {
            val engine = engineAtLevel(2)
            engine.onPathStarted(solution[0])
            engine.onPathUpdated(solution[1])
            if (withBacktracking) {
                // Avanza y desanda varias veces antes de seguir la solución.
                repeat(5) {
                    engine.onPathUpdated(solution[0])
                    engine.onPathUpdated(solution[1])
                }
            }
            solution.drop(2).forEach(engine::onPathUpdated)
            return engine
        }

        val clean = play(withBacktracking = false)
        val messy = play(withBacktracking = true)
        assertTrue(clean.state.value.isSolved && messy.state.value.isSolved)
        assertTrue(
            messy.calculateScore() < clean.calculateScore(),
            "titubear debería puntuar menos que trazar del tirón",
        )
        // La precisión viaja en el GameResult que publica `finish()` al resolver.
        assertEquals(
            100.0,
            clean.outcome.value?.accuracyPercentage,
            "un trazo sin retrocesos es 100% preciso",
        )
        assertTrue((messy.outcome.value?.accuracyPercentage ?: 100.0) < 100.0)
    }

    // --- Reinicio ---------------------------------------------------------------

    @Test
    fun reiniciarDejaElTableroComoAlEmpezarSinCambiarElNivel() {
        val engine = engineAtLevel(3)
        val obstacles = engine.state.value.obstacles
        engine.onPathStarted(NeonLineLevels.forNumber(3).playableCells.first())
        engine.restart()

        val s = engine.state.value
        assertTrue(s.path.isEmpty(), "el reinicio debe borrar el trazo")
        assertFalse(s.isTracing)
        assertEquals(obstacles, s.obstacles, "el reinicio no regenera el nivel")
    }

    // --- Solver del test --------------------------------------------------------

    /**
     * Busca un camino que recorra todas las celdas libres de [level] (backtracking con
     * Warnsdorff, probando cada celda como inicio). Es deliberadamente independiente
     * del generador: si solo se comprobara reproduciendo el camino que el generador
     * usó, el test no probaría que el nivel es resoluble, sino que el generador
     * recuerda su propia respuesta.
     */
    private fun solve(level: NeonLineLevel): List<GridPosition>? {
        val cells = level.playableCells
        // Un extremo del camino tiene que ser un callejón sin salida si lo hay; probar
        // primero por ahí poda casi todo el espacio de búsqueda.
        val starts = cells.sortedBy { it.orthogonalNeighborsIn(cells).size }

        for (start in starts) {
            val path = ArrayList<GridPosition>(cells.size)
            val visited = HashSet<GridPosition>(cells.size)
            var budget = cells.size * 20_000

            fun backtrack(current: GridPosition): Boolean {
                path.add(current)
                visited.add(current)
                if (path.size == cells.size) return true

                val next = current.orthogonalNeighborsIn(cells)
                    .filter { it !in visited }
                    .sortedBy { n -> n.orthogonalNeighborsIn(cells).count { it !in visited } }

                for (candidate in next) {
                    if (--budget <= 0) return false
                    if (backtrack(candidate)) return true
                }
                path.removeAt(path.size - 1)
                visited.remove(current)
                return false
            }

            if (backtrack(start)) return path
        }
        return null
    }
}
