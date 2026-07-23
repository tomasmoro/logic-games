package com.example.kortexgames.game.wordsearch

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.ResumableGameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * # Motor de "Neon Lexicon" (Sopa de Letras Neón)
 *
 * Toda la lógica de reglas y **geometría** vive aquí, pura y sin UI:
 *  - Mapear el trazo del dedo (celda inicial → celda actual) a una recta legal
 *    mediante el "snap" de [resolveLine].
 *  - Validar el trazo soltado contra las palabras objetivo.
 *  - Puntuar y decidir el fin de nivel.
 *
 * El ViewModel (capa MVI) solo traduce intents→llamadas y **eventos de dominio**
 * ([NeonLexiconEvent])→efectos sensoriales, siguiendo el mismo reparto que
 * `blockgrid` y Tornillos Neón. Mantener el mapeo evento→(sonido, háptica) fuera
 * del motor deja las reglas libres de decisiones de "textura".
 *
 * [Random] es inyectable para que los tests reproduzcan tableros deterministas.
 */
class NeonLexiconEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    private val random: Random = Random.Default,
) : BaseGameEngine<NeonLexiconGameState>(
    gameId = GameIds.NEON_LEXICON,
    difficulty = 1,
    scope = scope,
    audio = audio,
), ResumableGameEngine<NeonLexiconGameState> {

    private val _state = MutableStateFlow(NeonLexiconGameState())
    override val state: StateFlow<NeonLexiconGameState> = _state.asStateFlow()

    private val _events = Channel<NeonLexiconEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<NeonLexiconEvent> = _events.receiveAsFlow()

    private var currentLevel: Int = 1

    /** Contador monótono para escalonar animaciones (foundAtTick de cada palabra). */
    private var tick: Long = 0L

    /** Trazos soltados y aciertos, para la precisión del resultado. */
    private var attempts: Int = 0
    private var correct: Int = 0

    /** Estado a restaurar en el próximo [onStart]; lo consume [resumeFrom]. */
    private var pendingResume: NeonLexiconGameState? = null

    override fun onStart() {
        val resume = pendingResume
        if (resume != null) {
            _state.value = resume
            pendingResume = null
        } else {
            loadLevel(currentLevel)
        }
    }

    /** Arranca un nivel concreto del selector de la antesala. */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start()
    }

    /**
     * Reanuda una partida guardada al salir en vez de regenerar el tablero: adopta el
     * [saved] tal cual y delega en [start] para que el ciclo de vida sea idéntico al
     * de una partida normal. Los contadores de precisión y el `tick` de animación se
     * reinician (empiezan a contar desde la reanudación); es una simplificación
     * asumida, no afecta el récord (nivel alcanzado) ni el tablero.
     */
    override fun resumeFrom(saved: NeonLexiconGameState) {
        pendingResume = saved
        currentLevel = saved.level.coerceAtLeast(1)
        start()
    }

    /**
     * El jugador tocó la celda ([row], [col]): abre un trazo anclado ahí. El
     * primer contacto también emite [NeonLexiconEvent.LetterCrossed] para que la
     * "cremallera" suene desde la primera letra.
     */
    fun beginSelection(row: Int, col: Int) {
        val cell = Coordinate(row, col)
        if (!_state.value.grid.isInside(cell)) return
        _state.update { it.copy(selection = Selection(cell, cell, listOf(cell), isStraightLine = true)) }
        _events.trySend(NeonLexiconEvent.LetterCrossed)
    }

    /**
     * El dedo se movió a la celda ([row], [col]). Se recalcula el "snap" con
     * [resolveLine] y, si el extremo del trazo cruzó a una celda NUEVA, se emite
     * el tick de cremallera (sonido + háptica ligera) — el detalle que hace la
     * selección ultra satisfactoria.
     */
    fun moveSelection(row: Int, col: Int) {
        val current = _state.value
        val selection = current.selection ?: return
        val target = Coordinate(row, col)
        if (!current.grid.isInside(target)) return

        val resolved = resolveLine(selection.start, target, current.grid)
        // Solo hay "tick" cuando el trazo gana/pierde una letra: mover el dedo
        // dentro de la misma celda no debe repetir el sonido (sería ruido).
        if (resolved.current != selection.current) {
            _events.trySend(NeonLexiconEvent.LetterCrossed)
        }
        _state.update { it.copy(selection = resolved) }
    }

    /**
     * El jugador soltó el dedo: valida el trazo actual. Acierto ⇒ palabra marcada
     * y evento [NeonLexiconEvent.WordFound]; fallo ⇒ [NeonLexiconEvent.WrongSelection]
     * y la selección se limpia (la UI la desvanece). Al encontrar la última
     * palabra se cierra la partida.
     */
    fun commitSelection() {
        val current = _state.value
        val selection = current.selection ?: return
        attempts++

        // Se compara por CAMINO de celdas (no por texto): así una selección solo
        // acierta si recorre exactamente las celdas de una palabra, en cualquiera
        // de los dos sentidos. Comparar por string podría dar falsos positivos con
        // letras de relleno que casualmente formen la palabra en otro sitio.
        val match = current.words.firstOrNull { entry ->
            !entry.found && matchesPath(selection.cells, entry.word.cells)
        }

        if (match != null) {
            correct++
            onWordFound(match)
        } else {
            _events.trySend(NeonLexiconEvent.WrongSelection)
            _state.update { it.copy(selection = null) }
        }
    }

    /** El gesto se interrumpió: limpia la selección sin validar ni penalizar. */
    fun cancelSelection() {
        if (_state.value.selection == null) return
        _state.update { it.copy(selection = null) }
    }

    private fun onWordFound(entry: WordEntry) {
        tick += 1
        val gained = entry.text.length * 100 + currentLevel * 25

        _state.update { st ->
            st.copy(
                words = st.words.map {
                    if (it.word == entry.word) it.copy(found = true, foundAtTick = tick) else it
                },
                selection = null,
                score = st.score + gained,
            )
        }
        _events.trySend(NeonLexiconEvent.WordFound)

        // Fin de nivel: todas las palabras encontradas. El sonido de victoria lo
        // emite el ViewModel en onFinished (tras persistir), como en blockgrid.
        if (_state.value.words.all { it.found }) {
            _events.trySend(NeonLexiconEvent.AllWordsFound)
            finish()
        }
    }

    private fun loadLevel(level: Int) {
        tick = 0L
        attempts = 0
        correct = 0
        val puzzle = NeonLexiconGenerator.generate(level, random)
        _state.value = NeonLexiconGameState(
            level = level,
            grid = puzzle.grid,
            words = puzzle.words.map { WordEntry(it) },
        )
    }

    override fun calculateScore(): Int = _state.value.score

    /**
     * Precisión = trazos acertados / trazos soltados. Explorar arrastrando sin
     * soltar no penaliza (solo cuenta el `commit`), coherente con `blockgrid`.
     */
    override fun currentAccuracy(): Double =
        if (attempts == 0) 100.0 else correct * 100.0 / attempts

    /** Récord de la corrida = nivel alcanzado (LEVELED en [GameProgressions]). */
    override fun reachedMetric(): Int = _state.value.level

    /** Acierto si el camino recorre exactamente las celdas de la palabra (en cualquier sentido). */
    private fun matchesPath(selection: List<Coordinate>, word: List<Coordinate>): Boolean =
        selection.size == word.size && (selection == word || selection == word.asReversed())
}

