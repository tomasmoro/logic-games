package com.example.kortexgames.game.bubblemath

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

/** Fase de la partida de Burbujas de Cálculo. */
enum class BubblePhase {
    /** Las burbujas caen y el jugador puede tocarlas. */
    PLAYING,

    /** Se acabaron las vidas; se muestra el resultado. */
    GAME_OVER,
}

/** Resultado del último evento relevante (para el feedback breve de la UI). */
enum class TapResult { CORRECT, WRONG, MISSED }

/**
 * Una burbuja en caída. Su posición vertical [y] es una **fracción 0..1** (0 = línea
 * de aparición arriba, 1 = suelo) que el motor recalcula cada frame a partir del
 * tiempo transcurrido; la UI solo la lee y la mapea a píxeles.
 *
 * @property enterDelay retardo (en fracción de la caída) para escalonar la entrada,
 *           así las burbujas no aparecen todas en una línea perfecta.
 * @property isTarget true si su valor coincide con el objetivo de la ronda (la que
 *           hay que explotar). No se revela visualmente: distinguirlas es el reto.
 */
data class Bubble(
    val id: Int,
    val expr: MathExpression,
    val x: Float,
    val enterDelay: Float,
    val isTarget: Boolean,
    val y: Float = -enterDelay,
)

/**
 * Estado de UI del juego.
 *
 * @property combo aciertos consecutivos; a más combo, más multiplicador de puntos.
 * @property eventId contador que se incrementa en cada acierto/fallo/escape; la UI
 *           lo observa para disparar UNA vez el destello de feedback (evita
 *           reproducirlo en cada recomposición o al recalcular posiciones).
 */
data class BubbleMathState(
    val phase: BubblePhase = BubblePhase.PLAYING,
    val target: Int = 0,
    val bubbles: List<Bubble> = emptyList(),
    val round: Int = 0,
    val score: Int = 0,
    val lives: Int = MAX_LIVES,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val lastResult: TapResult? = null,
    val eventId: Int = 0,
) {
    companion object {
        const val MAX_LIVES = 3
    }
}

/**
 * Motor de "Burbujas de Cálculo".
 *
 * Bucle de juego: un [loopJob] recalcula ~60 veces/s la posición de cada burbuja
 * en función del tiempo real transcurrido en la ronda ([roundElapsedMs]), de modo
 * que la caída es **independiente de la tasa de refresco** y las pausas no la
 * adelantan. La velocidad de caída aumenta con la ronda (curva de dificultad).
 *
 * Reglas:
 *  - **Acierto** (tocar la burbuja objetivo): suma puntos —más cuanto más arriba se
 *    explota y mayor sea el combo—, sube el combo y pasa a la siguiente ronda.
 *  - **Fallo** (tocar un distractor): resta una vida, rompe el combo y explota ese
 *    distractor; la ronda continúa (aún puedes acertar).
 *  - **Se escapó** (la burbuja objetivo llega al suelo): resta una vida, rompe el
 *    combo y pasa a la siguiente ronda.
 *  - Sin vidas ⇒ fin de partida (el motor se autofinaliza vía [finish]).
 *
 * El tiempo de ronda se acumula igual que el cronómetro de [BaseGameEngine] para
 * que las pausas no falseen la física ni el `completion_time_ms`.
 */
class BubbleMathEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<BubbleMathState>(GameIds.BUBBLE_MATH, difficulty, scope, audio) {

    private val _state = MutableStateFlow(BubbleMathState())
    override val state: StateFlow<BubbleMathState> = _state.asStateFlow()

    // Contadores para la precisión final (aciertos / intentos totales).
    private var correctCount = 0
    private var wrongCount = 0
    private var missCount = 0

    private var nextBubbleId = 0

    // Cronómetro propio de la ronda (para la física de caída), consciente de pausas.
    private var roundMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var roundAccumMs: Long = 0
    private var fallDurationMs: Long = BASE_FALL_MS

    private var loopJob: Job? = null
    private var transitionJob: Job? = null

