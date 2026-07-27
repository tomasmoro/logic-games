package com.example.kortexgames.game.neonsudoku

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.serialization.Serializable

/**
 * # Neon Sudoku Matrix — contrato MVI (FASE 1)
 *
 * Define las tres piezas del ciclo unidireccional (ver
 * [com.example.kortexgames.core.mvi.MviViewModel]): el [NeonSudokuUiState]
 * renderizable, los [NeonSudokuIntent] que la UI emite y los [NeonSudokuEffect]
 * one-shot (sonido/háptica/animación) que el ViewModel dispara.
 *
 * > Nota de firma: la base real del proyecto es
 * > `MviViewModel<Intent, State, Effect>` (ese orden, no
 * > `<Intent, Effect, State>`). El motor de input, notas y detección de choques
 * > filas/columnas/bloques se implementa en FASE 2.
 */

/**
 * Estado inmutable de la pantalla de Neon Sudoku Matrix (fuente única de verdad
 * de la UI).
 *
 * @property board tablero actual, 81 celdas (ver [Board]). Se reemplaza entero
 *   en cada intent que modifica una celda (estado inmutable), nunca se muta.
 * @property selectedCell celda tocada por el jugador; `null` si ninguna. Alimenta
 *   tanto el resaltado de fila/columna/bloque como el highlight de número
 *   (ver [highlightedNumber]) — no se duplican en campos aparte para evitar que
 *   ambos resaltados diverjan entre sí.
 * @property notesMode `true` mientras el "Modo Notas" está activo: los toques de
 *   número (FASE 2) añaden/quitan una marca de lápiz en vez de escribir el valor
 *   definitivo de la celda.
 * @property errorCount número de choques cometidos en la partida. A
 *   [NeonSudokuConfig.MAX_ERRORS] el jugador pierde (salvo revivir con anuncio);
 *   se muestra en el HUD como `errorCount/MAX_ERRORS`.
 * @property elapsedMs tiempo de partida transcurrido en milisegundos.
 * @property difficulty dificultad elegida en la antesala (banco de plantillas y
 *   `difficultyLevel` del resultado). Sobrevive a "jugar de nuevo" como
 *   preferencia del jugador, igual que el tamaño de tablero en Neon Grid 2048.
 * @property awaitingRevive `true` mientras se ofrece la segunda oportunidad: el
 *   jugador agotó los errores y la UI muestra el overlay de "ver anuncio para
 *   continuar". El juego sigue en [GameStatus.RUNNING] (no FINISHED) pero con el
 *   cronómetro congelado, igual que "Burbujas de Cálculo": así, si acepta, se
 *   reanuda sin recrear estado.
 * @property status fase de la partida (IDLE en la antesala, RUNNING jugando,
 *   PAUSED, FINISHED). Reutiliza el [GameStatus] común a todos los juegos para
 *   que la navegación y los overlays se comporten igual que en el resto.
 * @property savedSummary resumen legible de la partida guardada al salir
 *   (p. ej. `"Medio · 42/81 celdas"`), o `null` si no hay ninguna pendiente. La
 *   antesala se lo pasa a `GameIntroScreen` como
 *   [com.example.kortexgames.ui.components.ResumeState.detail]: no-null hace que
 *   "Continuar" ([NeonSudokuIntent.ResumeSaved]) pase a ser el CTA principal y
 *   "Comenzar" ([NeonSudokuIntent.Start], SIEMPRE partida nueva) baje a acción
 *   secundaria — así el jugador nunca se queda sin forma de empezar de cero
 *   habiendo una partida activa guardada (mismo componente compartido que Neon
 *   Grid 2048 usa para su `savedScore`).
 * @property gameOver resumen del resultado (puntaje + percentil) cuando la
 *   partida termina; `null` mientras se juega. Mismo patrón que el resto de
 *   juegos del catálogo.
 */
