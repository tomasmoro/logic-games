package com.kortexgames.app.game.quantummerge

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Quantum Merge — contrato MVI (Fase 1)
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla Quantum Merge, siguiendo el patrón MVI
 * canónico del proyecto (ver `SettingsViewModel` / `HypergateViewModel`). El motor de física y el
 * `QuantumMergeViewModel` que los unen llegan en Fase 2.
 *
 * Separación deliberada de responsabilidades:
 *  - [QuantumMergeUiState] — TODO lo que la UI necesita para pintarse, como `StateFlow` inmutable.
 *  - [QuantumMergeIntent] — el ÚNICO canal de entrada de la UI hacia el ViewModel.
 *  - [QuantumMergeEffect] — eventos *one-shot* (sonido/háptica) que se consumen una vez y no son
 *    estado; nunca se guardan en [QuantumMergeUiState] para no re-dispararse en recomposición.
 *
 * ## Qué NO es un efecto aquí
 * El destello de la fusión —que en otros motores sería un evento "flash"— vive en el estado como
 * [MergeFlash]. Regla que sigue este juego: si algo tiene **duración** y hay que dibujarlo durante
 * varios frames, es estado; si es **instantáneo** y se dispara y olvida (un sonido, una vibración),
 * es efecto. Un destello enviado por el canal de efectos obligaría a la pantalla a mantener su
 * propia lista mutable de animaciones vivas, que es exactamente el estado paralelo que MVI evita.
 */

/**
 * Estado de UI observable de la pantalla Quantum Merge.
 *
 * Envuelve el estado de dominio del juego ([game]) junto con el ciclo de vida de la partida y el
 * overlay de fin, igual que `HypergateUiState`. La UI lo observa con `collectAsStateWithLifecycle()`.
 *
 * @property game estado del contenedor (esferas, dispensador, destellos, marcador, peligro).
 * @property status fase del ciclo de vida ([GameStatus]); en `IDLE` se muestra la antesala/intro y
 *   en `FINISHED` el overlay de resultados.
 * @property gameOver datos del resultado final (puntaje, percentil, récord); `null` mientras la
 *   partida no ha terminado. Es estado persistente (no un `Effect`) porque el overlay debe
 *   sobrevivir a las recomposiciones hasta que el jugador lo cierre.
 */
