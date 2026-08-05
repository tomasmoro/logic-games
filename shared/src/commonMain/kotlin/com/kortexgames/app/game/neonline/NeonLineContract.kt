package com.kortexgames.app.game.neonline

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
 * # Contrato MVI de "Línea Neón"
 *
 * Sigue el molde de los juegos LEVELED del proyecto (Conectores / Starport): un
 * estado de tablero puro ([NeonLineGameState], producido por el motor de FASE 2)
 * envuelto por el estado de pantalla ([NeonLineUiState]), que añade fase del flujo,
 * ciclo de vida de la partida y datos del resultado.
 *
 * ## Reparto de responsabilidades en el gesto
 *
 * La interacción es un **arrastre continuo** que traza la línea, no toques
 * discretos, así que los intents modelan el ciclo completo posar → arrastrar →
 * soltar ([NeonLineIntent.StartPath] / [NeonLineIntent.UpdatePath] /
 * [NeonLineIntent.ReleasePath]).
 *
 * La UI solo traduce **px → celda** (geometría de layout, suya) y reporta la celda
 * cruda bajo el dedo. TODA la validación —adyacencia sin diagonales, no pisar
 * obstáculos, no cruzarse, retroceder al desandar, detectar la victoria— la decide
 * el motor (reglas de juego, suyas). Nunca al revés: si la UI filtrara movimientos,
 * la regla quedaría duplicada en dos sitios y el motor dejaría de ser testeable
 * como fuente única de verdad.
 */

/**
 * Estado de la pantalla completa: antesala con selector de niveles, tablero en
 * juego y resultado.
 *
 * @property phase antesala con selector de niveles o partida en curso.
 * @property maxUnlocked nivel máximo superado (récord); define lo desbloqueado.
 * @property currentLevel nivel en juego (base de "Siguiente nivel" y del récord).
 * @property game estado puro del tablero (obstáculos, trazo, victoria).
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING→FINISHED).
 * @property gameOver datos de la pantalla de resultado (récord, percentil); null
 *           mientras se juega.
 */
data class NeonLineUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: NeonLineGameState = NeonLineGameState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface NeonLineIntent : UiIntent {

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : NeonLineIntent

    /**
     * El jugador posó el dedo sobre [cell]: arranca un trazo nuevo desde ahí.
     *
     * A diferencia de Conectores no hay "nodos" de los que partir: **cualquier**
     * celda libre es un comienzo válido, y elegir bien por dónde empezar es parte
     * del puzzle. Si ya había un trazo, el motor lo descarta y vuelve a empezar
     * desde esta celda (equivale a un reinicio implícito, más rápido que buscar el
     * botón). El motor ignora el intent si [cell] es un obstáculo.
     *
     * Se pasa la **celda** y no un par (x, y) suelto: [GridPosition] es el tipo
     * compartido del dominio de rejilla y evita la ambigüedad de qué eje es cuál.
     */
    data class StartPath(val cell: GridPosition) : NeonLineIntent

    /**
     * El dedo entró en [cell] durante el arrastre. El motor decide qué hacer:
     *  - vecina ortogonal libre y sin visitar → **avanza** la línea (tick háptico);
     *  - es la celda anterior a la punta → **retrocede**, borrando la punta (el
     *    "undo táctil": corregir sin levantar el dedo);
     *  - obstáculo, diagonal, salto de celda o celda ya visitada → se **ignora**
     *    (con destello de rechazo, ver [NeonLineEffect.RejectMove]).
     *
     * Que el arrastre pueda ir más rápido que un tick por celda no rompe nada: un
     * salto no adyacente simplemente no avanza, y el jugador ha de pasar por la
     * celda contigua. El motor NUNCA interpola celdas intermedias, porque eso
     * regalaría movimientos que el jugador no hizo.
     */
    data class UpdatePath(val cell: GridPosition) : NeonLineIntent

    /**
     * El jugador levantó el dedo: el motor consolida el trazo tal cual quedó (un
     * trazo a medias es válido y se conserva; se puede seguir después) y, si cubre
     * todas las celdas libres, cierra el nivel como superado.
     */
    data object ReleasePath : NeonLineIntent

    /** Botón de reinicio: borra el trazo y deja el nivel como al empezar. */
    data object RestartLevel : NeonLineIntent

    data object Pause : NeonLineIntent
    data object Resume : NeonLineIntent

    /** Desde el resultado: rejugar el mismo nivel. */
    data object PlayAgain : NeonLineIntent

    /** Desde el resultado: avanzar al siguiente nivel. */
    data object NextLevel : NeonLineIntent

    /** Volver al selector de niveles (desde el resultado). */
    data object ChooseLevel : NeonLineIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial y destellos
 * que no deben re-emitirse en recomposición ni al rotar la pantalla.
 *
 * El brief pide eventos semánticos (NodeConnected, Error, LevelComplete, Tick,
 * Success). Se mapean al catálogo existente en vez de inventar tokens nuevos, igual
 * que hacen Conectores y Starport:
 *
 *  - **NodeConnected** (avanzar una celda) → `Vibrate(LIGHT)`, el *tick* ASMR que
 *    da textura al arrastre. Sin sonido por celda a propósito: en un tablero de
 *    hasta 64 celdas, un SFX por celda satura; el sonido se reserva a los hitos.
 *  - **Error** (movimiento rechazado) → `PlaySound(ERROR)` + `RejectMove(cell)`.
 *  - **LevelComplete** → `PlaySound(LEVEL_UP)` + `Vibrate(SUCCESS)`.
 *
 * La tabla evento de dominio → efecto vivirá en un único sitio auditable del
 * ViewModel (FASE 2), igual que en `NeonCircuitViewModel.onEngineEvent`.
 */
sealed interface NeonLineEffect : UiEffect {

    /** Reproduce un SFX semántico del catálogo [SoundEffect] (ver mapeo arriba). */
    data class PlaySound(val sound: SoundEffect) : NeonLineEffect

    /**
     * Háptica: LIGHT en cada celda que avanza o retrocede (el "tick" que hace
     * agradable el arrastre y confirma el avance sin mirar), SUCCESS al completar
     * el nivel, ERROR al rechazar un movimiento.
     */
    data class Vibrate(val feedback: HapticFeedback) : NeonLineEffect

    /**
     * Un movimiento no fue legal: la UI hace un destello rojo corto en [cell] (el
     * obstáculo o la celda ya trazada que se intentó pisar).
     *
     * Es efecto y no estado porque es un destello puntual: si viviera en el State
     * volvería a dispararse en cada recomposición mientras no se limpiara.
     */
    data class RejectMove(val cell: GridPosition) : NeonLineEffect

    /**
     * El circuito se completó: la UI lanza el barrido de luz que recorre la línea
     * entera antes de mostrar el resultado. One-shot por la misma razón que
     * [RejectMove].
     */
    data object CircuitCompleted : NeonLineEffect
}
