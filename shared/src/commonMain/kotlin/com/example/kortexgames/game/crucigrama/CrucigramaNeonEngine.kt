package com.example.kortexgames.game.crucigrama

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** Resultado del último intento del jugador. */
enum class CrucigramaNeonOutcome { CORRECT, WRONG }

/**
 * Estado de UI del Crucigrama Neón entrelazado.
 *
 * @property inputBuffer texto escrito actualmente con el teclado inferior.
 */
data class CrucigramaNeonState(
    val level: Int = 0,
    val rows: Int = 0,
    val cols: Int = 0,
    val letters: List<Char> = emptyList(),
    val cells: List<CrucigramaNeonCellState> = emptyList(),
    val slots: List<CrucigramaNeonSlotState> = emptyList(),
    val inputBuffer: String = "",
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val correctWords: Int = 0,
    val wrongAttempts: Int = 0,
    val lastOutcome: CrucigramaNeonOutcome? = null,
    val feedbackTick: Long = 0L,
)

/**
 * Motor del crucigrama entrelazado con entrada automática.
 *
 * El jugador escribe una palabra en un buffer global y el motor busca la pista
 * pendiente que coincide con ese texto. Al completar una coincidencia exacta,
 * coloca automáticamente las letras en su posición cruzada.
 */
class CrucigramaNeonEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<CrucigramaNeonState>(GameIds.CRUCIGRAMA_NEON, difficulty, scope, audio) {

    private val _state = MutableStateFlow(CrucigramaNeonState())
    override val state: StateFlow<CrucigramaNeonState> = _state.asStateFlow()

    private var currentLevel: Int = 1

    override fun onStart() {
        loadLevel(currentLevel)
    }

    /** Arranca un nivel concreto del selector. */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start()
    }

    /** Añade una letra al buffer y valida automáticamente candidatas. */
    fun tapLetter(letter: Char) {
        val current = _state.value
        if (letter !in current.letters || current.slots.all { it.solved }) return

        val pending = unsolvedSlots(current)
        val maxLen = pending.maxOfOrNull { it.answer.length } ?: return
        if (current.inputBuffer.length >= maxLen) return

        val next = current.inputBuffer + letter

        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        _state.update { it.copy(inputBuffer = next) }

        val exact = pending.filter { it.answer == next }
        if (exact.isNotEmpty()) {
            // Si hay varias exactas idénticas, resolver cualquiera es consistente.
            onCorrect(slotNumber = exact.first().number)
            return
        }

        // Solo evaluamos "mal" cuando el jugador ya terminó de escribir una
        // palabra completa posible (longitud final máxima pendiente).
        if (next.length >= maxLen) {
            onWrong()
        }
    }

    /** Borra la última letra del buffer. */
    fun backspace() {
        val current = _state.value
        if (current.inputBuffer.isEmpty()) return
        _state.update { it.copy(inputBuffer = it.inputBuffer.dropLast(1)) }
    }

    /** Limpia por completo el buffer actual. */
    fun clearBuffer() {
        if (_state.value.inputBuffer.isEmpty()) return
        _state.update { it.copy(inputBuffer = "") }
    }

    /**
     * Devuelve una pista para mostrar tras ver un anuncio.
     *
     * Se toma siempre la primera palabra pendiente para que la ayuda sea objetiva y
     * no dependa de selección manual.
     */
    fun nextHint(): String? {
        val slot = _state.value.slots.firstOrNull { !it.solved } ?: return null
        val axis = if (slot.direction == CrucigramaDirection.HORIZONTAL) "Horizontal" else "Vertical"
        return "$axis: ${slot.clue}"
    }

    private fun onCorrect(slotNumber: Int) {
        val current = _state.value
        val slot = current.slots.firstOrNull { it.number == slotNumber } ?: return
        val solvedTick = current.feedbackTick + 1
        val combo = current.combo + 1
        val gained = slot.answer.length * 150 + combo * 45 + current.level * 20

        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.SUCCESS)

        val solvedSlots = current.slots.updateWhere({ it.number == slotNumber }) {
            it.copy(solved = true, solvedAtTick = solvedTick)
        }
        val fixedIndices = solvedSlots.filter { it.solved }.flatMap { it.cellIndices }.toSet()
        val fixedCells = current.cells.mapIndexed { index, cell ->
            val entry = if (index in fixedIndices) cell.solution else cell.entry
            cell.copy(entry = entry, fixed = index in fixedIndices)
        }

        _state.update {
            it.copy(
                slots = solvedSlots,
                cells = fixedCells,
                inputBuffer = "",
                score = it.score + gained,
                combo = combo,
                bestCombo = maxOf(it.bestCombo, combo),
                correctWords = it.correctWords + 1,
                lastOutcome = CrucigramaNeonOutcome.CORRECT,
                feedbackTick = solvedTick,
            )
        }

        if (solvedSlots.all { it.solved }) finish()
    }

    private fun onWrong() {
        val feedbackTick = _state.value.feedbackTick + 1
        audio.playSound(SoundEffect.ERROR)
        audio.hapticFeedback(HapticFeedback.ERROR)
        _state.update {
            it.copy(
                inputBuffer = "",
                combo = 0,
                wrongAttempts = it.wrongAttempts + 1,
                lastOutcome = CrucigramaNeonOutcome.WRONG,
                feedbackTick = feedbackTick,
            )
        }
    }

    private fun unsolvedSlots(state: CrucigramaNeonState): List<CrucigramaNeonSlotState> =
        state.slots.filter { !it.solved }

    private fun loadLevel(level: Int) {
        val puzzle = CrucigramaNeonGenerator.generate(level, random)
        _state.value = CrucigramaNeonState(
            level = level,
            rows = puzzle.rows,
            cols = puzzle.cols,
            letters = puzzle.letters,
            cells = puzzle.cells,
            slots = puzzle.slots,
        )
    }

    override fun calculateScore(): Int = _state.value.score

    override fun currentAccuracy(): Double {
        val current = _state.value
        val attempts = current.correctWords + current.wrongAttempts
        return if (attempts == 0) 100.0 else current.correctWords.toDouble() / attempts * 100
    }

    override fun reachedMetric(): Int? = _state.value.level.takeIf { it > 0 }

    private fun List<CrucigramaNeonSlotState>.updateWhere(
        predicate: (CrucigramaNeonSlotState) -> Boolean,
        transform: (CrucigramaNeonSlotState) -> CrucigramaNeonSlotState,
    ): List<CrucigramaNeonSlotState> = map { if (predicate(it)) transform(it) else it }
}