data class QuantumMergeUiState(
    val game: QuantumMergeState = QuantumMergeState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/**
 * Intents de Quantum Merge: la única vía por la que la UI comunica gestos y ciclos al ViewModel.
 *
 * Nota de diseño: **no existe un `UpdateViewport`**, a diferencia de Hypergate. El mundo simulado
 * es de tamaño fijo ([QuantumWorld]) y quien conoce la escala mundo→píxel es el render, así que la
 * pantalla convierte el gesto ANTES de emitirlo y el motor nunca necesita saber cuántos píxeles
 * mide nada. Ver la Decisión 1 en la cabecera de `QuantumMergeModels.kt`.
 */
sealed interface QuantumMergeIntent : UiIntent {

    /**
     * El jugador desliza el dedo por la zona superior para apuntar.
     *
     * @property worldX posición horizontal deseada **en unidades de mundo**, no en píxeles: la
     *   pantalla ya tiene la transformación inversa (la calcula para dibujar el contenedor), así
     *   que convertir en el borde de la UI mantiene al motor libre de píxeles y hace que un test
     *   pueda apuntar a `worldX = 50f` sin simular una pantalla. El motor la recorta a las paredes
     *   con [QuantumWorld.clampInsideWalls] — el dedo puede salirse del tablero, la esfera no.
     */
    data class MoveDropper(val worldX: Float) : QuantumMergeIntent

    /**
     * Suelta la esfera que sostiene el dispensador: pasa de `currentDropSphere` a `activeSpheres`
     * con velocidad inicial nula y ya solo la gobierna la gravedad.
     *
     * No lleva coordenadas: la posición de lanzamiento es la mira vigente
     * ([QuantumMergeState.aimX]), que ya viajó por [MoveDropper]. Meterla aquí duplicaría la
     * fuente de verdad y abriría la puerta a que el toque de soltar (que en un tap sin arrastre
     * no mueve la mira) teletransportase la esfera.
     */
    data object DropSphere : QuantumMergeIntent

    /**
     * Tick del bucle de render. Transporta el timestamp monotónico del frame (`withFrameNanos`) en
     * lugar de un delta ya calculado: dejar que el MOTOR derive el `dt` a partir de dos timestamps
     * consecutivos (vía `FrameClock`) evita acumular error de redondeo, permite descartar el primer
     * frame y recorta los saltos tras una pausa — sin recorte, un frame largo teletransportaría una
     * esfera rápida al otro lado de la pila (túnel de colisión).
     *
     * @property frameNanos tiempo monotónico del frame actual, en nanosegundos.
     */
    data class Tick(val frameNanos: Long) : QuantumMergeIntent

    /**
     * Elige el nivel de dificultad desde la antesala.
     *
     * Solo tiene efecto antes de empezar: el nivel fija la geometría de la partida (tamaño de las
     * esferas y altura del techo), así que cambiarlo a mitad de una corrida invalidaría la pila ya
     * construida y el récord de la tabla en la que se está compitiendo.
     */
    data class SelectDifficulty(val difficulty: QuantumDifficulty) : QuantumMergeIntent

    /** Arranca la partida desde la antesala (intro): botón "Comenzar". */
    data object Start : QuantumMergeIntent

    /** Pausa la partida (menú de pausa): congela la física y el cronómetro. */
    data object Pause : QuantumMergeIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : QuantumMergeIntent

    /** Vacía el contenedor y empieza de cero: botón "Jugar de nuevo" del overlay de fin. */
    data object RestartGame : QuantumMergeIntent
}

/**
 * Efectos *one-shot* de Quantum Merge: feedback inmediato (sonoro + háptico) que el §9 del sistema
 * de diseño exige en cada acción del jugador. Se emiten por un `Channel`→`Flow` y se consumen una
 * sola vez; jamás forman parte del estado.
 *
 * Se modelan como eventos **semánticos** (qué ha pasado) en lugar de invocar el
 * `AudioAndHapticManager` desde el motor. Así la física queda 100 % portable y testeable (un test
 * puede afirmar "esta fusión emitió `MERGE_BIG`") sin acoplarse a la plataforma; el ViewModel
 * traduce cada efecto a la llamada concreta de audio/háptica, igual que en Hypergate.
 */
sealed interface QuantumMergeEffect : UiEffect {

    /**
     * Reproduce un pitido de juego.
     *
     * @property cue qué ha ocurrido en la simulación.
     */
    data class PlaySound(val cue: Cue) : QuantumMergeEffect {
        /** Señales sonoras del juego. */
        enum class Cue {
            /** El jugador soltó una esfera. */
            DROP,

            /**
             * Choque audible entre dos esferas (o contra el suelo).
             *
             * Aviso para la Fase 2: en un contenedor lleno hay **decenas de contactos por frame**;
             * emitir uno por contacto convertiría el juego en ruido blanco y saturaría el canal.
             * El motor solo debe emitirlo cuando la velocidad de aproximación supere un umbral (un
             * golpe de verdad, no un roce de reposo) y con un limitador de cadencia.
             */
            BOUNCE,

            /** Dos esferas del mismo tier se fusionaron. */
            MERGE,

            /** Una esfera se asentó por encima de la línea de peligro: fin de la partida. */
            GAME_OVER,
        }
    }

    /**
     * Dispara una vibración háptica.
     *
     * @property cue intención del pulso. Se nombra por **significado**, no por intensidad de
     *   plataforma: el mapeo a `HapticFeedback` (LIGHT / HEAVY / ERROR) lo hace el ViewModel en
     *   Fase 2. Es lo que permite ajustar la fuerza del feedback sin tocar el motor de física, y lo
     *   que mantiene al motor sin dependencias de plataforma.
     */
    data class Vibrate(val cue: Cue) : QuantumMergeEffect {
        /** Intenciones hápticas del juego. */
        enum class Cue {
            /**
             * Fusión de tier bajo: un toque corto y ágil (→ `LIGHT`). Ocurre constantemente, así
             * que debe ser casi imperceptible o cansa.
             */
            MERGE_SMALL,

            /**
             * Fusión de tier alto: un golpe con peso (→ `HEAVY`). Es la recompensa háptica del
             * juego —el jugador *siente* que ha creado algo grande—, y funciona precisamente
             * porque es rara.
             */
            MERGE_BIG,

            /** Derrota (→ `ERROR`). */
            GAME_OVER,
        }
    }
}
