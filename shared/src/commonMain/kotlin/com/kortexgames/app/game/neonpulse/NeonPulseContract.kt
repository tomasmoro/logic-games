package com.kortexgames.app.game.neonpulse

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Neon Pulse — contrato MVI (FASE 1)
 *
 * Define las tres piezas del ciclo unidireccional (ver [com.kortexgames.app.core.mvi.MviViewModel]):
 * el [NeonPulseUiState] renderizable, los [NeonPulseIntent] que la UI emite y los
 * [NeonPulseEffect] one-shot (sonido/háptica/animaciones) que el ViewModel dispara.
 *
 * > Nota de firma: la base es `MviViewModel<Intent, State, Effect>` (ese es el
 * > orden real de los parámetros de tipo en este proyecto). El motor de tiempo y
 * > la reducción se implementan en FASE 2.
 */

/**
 * Estado inmutable de la pantalla de Neon Pulse (fuente única de verdad de la UI).
 *
 * @property score puntuación acumulada de la partida.
 * @property lives vidas restantes ([NeonPulseConfig.INITIAL_LIVES] al empezar). A
 *   `0` la partida termina antes de agotarse el tiempo.
 * @property activeNodes nodos actualmente visibles en el lienzo. Es la lista que el
 *   `Canvas` (FASE 3) dibuja y sobre la que resuelve el hit-testing del toque. Se
 *   reemplaza entera en cada `tick` (estado inmutable), nunca se muta in situ.
 * @property remainingMs tiempo restante de partida en milisegundos; alimenta la
 *   barra/《countdown》 de la cabecera.
 * @property status fase de la partida (IDLE mientras se muestra la antesala/intro,
 *   RUNNING en juego, PAUSED, FINISHED). Reutiliza el [GameStatus] común a todos
 *   los juegos para que la navegación y los overlays se comporten igual.
 * @property gameOver resumen del resultado (puntaje + percentil) cuando la partida
 *   termina; `null` mientras se juega. Igual patrón que el resto de juegos.
 */
data class NeonPulseUiState(
    val score: Int = 0,
    val lives: Int = NeonPulseConfig.INITIAL_LIVES,
    val activeNodes: List<Node> = emptyList(),
    val remainingMs: Long = NeonPulseConfig.GAME_DURATION_MS,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/**
 * Intenciones de la UI: interacciones táctiles del jugador y pulsos del motor.
 *
 * El hit-testing (¿el toque cae dentro del radio de algún nodo?) lo resuelve el
 * `Canvas` en FASE 3 con la geometría que solo la UI conoce (tamaño real del
 * lienzo). Por eso la UI envía [TapNode] con el `id` ya resuelto, o [TapMiss] si
 * el toque fue al vacío; el ViewModel no recibe coordenadas crudas.
 */
sealed interface NeonPulseIntent : UiIntent {

    /** Arranca la partida desde la antesala (IDLE → RUNNING). */
    data object Start : NeonPulseIntent

    /** Repite partida desde la pantalla de resultado. */
    data object PlayAgain : NeonPulseIntent

    /** Pausa / reanuda (congela el game loop y los anillos). */
    data object Pause : NeonPulseIntent
    data object Resume : NeonPulseIntent

    /**
     * El jugador tocó **dentro** del nodo [id]. El reducer decidirá si fue acierto
     * (nodo normal → suma) o error (nodo trampa → penaliza) según su [NodeType].
     *
     * @property id identificador del [Node] impactado (resuelto por el `Canvas`).
     */
    data class TapNode(val id: Long) : NeonPulseIntent

    /** El jugador tocó el fondo vacío (ningún nodo bajo el dedo). Se modela como
     *  intent propio para poder dar feedback de "fallo" sin afectar puntuación. */
    data object TapMiss : NeonPulseIntent

    /**
     * Pulso del bucle de juego. Avanza la simulación [deltaMillis] milisegundos:
     * descuenta vida a cada nodo, retira los expirados (perdiendo vida si eran
     * normales), agenda nuevos spawns y consume el reloj de partida.
     *
     * Se modela como intent (y no como método interno) para mantener el ciclo MVI
     * puro y unidireccional: el motor de tiempo es la única fuente que emite
     * [Tick], igual que el jugador es la única fuente de los taps. Detalle de la
     * generación del delta y su concurrencia: ver KDoc del ViewModel (FASE 2).
     *
     * @property deltaMillis tiempo transcurrido desde el `tick` anterior.
     */
    data class Tick(val deltaMillis: Long) : NeonPulseIntent
}

/**
 * Efectos one-shot (feedback inmediato). NO forman parte del estado: se emiten por
 * `Channel` para no repetirse en recomposición ni al rotar (CLAUDE.md §MVI).
 *
 * El feedback inmediato (sonoro + háptico + visual) al acertar/fallar es un
 * requisito de producto para la sensación "viva" de la app (CLAUDE.md §9.4).
 */
sealed interface NeonPulseEffect : UiEffect {

    /**
     * Reproduce un efecto de sonido. Se envuelve [SoundEffect] para que la capa de
     * UI solo tenga que reenviarlo al [com.kortexgames.app.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego:
     *  - [Hit]   → acierto sobre un nodo normal ([SoundEffect.SUCCESS]).
     *  - [Error] → tocar una trampa o dejar expirar un nodo ([SoundEffect.ERROR]).
     */
    data class PlaySound(val sound: SoundEffect) : NeonPulseEffect {
        companion object {
            val Hit = PlaySound(SoundEffect.SUCCESS)
            val Error = PlaySound(SoundEffect.ERROR)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Tick]  → vibración ligera de confirmación al acertar ([HapticFeedback.LIGHT]).
     *  - [Heavy] → vibración fuerte al perder una vida / trampa ([HapticFeedback.HEAVY]).
     */
    data class Vibrate(val haptic: HapticFeedback) : NeonPulseEffect {
        companion object {
            val Tick = Vibrate(HapticFeedback.LIGHT)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
        }
    }

    /**
     * Solicita a la UI la animación de "explosión"/combo sobre un acierto: un
     * círculo que crece y se desvanece en alpha (FASE 3). Lleva la posición
     * normalizada del nodo impactado para que el `Canvas` sepa dónde animarla.
     *
     * @property x centro X normalizado `[0f..1f]` del acierto.
     * @property y centro Y normalizado `[0f..1f]` del acierto.
     */
    data class ShowComboAnim(val x: Float, val y: Float) : NeonPulseEffect
}
