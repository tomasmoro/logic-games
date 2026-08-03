package com.kortexgames.app.game.hypercube

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase

/**
 * # Neon Hyper-Cube — contrato MVI
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla del cubo, con el patrón MVI canónico del
 * proyecto (`SettingsViewModel`, `NeonCircuitViewModel`).
 *
 *  - [HyperCubeUiState] — TODO lo que la UI necesita para pintarse, como `StateFlow` inmutable.
 *  - [HyperCubeIntent] — el ÚNICO canal de entrada de la UI hacia el ViewModel.
 *  - [HyperCubeEffect] — eventos *one-shot* (sonido/háptica) que se consumen una vez y jamás
 *    forman parte del estado, para no re-dispararse en cada recomposición.
 *
 * El juego es **LEVELED** (como Neon Circuit o Water Sort): se entra por el selector de niveles y
 * el nivel determina la profundidad de la mezcla. Por eso el estado lleva [LeveledGamePhase] y
 * los intents de navegación entre niveles.
 */

/**
 * Estado observable de la pantalla del Neon Hyper-Cube.
 *
 * Tres bloques bien diferenciados: el **juego** ([game], que publica el motor), la **cámara**
 * (presentación pura, que el ViewModel actualiza sin tocar el motor) y el **ciclo de partida**
 * compartido con el resto de juegos LEVELED.
 *
 * @property game estado de dominio: cubo, giro en vuelo, jugadas y mezcla.
 * @property cameraYawRad giro horizontal acumulado de la cámara, en radianes. Sin límite: dar
 *   vueltas completas alrededor del cubo es natural y esperable.
 * @property cameraPitchRad inclinación vertical acumulada, en radianes. Se limita a
 *   ±[HyperCubeGeometry.MAX_PITCH_RAD] (ver su KDoc).
 * @property phase antesala con selector de nivel o tablero ([LeveledGamePhase]).
 * @property currentLevel nivel en juego (1-based); fija la profundidad de la mezcla.
 * @property maxUnlocked mejor nivel alcanzado (récord local-first); habilita el carril de niveles.
 * @property status fase del ciclo de vida ([GameStatus]). Ojo: durante la mezcla el estado es
 *   todavía `IDLE` a propósito — el cronómetro no debe correr mientras el cubo se baraja solo
 *   (ver [HyperCubeGameState.isScrambling]).
 * @property gameOver datos del resultado final (puntaje, percentil, récord); `null` hasta
 *   resolver el cubo. Es estado persistente —no un efecto— porque el overlay debe sobrevivir a
 *   las recomposiciones hasta que el jugador lo cierre.
 * @property saved resumen de la partida a medias guardada al salir, o `null` si no hay ninguna.
 *   La antesala la ofrece como "Continuar".
 * @property awaitingUndoAd hay un anuncio recompensado en marcha para pagar un deshacer. La
 *   pantalla lo usa como disparador (lanza el anuncio) y como señal de "cargando".
 */
data class HyperCubeUiState(
    val game: HyperCubeGameState = HyperCubeGameState(),
    val cameraYawRad: Float = DEFAULT_YAW_RAD,
    val cameraPitchRad: Float = DEFAULT_PITCH_RAD,
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val currentLevel: Int = 1,
    val maxUnlocked: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val saved: SavedGameSummary? = null,
    val awaitingUndoAd: Boolean = false,
) : UiState

/**
 * Lo justo de una partida guardada para describirla en la antesala ("Continuar").
 *
 * Se guarda este resumen y no el [HyperCubeSavedState] entero porque la antesala solo necesita
 * rotularlo; arrastrar las 27 piezas por el estado de UI para escribir una línea de texto sería
 * cargar la pantalla con datos que no pinta.
 *
 * @property level nivel de la partida pendiente.
 * @property isFreeMode si la partida pendiente es del modo libre.
 * @property moves movimientos que llevaba.
 */
data class SavedGameSummary(
    val level: Int,
    val isFreeMode: Boolean,
    val moves: Int,
)