    // ¿Hay una ronda encolada (pausa entre rondas)? Se usa para reprogramarla al
    // reanudar: el `viewModelScope` no se congela en segundo plano, así que la
    // pausa debe cancelar la transición y volver a lanzarla al volver al primer plano.
    private var nextRoundPending = false

    override fun onStart() {
        correctCount = 0
        wrongCount = 0
        missCount = 0
        nextBubbleId = 0
        nextRoundPending = false
        _state.value = BubbleMathState()
        startRound()
    }

    override fun onPause() {
        // Congela el reloj de la ronda y detiene el bucle: al reanudar continúa
        // exactamente donde estaba (sin "teletransportar" las burbujas). También
        // cancela la transición entre rondas para que no arranque una ronda estando
        // en segundo plano (quedaría marcada como pendiente y se relanza al volver).
        roundAccumMs += roundMark?.elapsedNow()?.inWholeMilliseconds ?: 0
        roundMark = null
        loopJob?.cancel()
        transitionJob?.cancel()
    }

    override fun onResume() {
        if (_state.value.phase != BubblePhase.PLAYING) return
        // Prioridad: si había una ronda encolada, se reprograma su cuenta atrás.
        if (nextRoundPending) {
            scheduleNextRound()
            return
        }
        if (_state.value.bubbles.isEmpty()) return
        roundMark = TimeSource.Monotonic.markNow()
        launchLoop()
    }

    /** Prepara y lanza una ronda nueva (genera burbujas y arranca la caída). */
    private fun startRound() {
        val round = _state.value.round + 1
        val spec = BubbleMathGenerator.generateRound(round, random)
        val n = spec.options.size
        fallDurationMs = (BASE_FALL_MS - (round - 1) * FALL_STEP_MS).coerceAtLeast(MIN_FALL_MS)

        val bubbles = spec.options.mapIndexed { i, expr ->
            // Reparte en columnas con jitter para que no queden alineadas.
            val slot = (i + 0.5f) / n
            val jitter = (random.nextFloat() - 0.5f) * (0.7f / n)
            Bubble(
                id = nextBubbleId++,
                expr = expr,
                x = (slot + jitter).coerceIn(0.08f, 0.92f),
                enterDelay = random.nextFloat() * MAX_ENTER_DELAY,
                isTarget = expr.value == spec.target,
            )
        }

        roundAccumMs = 0
        roundMark = TimeSource.Monotonic.markNow()
        _state.update {
            it.copy(
                round = round,
                target = spec.target,
                bubbles = bubbles,
                lastResult = null,
            )
        }
        launchLoop()
    }

    /** Tiempo activo de la ronda (excluye pausas), en ms. */
    private fun roundElapsedMs(): Long =
        roundAccumMs + (roundMark?.elapsedNow()?.inWholeMilliseconds ?: 0)

