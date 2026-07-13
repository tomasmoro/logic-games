package com.example.kortexgames.game.neoncircuit

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase

/**
 * # Contrato MVI de "Neon Circuit Flow"
 *
 * Sigue el molde de los juegos LEVELED (Starport / Tornillos Neón): un estado de
 * tablero puro ([NeonCircuitGameState], producido por el motor) envuelto por el
 * estado de pantalla ([NeonCircuitUiState]) que añade fase, ciclo de vida y
 * game-over.
 *
 * Particularidad de este juego: la interacción es un **arrastre continuo** que
 * traza un cable, no toques discretos. Los intents modelan el ciclo completo
 * posar → arrastrar → soltar ([NeonCircuitIntent.StartPath] /
 * [NeonCircuitIntent.ExtendPath] / [NeonCircuitIntent.EndPath]). La UI solo
 * traduce px→celda (geometría de layout, suya); TODA la lógica —validar
 * adyacencia (sin diagonales), cortar cables que se cruzan, detectar victoria— la
 * decide el motor (reglas de juego, suyas). Ver el "porqué" de la detección de
 * colisiones en O(1) en `NeonCircuitModel.kt`.
 */

/**
 * Estado de la pantalla completa: antesala/selector de nivel, tablero en juego y
 * resultado, igual que [com.example.kortexgames.game.starport.StarportUiState].
 *
 * @property phase antesala con selector de niveles o partida en curso.
 * @property maxUnlocked nivel máximo superado (récord); define lo desbloqueado.
 * @property currentLevel nivel en juego (para "Siguiente nivel").
 * @property game estado puro del tablero (nodos, cables, trazo activo, victoria).
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING→FINISHED).
 * @property gameOver datos de la pantalla de resultado (récord, percentil); null
 *           mientras se juega.
 */
data class NeonCircuitUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: NeonCircuitGameState = NeonCircuitGameState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface NeonCircuitIntent : UiIntent {

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : NeonCircuitIntent

    /**
     * El jugador posó el dedo sobre el nodo del canal [color] en la celda [cell]:
     * arranca un trazo nuevo desde ese terminal. Si el canal ya tenía un cable, el
     * motor lo descarta y vuelve a empezar (regla clásica de Flow: retocar un
     * color reinicia su trazo). Se pasa [color] —la identidad de canal— como
     * "nodeId" del diseño: basta para saber qué par se está conectando, ya que un
     * trazo puede nacer de cualquiera de los dos extremos.
     */
    data class StartPath(val color: WireColor, val cell: GridPosition) : NeonCircuitIntent

    /**
     * El dedo entró en la celda [cell]. El motor decide qué hacer con ella:
     *  - si es vecina ortogonal de la punta y está libre → extiende el cable
     *    (feedback de avance);
     *  - si retrocede sobre la penúltima celda → repliega el cable (deshacer);
     *  - si está ocupada por OTRO canal → corta ese cable desde ahí y avanza
     *    (feedback fuerte);
     *  - si el movimiento es diagonal o inválido → lo ignora.
     *
     * La UI reporta la **celda cruda** bajo el dedo, nunca decide si el avance es
     * legal: esa es competencia del motor (FASE 2).
     */
    data class ExtendPath(val cell: GridPosition) : NeonCircuitIntent

    /**
     * El jugador levantó el dedo: el motor consolida el trazo activo tal cual
     * quedó (un cable a medias es válido y se conserva) y limpia
     * [NeonCircuitGameState.activeColor]. Si con este trazo quedaron todos los
     * pares conectados, marca la victoria.
     */
    data object EndPath : NeonCircuitIntent

    /** Reinicia el nivel actual: borra todos los cables. */
    data object Restart : NeonCircuitIntent

    data object Pause : NeonCircuitIntent
    data object Resume : NeonCircuitIntent

    /** Desde el resultado: rejugar el mismo nivel. */
    data object PlayAgain : NeonCircuitIntent

    /** Desde el resultado: avanzar al siguiente nivel. */
    data object NextLevel : NeonCircuitIntent

    /** Volver al selector de niveles (desde el resultado). */
    data object ChooseLevel : NeonCircuitIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial y
 * celebraciones que no deben re-emitirse en recomposición.
 *
 * El brief pide eventos semánticos (NodeConnect, PathBreak, LevelComplete, Tick,
 * Heavy). Se mapean al catálogo existente en vez de inventar tokens nuevos, igual
 * que hace Starport:
 *  - **NodeConnect** → `PlaySound(SUCCESS)` al cerrar un par.
 *  - **PathBreak** → `PlaySound(ERROR)` al cortar un cable ajeno.
 *  - **LevelComplete** → `PlaySound(LEVEL_UP)` al conectar todos los pares.
 *  - **Tick** (avanzar de celda) → `Vibrate(LIGHT)` + estática suave.
 *  - **Heavy** (romper otro cable) → `Vibrate(HEAVY)`.
 */
sealed interface NeonCircuitEffect : UiEffect {

    /** Reproduce un SFX semántico del catálogo [SoundEffect] (ver mapeo arriba). */
    data class PlaySound(val sound: SoundEffect) : NeonCircuitEffect

    /**
     * Háptica: LIGHT en cada avance de celda (el "tick" que da textura al
     * arrastre), HEAVY al cortar un cable ajeno (el "golpe" que comunica que se
     * pisó algo), SUCCESS al cerrar un par.
     */
    data class Vibrate(val feedback: HapticFeedback) : NeonCircuitEffect

    /**
     * Un par acaba de conectarse: la UI hace "palpitar" (spring) el cable completo
     * de ese canal. Es efecto y no estado porque es un destello puntual que no
     * debe repetirse al recomponer.
     */
    data class PulsePath(val color: WireColor) : NeonCircuitEffect
}
