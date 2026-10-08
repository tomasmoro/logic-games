package com.kortexgames.app.game.hypergate

import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.physics.FrameClock
import com.kortexgames.app.core.audio.AudioAndHapticManager
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
 *  - Cada pocos segundos llega un **meteorito rojo** ([ProjectileKind.METEOR]) que ninguna
 *    polaridad absorbe: choca siempre, salvo que esté encendido el **modo escudo**.
 *  - El **modo escudo** se enciende manteniendo pulsado ([setBarrierHeld]). Mientras dura, todo
 *    lo que llega se deshace sin efecto: los meteoritos no dañan, y los cometas ni suman ni
 *    restan. Aguanta [BARRIER_MAX_HOLD_MS] seguidos; si se agota, recarga [BARRIER_COOLDOWN_MS].
 *
 * ## Por qué el escudo anula también los cometas (y no solo protege)
 * Si con el escudo encendido los cometas siguieran absorbiéndose, mantener pulsado sería
 * estrictamente mejor que jugar y el tope de 3 s sería la única regla del juego. Al no puntuar,
 * cada instante a cubierto es puntuación que se deja pasar: el jugador quiere encenderlo lo
 * justo para el meteorito y soltarlo, que es la decisión interesante.
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

    /** Segundos que faltan para el próximo meteorito. */
    private var meteorCountdownSec = 0f

    /**
     * Si el jugador está manteniendo pulsado AHORA. Es entrada, no estado de juego: lo que el
     * motor decide con ella (si el escudo llega a encenderse) sí va en [HypergateState].
     */
    private var barrierRequested = false

    override fun onStart() {
        frameClock.reset()
        spawnAccumulatorSec = 0f
        nextProjectileId = 1L
        meteorCountdownSec = FIRST_METEOR_DELAY_SEC
        barrierRequested = false
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
        // El menú de pausa se lleva el dedo: sin esto el escudo seguiría "pulsado" al reanudar
        // (y gastándose) aunque el jugador ya no esté tocando la pantalla.
        barrierRequested = false
        _state.update { it.copy(barrierActive = false, barrierHeldMs = 0L) }
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
     * Registra si el jugador mantiene pulsado, es decir, si **pide** el modo escudo. Encenderlo o
     * no lo decide [step] en el siguiente frame: durante la recarga la petición no hace nada.
     *
     * Soltar apaga el escudo en el acto (no espera al frame) para que la pantalla lo vea ya.
     * Ignora las pulsaciones fuera de RUNNING, pero **siempre** acepta soltar: si no, pausar con
     * el dedo puesto dejaría la petición colgada.
     */
    fun setBarrierHeld(held: Boolean) {
        if (!held) {
            barrierRequested = false
            _state.update { if (it.barrierActive) it.copy(barrierActive = false, barrierHeldMs = 0L) else it }
            return
        }
        if (status.value != GameStatus.RUNNING) return
        barrierRequested = true
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
     * Un paso de simulación de [dtSec] segundos: (0) resuelve el modo escudo, (1) spawnea según la
     * cadencia rampada, (2) avanza cada proyectil hacia el centro, (3) resuelve impactos,
     * (4) descuenta el tiempo.
     *
     * Devuelve un estado NUEVO (inmutabilidad MVI); los efectos de impacto se publican como
     * side-effect controlado en [_effects].
     */
    private fun step(state: HypergateState, dtSec: Float): HypergateState {
        val progression = roundProgress(state)
        val dtMs = (dtSec * 1_000f).toLong()

        // --- (0) Modo escudo: recarga, encendido y agotamiento -------------------------------
        var barrierActive = false
        var barrierHeldMs = 0L
        var barrierCooldownMs = (state.barrierCooldownMs - dtMs).coerceAtLeast(0L)
        if (barrierRequested && state.barrierCooldownMs == 0L) {
            barrierHeldMs = state.barrierHeldMs + dtMs
            if (barrierHeldMs >= BARRIER_MAX_HOLD_MS) {
                // Agotado: se apaga y recarga. Se descarta la petición para que, pasada la
                // recarga, haga falta volver a pulsar: si se reencendiera solo con el dedo aún
                // puesto, el jugador lo gastaría otra vez sin haberlo decidido.
                barrierHeldMs = 0L
                barrierCooldownMs = BARRIER_COOLDOWN_MS
                barrierRequested = false
                _effects.trySend(HypergateEffect.PlaySound(HypergateEffect.PlaySound.Cue.OVERHEAT))
                _effects.trySend(HypergateEffect.Vibrate(HypergateEffect.Vibrate.Cue.OVERHEAT))
            } else {
                barrierActive = true
            }
        }

        // --- (1) Spawn con cadencia que se acelera con el tiempo y la dificultad -------------
        val spawnInterval = spawnIntervalSec(progression)
        var projectiles = state.projectiles
        spawnAccumulatorSec += dtSec
        while (spawnAccumulatorSec >= spawnInterval) {
            spawnAccumulatorSec -= spawnInterval
            projectiles = projectiles + spawnProjectile(state, progression)
        }
        // Los meteoritos van con su propio reloj y no como un % de los spawns normales: así
        // llegan espaciados seguro. Dos seguidos obligarían a mantener el escudo más de lo que
        // aguanta, y eso no sería dificultad sino un castigo sin salida.
        meteorCountdownSec -= dtSec
        if (meteorCountdownSec <= 0f) {
            meteorCountdownSec = METEOR_MIN_GAP_SEC + random.nextFloat() * METEOR_GAP_VARIANCE_SEC
            projectiles = projectiles + spawnProjectile(state, progression, ProjectileKind.METEOR)
        }

        // --- (2)+(3) Avance radial y resolución de impactos ----------------------------------
        // Con el modo escudo encendido la frontera es su burbuja, no el anillo del portal.
        val shieldRadius = if (barrierActive) state.shieldRadiusPx * BARRIER_RADIUS_FACTOR else state.shieldRadiusPx
        var scoreDelta = 0
        var deflectedDelta = 0
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

            if (barrierActive) {
                // Modo escudo: se deshace sin más, sea lo que sea. Ni puntos ni penalización.
                deflectedDelta++
                _effects.trySend(HypergateEffect.PlaySound(HypergateEffect.PlaySound.Cue.DEFLECT))
                _effects.trySend(HypergateEffect.Vibrate(HypergateEffect.Vibrate.Cue.DEFLECT))
                continue
            }

            // Impacto: comparación de polaridad contra el escudo VIGENTE en este frame. Un
            // meteorito no tiene polaridad que valga: sin modo escudo, choca siempre.
            if (p.kind == ProjectileKind.COMET && p.required == state.shield) {
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
            remainingMs = (state.remainingMs - dtMs).coerceAtLeast(0L),
            barrierActive = barrierActive,
            barrierHeldMs = barrierHeldMs,
            barrierCooldownMs = barrierCooldownMs,
            deflected = state.deflected + deflectedDelta,
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
     *
     * @param kind clase a generar. Un meteorito va algo más lento que un cometa
     *   ([METEOR_SPEED_FACTOR]): su respuesta es mantener pulsado, que tarda un instante más en
     *   reconocerse que un toque, y sin ese margen llegaría antes de poder reaccionar.
     */
    private fun spawnProjectile(
        state: HypergateState,
        progression: Float,
        kind: ProjectileKind = ProjectileKind.COMET,
    ): Projectile {
        val angle = (random.nextFloat() * 2f * PI.toFloat())
        val spawnDistance = edgeDistanceForAngle(
            angleRad = angle,
            halfWidth = state.viewportWidthPx * 0.5f,
            halfHeight = state.viewportHeightPx * 0.5f,
        ) + SPAWN_MARGIN_PX

        val baseSpeed = BASE_SPEED_PX + random.nextFloat() * SPEED_VARIANCE_PX
        val difficultySpeed = baseSpeed * (1f + (difficulty.coerceIn(1, 5) - 1) * 0.08f)
        val kindFactor = if (kind == ProjectileKind.METEOR) METEOR_SPEED_FACTOR else 1f
        val speed = difficultySpeed * lerp(INITIAL_SPEED_MULTIPLIER, END_SPEED_MULTIPLIER, progression) * kindFactor

        return Projectile(
            id = nextProjectileId++,
            angleRad = angle,
            distancePx = spawnDistance,
            speedPx = speed,
            // 50/50 A/B: la discriminación es el núcleo del juego, no sesgar ninguna polaridad.
            required = if (random.nextBoolean()) ShieldState.A else ShieldState.B,
            kind = kind,
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

        // Meteoritos rojos. El primero tarda: los primeros segundos son para coger el ritmo de
        // las polaridades. El hueco mínimo entre dos es mayor que lo que aguanta el escudo.
        const val FIRST_METEOR_DELAY_SEC = 6f
        const val METEOR_MIN_GAP_SEC = 4f
        const val METEOR_GAP_VARIANCE_SEC = 3f
        const val METEOR_SPEED_FACTOR = 0.85f
    }
}