data class NeonSudokuUiState(
    val board: Board = Board.empty(),
    val selectedCell: CellPosition? = null,
    val notesMode: Boolean = false,
    val errorCount: Int = 0,
    val elapsedMs: Long = 0L,
    val difficulty: SudokuDifficulty = SudokuDifficulty.FACIL,
    val awaitingRevive: Boolean = false,
    val status: GameStatus = GameStatus.IDLE,
    val savedSummary: String? = null,
    val gameOver: GameOverInfo? = null,
) : UiState {

    /**
     * Dígito a resaltar en todo el tablero, o `null` si la celda seleccionada
     * está vacía o no hay ninguna seleccionada. Se deriva de [selectedCell] en
     * vez de guardarse como campo propio: es el mismo valor, mantenerlo aparte
     * solo abriría la puerta a que ambos queden desincronizados.
     */
    val highlightedNumber: Int?
        get() = selectedCell?.let { board.cellAt(it).value }
}

/**
 * Intenciones de la UI: interacciones táctiles del jugador sobre el tablero y el
 * teclado numérico, y pulsos del motor (temporizador).
 */
sealed interface NeonSudokuIntent : UiIntent {

    /**
     * Arranca desde la antesala: SIEMPRE sortea una plantilla nueva de la
     * [SudokuDifficulty] elegida, aunque haya una partida guardada — es el "Comenzar"
     * (o "Empezar de nuevo") de `GameIntroScreen`. Reanudar una guardada es un
     * intent aparte, [ResumeSaved]: sin esa separación, un jugador con una
     * partida activa no tendría forma de empezar una nueva (el guardado siempre
     * ganaría), que es justo lo que este intent evita.
     */
    data object Start : NeonSudokuIntent

    /** Reanuda explícitamente la partida guardada al salir (CTA "Continuar" de la
     *  antesala cuando [NeonSudokuUiState.savedSummary] no es null). La consume:
     *  tras reanudar, el guardado se borra hasta la próxima salida. */
    data object ResumeSaved : NeonSudokuIntent

    /** Repite partida (nueva plantilla de la misma dificultad) desde la pantalla
     *  de resultado. Igual que [Start]: nunca reanuda un guardado. */
    data object PlayAgain : NeonSudokuIntent

    /**
     * El jugador eligió una [SudokuDifficulty] en el selector de la antesala.
     * Solo tiene efecto en [GameStatus.IDLE] (cambiar de dificultad a mitad de
     * partida no tiene sentido); el ViewModel lo ignora fuera de la antesala,
     * mismo criterio que `SelectBoardSize` en Neon Grid 2048.
     */
    data class SelectDifficulty(val difficulty: SudokuDifficulty) : NeonSudokuIntent

    /** El jugador agotó los errores y aceptó ver un anuncio para continuar: se le
     *  devuelve margen ([NeonSudokuConfig.REVIVE_ERROR_GRANT]) y la partida sigue.
     *  Se ofrece una sola vez por partida (lo controla el ViewModel). */
    data object Revive : NeonSudokuIntent

    /** El jugador rechazó la segunda oportunidad (o se agotó su cuenta atrás): la
     *  partida termina de verdad (derrota). */
    data object DeclineRevive : NeonSudokuIntent

    /** Pausa / reanuda (congela el cronómetro). */
    data object Pause : NeonSudokuIntent
    data object Resume : NeonSudokuIntent

    /**
     * El jugador tocó la celda en `(row, col)`. Selecciona la celda para
     * resaltar su fila/columna/bloque; si ya contenía un valor, además dispara
     * el resaltado de "mismo número" (ver [NeonSudokuUiState.highlightedNumber]).
     *
     * @property row fila tocada, `0..8`.
     * @property col columna tocada, `0..8`.
     */
    data class SelectCell(val row: Int, val col: Int) : NeonSudokuIntent

