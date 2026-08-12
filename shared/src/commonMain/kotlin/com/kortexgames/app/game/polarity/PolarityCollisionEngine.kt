package com.kortexgames.app.game.polarity

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.physics.FrameClock
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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Balance de "Atracción Geométrica". Vive fuera del motor (y es público) porque la
 * pantalla necesita parte de estos valores para dibujar el HUD —cuántos huecos de
 * vida pintar, cuántos sectores puede llegar a tener el disco— y porque tener el
 * tuning en un solo sitio permite ajustar la curva sin tocar la física.
 */
object PolarityConfig {
    /** Sectores de color al empezar la partida: el disco arranca fácil de leer. */
    const val INITIAL_COLOR_COUNT = 3

    /** Tope de sectores. Cada lluvia de meteoros suma uno hasta llegar aquí. */
    const val MAX_COLOR_COUNT = 5

    /** Vidas iniciales. Cada mal impacto (color que no coincide) cuesta una. */
    const val INITIAL_LIVES = 3

    /**
     * Tope de vidas acumulables. Igual a [INITIAL_LIVES]: la vida de la lluvia
     * **repone**, no acumula. Con un tope mayor, encadenar lluvias sin fallar daría
     * un colchón que anularía la tensión de la partida; así el regalo vale justo
     * cuando el jugador viene tocado, que es cuando de verdad importa.
     */
    const val MAX_LIVES = 3

    /** Duración de una oleada normal antes de la lluvia de meteoros. */
    const val WAVE_DURATION_MS = 30_000L

    /** Pausa con cartel de aviso justo antes de la lluvia. */
    const val SHOWER_INTRO_MS = 1_600L

    /** Duración de la lluvia de meteoros (fase de regalo, sin riesgo). */
    const val SHOWER_DURATION_MS = 9_000L

    /** Pausa con cartel de recompensa tras la lluvia (vida + color nuevo). */
    const val WAVE_INTRO_MS = 2_000L
}

/**
 * Fase en la que está la partida infinita. El ciclo es siempre el mismo:
 * [PLAYING] → [SHOWER_INTRO] → [SHOWER] → [WAVE_INTRO] → [PLAYING]…
 *
 * Las dos fases de cartel ([SHOWER_INTRO], [WAVE_INTRO]) congelan la simulación —el
 * campo se limpia y no spawnea nada— para que el cambio de reglas se lea antes de
 * volver a jugar. No usan [GameStatus.PAUSED] a propósito: el jugador puede seguir
 * girando el disco para colocarse, y el menú de pausa real sigue siendo suyo.
 */
enum class PolarityPhase {
    /** Oleada normal: los fallos cuestan vida. */
    PLAYING,

    /** Cartel "lluvia de meteoros" (campo congelado). */
    SHOWER_INTRO,

    /** Lluvia de meteoros: solo suman los aciertos, no se pierden vidas. */
    SHOWER,

    /** Cartel de recompensa: +1 vida y +1 color (campo congelado). */
    WAVE_INTRO,
}

/**
 * Partícula individual de Atracción Geométrica.
 *
 * @property mass masa relativa: valores altos aceleran menos en curvas magnéticas,
 *            pero suelen spawnear con mayor velocidad inicial para subir presión.
 * @property colorIndex índice del color que debe coincidir con el sector del círculo.
 *            Siempre `< colorCount` de la oleada en curso.
 * @property meteor true si nació en la lluvia de meteoros: la pantalla le pinta
 *            estela y el motor no cobra vida si falla.
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
    val meteor: Boolean = false,
)

/**
 * Efecto transitorio de impacto (chispas) en el borde de captura.
 *
 * Vive fuera de [PolarityParticle] porque sobrevive a la partícula que lo originó: una
 * vez que hay acierto/fallo, la partícula desaparece del frame pero el destello de
 * chispas debe seguir animándose unos cientos de ms más. [ageMs] se incrementa cada
 * frame en el motor y la pantalla lo usa para interpolar tamaño/alfa del estallido.
 *
 * @property success true = acierto (color coincide, chispas del color del sector),
 *   false = fallo (chispas rojas de error).
 * @property harmless fallo que NO castiga (meteoro perdido durante la lluvia). Se
 *   dibuja apagado en vez de rojo: pintar "error" donde no se pierde nada enseñaría
 *   al jugador a temer la fase que precisamente es un regalo.
 */
