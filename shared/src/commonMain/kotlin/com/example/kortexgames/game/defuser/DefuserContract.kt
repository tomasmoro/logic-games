package com.example.kortexgames.game.defuser

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import kotlinx.serialization.Serializable

/**
 * # Neon Defuser — contrato MVI (FASE 1)
 *
 * Define las tres piezas del ciclo unidireccional (ver
 * [com.example.kortexgames.core.mvi.MviViewModel]): el [DefuserUiState]
 * renderizable, los [DefuserIntent] que la UI emite y los [DefuserEffect] one-shot
 * (sonido / háptica / animación) que el ViewModel dispara.
 *
 * > **Nota de firma:** la base real del proyecto es
 * > `MviViewModel<Intent, State, Effect>` (ese orden). El motor —colocación de
 * > minas con primer toque seguro, conteo de vecinas y *flood fill* de la
 * > cascada— se implementa en FASE 2; el renderizado en `Canvas`, en FASE 3.
 */

/**
 * Estado inmutable de la pantalla de Neon Defuser (fuente única de verdad de la UI).
 *
 * @property board panel actual (ver [MineBoard]). Se reemplaza entero en cada
 *   intent que modifica celdas (estado inmutable), nunca se muta in situ.
 * @property phase fase de la partida dentro del juego (jugando / ganada / perdida,
 *   ver [MinePhase]). Gobierna qué overlay muestra la UI al terminar; es
 *   independiente de [status] (lifecycle de navegación).
 * @property status fase de navegación (IDLE en la antesala, RUNNING jugando,
 *   PAUSED, FINISHED). Reutiliza el [GameStatus] común a todos los juegos para que
 *   overlays y navegación se comporten igual que en el resto del catálogo.
 * @property minesArmed `true` una vez colocadas las minas (tras el primer toque).
 *   Mientras es `false`, el panel está vacío y el primer [DefuserIntent.RevealCell]
 *   dispara la generación garantizando que esa celda —y su vecindad— sea segura
 *   (FASE 2). También distingue "aún no has empezado" para el cronómetro.
 * @property elapsedMs tiempo de partida transcurrido en milisegundos. Arranca con
 *   el primer toque (no al entrar en la pantalla), como en el Buscaminas clásico.
 * @property difficulty dificultad elegida en la antesala. Sobrevive a "jugar de
 *   nuevo" como preferencia del jugador, igual que la dificultad de Neon Sudoku.
 * @property hasSavedGame `true` si al abrir la antesala existe una partida guardada
 *   que "Comenzar" reanudará; solo cambia la etiqueta del botón, la reanudación la
 *   resuelve el ViewModel.
 * @property awaitingRevive `true` mientras se ofrece la segunda oportunidad: el
 *   jugador pisó una mina y la UI muestra el overlay de "ver anuncio para
 *   continuar" (mismo patrón que Neon Sudoku). La partida sigue en
 *   [GameStatus.RUNNING] (no FINISHED) pero con el cronómetro congelado, así que si
 *   acepta se reanuda sin recrear estado. Se ofrece una sola vez por partida.
 * @property gameOver resumen del resultado (puntaje + percentil) cuando la partida
 *   termina; `null` mientras se juega. Mismo patrón que el resto de juegos.
 */
data class DefuserUiState(
    val board: MineBoard = MineBoard.blank(MineDifficulty.FACIL),
    val phase: MinePhase = MinePhase.PLAYING,
    val status: GameStatus = GameStatus.IDLE,
    val minesArmed: Boolean = false,
    val elapsedMs: Long = 0L,
    val difficulty: MineDifficulty = MineDifficulty.FACIL,
    val hasSavedGame: Boolean = false,
    val awaitingRevive: Boolean = false,
    val gameOver: GameOverInfo? = null,
) : UiState {

    /**
     * Minas que el jugador **cree** que le quedan por marcar: total de minas menos
     * escudos colocados. Es el número clásico del HUD del Buscaminas. Puede ser
     * negativo si el jugador pone más banderas que minas hay (comportamiento
     * esperado, no se recorta). Se **deriva** del recuento de banderas del tablero
     * en vez de guardarse como campo propio para que nunca pueda desincronizarse.
     *
     * El total sale de [difficulty] y NO de `board.mineCount` a propósito: antes
     * del primer toque las minas aún no están sembradas ([minesArmed] `false`) y
     * `board.mineCount` sería `0`; usar la dificultad hace que el HUD muestre el
     * objetivo real desde que se entra a la partida.
     */
    val minesRemaining: Int get() = difficulty.mineCount - board.flagCount
}

