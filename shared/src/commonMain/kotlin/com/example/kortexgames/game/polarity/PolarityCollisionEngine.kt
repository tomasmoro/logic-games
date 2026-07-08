package com.example.kortexgames.game.polarity

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Partícula individual de Atracción Geométrica.
 *
 * @property mass masa relativa: valores altos aceleran menos en curvas magnéticas,
 *            pero suelen spawnear con mayor velocidad inicial para subir presión.
 * @property colorIndex índice de 0..3 que debe coincidir con el sector del círculo.
 */
data class PolarityParticle(
    val id: Long,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val mass: Float,
    val colorIndex: Int,
    val magnetic: Boolean,
    val radius: Float,
)

/**
 * Estado de UI del juego Atracción Geométrica.
 *
 * El estado conserva geometría de viewport para mantener el motor desacoplado del
 * framework visual: la pantalla reporta tamaño y el motor calcula spawns/física con
 * ese dato, evitando usar APIs de plataforma dentro del engine.
 */
data class PolarityCollisionState(
    val rotationRad: Float = 0f,
    val particles: List<PolarityParticle> = emptyList(),
    val viewportWidthPx: Float = 0f,
    val viewportHeightPx: Float = 0f,
    val score: Int = 0,
    val caught: Int = 0,
    val missed: Int = 0,
    val remainingMs: Long = DEFAULT_ROUND_DURATION_MS,
)

/**
 * Motor de "Atracción Geométrica" (Polarity Collision).
 *
 * Reglas clave:
 *  - El jugador rota un círculo de 4 sectores de color.
 *  - Partículas caen desde bordes hacia el centro con masas/velocidades distintas.
 *  - Partículas magnéticas curvan trayectorias cercanas, obligando anticipación.
 *  - Una partícula se captura solo si su color coincide con el sector impactado.
 *
 * Curva de onboarding + escalado continuo: la ronda arranca deliberadamente más
 * amable para enseñar la lectura espacial antes de saturar. Al inicio salen un 50%
 * menos de asteroides y viajan un 25% más despacio; desde ahí la presión aumenta de
 * forma gradual durante toda la cuenta atrás subiendo spawn, velocidad, gravedad y
 * probabilidad de magnetismo. La partida termina SOLO cuando se agota el tiempo.
 *
 * El loop de render corre en Compose (`withFrameNanos`), pero la física y scoring
 * viven aquí para que la lógica sea portable/testeable y coherente con MVI.
 */
class PolarityCollisionEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<PolarityCollisionState>(GameIds.POLARITY_COLLISION, difficulty, scope, audio) {

    private val _state = MutableStateFlow(PolarityCollisionState())
    override val state: StateFlow<PolarityCollisionState> = _state.asStateFlow()

    private var lastFrameNanos: Long? = null
    private var spawnAccumulatorSec = 0f
    private var nextParticleId = 1L

    override fun onStart() {
        lastFrameNanos = null
        spawnAccumulatorSec = 0f
        nextParticleId = 1L
        _state.value = PolarityCollisionState()
    }

    override fun onPause() {
        // Al reanudar descartamos el delta acumulado para evitar un salto de física.
        lastFrameNanos = null
    }

    /** Actualiza el tamaño útil del juego para spawns y colisiones. */
    fun updateViewport(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        _state.update {
            if (it.viewportWidthPx == widthPx && it.viewportHeightPx == heightPx) it
            else it.copy(viewportWidthPx = widthPx, viewportHeightPx = heightPx)
        }
    }

    /** Aplica una rotación incremental al hexágono (en radianes). */
    fun rotateBy(deltaRad: Float) {
        if (status.value != GameStatus.RUNNING) return
        _state.update { it.copy(rotationRad = normalizeAngle(it.rotationRad + deltaRad)) }
    }