data class PolarityImpact(
    val id: Long,
    val x: Float,
    val y: Float,
    val colorIndex: Int,
    val success: Boolean,
    val harmless: Boolean = false,
    val ageMs: Long = 0L,
)

/**
 * Estado de UI del juego Atracción Geométrica.
 *
 * El estado conserva geometría de viewport para mantener el motor desacoplado del
 * framework visual: la pantalla reporta tamaño y el motor calcula spawns/física con
 * ese dato, evitando usar APIs de plataforma dentro del engine.
 *
 * @property lives vidas restantes; a 0 termina la partida.
 * @property wave oleada en curso (1-based). Sube tras cada lluvia de meteoros.
 * @property colorCount sectores de color activos ahora mismo (3..5).
 * @property phaseRemainingMs cuenta atrás de la fase actual, no de la partida: la
 *   partida es infinita y solo la cortan las vidas.
 * @property showerCaught meteoros capturados en la lluvia en curso; alimenta el
 *   cartel de recompensa ("has cazado N").
 * @property rewardedLife true si la última lluvia SÍ dio vida (no estaba al tope).
 * @property rewardedColor true si la última lluvia SÍ añadió color (no estaba al tope).
 *   Ambos existen para que el cartel de recompensa no prometa lo que el tope ya no
 *   concede: cantar "+1 vida" con las vidas llenas se leería como un bug.
 * @property elapsedPlayMs tiempo total sobrevivido; entra en el bonus de puntaje y
 *   marca la rampa de dificultad.
 * @property mismatches impactos de color equivocado. NO se muestra en el HUD (el
 *   castigo visible son las vidas); solo alimenta la precisión del [com.kortexgames.app.domain.model.GameResult].
 */
data class PolarityCollisionState(
    val rotationRad: Float = 0f,
    val particles: List<PolarityParticle> = emptyList(),
    val impacts: List<PolarityImpact> = emptyList(),
    val viewportWidthPx: Float = 0f,
    val viewportHeightPx: Float = 0f,
    val score: Int = 0,
    val caught: Int = 0,
    val mismatches: Int = 0,
    val lives: Int = PolarityConfig.INITIAL_LIVES,
    val wave: Int = 1,
    val colorCount: Int = PolarityConfig.INITIAL_COLOR_COUNT,
    val phase: PolarityPhase = PolarityPhase.PLAYING,
    val phaseRemainingMs: Long = PolarityConfig.WAVE_DURATION_MS,
    val showerCaught: Int = 0,
    val rewardedLife: Boolean = false,
    val rewardedColor: Boolean = false,
    val elapsedPlayMs: Long = 0L,
) {
    /** ¿Estamos en un cartel entre fases? La física está congelada. */
    val isInterlude: Boolean
        get() = phase == PolarityPhase.SHOWER_INTRO || phase == PolarityPhase.WAVE_INTRO
}