    /**
     * El jugador tocó el dígito [number] (`1..9`) en el `Numpad` (FASE 3) con
     * una celda ya seleccionada. Si [NeonSudokuUiState.notesMode] está activo,
     * el reducer (FASE 2) añade/quita [number] como marca de lápiz (toggle); si
     * no, lo escribe como valor definitivo y valida choques.
     *
     * @property number dígito pulsado, `1..9`.
     */
    data class InputNumber(val number: Int) : NeonSudokuIntent

    /** Alterna el "Modo Notas" (lápiz) on/off para las siguientes pulsaciones
     *  de número. No modifica ninguna celda por sí sola. */
    data object ToggleNotesMode : NeonSudokuIntent

    /** Borra el valor y/o las notas de la celda seleccionada (no aplica sobre
     *  celdas fijas: ver [SudokuCell.isFixed], el reducer FASE 2 lo ignora). */
    data object EraseCell : NeonSudokuIntent

    /**
     * Pulso del cronómetro de partida. Avanza [deltaMillis] milisegundos el
     * tiempo transcurrido. Se modela como intent (y no como método interno) para
     * mantener el ciclo MVI unidireccional puro, igual que el resto de juegos
     * del proyecto: el reloj es la única fuente que emite [Tick].
     *
     * @property deltaMillis tiempo transcurrido desde el `tick` anterior.
     */
    data class Tick(val deltaMillis: Long) : NeonSudokuIntent
}

/**
 * Efectos one-shot (feedback inmediato). NO forman parte del estado: se emiten
 * por `Channel` para no repetirse en recomposición ni al rotar (CLAUDE.md §MVI).
 *
 * El feedback inmediato (sonoro + háptico + visual) al acertar/fallar es un
 * requisito de producto para la sensación "viva" de la app (CLAUDE.md §9.4).
 */
sealed interface NeonSudokuEffect : UiEffect {

    /**
     * Reproduce un efecto de sonido. Se envuelve [SoundEffect] para que la UI
     * solo tenga que reenviarlo al
     * [com.example.kortexgames.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego:
     *  - [Input] → dígito escrito o nota añadida/quitada sin choque.
     *  - [Error] → el número escrito choca con fila/columna/bloque.
     *  - [UnitComplete] → se completó una fila, columna o bloque 3x3.
     *  - [LevelComplete] → tablero completado sin choques (victoria).
     */
    data class PlaySound(val sound: SoundEffect) : NeonSudokuEffect {
        companion object {
            val Input = PlaySound(SoundEffect.TAP)
            val Error = PlaySound(SoundEffect.ERROR)
            val UnitComplete = PlaySound(SoundEffect.SUCCESS)
            val LevelComplete = PlaySound(SoundEffect.LEVEL_UP)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Tick]  → vibración ligera al seleccionar una celda.
     *  - [Heavy] → vibración fuerte al cometer un choque.
     */
    data class Vibrate(val haptic: HapticFeedback) : NeonSudokuEffect {
        companion object {
            val Tick = Vibrate(HapticFeedback.LIGHT)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
            val Success = Vibrate(HapticFeedback.SUCCESS)
        }
    }

    /**
     * Se acaba de completar una o más **unidades** (fila, columna o bloque 3x3):
     * las 9 celdas llenas y sin choques. La UI celebra con una onda que recorre
     * esas celdas desde la recién rellenada, en el mismo lenguaje visual que la
     * limpieza de línea de Tetris Neón (destello escalonado + chispas).
     *
     * Va como efecto one-shot y no como estado porque es una celebración con
     * ciclo de vida propio: nace, se propaga y se apaga sola sin que el dominio
     * tenga que emitir un frame por paso.
     *
     * @property cells unión (sin repetidos) de las celdas de todas las unidades
     *   completadas por la misma jugada. Al colocar el dígito que cierra a la vez
     *   la fila y su bloque, ambos comparten celdas: se envían fusionadas para
     *   que la UI no las dibuje —ni las haga chispear— dos veces.
     * @property origin celda que el jugador acaba de rellenar; epicentro desde el
     *   que se propaga la onda.
     */
    data class UnitsCompleted(
        val cells: List<CellPosition>,
        val origin: CellPosition,
    ) : NeonSudokuEffect

