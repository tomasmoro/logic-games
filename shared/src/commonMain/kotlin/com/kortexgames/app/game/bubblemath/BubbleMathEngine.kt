package com.kortexgames.app.game.bubblemath

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
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

    /**
     * Ha entrado la **nube de ecuación**: el reto relámpago que aparece cada 20 s de
     * juego activo y que devuelve una vida si se resuelve a tiempo.
     *
     * Entra siempre **entre rondas**, al resolverse la burbuja que estaba cayendo, y
     * nunca a mitad de una caída. El porqué: la nube tiene su propio cronómetro de
     * pocos segundos y sus fichas ocupan justo la zona del objetivo; interrumpir una
     * burbuja en el aire partía la ronda en dos y convertía una recompensa en un
     * castigo. Al cerrarse arranca la ronda siguiente con normalidad.
     */
    EQUATION_CLOUD,

    /**
     * Se acabaron las vidas pero aún NO se ha finalizado: se ofrece al jugador
     * **revivir viendo un anuncio** (una vida extra). El bucle está detenido y el
     * reloj de la ronda congelado; se sale de aquí por [grantRevive] (continúa) o
     * [declineRevive] (fin de partida real). Solo se ofrece una vez por partida.
     */
    REVIVE_OFFER,

    /** Se acabaron las vidas (sin revivir); se muestra el resultado. */
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
 * Datos del último "estallido" de una burbuja, para que la UI dibuje las chispas
 * exactamente donde estaba la burbuja al explotar. Es un dato **derivado y de un solo
 * uso**: se regenera con cada evento ([BubbleMathState.eventId]) y la UI lo consume
 * una vez (igual patrón que el destello de feedback).
 *
 * @property x posición horizontal fraccional (0..1) del centro de la burbuja.
 * @property y posición vertical fraccional (0 = arriba, 1 = suelo) al explotar.
 * @property colorId id de la burbuja; la UI deriva su color de la paleta de burbujas.
 * @property success true si era la burbuja correcta (libera MÁS chispas); false si
 *           fue un fallo (menos chispas, tinte de error).
 */
data class BubbleBurst(
    val x: Float,
    val y: Float,
    val colorId: Int,
    val success: Boolean,
)

/** Cómo terminó una nube de ecuación (lo que la UI anuncia al cerrarse). */
enum class CloudOutcome {
    /** Resuelta y el jugador había perdido vidas: se le devuelve una. */
    LIFE_GAINED,

    /**
     * Resuelta con las vidas al máximo. La vida solo se da "si el usuario la
     * necesita", así que en ese caso la recompensa se convierte en puntos: resolver
     * bien nunca debe sentirse como un premio vacío.
     */
    BONUS_POINTS,

    /** Se agotó el tiempo. **Sin penalización**: la nube es un extra, no una trampa. */
    FAILED,
}

/**
 * Estado de la nube de ecuación mientras está en pantalla.
 *
 * @property placed id de la ficha colocada en cada hueco (null = hueco vacío), en
 *   orden de lectura; misma indexación que [EquationPuzzle.solution].
 * @property remainingMs tiempo que queda para resolverla. Lo refresca el motor cada
 *   frame para que la UI pinte una cuenta atrás continua.
 * @property outcome null mientras se juega; al resolverse queda fijado durante el
 *   breve compás en que se anuncia el resultado antes de que la nube se cierre.
 * @property wrongTick se incrementa con cada combinación fallida; la UI lo observa
 *   para disparar UNA vez el temblor/destello rojo (mismo patrón que `eventId`).
 * @property bonusPoints puntos concedidos al resolverla con las vidas al máximo (0
 *   en cualquier otro caso). Viaja en el estado para que la UI pueda anunciarlos sin
 *   conocer las constantes de puntuación del motor.
 */
data class EquationCloudUi(
    val puzzle: EquationPuzzle,
    val placed: List<Int?>,
    val remainingMs: Long,
    val totalMs: Long,
    val outcome: CloudOutcome? = null,
    val wrongTick: Int = 0,
    val bonusPoints: Int = 0,
) {
    /** Fracción de tiempo restante (1 = recién aparecida, 0 = se agotó). */
    val timeFraction: Float get() = (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)

    /** Ficha colocada en el hueco [index], o null si sigue vacío. */
    fun tokenAt(index: Int): EquationToken? =
        placed.getOrNull(index)?.let { id -> puzzle.options.firstOrNull { it.id == id } }
}