/**
 * Motor de "Atracción Geométrica" (Polarity Collision).
 *
 * Reglas clave:
 *  - El jugador rota un círculo de sectores de color (3 al empezar, hasta 5).
 *  - Partículas caen desde bordes hacia el centro con masas/velocidades distintas.
 *  - Partículas magnéticas curvan trayectorias cercanas, obligando anticipación.
 *  - Una partícula se captura solo si su color coincide con el sector impactado; si
 *    no coincide, cuesta una vida.
 *
 * **Partida infinita por oleadas.** No hay reloj de fin: se juega hasta agotar las
 * vidas ([PolarityConfig.INITIAL_LIVES]). Cada [PolarityConfig.WAVE_DURATION_MS] la
 * simulación se congela y entra una **lluvia de meteoros** de todos los colores y
 * direcciones donde solo suman los aciertos —fallar no cuesta vida— y que funciona
 * como regalo de puntos y respiro. Al terminar la lluvia se regala **una vida** y se
 * añade **un color** al disco (tope [PolarityConfig.MAX_COLOR_COUNT]): el premio y
 * la subida de dificultad llegan juntos, así el juego crece sin depender solo de la
 * velocidad.
 *
 * Curva de onboarding + escalado continuo: la partida arranca deliberadamente más
 * amable para enseñar la lectura espacial antes de saturar. Al inicio salen un 50%
 * menos de asteroides y viajan un 25% más despacio; desde ahí la presión aumenta de
 * forma gradual (spawn, velocidad, gravedad y probabilidad de magnetismo) según el
 * tiempo sobrevivido, más un empujón fijo por oleada que no satura nunca —es lo que
 * garantiza que una partida infinita acabe cayendo—.
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

    // Reloj de frames compartido: deriva el `dt` sano de los timestamps de `withFrameNanos`
    // (descarta el primer frame, recorta saltos). Antes vivía inline aquí; ahora es común con
    // Hypergate para no duplicar esa lógica sensible. Ver [FrameClock].
    private val frameClock = FrameClock(maxDtSec = MAX_DT_SEC)
    private var spawnAccumulatorSec = 0f
    private var nextParticleId = 1L
    private var nextImpactId = 1L

    override fun onStart() {
        frameClock.reset()
        spawnAccumulatorSec = 0f
        nextParticleId = 1L
        nextImpactId = 1L
        // Conserva el viewport ya conocido: si lo perdiéramos (volviendo a 0,0), `onFrame`
        // se quedaría devolviendo temprano hasta el próximo reporte de tamaño, y como la
        // pantalla solo reenvía el viewport cuando su tamaño CAMBIA (`LaunchedEffect(viewportSize)`
        // en [PolarityCollisionScreen]), reiniciar sin volver a rotar el dispositivo dejaba el
        // juego congelado tras pulsar "Jugar de nuevo" (ninguna partícula se movía ni spawneaba).
        val current = _state.value
        _state.value = PolarityCollisionState(
            viewportWidthPx = current.viewportWidthPx,
            viewportHeightPx = current.viewportHeightPx,
        )
    }

    override fun onPause() {
        // Al reanudar descartamos el delta acumulado para evitar un salto de física.
        frameClock.reset()
    }

    /** Actualiza el tamaño útil del juego para spawns y colisiones. */
    fun updateViewport(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        _state.update {
            if (it.viewportWidthPx == widthPx && it.viewportHeightPx == heightPx) it
            else it.copy(viewportWidthPx = widthPx, viewportHeightPx = heightPx)
        }
    }

    /** Aplica una rotación incremental al disco (en radianes). */
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

        // `null` = primer frame tras arrancar/reanudar o dt==0: no integramos este frame.
        val dtSec = frameClock.tick(frameNanos) ?: return

        val updated = step(current, dtSec)
        _state.value = updated

        // Única condición de fin: quedarse sin vidas. La partida no tiene reloj.
        if (updated.lives <= 0 && status.value == GameStatus.RUNNING) {
            finish()
        }
    }

    /**
     * Puntaje = puntos de captura + bonus por supervivencia + bonus por oleada
     * superada. Los dos bonus existen porque en una partida infinita el mérito no es
     * solo cuánto capturas, sino **cuánto aguantas**: sin ellos, morir pronto con
     * muchas capturas empataría con sobrevivir varias oleadas.
     */
    override fun calculateScore(): Int {
        val state = _state.value
        val survivalBonus = (state.elapsedPlayMs / 1_000L * SURVIVAL_POINTS_PER_SEC).toInt()
        val waveBonus = (state.wave - 1) * WAVE_CLEAR_BONUS
        return (state.score + survivalBonus + waveBonus).coerceAtLeast(0)
    }

    /**
     * Precisión = capturas / (capturas + impactos de color equivocado). Incluye las
     * capturas de la lluvia de meteoros, que la inflan ligeramente al alza; es
     * deliberado: la lluvia es una recompensa y penalizar la estadística por
     * aprovecharla sería contradictorio.
     */
    override fun currentAccuracy(): Double {
        val state = _state.value
        val attempts = state.caught + state.mismatches
        return if (attempts == 0) 100.0 else (state.caught.toDouble() / attempts.toDouble() * 100.0)
    }

    /** Récord = mejor puntaje de la corrida; null si no sumó puntos. */
    override fun reachedMetric(): Int? = calculateScore().takeIf { it > 0 }

    /**
     * Un frame de simulación: envejece efectos, descuenta la fase en curso, deja
     * avanzar la física (solo si la fase no está congelada) y, cuando la cuenta
     * atrás llega a 0, encadena la fase siguiente.
     */
    private fun step(state: PolarityCollisionState, dtSec: Float): PolarityCollisionState {
        val dtMs = (dtSec * 1_000f).toLong()

        val agedImpacts = state.impacts.mapNotNull { impact ->
            val aged = impact.ageMs + dtMs
            if (aged >= IMPACT_LIFETIME_MS) null else impact.copy(ageMs = aged)
        }
        val ticked = state.copy(
            impacts = agedImpacts,
            elapsedPlayMs = state.elapsedPlayMs + dtMs,
            phaseRemainingMs = (state.phaseRemainingMs - dtMs).coerceAtLeast(0L),
        )

        val advanced = when (state.phase) {
            PolarityPhase.PLAYING -> advance(ticked, dtSec, shower = false)
            PolarityPhase.SHOWER -> advance(ticked, dtSec, shower = true)
            // Carteles: el campo está congelado, solo corre la cuenta atrás.
            PolarityPhase.SHOWER_INTRO, PolarityPhase.WAVE_INTRO -> ticked
        }

        return if (advanced.phaseRemainingMs > 0L) advanced else nextPhase(advanced)
    }

    /**
     * Transición al terminar la cuenta atrás de la fase actual.
     *
     * El premio (vida + color) se aplica al ENTRAR en [PolarityPhase.WAVE_INTRO], no
     * al salir, para que el cartel pueda mostrar ya los valores nuevos ("4 colores")
     * en lugar de prometer algo que aún no está en el estado.
     */
    private fun nextPhase(state: PolarityCollisionState): PolarityCollisionState = when (state.phase) {
        PolarityPhase.PLAYING -> {
            audio.playSound(SoundEffect.LEVEL_UP)
            state.copy(
                phase = PolarityPhase.SHOWER_INTRO,
                phaseRemainingMs = PolarityConfig.SHOWER_INTRO_MS,
                // Se limpia el campo: entrar en la lluvia con asteroides "de verdad"
                // aún volando mezclaría las dos reglas (unos cuestan vida, otros no).
                particles = emptyList(),
                showerCaught = 0,
            )
        }

        PolarityPhase.SHOWER_INTRO -> {
            spawnAccumulatorSec = 0f // el primer meteoro sale ya, sin esperar al intervalo
            state.copy(phase = PolarityPhase.SHOWER, phaseRemainingMs = PolarityConfig.SHOWER_DURATION_MS)
        }

        PolarityPhase.SHOWER -> {
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            state.copy(
                phase = PolarityPhase.WAVE_INTRO,
                phaseRemainingMs = PolarityConfig.WAVE_INTRO_MS,
                particles = emptyList(),
                lives = min(state.lives + 1, PolarityConfig.MAX_LIVES),
                colorCount = min(state.colorCount + 1, PolarityConfig.MAX_COLOR_COUNT),
                wave = state.wave + 1,
                rewardedLife = state.lives < PolarityConfig.MAX_LIVES,
                rewardedColor = state.colorCount < PolarityConfig.MAX_COLOR_COUNT,
            )
        }

        PolarityPhase.WAVE_INTRO -> {
            spawnAccumulatorSec = 0f
            state.copy(phase = PolarityPhase.PLAYING, phaseRemainingMs = PolarityConfig.WAVE_DURATION_MS)
        }
    }

    /**
     * Física + spawns + colisiones de un frame jugable.
     *
     * @param shower true durante la lluvia de meteoros: spawn mucho más denso y, sobre
     *   todo, **los fallos no cuestan vida ni puntos** (solo se cuentan éxitos).
     */
    private fun advance(state: PolarityCollisionState, dtSec: Float, shower: Boolean): PolarityCollisionState {
        val progression = rampProgress(state)
        val dynamicDifficulty = dynamicDifficultyMultiplier(state, progression)
        val spawnInterval = if (shower) {
            SHOWER_SPAWN_INTERVAL_SEC
        } else {
            val baseSpawnInterval = (BASE_SPAWN_INTERVAL_SEC - (difficulty.coerceIn(1, 5) - 1) * 0.08f)
                .coerceAtLeast(MIN_SPAWN_INTERVAL_SEC)
            lerp(
                start = baseSpawnInterval * INITIAL_SPAWN_INTERVAL_MULTIPLIER,
                end = (baseSpawnInterval * END_SPAWN_INTERVAL_MULTIPLIER / dynamicDifficulty).coerceAtLeast(MIN_SPAWN_INTERVAL_SEC),
                progress = progression,
            )
        }

        var particles = state.particles
        spawnAccumulatorSec += dtSec
        while (spawnAccumulatorSec >= spawnInterval) {
            spawnAccumulatorSec -= spawnInterval
            particles = particles + spawnParticle(state, progression, shower)
        }

        val centerX = state.viewportWidthPx * 0.5f
        val centerY = state.viewportHeightPx * 0.5f
        val hexRadius = min(state.viewportWidthPx, state.viewportHeightPx) * 0.18f
        val catchRadius = hexRadius * 0.82f

        var scoreDelta = 0
        var caughtDelta = 0
        var mismatchDelta = 0
        var livesDelta = 0
        var showerCaughtDelta = 0
        val nextParticles = ArrayList<PolarityParticle>(particles.size)
        val newImpacts = ArrayList<PolarityImpact>()

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
                // Ángulo del PUNTO DE CRUCE del borde, no de la posición final: (px,py)
                // ya está hundida en el disco y desviada tangencialmente por el
                // magnetismo justo antes de impactar, así que su ángulo puede caer en el
                // sector contiguo al que el jugador ve. Interpolamos el segmento
                // previo→nuevo hasta donde cruza `catchRadius` y usamos ESE ángulo.
                val (impactX, impactY) = borderCrossing(
                    fromX = particle.x, fromY = particle.y,
                    toX = px, toY = py,
                    centerX = centerX, centerY = centerY,
                    radius = catchRadius,
                )
                val impactAngle = atan2(impactY - centerY, impactX - centerX)
                val isMatch = isColorMatch(impactAngle, state.rotationRad, particle.colorIndex, state.colorCount)
                if (isMatch) {
                    caughtDelta++
                    if (shower) {
                        // Regalo de puntos: valor fijo y generoso, sin depender de masa
                        // ni velocidad. La lluvia premia cazar mucho, no cazar "bien".
                        scoreDelta += SHOWER_HIT_SCORE
                        showerCaughtDelta++
                    } else {
                        val speed = hypot(vx, vy)
                        scoreDelta += (80f * particle.mass + speed * 0.14f).toInt() + if (particle.magnetic) 35 else 0
                    }
                    audio.playSound(SoundEffect.SUCCESS)
                    audio.hapticFeedback(HapticFeedback.LIGHT)
                } else if (shower) {
                    // Meteoro con el color equivocado: se desintegra y ya está. Ni vida,
                    // ni puntos, ni sonido de error (ver [PolarityImpact.harmless]).
                    newImpacts += PolarityImpact(
                        id = nextImpactId++,
                        x = impactX,
                        y = impactY,
                        colorIndex = particle.colorIndex,
                        success = false,
                        harmless = true,
                    )
                    continue
                } else {
                    mismatchDelta++
                    livesDelta--
                    audio.playSound(SoundEffect.ERROR)
                    audio.hapticFeedback(HapticFeedback.ERROR)
                }
                // Chispa en el punto de cruce exacto (no en la posición final ya hundida
                // en el disco), para que el estallido quede pegado al borde del anillo.
                newImpacts += PolarityImpact(
                    id = nextImpactId++,
                    x = impactX,
                    y = impactY,
                    colorIndex = particle.colorIndex,
                    success = isMatch,
                )
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
            impacts = state.impacts + newImpacts,
            score = (state.score + scoreDelta).coerceAtLeast(0),
            caught = state.caught + caughtDelta,
            mismatches = state.mismatches + mismatchDelta,
            lives = (state.lives + livesDelta).coerceAtLeast(0),
            showerCaught = state.showerCaught + showerCaughtDelta,
        )
    }

    private fun spawnParticle(
        state: PolarityCollisionState,
        progression: Float,
        shower: Boolean,
    ): PolarityParticle {
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
        // La lluvia abre más el abanico de entrada: los meteoros llegan claramente en
        // diagonal y desde todos lados, que es lo que la hace leerse como "lluvia" y
        // no como más de lo mismo.
        val jitterRange = if (shower) SHOWER_ANGLE_JITTER_RAD else SPAWN_ANGLE_JITTER_RAD
        val jitter = random.nextFloat() * jitterRange * 2f - jitterRange
        val angle = baseAngle + jitter

        val mass = random.nextFloat() * 1.5f + 0.65f
        val baseSpeed = (PARTICLE_BASE_SPEED_PX + random.nextFloat() * PARTICLE_SPEED_VARIANCE_PX) / mass
        val difficultySpeed = baseSpeed * (1f + (difficulty.coerceIn(1, 5) - 1) * 0.07f)
        val speed = difficultySpeed * if (shower) {
            SHOWER_SPEED_MULTIPLIER
        } else {
            lerp(start = INITIAL_SPEED_MULTIPLIER, end = END_SPEED_MULTIPLIER, progress = progression)
        }

        val magneticProbability = if (shower) {
            SHOWER_MAGNETIC_PROBABILITY
        } else {
            lerp(
                start = INITIAL_MAGNETIC_PROBABILITY + (difficulty.coerceIn(1, 5) - 1) * 0.02f,
                end = FINAL_MAGNETIC_PROBABILITY + (difficulty.coerceIn(1, 5) - 1) * 0.03f,
                progress = progression,
            ).coerceAtMost(MAX_MAGNETIC_PROBABILITY)
        }
        val magnetic = random.nextFloat() <= magneticProbability

        return PolarityParticle(
            id = nextParticleId++,
            x = x,
            y = y,
            vx = cos(angle) * speed,
            vy = sin(angle) * speed,
            mass = mass,
            colorIndex = random.nextInt(state.colorCount),
            magnetic = magnetic,
            // Asteroides 3x para priorizar lectura visual sobre precisión milimétrica;
            // los meteoros son algo menores porque caen muchos a la vez.
            radius = when {
                shower -> 24f
                magnetic -> 39f
                else -> 30f
            },
            meteor = shower,
        )
    }

    /**
     * Punto donde el segmento (from→to) **entra** en la circunferencia de radio [radius]
     * centrada en (centerX,centerY). Resuelve la cuadrática |from + t·d − c|² = r² y toma
     * la raíz de ENTRADA (la menor t en [0,1]). Si el segmento no cruza limpiamente
     * (degenerado, o `from` ya estaba dentro), cae de vuelta al punto final [toX]/[toY].
     *
     * Se usa para muestrear el ángulo de impacto justo en el borde del área de captura,
     * que es lo que el jugador ve, en vez de en la posición final (ya dentro del disco).
     */
    private fun borderCrossing(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        centerX: Float, centerY: Float,
        radius: Float,
    ): Pair<Float, Float> {
        val dx = toX - fromX
        val dy = toY - fromY
        val a = dx * dx + dy * dy
        if (a <= 1e-6f) return toX to toY // segmento degenerado (sin movimiento)
        val ex = fromX - centerX
        val ey = fromY - centerY
        val b = 2f * (ex * dx + ey * dy)
        val c = ex * ex + ey * ey - radius * radius
        val disc = b * b - 4f * a * c
        if (disc < 0f) return toX to toY // no cruza (no debería pasar si `to` está dentro)
        val t = (-b - sqrt(disc)) / (2f * a)
        if (t < 0f || t > 1f) return toX to toY // `from` ya dentro / cruce fuera del paso
        return (fromX + t * dx) to (fromY + t * dy)
    }

    /**
     * Ángulo [angleRad] llevado al sistema de la rejilla de sectores: se le resta la
     * rotación del círculo para que `[0, sectorSize)` sea el sector 0. Resultado en
     * `[0, 2π)`. Base de [isColorMatch].
     *
     * IMPORTANTE: sin desplazamiento de medio sector. El sector 0 dibujado en pantalla
     * ([drawRingSectors][com.kortexgames.app.game.polarity.PolarityCollisionScreenKt])
     * arranca justo en `rotationRad` (no está centrado ahí) y las costuras/separadores
     * caen en `rotationRad + sectorSize·i`; esta función tiene que reproducir esas MISMAS
     * fronteras o el hit-test queda desfasado medio sector respecto al color que el
     * jugador ve, que es justo el bug que esto corrige.
     */
    private fun alignedAngle(angleRad: Float, rotationRad: Float): Float {
        return normalizeAnglePositive(angleRad - rotationRad)
    }

    /**
     * ¿El impacto en [angleRad] (con el círculo girado [rotationRad] y [sectorCount]
     * sectores) cuenta como acierto para una partícula de color [colorIndex]?
     *
     * Además del sector que contiene el ángulo, concede el **beneficio de la duda** cuando
     * el impacto cae MUY cerca de una frontera entre sectores (±[SECTOR_EDGE_TOLERANCE_RAD]):
     * ahí el color bajo el asteroide es ambiguo (toca ambos sectores), así que si su color
     * coincide con cualquiera de los dos contiguos se acepta. Evita fallos "injustos" en la
     * costura, donde el jugador percibe que el color sí coincidía. Con más sectores los
     * arcos son más estrechos, pero la tolerancia sigue siendo fija y pequeña (~5.7°):
     * escalarla con el número de sectores la volvería el caso normal, no la excepción.
     */
    private fun isColorMatch(
        angleRad: Float,
        rotationRad: Float,
        colorIndex: Int,
        sectorCount: Int,
    ): Boolean {
        val sectorSize = (2.0 * PI / sectorCount).toFloat()
        val aligned = alignedAngle(angleRad, rotationRad)
        val sector = (aligned / sectorSize).toInt().mod(sectorCount)
        if (sector == colorIndex) return true

        // Distancia a cada frontera del sector actual (within ∈ [0, sectorSize)).
        val within = aligned - sector * sectorSize
        if (within <= SECTOR_EDGE_TOLERANCE_RAD &&
            (sector - 1).mod(sectorCount) == colorIndex
        ) return true
        if (sectorSize - within <= SECTOR_EDGE_TOLERANCE_RAD &&
            (sector + 1).mod(sectorCount) == colorIndex
        ) return true
        return false
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

    /**
     * Progreso 0..1 de la rampa de dificultad, medido sobre el tiempo TOTAL
     * sobrevivido (no sobre la oleada): así la presión no se reinicia cada 30 s.
     * Satura en [RAMP_FULL_MS]; a partir de ahí la partida sigue endureciéndose por
     * el empujón de oleada y por los colores nuevos.
     */
    private fun rampProgress(state: PolarityCollisionState): Float =
        (state.elapsedPlayMs.toFloat() / RAMP_FULL_MS.toFloat()).coerceIn(0f, 1f)

    /** Interpolación lineal explícita para dejar clara la intención de tuning. */
    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    /**
     * Multiplicador de dificultad: base por nivel elegido × rampa temporal (satura)
     * × empujón por oleada (no satura). El término de oleada es el que garantiza que
     * una partida infinita termine: sin él, pasado [RAMP_FULL_MS] el juego se
     * quedaría igual de difícil para siempre y un jugador bueno no moriría nunca.
     */
    private fun dynamicDifficultyMultiplier(state: PolarityCollisionState, progress: Float): Float {
        val difficultyBase = 1f + (difficulty.coerceIn(1, 5) - 1) * 0.18f
        val timeRamp = lerp(1f, END_DIFFICULTY_MULTIPLIER, progress)
        val waveBoost = 1f + (state.wave - 1) * WAVE_DIFFICULTY_STEP
        return difficultyBase * timeRamp * waveBoost
    }

    private companion object {
        /**
         * Media banda de tolerancia (rad) a cada lado de una frontera de sector donde el
         * impacto se considera ambiguo y se da el beneficio de la duda (~5.7°). Ver
         * [isColorMatch].
         */
        const val SECTOR_EDGE_TOLERANCE_RAD = 0.10f

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

        /** Tiempo sobrevivido en el que la rampa temporal llega a su tope (~4 oleadas). */
        const val RAMP_FULL_MS = 160_000L

        /** Cuánto endurece cada oleada superada (acumulativo y sin tope). */
        const val WAVE_DIFFICULTY_STEP = 0.05f

        // --- Lluvia de meteoros (fase regalo) ---
        /** Muy denso: la lluvia debe verse "llena" de meteoros de todos los colores. */
        const val SHOWER_SPAWN_INTERVAL_SEC = 0.14f
        const val SHOWER_SPEED_MULTIPLIER = 1.0f
        /** Poco magnetismo: da variedad de trayectorias sin volver ilegible la lluvia. */
        const val SHOWER_MAGNETIC_PROBABILITY = 0.08f
        const val SHOWER_ANGLE_JITTER_RAD = 0.75f
        const val SHOWER_HIT_SCORE = 60

        const val BASE_GRAVITY_ACCEL = 360f
        const val MAGNETIC_CURVE_ACCEL = 820f
        const val MAGNET_RADIUS_PX = 190f

        const val SPAWN_MARGIN_PX = 42f
        const val OUTSIDE_MARGIN_PX = 70f
        const val SPAWN_ANGLE_JITTER_RAD = 0.35f

        const val PARTICLE_BASE_SPEED_PX = 210f
        const val PARTICLE_SPEED_VARIANCE_PX = 240f

        /** Bonus por segundo sobrevivido (incluye lluvias y carteles). */
        const val SURVIVAL_POINTS_PER_SEC = 8L

        /** Bonus fijo por cada oleada completada (es decir, por cada lluvia vivida). */
        const val WAVE_CLEAR_BONUS = 250
    }
}

/**
 * Duración del estallido de chispas de un impacto (ver [PolarityImpact.ageMs]) antes de
 * descartarlo del estado. Pública (no en el `companion object` privado del motor) porque
 * la pantalla la necesita para interpolar la misma animación que hace avanzar el motor.
 */
const val IMPACT_LIFETIME_MS = 420L
