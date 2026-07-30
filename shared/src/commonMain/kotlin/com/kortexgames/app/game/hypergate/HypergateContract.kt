package com.kortexgames.app.game.hypergate

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Hypergate — contrato MVI (Fase 1)
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla Hypergate, siguiendo el patrón MVI
 * canónico del proyecto (ver `SettingsViewModel` / `PolarityCollisionViewModel`). El
 * `HypergateViewModel` que los une llega en Fase 2.
 *
 * Separación deliberada de responsabilidades:
 *  - [HypergateUiState] — TODO lo que la UI necesita para pintarse, como `StateFlow` inmutable.
 *  - [HypergateIntent] — el ÚNICO canal de entrada de la UI hacia el ViewModel.
 *  - [HypergateEffect] — eventos *one-shot* (sonido/háptica) que se consumen una vez y no son
 *    estado; nunca se guardan en [HypergateUiState] para no re-dispararse en recomposición.
 */

/**
 * Estado de UI observable de la pantalla Hypergate.
 *
 * Envuelve el estado de dominio del juego ([game]) junto con el ciclo de vida de la partida y
 * el overlay de fin, exactamente como hace `PolarityCollisionUiState`. La UI observa esto con
 * `collectAsStateWithLifecycle()`.
 *
 * @property game estado de juego (escudo, proyectiles, viewport, marcador, tiempo).
 * @property status fase del ciclo de vida ([GameStatus]); en `IDLE` se muestra la antesala/intro
 *   y en `FINISHED` el overlay de resultados.
 * @property gameOver datos del resultado final (puntaje, percentil, récord); `null` mientras la
 *   partida no ha terminado. Es estado persistente (no un `Effect`) porque el overlay debe
 *   sobrevivir a las recomposiciones hasta que el jugador lo cierre.
 */
data class HypergateUiState(
    val game: HypergateState = HypergateState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/**
 * Intents de Hypergate: la única vía por la que la UI comunica gestos y ciclos al ViewModel.
 *
 * Nota de diseño: **el tap del jugador no lleva coordenadas**. La mecánica es "toca en cualquier
 * lugar para conmutar", así que la posición del toque es irrelevante y no se propaga —el gesto es
 * puro [ToggleShield]. El único evento posicional que sí importa (el tamaño del área) viaja por
 * [UpdateViewport].
 */
sealed interface HypergateIntent : UiIntent {
    /**
     * Reporta el tamaño real del área jugable en píxeles. La pantalla lo emite al medirse (y en
     * cada cambio de tamaño/rotación) para que el motor sitúe spawns y colisiones en el espacio
     * correcto sin depender de APIs de Compose.
     */
    data class UpdateViewport(val widthPx: Float, val heightPx: Float) : HypergateIntent

    /**
     * El jugador tocó la pantalla: conmuta el escudo (A↔B) instantáneamente. Se dispara para
     * cualquier toque, sin importar dónde (ver nota de la interfaz).
     */
    data object ToggleShield : HypergateIntent

    /**
     * Tick del bucle de render. Transporta el timestamp monotónico del frame (`withFrameNanos`)
     * en lugar de un delta ya calculado: dejar que el MOTOR derive el `dt` a partir de dos
     * timestamps consecutivos evita acumular error de redondeo si la UI recalcula el delta, y le
     * permite descartar limpiamente el primer frame y los saltos tras una pausa. El nombre
     * `Tick` respeta la nomenclatura pedida en el spec.
     *
     * @property frameNanos tiempo monotónico del frame actual, en nanosegundos.
     */
    data class Tick(val frameNanos: Long) : HypergateIntent

    /** Arranca la partida desde la antesala (intro): botón "Comenzar". */
    data object Start : HypergateIntent

    /** Pausa la partida (menú de pausa): congela la física y el cronómetro. */
    data object Pause : HypergateIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : HypergateIntent

    /** Reinicia tras el overlay de fin: botón "Jugar de nuevo". */
    data object PlayAgain : HypergateIntent
}

/**
 * Efectos *one-shot* de Hypergate: feedback inmediato (sonoro + háptico) que el §9 del sistema
 * de diseño exige en cada acierto/fallo. Se emiten por un `Channel`→`Flow` y se consumen una
 * sola vez; jamás forman parte del estado.
 *
 * Se modela como eventos semánticos ([PlaySound] / [Vibrate] con su motivo) en vez de invocar el
 * `AudioAndHapticManager` directamente desde el motor. Así la lógica de física queda 100%
 * portable y testeable (un test puede afirmar "este impacto emitió CRASH") sin acoplarse a la
 * plataforma; el ViewModel traduce cada efecto a la llamada concreta de audio/háptica.
 */
sealed interface HypergateEffect : UiEffect {
    /**
     * Reproduce un pitido de juego.
     *
     * @property cue qué señal sonora ([Cue.ABSORB] al absorber bien, [Cue.CRASH] al chocar mal).
     */
    data class PlaySound(val cue: Cue) : HypergateEffect {
        /** Señales sonoras posibles de un impacto. */
        enum class Cue { ABSORB, CRASH }
    }

    /**
     * Dispara una vibración háptica.
     *
     * @property cue intención del pulso ([Cue.SUCCESS] en la absorción correcta, [Cue.ERROR] en
     *   el choque de polaridad equivocada).
     */
    data class Vibrate(val cue: Cue) : HypergateEffect {
        /** Intenciones hápticas posibles. */
        enum class Cue { SUCCESS, ERROR }
    }
}
