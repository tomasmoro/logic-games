package com.kortexgames.app.game.crucigrama

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.ResumableGameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Resultado del último intento del jugador. */
@Serializable
enum class CrucigramaNeonOutcome { CORRECT, WRONG }

/**
 * Estado de UI del Crucigrama Neón entrelazado.
 *
 * @property inputBuffer texto escrito actualmente con el teclado inferior.
 */
@Serializable
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
    /** Palabras bonus del nivel (formables pero no en la rejilla). */
    val extraWords: List<String> = emptyList(),
    /** Palabras bonus ya descubiertas por el jugador. */
    val extraFound: Set<String> = emptySet(),
    /** Última palabra extra descubierta (para el feedback de la UI). */
    val lastExtra: String? = null,
    /** Contador que sube cada vez que se descubre una extra (dispara chispas + panel). */
    val extraTick: Long = 0L,
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
) : BaseGameEngine<CrucigramaNeonState>(GameIds.CRUCIGRAMA_NEON, difficulty, scope, audio),
    ResumableGameEngine<CrucigramaNeonState> {

    private val _state = MutableStateFlow(CrucigramaNeonState())
    override val state: StateFlow<CrucigramaNeonState> = _state.asStateFlow()

    private var currentLevel: Int = 1

    /** Estado a restaurar en el próximo [onStart]; lo consume [resumeFrom]. */
    private var pendingResume: CrucigramaNeonState? = null

    override fun onStart() {
        val resume = pendingResume
        if (resume != null) {
            _state.value = resume
            pendingResume = null
        } else {
            loadLevel(currentLevel)
        }
    }

    /** Arranca un nivel concreto del selector. */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start()
    }

    /**
     * Reanuda una partida guardada al salir en vez de generar el nivel de nuevo:
     * deja el estado listo para que [onStart] lo adopte tal cual y delega en
     * [start] para que el ciclo de vida (cronómetro, `finish()`, etc.) sea idéntico
     * al de una partida arrancada normalmente. El cronómetro se reinicia (mismo
     * comportamiento que cualquier [start]); no afecta el récord porque
     * `completionTimeMs` solo importa si el nivel se completa en esta sesión.
     */
    override fun resumeFrom(saved: CrucigramaNeonState) {
        pendingResume = saved
        currentLevel = saved.level
        start()
    }

    /** Añade una letra al buffer y valida automáticamente candidatas. */
    fun tapLetter(letter: Char) {
        val current = _state.value
        if (letter !in current.letters || current.slots.all { it.solved }) return

        // Tope de escritura = palabra más larga del nivel COMPLETO (no solo las
        // pendientes). Antes se usaba el máximo de las pendientes y, al resolver las
        // palabras largas, el buffer se bloqueaba antes de tiempo dando la sensación
        // de que "no deja escribir". Con el total, el jugador siempre puede teclear
        // cualquier letra y corregir con el botón de borrado.
        val maxLen = current.slots.maxOf { it.answer.length }
        if (current.inputBuffer.length >= maxLen) return

        val next = current.inputBuffer + letter

        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        _state.update { it.copy(inputBuffer = next) }

        // Validación no punitiva: solo reaccionamos ante un acierto exacto (auto-
        // colocación). Ya NO marcamos "error" por llenar el buffer; la escritura es
        // libre y el jugador decide cuándo borrar. Esto elimina el auto-limpiado que
        // interrumpía al escribir.
        val unsolved = unsolvedSlots(current)
        val exact = unsolved.filter { it.answer == next }
        if (exact.isNotEmpty()) {
            // Si hay varias exactas idénticas, resolver cualquiera es consistente.
            onCorrect(slotNumber = exact.first().number)
            return
        }

        // Palabra extra (bonus): se acepta solo si NO es prefijo de una palabra de
        // rejilla pendiente (para no consumir letras camino a completarla) y aún no se
        // ha descubierto.
        val isPrefixOfPending = unsolved.any { it.answer != next && it.answer.startsWith(next) }
        if (!isPrefixOfPending && next in current.extraWords && next !in current.extraFound) {
            onExtraFound(next)
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

    /**
     * Registra una palabra extra descubierta: bonus de puntos y feedback positivo, sin
     * tocar la rejilla ni el conteo de palabras principales (no adelanta el fin de nivel).
     */
    private fun onExtraFound(word: String) {
        val current = _state.value
        val tick = current.feedbackTick + 1
        val combo = current.combo + 1
        // Bonus menor que una palabra de rejilla (es un "extra", no el objetivo).
        val gained = word.length * 90 + current.level * 10

        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.SUCCESS)

        _state.update {
            it.copy(
                inputBuffer = "",
                extraFound = it.extraFound + word,
                lastExtra = word,
                extraTick = it.extraTick + 1,
                score = it.score + gained,
                combo = combo,
                bestCombo = maxOf(it.bestCombo, combo),
                lastOutcome = CrucigramaNeonOutcome.CORRECT,
                feedbackTick = tick,
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
            extraWords = puzzle.extraWords,
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


