package com.kortexgames.app.game.hexaflux

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase

/**
 * # Neon Hexa Flux — contrato MVI
 *
 * Las tres piezas del ciclo unidireccional (ver [com.kortexgames.app.core.mvi.MviViewModel]):
 * el [HexaFluxUiState] renderizable, los [HexaFluxIntent] que emite la UI y los
 * [HexaFluxEffect] one-shot de feedback.
 *
 * > Nota de firma: la base es `MviViewModel<Intent, State, Effect>` (ese es el orden
 * > real de los parámetros de tipo en este proyecto).
 */

/**
 * Estado inmutable de la pantalla (fuente única de verdad de la UI).
 *
 * @property phase antesala con selector de nivel o tablero (ver [LeveledGamePhase]).
 * @property maxUnlocked nivel más alto superado; el selector desbloquea hasta el siguiente.
 * @property status ciclo de vida de la partida. El tablero solo admite jugadas en
 *   `RUNNING`; pasa a `FINISHED` tanto al superar el nivel como al perderlo.
 * @property gameOver resultado guardado (puntaje, percentil, récord) de un nivel
 *   **superado**; `null` mientras se juega y también tras una derrota, que no guarda
 *   nada (ver [isGameOver]).
 * @property levelConfig receta del nivel en curso: máscara, gimmicks presentes,
 *   objetivo de victoria y límites. No cambia durante el nivel.
 * @property boardState celdas habilitadas indexadas por su coordenada axial. Las que
 *   la máscara deja fuera no están en el mapa (ver [HexCell]). Se reemplaza entero en
 *   cada jugada, nunca se muta in situ.
 * @property nextPieces piezas disponibles en la bandeja, ya con su rotación actual.
 * @property progress avance hacia [HexaLevelConfig.winCondition]. Va aparte de
 *   [levelConfig] porque es lo único del objetivo que cambia jugada a jugada, y
 *   mezclarlo obligaría a copiar la receta entera en cada turno.
 * @property score puntos de la partida en curso. Son la moneda del objetivo de
 *   puntuación y el reflejo de los combos; el resultado que se guarda lo calcula
 *   [HexaFluxScoring] y no es este número.
 * @property movesLeft jugadas restantes (en supervivencia, turnos que faltan por aguantar).
 * @property combo fusiones encadenadas por la última jugada; `0` si no fusionó. La
 *   pantalla lo usa para encender el halo de combo mientras sea mayor que 1.
 * @property isGameOver `true` si el nivel se perdió (sin jugadas o sin hueco para
 *   ninguna pieza de la bandeja).
 * @property isLevelCleared `true` si se cumplió el objetivo. Excluyente con [isGameOver].
 */
data class HexaFluxUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val levelConfig: HexaLevelConfig = HexaLevelGenerator.generate(1),
    val boardState: Map<HexCoord, HexCell> = levelConfig.initialBoard,
    val nextPieces: List<HexPiece> = emptyList(),
    val progress: ObjectiveProgress = levelConfig.initialProgress(),
    val score: Int = 0,
    val movesLeft: Int = levelConfig.moveLimit,
    val combo: Int = 0,
    val isGameOver: Boolean = false,
    val isLevelCleared: Boolean = false,
) : UiState {

    /** Nivel en juego (1-based). */
    val level: Int get() = levelConfig.level
}

/**
 * Intenciones de la UI.
 *
 * La pantalla resuelve qué celda hay bajo el dedo (solo ella conoce el tamaño real
 * del lienzo) y envía coordenadas axiales ya resueltas; el ViewModel nunca recibe
 * píxeles.
 */
sealed interface HexaFluxIntent : UiIntent {

