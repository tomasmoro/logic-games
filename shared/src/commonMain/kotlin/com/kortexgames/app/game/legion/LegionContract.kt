package com.kortexgames.app.game.legion

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Neon Legion — contrato MVI (Fase 1)
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla Neon Legion, siguiendo el patrón MVI
 * canónico del proyecto (ver `HypergateContract` / `QuantumMergeContract`). El motor y el
 * `LegionViewModel` que los unen llegan en Fases 2-4.
 *
 * Separación deliberada de responsabilidades:
 *  - [LegionUiState] — TODO lo que la UI necesita para pintarse, como `StateFlow` inmutable.
 *  - [LegionIntent] — el ÚNICO canal de entrada de la UI hacia el ViewModel.
 *  - [LegionEffect] — eventos *one-shot* (sonido/háptica) que se consumen una vez y no son
 *    estado; nunca se guardan en [LegionUiState] para no re-dispararse en recomposición.
 *
 * ## Qué NO es un efecto aquí
 * Los destellos de puerta y el rayo del láser tienen **duración** y hay que dibujarlos durante
 * varios frames, así que viven en el estado ([GateFlash], [SpaceshipEvent.VerticalLaser]); solo
 * lo instantáneo (un sonido, una vibración) viaja por el canal de efectos. Misma regla que
 * documenta la cabecera de `QuantumMergeContract`.
 */

/**
 * Estado de UI observable de la pantalla Neon Legion.
 *
 * Envuelve el estado de dominio ([game]) junto con el ciclo de vida, el overlay de fin y la
 * oferta de revive, igual que `Neon2048UiState`. La UI lo observa con
 * `collectAsStateWithLifecycle()`.
 *
 * @property game estado de la partida (ronda, tropas, puertas, naves, examen; ver [LegionState]).
 * @property status fase del ciclo de vida ([GameStatus]); en `IDLE` se muestra la antesala/intro
 *   y en `FINISHED` el overlay de resultados.
 * @property awaitingRevive `true` mientras se ofrece revivir viendo un anuncio tras perder el
 *   combate. Durante la oferta la partida NO está `FINISHED` todavía: si el jugador acepta, la
 *   ronda se reinicia con [LegionState.roundStartTroops]; si declina (o agota la cuenta atrás),
 *   entonces sí se cierra la partida y se publica [gameOver]. Solo se ofrece una vez
 *   ([LegionState.reviveUsed]).
 * @property gameOver datos del resultado final (puntaje, percentil, ranking, récord); `null`
 *   mientras la partida no ha terminado. Es estado persistente (no un `Effect`) porque el
 *   overlay debe sobrevivir a las recomposiciones hasta que el jugador lo cierre.
 * @property rankingPreview comparativa mundial para la antesala, ANTES de jugar (mismo panel que
 *   el diálogo de fin de partida; ver `ProgressRepository.previewRanking`). `null` mientras se
 *   resuelve ([rankingPreviewLoading]) o si no hay comparativa (invitado, sin red, sin marcas).
 *   Neon Legion es de dificultad única con tabla única, así que se pide una sola vez al entrar.
 * @property rankingPreviewLoading `true` mientras se pide [rankingPreview]. Arranca en `true`
 *   para no enseñar el aviso de "sin comparativa" un instante antes de que llegue la respuesta.
 * @property isFirstEverPlay `true` si este dispositivo nunca terminó una partida de Neon Legion
 *   (historial local vacío para `GameIds.NEON_LEGION`, ver `LegionViewModel.init`). La antesala
 *   lo usa para interponer el tutorial de las cuentas matemáticas ANTES de la primera ronda de la
 *   primera partida — una sola vez en la vida de la app, nunca más (se apoya en el historial en
 *   vez de en una preferencia aparte: cero esquema nuevo, y se "repara" solo si el historial se
 *   borrara). Arranca en `false` (no interrumpir) hasta que la consulta local responda.
 */
data class LegionUiState(
    val game: LegionState = LegionState(),
    val status: GameStatus = GameStatus.IDLE,
    val awaitingRevive: Boolean = false,
    val gameOver: GameOverInfo? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = true,
    val isFirstEverPlay: Boolean = false,
) : UiState

/**
 * Intents de Neon Legion: la única vía por la que la UI comunica gestos y ciclos al ViewModel.
 *
 * Nota de diseño: el movimiento es UN solo intent continuo ([AimAt]) y no un par
 * "tap a carril / swipe a carril adyacente". El ejército no salta entre carriles: **sigue al
 * dedo** con retardo ([LegionBalance.AIM_FOLLOW_TAU_SEC]), así que lo que la UI comunica es una
 * posición, no una orden discreta de cambio. El redondeo a carril —lo único que el juego
 * necesita para colisionar— lo hace el dominio en [LegionState.playerLane].
 */
sealed interface LegionIntent : UiIntent {

    /** Arranca la partida desde la antesala (intro): botón "Comenzar". */
    data object Start : LegionIntent

