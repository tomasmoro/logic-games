package com.example.kortexgames.game.neon2048

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.serialization.Serializable

/**
 * # Neon Grid 2048 — contrato MVI (FASE 1)
 *
 * Las tres piezas del ciclo unidireccional (ver
 * [com.example.kortexgames.core.mvi.MviViewModel]): el [Neon2048UiState]
 * renderizable, los [Neon2048Intent] que la UI emite y los [Neon2048Effect]
 * one-shot (sonido/háptica/celebración) que el ViewModel dispara.
 *
 * > Nota de firma: la base de este proyecto es `MviViewModel<Intent, State, Effect>`
 * > (ese es el orden real de sus parámetros de tipo). El motor de colapso/fusión
 * > llega en la FASE 2 y el renderizado animado en la FASE 3.
 *
 * Como el resto de juegos **endless por puntuación** (Neon Pulse, Block Grid),
 * el ciclo de partida se apoya en [GameStatus] + [GameOverInfo] y no en
 * `LeveledGamePhase`: aquí no hay selector de niveles, la partida dura hasta que
 * no queda ningún movimiento válido.
 */

/**
 * Estado inmutable de la pantalla (fuente única de verdad de la UI).
 *
 * @property tiles fichas vivas en el tablero. Es una **lista con identidad**, no
 *   una matriz de valores: ver la nota de diseño en [Tile]. El orden de la lista
 *   es irrelevante para el render (cada ficha se posiciona por sus coordenadas y
 *   se identifica por su `id`), así que el motor puede reconstruirla libremente.
 * @property ghosts fichas **absorbidas** en la última jugada (FASE 2), cada una
 *   con su casilla de origen y de destino (ver [Ghost]). Cuando dos fichas se
 *   fusionan, una sobrevive con el valor doble y la otra desaparece; si
 *   simplemente se borrara, se esfumaría en su casilla vieja en lugar de
 *   deslizarse hasta el encuentro. Por eso la absorbida se conserva un turno: la
 *   UI la anima viajando de [Ghost.from] a [Ghost.to] y la dibuja **debajo** de
 *   la superviviente, que queda encima al llegar ambas al mismo sitio. Se
 *   descarta entera en la siguiente jugada. Va en una lista aparte —y no mezclada
 *   en [tiles]— para que las reglas (ocupación, fusiones, game over) nunca tengan
 *   que filtrar fichas fantasma: son puro artefacto de render.
 * @property score puntuación de la partida en curso. Sigue la regla del 2048
 *   clásico: cada fusión suma el **valor resultante** (2+2 → +4).
 * @property bestScore mejor puntuación histórica del jugador en este juego. Se
 *   lee del repositorio local al iniciar y se refresca en vivo cuando [score] lo
 *   supera, para que la cabecera muestre el récord cayendo en tiempo real.
 * @property status ciclo de vida estándar (IDLE en la antesala/intro, RUNNING en
 *   juego, PAUSED, FINISHED al no quedar jugadas).
 * @property hasWon true en cuanto aparece la primera ficha de
 *   [Neon2048Config.WINNING_VALUE]. Es un hito, **no** un final: se conserva en
 *   true el resto de la partida (sirve de badge en el HUD).
 * @property keepPlaying true si el jugador ya pulsó "seguir jugando" tras ganar.
 *   Se guarda aparte de [hasWon] porque son cosas distintas: [hasWon] responde
 *   "¿llegó a 2048?" y este responde "¿hay que seguir mostrando el diálogo de
 *   victoria?". Sin esta separación, el overlay reaparecería en cada jugada
 *   posterior a la victoria.
 * @property gameOver resumen del resultado (récord + percentil) cuando la
 *   partida termina; null mientras se juega. Mismo patrón que el resto de juegos.
 * @property awaitingRevive true mientras se ofrece revivir viendo un anuncio tras
 *   quedarse sin movimientos. El [status] sigue [GameStatus.RUNNING] (la partida aún no
 *   terminó): la UI muestra el overlay de revivir; al aceptar se **limpian las fichas 2
 *   y 4** y la corrida continúa, al rechazar (o agotarse el tiempo) termina. Es estado
 *   de UI transitorio, por eso no viaja en el guardado serializable ([Neon2048SavedState]).
 * @property boardSize lado del tablero elegido en la antesala (ver
 *   [Neon2048Intent.SelectBoardSize] y [Neon2048Config.BOARD_SIZE_OPTIONS]).
 *   Se conserva entre partidas —incluida [Neon2048Intent.RestartGame]— porque es
 *   una preferencia del jugador, no parte del resultado de la partida anterior;
 *   solo se vuelve a elegir explícitamente desde el selector de la antesala.
 * @property savedScore puntuación de la corrida guardada al salir, o null si no hay
 *   ninguna pendiente. Solo relevante en la antesala (IDLE), donde se ofrece como
 *   "Continuar" (ver [com.example.kortexgames.ui.components.ResumeState]).
 */