    /**
     * Avanza la simulación usando timestamp absoluto de frame (`withFrameNanos`).
     *
     * @param frameNanos tiempo monotónico del frame actual.
     */
    fun onFrame(frameNanos: Long) {
        if (status.value != GameStatus.RUNNING) return

        val current = _state.value
        if (current.viewportWidthPx <= 0f || current.viewportHeightPx <= 0f) return

        val previousFrame = lastFrameNanos
        lastFrameNanos = frameNanos
        if (previousFrame == null) return

        val dtSec = ((frameNanos - previousFrame) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        if (dtSec == 0f) return

        val updated = step(current, dtSec)
        _state.value = updated

        if (updated.remainingMs <= 0L && status.value == GameStatus.RUNNING) {
            finish()
        }
    }

    override fun calculateScore(): Int {
        val state = _state.value
        val survivalBonus = ((state.remainingMs / 1_000L) * 12L).toInt().coerceAtLeast(0)
        return (state.score + survivalBonus).coerceAtLeast(0)
    }

    override fun currentAccuracy(): Double {
        val attempts = _state.value.caught + _state.value.missed
        return if (attempts == 0) 100.0 else (_state.value.caught.toDouble() / attempts.toDouble() * 100.0)
    }

    /** Récord = mejor puntaje de la corrida; null si no sumó puntos. */
    override fun reachedMetric(): Int? = calculateScore().takeIf { it > 0 }

    private fun step(state: PolarityCollisionState, dtSec: Float): PolarityCollisionState {
        val progression = roundProgress(state)
        val dynamicDifficulty = dynamicDifficultyMultiplier(progression)
        val baseSpawnInterval = (BASE_SPAWN_INTERVAL_SEC - (difficulty.coerceIn(1, 5) - 1) * 0.08f)
            .coerceAtLeast(MIN_SPAWN_INTERVAL_SEC)
        val spawnInterval = lerp(
            start = baseSpawnInterval * INITIAL_SPAWN_INTERVAL_MULTIPLIER,
            end = (baseSpawnInterval * END_SPAWN_INTERVAL_MULTIPLIER / dynamicDifficulty).coerceAtLeast(MIN_SPAWN_INTERVAL_SEC),
            progress = progression,
        )

        var particles = state.particles
        spawnAccumulatorSec += dtSec
        while (spawnAccumulatorSec >= spawnInterval) {
            spawnAccumulatorSec -= spawnInterval
            particles = particles + spawnParticle(state, progression)
        }

        val centerX = state.viewportWidthPx * 0.5f
        val centerY = state.viewportHeightPx * 0.5f
        val hexRadius = min(state.viewportWidthPx, state.viewportHeightPx) * 0.18f
        val catchRadius = hexRadius * 0.82f

        var scoreDelta = 0
        var caughtDelta = 0
        var missedDelta = 0
        val nextParticles = ArrayList<PolarityParticle>(particles.size)

        for (particle in particles) {
            var ax = 0f
            var ay = 0f

            val toCenterX = centerX - particle.x
            val toCenterY = centerY - particle.y
            val centerDistance = hypot(toCenterX, toCenterY).coerceAtLeast(1f)
            val centerNx = toCenterX / centerDistance
            val centerNy = toCenterY / centerDistance
            val gravityAccel = BASE_GRAVITY_ACCEL * dynamicDifficulty / particle.mass
            ax += centerNx * gravityAccel
            ay += centerNy * gravityAccel

            for (magnet in particles) {
                if (!magnet.magnetic || magnet.id == particle.id) continue
                val mx = magnet.x - particle.x
                val my = magnet.y - particle.y
                val md = hypot(mx, my)
                if (md <= 1f || md >= MAGNET_RADIUS_PX) continue
                val proximity = 1f - (md / MAGNET_RADIUS_PX)
                val nx = mx / md
                val ny = my / md
                val tangentX = -ny
                val tangentY = nx
                val curveAccel = MAGNETIC_CURVE_ACCEL * dynamicDifficulty * proximity * (if (particle.magnetic) 1.25f else 1f) / particle.mass
                ax += tangentX * curveAccel
                ay += tangentY * curveAccel
            }

            val vx = particle.vx + ax * dtSec
            val vy = particle.vy + ay * dtSec
            val px = particle.x + vx * dtSec
            val py = particle.y + vy * dtSec

            val afterStepDistance = hypot(px - centerX, py - centerY)
            if (afterStepDistance <= catchRadius) {
                val impactAngle = atan2(py - centerY, px - centerX)
                val targetSector = sectorFromAngle(impactAngle, state.rotationRad)
                if (targetSector == particle.colorIndex) {
                    caughtDelta++
                    val speed = hypot(vx, vy)
                    scoreDelta += (80f * particle.mass + speed * 0.14f).toInt() + if (particle.magnetic) 35 else 0
                    audio.playSound(SoundEffect.SUCCESS)
                    audio.hapticFeedback(HapticFeedback.LIGHT)
                } else {
                    missedDelta++
                    scoreDelta -= MISMATCH_PENALTY
                    audio.playSound(SoundEffect.ERROR)
                    audio.hapticFeedback(HapticFeedback.ERROR)
                }
                continue
            }

            val margin = OUTSIDE_MARGIN_PX
            val outOfBounds = px < -margin || py < -margin ||
                px > state.viewportWidthPx + margin || py > state.viewportHeightPx + margin
            if (!outOfBounds) {
                nextParticles += particle.copy(x = px, y = py, vx = vx, vy = vy)
            }
        }

        return state.copy(
            particles = nextParticles,
            score = (state.score + scoreDelta).coerceAtLeast(0),
            caught = state.caught + caughtDelta,
            missed = state.missed + missedDelta,
            remainingMs = (state.remainingMs - (dtSec * 1_000f).toLong()).coerceAtLeast(0L),
        )
    }

    private fun spawnParticle(state: PolarityCollisionState, progression: Float): PolarityParticle {
        val w = state.viewportWidthPx
        val h = state.viewportHeightPx
        val cx = w * 0.5f
        val cy = h * 0.5f
        val margin = SPAWN_MARGIN_PX

        val (x, y) = when (random.nextInt(4)) {
            0 -> random.nextFloat() * w to -margin
            1 -> w + margin to random.nextFloat() * h
            2 -> random.nextFloat() * w to h + margin
            else -> -margin to random.nextFloat() * h
        }

        val toCenterX = cx - x
        val toCenterY = cy - y
        val baseAngle = atan2(toCenterY, toCenterX)
        val jitter = random.nextFloat() * SPAWN_ANGLE_JITTER_RAD * 2f - SPAWN_ANGLE_JITTER_RAD
        val angle = baseAngle + jitter

        val mass = random.nextFloat() * 1.5f + 0.65f
        val baseSpeed = (PARTICLE_BASE_SPEED_PX + random.nextFloat() * PARTICLE_SPEED_VARIANCE_PX) / mass
        val difficultySpeed = baseSpeed * (1f + (difficulty.coerceIn(1, 5) - 1) * 0.07f)
        val speed = difficultySpeed * lerp(
            start = INITIAL_SPEED_MULTIPLIER,
            end = END_SPEED_MULTIPLIER,
            progress = progression,
        )

        val magneticProbability = lerp(
            start = INITIAL_MAGNETIC_PROBABILITY + (difficulty.coerceIn(1, 5) - 1) * 0.02f,
            end = FINAL_MAGNETIC_PROBABILITY + (difficulty.coerceIn(1, 5) - 1) * 0.03f,
            progress = progression,
        ).coerceAtMost(MAX_MAGNETIC_PROBABILITY)
        val magnetic = random.nextFloat() <= magneticProbability

        return PolarityParticle(
            id = nextParticleId++,
            x = x,
            y = y,
            vx = cos(angle) * speed,
            vy = sin(angle) * speed,
            mass = mass,
            colorIndex = random.nextInt(COLOR_SECTORS),
            magnetic = magnetic,
            // Asteroides 3x para priorizar lectura visual sobre precisión milimétrica.
            radius = if (magnetic) 39f else 30f,
        )
    }

    private fun sectorFromAngle(angleRad: Float, rotationRad: Float): Int {
        val sectorSize = (2.0 * PI / COLOR_SECTORS).toFloat()
        val aligned = normalizeAnglePositive(angleRad - rotationRad + sectorSize * 0.5f)
        return (aligned / sectorSize).toInt().mod(COLOR_SECTORS)
    }

    private fun normalizeAngle(angleRad: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var angle = angleRad
        while (angle <= -PI.toFloat()) angle += twoPi
        while (angle > PI.toFloat()) angle -= twoPi
        return angle
    }

    private fun normalizeAnglePositive(angleRad: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var angle = angleRad % twoPi
        if (angle < 0f) angle += twoPi
        return angle
    }

    /** Progreso normalizado 0..1 de la ronda actual, útil para rampas suaves. */
    private fun roundProgress(state: PolarityCollisionState): Float {
        val elapsed = (ROUND_DURATION_MS - state.remainingMs).coerceAtLeast(0L)
        return (elapsed.toFloat() / ROUND_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    }

    /** Interpolación lineal explícita para dejar clara la intención de tuning. */
    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    /** Multiplicador de dificultad base + rampa temporal continua. */
    private fun dynamicDifficultyMultiplier(progress: Float): Float {
        val difficultyBase = 1f + (difficulty.coerceIn(1, 5) - 1) * 0.18f
        val timeRamp = lerp(1f, END_DIFFICULTY_MULTIPLIER, progress)
        return difficultyBase * timeRamp
    }

    private companion object {
        const val COLOR_SECTORS = 4
        const val ROUND_DURATION_MS = DEFAULT_ROUND_DURATION_MS

        const val BASE_SPAWN_INTERVAL_SEC = 1.15f
        const val MIN_SPAWN_INTERVAL_SEC = 0.42f
        const val MAX_DT_SEC = 0.05f
        const val INITIAL_SPAWN_INTERVAL_MULTIPLIER = 2f
        const val INITIAL_SPEED_MULTIPLIER = 0.75f
        const val END_SPAWN_INTERVAL_MULTIPLIER = 0.78f
        const val END_SPEED_MULTIPLIER = 1.30f
        const val END_DIFFICULTY_MULTIPLIER = 1.22f
        const val INITIAL_MAGNETIC_PROBABILITY = 0.02f
        const val FINAL_MAGNETIC_PROBABILITY = 0.18f
        const val MAX_MAGNETIC_PROBABILITY = 0.30f

        const val BASE_GRAVITY_ACCEL = 360f
        const val MAGNETIC_CURVE_ACCEL = 820f
        const val MAGNET_RADIUS_PX = 190f

        const val SPAWN_MARGIN_PX = 42f
        const val OUTSIDE_MARGIN_PX = 70f
        const val SPAWN_ANGLE_JITTER_RAD = 0.35f

        const val PARTICLE_BASE_SPEED_PX = 210f
        const val PARTICLE_SPEED_VARIANCE_PX = 240f

        const val MISMATCH_PENALTY = 95
    }
}

private const val DEFAULT_ROUND_DURATION_MS = 30_000L



