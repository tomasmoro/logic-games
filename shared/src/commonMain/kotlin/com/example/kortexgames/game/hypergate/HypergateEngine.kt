package com.example.kortexgames.game.hypergate

import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.physics.FrameClock
import com.example.kortexgames.core.audio.AudioAndHapticManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * # HypergateEngine — motor de física y colisiones (Fase 2)
 *
 * Motor del minijuego **Hypergate**. Reglas:
 *  - Un **escudo central** de dos polaridades ([ShieldState.A]/[ShieldState.B]) que el jugador
 *    conmuta tocando en cualquier parte ([toggleShield]).
 *  - **Proyectiles** que aparecen en el borde y viajan en línea recta al centro exacto, cada uno
 *    cargado con la polaridad que lo absorbe.
 *  - En el impacto (`distancePx <= shieldRadiusPx`): si la polaridad del escudo coincide con la
 *    del proyectil → **absorción** (+puntos); si no → **choque** (penalización). La ronda termina
 *    SOLO al agotarse el tiempo (score-attack), como en *Atracción Geométrica*.
 *
 * ## Decisión del bucle (Tick) y su rendimiento
 * La física corre dirigida por el render (`withFrameNanos` en Compose emite [onFrame]); el motor
 * NO tiene su propio timer de coroutines. Motivos:
 *  - **Coherencia visual**: integrar exactamente una vez por frame elimina el *judder* de
 *    interpolar entre una simulación a ritmo fijo y un render a otro ritmo.
 *  - **Batería**: sin frames (app en fondo/pausada) no hay trabajo; el reloj se congela solo.
 *
 * El `dt` se deriva vía [FrameClock] —el MISMO reloj que usa Polarity— en lugar de duplicar aquí
 * el manejo de timestamps: descarta el primer frame y recorta saltos para que un proyectil rápido
 * no "tunelee" a través del escudo tras un frame largo.
 *
 * ## Por qué polar y O(n) por frame (contraste con Polarity)
 * Polarity integra fuerzas (gravedad + magnetismo) en cartesiano: cada partícula mira a todas las
 * demás → O(n²) por frame, asumible porque las trayectorias curvas son su mecánica. Hypergate NO
 * tiene interacción entre proyectiles: cada uno viaja recto a velocidad constante, así que el
 * movimiento es un simple `distancePx -= speedPx * dt` por proyectil → **O(n) por frame** y sin
 * una sola llamada a `cos/sin` en la física (la trigonometría se paga solo al renderizar, Fase 3).
 * Ese es el trade-off que justifica el modelo polar de [Projectile]: máxima baratura de tick a
 * cambio de renunciar a curvas que este juego no necesita.
 *
 * ## Feedback como eventos (no audio directo)
 * A diferencia de Polarity —que llama al `AudioAndHapticManager` dentro del motor—, aquí cada
 * impacto se emite como [HypergateEffect] semántico por [effects]. Así la física queda 100%
 * portable y testeable (un test afirma "este impacto emitió CRASH" sin plataforma); el ViewModel
 * traduce el efecto a sonido + háptica y lo reenvía a la UI.
 *
 * @param random inyectable para tests deterministas de spawn/colisión.
 */
class HypergateEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<HypergateState>(GameIds.HYPERGATE, difficulty, scope, audio) {

    private val _state = MutableStateFlow(HypergateState())
    override val state: StateFlow<HypergateState> = _state.asStateFlow()

    /**
     * Canal de efectos de impacto (absorción/choque). `BUFFERED` para no perder un evento si el
     * colector va un instante por detrás; se consume una sola vez (semántica one-shot).
     */
    private val _effects = Channel<HypergateEffect>(Channel.BUFFERED)

    /** Flujo de efectos de impacto que el ViewModel colecta para audio/háptica y reenvío a UI. */
    val effects: Flow<HypergateEffect> = _effects.receiveAsFlow()

    private val frameClock = FrameClock(maxDtSec = MAX_DT_SEC)
    private var spawnAccumulatorSec = 0f
    private var nextProjectileId = 1L

    override fun onStart() {
        frameClock.reset()
        spawnAccumulatorSec = 0f
        nextProjectileId = 1L
        // Conserva el viewport ya conocido para no "ciega" la primera ronda si la pantalla ya
        // reportó su tamaño antes de pulsar Comenzar.
        val current = _state.value
        _state.value = HypergateState(
            shieldRadiusPx = current.shieldRadiusPx,
            viewportWidthPx = current.viewportWidthPx,
            viewportHeightPx = current.viewportHeightPx,
        )
    }

    override fun onPause() {
        // Descartar el delta acumulado evita un salto de física al reanudar.
        frameClock.reset()
    }

    /**
     * Actualiza el tamaño útil del juego y recalcula el radio del escudo. El radio se deriva del
     * lado menor para que el anillo quepa y sea idéntico en cualquier orientación.
     */
    fun updateViewport(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val radius = min(widthPx, heightPx) * SHIELD_RADIUS_FACTOR
        _state.update {
            if (it.viewportWidthPx == widthPx && it.viewportHeightPx == heightPx) it
            else it.copy(
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx,
                shieldRadiusPx = radius,
            )
        }
    }

