package com.example.kortexgames.game.wordsearch

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import kotlinx.serialization.Serializable

/**
 * # Contrato MVI de "Neon Lexicon" (Sopa de Letras Neón)
 *
 * Juego **LEVELED** (con antesala de selección de nivel, como el Crucigrama y
 * Water Sort): la pantalla alterna entre `GameIntroScreen` y el tablero según
 * [NeonLexiconUiState.phase], sin rutas de navegación nuevas.
 *
 * El ciclo es el estándar del proyecto: UI → [NeonLexiconIntent] → reduce → State.
 * El feedback sensorial (sonido/háptica) NO va en el State: son eventos one-shot
 * modelados como [NeonLexiconEffect] para no re-emitirse en recomposición.
 */

/**
 * Selección en curso: el trazo que el jugador está dibujando con el dedo.
 *
 * Vive en el State porque de él dependen varios elementos del render a la vez (el
 * "láser" del Canvas y el resaltado de las letras bajo el trazo). Solo guarda las
 * dos celdas ancla ([start] y [current]); las [cells] intermedias y si el trazo
 * es una recta legal ([isStraightLine]) las **calcula el ViewModel en la Fase 2**
 * (el "snap" geométrico), no la UI, que se limita a pintar lo que el State diga.
 *
 * @property start celda donde empezó el arrastre (primera letra tocada).
 * @property current celda bajo el dedo ahora mismo, ya "ajustada" al eje válido.
 * @property cells celdas del trazo recto de [start] a [current]; vacía hasta que
 *           el ViewModel resuelve el snap.
 * @property isStraightLine true si [start]→[current] forma una de las 8 rectas
 *           legales ([LineDirection]); con false la UI puede pintar el láser en
 *           tono neutro para indicar "todavía no es una selección válida".
 */
@Serializable
data class Selection(
    val start: Coordinate,
    val current: Coordinate,
    val cells: List<Coordinate> = emptyList(),
    val isStraightLine: Boolean = false,
)

/**
 * Estado renderizable completo de la pantalla de Neon Lexicon.
 *
 * @property phase antesala (selección de nivel) o juego; ver [LeveledGamePhase].
 * @property maxUnlocked nivel máximo desbloqueado (para pintar el carril de niveles).
 * @property currentLevel nivel en juego (1-based).
 * @property grid cuadrícula de letras; fuente de verdad de qué se ve en cada celda.
 * @property words palabras objetivo con su estado encontrado/pendiente (panel lateral).
 * @property selection trazo en curso, o null en reposo.
 * @property status ciclo de vida estándar de partida (IDLE→RUNNING→FINISHED).
 * @property gameOver datos de la pantalla de resultado (récord, percentil); null
 *           mientras se juega.
 * @property savedLevel nivel de la partida guardada al salir, o null si no hay
 *           ninguna pendiente. La antesala lo ofrece como "Continuar" (ver
 *           [com.example.kortexgames.ui.components.ResumeState]).
 */
data class NeonLexiconUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val grid: WordSearchGrid = WordSearchGrid(),
    val words: List<WordEntry> = emptyList(),
    val selection: Selection? = null,
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
    val savedLevel: Int? = null,
) : UiState {

    /**
     * Celdas de todas las palabras ya encontradas. Derivado (no se guarda) para
     * que nunca quede desincronizado con [words]: la UI lo usa para mantener esas
     * letras "encendidas" en magenta aunque no haya selección activa.
     */
    val solvedCells: Set<Coordinate>
        get() = words.filter { it.found }.flatMap { it.word.cells }.toSet()

    /** true cuando no queda ninguna palabra pendiente ⇒ nivel superado. */
    val allWordsFound: Boolean
        get() = words.isNotEmpty() && words.all { it.found }
}

/**
 * Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md).
 *
 * El trío de arrastre usa **coordenadas de celda** (fila/columna), nunca píxeles:
 * la UI traduce el punto del dedo a celda (es geometría de layout, suya) y el
 * ViewModel razona en celdas (es lógica de juego, suya). Se nombran `row`/`col`
 * por coherencia con el resto de juegos de arrastre del repo (ver `blockgrid`).
 */
sealed interface NeonLexiconIntent : UiIntent {

    /** Arranca la partida del nivel actual (o reinicia desde el resultado). */
    data object Start : NeonLexiconIntent

    /** Desde la antesala: jugar un nivel concreto del carril. */
    data class PlayLevel(val level: Int) : NeonLexiconIntent

    /** Desde la antesala: retomar la partida guardada al salir (ver [NeonLexiconUiState.savedLevel]). */
    data object ResumeSaved : NeonLexiconIntent

    /**
     * El jugador tocó la celda ([row], [col]): comienza un trazo con ancla ahí.
     * Equivale al `StartDrag(x, y)` del enunciado, con x=col e y=row.
     */
    data class StartDrag(val row: Int, val col: Int) : NeonLexiconIntent

    /**
     * El dedo se movió y ahora está sobre la celda ([row], [col]). El ViewModel
     * hará el "snap" a la recta legal más cercana y, si el trazo cruzó a una
     * celda nueva, disparará el feedback de "cremallera" (tick + háptica ligera).
     * Equivale a `UpdateDrag(x, y)`.
     */
    data class UpdateDrag(val row: Int, val col: Int) : NeonLexiconIntent

    /**
     * El jugador soltó el dedo: se valida el trazo actual contra las palabras
     * objetivo. Acierto ⇒ palabra marcada; fallo ⇒ la selección se desvanece.
     * Equivale a `EndDrag`.
     */
    data object EndDrag : NeonLexiconIntent

    /** El arrastre se canceló (gesto interrumpido): limpia la selección sin validar. */
    data object CancelDrag : NeonLexiconIntent

    data object Pause : NeonLexiconIntent
    data object Resume : NeonLexiconIntent

    /** Desde el resultado: repetir el mismo nivel. */
    data object PlayAgain : NeonLexiconIntent

    /** Desde el resultado: pasar al siguiente nivel. */
    data object NextLevel : NeonLexiconIntent

    /** Volver a la antesala de selección de nivel. */
    data object ChooseLevel : NeonLexiconIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): el feedback sensorial "ASMR"
 * que da la sensación de cremallera y de recompensa. Se modela reutilizando los
 * enums existentes ([SoundEffect]/[HapticFeedback]) en vez de inventar tipos,
 * igual que el contrato de `blockgrid`.
 *
 * Mapa semántico de los eventos que pide el enunciado:
 *  - *Tick* (el dedo cruza una letra nueva) → `PlaySound(SoundEffect.TAP)` +
 *    `Vibrate(HapticFeedback.LIGHT)`.
 *  - *WordFound* (palabra acertada) → `PlaySound(SoundEffect.SUCCESS)` +
 *    `Vibrate(HapticFeedback.SUCCESS)`.
 *  - *AllWordsFound* (nivel superado) → `PlaySound(SoundEffect.LEVEL_UP)`.
 */
sealed interface NeonLexiconEffect : UiEffect {

    /** Reproduce un SFX semántico (ver mapa en el KDoc de la interfaz). */
    data class PlaySound(val sound: SoundEffect) : NeonLexiconEffect

    /** Dispara háptica de la intensidad indicada. */
    data class Vibrate(val feedback: HapticFeedback) : NeonLexiconEffect
}