/**
 * Estado puro del juego en un instante: lo que el motor emite y la UI dibuja.
 * Inmutable: cada jugada produce un snapshot nuevo.
 *
 * @property level nivel en curso (0 = sin cargar).
 * @property grid cuadrícula de letras.
 * @property words palabras objetivo con su estado encontrado/pendiente.
 * @property selection trazo en curso, o null en reposo.
 * @property score puntos acumulados (base para el [com.example.kortexgames.domain.model.GameResult]).
 */
@Serializable
data class NeonLexiconGameState(
    val level: Int = 0,
    val grid: WordSearchGrid = WordSearchGrid(),
    val words: List<WordEntry> = emptyList(),
    val selection: Selection? = null,
    val score: Int = 0,
)

/**
 * Eventos de dominio del motor. El ViewModel los traduce a Effects sensoriales;
 * mantener el mapeo fuera del motor deja las reglas libres de decisiones de
 * "textura" (ver [NeonLexiconEngine]).
 */
sealed interface NeonLexiconEvent {
    /** El trazo cruzó a una letra nueva: "tick" de cremallera (tap + háptica ligera). */
    data object LetterCrossed : NeonLexiconEvent

    /** El jugador acertó una palabra. */
    data object WordFound : NeonLexiconEvent

    /** El trazo soltado no coincidió con ninguna palabra pendiente. */
    data object WrongSelection : NeonLexiconEvent