/**
 * Intenciones de la UI: los gestos del jugador sobre el panel y los pulsos del
 * motor (cronómetro), más el ciclo de vida de la antesala.
 */
sealed interface DefuserIntent : UiIntent {

    /** Arranca desde la antesala (IDLE → RUNNING): reanuda la partida guardada si
     *  existe, o prepara un panel vacío de la [MineDifficulty] elegida (las minas
     *  se siembran en el primer [RevealCell], FASE 2). */
    data object Start : DefuserIntent

    /** Reinicia la partida con un panel nuevo de la **misma** dificultad. Cubre
     *  tanto el botón "Reiniciar" del HUD como "Jugar de nuevo" tras ganar/perder;
     *  a diferencia de [Start], NUNCA reanuda un guardado. */
    data object RestartGame : DefuserIntent

    /**
     * El jugador eligió una [MineDifficulty] en el selector de la antesala. Solo
     * tiene efecto en [GameStatus.IDLE] (cambiar de dificultad a mitad de partida
     * no tiene sentido); el ViewModel lo ignora fuera de la antesala, mismo
     * criterio que `SelectDifficulty` en Neon Sudoku.
     */
    data class SelectDifficulty(val difficulty: MineDifficulty) : DefuserIntent

    /**
     * **Tap corto:** revela la celda en [position].
     *  - Si es el primer toque de la partida, el motor (FASE 2) siembra las minas
     *    dejando esta celda y su vecindad libres (primer toque siempre seguro).
     *  - Si oculta una mina → explota ([MinePhase.LOST], Game Over).
     *  - Si es un número → se muestra.
     *  - Si es 0 → desencadena la **cascada** (*flood fill*) que revela en onda
     *    todas las celdas seguras conectadas.
     *
     * El motor ignora el intent sobre celdas ya reveladas o marcadas con escudo
     * (no se puede revelar por accidente una celda que marcaste como mina).
     *
     * @property position celda tocada.
     */
    data class RevealCell(val position: CellPosition) : DefuserIntent

    /**
     * **Long press (tap prolongado):** coloca o quita un "escudo" (bandera) en la
     * celda oculta en [position] para marcar dónde el jugador cree que hay una
     * mina. Es un *toggle* y solo aplica a celdas [MineCellState.HIDDEN]/`FLAGGED`;
     * sobre una celda ya revelada no hace nada.
     *
     * @property position celda a marcar/desmarcar.
     */
    data class ToggleFlag(val position: CellPosition) : DefuserIntent

    /** El jugador pisó una mina y aceptó ver un anuncio para continuar: esa mina
     *  concreta queda **neutralizada** (pasa a escudo permanente, ver
     *  [MineCell.isDefused]) y la partida sigue. Se ofrece una sola vez por
     *  partida (lo controla el ViewModel), igual que `Revive` en Neon Sudoku. */
    data object Revive : DefuserIntent

    /** El jugador rechazó la segunda oportunidad (o se agotó su cuenta atrás): la
     *  partida termina de verdad (derrota), revelando el resto de minas. */
    data object DeclineRevive : DefuserIntent

    /** Pausa / reanuda (congela el cronómetro) sin perder el estado del panel. */
    data object Pause : DefuserIntent
    data object Resume : DefuserIntent

    /**
     * Pulso del cronómetro de partida. Avanza [deltaMillis] milisegundos el tiempo
     * transcurrido. Se modela como intent (y no como método interno) para mantener
     * el ciclo MVI unidireccional puro, igual que el resto de juegos: el reloj es
     * la única fuente que emite [Tick].
     *
     * @property deltaMillis tiempo transcurrido desde el `tick` anterior.
     */
    data class Tick(val deltaMillis: Long) : DefuserIntent
}

/**
 * Efectos one-shot (feedback inmediato). NO forman parte del estado: se emiten por
 * `Channel` para no repetirse en recomposición ni al rotar (CLAUDE.md §MVI).
 *
 * El feedback inmediato (sonoro + háptico + visual) al revelar / marcar / explotar
 * es un requisito de producto para la sensación "viva" de la app (CLAUDE.md §9.4).
 */
sealed interface DefuserEffect : UiEffect {

