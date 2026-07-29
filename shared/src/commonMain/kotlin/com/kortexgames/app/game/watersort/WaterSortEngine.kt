package com.kortexgames.app.game.watersort

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Evento efímero de vertido para que la pantalla anime el "chorro" del color
 * correcto entre dos tubos. Vive en el estado solo hasta la siguiente acción; su
 * [id] incremental permite a la UI relanzar la animación aunque se repita un
 * vertido idéntico (from/to iguales).
 *
 * @property color índice de color vertido (la UI lo mapea a su tono neón).
 */
data class PourEvent(
    val id: Long,
    val from: Int,
    val to: Int,
    val color: Int,
    val count: Int,
)

/**
 * Máximo de tubos extra que el jugador puede añadir por partida viendo un anuncio
 * recompensado. Se limita a **uno**: un solo tubo vacío ya da un margen de maniobra
 * decisivo cuando el jugador se atasca; permitir más trivializaría el nivel. Es la
 * ayuda monetizada del juego (§1).
 */
const val MAX_EXTRA_TUBES: Int = 1

/**
 * Estado de UI de "Ordena las Pociones".
 *
 * @property tubes tablero actual (fuente de verdad para pintar).
 * @property round ronda actual (1-based).
 * @property totalRounds número total de rondas de la partida.
 * @property selected índice del tubo "levantado" (origen elegido), o null.
 * @property moves nº de vertidos realizados (para puntuar la eficiencia).
 * @property solved true cuando todos los tubos están resueltos (victoria).
 * @property canUndo hay historial para deshacer el último vertido.
 * @property lastPour último vertido correcto, para animar el chorro (o null).
 * @property extraTubesUsed tubos extra ya añadidos (vía anuncio) en esta partida.
 * @property canAddTube aún queda cupo de tubos extra ([MAX_EXTRA_TUBES]) y la
 *   partida sigue en curso → la UI puede ofrecer el botón "Tubo extra".
 */
data class WaterSortState(
    val tubes: List<Tube> = emptyList(),
    val capacity: Int = TUBE_CAPACITY,
    val round: Int = 1,
    val totalRounds: Int = 3,
    val selected: Int? = null,
    val moves: Int = 0,
    val solved: Boolean = false,
    val canUndo: Boolean = false,
    val lastPour: PourEvent? = null,
    val extraTubesUsed: Int = 0,
    val canAddTube: Boolean = true,
)

/**
 * Motor de "Ordena las Pociones" (Water Sort), categoría Pensamiento Lógico.
 *
 * Juego **por turnos** (sin bucle de tiempo): el jugador selecciona un tubo
 * origen y luego un destino; si el vertido es válido ([WaterSortRules.canPour])
 * se aplica el bloque superior del color coincidente. Gana cuando todos los tubos
 * quedan resueltos → llama a [finish].
 *
 * Feedback inmediato (CLAUDE.md §9.4), pedido explícitamente por el usuario:
 *  - **vertido correcto** → [SoundEffect.SUCCESS] + háptica media,
 *  - **intento con color que no corresponde** → [SoundEffect.ERROR] + háptica de error,
 *  - **tubo completado** (queda lleno de un color) → refuerzo con háptica fuerte,
 *  - **seleccionar/levantar un tubo** → [SoundEffect.TAP] + háptica ligera.
 *  El sonido de **fin de partida** lo dispara el ViewModel al persistir (LEVEL_UP).
 *
 * Historial de tableros para **deshacer** ([undo]) y snapshot inicial para
 * **reiniciar** ([restart]) el mismo nivel, extras solicitados por el usuario.
 *
 * @param random fuente aleatoria (sembrable en tests para niveles deterministas).
 */
class WaterSortEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<WaterSortState>(GameIds.WATER_SORT, difficulty, scope, audio) {

    private val _state = MutableStateFlow(WaterSortState())
    override val state: StateFlow<WaterSortState> = _state.asStateFlow()