    /** Se encontraron todas las palabras: nivel superado. */
    data object AllWordsFound : NeonLexiconEvent
}

/**
 * # "Snap" geométrico: el motor matemático de la selección
 *
 * Dada la celda [start] (donde empezó el trazo) y la celda [current] bajo el dedo
 * ahora mismo, devuelve la [Selection] **ajustada a la recta legal más cercana**
 * (una de las 8 [LineDirection]). Es una función pura (sin estado) para poder
 * testearla de forma aislada.
 *
 * ## Cómo evita selecciones inválidas o "curvas"
 *
 * Una sopa de letras solo admite trazos rectos sobre 8 ejes; el dedo humano nunca
 * dibuja recto. El snap resuelve eso en tres pasos, y por construcción el
 * resultado **siempre** es una recta de celdas contiguas sobre un único eje:
 *
 * 1. **Elegir el eje.** Se puntúa cada dirección por su *proyección normalizada*
 *    del gesto: `dot(d, u) / |u|`, donde `d = current - start` y `u` es el vector
 *    de la dirección. Dividir entre `|u|` es la clave: pone en pie de igualdad a
 *    las diagonales (`|u| = √2`) frente a las ortogonales (`|u| = 1`). Sin esa
 *    normalización, una diagonal ganaría siempre por tener mayor producto escalar
 *    y el trazo "saltaría" a 45° incluso con gestos casi horizontales.
 *
 * 2. **Elegir la longitud.** Se proyecta el gesto sobre el eje elegido en "pasos"
 *    de celda con `round(dot / |u|²)`:
 *      - ortogonal (`|u|² = 1`): pasos = desplazamiento en ese eje.
 *      - diagonal (`|u|² = 2`): pasos = `(Δfila + Δcol) / 2`, que en una diagonal
 *        perfecta (`Δfila = Δcol`) es exactamente `Δfila`.
 *    Redondear y forzar `≥ 1` elimina longitudes cero y cualquier trazo torcido:
 *    no existe forma de que el resultado sea una curva o un salto de caballo.
 *
 * 3. **Recortar a la rejilla.** Se materializan las celdas y se cortan en el
 *    primer borde: si el trazo se sale, la selección se queda en la última celda
 *    válida y nunca referencia celdas inexistentes.
 */
internal fun resolveLine(start: Coordinate, current: Coordinate, grid: WordSearchGrid): Selection {
    val dRow = current.row - start.row
    val dCol = current.col - start.col

    // Sin desplazamiento: selección de una sola letra (recta trivial).
    if (dRow == 0 && dCol == 0) {
        return Selection(start = start, current = start, cells = listOf(start), isStraightLine = true)
    }

    // 1) Eje: dirección con mayor proyección normalizada del gesto (ver KDoc).
    val direction = LineDirection.entries.maxBy { d ->
        val dot = dRow * d.rowStep + dCol * d.colStep
        dot / sqrt((d.rowStep * d.rowStep + d.colStep * d.colStep).toDouble())
    }

    // 2) Longitud: proyección del gesto sobre el eje, en pasos de celda.
    val normSq = direction.rowStep * direction.rowStep + direction.colStep * direction.colStep
    val dot = dRow * direction.rowStep + dCol * direction.colStep
    val steps = (dot.toDouble() / normSq).roundToInt().coerceAtLeast(1)

    // 3) Celdas del trazo, recortadas al primer borde de la rejilla.
    val cells = (0..steps)
        .map { i -> Coordinate(start.row + direction.rowStep * i, start.col + direction.colStep * i) }
        .takeWhile { grid.isInside(it) }

    return Selection(
        start = start,
        current = cells.last(),
        cells = cells,
        isStraightLine = true,
    )
}
