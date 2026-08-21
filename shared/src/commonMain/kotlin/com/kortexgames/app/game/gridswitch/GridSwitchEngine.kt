package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.grid.GridPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.max

/**
 * # Motor de "Neon Grid Switch"
 *
 * Juego **LEVELED sin selector**: las etapas se suceden en orden estricto desde
 * la 1ª (ver KDoc de [GridSwitchContract]). Cada etapa es, a efectos de
 * puntuación y persistencia, una "partida" propia —igual que Línea Neón—: se
 * genera con [GridSwitchGenerator] al arrancar, y resolverla dispara [finish]
 * de inmediato (sin esperar a que el jugador pulse nada), para que la
 * celebración sea instantánea y el resultado (récord = etapa alcanzada) se
 * guarde etapa a etapa.
 *
 * ## Reparto de responsabilidades
 *
 * Toda la regla del juego —conmutación ortogonal, conteo de movimientos,
 * detección de victoria— vive aquí. El ViewModel (FASE 2, capa de arriba) solo
 * traduce intents→llamadas y eventos de dominio ([GridSwitchEvent])→Effects
 * sensoriales, mismo esquema que el resto de motores del catálogo.
 *
 * ## Por qué "reiniciar" no genera un tablero nuevo
 *
 * [restartStage] vuelve al MISMO desorden inicial de la etapa ([initialBoard]),
 * no a uno nuevo: como en Línea Neón (`NeonLineEngine.restart`), reiniciar es
 * "vuelvo a intentar este puzzle concreto", no "dame otro". Generar uno distinto
 * en cada reinicio dejaría al jugador sin poder aplicar lo que ya aprendió del
 * tablero al fallar, que es precisamente el valor de reiniciar.
 */
class GridSwitchEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : BaseGameEngine<GridSwitchGameState>(GameIds.NEON_GRID_SWITCH, difficulty, scope, audio) {

    private val _state = MutableStateFlow(GridSwitchGameState())
    override val state: StateFlow<GridSwitchGameState> = _state.asStateFlow()

    private val _events = Channel<GridSwitchEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<GridSwitchEvent> = _events.receiveAsFlow()

    /** Etapa en juego (1-based); base del récord, de la dificultad y del "par" de puntuación. */
    private var currentStage = 1

    /**
     * Desorden con el que arrancó la etapa actual. Se guarda aparte de
     * [GridSwitchGameState.board] (que muta con cada toque) porque [restartStage]
     * necesita volver exactamente a él, no a un scramble nuevo (ver KDoc de clase).
     */
    private var initialBoard: LightGrid = LightGrid.solved(GRID_SWITCH_MIN_SIZE)

    /**
     * Veces que el jugador reinició la etapa actual. Cuenta como "ayuda" a efectos
     * de puntuación (ver [calculateScore]): sin penalizarlo, tantear a lo bruto y
     * reiniciar hasta acertar por azar sería la estrategia óptima, y el puntaje
     * dejaría de medir si el jugador RESOLVIÓ el puzzle o lo encontró por fuerza
     * bruta. Mismo criterio que `NeonLineEngine.restarts`.
     */
    private var restarts = 0

    /** Arranca (o reempieza desde cero) la etapa [stage]. */
    fun startAtStage(stage: Int) {
        currentStage = stage.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        restarts = 0
        initialBoard = GridSwitchGenerator.generate(currentStage, stageSeed(currentStage))
        _state.value = GridSwitchGameState(board = initialBoard, moveCount = 0)
    }

    /**
     * Vuelve al desorden con el que arrancó la etapa (ver KDoc de clase) y suma un
     * [restarts]. No-op si la partida ya terminó (no tiene sentido reiniciar una
     * etapa cuyo resultado ya se guardó).
     */
    fun restartStage() {
        if (status.value == GameStatus.FINISHED) return
        restarts++
        _state.value = GridSwitchGameState(board = initialBoard, moveCount = 0)
    }

    /**
     * El jugador tocó [cell]: conmuta esa celda y sus vecinas ortogonales
     * ([LightGrid.toggled]), cuenta el movimiento y comprueba victoria.
     *
     * Se ignora fuera de [GameStatus.RUNNING] o si la etapa ya está resuelta —esto
     * último cubre el instante entre `board.isSolved` y que el ViewModel reaccione
     * a [finish]: un toque colado ahí no debe seguir mutando un tablero ya dado
     * por bueno.
     */
    fun onCellToggled(cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.isSolved) return

        val board = s.board.toggled(cell)
        _state.value = s.copy(board = board, moveCount = s.moveCount + 1)
        _events.trySend(GridSwitchEvent.CellToggled)

