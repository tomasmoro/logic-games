package com.example.kortexgames.game.memory

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Fase interna de una ronda de Memoria de Secuencias. */
enum class MemoryPhase {
    IDLE,       // aún no empieza
    SHOWING,    // el juego reproduce la secuencia (input bloqueado)
    INPUT,      // turno del jugador de repetirla
    ROUND_OK,   // acertó la ronda (breve celebración antes de la siguiente)
    GAME_OVER,  // falló → partida terminada
}

/**
 * Estado de UI de Memoria de Secuencias. Inmutable; la UI lo observa entero.
 *
 * @property litTile índice del tile iluminado durante SHOWING (null si ninguno).
 * @property inputProgress toques correctos acumulados en la ronda actual.
 */
data class SequenceMemoryState(
    val phase: MemoryPhase = MemoryPhase.IDLE,
    val gridSize: Int = GRID_SIZE,
    val level: Int = 0,
    val litTile: Int? = null,
    val inputProgress: Int = 0,
    val score: Int = 0,
) {
    companion object {
        /** Rejilla 3x3 = 9 tiles. */
        const val GRID_SIZE = 9
    }
}

/**
 * Motor de "Memoria de Secuencias": el juego muestra una secuencia creciente de
 * tiles iluminados y el jugador debe repetirla. Cada ronda añade un paso. Falla
 * un toque → fin de la partida.
 *
 * Decisiones:
 *  - La reproducción corre en una corrutina cancelable ([showJob]); al pausar se
 *    cancela y al reanudar se vuelve a mostrar la secuencia desde el principio
 *    (no tendría sentido reanudar a media reproducción).
 *  - El puntaje premia el nivel alcanzado; la precisión mide toques correctos
 *    sobre el total (penaliza dudar).
 *
 * @param showMs milisegundos que cada tile permanece iluminado (baja con la
 *        dificultad para exigir más). @param gapMs pausa entre tiles.
 */
class SequenceMemoryEngine(
    scope: kotlinx.coroutines.CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
    private val showMs: Long = 600,
    private val gapMs: Long = 220,
) : BaseGameEngine<SequenceMemoryState>(GameIds.SEQUENCE_MEMORY, difficulty, scope, audio) {

    private val _state = MutableStateFlow(SequenceMemoryState())
    override val state: StateFlow<SequenceMemoryState> = _state.asStateFlow()

    private val sequence = mutableListOf<Int>()
    private var correctTaps = 0
    private var totalTaps = 0
    private var showJob: Job? = null

    override fun onStart() {
        sequence.clear()
        correctTaps = 0
        totalTaps = 0
        _state.value = SequenceMemoryState()
        nextRound()
    }

    override fun onPause() {
        showJob?.cancel()
    }

    override fun onResume() {
        // Reanudar a media reproducción confundiría; se repite la secuencia.
        if (_state.value.phase == MemoryPhase.SHOWING) showSequence()
    }

    /** Añade un paso aleatorio y reproduce la nueva secuencia. */
    private fun nextRound() {
        sequence += random.nextInt(SequenceMemoryState.GRID_SIZE)
        _state.update { it.copy(level = sequence.size, inputProgress = 0, phase = MemoryPhase.SHOWING) }
        showSequence()
    }

    private fun showSequence() {
        showJob?.cancel()
        showJob = scope.launch {
            _state.update { it.copy(phase = MemoryPhase.SHOWING, litTile = null, inputProgress = 0) }
            delay(gapMs)
            for (tile in sequence) {
                _state.update { it.copy(litTile = tile) }
                // Cada tile suena su propia nota (timbre arcade): la secuencia es una melodía.
                audio.playTone(MemoryNotes.frequencyFor(tile), durationMs = 260)
                delay(showMs)
                _state.update { it.copy(litTile = null) }
                delay(gapMs)
            }
            _state.update { it.copy(phase = MemoryPhase.INPUT) }
        }
    }

    /**
     * Toque del jugador sobre un tile. Solo se atiende en fase INPUT.
     * Acierto que completa la secuencia → nueva ronda. Fallo → fin de partida.
     */
    fun onTileTapped(index: Int) {
        if (_state.value.phase != MemoryPhase.INPUT) return
        totalTaps++

        val expected = sequence[_state.value.inputProgress]
        if (index != expected) {
            audio.playSound(SoundEffect.ERROR)
            audio.hapticFeedback(HapticFeedback.ERROR)
            _state.update { it.copy(phase = MemoryPhase.GAME_OVER, litTile = index) }
            finish() // publica el resultado en outcome
            return
        }

        // Acierto: el botón responde con su nota (mismo timbre que la reproducción).
        correctTaps++
        audio.playTone(MemoryNotes.frequencyFor(index))
        audio.hapticFeedback(HapticFeedback.LIGHT)
        val progress = _state.value.inputProgress + 1

        if (progress == sequence.size) {
            // Ronda completada: suma puntos y pasa a la siguiente.
            val newScore = _state.value.score + sequence.size * POINTS_PER_STEP
            audio.playSound(SoundEffect.SUCCESS)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            _state.update { it.copy(phase = MemoryPhase.ROUND_OK, score = newScore, inputProgress = progress) }
            scope.launch {
                delay(ROUND_PAUSE_MS)
                if (_state.value.phase == MemoryPhase.ROUND_OK) nextRound()
            }
        } else {
            _state.update { it.copy(inputProgress = progress) }
        }
    }

    /** Puntaje = suma de puntos por paso de cada ronda superada. */
    override fun calculateScore(): Int = _state.value.score

    override fun currentAccuracy(): Double =
        if (totalTaps == 0) 100.0 else (correctTaps.toDouble() / totalTaps * 100).coerceIn(0.0, 100.0)

    private companion object {
        const val POINTS_PER_STEP = 100
        const val ROUND_PAUSE_MS = 700L
    }
}
