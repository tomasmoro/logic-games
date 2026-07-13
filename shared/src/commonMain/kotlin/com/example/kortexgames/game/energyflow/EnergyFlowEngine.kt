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
    val round: Int = 1,
    val totalRounds: Int = 3,
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
 * Esta versión se juega en **tres rondas** consecutivas: 4x4, 5x5 y 6x6. Solo al
 * cerrar la tercera se finaliza la partida.
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

    /**
     * Nivel en juego (1-based). Lo fija [startAtLevel] al elegir un nivel en el
     * selector; su tamaño de rejilla sale de la curva paramétrica [configForLevel].
     * Es la base del récord (nivel máximo) y del punto de reanudación.
     */
    private var currentLevel = 1

    // Tablero inicial (para reiniciar) y óptimo de giros del nivel (para la eficiencia).
    private var initialGrid: EnergyGrid = EnergyGrid(0, 0, emptyList())
    private var currentRoundOptimalRotations = 0
    private var rotationSeq = 0L

    /**
     * Curva de dificultad: nivel N → tamaño de rejilla cuadrada. Progresión lenta
     * (pedido del usuario): niveles 1-2 en 4×4, luego bloques de **3 niveles** por
     * cada tamaño creciente (5×5, 6×6, 7×7...), acotado a un máximo de 7×7 para que
     * la generación y la lectura del tablero sigan siendo cómodas.
     */
    private fun configForLevel(level: Int): EnergyFlowGenerator.Config {
        val n = if (level <= 2) {
            4
        } else {
            val block = (level - 3) / 3 // niveles 3-5 -> bloque 0, 6-8 -> bloque 1, ...
            (5 + block).coerceAtMost(7)
        }
        return EnergyFlowGenerator.Config(n, n)
    }

    /**
     * Arranca el nivel [level] elegido en el selector: fija [currentLevel], regenera
     * su rejilla y empieza la partida. La usa el ViewModel al pulsar un nivel
     * desbloqueado o "Siguiente nivel".
     */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        startCurrentLevel()
    }

    private fun startCurrentLevel() {
        val level = EnergyFlowGenerator.generate(configForLevel(currentLevel), random)
        initialGrid = level.grid
        currentRoundOptimalRotations = level.optimalRotations
        rotationSeq = 0
        _state.value = EnergyFlowState(
            grid = level.grid,
            powered = level.grid.poweredIndices(),
            round = currentLevel,
            totalRounds = currentLevel, // el HUD lo lee como "Nivel N"
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
            // Nivel resuelto: refuerzo marcado y cierre. El avance al siguiente nivel
            // lo decide el jugador desde el game-over (o el selector).
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
            round = currentLevel,
            totalRounds = currentLevel,
        )
    }

    /**
     * Puntaje: base proporcional al nivel menos una penalización por cada giro de
     * más respecto al óptimo ([optimalRotations]). Premia resolver niveles altos con
     * pocos giros; nunca baja de 0.
     */
    override fun calculateScore(): Int {
        val base = currentLevel * 1_000
        val extra = (_state.value.rotations - currentRoundOptimalRotations).coerceAtLeast(0)
        return (base - extra * PENALTY_PER_EXTRA_ROTATION).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: giros óptimos / giros reales (tope 100 %). */
    override fun currentAccuracy(): Double {
        val rotations = _state.value.rotations
        if (rotations == 0 || currentRoundOptimalRotations == 0) return 100.0
        return (currentRoundOptimalRotations.toDouble() / rotations * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel completado (solo se llega aquí al resolver el nivel). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        const val PENALTY_PER_EXTRA_ROTATION = 25
    }
}
