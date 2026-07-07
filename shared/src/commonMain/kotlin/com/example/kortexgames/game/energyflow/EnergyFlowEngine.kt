package com.example.kortexgames.game.energyflow

import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.game.BaseGameEngine
import com.example.kortexgames.game.GameIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Estado de UI de "Flujo de Energía".
 *
 * @property grid tablero actual (fuente de verdad para pintar).
 * @property powered índices de celdas energizadas desde la fuente (para el "glow"
 *           de la red conectada; se recalcula tras cada giro).
 * @property rotations nº de giros hechos por el jugador (para puntuar la eficiencia).
 * @property solved true cuando el circuito queda cerrado (victoria).
 * @property lastRotated índice de la última pieza girada, o null (para el destello).
 * @property rotationSeq contador que sube en cada giro; la UI lo observa para
 *           relanzar UNA vez el feedback aunque se gire dos veces la misma pieza.
 */
data class EnergyFlowState(
    val grid: EnergyGrid = EnergyGrid(0, 0, emptyList()),
    val powered: Set<Int> = emptySet(),
    val rotations: Int = 0,
    val solved: Boolean = false,
    val lastRotated: Int? = null,
    val rotationSeq: Long = 0,
)

/**
 * Motor de "Flujo de Energía", categoría Visión Espacial.
 *
 * Juego **por turnos** (sin bucle de tiempo): cada toque en una pieza la gira 90°
 * en sentido horario; el motor recalcula la red energizada y comprueba si el
 * circuito quedó cerrado ([EnergyGrid.isSolved]). Al cerrarlo → [finish].
 *
 * Feedback inmediato (CLAUDE.md §9.4):
 *  - **giro** → [SoundEffect.TAP] + háptica ligera,
 *  - **circuito cerrado** → [SoundEffect.SUCCESS] + háptica fuerte (el clímax).
 *  El sonido de **fin de partida** ([SoundEffect.LEVEL_UP]) lo dispara el ViewModel
 *  al persistir, coherente con los demás juegos.
 *
 * Guarda el tablero inicial barajado para poder **reiniciar** el mismo nivel.
 *
 * @param random fuente aleatoria (sembrable en tests para niveles deterministas).
 */
class EnergyFlowEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<EnergyFlowState>(GameIds.ENERGY_FLOW, difficulty, scope, audio) {

    private val _state = MutableStateFlow(EnergyFlowState())
    override val state: StateFlow<EnergyFlowState> = _state.asStateFlow()

    private val config = EnergyFlowGenerator.configForDifficulty(difficulty)

    // Tablero inicial (para reiniciar) y óptimo de giros (para puntuar la eficiencia).
    private var initialGrid: EnergyGrid = EnergyGrid(0, 0, emptyList())
    private var optimalRotations = 0
    private var rotationSeq = 0L

    override fun onStart() {
        val level = EnergyFlowGenerator.generate(config, random)
        initialGrid = level.grid
        optimalRotations = level.optimalRotations
        rotationSeq = 0
        _state.value = EnergyFlowState(
            grid = level.grid,
            powered = level.grid.poweredIndices(),
        )
    }

    /**
     * Único gesto de entrada: tocar la pieza [index] la gira 90° en horario. Ignora
     * toques cuando el circuito ya está cerrado (partida terminada).
     */
    fun onTileRotate(index: Int) {
        val s = _state.value
        if (s.solved) return

        val newGrid = s.grid.rotate(index)
        val solved = newGrid.isSolved()
        _state.value = s.copy(
            grid = newGrid,
            powered = newGrid.poweredIndices(),
            rotations = s.rotations + 1,
            solved = solved,
            lastRotated = index,
            rotationSeq = ++rotationSeq,
        )

        if (solved) {
            // Clímax del nivel: refuerzo marcado antes de que el VM cierre la partida.
            audio.playSound(SoundEffect.SUCCESS)
            audio.hapticFeedback(HapticFeedback.HEAVY)
            finish()
        } else {
            audio.playSound(SoundEffect.TAP)
            audio.hapticFeedback(HapticFeedback.LIGHT)
        }
    }

    /** Reinicia el MISMO nivel a su barajado inicial (no genera uno nuevo). */
    fun restart() {
        rotationSeq = 0
        _state.value = EnergyFlowState(
            grid = initialGrid,
            powered = initialGrid.poweredIndices(),
        )
    }

    /**
     * Puntaje: base por dificultad menos una penalización por cada giro de más
     * respecto al óptimo ([optimalRotations]). Premia resolver con pocos giros;
     * nunca baja de 0.
     */
    override fun calculateScore(): Int {
        val base = difficulty * 1_000
        val extra = (_state.value.rotations - optimalRotations).coerceAtLeast(0)
        return (base - extra * PENALTY_PER_EXTRA_ROTATION).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: giros óptimos / giros reales (tope 100 %). */
    override fun currentAccuracy(): Double {
        val rotations = _state.value.rotations
        if (rotations == 0 || optimalRotations == 0) return 100.0
        return (optimalRotations.toDouble() / rotations * 100).coerceAtMost(100.0)
    }

    private companion object {
        const val PENALTY_PER_EXTRA_ROTATION = 25
    }
}
