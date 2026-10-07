package com.kortexgames.app.game.tents

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase

/**
 * # Neon Trees & Tents — contrato MVI
 *
 * Triángulo `State` / `Intent` / `Effect` de la pantalla, con el mismo reparto que
 * `ShikakuContract`. Lo implementa [TentsViewModel] y lo consume `TentsScreen`.
 *
 * Regla que separa estado de efecto en este juego: lo que **dura** y hay que pintar durante varios
 * frames es estado (el rojo de un contador excedido, el aviso de dos tiendas que se tocan — ambos
 * se derivan del tablero); lo que **se dispara y se olvida** es efecto (un sonido, una vibración).
 */

/**
 * Estado de UI observable de Neon Trees & Tents.
 *
 * @property phase antesala con selector de nivel o tablero (ver [LeveledGamePhase]).
 * @property maxUnlocked nivel más alto completado; el selector desbloquea hasta el siguiente.
 * @property status ciclo de vida de la partida. El cronómetro solo corre en `RUNNING` y el tablero
 *   solo admite toques en ese estado.
 * @property gameOver resultado guardado (puntaje, percentil, récord); `null` hasta que termina la
 *   partida y responde el repositorio. Es estado y no efecto porque el overlay debe sobrevivir a
 *   las recomposiciones.
 * @property level nivel en juego (1-based).
 * @property board rejilla con árboles, tiendas y pasto; [TentsBoard.EMPTY] hasta que se genera el
 *   primer nivel.
 * @property rowTargets tiendas requeridas por fila (índice = `y`).
 * @property columnTargets tiendas requeridas por columna (índice = `x`).
 * @property rowCounts tiendas colocadas ahora mismo por fila. Se guardan en el estado (en vez de
 *   contarlas la UI al pintar) porque el ViewModel ya las recalcula en cada jugada para decidir la
 *   victoria, y así cada cabecera se recompone solo cuando cambia SU número.
 * @property columnCounts tiendas colocadas ahora mismo por columna.
 * @property isSolved `true` cuando se cumplen a la vez los contadores, el emparejamiento
 *   árbol↔tienda y el aislamiento. Lo fija el ViewModel; no es derivable solo de los contadores.
 * @property isGenerating `true` mientras el generador trabaja en segundo plano: comprobar la
 *   unicidad de un 8×8 puede tardar milisegundos que no deben caer en el hilo principal.
 */
data class TentsUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val level: Int = 1,
    val board: TentsBoard = TentsBoard.EMPTY,
    val rowTargets: List<Int> = emptyList(),
    val columnTargets: List<Int> = emptyList(),
    val rowCounts: List<Int> = emptyList(),
    val columnCounts: List<Int> = emptyList(),
    val isSolved: Boolean = false,
    val isGenerating: Boolean = false,
) : UiState {

    /** Lado del tablero, en casillas. */
    val gridSize: Int get() = board.size

    /** Cómo va la fila [y] frente a su contador; decide el color de la cabecera. */
    fun rowStatus(y: Int): TentsLineStatus = TentsLineStatus.of(rowCounts[y], rowTargets[y])

    /** Cómo va la columna [x] frente a su contador. */
    fun columnStatus(x: Int): TentsLineStatus = TentsLineStatus.of(columnCounts[x], columnTargets[x])
}

/**
 * Intents de Neon Trees & Tents: la única vía de entrada de la UI al ViewModel.
 *
 * Todas las coordenadas van **en celdas**; la pantalla hace la conversión píxel→celda antes de
 * emitir. Los toques sobre un árbol o fuera de la rejilla se ignoran en el ViewModel.
 */
sealed interface TentsIntent : UiIntent {

    /** Elige un nivel desbloqueado en la antesala y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : TentsIntent

    /** Toque simple en ([x], [y]): avanza el ciclo `Vacío → Pasto → Tienda → Vacío`. */
    data class CycleCell(val x: Int, val y: Int) : TentsIntent

    /**
     * Fija ([x], [y]) directamente en [target], saltándose el ciclo. Es lo que usan el modo rápido
     * (pincel de tienda o de pasto) y el arrastre para marcar varias casillas seguidas: con el
     * ciclo, pasar dos veces el dedo por la misma casilla la cambiaría dos veces.
     *
     * [target] no puede ser [TentsCellType.TREE]: los árboles son del nivel, no del jugador.
     */
    data class SetCellState(val x: Int, val y: Int, val target: TentsCellType) : TentsIntent

    /**
     * Vacía el tablero conservando el mismo nivel (mismos árboles y contadores). El cronómetro NO
     * se reinicia y cada reinicio resta puntos: si fuera gratis, bastaría reiniciar para borrar el
     * rastro de las correcciones.
     */
    data object RestartLevel : TentsIntent

    /** Pausa la partida y congela el cronómetro. */
    data object Pause : TentsIntent

    /** Reanuda la partida tras la pausa. */
    data object Resume : TentsIntent

    /** Repite el nivel actual desde cero (cronómetro y penalizaciones incluidos). */
    data object PlayAgain : TentsIntent

    /** Vuelve a la antesala para elegir otro nivel. */
    data object ChooseLevel : TentsIntent

    /**
     * Pasa al nivel siguiente. Solo tiene efecto con la partida terminada: el intent es público y
     * el ViewModel no se fía de que la UI únicamente lo ofrezca tras la victoria.
     */
    data object NextLevel : TentsIntent
}

/**
 * Sonidos del juego, nombrados por lo que significan en la partida. El mapeo al catálogo global
 * ([SoundEffect]) vive aquí para que el ViewModel hable en términos de juego y cambiar un sample
 * sea tocar una sola línea.
 */
enum class TentsSound(val effect: SoundEffect) {
    /** Se planta una tienda. */
    PLACE_TENT(SoundEffect.TAP),

    /** Se marca pasto. Mismo sample que la tienda: es la jugada más repetida y no debe cansar. */
    PLACE_GRASS(SoundEffect.TAP),

    /** La tienda recién puesta toca a otra o desborda un contador. */
    ERROR(SoundEffect.ERROR),

    /** Tablero resuelto. */
    LEVEL_COMPLETE(SoundEffect.LEVEL_UP),
}

/** Vibraciones del juego, con su mapeo a [HapticFeedback]. */
enum class TentsHaptic(val feedback: HapticFeedback) {
    /** Cualquier cambio de casilla: golpe mínimo, se repite muchas veces por nivel. */
    TICK(HapticFeedback.LIGHT),

    /** Nivel completado. */
    SUCCESS(HapticFeedback.SUCCESS),
}

/**
 * Efectos one-shot. Solo sonido y háptica: nada de esto es estado ni debe re-dispararse al
 * recomponer o rotar la pantalla.
 */
sealed interface TentsEffect : UiEffect {

    /** Reproduce [sound]. */
    data class PlaySound(val sound: TentsSound) : TentsEffect

    /** Dispara la vibración [haptic]. */
    data class Vibrate(val haptic: TentsHaptic) : TentsEffect
}