/**
 * Intents del Neon Hyper-Cube: la única vía por la que la UI comunica gestos y ciclo al ViewModel.
 *
 * Nota de diseño sobre el reparto de responsabilidades en los gestos: la pantalla decide **qué
 * tipo** de arrastre es (cámara vs. capa) porque solo ella conoce píxeles y proyección; el
 * ViewModel recibe ya la intención resuelta en términos de dominio (radianes, eje, capa,
 * sentido). Así el motor no depende de coordenadas de pantalla y es testeable sin Compose.
 */
sealed interface HyperCubeIntent : UiIntent {

    /**
     * Arrastre **fuera** del cubo: orbita la cámara.
     *
     * Los deltas llegan ya convertidos a radianes por la pantalla, que es quien sabe cuántos
     * píxeles equivalen a una vuelta en el tamaño real del área de juego.
     *
     * @property deltaYawRad incremento del giro horizontal.
     * @property deltaPitchRad incremento de la inclinación vertical (se limita en el ViewModel).
     */
    data class RotateCamera(val deltaYawRad: Float, val deltaPitchRad: Float) : HyperCubeIntent

    /**
     * El dedo se ha levantado tras orbitar: la cámara sigue girando con la velocidad que llevaba
     * y se va frenando sola.
     *
     * La velocidad se envía **en radianes por segundo** (no en píxeles ni en "por frame") porque
     * es la pantalla quien sabe cuántos píxeles equivalen a un radián, y porque expresarla por
     * unidad de tiempo hace que el frenado no dependa de la tasa de refresco del dispositivo.
     *
     * @property yawRadPerSec velocidad angular horizontal al soltar.
     * @property pitchRadPerSec velocidad angular vertical al soltar.
     */
    data class FlingCamera(val yawRadPerSec: Float, val pitchRadPerSec: Float) : HyperCubeIntent

    /**
     * Corta en seco la inercia de la cámara. Lo emite la pantalla al posar el dedo: agarrar el
     * cubo mientras gira debe detenerlo al instante, como pararlo con la mano.
     */
    data object StopCameraInertia : HyperCubeIntent

    /**
     * Arrastre **sobre** una fila/columna del cubo: inicia el giro de esa rebanada 90°.
     *
     * Se ignora si ya hay un giro en vuelo o si la mezcla sigue en marcha.
     *
     * @property axis eje de la rebanada arrastrada.
     * @property layer índice de la capa (`-1`, `0` o `1`) a lo largo de [axis].
     * @property direction sentido del giro con la convención de mano derecha de [TurnDirection];
     *   la pantalla ya lo ha deducido combinando la dirección del dedo con la orientación actual
     *   de la cámara.
     */
    data class StartLayerRotation(
        val axis: Axis,
        val layer: Int,
        val direction: TurnDirection,
    ) : HyperCubeIntent

    /**
     * Vuelve a mezclar el nivel actual: descarta el desorden presente, deja el cubo resuelto y
     * reproduce una mezcla nueva. Es el "reintentar" del juego (y reinicia jugadas y cronómetro).
     */
    data object ScrambleCube : HyperCubeIntent

    /**
     * Tick del bucle de render, con el timestamp monotónico del frame (`withFrameNanos`).
     *
     * Aunque la lista de gestos del juego se agota con los dos primeros intents, el cubo necesita
     * un reloj: el giro en vuelo avanza su [ActiveTurn.progress] y la mezcla encadena sus giros.
     * Se transporta el **timestamp** en vez de un delta ya calculado —igual que en Hypergate—
     * para que el motor derive el `dt` y pueda descartar limpiamente el primer frame y los saltos
     * tras una pausa, sin acumular error de redondeo.
     *
     * @property frameNanos tiempo monotónico del frame actual, en nanosegundos.
     */
    data class Tick(val frameNanos: Long) : HyperCubeIntent

    /** Juega el nivel elegido en el selector: mezcla el cubo con la profundidad de ese nivel. */
    data class PlayLevel(val level: Int) : HyperCubeIntent

    /**
     * Juega el **modo libre**: cubo completamente mezclado, sin nivel ni récord de progresión.
     * Es el reto para quien sabe resolver un cubo de verdad (ver [scrambleDepthFor]).
     */
    data object PlayFreeMode : HyperCubeIntent