data class Neon2048UiState(
    val tiles: List<Tile> = emptyList(),
    val ghosts: List<Ghost> = emptyList(),
    val score: Int = 0,
    val bestScore: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val hasWon: Boolean = false,
    val keepPlaying: Boolean = false,
    val gameOver: GameOverInfo? = null,
    val awaitingRevive: Boolean = false,
    val boardSize: Int = Neon2048Config.DEFAULT_BOARD_SIZE,
    val savedScore: Int? = null,
) : UiState {

    /** Atajo semántico: la partida terminó por falta de movimientos válidos. */
    val isGameOver: Boolean get() = status == GameStatus.FINISHED

    /**
     * ¿Hay que mostrar el overlay de victoria? Solo el turno en el que se alcanza
     * 2048 y hasta que el jugador decida ([Neon2048Intent.ContinueAfterWin] o
     * [Neon2048Intent.RestartGame]).
     */
    val showWinOverlay: Boolean get() = hasWon && !keepPlaying && !isGameOver

    /** Ficha de mayor valor sobre el tablero (0 si está vacío). Alimenta el HUD. */
    val highestValue: Int get() = tiles.maxOfOrNull { it.value } ?: 0
}

/**
 * Subconjunto **serializable** de una partida en curso, guardado al salir (ver
 * [com.example.kortexgames.domain.repository.SavedGameStateRepository]).
 *
 * No se serializa [Neon2048UiState] tal cual porque mezcla datos persistentes con
 * campos de ciclo de vida que no tiene sentido "reanudar" ([Neon2048UiState.status]
 * sería IDLE/PAUSED/FINISHED según cuándo se guardó, y
 * [Neon2048UiState.gameOver] no es serializable ni relevante: siempre es `null`
 * mientras se juega). Este subconjunto es justo lo necesario para reconstruir el
 * tablero tal como estaba.
 *
 * @property nextTileId el contador de [Tile.id] del ViewModel (no vive en
 *   [Neon2048UiState]: ver su KDoc "Estado interno del motor"). Es el campo más
 *   delicado de restaurar: si se reiniciara a 0 en vez de guardarse, el siguiente
 *   `spawnTile()` tras reanudar podría acuñar un id que ya usa una ficha
 *   reanudada, rompiendo la identidad `key(tile.id)` que usa Compose para animar.
 * @property moves / @property productiveMoves contadores de precisión (ver KDoc de
 *   [Neon2048ViewModel]); se restauran para que la partida reanudada siga
 *   contribuyendo a la misma métrica en vez de arrancar de cero.
 */
@Serializable
data class Neon2048SavedState(
    val tiles: List<Tile>,
    val ghosts: List<Ghost>,
    val score: Int,
    val bestScore: Int,
    val hasWon: Boolean,
    val keepPlaying: Boolean,
    val boardSize: Int,
    val nextTileId: Long,
    val moves: Int,
    val productiveMoves: Int,
)

/** Intents: único punto de entrada de la UI (patrón MVI, CLAUDE.md §4). */
sealed interface Neon2048Intent : UiIntent {

    /** Arranca la partida desde la antesala: tablero limpio y dos fichas iniciales. */
    data object StartGame : Neon2048Intent

    /** Desde la antesala: retomar la corrida guardada al salir (ver [Neon2048UiState.savedScore]). */
    data object ResumeSaved : Neon2048Intent

    /**
     * El jugador eligió un tamaño de tablero en el selector de la antesala.
     *
     * Solo tiene efecto en [com.example.kortexgames.game.GameStatus.IDLE]: cambiar
     * el lado del tablero a mitad de partida invalidaría las fichas ya colocadas
     * (coordenadas que dejarían de existir en un tablero más pequeño). El
     * ViewModel ignora el intent fuera de la antesala en vez de fallar — es más
     * simple para la UI (no necesita ocultar el selector durante la partida) y no
     * hay ningún camino real en la app que lo dispare fuera de IDLE.
     *
     * @property size lado nuevo; debe ser uno de [Neon2048Config.BOARD_SIZE_OPTIONS].
     */
    data class SelectBoardSize(val size: Int) : Neon2048Intent