    /** Elige un nivel desbloqueado en la antesala y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : HexaFluxIntent

    /**
     * Coloca [piece] con su ancla en la celda `([q], [r])`. Si alguna de sus fichas
     * cae fuera de la máscara o sobre una celda ocupada, la jugada se rechaza sin
     * consumir turno.
     *
     * @property piece pieza de la bandeja. Solo se usa su [HexPiece.id] para
     *   localizarla: la rotación que cuenta es la que tiene en el estado, de modo que
     *   una copia desfasada en manos de la UI no puede colocar una orientación que
     *   el jugador ya no está viendo.
     */
    data class PlacePiece(val q: Int, val r: Int, val piece: HexPiece) : HexaFluxIntent

    /**
     * Gira 60° en horario la pieza [pieceIndex] de la bandeja. No consume turno.
     *
     * @property pieceIndex posición en [HexaFluxUiState.nextPieces].
     */
    data class RotatePiece(val pieceIndex: Int) : HexaFluxIntent

    /**
     * Reinicia el nivel en curso con el mismo tablero y la misma secuencia de piezas
     * (el generador es determinista). Sirve tanto a mitad de partida como para
     * reintentar tras perder. El cronómetro NO se pone a cero y cada reinicio resta
     * puntos: si fuera gratis, bastaría reiniciar hasta memorizar las piezas.
     */
    data object RestartLevel : HexaFluxIntent

    /**
     * Avanza al nivel siguiente. Solo tiene efecto con [HexaFluxUiState.isLevelCleared]:
     * el intent es público y el ViewModel no se fía de que la UI lo ofrezca solo
     * tras la victoria.
     */
    data object NextLevel : HexaFluxIntent

    /** Pausa la partida y congela el cronómetro. */
    data object Pause : HexaFluxIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : HexaFluxIntent

    /** Vuelve a la antesala para elegir otro nivel. */
    data object ChooseLevel : HexaFluxIntent
}

/**
 * Efectos one-shot de feedback. NO forman parte del estado: se emiten por `Channel`
 * para no repetirse en recomposición ni al rotar (CLAUDE.md §4, MVI).
 */
sealed interface HexaFluxEffect : UiEffect {

    /**
     * Reproduce un efecto de sonido; la UI lo reenvía tal cual al
     * [com.kortexgames.app.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego (reutilizan assets existentes, sin audio nuevo):
     *  - [Place] → pieza colocada ([SoundEffect.TAP]).
     *  - [Merge] → fusión. Usa [SoundEffect.MERGE_POP], pensado justo para sonar en
     *    casi cada jugada sin cansar. Se emite una vez por jugada aunque haya combo:
     *    varias copias del mismo sample en el mismo instante no suenan "a más".
     *  - [BreakIce] → gimmick roto ([SoundEffect.SUCCESS]): es un logro puntual y
     *    debe destacar sobre el "pop" de las fusiones.
     *  - [LevelComplete] → objetivo cumplido ([SoundEffect.LEVEL_UP]).
     *  - [Invalid] → colocación rechazada o nivel perdido ([SoundEffect.ERROR]).
     */
    data class PlaySound(val sound: SoundEffect) : HexaFluxEffect {
        companion object {
            val Place = PlaySound(SoundEffect.TAP)
            val Merge = PlaySound(SoundEffect.MERGE_POP)
            val BreakIce = PlaySound(SoundEffect.SUCCESS)
            val LevelComplete = PlaySound(SoundEffect.LEVEL_UP)
            val Invalid = PlaySound(SoundEffect.ERROR)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Light] → confirmación de colocación o giro ([HapticFeedback.LIGHT]).
     *  - [Success] → nivel superado ([HapticFeedback.SUCCESS]).
     *  - [Heavy] → una bomba ha detonado ([HapticFeedback.HEAVY]): el golpe fuerte
     *    se reserva para lo único del turno que el jugador no ha provocado él.
     *  - [Error] → colocación rechazada o nivel perdido ([HapticFeedback.ERROR]).
     */
    data class Vibrate(val haptic: HapticFeedback) : HexaFluxEffect {
        companion object {
            val Light = Vibrate(HapticFeedback.LIGHT)
            val Success = Vibrate(HapticFeedback.SUCCESS)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
            val Error = Vibrate(HapticFeedback.ERROR)
        }
    }
}
