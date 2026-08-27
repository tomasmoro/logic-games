package com.kortexgames.app.game.hexaorbit

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Hexa Orbit — contrato MVI (FASE 1)
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla, siguiendo el patrón canónico del
 * proyecto (ver `LegionContract` / `HypergateContract`). El motor y el `HexaOrbitViewModel` que
 * los unen llegan en FASE 2, y el renderizado en FASE 3.
 *
 *  - [HexaOrbitUiState] — TODO lo que la UI necesita para pintarse, como `StateFlow` inmutable.
 *  - [HexaOrbitIntent] — el ÚNICO canal de entrada de la UI hacia el ViewModel.
 *  - [HexaOrbitEffect] — eventos *one-shot* (sonido/háptica) que se consumen una vez.
 *
 * ## Qué NO es un efecto aquí
 *
 * La estela del puntero, el haz proyectado y el parpadeo de los orbes **duran** varios frames,
 * así que son estado ([HexaOrbitState]); solo lo instantáneo —un sonido, una vibración— viaja
 * por el canal de efectos. Meterlos en el `Channel` los haría depender de que la UI esté
 * suscrita justo en ese frame.
 *
 * ## Nota sobre el orden de los parámetros genéricos
 *
 * El `MviViewModel` del proyecto se declara `MviViewModel<I : UiIntent, S : UiState, E : UiEffect>`
 * (intent, estado, efecto), así que el ViewModel de FASE 2 extenderá
 * `MviViewModel<HexaOrbitIntent, HexaOrbitUiState, HexaOrbitEffect>`. Es el mismo triángulo que
 * pedía el spec, con el orden que ya usan los ~18 juegos existentes.
 */

/**
 * Estado de UI observable de la pantalla Hexa Orbit.
 *
 * Envuelve el estado de dominio ([game]) junto con el ciclo de vida y el overlay de fin, igual
 * que `LegionUiState`. La UI lo observa con `collectAsStateWithLifecycle()`.
 *
 * @property game estado de la partida: tablero, puntero, haz proyectado, orbes y velocidad
 *           (ver [HexaOrbitState]).
 * @property status fase del ciclo de vida ([GameStatus]); en `IDLE` se muestra la antesala/intro
 *           y en `FINISHED` el overlay de resultados.
 * @property awaitingRevive `true` mientras se ofrece revivir viendo un anuncio tras la primera
 *           fuga del puntero. Se espeja de [HexaOrbitState.awaitingRevive] (es el motor quien
 *           abre/cierra la oferta); durante la oferta la partida NO está `FINISHED` todavía: si
 *           el jugador acepta, el puntero vuelve al centro; si declina (o expira la cuenta
 *           atrás), entonces sí se cierra la partida y se publica [gameOver]. Solo se ofrece una
 *           vez ([HexaOrbitState.reviveUsed]).
 * @property gameOver datos del resultado final (puntaje, percentil, ranking, récord); `null`
 *           mientras la partida no ha terminado. Es estado persistente y no un `Effect` porque
 *           el overlay debe sobrevivir a las recomposiciones hasta que el jugador lo cierre.
 * @property rankingPreview comparativa mundial para la antesala, ANTES de jugar. `null` mientras
 *           se resuelve ([rankingPreviewLoading]) o si no hay comparativa (invitado, sin red).
 * @property rankingPreviewLoading `true` mientras se pide [rankingPreview]. Arranca en `true`
 *           para no enseñar el aviso de "sin comparativa" un instante antes de la respuesta.
 */
data class HexaOrbitUiState(
    val game: HexaOrbitState = HexaOrbitState(),
    val status: GameStatus = GameStatus.IDLE,
    val awaitingRevive: Boolean = false,
    val gameOver: GameOverInfo? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = true,
) : UiState

/**
 * Intents de Hexa Orbit: la única vía por la que la UI comunica gestos y ciclo al ViewModel.
 */
sealed interface HexaOrbitIntent : UiIntent {

    /** Arranca la partida desde la antesala (intro): botón "Comenzar". */
    data object Start : HexaOrbitIntent