    /**
     * Se acaban de colocar las **9 apariciones** de [digit] en el tablero, sin
     * ningún choque entre ellas: ya no queda ninguna instancia por colocar.
     * Agotar un dígito es un hito de la partida entera (no de una unidad
     * puntual como [UnitsCompleted]), así que la UI celebra con **fuegos
     * artificiales de pantalla completa** — el mismo componente que usa el
     * resto del catálogo para un nuevo récord —, más vistoso que el destello
     * local de una fila/columna/bloque.
     *
     * Se emite como mucho una vez por dígito y partida: el ViewModel lleva la
     * cuenta para no repetir la celebración si el jugador reescribe una celda
     * que ya tenía ese valor.
     *
     * @property digit dígito agotado, `1..9`.
     */
    data class DigitCompleted(val digit: Int) : NeonSudokuEffect

    /**
     * Solicita a la UI la animación de sacudida horizontal (`shake`) sobre la
     * celda [position] al cometer un choque (FASE 3). Se envía la posición en
     * vez de asumir "la celda seleccionada" porque para cuando la UI procesa el
     * efecto la selección ya pudo cambiar.
     *
     * @property position celda que chocó y debe sacudirse.
     */
    data class ShakeCell(val position: CellPosition) : NeonSudokuEffect

    /** Solicita a la UI la onda de luz que barre el tablero completo al ganar
     *  (FASE 3). No lleva datos: es un barrido sobre las 81 celdas. */
    data object SweepVictory : NeonSudokuEffect
}

/**
 * # Partida en curso guardada (FASE 4)
 *
 * Instantánea serializable de una partida sin terminar, para que salir (back del
 * sistema o "SALIR" del menú de pausa) no pierda el progreso: al volver a la
 * antesala, "Comenzar" la reanuda. Mismo mecanismo que Neon Grid 2048
 * ([com.example.kortexgames.game.neon2048.Neon2048SavedState]): se persiste como
 * JSON crudo vía [com.example.kortexgames.domain.repository.SavedGameStateRepository]
 * (100% local, no viaja a Supabase — es estado efímero de sesión, no un récord).
 *
 * Se guardan **todas** las piezas necesarias para reconstruir el estado bit a
 * bit, incluidos los contadores de precisión y el flag de revivir: reanudar debe
 * dejar la partida exactamente como estaba, no "casi".
 *
 * @property cells las 81 celdas en orden plano `row*9+col` (se reconstruye el
 *   [Board] con ellas; no se serializa `Board` directamente porque su lista es un
 *   detalle privado — ver [Board.allCells]).
 * @property selectedRow / @property selectedCol celda seleccionada, o `-1`/`-1`
 *   si no había ninguna (se usan primitivos en vez de un `CellPosition?` anidado
 *   para un JSON plano y estable).
 * @property notesMode si el modo lápiz estaba activo.
 * @property errorCount choques cometidos hasta el momento del guardado.
 * @property elapsedMs tiempo transcurrido acumulado.
 * @property difficulty dificultad de la partida (banco de plantillas + resultado).
 * @property reviveOffered si la segunda oportunidad ya se ofreció (para no
 *   ofrecerla dos veces tras reanudar).
 * @property totalInputs / @property conflictInputs contadores de precisión, para
 *   que el `accuracyPercentage` del resultado siga siendo correcto tras reanudar.
 */
@Serializable
data class NeonSudokuSavedState(
    val cells: List<SudokuCell>,
    val selectedRow: Int,
    val selectedCol: Int,
    val notesMode: Boolean,
    val errorCount: Int,
    val elapsedMs: Long,
    val difficulty: SudokuDifficulty,
    val reviveOffered: Boolean,
    val totalInputs: Int,
    val conflictInputs: Int,
)
