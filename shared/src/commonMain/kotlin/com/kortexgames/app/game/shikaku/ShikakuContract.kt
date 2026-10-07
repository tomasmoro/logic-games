package com.kortexgames.app.game.shikaku

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase

/**
 * # Neon Shikaku Matrix — contrato MVI (Fase 1)
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla, siguiendo el patrón canónico del
 * proyecto (`core/mvi/Mvi.kt`, mismo reparto que `QuantumMergeContract`). El `ShikakuViewModel`
 * que lo implementa llega en Fase 2 y la pantalla en Fase 3.
 *
 * Regla que separa estado de efecto en este juego: lo que **dura** y hay que pintar durante varios
 * frames es estado (la selección en curso, el parpadeo de un rectángulo inválido — que se deriva
 * de [ShikakuRectangle.validity]); lo que **se dispara y se olvida** es efecto (un sonido, una
 * vibración). Por eso el arrastre vive en [ShikakuUiState.selection] y no en un canal: si fuera
 * un efecto, la pantalla tendría que guardar su propia copia mutable del rectángulo en curso.
 */

/**
 * Selección en curso mientras el jugador arrastra.
 *
 * Se guardan el ancla y la celda actual (y no solo el rectángulo resultante) porque el ancla es
 * lo que permite seguir estirando hacia cualquier diagonal: con solo los límites no se sabría
 * qué esquina está "clavada" bajo el primer toque.
 *
 * @property anchorX columna donde empezó el gesto.
 * @property anchorY fila donde empezó el gesto.
 * @property currentX columna bajo el dedo ahora mismo.
 * @property currentY fila bajo el dedo ahora mismo.
 * @property validity veredicto en vivo de [bounds]; lo calcula el ViewModel en cada
 *   `UpdateDrag` para que el resaltado y el badge no dupliquen las reglas en la UI.
 * @property targetNumber la pista contenida cuando hay exactamente una, o `null` si no hay
 *   ninguna o hay varias. Permite al badge mostrar "3 × 2 = 6" contra el número que debería dar.
 * @property hasMoved `true` en cuanto el dedo ha salido de la celda ancla al menos una vez. Es lo
 *   que distingue un **toque** (borrar el rectángulo tocado) de un **arrastre** que vuelve a su
 *   origen (rectángulo de 1×1 deliberado). Vive en el estado y no en un `var` del ViewModel para
 *   que el gesto completo sea reproducible a partir de la secuencia de intents.
 */
data class ShikakuSelection(
    val anchorX: Int,
    val anchorY: Int,
    val currentX: Int,
    val currentY: Int,
    val validity: ShikakuRectValidity,
    val targetNumber: Int? = null,
    val hasMoved: Boolean = false,
) {
    /** Rectángulo abarcado entre el ancla y el dedo, ya normalizado. */
    val bounds: ShikakuRect get() = ShikakuRect.spanning(anchorX, anchorY, currentX, currentY)
}

/**
 * Estado de validación global del tablero.
 *
 * El veredicto de cada rectángulo viaja en el propio [ShikakuRectangle.validity]; esto es el
 * agregado que necesitan la barra de progreso y la condición de victoria.
 *
 * @property coveredCells celdas jugables cubiertas por algún rectángulo del jugador.
 * @property totalCells celdas jugables de la máscara.
 * @property invalidCount rectángulos colocados que incumplen alguna regla.
 */
data class ShikakuBoardValidation(
    val coveredCells: Int = 0,
    val totalCells: Int = 0,
    val invalidCount: Int = 0,
) {
    /**
     * Victoria: todo cubierto y ningún rectángulo inválido. No hace falta comprobar solapes
     * aparte: el ViewModel no permite que coexistan dos rectángulos solapados, así que
     * "todas las celdas cubiertas" ya implica "cada una exactamente una vez".
     */
    val isSolved: Boolean get() = totalCells > 0 && coveredCells == totalCells && invalidCount == 0
}

/**
 * Estado de UI observable de Neon Shikaku Matrix.
 *
 * @property phase antesala con selector de nivel o tablero (ver [LeveledGamePhase]).
 * @property maxUnlocked nivel más alto completado; el selector desbloquea hasta el siguiente.
 * @property status ciclo de vida de la partida. El cronómetro solo corre en `RUNNING` y el
 *   tablero solo admite gestos en ese estado.
 * @property gameOver resultado guardado (puntaje, percentil, récord); `null` hasta que termina
 *   la partida y responde el repositorio. Es estado y no efecto porque el overlay debe sobrevivir
 *   a las recomposiciones.
 * @property level nivel en juego (1-based).
 * @property mask figura del tablero; [BoardMask.EMPTY] hasta que se genera el primer nivel.
 * @property clues celdas con número.
 * @property rectangles rectángulos colocados por el jugador, en orden de creación.
 * @property selection arrastre en curso, o `null` si no hay dedo en el tablero.
 * @property validation progreso y veredicto global.
 * @property isGenerating `true` mientras el generador trabaja en segundo plano. Los niveles
 *   grandes pueden tardar decenas de milisegundos (búsqueda de unicidad), así que se generan
 *   fuera del hilo principal y la UI necesita saber que el tablero aún no está listo.
 */