        // Victoria: se cierra aquí mismo (no al pulsar nada) para que la celebración
        // sea inmediata, igual que Línea Neón al cubrir la última celda del trazo.
        if (board.isSolved) {
            _events.trySend(GridSwitchEvent.StageCleared)
            finish()
        }
    }

    // ------------------------------------------------------------- puntuación

    /**
     * Puntaje = **etapa + eficiencia + velocidad − ayudas**, los tres ejes de
     * mérito de la regla de puntuación del proyecto (ver memoria: "la puntuación
     * debe penalizar ayudas"). Un puntaje que solo mirara la etapa premiaría a
     * quien más partidas lleva jugadas, no a quien resuelve mejor.
     *
     *  - **Etapa** ([STAGE_POINTS] por etapa): escala dominante, para que una
     *    etapa 6 resuelta con torpeza siempre valga más que una etapa 2 perfecta.
     *  - **Eficiencia** ([efficiencyRatio]): movimientos reales contra el "par" de
     *    la etapa. El *par* es el nº de toques que usó el generador para
     *    desordenarla ([GridSwitchStages.scrambleTouchesForStage]): no es el
     *    óptimo matemático (calcularlo exigiría álgebra lineal sobre GF(2) en
     *    tiempo real, ver KDoc de [GridSwitchGenerator]), pero SÍ es una solución
     *    conocida y válida —resolver en ese nº de toques o menos es, por
     *    definición, jugar tan bien o mejor que el desorden que generó el nivel—,
     *    así que sirve como referencia justa sin coste de cómputo extra.
     *  - **Velocidad**: proporción entre el tiempo objetivo (par × tiempo por
     *    movimiento, [TARGET_MS_PER_MOVE]) y el real.
     *  - **Reinicios**: castigo por tirar el tablero y volver a empezar.
     *
     * El castigo total se topa en [MAX_PENALTY] = `STAGE_POINTS - 1` para que
     * ninguna partida, por muchos reinicios que acumule, pueda hundir su puntaje
     * por debajo del de la etapa anterior: la escala por etapa manda en el orden
     * del ranking, la penalización solo desempata dentro de una misma etapa.
     */
    override fun calculateScore(): Int {
        val s = _state.value
        if (!s.isSolved) return 0

        val par = GridSwitchStages.scrambleTouchesForStage(currentStage)
        val efficiency = efficiencyRatio(par, s.moveCount)

        val targetMs = par.toLong() * TARGET_MS_PER_MOVE
        val elapsedMs = max(1L, elapsedActive().inWholeMilliseconds)
        val speed = (targetMs.toDouble() / elapsedMs).coerceIn(0.0, 1.0)

        val penalty = (restarts * RESTART_PENALTY).coerceAtMost(MAX_PENALTY)
        val score = currentStage * STAGE_POINTS +
            EFFICIENCY_BONUS * efficiency +
            SPEED_BONUS * speed -
            penalty
        return score.toInt().coerceAtLeast(0)
    }

    /**
     * Precisión = la misma eficiencia del puntaje, en 0..100: en este juego
     * "hacerlo bien" no es acertar respuestas sino no gastar toques de más frente
     * al par de la etapa.
     */
    override fun currentAccuracy(): Double =
        efficiencyRatio(GridSwitchStages.scrambleTouchesForStage(currentStage), _state.value.moveCount) * 100

    /** Récord = etapa completada (la partida-etapa solo termina al resolverla). */
    override fun reachedMetric(): Int = currentStage

    /**
     * Fracción `par / movimientos`, acotada a `1.0`: resolver en el par o menos
     * puntúa eficiencia máxima: pasarse de toques la reduce proporcionalmente.
     */
    private fun efficiencyRatio(par: Int, moveCount: Int): Double =
        (par.toDouble() / max(1, moveCount)).coerceIn(0.0, 1.0)

    /**
     * Semilla determinista de la etapa [stage]: el mismo número de etapa produce
     * siempre el mismo desorden inicial (ver KDoc de [GridSwitchGenerator]).
     */
    private fun stageSeed(stage: Int): Long = stage.toLong()

    private companion object {
        /** Escala base por etapa: el eje dominante del puntaje. */
        const val STAGE_POINTS = 1_000

        /** Máximo que aporta resolver en el par de toques (o menos) sin pasarse. */
        const val EFFICIENCY_BONUS = 400

        /** Máximo que aporta resolver dentro del tiempo objetivo. */
        const val SPEED_BONUS = 300

        /** Tiempo objetivo por movimiento del par: define el ritmo "resuelto con soltura". */
        const val TARGET_MS_PER_MOVE = 2_500L

        /** Castigo por reiniciar la etapa y volver a empezar. */
        const val RESTART_PENALTY = 80

        /**
         * Tope del castigo acumulado. Queda 1 punto por debajo de [STAGE_POINTS]
         * para que ninguna partida penalizada caiga por debajo de la etapa
         * anterior (ver KDoc de [calculateScore]).
         */
        const val MAX_PENALTY = STAGE_POINTS - 1
    }
}

/**
 * Estado puro del motor en un instante: lo que emite el motor y dibuja la UI.
 * Sin nociones de sonido/háptica ni de ciclo de vida (eso vive en el contrato y
 * en [com.kortexgames.app.game.GameStatus] respectivamente). Inmutable: cada
 * toque produce un snapshot nuevo.
 *
 * @property board tablero de luces actual.
 * @property moveCount toques del jugador desde el último inicio/reinicio de la etapa.
 */
data class GridSwitchGameState(
    val board: LightGrid = LightGrid.solved(GRID_SWITCH_MIN_SIZE),
    val moveCount: Int = 0,
) {
    /** Azúcar sobre `board.isSolved`, para no repetir la ruta en cada consulta. */
    val isSolved: Boolean get() = board.isSolved
}

/**
 * Eventos de dominio del motor: describen QUÉ pasó (semántica de juego), no cómo
 * suena o vibra — esa traducción la hace el ViewModel al mapearlos a Effects
 * (tabla única auditable, mismo esquema que el resto del catálogo).
 */
sealed interface GridSwitchEvent {
    /** Una celda se conmutó (y con ella sus vecinas ortogonales existentes). */
    data object CellToggled : GridSwitchEvent

    /** El tablero quedó completamente apagado: etapa resuelta. */
    data object StageCleared : GridSwitchEvent
}