    /**
     * Tick del bucle de render. Transporta el timestamp monotónico del frame (`withFrameNanos`)
     * en lugar de un delta ya calculado: dejar que el MOTOR derive el `dt` de dos timestamps
     * consecutivos evita acumular error de redondeo, permite descartar el primer frame y recorta
     * los saltos tras una pausa o un anuncio — sin recorte, un frame largo haría que una fila de
     * puertas atravesara al jugador sin colisionar (tunneling). Mismo criterio que
     * `QuantumMergeIntent.Tick`.
     *
     * @property frameNanos tiempo monotónico del frame actual, en nanosegundos.
     */
    data class Tick(val frameNanos: Long) : LegionIntent

    /**
     * El dedo señala una posición de la pista: el ejército empieza a moverse hacia allí (con el
     * retardo de seguimiento, no de golpe). Se emite al posar el dedo y en cada movimiento.
     *
     * @property laneX posición deseada en **unidades de carril continuas** (`0f` = centro del
     *   primer carril, `1f` = centro del segundo…), no en píxeles: la pantalla ya conoce el
     *   ancho de carril para dibujar, así que convertir en el borde de la UI mantiene al motor
     *   libre de píxeles y permite a un test apuntar a `laneX = 1.5f` sin simular una pantalla.
     *   El motor lo recorta a los carriles existentes — el dedo puede salirse del tablero, la
     *   legión no.
     */
    data class AimAt(val laneX: Float) : LegionIntent

    /**
     * El jugador pulsa una opción del examen del láser horizontal.
     *
     * @property optionIndex índice del botón pulsado dentro de [LaserQuiz.options]. Se valida
     *   contra [LaserQuiz.correctIndex] en el motor, nunca en la UI: la pantalla no debe saber
     *   cuál es la buena (evita que un refactor de render "resuelva" el examen).
     */
    data class AnswerQuiz(val optionIndex: Int) : LegionIntent

    /**
     * El anuncio recompensado terminó con recompensa concedida: restaura las tropas de entrada
     * de la ronda y la reinicia. La UI SOLO lo emite tras `AdManager.showRewardedAd()` ==
     * `EARNED` (el flujo del anuncio vive en la pantalla, como en Neon 2048); el motor se limita
     * a aplicar la restauración y marcar [LegionState.reviveUsed].
     */
    data object Revive : LegionIntent

    /** El jugador rechaza revivir (o la cuenta atrás expiró): cierra la partida de verdad. */
    data object DeclineRevive : LegionIntent

    /** Pausa la partida (menú de pausa): congela pista, láseres y cronómetro. */
    data object Pause : LegionIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : LegionIntent

    /** Reinicia tras el overlay de fin: botón "Jugar de nuevo". */
    data object PlayAgain : LegionIntent
}

/**
 * Efectos *one-shot* de Neon Legion: el feedback inmediato (sonoro + háptico) que el §9 del
 * sistema de diseño exige en cada acierto/fallo.
 *
 * Se modelan como eventos SEMÁNTICOS ([PlaySound]/[Vibrate] con su motivo) en vez de invocar el
 * `AudioAndHapticManager` desde el motor, igual que en Hypergate: la lógica de juego queda 100 %
 * portable y testeable (un test puede afirmar "cruzar esta puerta emitió GATE_LOSS") y es el
 * ViewModel quien traduce cada cue a la llamada concreta de audio/háptica.
 */
sealed interface LegionEffect : UiEffect {

    /**
     * Reproduce una señal sonora de juego.
     *
     * @property cue qué sonó y por qué (ver [Cue]).
     */
    data class PlaySound(val cue: Cue) : LegionEffect {
        /** Señales sonoras del juego, una por momento de feedback exigido por el spec. */
        enum class Cue {
            /** Cruce de puerta que hace crecer el ejército (`+`/`×`). */
            GATE_GAIN,

            /** Cruce de puerta que lo encoge (`−`/`÷`). */
            GATE_LOSS,

            /** Cambio de carril (tick corto: confirma que el gesto entró). */
            LANE_SWITCH,

            /** Un láser vertical disparó SIN alcanzar al jugador (esquivado): chasquido neutro. */
            LASER_FIRE,

            /**
             * Un láser vertical alcanzó al jugador (−50 % de tropas). Cue aparte de [LASER_FIRE]
             * porque el mapeo sonoro es opuesto (castigo vs. ambiente) y el ViewModel no debe
             * inferir el impacto comparando carriles: eso es conocimiento del motor.
             */
            LASER_HIT,

            /** Respuesta correcta en el examen del láser horizontal. */
            QUIZ_CORRECT,

            /** Respuesta incorrecta (o tiempo agotado) en el examen. */
            QUIZ_WRONG,

            /** Combate de fin de ronda ganado. */
            COMBAT_WIN,

            /** Combate perdido: derrota. */
            COMBAT_LOSS,
        }
    }

    /**
     * Dispara una vibración háptica.
     *
     * @property cue intención del pulso (ver [Cue]).
     */
    data class Vibrate(val cue: Cue) : LegionEffect {
        /** Intenciones hápticas posibles. */
        enum class Cue {
            /** Refuerzo positivo (puerta buena, examen acertado, combate ganado). */
            SUCCESS,

            /** Castigo (impacto de láser, examen fallado, derrota). */
            ERROR,
        }
    }
}