    /**
     * Conmuta la polaridad del escudo (A↔B). Sin coordenadas: el tap es posicional-agnóstico. El
     * micro-rebote táctil al cambiar es responsabilidad del render (spring sobre la escala), no
     * del motor. Ignora toques fuera de RUNNING (antesala/pausa/fin).
     */
    fun toggleShield() {
        if (status.value != GameStatus.RUNNING) return
        _state.update { it.copy(shield = it.shield.toggled()) }
    }

    /**
     * Avanza la simulación con el timestamp absoluto del frame (`withFrameNanos`).
     *
     * @param frameNanos tiempo monotónico del frame actual.
     */
    fun onFrame(frameNanos: Long) {
        if (status.value != GameStatus.RUNNING) return

        val current = _state.value
        if (current.viewportWidthPx <= 0f || current.viewportHeightPx <= 0f) return

        // `null` = primer frame tras arrancar/reanudar o dt==0: no integramos este frame.
        val dtSec = frameClock.tick(frameNanos) ?: return

        val updated = step(current, dtSec)
        _state.value = updated

        if (updated.remainingMs <= 0L && status.value == GameStatus.RUNNING) {
            finish()
        }
    }

    override fun calculateScore(): Int {
        val s = _state.value
        // Bono de supervivencia: premia terminar la ronda, no solo absorber (coherente con Polarity).
        val survivalBonus = ((s.remainingMs / 1_000L) * SURVIVAL_BONUS_PER_SEC).toInt().coerceAtLeast(0)
        return (s.score + survivalBonus).coerceAtLeast(0)
    }

    override fun currentAccuracy(): Double {
        val attempts = _state.value.absorbed + _state.value.crashed
        return if (attempts == 0) 100.0
        else _state.value.absorbed.toDouble() / attempts.toDouble() * 100.0
    }

    /** Récord = mejor puntaje de la corrida; `null` si no sumó puntos. */
    override fun reachedMetric(): Int? = calculateScore().takeIf { it > 0 }

    /**
     * Un paso de simulación de [dtSec] segundos: (1) spawnea según la cadencia rampada, (2) avanza
     * cada proyectil hacia el centro, (3) resuelve impactos, (4) descuenta el tiempo.
     *
     * Devuelve un estado NUEVO (inmutabilidad MVI); los efectos de impacto se publican como
     * side-effect controlado en [_effects].
     */
    private fun step(state: HypergateState, dtSec: Float): HypergateState {
        val progression = roundProgress(state)

        // --- (1) Spawn con cadencia que se acelera con el tiempo y la dificultad -------------
        val spawnInterval = spawnIntervalSec(progression)
        var projectiles = state.projectiles
        spawnAccumulatorSec += dtSec
        while (spawnAccumulatorSec >= spawnInterval) {
            spawnAccumulatorSec -= spawnInterval
            projectiles = projectiles + spawnProjectile(state, progression)
        }

        // --- (2)+(3) Avance radial y resolución de impactos ----------------------------------
        val shieldRadius = state.shieldRadiusPx
        var scoreDelta = 0
        var absorbedDelta = 0
        var crashedDelta = 0
        val survivors = ArrayList<Projectile>(projectiles.size)

        for (p in projectiles) {
            // Movimiento radial puro: una resta escalar, sin trigonometría (ver KDoc de clase).
            val nextDistance = p.distancePx - p.speedPx * dtSec
            if (nextDistance > shieldRadius) {
                survivors += p.copy(distancePx = nextDistance)
                continue
            }

            // Impacto: comparación de polaridad contra el escudo VIGENTE en este frame.
            if (p.required == state.shield) {
                absorbedDelta++
                // Premia la velocidad: absorber un proyectil rápido vale más (mayor riesgo).
                scoreDelta += ABSORB_BASE_SCORE + (p.speedPx * SPEED_SCORE_FACTOR).toInt()
                _effects.trySend(HypergateEffect.PlaySound(HypergateEffect.PlaySound.Cue.ABSORB))
                _effects.trySend(HypergateEffect.Vibrate(HypergateEffect.Vibrate.Cue.SUCCESS))
            } else {
                crashedDelta++
                scoreDelta -= MISMATCH_PENALTY
                _effects.trySend(HypergateEffect.PlaySound(HypergateEffect.PlaySound.Cue.CRASH))
                _effects.trySend(HypergateEffect.Vibrate(HypergateEffect.Vibrate.Cue.ERROR))
            }
            // Absorbido o estrellado, el proyectil desaparece: no se añade a survivors.
        }

        // --- (4) Descuento de tiempo ---------------------------------------------------------
        return state.copy(
            projectiles = survivors,
            score = (state.score + scoreDelta).coerceAtLeast(0),
            absorbed = state.absorbed + absorbedDelta,
            crashed = state.crashed + crashedDelta,
            remainingMs = (state.remainingMs - (dtSec * 1_000f).toLong()).coerceAtLeast(0L),
        )
    }

