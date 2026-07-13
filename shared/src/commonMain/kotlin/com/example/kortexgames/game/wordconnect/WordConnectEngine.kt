package com.example.kortexgames.game.wordconnect

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

/** Resultado del último trazo, para el feedback visual/sonoro inmediato. */
enum class WordConnectOutcome {
    /** Palabra objetivo recién encontrada. */
    CORRECT,

    /** Palabra objetivo válida pero ya encontrada antes (feedback neutro). */
    REPEAT,

    /** El trazo no forma ninguna palabra objetivo. */
    WRONG,
}

/**
 * Estado de UI de Palabras Conectadas.
 *
 * @property selection índices (en [letters]) de los nodos trazados, en orden.
 * @property currentWord palabra que se está formando (lo que se escribe "en el espacio").
 * @property lastOutcome resultado del último trazo terminado (para el flash de feedback).
 * @property feedbackTick contador monótono que dispara animaciones one-shot en la UI.
 */
data class WordConnectState(
    val level: Int = 0,
    val letters: List<WheelLetter> = emptyList(),
    val slots: List<WordSlotState> = emptyList(),
    val selection: List<Int> = emptyList(),
    val currentWord: String = "",
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val correctWords: Int = 0,
    val wrongAttempts: Int = 0,
    val lastOutcome: WordConnectOutcome? = null,
    val feedbackTick: Long = 0L,
)

/**
 * Motor de Palabras Conectadas.
 *
 * El jugador **arrastra** por el anillo encadenando nodos; el motor mantiene el trazo
 * ([selection]) y, al soltar ([endTrace]), evalúa la palabra formada contra las
 * ranuras pendientes. La entrada es por *índice de nodo* (no por carácter) para que el
 * trazo sea inequívoco aunque dos posiciones compartieran letra.
 */
class WordConnectEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<WordConnectState>(GameIds.WORD_CONNECT, difficulty, scope, audio) {

    private val _state = MutableStateFlow(WordConnectState())
    override val state: StateFlow<WordConnectState> = _state.asStateFlow()

    private var currentLevel: Int = 1

    override fun onStart() {
        loadLevel(currentLevel)
    }

    /** Arranca un nivel concreto del selector. */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start()
    }

    /** Inicia un nuevo trazo (dedo abajo): descarta cualquier selección previa. */
    fun beginTrace() {
        if (_state.value.slots.all { it.solved }) return
        _state.update { it.copy(selection = emptyList(), currentWord = "", lastOutcome = null) }
    }

    /**
     * Encadena el nodo [letterIndex] al trazo actual mientras el dedo lo recorre.
     *
     * Soporta **retroceso**: si el jugador vuelve al penúltimo nodo, se deshace el
     * último (gesto natural de Word Connect para corregir sin levantar el dedo).
     * Ignora nodos ya seleccionados para no formar bucles.
     */
    fun extendTrace(letterIndex: Int) {
        val current = _state.value
        if (current.slots.all { it.solved }) return
        if (letterIndex !in current.letters.indices) return

        val sel = current.selection
        when {
            // Retroceso: el dedo regresó al penúltimo nodo → suelta el último.
            sel.size >= 2 && letterIndex == sel[sel.size - 2] -> {
                val next = sel.dropLast(1)
                audio.hapticFeedback(HapticFeedback.LIGHT)
                _state.update { it.copy(selection = next, currentWord = wordOf(next, it.letters)) }
            }
            // Nodo nuevo → se añade al trazo.
            letterIndex !in sel -> {
                val next = sel + letterIndex
                audio.playSound(SoundEffect.TAP)
                audio.hapticFeedback(HapticFeedback.LIGHT)
                _state.update { it.copy(selection = next, currentWord = wordOf(next, it.letters)) }
            }
            // Nodo ya en el trazo (no penúltimo) → se ignora.
            else -> Unit
        }
    }

    /** Cierra el trazo (dedo arriba) y evalúa la palabra formada. */
    fun endTrace() {
        val current = _state.value
        val word = current.currentWord
        if (word.isEmpty()) return

        val matching = current.slots.firstOrNull { it.answer == word }
        when {
            matching != null && !matching.solved -> onCorrect(word)
            matching != null && matching.solved -> onRepeat()
            else -> onWrong()
        }
    }

    private fun onCorrect(word: String) {
        val current = _state.value
        val tick = current.feedbackTick + 1
        val combo = current.combo + 1
        val gained = word.length * 120 + combo * 40 + current.level * 15

        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.SUCCESS)

        val solvedSlots = current.slots.map {
            if (it.answer == word && !it.solved) it.copy(solved = true, solvedAtTick = tick) else it
        }
        _state.update {
            it.copy(
                slots = solvedSlots,
                selection = emptyList(),
                currentWord = "",
                score = it.score + gained,
                combo = combo,
                bestCombo = maxOf(it.bestCombo, combo),
                correctWords = it.correctWords + 1,
                lastOutcome = WordConnectOutcome.CORRECT,
                feedbackTick = tick,
            )
        }

        if (solvedSlots.all { it.solved }) finish()
    }

    private fun onRepeat() {
        val tick = _state.value.feedbackTick + 1
        audio.hapticFeedback(HapticFeedback.LIGHT)
        // No penaliza el combo: repetir una palabra ya hallada no es un error del jugador.
        _state.update {
            it.copy(
                selection = emptyList(),
                currentWord = "",
                lastOutcome = WordConnectOutcome.REPEAT,
                feedbackTick = tick,
            )
        }
    }

    private fun onWrong() {
        val tick = _state.value.feedbackTick + 1
        audio.playSound(SoundEffect.ERROR)
        audio.hapticFeedback(HapticFeedback.ERROR)
        _state.update {
            it.copy(
                selection = emptyList(),
                currentWord = "",
                combo = 0,
                wrongAttempts = it.wrongAttempts + 1,
                lastOutcome = WordConnectOutcome.WRONG,
                feedbackTick = tick,
            )
        }
    }

    private fun loadLevel(level: Int) {
        val puzzle = WordConnectGenerator.generate(level, random)
        _state.value = WordConnectState(
            level = level,
            letters = puzzle.letters,
            slots = puzzle.slots,
        )
    }

    private fun wordOf(selection: List<Int>, letters: List<WheelLetter>): String =
        selection.joinToString("") { letters[it].char.toString() }

    override fun calculateScore(): Int = _state.value.score

    override fun currentAccuracy(): Double {
        val current = _state.value
        val attempts = current.correctWords + current.wrongAttempts
        return if (attempts == 0) 100.0 else current.correctWords.toDouble() / attempts * 100
    }

    override fun reachedMetric(): Int? = _state.value.level.takeIf { it > 0 }
}