    /**
     * El jugador deslizó el dedo hacia [direction].
     *
     * La UI resuelve el gesto crudo (qué eje domina y si supera
     * [Neon2048Config.SWIPE_THRESHOLD_PX]) porque es geometría de puntero, suya;
     * el ViewModel solo recibe la dirección ya destilada y aplica las reglas. Si
     * el movimiento no cambia el tablero, el reducer lo ignora **sin** generar
     * ficha nueva: en 2048 un swipe contra una pared no consume turno.
     */
    data class Swipe(val direction: Direction) : Neon2048Intent

    /** Reinicia la partida (desde el HUD o desde el overlay de victoria/derrota). */
    data object RestartGame : Neon2048Intent

    /**
     * El anuncio recompensado concedió el trato: **limpia todas las fichas 2 y 4** y
     * continúa la corrida. La oferta es de una sola vez por sesión de juego (al usarla
     * no se vuelve a ofrecer, ni siquiera en una partida nueva).
     */
    data object Revive : Neon2048Intent

    /** Se rechazó la oferta de revivir (o el anuncio no se completó): fin de partida. */
    data object DeclineRevive : Neon2048Intent

    /**
     * Tras alcanzar 2048, el jugador elige seguir jugando: cierra el overlay y
     * mantiene tablero y puntuación intactos (marca `keepPlaying`).
     */
    data object ContinueAfterWin : Neon2048Intent

    /** Pausa / reanuda (congela la entrada y atenúa el tablero). */
    data object Pause : Neon2048Intent
    data object Resume : Neon2048Intent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial que no debe
 * re-emitirse en recomposición ni al rotar (CLAUDE.md §4).
 *
 * Ojo a la frontera con el estado: el "pop" de fusión y el "nacimiento" de una
 * ficha **no** son efectos, son [Tile.isMerged] / [Tile.isNew] — son propiedades
 * de cómo se pinta una ficha concreta, y necesitan sobrevivir a la recomposición
 * mientras dura el turno. Aquí solo viaja lo verdaderamente puntual.
 */
sealed interface Neon2048Effect : UiEffect {

    /**
     * Reproduce un SFX. Se envuelve [SoundEffect] para que la UI solo tenga que
     * reenviarlo al [com.example.kortexgames.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego:
     *  - [Move]     → deslizamiento válido, sin fusión ([SoundEffect.TAP]).
     *  - [Merge]    → al menos una fusión en la jugada ([SoundEffect.SUCCESS]).
     *  - [Win]      → primera ficha 2048 ([SoundEffect.LEVEL_UP]).
     *  - [GameOver] → sin movimientos válidos ([SoundEffect.ERROR]).
     */
    data class PlaySound(val sound: SoundEffect) : Neon2048Effect {
        companion object {
            val Move = PlaySound(SoundEffect.TAP)
            val Merge = PlaySound(SoundEffect.SUCCESS)
            val Win = PlaySound(SoundEffect.LEVEL_UP)
            val GameOver = PlaySound(SoundEffect.ERROR)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Tick]  → confirmación de que el swipe movió algo ([HapticFeedback.LIGHT]).
     *  - [Heavy] → fusión de valor alto, ≥ [Neon2048Config.HEAVY_HAPTIC_THRESHOLD]
     *    ([HapticFeedback.HEAVY]).
     *  - [Error] → swipe que no cambia el tablero: el jugador percibe "aquí no
     *    hay jugada" sin necesidad de mirar ([HapticFeedback.ERROR]).
     */
    data class Vibrate(val feedback: HapticFeedback) : Neon2048Effect {
        companion object {
            val Tick = Vibrate(HapticFeedback.LIGHT)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
            val Error = Vibrate(HapticFeedback.ERROR)
        }
    }

    /**
     * Se alcanzó por primera vez [Neon2048Config.WINNING_VALUE]: la UI lanza la
     * celebración (destello + guirnaldas). Es efecto y no estado porque es un
     * destello puntual; el badge persistente lo cubre [Neon2048UiState.hasWon].
     */
    data object ShowWinCelebration : Neon2048Effect

    /**
     * Fusión "grande": se acaba de crear una ficha de valor
     * ≥ [Neon2048Config.FIREWORKS_MERGE_THRESHOLD] (mayor a 64). La UI lanza una
     * salva corta de fuegos artificiales teñida del color de la ficha resultante
     * ([com.example.kortexgames.ui.components.FireworksOverlay]), más discreta
     * que la de [ShowWinCelebration] —esta puede repetirse varias veces por
     * partida, así que una celebración tan grande como la del 2048 perdería
     * impacto por repetición—.
     *
     * No se emite en el turno en que además se gana la partida: ese turno ya
     * dispara [ShowWinCelebration], y encadenar las dos saturaría la pantalla.
     *
     * @property value valor de la ficha resultante de la fusión (tiñe los fuegos).
     */
    data class ShowMergeCelebration(val value: Int) : Neon2048Effect
}
