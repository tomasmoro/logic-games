package com.example.kortexgames.game.starport

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase

/**
 * # Contrato MVI de "Neon Starport Escape"
 *
 * Sigue el molde de los juegos LEVELED (Water Sort / Tornillos Neón): un
 * estado de tablero puro ([StarportGameState], producido por el motor)
 * envuelto por el estado de pantalla ([StarportUiState]) que añade fase,
 * ciclo de vida y game-over.
 *
 * Particularidad de este juego: la interacción es un **arrastre continuo**
 * (no toques discretos), así que los intents modelan el ciclo completo
 * levantar → arrastrar → soltar. La UI solo traduce px→celdas (geometría de
 * layout, suya); TODA la física —clamping contra otras naves, snap a la
 * cuadrícula, detección de choque— la decide el motor (reglas de juego,
 * suyas). Ver el invariante de no-superposición en `StarportModel.kt`.
 */

/**
 * Estado de la pantalla completa: antesala/selector de nivel, hangar en juego
 * y resultado, igual que [com.example.kortexgames.game.screws.ScrewGameUiState].
 *
 * @property phase antesala con selector de niveles o partida en curso.
 * @property maxUnlocked nivel máximo superado (récord); define lo desbloqueado.
 * @property currentLevel nivel en juego (para "Siguiente nivel").
 * @property game estado puro del hangar (naves, esclusa, arrastre, movimientos).
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING→FINISHED).
 * @property gameOver datos de la pantalla de resultado (récord, percentil);
 *           null mientras se juega.
 */
data class StarportUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: StarportGameState = StarportGameState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface StarportIntent : UiIntent {

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : StarportIntent

    /**
     * El jugador posó el dedo sobre la nave [shipId]: se "levanta" (feedback
     * de resorte + háptica ligera) y arranca un [ShipDrag] con offset 0. No
     * cuenta como movimiento hasta que se suelte en una celda distinta.
     */
    data class SelectShip(val shipId: Int) : StarportIntent

    /**
     * El dedo se movió [deltaCells] celdas (fraccionales) a lo largo del eje
     * de la nave desde el último evento. Se reporta el **delta crudo** y no
     * una posición absoluta: el motor lo acumula sobre [ShipDrag.axisOffset]
     * clampeándolo al intervalo libre — si el delta empuja contra otra nave o
     * contra el mamparo, el offset se detiene ahí y se dispara el feedback de
     * choque. La UI nunca decide hasta dónde puede llegar la nave.
     *
     * La componente perpendicular del gesto se descarta ANTES de llegar aquí
     * (la UI proyecta el arrastre sobre el eje de la nave): por contrato este
     * intent solo habla la única dimensión en la que la nave puede moverse.
     */
    data class DragShip(val shipId: Int, val deltaCells: Float) : StarportIntent

    /**
     * El jugador soltó la nave: el motor redondea [ShipDrag.axisOffset] a la
     * celda válida más cercana (snap), consolida la posición y, si cambió de
     * celda, incrementa el contador de movimientos. Si la nave es la VIP y
     * quedó con vía libre hasta la esclusa, se marca el escape.
     */
    data class ReleaseShip(val shipId: Int) : StarportIntent

    /**
     * La UI terminó de animar a la VIP cruzando la esclusa: el dominio
     * consolida el nivel superado (récord, game-over, desbloqueo). Separar
     * "escapó" (dominio) de "ya salió de pantalla" (UI) evita temporizadores
     * en el ViewModel, igual que `PlateFallFinished` en Tornillos Neón.
     */
    data object ExitAnimFinished : StarportIntent

    /** Reinicia el nivel actual a su disposición inicial (movimientos a 0). */
    data object Restart : StarportIntent

    data object Pause : StarportIntent
    data object Resume : StarportIntent

    /** Desde el resultado: rejugar el mismo nivel. */
    data object PlayAgain : StarportIntent

    /** Desde el resultado: avanzar al siguiente nivel. */
    data object NextLevel : StarportIntent

    /** Volver al selector de niveles (desde el resultado). */
    data object ChooseLevel : StarportIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial y
 * celebraciones que no deben re-emitirse en recomposición.
 */
sealed interface StarportEffect : UiEffect {

    /**
     * Reproduce un SFX semántico con el catálogo existente de [SoundEffect]:
     * TAP al levantar/acoplar una nave, ERROR al chocar contra otra,
     * LEVEL_UP cuando la VIP cruza la esclusa.
     */
    data class PlaySound(val sound: SoundEffect) : StarportEffect

    /**
     * Háptica: LIGHT al levantar, MEDIUM al acoplarse al snap, HEAVY en el
     * choque contra otra nave o el mamparo (el "bump" que comunica físicamente
     * que el camino está bloqueado, aunque el dedo siga empujando).
     */
    data class Vibrate(val feedback: HapticFeedback) : StarportEffect

    /**
     * La VIP quedó libre y cruza la esclusa: la UI anima el vuelo de salida
     * (aceleración + estela) y responde con [StarportIntent.ExitAnimFinished].
     * Es efecto y no estado porque es un destello puntual que no debe
     * reaparecer al recomponer.
     */
    data object ShowExitAnim : StarportEffect
}