    /**
     * Genera un proyectil en un ángulo uniforme `[0, 2π)` a la distancia justa para nacer **fuera**
     * de la pantalla por ese ángulo.
     *
     * La distancia de spawn no es fija: se calcula dónde el rayo desde el centro cruza el borde del
     * viewport ([edgeDistanceForAngle]) y se le suma un margen. Así todo proyectil "asoma" por el
     * borde con una entrada perceptualmente consistente, en lugar de nacer más lejos en las
     * direcciones cardinales que en las diagonales (detalle de pulido §9).
     */
    private fun spawnProjectile(state: HypergateState, progression: Float): Projectile {
        val angle = (random.nextFloat() * 2f * PI.toFloat())
        val spawnDistance = edgeDistanceForAngle(
            angleRad = angle,
            halfWidth = state.viewportWidthPx * 0.5f,
            halfHeight = state.viewportHeightPx * 0.5f,
        ) + SPAWN_MARGIN_PX

        val baseSpeed = BASE_SPEED_PX + random.nextFloat() * SPEED_VARIANCE_PX
        val difficultySpeed = baseSpeed * (1f + (difficulty.coerceIn(1, 5) - 1) * 0.08f)
        val speed = difficultySpeed * lerp(INITIAL_SPEED_MULTIPLIER, END_SPEED_MULTIPLIER, progression)

        return Projectile(
            id = nextProjectileId++,
            angleRad = angle,
            distancePx = spawnDistance,
            speedPx = speed,
            // 50/50 A/B: la discriminación es el núcleo del juego, no sesgar ninguna polaridad.
            required = if (random.nextBoolean()) ShieldState.A else ShieldState.B,
        )
    }

    /**
     * Distancia desde el centro hasta el borde del rectángulo del viewport siguiendo el rayo de
     * ángulo [angleRad]. Resuelve el primer lado que el rayo cruza:
     * `t = min(halfWidth/|cos|, halfHeight/|sin|)`. Los `abs(...).coerceAtLeast(EPS)` evitan la
     * división por cero en ángulos exactamente horizontales/verticales.
     */
    private fun edgeDistanceForAngle(angleRad: Float, halfWidth: Float, halfHeight: Float): Float {
        val cosA = abs(cos(angleRad)).coerceAtLeast(RAY_EPSILON)
        val sinA = abs(sin(angleRad)).coerceAtLeast(RAY_EPSILON)
        return min(halfWidth / cosA, halfHeight / sinA)
    }

    /** Cadencia de spawn (seg entre proyectiles): arranca amable y se acelera con la ronda. */
    private fun spawnIntervalSec(progression: Float): Float {
        val base = (BASE_SPAWN_INTERVAL_SEC - (difficulty.coerceIn(1, 5) - 1) * 0.06f)
            .coerceAtLeast(MIN_SPAWN_INTERVAL_SEC)
        return lerp(
            start = base * INITIAL_SPAWN_INTERVAL_MULTIPLIER,
            end = (base * END_SPAWN_INTERVAL_MULTIPLIER).coerceAtLeast(MIN_SPAWN_INTERVAL_SEC),
            progress = progression,
        )
    }

    /** Progreso normalizado 0..1 de la ronda, para rampas suaves de spawn/velocidad. */
    private fun roundProgress(state: HypergateState): Float {
        val elapsed = (ROUND_DURATION_MS - state.remainingMs).coerceAtLeast(0L)
        return (elapsed.toFloat() / ROUND_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    }

    /** Interpolación lineal explícita para dejar clara la intención de tuning. */
    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private companion object {
        /** Radio del escudo como fracción del lado menor del viewport (umbral de colisión y dibujo). */
        const val SHIELD_RADIUS_FACTOR = 0.13f

        const val MAX_DT_SEC = 0.05f

        // Rampa de spawn: menos proyectiles al inicio (onboarding), más presión al final.
        const val BASE_SPAWN_INTERVAL_SEC = 0.95f
        const val MIN_SPAWN_INTERVAL_SEC = 0.34f
        const val INITIAL_SPAWN_INTERVAL_MULTIPLIER = 1.8f
        const val END_SPAWN_INTERVAL_MULTIPLIER = 0.72f

        // Rampa de velocidad de los proyectiles a lo largo de la ronda.
        const val INITIAL_SPEED_MULTIPLIER = 0.8f
        const val END_SPEED_MULTIPLIER = 1.35f
        const val BASE_SPEED_PX = 240f
        const val SPEED_VARIANCE_PX = 160f

        const val SPAWN_MARGIN_PX = 48f
        /** Evita div/0 en [edgeDistanceForAngle] para rayos horizontales/verticales exactos. */
        const val RAY_EPSILON = 1e-4f

        const val ABSORB_BASE_SCORE = 70
        const val SPEED_SCORE_FACTOR = 0.12f
        const val MISMATCH_PENALTY = 90
        const val SURVIVAL_BONUS_PER_SEC = 12L
    }
}
