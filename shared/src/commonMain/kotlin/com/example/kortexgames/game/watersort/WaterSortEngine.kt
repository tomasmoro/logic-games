package com.example.kortexgames.game.watersort

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * Secuencia fija pedida para el juego:
     *  1) 6 tubos, capacidad 4, 1 libre  => 5 colores
     *  2) 8 tubos, capacidad 4, 2 libres => 6 colores
     *  3) 8 tubos, capacidad 5, 2 libres => 6 colores
     */
    private val roundConfigs = listOf(
        LevelConfig(colorCount = 5, emptyTubes = 2, capacity = 4),
    )

    // Tablero inicial (para reiniciar) e historial de vertidos (para deshacer).
    private var initialTubes: List<Tube> = emptyList()
    private val history = ArrayDeque<List<Tube>>()
    private var currentRoundMinMoves = 0
    private var pourSeq = 0L
    private var currentRoundIndex = 0
    private var totalMoves = 0
    private var totalReferenceMoves = 0
    /** Rondas resueltas = nivel alcanzado (base del récord y del lastLevel). */
    private var solvedRounds = 0
    private var pendingRoundJob: Job? = null

    override fun onStart() {
        pendingRoundJob?.cancel()
        currentRoundIndex = 0
        totalMoves = 0
        totalReferenceMoves = 0
        solvedRounds = 0
        startRound(currentRoundIndex)
    }

    private fun startRound(index: Int) {
        val level = WaterSortGenerator.generate(roundConfigs[index], random)
        initialTubes = level.tubes
        currentRoundMinMoves = level.minMoves
        history.clear()
        _state.value = WaterSortState(
            tubes = level.tubes,
            capacity = level.capacity,
            round = index + 1,
            totalRounds = roundConfigs.size,
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
            // Cierre de ronda: feedback explícito antes de pasar de nivel o terminar.
            audio.playSound(SoundEffect.SUCCESS)
            audio.hapticFeedback(HapticFeedback.HEAVY)

            totalMoves += roundMoves
            totalReferenceMoves += currentRoundMinMoves
            solvedRounds = currentRoundIndex + 1 // 1-based: nivel alcanzado

            if (currentRoundIndex < roundConfigs.lastIndex) {
                currentRoundIndex++
                pendingRoundJob?.cancel()
                pendingRoundJob = scope.launch {
                    delay(ROUND_TRANSITION_DELAY_MS)
                    startRound(currentRoundIndex)
                }
            } else {
                finish()
            }
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

    /** Reinicia el MISMO nivel a su estado inicial (no genera uno nuevo). */
    fun restart() {
        pendingRoundJob?.cancel()
        history.clear()
        _state.value = WaterSortState(
            tubes = initialTubes,
            capacity = roundConfigs[currentRoundIndex].capacity,
            round = currentRoundIndex + 1,
            totalRounds = roundConfigs.size,
        )
    }

    /**
     * Puntaje: base por dificultad menos una penalización por cada vertido de más
     * respecto a la solución de referencia ([minMoves]). Premia resolver con pocos
     * movimientos; nunca baja de 0.
     */
    override fun calculateScore(): Int {
        val base = difficulty * 1_000 * roundConfigs.size
        val extraMoves = (totalMoves - totalReferenceMoves).coerceAtLeast(0)
        return (base - extraMoves * PENALTY_PER_EXTRA_MOVE).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: movimientos de referencia / movimientos reales. */
    override fun currentAccuracy(): Double {
        val moves = totalMoves + _state.value.moves
        val reference = totalReferenceMoves + currentRoundMinMoves
        if (moves == 0 || reference == 0) return 100.0
        return (reference.toDouble() / moves * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel alcanzado (rondas resueltas); null si no resolvió ninguna. */
    override fun reachedMetric(): Int? = solvedRounds.takeIf { it > 0 }

    private companion object {
        const val PENALTY_PER_EXTRA_MOVE = 40
        const val ROUND_TRANSITION_DELAY_MS = 1_000L
    }
}