    /**
     * Nivel en juego (1-based). Lo fija [startAtLevel] al elegir un nivel en el
     * selector; su configuración sale de la curva paramétrica [configForLevel].
     * Es la base del récord (nivel máximo) y del punto de reanudación.
     */
    private var currentLevel = 1

    // Tablero inicial (para reiniciar) e historial de vertidos (para deshacer).
    private var initialTubes: List<Tube> = emptyList()
    private val history = ArrayDeque<List<Tube>>()
    private var currentRoundMinMoves = 0
    private var pourSeq = 0L

    /**
     * Curva de dificultad: nivel N → configuración. El **nivel 1 conserva la config
     * base** del juego (5 colores, capacidad 4, 2 tubos libres); a mayor nivel suben
     * los colores y la capacidad. Se acota (colores ≤ 7, capacidad ≤ 5) para que el
     * generador —con solver DFS— siga produciendo niveles resolubles en tiempo razonable.
     */
    private fun configForLevel(level: Int): LevelConfig {
        val l = level.coerceAtLeast(1)
        val colorCount = (5 + (l - 1) / 2).coerceIn(5, 7)
        val capacity = (4 + (l - 1) / 6).coerceIn(4, 5)
        return LevelConfig(colorCount = colorCount, emptyTubes = 2, capacity = capacity)
    }