data class ShikakuUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val level: Int = 1,
    val mask: BoardMask = BoardMask.EMPTY,
    val clues: List<ShikakuCell> = emptyList(),
    val rectangles: List<ShikakuRectangle> = emptyList(),
    val selection: ShikakuSelection? = null,
    val validation: ShikakuBoardValidation = ShikakuBoardValidation(),
    val isGenerating: Boolean = false,
) : UiState

/**
 * Intents de Neon Shikaku Matrix: la única vía de entrada de la UI al ViewModel.
 *
 * Todas las coordenadas van **en celdas**. La pantalla ya conoce el tamaño de celda (lo calcula
 * para dibujar) y hace la conversión píxel→celda antes de emitir; el ViewModel recorta a la
 * rejilla lo que se salga, porque el dedo puede abandonar el tablero a mitad de gesto.
 */
sealed interface ShikakuIntent : UiIntent {

    /** Elige un nivel desbloqueado en la antesala y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : ShikakuIntent

    /** El dedo toca la celda ([x], [y]): fija el ancla de una nueva selección. */
    data class StartDrag(val x: Int, val y: Int) : ShikakuIntent

    /**
     * El dedo pasa a la celda ([x], [y]). La pantalla solo debe emitirlo al CAMBIAR de celda, no
     * por cada píxel: cada emisión recalcula la validación y dispara el "tick" háptico.
     */
    data class UpdateDrag(val x: Int, val y: Int) : ShikakuIntent

    /** El dedo se levanta: se intenta sellar la selección vigente. */
    data object ReleaseDrag : ShikakuIntent

    /**
     * El sistema interrumpe el gesto (otra ventana, gesto de sistema). Se descarta la selección
     * sin sellar nada ni sonar a error: el jugador no ha decidido nada.
     */
    data object CancelDrag : ShikakuIntent

    /** Borra el rectángulo [id]. Si ya no existe se ignora (doble toque, carrera con un reinicio). */
    data class RemoveRectangle(val id: Int) : ShikakuIntent

    /**
     * Vacía el tablero conservando el mismo nivel (misma figura y mismas pistas). El cronómetro
     * NO se reinicia y cada reinicio resta puntos: si fuera gratis, bastaría reiniciar para
     * borrar el rastro de las correcciones.
     */
    data object RestartLevel : ShikakuIntent

    /** Pausa la partida: congela el cronómetro y descarta el arrastre en curso. */
    data object Pause : ShikakuIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : ShikakuIntent

    /** Repite el nivel actual desde cero (cronómetro y penalizaciones incluidos). */
    data object PlayAgain : ShikakuIntent

    /** Vuelve a la antesala para elegir otro nivel. */
    data object ChooseLevel : ShikakuIntent

    /**
     * Pasa al nivel siguiente. Solo tiene efecto con la partida terminada: el intent es público y
     * el ViewModel no se fía de que la UI únicamente lo ofrezca tras la victoria.
     */
    data object NextLevel : ShikakuIntent
}

/**
 * Sonidos del juego, nombrados por lo que significan en la partida. El mapeo al catálogo global
 * ([SoundEffect]) vive aquí para que el ViewModel hable en términos de juego y cambiar un sample
 * sea tocar una sola línea.
 */
enum class ShikakuSound(val effect: SoundEffect) {
    /** Empieza una selección. */
    SELECT(SoundEffect.TAP),

    /** Se sella un rectángulo válido. */
    RECTANGLE_COMPLETE(SoundEffect.SUCCESS),

    /** Se rechaza o queda inválido un rectángulo. */
    ERROR(SoundEffect.ERROR),

    /** Tablero completado. */
    LEVEL_COMPLETE(SoundEffect.LEVEL_UP),
}

/** Vibraciones del juego, con su mapeo a [HapticFeedback]. */
enum class ShikakuHaptic(val feedback: HapticFeedback) {
    /** La selección cambia de tamaño: golpe mínimo, se repite muchas veces por gesto. */
    TICK(HapticFeedback.LIGHT),

    /** Rectángulo sellado o nivel completado. */
    SUCCESS(HapticFeedback.SUCCESS),
}

/**
 * Efectos one-shot. Solo sonido y háptica: nada de esto es estado ni debe re-dispararse al
 * recomponer o rotar la pantalla.
 */
sealed interface ShikakuEffect : UiEffect {

    /** Reproduce [sound]. */
    data class PlaySound(val sound: ShikakuSound) : ShikakuEffect

    /** Dispara la vibración [haptic]. */
    data class Vibrate(val haptic: ShikakuHaptic) : ShikakuEffect
}
