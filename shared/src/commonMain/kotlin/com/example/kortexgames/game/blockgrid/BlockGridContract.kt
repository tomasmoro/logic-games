package com.example.kortexgames.game.blockgrid

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus

/**
 * # Contrato MVI de "Neon Block Grid"
 *
 * A diferencia de los juegos LEVELED (Water Sort, Tornillos Neón), este es un
 * juego **endless por puntuación**: no hay selector de niveles, la partida
 * dura hasta que ninguna pieza de la mano cabe en el tablero. Por eso el
 * estado se apoya en [GameStatus] + [GameOverInfo] (molde de Reflejos/Memoria)
 * y no en `LeveledGamePhase`.
 */

/**
 * Vista previa de colocación durante el arrastre ("fantasma" gris).
 *
 * La calcula el ViewModel en cada `DragMoved` — y no la UI — porque decidir
 * validez requiere las reglas del tablero (colisiones, límites) y el patrón
 * MVI exige que la UI solo pinte estado, nunca lo derive.
 *
 * @property cells celdas del tablero que ocuparía la pieza anclada donde está
 *           el dedo. Se pintan atenuadas para anticipar el resultado.
 * @property isValid true si soltar aquí ancla la pieza. Con false la UI pinta
 *           el fantasma en tono de error para comunicar "aquí no cabe".
 */
data class PlacementPreview(
    val cells: Set<GridPos>,
    val isValid: Boolean,
)

/**
 * Estado del arrastre en curso, o null si no se arrastra nada.
 *
 * Vive en el `State` (no en la UI) porque afecta al render de varias piezas a
 * la vez: la pieza levantada se escala y se oculta de la mano, y el tablero
 * pinta el fantasma. Mantenerlo centralizado evita estados locales
 * desincronizados entre composables.
 *
 * @property pieceId pieza de la mano que se está arrastrando.
 * @property preview fantasma actual sobre el tablero; null mientras el dedo
 *           está fuera del área del tablero.
 */
data class DragState(
    val pieceId: Int,
    val preview: PlacementPreview? = null,
)

/**
 * Estado renderizable completo de la pantalla.
 *
 * @property board tablero 8×8 (única fuente de verdad de qué hay en cada celda,
 *           incluidas las celdas en animación de limpieza [BoardCell.Clearing]).
 * @property hand piezas disponibles. Tamaño ≤ [HAND_SIZE]; al vaciarse el motor
 *           repone 3 nuevas. Las colocadas se eliminan (la UI las distingue por
 *           `id`, ver [Polyomino.id]).
 * @property drag arrastre en curso, o null en reposo.
 * @property score puntos acumulados (bloques colocados + líneas + combos).
 * @property linesCleared total de líneas rotas en la partida (métrica para
 *           logros y resultados).
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING→FINISHED).
 * @property gameOver datos de la pantalla de resultado (récord, percentil);
 *           null mientras se juega.
 */
data class BlockGridUiState(
    val board: BoardGrid = BoardGrid(),
    val hand: List<Polyomino> = emptyList(),
    val drag: DragState? = null,
    val score: Int = 0,
    val linesCleared: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface BlockGridIntent : UiIntent {

    /** Arranca (o reinicia) la partida: tablero limpio y mano nueva. */
    data object StartGame : BlockGridIntent

    /**
     * El jugador levantó la pieza [pieceId] de la mano. Dispara el feedback de
     * "resorte" (escala + háptica ligera) y activa el modo arrastre.
     */
    data class DragStarted(val pieceId: Int) : BlockGridIntent

    /**
     * El dedo movió la pieza y su origen (esquina superior izquierda del
     * bounding box) cae ahora sobre la celda ([row], [col]) del tablero, o
     * fuera de él si [row]/[col] no forman una posición válida. La UI traduce
     * píxeles→celda (es geometría de layout, suya); el ViewModel decide el
     * fantasma (son reglas de juego, suyas).
     */
    data class DragMoved(val pieceId: Int, val row: Int, val col: Int) : BlockGridIntent

    /**
     * El jugador soltó la pieza con origen en ([row], [col]). Si la posición
     * es válida la pieza se ancla, se evalúan líneas y Game Over; si no, la
     * pieza "vuelve" a la mano (la UI anima el retorno al limpiarse [DragState]).
     */
    data class DropPiece(val pieceId: Int, val row: Int, val col: Int) : BlockGridIntent

    /** El arrastre se canceló (dedo soltado fuera del tablero, gesto interrumpido). */
    data object DragCancelled : BlockGridIntent

    /**
     * La UI terminó la animación fade+shrink de las celdas [BoardCell.Clearing]:
     * el dominio las vacía definitivamente. Separar "línea rota" (dominio) de
     * "animación terminada" (UI) evita temporizadores en el ViewModel, igual
     * que `PlateFallFinished` en Tornillos Neón.
     */
    data object LineClearFinished : BlockGridIntent

    data object Pause : BlockGridIntent
    data object Resume : BlockGridIntent

    /** Desde la pantalla de resultado: nueva partida. */
    data object PlayAgain : BlockGridIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial y
 * celebraciones que no deben re-emitirse en recomposición.
 */
sealed interface BlockGridEffect : UiEffect {

    /**
     * Reproduce un SFX semántico: TAP al levantar/anclar pieza, SUCCESS al
     * romper línea, ERROR en Game Over (mapeo del [SoundEffect] existente).
     */
    data class PlaySound(val sound: SoundEffect) : BlockGridEffect

    /** Háptica: LIGHT al levantar, MEDIUM al anclar, HEAVY al romper líneas. */
    data class Vibrate(val feedback: HapticFeedback) : BlockGridEffect

    /**
     * Se rompieron [lines] líneas a la vez (≥ 2 = combo): la UI muestra la
     * animación de celebración proporcional. Es efecto y no estado porque es
     * un destello puntual que no debe reaparecer al recomponer.
     */
    data class ShowComboAnim(val lines: Int) : BlockGridEffect
}