    /**
     * Tick del bucle de juego (60 FPS). Transporta el timestamp monotónico del frame
     * (`withFrameNanos`) en lugar de un delta ya calculado: dejar que el MOTOR derive el `dt` de
     * dos timestamps consecutivos evita acumular error de redondeo, permite descartar el primer
     * frame y —lo crítico aquí— **recortar los saltos** tras una pausa o un anuncio. Sin ese
     * recorte, un frame largo haría avanzar al puntero varios azulejos de golpe: se saltaría
     * orbes y podría cruzar la frontera sin que el jugador viera nada. Mismo criterio que
     * `LegionIntent.Tick`.
     *
     * @property frameNanos tiempo monotónico del frame actual, en nanosegundos.
     */
    data class Tick(val frameNanos: Long) : HexaOrbitIntent

    /**
     * El jugador toca un hexágono: gira 60° en sentido horario.
     *
     * Lleva la [HexCoord] del dominio en vez de `(q, r)` sueltos porque el redondeo de píxeles a
     * celda ya ocurre en la pantalla (FASE 3) y produce justamente una coordenada; pasarla
     * entera evita que el ViewModel la reconstruya y que un refactor cruce los dos enteros.
     *
     * El motor la valida: un tap fuera del tablero es un no-op silencioso, no un error.
     *
     * @property coord azulejo que el jugador quiere girar.
     */
    data class RotateTile(val coord: HexCoord) : HexaOrbitIntent

    /**
     * El anuncio recompensado terminó con recompensa concedida: repone el puntero en el centro
     * con un horizonte nuevo. La UI SOLO lo emite tras `AdManager.showRewardedAd()` == `EARNED`
     * (el flujo del anuncio vive en la pantalla, como en Neon Legion); el motor se limita a
     * aplicar la reposición y marcar [HexaOrbitState.reviveUsed].
     */
    data object Revive : HexaOrbitIntent

    /** El jugador rechaza revivir (o la cuenta atrás expiró): cierra la partida de verdad. */
    data object DeclineRevive : HexaOrbitIntent

    /** Pausa la partida (menú de pausa): congela puntero, velocidad y cronómetro. */
    data object Pause : HexaOrbitIntent

    /** Reanuda tras la pausa. El motor descarta el primer [Tick] para no acumular el parón. */
    data object Resume : HexaOrbitIntent

    /** Reinicia la partida: botón "Jugar de nuevo" del overlay de fin. */
    data object RestartGame : HexaOrbitIntent
}

/**
 * Efectos *one-shot* de Hexa Orbit: el feedback inmediato (sonoro + háptico) que el §9 del
 * sistema de diseño exige en cada interacción.
 *
 * Se modelan como eventos SEMÁNTICOS ([PlaySound]/[Vibrate] con su motivo) en vez de invocar el
 * `AudioAndHapticManager` desde el motor, igual que en Legion e Hypergate: la lógica de juego
 * queda 100 % portable y testeable (un test puede afirmar "recoger un orbe emitió COLLECT_POINT")
 * y es el ViewModel quien traduce cada cue a la llamada concreta de audio/háptica.
 */
sealed interface HexaOrbitEffect : UiEffect {

    /**
     * Reproduce una señal sonora de juego.
     *
     * @property cue qué sonó y por qué (ver [Cue]).
     */
    data class PlaySound(val cue: Cue) : HexaOrbitEffect {
        /** Señales sonoras del juego, una por momento de feedback exigido por el spec. */
        enum class Cue {
            /** Giro de un hexágono: clic corto que confirma que el tap entró. */
            ROTATE,

            /** Orbe de energía recogido. */
            COLLECT_POINT,

            /** El puntero cruzó la frontera exterior: fin de partida. */
            GAME_OVER,
        }
    }

    /**
     * Dispara una vibración háptica.
     *
     * @property cue intención del pulso (ver [Cue]).
     */
    data class Vibrate(val cue: Cue) : HexaOrbitEffect {
        /** Intenciones hápticas posibles. */
        enum class Cue {
            /** Pulso mínimo de confirmación táctil al girar una pieza. */
            LIGHT,

            /** Refuerzo positivo: orbe recogido. */
            SUCCESS,

            /** Castigo: el puntero se escapó del tablero. */
            ERROR,
        }
    }
}