    /**
     * Bucle de física: avanza [y] de cada burbuja y detecta cuándo la objetivo
     * toca el suelo. Los distractores que llegan al suelo simplemente desaparecen
     * (no penalizan: solo importa la burbuja correcta).
     */
    private fun launchLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (_state.value.phase == BubblePhase.PLAYING) {
                val frac = roundElapsedMs().toFloat() / fallDurationMs
                val current = _state.value.bubbles
                if (current.isEmpty()) break

                val updated = current.map { it.copy(y = frac - it.enterDelay) }
                val targetHitFloor = updated.any { it.isTarget && it.y >= 1f }

                if (targetHitFloor) {
                    onTargetMissed()
                    break
                }

                // Descarta distractores que ya cruzaron el suelo (sin castigo).
                val visible = updated.filterNot { !it.isTarget && it.y >= 1f }
                _state.update { it.copy(bubbles = visible) }
                delay(FRAME_MS)
            }
        }
    }

    /**
     * Único input del juego: el jugador toca una burbuja. Distingue acierto de
     * fallo por [Bubble.isTarget] (ya calculado; no recalcula operaciones aquí).
     */
    fun onBubbleTap(id: Int) {
        if (_state.value.phase != BubblePhase.PLAYING) return
        val bubble = _state.value.bubbles.firstOrNull { it.id == id } ?: return

        if (bubble.isTarget) onCorrect(bubble) else onWrong(bubble)
    }

    private fun onCorrect(bubble: Bubble) {
        correctCount++
        loopJob?.cancel()
        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.SUCCESS)

        val combo = _state.value.combo + 1
        // Cuanto más arriba se explota (menor y) más bonus de rapidez; el combo
        // multiplica la recompensa para premiar rachas sin fallos.
        val heightBonus = ((1f - bubble.y).coerceIn(0f, 1f) * SPEED_BONUS).toInt()
        val multiplier = 1f + (combo - 1) * COMBO_STEP
        val gained = (((BASE_POINTS + heightBonus) * multiplier).toInt() + _state.value.round * ROUND_BONUS)

        _state.update {
            it.copy(
                score = it.score + gained,
                combo = combo,
                bestCombo = maxOf(it.bestCombo, combo),
                bubbles = emptyList(),
                lastResult = TapResult.CORRECT,
                eventId = it.eventId + 1,
            )
        }
        scheduleNextRound()
    }

    private fun onWrong(bubble: Bubble) {
        wrongCount++
        audio.playSound(SoundEffect.ERROR)
        audio.hapticFeedback(HapticFeedback.ERROR)
        val lives = _state.value.lives - 1
        _state.update {
            it.copy(
                lives = lives,
                combo = 0,
                // Explota el distractor tocado; la ronda sigue si quedan vidas.
                bubbles = it.bubbles.filterNot { b -> b.id == bubble.id },
                lastResult = TapResult.WRONG,
                eventId = it.eventId + 1,
            )
        }
        if (lives <= 0) gameOver()
    }

    private fun onTargetMissed() {
        missCount++
        audio.playSound(SoundEffect.ERROR)
        audio.hapticFeedback(HapticFeedback.ERROR)
        val lives = _state.value.lives - 1
        _state.update {
            it.copy(
                lives = lives,
                combo = 0,
                bubbles = emptyList(),
                lastResult = TapResult.MISSED,
                eventId = it.eventId + 1,
            )
        }
        if (lives <= 0) gameOver() else scheduleNextRound()
    }

    /** Pausa breve entre rondas para que se lea el feedback, y arranca la siguiente. */
    private fun scheduleNextRound() {
        transitionJob?.cancel()
        nextRoundPending = true
        transitionJob = scope.launch {
            delay(ROUND_GAP_MS)
            nextRoundPending = false
            if (_state.value.phase == BubblePhase.PLAYING) startRound()
        }
    }

    private fun gameOver() {
        loopJob?.cancel()
        transitionJob?.cancel()
        _state.update { it.copy(phase = BubblePhase.GAME_OVER, bubbles = emptyList()) }
        // Autofinaliza: publica el GameResult en `outcome` para que lo guarde el VM.
        finish()
    }

    /** El puntaje del juego es el acumulado durante la partida. */
    override fun calculateScore(): Int = _state.value.score

    /** Precisión = aciertos / intentos (fallos y escapes cuentan como intento). */
    override fun currentAccuracy(): Double {
        val attempts = correctCount + wrongCount + missCount
        return if (attempts == 0) 100.0 else correctCount.toDouble() / attempts * 100
    }

    private companion object {
        // --- Física de la caída ---
        const val FRAME_MS = 16L          // ~60 fps
        const val BASE_FALL_MS = 7000L    // tiempo de caída en la ronda 1
        const val FALL_STEP_MS = 280L     // aceleración por ronda
        const val MIN_FALL_MS = 2600L     // caída más rápida posible
        const val MAX_ENTER_DELAY = 0.30f // escalonado de entrada (fracción)
        const val ROUND_GAP_MS = 650L     // pausa entre rondas

        // --- Puntuación ---
        const val BASE_POINTS = 50
        const val SPEED_BONUS = 100       // bonus máximo por explotar arriba del todo
        const val ROUND_BONUS = 5         // bonus fijo por ronda alcanzada
        const val COMBO_STEP = 0.25f      // +25% de puntos por cada combo encadenado
    }
}
