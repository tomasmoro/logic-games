package com.kortexgames.app.game.shikaku

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * # ShikakuViewModel — motor de la partida
 *
 * Traduce los gestos ([ShikakuIntent]) en cambios de tablero aplicando [ShikakuRules], lleva el
 * ciclo de vida de la partida (antesala → jugando ↔ pausa → terminada), y al resolver el nivel
 * guarda el resultado en el repositorio local-first. No contiene reglas propias: aquí solo se
 * decide **cuándo** se evalúa y **qué** se le cuenta al jugador.
 *
 * A diferencia de otros juegos por niveles, no delega en un `BaseGameEngine`: el estado del
 * tablero ya es un reductor puro ([ShikakuRules]) y lo único que aportaría el motor base es el
 * cronómetro con pausa, que aquí son cuatro líneas. Un motor intermedio solo duplicaría el estado.
 *
 * Decisiones de comportamiento:
 *
 *  - **Toque = borrar, arrastre = trazar.** Un gesto que nunca sale de su celda sobre un
 *    rectángulo existente lo elimina; cualquier otro traza. No hay "modo edición": trazar encima
 *    reemplaza lo que pise (ver [ShikakuRules.place]).
 *  - **Lo inválido se queda, lo imposible no.** Un rectángulo con área equivocada o sin pista se
 *    coloca igualmente (parpadeará en rojo): a menudo es un paso intermedio del razonamiento y
 *    borrarlo obligaría a repetir el gesto. Uno que pisa agujeros se rechaza, porque no existe
 *    forma de dibujarlo.
 *  - **Rectificar cuesta.** Cada rectángulo borrado, reemplazado o sellado sin cumplir la regla
 *    cuenta como corrección y baja la eficiencia ([ShikakuScoring]).
 *
 * @param generatorDispatcher dónde corre el generador. Es CPU pura (partición + certificación de
 *   unicidad) y no debe ocupar el hilo principal; se inyecta para que los tests lo sustituyan.
 */
class ShikakuViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val adManager: AdManager,
    private val generatorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviViewModel<ShikakuIntent, ShikakuUiState, ShikakuEffect>(ShikakuUiState()) {

    /**
     * Contador de ids de rectángulo. Es monótono durante toda la vida del ViewModel (no se
     * reinicia por nivel) para que un `RemoveRectangle` rezagado de un tablero anterior no pueda
     * coincidir con un rectángulo del actual.
     */
    private var nextRectangleId = 1

    /** Generación en curso; se cancela si se pide otro nivel antes de que termine. */
    private var generation: Job? = null

    // Contadores de la partida en curso. No son estado de UI (nada los pinta): solo alimentan la
    // puntuación al terminar.
    private var corrections = 0
    private var restarts = 0

    // Cronómetro de tiempo ACTIVO: `accumulated` guarda los tramos ya cerrados y `runMark` el
    // tramo abierto. Pausar cierra el tramo, así el tiempo en pausa nunca cuenta.
    private var accumulated: Duration = Duration.ZERO
    private var runMark: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        playerProgress.observe(GameIds.NEON_SHIKAKU)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: ShikakuIntent) {
        when (intent) {
            is ShikakuIntent.PlayLevel -> playLevel(intent.level)
            is ShikakuIntent.StartDrag -> startDrag(intent.x, intent.y)
            is ShikakuIntent.UpdateDrag -> updateDrag(intent.x, intent.y)
            ShikakuIntent.ReleaseDrag -> releaseDrag()
            ShikakuIntent.CancelDrag -> setState { copy(selection = null) }
            is ShikakuIntent.RemoveRectangle -> removeRectangle(intent.id)
            ShikakuIntent.RestartLevel -> restart()
            ShikakuIntent.Pause -> pause()
            ShikakuIntent.Resume -> resume()
            ShikakuIntent.PlayAgain -> playLevel(currentState.level)
            ShikakuIntent.NextLevel -> if (currentState.status == GameStatus.FINISHED) {
                // Entre niveles es el corte natural para un anuncio: nunca a mitad de tablero.
                adManager.onAdBreakpoint()
                playLevel(currentState.level + 1)
            }
            ShikakuIntent.ChooseLevel -> {
                generation?.cancel()
                setState { ShikakuUiState(maxUnlocked = maxUnlocked) }
            }
        }
    }

    /** `true` si el tablero admite gestos: generado, en marcha y sin resolver. */
    private val ShikakuUiState.acceptsInput: Boolean get() = status == GameStatus.RUNNING && !isGenerating

    private fun startDrag(x: Int, y: Int) {
        val state = currentState
        // Empezar en un agujero no es un error del jugador (el dedo cayó fuera de la figura):
        // se ignora en silencio en vez de sonar a fallo.
        if (!state.acceptsInput || !state.mask.isEnabled(x, y)) return
        setState { copy(selection = selectionOf(x, y, x, y, hasMoved = false)) }
        sendEffect(ShikakuEffect.PlaySound(ShikakuSound.SELECT))
    }

    private fun updateDrag(x: Int, y: Int) {
        val state = currentState
        val selection = state.selection ?: return
        // El dedo puede salirse del tablero a mitad de gesto: se recorta a la rejilla para que la
        // selección siga pegada al borde en lugar de desaparecer.
        val cx = x.coerceIn(0, state.mask.columns - 1)
        val cy = y.coerceIn(0, state.mask.rows - 1)
        if (cx == selection.currentX && cy == selection.currentY) return
        setState { copy(selection = selectionOf(selection.anchorX, selection.anchorY, cx, cy, hasMoved = true)) }
        sendEffect(ShikakuEffect.Vibrate(ShikakuHaptic.TICK))
    }

    private fun releaseDrag() {
        val state = currentState
        val selection = state.selection ?: return
        val bounds = selection.bounds

        if (!selection.hasMoved) {
            val tapped = ShikakuRules.rectangleAt(state.rectangles, selection.anchorX, selection.anchorY)
            if (tapped != null) {
                setState { copy(selection = null) }
                removeRectangle(tapped.id)
                return
            }
            // Toque suelto sobre una celda libre: solo se sella si resulta ser un "1" válido. Si
            // no, es casi seguro un toque accidental y no merece ni rectángulo ni sonido de error.
            if (selection.validity != ShikakuRectValidity.VALID) {
                setState { copy(selection = null) }
                return
            }
        }

        if (selection.validity == ShikakuRectValidity.OUT_OF_MASK) {
            setState { copy(selection = null) }
            sendEffect(ShikakuEffect.PlaySound(ShikakuSound.ERROR))
            return
        }

        val verdict = ShikakuVerdict(selection.validity, selection.targetNumber)
        val rectangles = ShikakuRules.place(state.rectangles, bounds, nextRectangleId++, verdict)
        val validation = ShikakuRules.validate(state.mask, rectangles)
        // Correcciones: los rectángulos que este trazo se llevó por delante, más él mismo si
        // nace incumpliendo la regla (habrá que rehacerlo).
        corrections += state.rectangles.size - (rectangles.size - 1)
        if (verdict.validity != ShikakuRectValidity.VALID) corrections++
        setState { copy(rectangles = rectangles, selection = null, validation = validation) }

        when {
            validation.isSolved -> finish()

            verdict.validity == ShikakuRectValidity.VALID -> {
                sendEffect(ShikakuEffect.PlaySound(ShikakuSound.RECTANGLE_COMPLETE))
                sendEffect(ShikakuEffect.Vibrate(ShikakuHaptic.SUCCESS))
            }

            else -> sendEffect(ShikakuEffect.PlaySound(ShikakuSound.ERROR))
        }
    }

    private fun removeRectangle(id: Int) {
        val state = currentState
        if (!state.acceptsInput || state.rectangles.none { it.id == id }) return
        val rectangles = state.rectangles.filterNot { it.id == id }
        corrections++
        setState { copy(rectangles = rectangles, validation = ShikakuRules.validate(mask, rectangles)) }
        sendEffect(ShikakuEffect.PlaySound(ShikakuSound.SELECT))
    }

    private fun restart() {
        if (!currentState.acceptsInput) return
        restarts++
        setState {
            copy(rectangles = emptyList(), selection = null, validation = ShikakuRules.validate(mask, emptyList()))
        }
    }

    private fun pause() {
        if (currentState.status != GameStatus.RUNNING) return
        stopClock()
        setState { copy(status = GameStatus.PAUSED, selection = null) }
    }

    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        runMark = TimeSource.Monotonic.markNow()
        setState { copy(status = GameStatus.RUNNING) }
    }

    /** Cierra el tramo de cronómetro abierto, si lo hay. */
    private fun stopClock() {
        accumulated += runMark?.elapsedNow() ?: Duration.ZERO
        runMark = null
    }

    /** Construye la selección ya evaluada, para que la UI no tenga que conocer las reglas. */
    private fun selectionOf(ax: Int, ay: Int, cx: Int, cy: Int, hasMoved: Boolean): ShikakuSelection {
        val state = currentState
        val verdict = ShikakuRules.evaluate(ShikakuRect.spanning(ax, ay, cx, cy), state.mask, state.clues)
        return ShikakuSelection(ax, ay, cx, cy, verdict.validity, verdict.targetNumber, hasMoved)
    }

    /**
     * Genera el tablero de [level] y arranca la partida. El cronómetro empieza cuando el tablero
     * ya está en pantalla, no al pulsar: el tiempo de generación no es tiempo de juego.
     */
    private fun playLevel(level: Int) {
        generation?.cancel()
        val target = level.coerceAtLeast(1)
        setState {
            ShikakuUiState(
                phase = LeveledGamePhase.PLAYING,
                maxUnlocked = maxUnlocked,
                level = target,
                isGenerating = true,
            )
        }
        generation = viewModelScope.launch {
            val generated = withContext(generatorDispatcher) { ShikakuLevelGenerator.generate(target) }
            corrections = 0
            restarts = 0
            accumulated = Duration.ZERO
            runMark = TimeSource.Monotonic.markNow()
            setState {
                copy(
                    mask = generated.mask,
                    clues = generated.clues,
                    validation = ShikakuRules.validate(generated.mask, emptyList()),
                    isGenerating = false,
                    status = GameStatus.RUNNING,
                )
            }
        }
    }

    /**
     * Cierra la partida resuelta: para el reloj, calcula el resultado y lo guarda. El overlay de
     * fin aparece cuando el repositorio responde (trae percentil y récord), pero el estado pasa a
     * `FINISHED` de inmediato para bloquear el tablero mientras tanto.
     */
    private fun finish() {
        stopClock()
        val state = currentState
        val efficiency = ShikakuScoring.efficiency(state.clues.size, corrections)
        val elapsedMs = accumulated.inWholeMilliseconds
        val result = GameResult(
            gameId = GameIds.NEON_SHIKAKU,
            score = ShikakuScoring.score(state.level, state.mask.enabledCount, elapsedMs, efficiency, restarts),
            completionTimeMs = elapsedMs,
            accuracyPercentage = efficiency * 100,
            // El nivel viaja en `reachedMetric`, no aquí: `difficulty_level` es en el backend un
            // escalón 1..5 de tabla de ranking, y este juego compite en tabla única (el puntaje
            // ya está dominado por el nivel). Mismo criterio que Línea Neón.
            reachedMetric = state.level,
        )
        setState { copy(status = GameStatus.FINISHED) }
        sendEffect(ShikakuEffect.PlaySound(ShikakuSound.LEVEL_COMPLETE))
        sendEffect(ShikakuEffect.Vibrate(ShikakuHaptic.SUCCESS))
        viewModelScope.launch {
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }
}
