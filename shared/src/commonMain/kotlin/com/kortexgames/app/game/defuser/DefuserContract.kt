package com.kortexgames.app.game.defuser

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import kotlinx.serialization.Serializable

/**
 * # Neon Defuser — contrato MVI (FASE 1)
 *
 * Define las tres piezas del ciclo unidireccional (ver
 * [com.kortexgames.app.core.mvi.MviViewModel]): el [DefuserUiState]
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
 * @property scanUsesRemaining usos del **escáner de minas** que le quedan al jugador
 *   en esta partida ([DefuserConfig.SCAN_MAX_USES] al empezar). Cada anuncio visto
 *   con éxito lo decrementa; a `0` el botón de escáner se deshabilita. Persiste en el
 *   guardado ([DefuserSavedState]) para que salir y reanudar no lo recargue.
 * @property awaitingScanAd `true` mientras se está reproduciendo el anuncio del
 *   escáner (entre pulsar el botón y conocer el resultado). Bloquea los toques del
 *   tablero para que un tap no se procese "por debajo" del anuncio. La UI lo observa
 *   para lanzar el rewarded, igual que `awaitingHint` en Neon Sudoku.
 * @property scanning `true` cuando el anuncio ya se vio y el jugador está en **modo
 *   selección**: el siguiente toque sobre una celda oculta la inspecciona (revela si
 *   es mina —queda desactivada— o segura —se abre con cascada—) en vez de jugarse
 *   como un revelado normal. Es transitorio (no se persiste): al inspeccionar o
 *   cancelar vuelve a `false`.
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
    val scanUsesRemaining: Int = DefuserConfig.SCAN_MAX_USES,
    val awaitingScanAd: Boolean = false,
    val scanning: Boolean = false,
    val gameOver: GameOverInfo? = null,
) : UiState {

    /**
     * `true` si el jugador puede activar el **escáner de minas** ahora mismo: partida
     * en curso con minas ya sembradas (inspeccionar antes del primer toque no tendría
     * sentido, aún no hay minas), le quedan usos, y no hay ya un anuncio/selección o
     * una oferta de revivir en marcha. La UI la usa para habilitar/atenuar el botón,
     * y el ViewModel la revalida antes de lanzar el anuncio (fuente única del "porqué").
     */
    val canRequestScan: Boolean
        get() = status == GameStatus.RUNNING && minesArmed && scanUsesRemaining > 0 &&
            !awaitingRevive && !awaitingScanAd && !scanning

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
     * **Modo escáner:** si [DefuserUiState.scanning] está activo (el jugador vio el
     * anuncio del escáner), este mismo toque **inspecciona** la celda en vez de
     * jugarla: una mina queda neutralizada como escudo permanente y una celda segura
     * se abre con cascada. Se reutiliza este intent —en lugar de uno nuevo— porque la
     * UI ya enruta el tap del tablero aquí; el ViewModel bifurca según el estado.
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

    /**
     * El jugador pulsó el botón del **escáner de minas**: pide ver un anuncio para
     * poder inspeccionar una celda a elección. No inspecciona nada todavía —solo
     * arranca el anuncio ([DefuserUiState.awaitingScanAd])—; la UI lanza el rewarded y
     * responde con [ConfirmScan] (visto) o [CancelScan] (cerrado / no disponible),
     * mismo patrón que `RequestHint` en Neon Sudoku. No-op si [DefuserUiState.canRequestScan]
     * es `false`.
     */
    data object RequestScan : DefuserIntent

    /** El anuncio del escáner se vio completo: descuenta un uso y entra en **modo
     *  selección** ([DefuserUiState.scanning]), a la espera de que el jugador toque la
     *  celda a inspeccionar. */
    data object ConfirmScan : DefuserIntent

    /** Cancela el escáner: cubre tanto que el anuncio se cerrara antes de recompensar
     *  (no se descuenta uso) como que el jugador abandone el modo selección tras verlo
     *  (el uso ya se gastó en el anuncio). En ambos casos limpia
     *  [DefuserUiState.awaitingScanAd]/[DefuserUiState.scanning]. */
    data object CancelScan : DefuserIntent

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
     * [com.kortexgames.app.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego (mapeados al catálogo [SoundEffect] existente;
     * si más adelante se graban assets propios de Buscaminas, basta cambiar el
     * mapeo aquí, sin tocar el motor):
     *  - [Reveal]    → tap corto que descubre una celda segura individual.
     *  - [Cascade]   → apertura en cadena de una zona de ceros (onda expansiva).
     *  - [Flag]      → colocar/quitar un escudo.
     *  - [Explosion] → se reveló una mina: Game Over.
     *  - [Win]       → se despejó todo el campo: victoria.
     *  - [ScanArmed] → el anuncio del escáner terminó: ya se puede elegir celda.
     *  - [ScanMine]  → el escáner destapó una mina y la neutralizó (hallazgo útil).
     */
    data class PlaySound(val sound: SoundEffect) : DefuserEffect {
        companion object {
            val Reveal = PlaySound(SoundEffect.TAP)
            val Cascade = PlaySound(SoundEffect.SUCCESS)
            val Flag = PlaySound(SoundEffect.TAP)
            val Explosion = PlaySound(SoundEffect.ERROR)
            val Win = PlaySound(SoundEffect.LEVEL_UP)
            val ScanArmed = PlaySound(SoundEffect.TAP)
            val ScanMine = PlaySound(SoundEffect.SUCCESS)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Light]  → vibración ligera al revelar una celda o poner un escudo.
     *  - [Medium] → vibración media al neutralizar una mina con el escáner (un
     *    hallazgo con más peso que un revelado, pero sin el golpe de una explosión).
     *  - [Heavy]  → vibración fuerte al pisar una mina (la explosión se "siente").
     */
    data class Vibrate(val haptic: HapticFeedback) : DefuserEffect {
        companion object {
            val Light = Vibrate(HapticFeedback.LIGHT)
            val Medium = Vibrate(HapticFeedback.MEDIUM)
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
     * artificiales neón ([com.kortexgames.app.ui.components.FireworksOverlay]),
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
 * Sudoku, vía [com.kortexgames.app.domain.repository.SavedGameStateRepository]).
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
 * @property scanUsesRemaining usos del escáner que le quedaban al guardar (ver
 *   [DefuserUiState.scanUsesRemaining]): se persiste para que salir y reanudar no
 *   recargue los usos gratis. Tiene valor por defecto para que los guardados escritos
 *   antes de existir el escáner sigan deserializando (arrancan con el tope de usos).
 */
@Serializable
data class DefuserSavedState(
    val board: MineBoard,
    val minesArmed: Boolean,
    val elapsedMs: Long,
    val difficulty: MineDifficulty,
    val reviveOffered: Boolean,
    val scanUsesRemaining: Int = DefuserConfig.SCAN_MAX_USES,
)
