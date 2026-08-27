package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.grid.GridPosition

/**
 * # Contrato MVI de "Neon Grid Switch"
 *
 * Juego **LEVELED con selector**: la antesala ofrece un carril de etapas
 * jugables/rejugables (mismo molde que Línea Neón/Starport, `LeveledGamePhase`),
 * pedido explícito del usuario ("las etapas sean seleccionables, como en
 * Crucigrama o Sopa de Letras") — antes se sucedían en orden estricto sin
 * selector, como los juegos de rondas crecientes (Neon Legion, Burbujas de
 * Cálculo); ya no es el caso.
 *
 * ## Coordenadas: `GridPosition`, no `(x, y)`
 *
 * El encargo describe `ToggleCell(x, y)`, pero el vocabulario de rejilla del
 * proyecto ya resolvió esa ambigüedad con [GridPosition] (`row`/`col` con nombre,
 * ver su KDoc en `game.grid`): reutilizarlo aquí evita reintroducir la confusión
 * clásica de "¿x es fila o columna?" y mantiene un único tipo de coordenada en
 * todos los juegos de rejilla (Conectores, Línea Neón y ahora este).
 */

/**
 * Estado renderizable completo de la pantalla.
 *
 * @property phase antesala con selector de etapas o partida en curso (ver KDoc
 *           de archivo).
 * @property maxUnlocked etapa máxima ya superada (récord); define qué etapas
 *           están desbloqueadas en el carril de la antesala.
 * @property gridSize lado de la cuadrícula de la etapa actual (3..6, ver
 *           [GridSwitchStages.gridSizeForStage]). Redundante con `board.size`
 *           pero se expone aparte porque lo pide el layout adaptativo de la UI
 *           (FASE 3) sin tener que destructurar el tablero.
 * @property board tablero de luces actual (única fuente de verdad de qué celdas
 *           están encendidas).
 * @property moveCount toques del jugador en la etapa actual. Se reinicia en cada
 *           [GridSwitchIntent.RestartStage] y en cada nueva etapa; es la base del
 *           marcador de "movimientos" que se muestra al completar la etapa.
 * @property stageLevel etapa en curso (1-based). Determina `gridSize` y el
 *           desorden inicial vía [GridSwitchStages].
 * @property isCompleted true en el instante en que `board.isSolved` se cumple:
 *           la UI muestra el marcador de movimientos y el botón de siguiente
 *           etapa. Coincide con `status == GameStatus.FINISHED` (cada etapa es,
 *           a efectos de ciclo de vida y persistencia, una "partida" propia que
 *           termina al resolverla — mismo molde que Línea Neón/Hyper-Cubo, los
 *           otros LEVELED "que nunca fallan" del catálogo); se expone aparte
 *           para que la UI no tenga que reinterpretar el `status` genérico.
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING↔PAUSED→FINISHED).
 *           FINISHED se alcanza al resolver la etapa actual; [GridSwitchIntent.NextStage]
 *           arranca la siguiente como una partida nueva (RUNNING de nuevo).
 * @property gameOver datos de la pantalla de resultado de la etapa (récord =
 *           etapa máxima alcanzada, percentil); null mientras se juega.
 */
data class GridSwitchUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val gridSize: Int = GRID_SWITCH_MIN_SIZE,
    val board: LightGrid = LightGrid.solved(GRID_SWITCH_MIN_SIZE),
    val moveCount: Int = 0,
    val stageLevel: Int = 1,
    val isCompleted: Boolean = false,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface GridSwitchIntent : UiIntent {

    /** Elige una etapa desbloqueada en el selector y empieza a jugarla. */
    data class PlayStage(val stage: Int) : GridSwitchIntent

    /**
     * El jugador tocó la celda [cell]: conmuta esa celda y sus vecinas ortogonales
     * ([LightGrid.toggled]), incrementa [GridSwitchUiState.moveCount] y comprueba
     * victoria. Se ignora si la etapa ya está completada (evita toques colados
     * mientras se anima la celebración, antes de que el jugador pulse "siguiente").
     */
    data class ToggleCell(val cell: GridPosition) : GridSwitchIntent

    /** Vuelve a desordenar la etapa actual desde cero (mismo tamaño, nuevo scramble). */
    data object RestartStage : GridSwitchIntent

    /** Avanza a la etapa `stageLevel + 1` (solo válido con [GridSwitchUiState.isCompleted]). */
    data object NextStage : GridSwitchIntent

    data object Pause : GridSwitchIntent
    data object Resume : GridSwitchIntent

    /** Desde la pantalla de resultado: rejugar la MISMA etapa (nuevo desorden). */
    data object PlayAgain : GridSwitchIntent

    /** Vuelve al selector de etapas (desde el resultado o el menú de pausa). */
    data object ChooseStage : GridSwitchIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial que no debe
 * re-emitirse en recomposición.
 *
 * Mapeo del brief a los catálogos existentes (mismo criterio que Línea Neón):
 *  - **Toggle** (tocar una celda) → `PlaySound(TAP)` + `Vibrate(LIGHT)` (el
 *    "tick" que confirma cada toque sin resultar intrusivo en tableros grandes
 *    donde se dan muchos toques por partida).
 *  - **StageClear** (etapa resuelta) → `PlaySound(LEVEL_UP)` + `Vibrate(SUCCESS)`.
 */
sealed interface GridSwitchEffect : UiEffect {

    /** Reproduce un SFX semántico del catálogo [SoundEffect] (ver mapeo arriba). */
    data class PlaySound(val sound: SoundEffect) : GridSwitchEffect

    /** Háptica: LIGHT en cada toque (Tick), SUCCESS al resolver la etapa. */
    data class Vibrate(val feedback: HapticFeedback) : GridSwitchEffect
}