/**
 * Estado de UI del juego.
 *
 * @property cloud nube de ecuación en curso, o null si no hay ninguna (ver
 *   [BubblePhase.EQUATION_CLOUD]).
 * @property combo aciertos consecutivos; a más combo, más multiplicador de puntos.
 * @property eventId contador que se incrementa en cada acierto/fallo/escape; la UI
 *           lo observa para disparar UNA vez el destello de feedback (evita
 *           reproducirlo en cada recomposición o al recalcular posiciones).
 * @property lastBurst datos del estallido del último toque (acierto/fallo) para las
 *           chispas; null tras un escape (la burbuja se fue por abajo, no explota).
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
    val lastBurst: BubbleBurst? = null,
    val cloud: EquationCloudUi? = null,
) {
    companion object {
        const val MAX_LIVES = 3
    }
}

/**
 * Motor de "Burbujas de Cálculo".
 *
 * **Partida infinita**: no hay última ronda ni condición de victoria; se juega
 * hasta quedarse sin vidas y la marca es hasta dónde se llegó. La dificultad sigue
 * subiendo indefinidamente por el eje cognitivo (ver [BubbleMathGenerator]).
 *
 * Bucle de juego: un [loopJob] recalcula ~60 veces/s la posición de cada burbuja
 * en función del tiempo real transcurrido en la ronda ([roundElapsedMs]), de modo
 * que la caída es **independiente de la tasa de refresco** y las pausas no la
 * adelantan. La velocidad de caída sube durante las primeras rondas y luego se
 * congela (ver [BubbleMathGenerator.fallDurationMs]): a partir de ahí la dificultad
 * la aportan las cuentas, no el reloj.
 *
 * Reglas:
 *  - **Acierto** (tocar la burbuja objetivo): suma puntos —más cuanto más arriba se
 *    explota y mayor sea el combo—, sube el combo y pasa a la siguiente ronda.
 *  - **Fallo** (tocar un distractor): resta una vida, rompe el combo y explota ese
 *    distractor; la ronda continúa (aún puedes acertar).
 *  - **Se escapó** (la burbuja objetivo llega al suelo): resta una vida, rompe el
 *    combo y pasa a la siguiente ronda.
 *  - **Nube de ecuación**: cada [CLOUD_INTERVAL_MS] de juego activo entra una
 *    ecuación incompleta con [CLOUD_SOLVE_MS] para resolverla; acertar devuelve una
 *    vida (o da puntos si ya se tenían todas). Fallar no cuesta nada.
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

    // ¿Ya se ofreció (y usó/rechazó) el revivir por anuncio en esta partida? El trato
    // de "segunda oportunidad" se ofrece UNA sola vez: al perder de nuevo, fin directo.
    private var reviveOffered = false

    // Cronómetro propio de la ronda (para la física de caída), consciente de pausas.
    private var roundMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var roundAccumMs: Long = 0
    private var fallDurationMs: Long = BubbleMathGenerator.fallDurationMs(1)

    // --- Nube de ecuación ---------------------------------------------------
    // Cronómetro propio de la nube (los segundos para resolverla), con la misma
    // mecánica consciente de pausas que el de la ronda.
    private var cloudMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var cloudAccumMs: Long = 0

    // Instante (en tiempo de nube) en que debe cerrarse tras anunciar el resultado.
    private var cloudCloseAtMs: Long = Long.MAX_VALUE

    // Instante (en tiempo de nube) del último intento fallido, para borrar las fichas
    // colocadas cuando termine su destello rojo y poder reintentar.
    private var cloudWrongAtMs: Long? = null

    // Juego activo acumulado desde la última nube: solo corre mientras las burbujas
    // caen (ni pausas, ni la propia nube, ni la transición entre rondas cuentan).
    private var playedSinceCloudMs: Long = 0

    private var loopJob: Job? = null
    private var transitionJob: Job? = null
    private var cloudJob: Job? = null

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
        reviveOffered = false
        playedSinceCloudMs = 0
        cloudMark = null
        cloudAccumMs = 0
        cloudWrongAtMs = null
        cloudJob?.cancel()
        _state.value = BubbleMathState()
        startRound()
    }

    override fun onPause() {
        // Congela el reloj de la ronda y detiene el bucle: al reanudar continúa
        // exactamente donde estaba (sin "teletransportar" las burbujas). También
        // cancela la transición entre rondas para que no arranque una ronda estando
        // en segundo plano (quedaría marcada como pendiente y se relanza al volver).
        freezeRoundClock()
        loopJob?.cancel()
        transitionJob?.cancel()
        // La nube tiene su propio cronómetro: se congela igual, o el jugador volvería
        // de segundo plano con el reto ya perdido.
        cloudAccumMs += cloudMark?.elapsedNow()?.inWholeMilliseconds ?: 0
        cloudMark = null
        cloudJob?.cancel()
    }

    override fun onResume() {
        when (_state.value.phase) {
            // Con una nube en pantalla, lo que se reanuda es SU cuenta atrás: las
            // burbujas siguen congeladas hasta que la nube se cierre.
            BubblePhase.EQUATION_CLOUD -> {
                cloudMark = TimeSource.Monotonic.markNow()
                launchCloudLoop()
            }

            BubblePhase.PLAYING -> {
                // Prioridad: si había una ronda encolada, se reprograma su cuenta atrás.
                if (nextRoundPending) {
                    scheduleNextRound()
                    return
                }
                if (_state.value.bubbles.isEmpty()) return
                roundMark = TimeSource.Monotonic.markNow()
                launchLoop()
            }

            else -> Unit
        }
    }

    /** Detiene el cronómetro de la ronda acumulando lo transcurrido hasta ahora. */
    private fun freezeRoundClock() {
        roundAccumMs += roundMark?.elapsedNow()?.inWholeMilliseconds ?: 0
        roundMark = null
    }

    /** Prepara y lanza una ronda nueva (genera burbujas y arranca la caída). */
    private fun startRound() {
        val round = _state.value.round + 1
        val spec = BubbleMathGenerator.generateRound(round, random)
        val n = spec.options.size
        fallDurationMs = BubbleMathGenerator.fallDurationMs(round)

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
            // Marca de frame para medir el juego activo (el reloj de la nube): se mide
            // por deltas del propio bucle, así se detiene solo cuando el bucle se
            // detiene (pausa, nube, transición entre rondas).
            var frameMark = TimeSource.Monotonic.markNow()
            while (_state.value.phase == BubblePhase.PLAYING) {
                playedSinceCloudMs += frameMark.elapsedNow().inWholeMilliseconds
                frameMark = TimeSource.Monotonic.markNow()

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
     * ¿Debe entrar una nube de ecuación al cerrar la ronda actual? El intervalo se
     * mide en juego activo, pero la nube **nunca interrumpe una burbuja en el aire**:
     * espera a que la ronda se resuelva (acierto o escape). Cortar una caída a medias
     * dejaba al jugador con el cálculo hecho a medias y la ronda "partida" al volver.
     */
    private fun cloudDue(): Boolean = playedSinceCloudMs >= CLOUD_INTERVAL_MS

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
                // Estallido "generoso" (acierto) en el sitio exacto de la burbuja.
                lastBurst = BubbleBurst(bubble.x, bubble.y.coerceIn(0f, 1f), bubble.id, success = true),
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
                // Estallido "pobre" (fallo): menos chispas y tinte de error.
                lastBurst = BubbleBurst(bubble.x, bubble.y.coerceIn(0f, 1f), bubble.id, success = false),
            )
        }
        if (lives <= 0) endOrOfferRevive()
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
                // Un escape no "explota" (la burbuja se fue por abajo): sin chispas.
                lastBurst = null,
            )
        }
        if (lives <= 0) endOrOfferRevive() else scheduleNextRound()
    }

    /**
     * Pausa breve entre rondas para que se lea el feedback y, después, o bien entra la
     * **nube de ecuación** (si ya toca) o bien arranca la ronda siguiente. Este es el
     * único punto donde nace una nube: así siempre aparece con el tablero limpio, entre
     * una burbuja resuelta y la siguiente.
     */
    private fun scheduleNextRound() {
        transitionJob?.cancel()
        nextRoundPending = true
        transitionJob = scope.launch {
            delay(ROUND_GAP_MS)
            nextRoundPending = false
            if (_state.value.phase != BubblePhase.PLAYING) return@launch
            if (cloudDue()) startCloud() else startRound()
        }
    }

    // ------------------------------------------------------------------------
    // Nube de ecuación
    // ------------------------------------------------------------------------

    /**
     * Hace entrar la nube. Solo la llama [scheduleNextRound], con la ronda ya cerrada:
     * el tablero está vacío, así que no hay física que congelar más allá de parar el
     * reloj de la ronda por higiene.
     */
    private fun startCloud() {
        freezeRoundClock()
        loopJob?.cancel()
        val puzzle = EquationCloudGenerator.generate(_state.value.round, random)
        cloudAccumMs = 0
        cloudMark = TimeSource.Monotonic.markNow()
        cloudCloseAtMs = Long.MAX_VALUE
        cloudWrongAtMs = null
        // Aviso sonoro y háptico: la nube irrumpe y el reloj ya corre; hay que mirarla.
        audio.playSound(SoundEffect.TIMER_TICK)
        audio.hapticFeedback(HapticFeedback.MEDIUM)
        _state.update {
            it.copy(
                phase = BubblePhase.EQUATION_CLOUD,
                cloud = EquationCloudUi(
                    puzzle = puzzle,
                    placed = List(puzzle.blankCount) { null },
                    remainingMs = CLOUD_SOLVE_MS,
                    totalMs = CLOUD_SOLVE_MS,
                ),
            )
        }
        launchCloudLoop()
    }

    /** Tiempo activo de la nube (excluye pausas), en ms. */
    private fun cloudElapsedMs(): Long =
        cloudAccumMs + (cloudMark?.elapsedNow()?.inWholeMilliseconds ?: 0)

    /**
     * Bucle de la nube: refresca la cuenta atrás, limpia el destello de un intento
     * fallido para permitir reintentar y, cuando el reto ya está resuelto, la cierra
     * tras el compás en que se anuncia el resultado. Va en el mismo reloj pausable que
     * el resto, así que el jugador nunca pierde tiempo estando en segundo plano.
     */
    private fun launchCloudLoop() {
        cloudJob?.cancel()
        cloudJob = scope.launch {
            while (_state.value.phase == BubblePhase.EQUATION_CLOUD) {
                val elapsed = cloudElapsedMs()
                val cloud = _state.value.cloud ?: break

                if (cloud.outcome != null) {
                    if (elapsed >= cloudCloseAtMs) {
                        closeCloud()
                        break
                    }
                } else {
                    val clearWrong = cloudWrongAtMs?.let { elapsed - it >= CLOUD_WRONG_FLASH_MS } == true
                    if (clearWrong) cloudWrongAtMs = null
                    val remaining = (CLOUD_SOLVE_MS - elapsed).coerceAtLeast(0)
                    _state.update { st ->
                        val c = st.cloud ?: return@update st
                        st.copy(
                            cloud = c.copy(
                                remainingMs = remaining,
                                placed = if (clearWrong) List(c.placed.size) { null } else c.placed,
                            ),
                        )
                    }
                    if (remaining == 0L) resolveCloud(CloudOutcome.FAILED)
                }
                delay(FRAME_MS)
            }
        }
    }

    /**
     * El jugador pulsa una ficha de la bandeja: se coloca en el primer hueco libre.
     * Al llenarse el último se comprueba la respuesta comparando **etiquetas** con la
     * solución, lo que es válido porque el generador garantiza que no hay otra
     * combinación correcta posible (ver [EquationPuzzle]).
     */
    fun onCloudTokenTap(tokenId: Int) {
        val cloud = _state.value.cloud ?: return
        if (_state.value.phase != BubblePhase.EQUATION_CLOUD || cloud.outcome != null) return
        if (tokenId in cloud.placed) return // ficha ya colocada: se ignora
        val slot = cloud.placed.indexOfFirst { it == null }
        if (slot < 0) return

        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        val placed = cloud.placed.toMutableList().also { it[slot] = tokenId }
        if (placed.any { it == null }) {
            _state.update { it.copy(cloud = it.cloud?.copy(placed = placed)) }
            return
        }

        val answer = placed.map { id -> cloud.puzzle.options.first { it.id == id }.label }
        if (answer == cloud.puzzle.solution) onCloudSolved(placed) else onCloudWrong(placed)
    }

    /**
     * El jugador pulsa un hueco ya relleno para **sacar** la ficha y recolocarla. Sin
     * esto, un error en el primer hueco de una ecuación de dos obligaría a esperar al
     * fallo completo, tirando segundos de un reto que dura muy poco.
     */
    fun onCloudBlankTap(index: Int) {
        val cloud = _state.value.cloud ?: return
        if (_state.value.phase != BubblePhase.EQUATION_CLOUD || cloud.outcome != null) return
        if (cloud.placed.getOrNull(index) == null) return
        audio.playSound(SoundEffect.TAP)
        val placed = cloud.placed.toMutableList().also { it[index] = null }
        _state.update { it.copy(cloud = it.cloud?.copy(placed = placed)) }
    }

    /**
     * Respuesta correcta: **devuelve una vida si al jugador le falta alguna**; si las
     * tiene todas, la recompensa se paga en puntos (ver [CloudOutcome.BONUS_POINTS]).
     */
    private fun onCloudSolved(placed: List<Int?>) {
        val needsLife = _state.value.lives < BubbleMathState.MAX_LIVES
        _state.update {
            it.copy(
                lives = if (needsLife) it.lives + 1 else it.lives,
                score = if (needsLife) it.score else it.score + CLOUD_BONUS_POINTS,
                cloud = it.cloud?.copy(
                    placed = placed,
                    bonusPoints = if (needsLife) 0 else CLOUD_BONUS_POINTS,
                ),
            )
        }
        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.SUCCESS)
        resolveCloud(if (needsLife) CloudOutcome.LIFE_GAINED else CloudOutcome.BONUS_POINTS)
    }

    /**
     * Combinación equivocada: destello rojo y las fichas vuelven a la bandeja (lo hace
     * el bucle al terminar el destello). **No cuesta vidas ni tiempo extra**: el único
     * castigo son los segundos gastados.
     */
    private fun onCloudWrong(placed: List<Int?>) {
        audio.playSound(SoundEffect.ERROR)
        audio.hapticFeedback(HapticFeedback.ERROR)
        cloudWrongAtMs = cloudElapsedMs()
        _state.update { st ->
            val cloud = st.cloud ?: return@update st
            st.copy(cloud = cloud.copy(placed = placed, wrongTick = cloud.wrongTick + 1))
        }
    }

    /** Fija el desenlace de la nube y programa su cierre tras anunciarlo. */
    private fun resolveCloud(outcome: CloudOutcome) {
        cloudCloseAtMs = cloudElapsedMs() + CLOUD_RESULT_MS
        if (outcome == CloudOutcome.FAILED) {
            // La nube se escapa sin más: un toque seco, no el sonido de error — no ha
            // habido penalización y castigar sonoramente un extra desanima.
            audio.playSound(SoundEffect.TAP)
            audio.hapticFeedback(HapticFeedback.LIGHT)
        }
        _state.update { it.copy(cloud = it.cloud?.copy(outcome = outcome)) }
    }

    /**
     * Cierra la nube y arranca la ronda que estaba esperando. Como la nube solo entra
     * entre rondas, aquí no hay caída que reanudar: se sigue con el juego normal.
     */
    private fun closeCloud() {
        playedSinceCloudMs = 0
        cloudAccumMs = 0
        cloudMark = null
        cloudCloseAtMs = Long.MAX_VALUE
        cloudWrongAtMs = null
        _state.update { it.copy(phase = BubblePhase.PLAYING, cloud = null) }
        startRound()
    }

    /**
     * Sin vidas: si aún no se ofreció, entra en [BubblePhase.REVIVE_OFFER] (una
     * segunda oportunidad viendo un anuncio); si ya se ofreció, termina la partida.
     */
    private fun endOrOfferRevive() {
        if (reviveOffered) {
            gameOver()
            return
        }
        // Detiene la física y congela el reloj de la ronda: mientras el jugador decide
        // (y ve el anuncio) las burbujas no deben "adelantarse" al reanudar.
        loopJob?.cancel()
        transitionJob?.cancel()
        nextRoundPending = false
        freezeRoundClock()
        _state.update { it.copy(phase = BubblePhase.REVIVE_OFFER, bubbles = emptyList()) }
    }

    /**
     * El anuncio concedió la recompensa: repone la vida y **continúa la partida** con
     * una ronda nueva (conservando puntos, ronda y combo reiniciado). Se marca el trato
     * como usado para no volver a ofrecerlo. Sin efecto si no se estaba ofreciendo.
     */
    fun grantRevive() {
        if (_state.value.phase != BubblePhase.REVIVE_OFFER) return
        reviveOffered = true
        _state.update {
            it.copy(phase = BubblePhase.PLAYING, lives = REVIVE_LIVES, combo = 0)
        }
        startRound()
    }

    /**
     * El jugador rechaza la oferta (o el anuncio se cerró / no había): fin de partida
     * real. Sin efecto si no se estaba ofreciendo.
     */
    fun declineRevive() {
        if (_state.value.phase != BubblePhase.REVIVE_OFFER) return
        reviveOffered = true
        gameOver()
    }

    private fun gameOver() {
        loopJob?.cancel()
        transitionJob?.cancel()
        cloudJob?.cancel()
        _state.update { it.copy(phase = BubblePhase.GAME_OVER, bubbles = emptyList(), cloud = null) }
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

    /** Récord = mejor ronda alcanzada antes de perder las vidas; null si ninguna. */
    override fun reachedMetric(): Int? = _state.value.round.takeIf { it > 0 }

    private companion object {
        // --- Física de la caída ---
        // La curva de velocidad (cuánto dura la caída en cada ronda y cuándo deja de
        // acelerar) vive en [BubbleMathGenerator.fallDurationMs], junto al resto de la
        // curva de dificultad, para poder testearla sin arrancar el bucle.
        const val FRAME_MS = 16L          // ~60 fps
        const val MAX_ENTER_DELAY = 0.30f // escalonado de entrada (fracción)
        const val ROUND_GAP_MS = 650L     // pausa entre rondas

        // --- Nube de ecuación ---
        // Cada 20 s de juego ACTIVO (no de reloj de pared: las pausas y la propia nube
        // no cuentan) entra una ecuación incompleta, siempre al cerrarse la ronda en
        // curso. 20 s da tiempo a varias rondas, así que la nube se lee como un respiro
        // con premio y no como una interrupción constante del ritmo de las burbujas.
        const val CLOUD_INTERVAL_MS = 20_000L

        /** Segundos para resolver la ecuación de la nube. */
        const val CLOUD_SOLVE_MS = 7_000L

        // Compás en que se anuncia el desenlace antes de que la nube se cierre.
        const val CLOUD_RESULT_MS = 1_000L

        // Duración del destello rojo de un intento fallido, antes de devolver las
        // fichas a la bandeja para poder reintentar.
        const val CLOUD_WRONG_FLASH_MS = 450L

        // Premio alternativo cuando se resuelve con las vidas al máximo (no hay vida
        // que devolver). Generoso a propósito: cuesta lo mismo acertarla.
        const val CLOUD_BONUS_POINTS = 250

        // --- Revivir por anuncio ---
        // Vidas con las que se continúa tras ver el anuncio. Una sola: es una segunda
        // oportunidad, no un reinicio; sigue siendo tenso.
        const val REVIVE_LIVES = 1

        // --- Puntuación ---
        const val BASE_POINTS = 50
        const val SPEED_BONUS = 100       // bonus máximo por explotar arriba del todo
        const val ROUND_BONUS = 5         // bonus fijo por ronda alcanzada
        const val COMBO_STEP = 0.25f      // +25% de puntos por cada combo encadenado
    }
}
