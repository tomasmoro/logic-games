package com.kortexgames.app.game

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.domain.model.GameResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Ciclo de vida de una partida. Transiciones válidas: IDLE→RUNNING↔PAUSED→FINISHED. */
enum class GameStatus { IDLE, RUNNING, PAUSED, FINISHED }

/**
 * Contrato genérico que implementan los 30 minijuegos. Expone el ciclo de vida
 * pedido (start/pause/resume/finish/calculateScore) más dos flujos observables:
 * [status] (ciclo de vida) y [state] (estado de UI específico del juego).
 *
 * @param S tipo del estado de UI del juego (data class inmutable).
 */
interface GameEngine<S : Any> {
    /** UUID del juego en el catálogo (tabla `games`); se usa al guardar el resultado. */
    val gameId: String

    /** Nivel de dificultad 1..5 con el que se juega esta partida. */
    val difficulty: Int

    val status: StateFlow<GameStatus>
    val state: StateFlow<S>

    /**
     * Resultado final, `null` hasta que la partida termina. Permite que un juego
     * que finaliza por sí mismo (p. ej. fallo en Memoria) notifique al ViewModel
     * sin que este tenga que llamar a [finish] manualmente.
     */
    val outcome: StateFlow<GameResult?>

    fun start()
    fun pause()
    fun resume()

    /** Cierra la partida y devuelve (y publica en [outcome]) el [GameResult]. */
    fun finish(): GameResult

    /** Puntaje según las reglas del juego. La invoca [finish]. */
    fun calculateScore(): Int
}

/**
 * Base para motores de juego. Resuelve lo transversal para que cada juego solo
 * implemente SU lógica:
 *
 *  - Máquina de estados de [status].
 *  - **Cronómetro que descuenta pausas**: usa [TimeSource.Monotonic] y acumula
 *    solo el tiempo en RUNNING, así el `completion_time_ms` no se infla si el
 *    usuario deja el juego en pausa (y es coherente con el AdManager).
 *  - Construcción del [GameResult] a partir de gameId, score, precisión y tiempo.
 *
 * Los juegos extienden esta clase, exponen su [state] y definen [calculateScore],
 * [currentAccuracy] y los hooks `onStart/onPause/onResume/onFinish`.
 *
 * @param scope scope de corrutinas (normalmente `viewModelScope`) para timers.
 * @param audio manager de audio/háptica para el feedback inmediato del juego.
 */
abstract class BaseGameEngine<S : Any>(
    final override val gameId: String,
    final override val difficulty: Int,
    protected val scope: CoroutineScope,
    protected val audio: AudioAndHapticManager,
) : GameEngine<S> {

    private val _status = MutableStateFlow(GameStatus.IDLE)
    final override val status: StateFlow<GameStatus> = _status.asStateFlow()

    private val _outcome = MutableStateFlow<GameResult?>(null)
    final override val outcome: StateFlow<GameResult?> = _outcome.asStateFlow()

    // Cronómetro: marca del último arranque de RUNNING + tiempo activo acumulado.
    private var runMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var accumulated: Duration = Duration.ZERO

    /** Tiempo activo total (excluye pausas). Válido en cualquier estado. */
    protected fun elapsedActive(): Duration =
        accumulated + (runMark?.elapsedNow() ?: Duration.ZERO)

    final override fun start() {
        accumulated = Duration.ZERO
        _outcome.value = null
        runMark = TimeSource.Monotonic.markNow()
        _status.value = GameStatus.RUNNING
        onStart()
    }

    final override fun pause() {
        if (_status.value != GameStatus.RUNNING) return
        accumulated += runMark?.elapsedNow() ?: Duration.ZERO
        runMark = null
        _status.value = GameStatus.PAUSED
        onPause()
    }

    final override fun resume() {
        if (_status.value != GameStatus.PAUSED) return
        runMark = TimeSource.Monotonic.markNow()
        _status.value = GameStatus.RUNNING
        onResume()
    }

    final override fun finish(): GameResult {
        if (_status.value == GameStatus.RUNNING) {
            accumulated += runMark?.elapsedNow() ?: Duration.ZERO
            runMark = null
        }
        _status.value = GameStatus.FINISHED
        onFinish()
        return GameResult(
            gameId = gameId,
            score = calculateScore(),
            completionTimeMs = elapsedActive().inWholeMilliseconds,
            accuracyPercentage = currentAccuracy(),
            difficultyLevel = difficulty,
            reachedMetric = reachedMetric(),
        ).also { _outcome.value = it }
    }

    /** Precisión 0..100 de la partida (aciertos/intentos). La usa [finish]. */
    protected abstract fun currentAccuracy(): Double

    /**
     * Valor de progresión ALCANZADO en la partida (base del récord), en la unidad
     * natural del juego: longitud máx de secuencia, nivel/ronda alcanzado, mejor
     * reacción en ms, etc. `null` si no hubo métrica medible (default).
     *
     * Es distinto de [difficulty] (dificultad de INICIO) y de [calculateScore]
     * (puntaje). Cada juego lo sobreescribe según su semántica; ver [GameProgressions].
     */
    protected open fun reachedMetric(): Int? = null

    // Hooks opcionales; por defecto no hacen nada.
    protected open fun onStart() {}
    protected open fun onPause() {}
    protected open fun onResume() {}
    protected open fun onFinish() {}
}
