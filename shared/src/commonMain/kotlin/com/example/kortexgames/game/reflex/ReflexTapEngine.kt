package com.example.kortexgames.game.reflex

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

/** Fase de una ronda de Reflejos. */
enum class ReflexPhase {
    IDLE,
    WAITING,       // esperando (tiempo aleatorio) a que aparezca el objetivo
    READY,         // ¡objetivo visible! hay que tocar YA
    TOO_SOON,      // tocó antes de tiempo (ronda penalizada)
    ROUND_RESULT,  // muestra la reacción de la ronda
    FINISHED,
}

/**
 * Estado de UI de Reflejos.
 *
 * @property lastReactionMs reacción de la última ronda (o null / penalización).
 * @property bestMs mejor reacción hasta ahora. @property avgMs media de válidas.
 */
data class ReflexTapState(
    val phase: ReflexPhase = ReflexPhase.IDLE,
    val round: Int = 0,
    val totalRounds: Int = TOTAL_ROUNDS,
    val lastReactionMs: Long? = null,
    val bestMs: Long? = null,
    val avgMs: Long? = null,
    val score: Int = 0,
) {
    companion object {
        const val TOTAL_ROUNDS = 5
    }
}

/**
 * Motor de "Reflejos de Toque Rápido": tras una espera aleatoria aparece un
 * objetivo y se mide el tiempo de reacción con [TimeSource.Monotonic] (preciso e
 * inmune a cambios de reloj). Tocar antes de tiempo penaliza la ronda.
 *
 * Puntaje: cada ronda aporta `max(0, SCORE_CAP - reacciónMs)` → cuanto más rápido,
 * más puntos; una reacción ≥ SCORE_CAP o una penalización aportan 0.
 *
 * La espera corre en [waitJob], cancelable en pausa; al reanudar se reprograma
 * con una espera nueva (así el jugador no "adivina" el momento exacto).
 */
class ReflexTapEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
    private val waitMinMs: Long = 1200,
    private val waitMaxMs: Long = 3200,
) : BaseGameEngine<ReflexTapState>(GameIds.REFLEX_TAP, difficulty, scope, audio) {

    private val _state = MutableStateFlow(ReflexTapState())
    override val state: StateFlow<ReflexTapState> = _state.asStateFlow()

    private val reactions = mutableListOf<Long>() // reacciones válidas (ms)
    private var tooSoonCount = 0
    private var targetMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var waitJob: Job? = null

    override fun onStart() {
        reactions.clear()
        tooSoonCount = 0
        _state.value = ReflexTapState()
        startNextRound()
    }

    override fun onPause() {
        waitJob?.cancel()
    }

    override fun onResume() {
        if (_state.value.phase == ReflexPhase.WAITING) scheduleTarget()
    }

    private fun startNextRound() {
        val next = _state.value.round + 1
        if (next > _state.value.totalRounds) {
            finish()
            return
        }
        _state.update { it.copy(round = next, phase = ReflexPhase.WAITING, lastReactionMs = null) }
        scheduleTarget()
    }

    /** Programa la aparición del objetivo tras una espera aleatoria. */
    private fun scheduleTarget() {
        waitJob?.cancel()
        waitJob = scope.launch {
            delay(random.nextLong(waitMinMs, waitMaxMs))
            targetMark = TimeSource.Monotonic.markNow()
            audio.playSound(SoundEffect.TIMER_TICK)
            _state.update { it.copy(phase = ReflexPhase.READY) }
        }
    }

    /** Único input del juego: un toque en cualquier parte de la zona de juego. */
    fun onTap() {
        when (_state.value.phase) {
            ReflexPhase.WAITING -> {
                // Tocó antes de que apareciera el objetivo → penalización.
                waitJob?.cancel()
                tooSoonCount++
                audio.playSound(SoundEffect.ERROR)
                audio.hapticFeedback(HapticFeedback.ERROR)
                _state.update { it.copy(phase = ReflexPhase.TOO_SOON) }
                scope.launch {
                    delay(ROUND_PAUSE_MS)
                    if (_state.value.phase == ReflexPhase.TOO_SOON) startNextRound()
                }
            }

            ReflexPhase.READY -> {
                val reaction = targetMark?.elapsedNow()?.inWholeMilliseconds ?: return
                reactions += reaction
                audio.playSound(SoundEffect.SUCCESS)
                audio.hapticFeedback(HapticFeedback.MEDIUM)
                _state.update {
                    it.copy(
                        phase = ReflexPhase.ROUND_RESULT,
                        lastReactionMs = reaction,
                        bestMs = reactions.min(),
                        avgMs = reactions.average().toLong(),
                        score = calculateScore(),
                    )
                }
                scope.launch {
                    delay(ROUND_PAUSE_MS)
                    if (_state.value.phase == ReflexPhase.ROUND_RESULT) startNextRound()
                }
            }

            else -> Unit // en WAITING→ya gestionado, en otras fases se ignora
        }
    }

    /** Suma de puntos por rapidez de cada ronda válida. */
    override fun calculateScore(): Int =
        reactions.sumOf { (SCORE_CAP - it).coerceAtLeast(0L) }.toInt()

    /** Precisión = rondas válidas / intentos totales (penaliza los "too soon"). */
    override fun currentAccuracy(): Double {
        val attempts = reactions.size + tooSoonCount
        return if (attempts == 0) 100.0 else (reactions.size.toDouble() / attempts * 100)
    }

    /**
     * Récord = mejor (menor) reacción en ms; null si no hubo ninguna válida.
     * Ojo: la dirección "menor = mejor" la conoce la app ([GameProgressions]); aquí
     * se reporta el valor crudo.
     */
    override fun reachedMetric(): Int? = reactions.minOrNull()?.toInt()

    private companion object {
        const val SCORE_CAP = 600L      // reacción (ms) a partir de la cual no hay puntos
        const val ROUND_PAUSE_MS = 900L
    }
}