    /**
     * Arranca el nivel [level] elegido en el selector: fija [currentLevel], regenera
     * su tablero paramétrico y empieza la partida. La usa el ViewModel al pulsar un
     * nivel desbloqueado o "Siguiente nivel".
     */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        startCurrentLevel()
    }

    private fun startCurrentLevel() {
        val level = WaterSortGenerator.generate(configForLevel(currentLevel), random)
        initialTubes = level.tubes
        currentRoundMinMoves = level.minMoves
        history.clear()
        _state.value = WaterSortState(
            tubes = level.tubes,
            capacity = level.capacity,
            round = currentLevel,
            totalRounds = currentLevel, // el HUD lo lee como "Nivel N"
        )
    }

    /**
     * Único gesto de entrada: tocar el tubo [index].
     *  - Sin selección previa → selecciona ese tubo como origen (si no está vacío).
     *  - Tocar el mismo tubo → lo deselecciona.
     *  - Con origen ya elegido → intenta verter origen→[index].
     *
     * Ignora toques mientras la partida no está en curso.
     */
    fun onTubeTap(index: Int) {
        val s = _state.value
        if (s.solved) return

        val selected = s.selected
        when {
            selected == null -> selectSource(index)
            selected == index -> _state.value = s.copy(selected = null) // deseleccionar
            else -> attemptPour(from = selected, to = index)
        }
    }

    private fun selectSource(index: Int) {
        val s = _state.value
        if (s.tubes[index].isEmpty) return // no se levanta un tubo vacío
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        _state.value = s.copy(selected = index, lastPour = null)
    }

    private fun attemptPour(from: Int, to: Int) {
        val s = _state.value
        if (!WaterSortRules.canPour(s.tubes, from, to, s.capacity)) {
            // Color que no corresponde (o destino lleno): feedback de error y se
            // mantiene el origen seleccionado para reintentar con otro destino.
            audio.playSound(SoundEffect.ERROR)
            audio.hapticFeedback(HapticFeedback.ERROR)
            _state.value = s.copy(selected = null)
            return
        }

        history.addLast(s.tubes) // snapshot ANTES de verter (para deshacer)
        val result = WaterSortRules.pour(s.tubes, from, to, s.capacity)

        audio.playSound(SoundEffect.SUCCESS)
        // Un tubo recién completado merece un refuerzo háptico más marcado.
        audio.hapticFeedback(if (result.dstNowComplete) HapticFeedback.HEAVY else HapticFeedback.MEDIUM)

        val solved = WaterSortRules.isSolved(result.tubes, s.capacity)
        val roundMoves = s.moves + 1
        _state.value = s.copy(
            tubes = result.tubes,
            selected = null,
            moves = roundMoves,
            solved = solved,
            canUndo = true,
            lastPour = PourEvent(pourSeq++, from, to, result.color, result.count),
        )

        if (solved) {
            // Nivel resuelto: refuerzo explícito y cierre de la partida. El avance al
            // siguiente nivel lo decide el jugador desde el game-over (o el selector).
            audio.playSound(SoundEffect.SUCCESS)
            audio.hapticFeedback(HapticFeedback.HEAVY)
            finish()
        }
    }

    /** Deshace el último vertido restaurando el tablero previo. */
    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        val s = _state.value
        _state.value = s.copy(
            tubes = previous,
            selected = null,
            moves = (s.moves - 1).coerceAtLeast(0),
            solved = false,
            canUndo = history.isNotEmpty(),
            lastPour = null,
        )
    }

    /**
     * Añade un **tubo de ensayo vacío** al tablero como ayuda (recompensa por ver un
     * anuncio). Da margen de maniobra sin regenerar el nivel. La concede el ViewModel
     * SOLO tras completarse el anuncio recompensado; aquí se aplica la regla de negocio:
     *  - no hace nada si la partida ya está resuelta o se agotó el cupo ([MAX_EXTRA_TUBES]);
     *  - el tubo se añade también a [initialTubes] y a **cada snapshot del historial**
     *    para que el conteo de tubos sea consistente: así ni **deshacer** ni **reiniciar**
     *    le quitan al jugador la ayuda que ya "pagó" viendo el anuncio.
     *
     * @return true si se añadió el tubo; false si no correspondía (cupo/estado).
     */
    fun addExtraTube(): Boolean {
        val s = _state.value
        if (s.solved || s.extraTubesUsed >= MAX_EXTRA_TUBES) return false

        // Crece el tablero en todas las "líneas de tiempo" (inicial + historial) para
        // que undo/restart mantengan el tubo extra y no rompan índices de tubo.
        initialTubes = initialTubes + Tube()
        for (i in history.indices) history[i] = history[i] + Tube()

        // Feedback inmediato de aparición (§9.4): toque ligero, no intrusivo.
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)

        val used = s.extraTubesUsed + 1
        _state.value = s.copy(
            tubes = s.tubes + Tube(),
            extraTubesUsed = used,
            canAddTube = used < MAX_EXTRA_TUBES,
        )
        return true
    }

    /**
     * Reinicia el MISMO nivel a su estado inicial (no genera uno nuevo). Conserva los
     * tubos extra ya obtenidos por anuncio ([initialTubes] los incluye) y su contador,
     * para no devolverle al jugador un tablero más difícil del que ya "pagó".
     */
    fun restart() {
        history.clear()
        val used = _state.value.extraTubesUsed
        _state.value = WaterSortState(
            tubes = initialTubes,
            capacity = configForLevel(currentLevel).capacity,
            round = currentLevel,
            totalRounds = currentLevel,
            extraTubesUsed = used,
            canAddTube = used < MAX_EXTRA_TUBES,
        )
    }

    /**
     * Puntaje: base proporcional al nivel menos una penalización por cada vertido de
     * más respecto a la solución de referencia ([minMoves]). Premia resolver niveles
     * altos con pocos movimientos; nunca baja de 0.
     */
    override fun calculateScore(): Int {
        val base = currentLevel * 1_000
        val extraMoves = (_state.value.moves - currentRoundMinMoves).coerceAtLeast(0)
        return (base - extraMoves * PENALTY_PER_EXTRA_MOVE).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: movimientos de referencia / movimientos reales. */
    override fun currentAccuracy(): Double {
        val moves = _state.value.moves
        if (moves == 0 || currentRoundMinMoves == 0) return 100.0
        return (currentRoundMinMoves.toDouble() / moves * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel completado (solo se llega aquí al resolver el nivel). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        const val PENALTY_PER_EXTRA_MOVE = 40
    }
}