    /**
     * Reproduce un efecto de sonido. Se envuelve [SoundEffect] para que la UI solo
     * tenga que reenviarlo al
     * [com.example.kortexgames.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego (mapeados al catálogo [SoundEffect] existente;
     * si más adelante se graban assets propios de Buscaminas, basta cambiar el
     * mapeo aquí, sin tocar el motor):
     *  - [Reveal]    → tap corto que descubre una celda segura individual.
     *  - [Cascade]   → apertura en cadena de una zona de ceros (onda expansiva).
     *  - [Flag]      → colocar/quitar un escudo.
     *  - [Explosion] → se reveló una mina: Game Over.
     *  - [Win]       → se despejó todo el campo: victoria.
     */
    data class PlaySound(val sound: SoundEffect) : DefuserEffect {
        companion object {
            val Reveal = PlaySound(SoundEffect.TAP)
            val Cascade = PlaySound(SoundEffect.SUCCESS)
            val Flag = PlaySound(SoundEffect.TAP)
            val Explosion = PlaySound(SoundEffect.ERROR)
            val Win = PlaySound(SoundEffect.LEVEL_UP)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Light] → vibración ligera al revelar una celda o poner un escudo.
     *  - [Heavy] → vibración fuerte al pisar una mina (la explosión se "siente").
     */
    data class Vibrate(val haptic: HapticFeedback) : DefuserEffect {
        companion object {
            val Light = Vibrate(HapticFeedback.LIGHT)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
        }
    }

    /**
     * Solicita a la UI la animación de **halo expansivo** de la mina que el jugador
     * pisó (FASE 3). Se envía la [position] concreta (en vez de asumir "la última
     * tocada") porque para cuando la UI procesa el efecto el estado ya pudo cambiar
     * y conviene que el origen del halo sea explícito.
     *
     * Se dispara siempre al pisar una mina, gane o no el jugador la segunda
     * oportunidad después: la explosión es el mismo golpe visual/sonoro tanto si
     * termina en derrota como si se ofrece revivir ([DefuserUiState.awaitingRevive]).
     *
     * @property position celda de la mina detonada, foco del halo rojo.
     */
    data class ExplodeAt(val position: CellPosition) : DefuserEffect

    /**
     * Solicita a la UI la **celebración de victoria**: una tanda larga de fuegos
     * artificiales neón ([com.example.kortexgames.ui.components.FireworksOverlay]),
     * con sonido y háptica sincronizados a cada estallido. No lleva datos: la
     * secuencia y su ritmo son cosa de la capa de presentación.
     *
     * Nota: la cascada de revelado (tap sobre un 0) NO necesita un efecto propio;
     * la UI la escalona a partir de [CellPosition.distanceTo] entre cada celda
     * recién revelada y el origen del toque, reconstruyendo la onda desde el propio
     * estado (ver FASE 3). Aquí solo viaja el sonido [PlaySound.Cascade].
     */
    data object VictoryFireworks : DefuserEffect
}

/**
 * Fotografía serializable de una partida de Neon Defuser en curso, para guardarla
 * al salir y reanudarla desde la antesala (mismo mecanismo 100% local que Neon
 * Sudoku, vía [com.example.kortexgames.domain.repository.SavedGameStateRepository]).
 *
 * Persiste el **panel completo** ([MineBoard], que ya incluye dimensiones, minas
 * colocadas, adyacencias calculadas y el estado de cada celda) más los contadores
 * que no se pueden re-derivar de él. La [minesArmed] viaja explícita porque un
 * panel recién empezado (sin minas aún) y uno donde por azar el jugador no reveló
 * nada son indistinguibles solo mirando las celdas.
 *
 * @property board panel en su estado actual (fuente de verdad al reanudar).
 * @property minesArmed si las minas ya se sembraron (ver [DefuserUiState.minesArmed]).
 * @property elapsedMs cronómetro acumulado.
 * @property difficulty dificultad de la partida.
 * @property reviveOffered si la segunda oportunidad ya se ofreció en esta partida
 *   (ver [DefuserIntent.Revive]): evita que, tras reanudar un guardado, el jugador
 *   reciba una segunda "vida extra" en la misma partida.
 */
@Serializable
data class DefuserSavedState(
    val board: MineBoard,
    val minesArmed: Boolean,
    val elapsedMs: Long,
    val difficulty: MineDifficulty,
    val reviveOffered: Boolean,
)
