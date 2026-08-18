package com.kortexgames.app.game.legion

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.physics.FrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * # LegionEngine — motor de carrera y eventos láser de Neon Legion (Fases 2-3)
 *
 * Motor del minijuego **Neon Legion**: hace caer filas de puertas matemáticas y al ejército
 * enemigo hacia el jugador (anclado en [LegionBalance.PLAYER_Y]), aplica la puerta del carril
 * elegido al cruzarla, gobierna los eventos de naves (láser vertical con carga y examen de
 * barrido horizontal) y resuelve el combate 1v1 de fin de ronda.
 *
 * ## Calendario de eventos anclado al PROGRESO, no al reloj
 * Los eventos láser de una ronda se programan al generarla como **índices de fila** ("al cruzar
 * la fila k, aparece la nave"), no como instantes de tiempo. Motivo: la velocidad crece por
 * ronda, así que un calendario en segundos dispararía los eventos en puntos distintos de la
 * pista según la ronda (o incluso después del combate); anclado al progreso, el susto llega
 * siempre "entre puertas", que es donde hay decisión que perturbar. Además hace el calendario
 * determinista bajo un [Random] sembrado, que es como se testea.
 *
 * ## El barrido "hace desaparecer" las puertas… en el RENDER, no en el dominio
 * Durante el examen la pista entera se CONGELA ([LegionPhase.QUIZ]) y es la pantalla quien
 * oculta las puertas mientras el barrido está en escena; el dominio no las elimina. La
 * alternativa literal (borrar las filas visibles) invalidaría al enemigo: se dimensiona contra
 * el recorrido óptimo COMPLETO al arrancar la ronda, y quitarle filas al jugador podría dejar la
 * ronda matemáticamente imposible de ganar. Congelar mantiene la promesa del spec ("la carrera
 * continúa desde donde estaba") sin romper la del enemigo alcanzable.
 *
 * ## Bucle dirigido por el render (Tick)
 * Igual que Hypergate/Polarity: la simulación avanza una vez por frame (`withFrameNanos` →
 * [onFrame]) y el `dt` lo deriva el [FrameClock] compartido, que descarta el primer frame y
 * recorta saltos largos — sin recorte, el frame gigante que sigue a una pausa o a un anuncio
 * haría que una fila de puertas atravesara al jugador sin colisionar (tunneling).
 *
 * ## Enemigo relativo al recorrido (Decisión 4 de `LegionModels.kt`)
 * Al generar la pista de la ronda se simula el **recorrido óptimo** (cruzarla eligiendo siempre
 * la mejor puerta partiendo de las tropas de entrada) y el enemigo se dimensiona como
 * `ceil(óptimo * enemyFactor(ronda))`. Así el reto es constante a cualquier ronda ("acércate al
 * óptimo") y la partida siempre puede perderse, cosa que un enemigo de crecimiento fijo no
 * garantiza contra un ejército que crece con puertas `×`.
 *
 * ## Suelo de 1 tropa DENTRO de la ronda
 * Una puerta o un láser pueden dejar el ejército en 0 (p. ej. `÷2` sobre 1 tropa). Se aplica un
 * suelo de **1** tras cada operación: un jugador corriendo sin ejército no tendría nada que
 * dibujar ni forma de interactuar, y la derrota ya tiene su lugar natural (el combate, donde 1
 * tropa pierde contra cualquier enemigo real). La puerta letal castiga igual — deja al jugador
 * casi sin remontada — pero la partida sigue siendo jugable hasta el choque.
 *
 * ## Feedback como eventos (no audio directo)
 * Cada momento de feedback se emite como [LegionEffect] semántico por [effects]: la lógica queda
 * 100 % portable y testeable (un test afirma "cruzar esta puerta emitió GATE_LOSS" sin
 * plataforma) y es el ViewModel quien traduce a `AudioAndHapticManager`.
 *
 * @param random inyectable para tests deterministas de generación de pista y combate.
 */
class LegionEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<LegionState>(GameIds.NEON_LEGION, difficulty, scope, audio) {

    private val _state = MutableStateFlow(LegionState())
    override val state: StateFlow<LegionState> = _state.asStateFlow()

    /**
     * Canal de efectos de juego. `BUFFERED` para no perder un evento si el colector va un
     * instante por detrás; se consume una sola vez (semántica one-shot).
     */
    private val _effects = Channel<LegionEffect>(Channel.BUFFERED)

    /** Flujo de efectos que el ViewModel colecta para audio/háptica y reenvío a la UI. */
    val effects: Flow<LegionEffect> = _effects.receiveAsFlow()

    private val frameClock = FrameClock(maxDtSec = MAX_DT_SEC)

    /** Ids monotónicos para filas, destellos y naves (estables dentro de la partida). */
    private var nextRowId = 1L
    private var nextFlashId = 1L
    private var nextShipId = 1L

    /**
     * ¿Queda por entrar la escolta con láser vertical de esta ronda? Su señal de entrada NO es
     * una fila cruzada sino la **aparición del ejército enemigo** ([LegionBalance.ENEMY_VISIBLE_Y]):
     * escolta y ejército son la misma amenaza y tienen que entrar juntos en escena.
     */
    private var escortPending = false

    /**
     * Fila que, al cruzarse, dispara el barrido horizontal; `null` si esta ronda no lleva. A
     * diferencia de la escolta, el barrido sí interrumpe en mitad de la carrera: ahí está su
     * gracia, cortar la racha de puertas.
     */
    private var pendingSweepTrigger: Int? = null

    /**
     * Cuenta atrás del "beat" de resolución del combate (segundos). Mientras corre, la fase es
     * [LegionPhase.COMBAT] y la pista está congelada: el jugador ve el choque antes de que el
     * motor decida avanzar de ronda, ofrecer el revive o terminar.
     */
    private var combatBeatSec = 0f

    /** La ronda máxima ALCANZADA (para [reachedMetric]): sobrevive aunque el estado se reinicie. */
    private var bestRound = 1

    override fun onStart() {
        frameClock.reset()
        nextRowId = 1L
        nextFlashId = 1L
        nextShipId = 1L
        combatBeatSec = 0f
        bestRound = 1
        _state.value = startRound(
            base = LegionState(),
            round = 1,
            entryTroops = LegionBalance.INITIAL_TROOPS,
        )
    }

    override fun onPause() {
        // Descartar el delta acumulado evita un salto de física al reanudar.
        frameClock.reset()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Entrada del jugador
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * El dedo apunta a [laneX] (unidades de carril continuas): fija el objetivo que el ejército
     * perseguirá en los siguientes ticks. NO mueve nada de inmediato — el desplazamiento real lo
     * hace el suavizado de [stepRace], que es de donde sale la sensación de arrastrar una masa.
     *
     * Ignora toques fuera de la carrera (antesala, pausa, examen, combate): durante el examen
     * los únicos controles válidos son los botones de opción, y en combate ya no hay nada que
     * esquivar. El recorte a `[0, carriles−1]` es lo que impide que el dedo saque a la legión
     * fuera de la pista.
     */
    fun aimAt(laneX: Float) {
        val s = _state.value
        if (!canSteer(s)) return
        _state.update { it.copy(aimX = laneX.coerceIn(0f, (it.lanes - 1).toFloat())) }
    }

    /** ¿Se puede pilotar ahora? Solo corriendo, en fase de carrera y sin revive en oferta. */
    private fun canSteer(s: LegionState): Boolean =
        status.value == GameStatus.RUNNING && s.phase == LegionPhase.RACING && !s.awaitingRevive

    /**
     * El jugador pulsa una opción del examen del barrido horizontal. La validación contra
     * [LaserQuiz.correctIndex] ocurre AQUÍ, nunca en la UI (la pantalla no sabe cuál es la
     * buena).
     *
     *  - **Correcta** → destruye el láser y la carrera continúa desde donde estaba (la pista
     *    solo estaba congelada, ver cabecera de la clase).
     *  - **Incorrecta** → pérdida directa del 50 % de las tropas VIVAS (además de lo ya drenado
     *    por tiempo: responder mal tarde duele más que responder mal pronto, que es el orden de
     *    castigos correcto) y el evento termina igualmente.
     */
    fun answerQuiz(optionIndex: Int) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.phase != LegionPhase.QUIZ) return
        val sweep = s.sweep ?: return
        if (optionIndex !in sweep.quiz.options.indices) return

        val correct = optionIndex == sweep.quiz.correctIndex
        val newTroops = if (correct) s.troops else (s.troops - s.troops / 2).coerceAtLeast(1)

        _effects.trySend(
            LegionEffect.PlaySound(
                if (correct) LegionEffect.PlaySound.Cue.QUIZ_CORRECT
                else LegionEffect.PlaySound.Cue.QUIZ_WRONG,
            ),
        )
        _effects.trySend(
            LegionEffect.Vibrate(
                if (correct) LegionEffect.Vibrate.Cue.SUCCESS else LegionEffect.Vibrate.Cue.ERROR,
            ),
        )

        _state.update {
            it.copy(
                troops = newTroops,
                phase = LegionPhase.RACING,
                sweep = null,
                quizTotal = it.quizTotal + 1,
                quizCorrect = it.quizCorrect + if (correct) 1 else 0,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Revive
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Concede el revive (el anuncio recompensado YA terminó con `EARNED`; el flujo del anuncio
     * vive en la pantalla): restaura las tropas con las que se entró a la ronda y la reinicia
     * con una pista nueva. La pista se REGENERA en vez de reutilizarse a propósito: repetir
     * exactamente las mismas puertas convertiría el revive en memorización, no en una segunda
     * oportunidad del mismo reto (el enemigo se redimensiona igual, así que la exigencia no baja).
     */
    fun grantRevive() {
        val s = _state.value
        if (!s.awaitingRevive || status.value != GameStatus.RUNNING) return
        frameClock.reset()
        _state.value = startRound(
            base = s.copy(reviveUsed = true, awaitingRevive = false),
            round = s.round,
            entryTroops = s.roundStartTroops,
        )
    }

    /** El jugador rechazó revivir (o expiró la oferta): ahora sí, fin de partida real. */
    fun declineRevive() {
        val s = _state.value
        if (!s.awaitingRevive || status.value != GameStatus.RUNNING) return
        _state.update { it.copy(awaitingRevive = false) }
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Bucle de simulación
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Avanza la simulación con el timestamp absoluto del frame (`withFrameNanos`).
     *
     * @param frameNanos tiempo monotónico del frame actual.
     */
    fun onFrame(frameNanos: Long) {
        if (status.value != GameStatus.RUNNING) return
        val current = _state.value

        // `null` = primer frame tras arrancar/reanudar o dt==0: no integramos este frame.
        val dtSec = frameClock.tick(frameNanos) ?: return

        // Oferta de revive en pantalla: la simulación entera queda congelada (el FrameClock
        // sigue consumiéndose para que al decidir no llegue un dt gigante de golpe).
        if (current.awaitingRevive) return

        _state.value = when (current.phase) {
            LegionPhase.RACING -> stepRace(current, dtSec)
            LegionPhase.COMBAT -> stepCombat(current, dtSec)
            LegionPhase.QUIZ -> stepQuiz(current, dtSec)
            LegionPhase.BOSS -> stepBoss(current, dtSec)
        }
    }

    /**
     * Duelo contra el Jefe: la legión le quita vida sin parar mientras él fulmina al 30 % cada
     * dos segundos. Gana quien llegue antes a cero.
     *
     * El daño de la legión se aplica de forma **continua** (`tropas × dt`) y el del Jefe en
     * **andanadas** discretas. Esa asimetría es la que crea la tensión del duelo: el jugador ve
     * su daño por segundo desplomarse de golpe con cada rayo, y a partir de ahí es una carrera
     * entre lo que le queda de legión y lo que le queda de vida al Jefe.
     */
    private fun stepBoss(state: LegionState, dtSec: Float): LegionState {
        val boss = state.boss ?: return state.copy(phase = LegionPhase.RACING)

        // Daño continuo de la legión: cae solo según van muriendo naves.
        val hp = boss.hp - state.troops * LegionBalance.BOSS_DAMAGE_PER_SHIP_SEC * dtSec
        var troops = state.troops
        var nextShot = boss.nextShotSec - dtSec
        var beam = (boss.beamRemainingSec - dtSec).coerceAtLeast(0f)
        var volleyAge = boss.volleyAgeSec + dtSec
        var beforeVolley = boss.troopsBeforeVolley

        // Andanada del Jefe. Se cobra solo si sigue vivo: un Jefe que muere en este mismo frame
        // no llega a disparar (si no, el jugador perdería naves DESPUÉS de haberlo derrotado).
        if (nextShot <= 0f && hp > 0f) {
            nextShot += LegionBalance.BOSS_SHOT_INTERVAL_SEC
            beforeVolley = troops
            troops = (troops * (1f - LegionBalance.BOSS_SHOT_KILL_FRACTION)).toInt()
            beam = LegionBalance.BOSS_BEAM_DURATION_SEC
            volleyAge = 0f
            _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LASER_HIT))
            _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.ERROR))
        }

        val advanced = state.copy(
            troops = troops,
            boss = boss.copy(
                hp = hp,
                elapsedSec = boss.elapsedSec + dtSec,
                nextShotSec = nextShot,
                beamRemainingSec = beam,
                volleyAgeSec = volleyAge,
                troopsBeforeVolley = beforeVolley,
            ),
        )

        return when {
            // Jefe abatido: se avanza de ronda con lo que quede de legión.
            hp <= 0f -> {
                bestRound = max(bestRound, advanced.round + 1)
                _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.COMBAT_WIN))
                _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.SUCCESS))
                startRound(
                    base = advanced.copy(boss = null),
                    round = advanced.round + 1,
                    entryTroops = troops.coerceAtLeast(1),
                )
            }

            // Legión aniquilada: mismo desenlace que perder un choque normal.
            troops <= 0 -> {
                _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.COMBAT_LOSS))
                _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.ERROR))
                if (!advanced.reviveUsed) {
                    advanced.copy(awaitingRevive = true)
                } else {
                    finish()
                    advanced
                }
            }

            else -> advanced
        }
    }

    /**
     * Fase de examen: la pista está congelada y solo corren el temporizador del barrido y el
     * drenaje gradual. El drenaje se calcula contra la FOTO de tropas al abrir el examen
     * ([SpaceshipEvent.HorizontalSweep.troopsAtStart]) como objetivo absoluto acumulado
     * (`drainedTroops`), no como resta proporcional por frame: así alcanza EXACTAMENTE el 50 %
     * prometido al agotarse el tiempo, sin decaimiento exponencial ni error de redondeo por
     * frame acumulado.
     *
     * Si el tiempo se agota sin respuesta, el examen se da por fallado (cuenta en `quizTotal`)
     * pero SIN el 50 % extra de la respuesta incorrecta: el drenaje ya se quedó con la mitad
     * del ejército, y castigar el silencio igual que el error quitaría la decisión de "no
     * arriesgar y aguantar el drenaje" que hace interesante a los segundos finales.
     */
    private fun stepQuiz(state: LegionState, dtSec: Float): LegionState {
        val sweep = state.sweep ?: return state.copy(phase = LegionPhase.RACING)
        val advanced = sweep.copy(
            timeRemainingSec = (sweep.timeRemainingSec - dtSec).coerceAtLeast(0f),
        )
        val remaining = advanced.timeRemainingSec

        // Drenaje gradual: las tropas caen al ritmo del ATAQUE de la nave, que es el mismo valor
        // con el que el render decide cuántas naves revienta (ver HorizontalSweep.attackProgress).
        // Leerlo de ahí en vez de recalcular la ventana aquí es lo que mantiene sincronizadas las
        // explosiones de la pantalla con las tropas que de verdad se pierden.
        val targetDrain = (advanced.doomedTroops * advanced.attackProgress).toInt()
        val drainDelta = (targetDrain - sweep.drainedTroops).coerceAtLeast(0)
        val newTroops = (state.troops - drainDelta).coerceAtLeast(1)

        if (remaining <= 0f) {
            // Tiempo agotado: examen fallado, la carrera continúa (ver KDoc de la función).
            _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.QUIZ_WRONG))
            _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.ERROR))
            return state.copy(
                troops = newTroops,
                phase = LegionPhase.RACING,
                sweep = null,
                quizTotal = state.quizTotal + 1,
                flashes = ageFlashes(state.flashes, dtSec),
            )
        }

        return state.copy(
            troops = newTroops,
            sweep = advanced.copy(drainedTroops = targetDrain),
            flashes = ageFlashes(state.flashes, dtSec),
        )
    }

    /**
     * Un paso de carrera de [dtSec] segundos: (0) el ejército persigue al dedo, (1) todo cae
     * `speed * dt`, (2) se resuelven los cruces de fila, (3) se detecta la llegada del enemigo,
     * (4) envejecen los destellos.
     */
    private fun stepRace(previous: LegionState, dtSec: Float): LegionState {
        val fall = previous.speed * dtSec

        // --- (0) Seguimiento del dedo --------------------------------------------------------
        // Suavizado exponencial hacia `aimX` con constante de tiempo AIM_FOLLOW_TAU_SEC. El
        // factor se calcula con exp(−dt/τ) y NO con una fracción fija por frame: así el retardo
        // percibido es el mismo a 60 o a 120 Hz, y un frame largo (tras una pausa) no
        // teletransporta la legión al dedo.
        //
        // Se aplica lo PRIMERO del tick, y sobre `state` corre TODO lo que viene después, a
        // propósito: las puertas y los láseres de este mismo frame deben resolverse contra la
        // posición ya actualizada. Al revés, el jugador vería su legión entrar en un carril y
        // aun así chocar con el anterior.
        val follow = 1f - exp(-dtSec / LegionBalance.AIM_FOLLOW_TAU_SEC)
        val state = previous.copy(
            playerX = previous.playerX + (previous.aimX - previous.playerX) * follow,
        )
        // El "clic" de cambio de carril suena cuando la legión CRUZA de verdad —no al mover el
        // dedo—, que es lo coherente con que el movimiento tenga retardo.
        if (state.playerLane != previous.playerLane) {
            _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LANE_SWITCH))
        }

        // --- (1)+(2) Caída de filas y resolución de cruces -----------------------------------
        // Una fila "cruza" cuando su y alcanza PLAYER_Y. Con el dt recortado por FrameClock a
        // 50 ms y la velocidad máxima (~0.43 u/s) el avance por frame (≤0.022) es muy inferior
        // al espaciado entre filas (0.65): jamás cruzan dos filas el umbral en el mismo frame.
        var troops = state.troops
        var optimalPicks = state.optimalPicks
        var totalPicks = state.totalPicks
        var rowsCleared = state.rowsCleared
        var flashes = state.flashes
        val survivingRows = ArrayList<GateRow>(state.gateRows.size)

        for (row in state.gateRows) {
            val nextY = row.y + fall
            if (nextY < LegionBalance.PLAYER_Y) {
                survivingRows += row.copy(y = nextY)
                continue
            }

            // Cruce: aplica la puerta del carril del jugador y registra si fue la óptima.
            val gate = row.gates.first { it.lane == state.playerLane }
            val results = row.gates.map { it.operation.apply(troops).coerceAtLeast(1) }
            val picked = gate.operation.apply(troops).coerceAtLeast(1)
            val bestPossible = results.max()

            totalPicks++
            if (picked == bestPossible) optimalPicks++
            rowsCleared++
            troops = picked

            val positive = gate.operation.isPositive
            flashes = flashes + GateFlash(
                id = nextFlashId++,
                lane = state.playerLane,
                positive = positive,
            )
            val soundCue = if (positive) LegionEffect.PlaySound.Cue.GATE_GAIN
            else LegionEffect.PlaySound.Cue.GATE_LOSS
            val hapticCue = if (positive) LegionEffect.Vibrate.Cue.SUCCESS
            else LegionEffect.Vibrate.Cue.ERROR
            _effects.trySend(LegionEffect.PlaySound(soundCue))
            _effects.trySend(LegionEffect.Vibrate(hapticCue))
            // La fila cruzada NO sobrevive: se elimina en vez de marcarse (ver KDoc de GateRow).
        }

        // --- (3) Naves de láser vertical: carga, disparo y expiración ------------------------
        // El daño se aplica UNA vez, en la transición carga→disparo; el rayo visible que queda
        // (firingRemainingSec) es solo feedback (ver KDoc de VerticalLaser).
        var lasers = ArrayList<SpaceshipEvent.VerticalLaser>(state.verticalLasers.size)
        for (laser in state.verticalLasers) {
            if (laser.firing) {
                val left = laser.firingRemainingSec - dtSec
                if (left > 0f) lasers += laser.copy(firingRemainingSec = left)
                continue // Expirado: desaparece.
            }
            val charge = laser.chargeRemainingSec - dtSec
            if (charge > 0f) {
                lasers += laser.copy(chargeRemainingSec = charge)
                continue
            }
            // Disparo. ¿Sigue el jugador en el carril amenazado?
            if (laser.lane == state.playerLane) {
                troops = (troops - troops / 2).coerceAtLeast(1) // Pierde floor(50 %), mínimo 1.
                _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LASER_HIT))
                _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.ERROR))
                flashes = flashes + GateFlash(
                    id = nextFlashId++,
                    lane = state.playerLane,
                    positive = false,
                )
            } else {
                _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LASER_FIRE))
            }
            lasers += laser.copy(
                chargeRemainingSec = 0f,
                firingRemainingSec = LegionBalance.LASER_FIRE_DURATION_SEC,
            )
        }

        // --- (4) Avance del enemigo ----------------------------------------------------------
        // Frena en ENEMY_CLASH_Y (tercer quinto), no encima del jugador: los dos ejércitos
        // quedan enfrentados con un quinto de separación (ver KDoc de la constante).
        val enemyY = (state.enemyY ?: 0f) + fall
        val enemyArrived = enemyY >= LegionBalance.ENEMY_CLASH_Y

        // --- (5) ¿Entra ya la escolta? --------------------------------------------------------
        // Se engancha a la aparición del ejército enemigo, no a una fila cruzada: los dos son la
        // misma amenaza y entran juntos. Desde ese punto la escolta tiene el tramo
        // ENEMY_VISIBLE_Y → ENEMY_CLASH_Y para cargar y disparar (2,6 s en la ronda 3, 1,8 s
        // desde la 10), holgado sobre su carga de 2,0 s → 0,8 s.
        //
        // La condición del cielo despejado sigue: si el barrido acaba de dejar naves en pantalla
        // la escolta espera al siguiente frame libre en vez de amontonarse.
        if (escortPending && enemyY >= LegionBalance.ENEMY_VISIBLE_Y && lasers.isEmpty()) {
            escortPending = false
            lasers += spawnVerticalEvent(state.round, state.lanes)
        }

        val advanced = state.copy(
            troops = troops,
            optimalPicks = optimalPicks,
            totalPicks = totalPicks,
            rowsCleared = rowsCleared,
            gateRows = survivingRows,
            verticalLasers = lasers,
            enemyY = enemyY,
            flashes = ageFlashes(flashes, dtSec),
        )

        // --- (6) Prioridades de transición: combate > barrido --------------------------------
        // Si el enemigo llega en el mismo frame que un trigger de barrido, gana el combate: un
        // examen a pantalla completa con el choque ya encima no podría resolverse con sentido.
        if (enemyArrived) return beginCombat(advanced)

        val sweepTrigger = pendingSweepTrigger
        if (sweepTrigger != null && rowsCleared >= sweepTrigger) {
            pendingSweepTrigger = null
            return beginSweep(advanced)
        }
        return advanced
    }

    /**
     * Genera las naves de un evento vertical: carriles DISTINTOS elegidos al azar, tantas naves
     * como diga [LegionBalance.shipsPerVerticalEvent] — que por su invariante nunca iguala al
     * número de carriles, así que **siempre queda al menos un carril libre** al que huir.
     */
    private fun spawnVerticalEvent(round: Int, lanes: Int): List<SpaceshipEvent.VerticalLaser> {
        val ships = LegionBalance.shipsPerVerticalEvent(round)
        val charge = LegionBalance.laserChargeSeconds(round)
        return (0 until lanes).shuffled(random).take(ships).map { lane ->
            SpaceshipEvent.VerticalLaser(
                id = nextShipId++,
                lane = LaneIndex(lane),
                chargeTotalSec = charge,
                chargeRemainingSec = charge,
            )
        }
    }

    /**
     * Abre el barrido horizontal: limpia cualquier láser vertical en curso (la nave "barre
     * todos los carriles", también a las suyas), congela la pista pasando a [LegionPhase.QUIZ]
     * y publica el examen. Las puertas NO se eliminan del dominio (ver cabecera de la clase).
     */
    private fun beginSweep(state: LegionState): LegionState {
        _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LASER_FIRE))
        return state.copy(
            phase = LegionPhase.QUIZ,
            verticalLasers = emptyList(),
            sweep = SpaceshipEvent.HorizontalSweep(
                quiz = generateQuiz(state.round),
                timeRemainingSec = LegionBalance.QUIZ_TIME_LIMIT_SEC,
                troopsAtStart = state.troops,
            ),
        )
    }

    /**
     * Abre la fase de combate: congela la pista, publica la foto del choque ([CombatStats]) y
     * arma el beat de resolución. El feedback suena AQUÍ (en el choque), no al terminar el beat:
     * el beat es tiempo de lectura del resultado, no suspense artificial.
     */
    private fun beginCombat(state: LegionState): LegionState {
        // Las rondas de Jefe no se resuelven comparando tropas: abren un duelo por tiempo.
        if (state.isBossRound) return beginBossFight(state)

        val combat = CombatStats(
            playerTroops = state.troops,
            enemyTroops = state.enemyTroops,
        )
        combatBeatSec = COMBAT_BEAT_SEC
        val soundCue = if (combat.playerWins) LegionEffect.PlaySound.Cue.COMBAT_WIN
        else LegionEffect.PlaySound.Cue.COMBAT_LOSS
        val hapticCue = if (combat.playerWins) LegionEffect.Vibrate.Cue.SUCCESS
        else LegionEffect.Vibrate.Cue.ERROR
        _effects.trySend(LegionEffect.PlaySound(soundCue))
        _effects.trySend(LegionEffect.Vibrate(hapticCue))
        // Se fija la Y exacta del frente: el último frame pudo pasarse unos milésimos del
        // umbral y el enemigo debe quedar clavado ahí durante todo el combate. La escolta se
        // retira: su láser ya no puede resolverse (el tick de carrera no corre en COMBAT) y
        // dejarla congelada a medio cargar sería un adorno muerto sobre el choque.
        return state.copy(
            phase = LegionPhase.COMBAT,
            combat = combat,
            enemyY = LegionBalance.ENEMY_CLASH_Y,
            verticalLasers = emptyList(),
        )
    }

    /**
     * Abre el duelo contra el Jefe: fija su vida en proporción a la legión que llega
     * ([LegionBalance.BOSS_HP_PER_TROOP] por nave) y lo clava en la línea de combate.
     *
     * El primer rayo no sale hasta pasados los dos segundos de rigor: el jugador entra al duelo
     * con una andanada de gracia en la que ve la barra de vida bajar antes de recibir el primer
     * golpe, que es lo que le enseña la mecánica sin tener que leerla.
     */
    private fun beginBossFight(state: LegionState): LegionState {
        val maxHp = (state.troops * LegionBalance.BOSS_HP_PER_TROOP).toFloat()
        _effects.trySend(LegionEffect.PlaySound(LegionEffect.PlaySound.Cue.LASER_FIRE))
        _effects.trySend(LegionEffect.Vibrate(LegionEffect.Vibrate.Cue.ERROR))
        return state.copy(
            phase = LegionPhase.BOSS,
            boss = BossFight(hp = maxHp, maxHp = maxHp, troopsAtStart = state.troops),
            enemyY = LegionBalance.ENEMY_CLASH_Y,
            verticalLasers = emptyList(),
        )
    }

    /**
     * Fase de combate: solo consume el beat de resolución (y desvanece destellos). Al agotarse:
     *  - victoria → siguiente ronda con el EXCEDENTE de tropas (`jugador - enemigo`, mínimo 1):
     *    ganar por poco deja un ejército mermado, que es el castigo natural de rozar el límite;
     *  - derrota con revive disponible → congela la partida en la oferta ([grantRevive]/
     *    [declineRevive] la resuelven);
     *  - derrota sin revive → [finish] (publica el resultado y el ViewModel lo guarda).
     */
    private fun stepCombat(state: LegionState, dtSec: Float): LegionState {
        combatBeatSec -= dtSec
        // El progreso del beat viaja en el estado: es lo que sincroniza la resolución con las
        // explosiones y el encogimiento de los dos enjambres (ver KDoc de CombatStats.progress).
        val progress = (1f - combatBeatSec / COMBAT_BEAT_SEC).coerceIn(0f, 1f)
        val aged = state.copy(
            combat = state.combat?.copy(progress = progress),
            flashes = ageFlashes(state.flashes, dtSec),
        )
        if (combatBeatSec > 0f) return aged

        val combat = aged.combat ?: return aged
        return when {
            combat.playerWins -> {
                bestRound = max(bestRound, aged.round + 1)
                startRound(
                    base = aged,
                    round = aged.round + 1,
                    entryTroops = (combat.playerTroops - combat.enemyTroops).coerceAtLeast(1),
                )
            }

            !aged.reviveUsed -> aged.copy(awaitingRevive = true)

            else -> {
                finish()
                aged
            }
        }
    }

    /** Envejece los destellos y elimina los que superaron su vida útil. */
    private fun ageFlashes(flashes: List<GateFlash>, dtSec: Float): List<GateFlash> =
        flashes.mapNotNull { flash ->
            val aged = flash.copy(ageSec = flash.ageSec + dtSec)
            aged.takeIf { it.ageSec < LegionBalance.FLASH_DURATION_SEC }
        }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Generación de la pista
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Construye el estado de arranque de una ronda: pista de puertas nueva, enemigo dimensionado
     * y contadores de ronda a cero — conservando de [base] todo lo que es de PARTIDA (contadores
     * de eficiencia, `reviveUsed`) y no de ronda.
     *
     * El jugador se recoloca en el carril central-izquierdo (`lanes/2` truncado: carril 1 de 2 →
     * índice 0; de 3 → índice 1) para que la expansión a 3 carriles de la ronda 7 no lo deje
     * pegado a un borde recién aparecido.
     */
    private fun startRound(base: LegionState, round: Int, entryTroops: Int): LegionState {
        val lanes = LegionBalance.lanesForRound(round)
        val rowCount = LegionBalance.gateRowsForRound(round)

        // Genera las filas encadenando la PROYECCIÓN óptima: los valores de cada fila se
        // escalan a las tropas que tendría un jugador perfecto al llegar a ella, no a las de
        // entrada — con valores fijos, tras un par de `×2` las puertas `+8` serían ruido.
        //
        // El crecimiento va DIRIGIDO al objetivo de la ronda ([targetTroopsForRound]) repartido
        // entre las filas que quedan, y se recalcula fila a fila: así el reparto se
        // autocorrige (si un `×2` se pasa de la raya, las filas siguientes crecen menos) y el
        // ejército nunca se dispara fuera del rango 0..1000.
        val target = LegionBalance.targetTroopsForRound(round)
        val rows = ArrayList<GateRow>(rowCount)
        var projected = entryTroops
        for (i in 0 until rowCount) {
            val row = generateRow(
                y = LegionBalance.SPAWN_Y - i * LegionBalance.GATE_ROW_SPACING,
                lanes = lanes,
                projectedTroops = projected,
                remainingRows = rowCount - i,
                targetTroops = target,
            )
            rows += row
            projected = row.gates.maxOf { it.operation.apply(projected).coerceAtLeast(1) }
        }

        // Calendario de eventos láser (ver cabecera). La escolta no lleva índice de fila: entra
        // cuando el enemigo asoma (se comprueba en el tick). El barrido sí, en una fila sorteada
        // que nunca es la 0, para dar aire al arranque de la ronda.
        escortPending = LegionBalance.hasVerticalLaser(round)
        pendingSweepTrigger = if (LegionBalance.sweepsForRound(round) > 0) {
            (1 until rowCount).random(random)
        } else {
            null
        }

        // Enemigo relativo al recorrido óptimo (Decisión 4); los Jefes exigen un poco más.
        var factor = LegionBalance.enemyFactor(round)
        if (LegionBalance.isBossRound(round)) {
            factor = (factor + LegionBalance.BOSS_FACTOR_BONUS)
                .coerceAtMost(LegionBalance.BOSS_FACTOR_CAP)
        }
        val enemyTroops = ceil(projected * factor).toInt().coerceAtLeast(1)

        return base.copy(
            round = round,
            troops = entryTroops,
            roundStartTroops = entryTroops,
            // La posición NO se reinicia entre rondas: la legión se queda donde el jugador tenga
            // el dedo. Recolocarla en el centro le arrancaba el control de las manos justo al
            // empezar la ronda nueva —con la primera fila ya cayendo— y obligaba a recuperar la
            // posición a ciegas. Solo se recorta al abrirse el tercer carril (ronda 7), donde el
            // rango cambia; ahí la legión conserva su carril y el nuevo aparece a su lado.
            playerX = base.playerX.coerceIn(0f, (lanes - 1).toFloat()),
            aimX = base.aimX.coerceIn(0f, (lanes - 1).toFloat()),
            phase = LegionPhase.RACING,
            gateRows = rows,
            verticalLasers = emptyList(),
            sweep = null,
            combat = null,
            boss = null,
            enemyTroops = enemyTroops,
            // El hueco tras la última puerta depende de si esta ronda trae escolta: largo para
            // que quepa su carga, corto si no hay nada que llenarlo (ver enemyGapForRound).
            enemyY = LegionBalance.SPAWN_Y - rowCount * LegionBalance.GATE_ROW_SPACING -
                LegionBalance.enemyGapForRound(round),
            rowsCleared = 0,
            flashes = emptyList(),
        )
    }

    /**
     * Genera una fila de puertas (una por carril) para un jugador con [projectedTroops].
     *
     * Garantías del generador (spec del juego, verificadas por reintento):
     *  1. **Todas las puertas de la fila son del mismo signo** ([GateRowKind]): o todas suman, o
     *     todas restan, o es una fila mixta. Es la palanca principal de dificultad — ver el KDoc
     *     de [GateRowKind] sobre por qué una fila siempre mixta no obliga a calcular nada.
     *  2. **Nunca se puede morir en una puerta**: incluso en las filas negativas el motor aplica
     *     un suelo de 1 tropa (ver la cabecera de la clase). La derrota vive en el combate.
     *  3. **Las opciones son distinguibles**: los resultados difieren entre sí al menos
     *     [MIN_DISTINCT_FRACTION] de las tropas proyectadas — dos puertas que dan exactamente lo
     *     mismo convertirían la decisión en irrelevante. El umbral es bajo (8 %) a propósito:
     *     los emparejamientos interesantes (`×2` contra un `+n` grande) quedan MUY cerca, y con
     *     un umbral alto el generador los rechazaba justo por ser los difíciles.
     *
     * Se intenta hasta [MAX_ROW_ATTEMPTS] veces y, si el azar se empeña en colisionar (posible
     * con tropas muy bajas, donde el espacio de resultados es diminuto), se cae a una fila
     * canónica determinista.
     */
    private fun generateRow(
        y: Float,
        lanes: Int,
        projectedTroops: Int,
        remainingRows: Int,
        targetTroops: Int,
    ): GateRow {
        val kind = pickRowKind()
        repeat(MAX_ROW_ATTEMPTS) {
            val operations = when (kind) {
                GateRowKind.POSITIVE -> positiveRow(projectedTroops, remainingRows, targetTroops, lanes)
                GateRowKind.NEGATIVE -> negativeRow(projectedTroops, lanes)
                GateRowKind.MIXED -> mixedRow(projectedTroops, remainingRows, targetTroops, lanes)
            }.shuffled(random) // La puerta buena no debe caer siempre en el mismo carril.

            val results = operations.map { it.apply(projectedTroops).coerceAtLeast(1) }
            if (resultsDistinguishable(results, projectedTroops)) {
                return GateRow(
                    id = nextRowId++,
                    y = y,
                    kind = kind,
                    gates = operations.mapIndexed { lane, op -> MathGate(LaneIndex(lane), op) },
                )
            }
        }

        // Fila canónica de respaldo (mixta): una suma que sigue el objetivo y castigos claros.
        // Los resultados (~crecimiento, ~0.5x, ~0.66x) quedan separados por construcción.
        val fallback = buildList {
            add(bestGrowthOperation(projectedTroops, remainingRows, targetTroops))
            add(GateOperation.Divide(2))
            if (lanes >= 3) add(GateOperation.Subtract(max(projectedTroops / 3, 1)))
        }.shuffled(random)
        return GateRow(
            id = nextRowId++,
            y = y,
            kind = GateRowKind.MIXED,
            gates = fallback.mapIndexed { lane, op -> MathGate(LaneIndex(lane), op) },
        )
    }

    /** Sortea qué clase de decisión plantea la siguiente fila (ver [GateRowKind]). */
    private fun pickRowKind(): GateRowKind {
        val roll = random.nextFloat()
        return when {
            roll < ROW_POSITIVE_WEIGHT -> GateRowKind.POSITIVE
            roll < ROW_POSITIVE_WEIGHT + ROW_MIXED_WEIGHT -> GateRowKind.MIXED
            else -> GateRowKind.NEGATIVE
        }
    }

    /**
     * Fila POSITIVA: todas suman, y la gracia está en cuál suma más.
     *
     * Cuando duplicar cabe en la escala se emparejan `×2` y `+n` con `n` entre el 55 % y el 92 %
     * de las tropas — es decir, resultados entre `1,55×` y `1,92×` frente al `2×`. Quedan
     * deliberadamente cerca: el jugador no puede resolverlo de un vistazo por orden de magnitud,
     * tiene que calcular. Y como el error solo cuesta unas tropas de más o de menos (ambas
     * suman), es una dificultad que enseña sin castigar.
     *
     * Si duplicar se sale de la escala, la fila degenera en dos sumas de distinto tamaño: fácil,
     * pero mantiene la promesa de que en una fila positiva nada resta.
     */
    private fun positiveRow(projected: Int, remainingRows: Int, target: Int, lanes: Int): List<GateOperation> {
        val doubleFits = projected * 2 <= min(
            (target * MULTIPLY_TARGET_SLACK).toInt(),
            LegionBalance.TROOPS_SOFT_CAP,
        )
        val best: GateOperation
        val gainCeiling: Int
        if (doubleFits) {
            best = GateOperation.Multiply(2)
            gainCeiling = projected // La ganancia del ×2 es exactamente `projected`.
        } else {
            best = bestGrowthOperation(projected, remainingRows, target)
            gainCeiling = (best.apply(projected) - projected).coerceAtLeast(2)
        }
        return buildList {
            add(best)
            repeat(lanes - 1) {
                val share = POSITIVE_DECOY_MIN_SHARE +
                    random.nextFloat() * (POSITIVE_DECOY_MAX_SHARE - POSITIVE_DECOY_MIN_SHARE)
                add(GateOperation.Add((gainCeiling * share).roundToInt().coerceAtLeast(1)))
            }
        }
    }

    /**
     * Fila NEGATIVA: todas restan y se elige la menos mala. El emparejamiento buscado es `÷2`
     * (siempre la mitad) contra un `−n` cercano a esa mitad: cuál duele menos depende del tamaño
     * del ejército, así que vuelve a exigir cálculo en vez de reconocer un símbolo.
     *
     * La mejor opción pierde entre el 12 % y el 28 %, muy por debajo del 50 % del `÷2`, para que
     * una fila negativa sea un peaje y no una sentencia: el reparto de las filas siguientes
     * recupera el terreno solo (ver [bestGrowthOperation]).
     */
    private fun negativeRow(projected: Int, lanes: Int): List<GateOperation> {
        val bestLoss = (projected * (NEGATIVE_BEST_MIN_LOSS +
            random.nextFloat() * (NEGATIVE_BEST_MAX_LOSS - NEGATIVE_BEST_MIN_LOSS)))
            .roundToInt().coerceAtLeast(1)
        return buildList {
            add(GateOperation.Subtract(bestLoss))
            repeat(lanes - 1) {
                if (random.nextFloat() < NEGATIVE_DIVIDE_WEIGHT) {
                    add(GateOperation.Divide(2))
                } else {
                    val lo = max(bestLoss + 1, ceil(projected * SUBTRACT_MIN_FRACTION).toInt())
                    val hi = max(lo + 1, ceil(projected * SUBTRACT_MAX_FRACTION).toInt())
                    add(GateOperation.Subtract(random.nextInt(lo, hi + 1)))
                }
            }
        }
    }

    /** Fila MIXTA: la buena suma (y marca el ritmo de la ronda), las demás castigan. */
    private fun mixedRow(projected: Int, remainingRows: Int, target: Int, lanes: Int): List<GateOperation> =
        buildList {
            add(bestGrowthOperation(projected, remainingRows, target))
            repeat(lanes - 1) {
                if (random.nextFloat() < NEGATIVE_DIVIDE_WEIGHT) {
                    add(GateOperation.Divide(if (random.nextFloat() < RARE_FACTOR_WEIGHT) 3 else 2))
                } else {
                    val lo = max(1, ceil(projected * SUBTRACT_MIN_FRACTION).toInt())
                    val hi = max(lo + 1, ceil(projected * SUBTRACT_MAX_FRACTION).toInt())
                    add(GateOperation.Subtract(random.nextInt(lo, hi + 1)))
                }
            }
        }

    /**
     * La suma que sigue el recorrido óptimo hacia [targetTroops]: el crecimiento por fila es la
     * raíz [remainingRows]-ésima de lo que falta (`(target/projected)^(1/filas restantes)`),
     * recortado a `[MIN_ROW_GROWTH, MAX_ROW_GROWTH]`.
     *
     * Recalcularlo en CADA fila —en vez de repartir una vez al generar la ronda— es lo que hace
     * el reparto autocorrector: si una fila se pasa (un `×2`) o se queda corta (una fila
     * negativa), las siguientes ajustan solas.
     *
     * [MIN_ROW_GROWTH] es exactamente 1: con el 1,06 que tenía antes, una ronda que ya hubiera
     * alcanzado su objetivo seguía creciendo un 6 % por fila y, compuesto sobre catorce filas
     * (×2,3), se saltaba el techo de 1000 que precisamente sostiene la legibilidad de las cifras.
     */
    private fun bestGrowthOperation(projected: Int, remainingRows: Int, targetTroops: Int): GateOperation {
        val growth = (targetTroops.toFloat() / projected.toFloat())
            .pow(1f / remainingRows)
            .coerceIn(MIN_ROW_GROWTH, MAX_ROW_GROWTH)
        val gain = (projected * (growth - 1f)).roundToInt().coerceAtLeast(2)
        return GateOperation.Add(gain)
    }

    /** ¿Todos los resultados difieren entre sí lo bastante para que elegir importe? */
    private fun resultsDistinguishable(results: List<Int>, projectedTroops: Int): Boolean {
        val minGap = max(2, ceil(projectedTroops * MIN_DISTINCT_FRACTION).toInt())
        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                if (abs(results[i] - results[j]) < minGap) return false
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Generación del examen (barrido horizontal)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Borrador interno de un examen: la expresión, su resultado y los distractores CANDIDATOS
     * (aún sin filtrar ni barajar). Separarlo de [LaserQuiz] deja el ensamblado final (filtrar,
     * completar, barajar) en un único sitio ([assembleQuiz]) común a todos los niveles.
     */
    private data class QuizDraft(val prompt: String, val correct: Int, val plausible: List<Int>)

    /**
     * Genera la cuenta del examen, escalada en tres niveles que acompañan al número de opciones
     * ([LegionBalance.quizOptionCount]): dos operandos (rondas ≤ 8), tres términos (9-13) y
     * multiplicación con corrección (14+).
     *
     * Los distractores NO son números aleatorios: cada nivel propone los resultados de los
     * **errores típicos** de esa operación (signo invertido, error de acarreo ±10, factor
     * vecino en la tabla, saltarse el último término). Si los distractores se descartaran de un
     * vistazo por orden de magnitud, el examen no exigiría calcular — solo estimar — y dejaría
     * de ser un evento de cálculo mental.
     */
    private fun generateQuiz(round: Int): LaserQuiz {
        val draft = when {
            round <= 8 -> twoOperandDraft()
            round <= 13 -> threeTermDraft()
            else -> hardMultiplyDraft()
        }
        return assembleQuiz(draft, LegionBalance.quizOptionCount(round))
    }

    /** Nivel 1: `a + b`, `a − b` o `a × b` con operandos de una tabla escolar cómoda. */
    private fun twoOperandDraft(): QuizDraft = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(13, 49)
            QuizDraft(
                prompt = "$a + $b",
                correct = a + b,
                // Errores típicos de suma: acarreo olvidado/duplicado (±10) y resta por despiste.
                plausible = listOf(a + b + 10, a + b - 10, a + b + 1, a + b - 1, a - b),
            )
        }

        1 -> {
            val a = random.nextInt(25, 80)
            val b = random.nextInt(11, a - 1) // Resultado siempre ≥ 1.
            QuizDraft(
                prompt = "$a − $b",
                correct = a - b,
                // Préstamo mal hecho (±10), off-by-one y el signo invertido (a+b).
                plausible = listOf(a - b + 10, a - b - 10, a - b + 1, a - b - 1, a + b),
            )
        }

        else -> {
            val a = random.nextInt(3, 10)
            val b = random.nextInt(4, 13)
            QuizDraft(
                prompt = "$a × $b",
                correct = a * b,
                // Vecinos de la tabla de multiplicar: el error clásico es resbalar una fila.
                plausible = listOf(a * (b + 1), a * (b - 1), (a + 1) * b, (a - 1) * b, a * b + a),
            )
        }
    }

    /** Nivel 2: tres términos (`a + b − c` o `a × b ± c`): obliga a retener un parcial. */
    private fun threeTermDraft(): QuizDraft = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(15, 50)
            val b = random.nextInt(12, 40)
            val c = random.nextInt(5, a + b - 1) // Resultado ≥ 1.
            QuizDraft(
                prompt = "$a + $b − $c",
                correct = a + b - c,
                // Último signo invertido, término final olvidado y acarreo.
                plausible = listOf(a + b + c, a + b, a + b - c + 10, a + b - c - 10, a + b - c + 1),
            )
        }

        1 -> {
            val a = random.nextInt(4, 10)
            val b = random.nextInt(5, 13)
            val c = random.nextInt(8, 30)
            QuizDraft(
                prompt = "$a × $b + $c",
                correct = a * b + c,
                plausible = listOf(a * b - c, a * b, a * (b + 1) + c, a * b + c + 10, a * b + c - 1),
            )
        }

        else -> {
            val a = random.nextInt(4, 10)
            val b = random.nextInt(5, 13)
            val c = random.nextInt(3, a * b - 1) // Resultado ≥ 1.
            QuizDraft(
                prompt = "$a × $b − $c",
                correct = a * b - c,
                plausible = listOf(a * b + c, a * b, (a + 1) * b - c, a * b - c - 10, a * b - c + 1),
            )
        }
    }

    /** Nivel 3: multiplicación grande con corrección, fuera de la tabla memorizada. */
    private fun hardMultiplyDraft(): QuizDraft {
        val a = random.nextInt(6, 15)
        val b = random.nextInt(7, 16)
        val c = random.nextInt(11, 50)
        val plus = random.nextBoolean()
        val correct = if (plus) a * b + c else a * b - c
        return QuizDraft(
            prompt = if (plus) "$a × $b + $c" else "$a × $b − $c",
            correct = correct,
            plausible = listOf(
                if (plus) a * b - c else a * b + c, // Signo del término final invertido.
                a * b,                              // Término final olvidado.
                (a + 1) * b + if (plus) c else -c,  // Factor vecino.
                a * (b - 1) + if (plus) c else -c,
                correct + 10, correct - 10,         // Acarreo en la suma final.
            ),
        )
    }

    /**
     * Ensambla el [LaserQuiz] final: filtra distractores inválidos (negativos, duplicados o
     * iguales a la correcta), toma los necesarios al azar, rellena con desplazamientos ±3..12
     * si los plausibles no alcanzan (posible tras el filtrado en resultados pequeños) y baraja
     * la posición de la correcta — que un test puede verificar que NO es fija.
     */
    private fun assembleQuiz(draft: QuizDraft, optionCount: Int): LaserQuiz {
        val distractors = draft.plausible
            .filter { it >= 0 && it != draft.correct }
            .distinct()
            .shuffled(random)
            .take(optionCount - 1)
            .toMutableList()

        while (distractors.size < optionCount - 1) {
            val offset = random.nextInt(3, 13) * if (random.nextBoolean()) 1 else -1
            val filler = draft.correct + offset
            if (filler >= 0 && filler != draft.correct && filler !in distractors) {
                distractors += filler
            }
        }

        val correctIndex = random.nextInt(optionCount)
        val options = buildList {
            addAll(distractors.take(correctIndex))
            add(draft.correct)
            addAll(distractors.drop(correctIndex))
        }
        return LaserQuiz(prompt = draft.prompt, options = options, correctIndex = correctIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Puntuación
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Puntaje de la partida. MONÓTONO en la ronda alcanzada por la desigualdad documentada en
     * `LegionBalance` (2000 > 400 + 500 + 800): la peor partida que alcanza la ronda N+1 supera
     * SIEMPRE a la mejor que muere en la N, así el ranking nunca se invierte.
     *
     * El bonus de eficiencia premia jugar bien DENTRO de la ronda (fracción de puertas óptimas
     * elegidas y exámenes acertados) y el de tropas, morir con un ejército grande; ambos con
     * techo. El revive resta ([LegionBalance.REVIVE_PENALTY]): las ayudas siempre penalizan en
     * este proyecto (si no, el ranking premiaría ver anuncios).
     */
    override fun calculateScore(): Int {
        val s = _state.value
        val roundPoints = s.round * LegionBalance.POINTS_PER_ROUND
        val troopsBonus = s.troops.coerceAtMost(LegionBalance.TROOPS_BONUS_CAP)
        val attempts = s.totalPicks + s.quizTotal
        val efficiencyBonus = if (attempts == 0) 0 else {
            val hits = s.optimalPicks + s.quizCorrect
            (LegionBalance.EFFICIENCY_BONUS_MAX * hits) / attempts
        }
        val revivePenalty = if (s.reviveUsed) LegionBalance.REVIVE_PENALTY else 0
        return (roundPoints + troopsBonus + efficiencyBonus - revivePenalty).coerceAtLeast(0)
    }

    /**
     * Precisión = decisiones buenas / decisiones tomadas (puertas óptimas + exámenes
     * acertados). 100 si aún no hubo ninguna decisión (partida terminada antes de la primera
     * fila): no castigar lo que no llegó a intentarse.
     */
    override fun currentAccuracy(): Double {
        val s = _state.value
        val attempts = s.totalPicks + s.quizTotal
        if (attempts == 0) return 100.0
        return (s.optimalPicks + s.quizCorrect).toDouble() / attempts.toDouble() * 100.0
    }

    /**
     * Récord = ronda máxima ALCANZADA (juego ENDLESS, mayor = mejor; ver `GameProgressions`).
     * Se lleva en [bestRound] y no se lee de `state.round` directamente por el revive: reiniciar
     * la ronda tras revivir no debe poder rebajar la marca ya lograda.
     */
    override fun reachedMetric(): Int = bestRound

    private companion object {
        /** Techo del `dt` (50 ms): por encima preferimos cámara lenta a tunneling. */
        const val MAX_DT_SEC = 0.05f

        /** Beat de lectura del choque final antes de resolver (avanzar / revive / fin). */
        const val COMBAT_BEAT_SEC = 1.4f

        /** Reintentos del generador de fila antes de caer a la fila canónica de respaldo. */
        const val MAX_ROW_ATTEMPTS = 16

        /**
         * Separación mínima entre resultados de una fila, como fracción de las tropas. Bajo
         * (8 %) a propósito: los emparejamientos que de verdad hacen pensar (`×2` contra un
         * `+n` grande) quedan muy cerca, y un umbral alto los rechazaba justo por difíciles.
         */
        const val MIN_DISTINCT_FRACTION = 0.08f

        // Reparto de clases de fila (ver GateRowKind). Las positivas y las mixtas se llevan el
        // grueso; las negativas son la minoría porque son un peaje —restan siempre— y en exceso
        // convertirían la ronda en una cuesta abajo de la que no se puede salir.
        const val ROW_POSITIVE_WEIGHT = 0.4f
        const val ROW_MIXED_WEIGHT = 0.4f

        /** Margen que se le permite al `×2` sobre el objetivo de la ronda antes de descartarlo. */
        const val MULTIPLY_TARGET_SLACK = 1.2f

        /** Crecimiento mínimo/máximo por fila de la suma buena (ver [bestGrowthOperation]). */
        const val MIN_ROW_GROWTH = 1f
        const val MAX_ROW_GROWTH = 1.8f

        /** Probabilidad de que un `÷` use factor 3 en vez de 2. */
        const val RARE_FACTOR_WEIGHT = 0.25f

        // Señuelos de una fila POSITIVA: qué parte de la ganancia de la puerta buena ofrecen.
        // El rango llega al 92 % para que el resultado quede pegado al de la buena.
        const val POSITIVE_DECOY_MIN_SHARE = 0.55f
        const val POSITIVE_DECOY_MAX_SHARE = 0.92f

        // Pérdida de la MEJOR puerta de una fila negativa (la menos mala), como fracción.
        const val NEGATIVE_BEST_MIN_LOSS = 0.12f
        const val NEGATIVE_BEST_MAX_LOSS = 0.28f

        /** Con qué frecuencia un castigo es `÷` en vez de `−`. */
        const val NEGATIVE_DIVIDE_WEIGHT = 0.45f

        // Rangos de las puertas de resta que hacen de castigo, como fracción de lo proyectado.
        const val SUBTRACT_MIN_FRACTION = 0.25f
        const val SUBTRACT_MAX_FRACTION = 0.6f
    }
}