    /** Pausa: congela cronómetro y animación (menú de pausa). */
    data object Pause : HyperCubeIntent

    /** Reanuda tras la pausa. */
    data object Resume : HyperCubeIntent

    /**
     * Repite la partida actual con una mezcla nueva: botón "Jugar de nuevo" del overlay final.
     * Respeta el modo en curso (mismo nivel, o una mezcla completa nueva si es el modo libre).
     */
    data object PlayAgain : HyperCubeIntent

    /**
     * Avanza al siguiente nivel desde el overlay final. Al superar el último de la rampa
     * ([MAX_LEVEL]) desemboca en el modo libre, que es la continuación natural del juego.
     */
    data object NextLevel : HyperCubeIntent

    /** Vuelve a la antesala con el carril de niveles. */
    data object ChooseLevel : HyperCubeIntent

    /** Retoma la partida guardada que ofrece la antesala. */
    data object ResumeSaved : HyperCubeIntent

    /**
     * El jugador pulsó "Deshacer".
     *
     * El **primero de cada partida es gratis** y se aplica en el acto; a partir de ahí cada uno
     * pasa por un anuncio recompensado, y el ViewModel se limita a marcar
     * [HyperCubeUiState.awaitingUndoAd] para que la pantalla lo lance. Pulsar el botón ya es la
     * confirmación del jugador —el propio botón avisa del coste con su icono—, así que no se
     * interpone ningún diálogo de "¿ver anuncio?", igual que con la pista de Neon Sudoku.
     */
    data object RequestUndo : HyperCubeIntent

    /** El anuncio se vio entero: se aplica el deshacer pagado. */
    data object ConfirmUndo : HyperCubeIntent

    /** El anuncio se cerró antes de tiempo o no había: no se deshace nada y sigue la partida. */
    data object CancelUndo : HyperCubeIntent
}

/**
 * Efectos *one-shot* del Neon Hyper-Cube: el feedback inmediato (sonoro + háptico) que exige el
 * §9 del sistema de diseño. Se emiten por `Channel`→`Flow` y se consumen una sola vez.
 *
 * Igual que en Hypergate, se modelan como **eventos semánticos** en lugar de invocar el
 * `AudioAndHapticManager` desde el motor: así la lógica del cubo queda portable y testeable
 * ("este giro emitió CLACK") y es el ViewModel quien traduce cada señal a la llamada concreta de
 * plataforma.
 */
sealed interface HyperCubeEffect : UiEffect {

    /**
     * Reproduce una señal sonora.
     *
     * @property cue qué se oye: el roce al arrancar el giro ([Cue.SLICE]), el golpe seco al
     *   encajar los 90° ([Cue.CLACK]) o la fanfarria de cubo resuelto ([Cue.SOLVED]).
     */
    data class PlaySound(val cue: Cue) : HyperCubeEffect {
        /** Señales sonoras del cubo. */
        enum class Cue {
            /** Comienzo del giro de una capa: rozamiento. */
            SLICE,

            /** Fin del giro: la capa encaja en su posición discreta. */
            CLACK,

            /** El cubo ha quedado resuelto. */
            SOLVED,
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * @property cue intención del pulso: [Cue.TICK] es el micro-pulso durante el arrastre (da
     *   "tacto" mecánico al gesto sin ser invasivo), [Cue.CLACK] el golpe al completar el giro y
     *   [Cue.SOLVED] la celebración final.
     */
    data class Vibrate(val cue: Cue) : HyperCubeEffect {
        /** Intenciones hápticas del cubo. */
        enum class Cue { TICK, CLACK, SOLVED }
    }
}

/**
 * Yaw inicial de la cámara (≈ 35°). Junto con [DEFAULT_PITCH_RAD] coloca el cubo en la clásica
 * vista de tres cuartos: se ven tres caras a la vez, que es lo que comunica de un vistazo que el
 * objeto es tridimensional y manipulable. Una vista frontal parecería una rejilla plana.
 */
internal const val DEFAULT_YAW_RAD = 0.6f

/** Pitch inicial de la cámara (≈ 25°): mirada ligeramente picada, dentro del límite permitido. */
internal const val DEFAULT_PITCH_RAD = 0.45f
