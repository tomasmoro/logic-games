package com.kortexgames.app.game.neonline

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
 * # Motor de "Línea Neón" — validación estricta del trazo y retroceso táctil
 *
 * Juego **LEVELED por gesto** (sin bucle de tiempo): el jugador posa el dedo en una
 * celda libre y arrastra, celda a celda, hasta cubrir TODAS las celdas libres con
 * una sola línea. El motor es el único que decide qué hace cada celda que toca el
 * dedo; la UI solo reporta la celda cruda bajo él (px→celda es geometría de layout,
 * suya).
 *
 * ## La máquina del trazo (el "porqué" del diseño)
 *
 * Todo el juego se reduce a mantener UNA lista ordenada
 * ([NeonLineGameState.path]) y, por cada celda nueva bajo el dedo, resolver un
 * `when` de casos **mutuamente excluyentes** contra la punta. El orden importa: los
 * casos van de más específico a más general, y el primero que encaja decide.
 *
 *  1. **Misma celda** que la punta → nada (el arrastre emite muchos eventos dentro
 *     de la misma celda).
 *  2. **Retroceso**: la celda es la ANTERIOR a la punta → se borra la punta. Es el
 *     "undo táctil": desandar el dedo corrige sin levantarlo (ver más abajo por qué
 *     solo un paso).
 *  3. **No adyacente** (diagonal o salto de celda) → se ignora **en silencio**.
 *  4. **Obstáculo** → rechazo con feedback.
 *  5. **Ya visitada** (y no era el retroceso del caso 2) → rechazo con feedback: la
 *     línea no se pisa a sí misma.
 *  6. **Celda libre y adyacente** → avanza la punta. Si con ella el trazo cubre
 *     todas las celdas libres, el nivel está resuelto y la partida termina.
 *
 * ### Por qué el rechazo NO se emite en el caso 3
 *
 * Un dedo que se mueve rápido genera decenas de eventos por segundo y muchos caen en
 * celdas no adyacentes (el gesto natural corta esquinas en diagonal). Sonar y vibrar
 * ahí convertiría el arrastre en una ametralladora de errores por hacer algo que el
 * jugador ni percibe como movimiento. Se reserva el feedback de rechazo a los
 * intentos **deliberados**: entrar en un obstáculo o cruzar la propia línea, que son
 * celdas adyacentes a las que el jugador apuntó a propósito. Además se deduplica con
 * [lastRejected]: mantener el dedo quieto sobre un obstáculo avisa una vez, no cien.
 *
 * ### Por qué el motor NUNCA interpola celdas intermedias
 *
 * Sería tentador que un salto de 3 celdas en línea recta rellenara las intermedias
 * (el dedo iba rápido). No se hace: regalaría movimientos que el jugador no ejecutó
 * y, en un puzzle cuyo mérito es decidir por dónde pasar, eso es resolver por él. Si
 * el dedo se adelanta, el trazo simplemente no avanza hasta volver a una celda
 * contigua.
 *
 * ### Por qué el retroceso es de UN paso y no hasta cualquier celda
 *
 * Se podría permitir arrastrar hasta una celda intermedia del trazo y recortar todo
 * lo posterior de golpe (como hace Conectores al retomar un cable). Aquí no: el
 * único gesto continuo del juego es la línea, y saltar el dedo a una celda intermedia
 * lejana es indistinguible de un arrastre rápido que pasa por encima. Recortar por
 * "un paso atrás cada vez" mantiene la regla trivial de explicar —desandas lo
 * andado— y hace imposible borrar medio tablero por un gesto brusco. Para tirarlo
 * todo está [restart].
 *
 * ## Feedback
 * Igual que Conectores y Starport: el motor emite eventos de dominio
 * ([NeonLineEvent]) y el ViewModel los traduce a Effects MVI (tabla única
 * auditable). El `audio` heredado de [BaseGameEngine] queda sin usar a propósito.
 */
class NeonLineEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : BaseGameEngine<NeonLineGameState>(GameIds.NEON_LINE, difficulty, scope, audio) {

    private val _state = MutableStateFlow(NeonLineGameState())
    override val state: StateFlow<NeonLineGameState> = _state.asStateFlow()

    // Buffered + trySend: los eventos son feedback efímero; si el canal se llenara
    // (imposible en la práctica) perder uno es preferible a bloquear el arrastre.
    private val _events = Channel<NeonLineEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<NeonLineEvent> = _events.receiveAsFlow()

    /** Nivel en juego (1-based); base del récord y de "Siguiente nivel". */
    private var currentLevel = 1

    /** Nivel cargado; se guarda para no regenerarlo en cada consulta. */
    private var level: NeonLineLevel = NeonLineLevels.forNumber(1)

    /**
     * Nº de celdas desandadas en la partida. Es la métrica de eficiencia del juego:
     * una solución perfecta se traza de una tirada, sin volver atrás (ver
     * [calculateScore]).
     */
    private var retractions = 0

    /**
     * Nº de veces que el jugador tiró el trazo entero para empezar de cero (botón de
     * reinicio o posar el dedo de nuevo sobre un trazo ya avanzado).
     *
     * Cuenta como "ayuda" a efectos de puntuación: no da información, pero borra el
     * coste de haberse equivocado. Sin penalizarlo, la estrategia óptima sería
     * tantear a lo bruto y reiniciar hasta acertar, y la puntuación dejaría de medir
     * si el jugador RESOLVIÓ el nivel o lo encontró por fuerza bruta.
     */
    private var restarts = 0

    /**
     * Última celda cuyo rechazo ya se notificó, para no repetir el aviso mientras el
     * dedo sigue apoyado en ella (ver el KDoc de la clase). Se limpia en cuanto hay
     * un movimiento válido: volver a chocar después sí es un intento nuevo.
     */
    private var lastRejected: GridPosition? = null

    /** Arranca el nivel elegido en el selector (o el siguiente desde el resultado). */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        level = NeonLineLevels.forNumber(currentLevel)
        retractions = 0
        restarts = 0
        lastRejected = null
        _state.value = NeonLineGameState.from(level)
    }

    /**
     * Reinicia el MISMO nivel: borra el trazo. A diferencia de [onStart] conserva
     * los contadores y suma un [restarts], porque reiniciar es precisamente lo que
     * la puntuación debe registrar (ver [calculateScore]); si los limpiara, reiniciar
     * sería gratis y el contador no mediría nada.
     */
    fun restart() {
        if (status.value == GameStatus.FINISHED) return
        restarts++
        lastRejected = null
        _state.value = NeonLineGameState.from(level)
    }

    // ------------------------------------------------------------------ input

    /**
     * El jugador posó el dedo en [cell]: empieza un trazo nuevo desde ahí.
     *
     * Cualquier celda libre es un comienzo válido —elegir bien por dónde empezar es
     * parte del puzzle—, así que solo se rechaza el obstáculo. Si ya había un trazo
     * con recorrido, posar el dedo en otro sitio equivale a **reiniciar**: se
     * contabiliza como tal para que la puntuación no premie tantear (ver [restarts]).
     */
    fun onPathStarted(cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.isSolved) return
        if (!level.isPlayable(cell)) {
            reject(cell)
            return
        }
        // Un trazo de 1 celda es un comienzo sin recorrido: recolocar el dedo ahí no
        // es "tirar el progreso", así que no cuenta como reinicio.
        if (s.path.size > 1) restarts++

        lastRejected = null
        _state.value = s.copy(path = listOf(cell), isTracing = true)
        _events.trySend(NeonLineEvent.PathStarted)
    }

    /**
     * El dedo entró en [cell]. Resuelve el `when` de casos descrito en el encabezado
     * de la clase. Si no hay trazo en curso o la partida no está RUNNING, se ignora.
     */
    fun onPathUpdated(cell: GridPosition) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.isSolved) return
        if (!s.isTracing) return
        val head = s.head ?: return

        if (cell == head) return                                   // caso 1: misma celda

        // Caso 2: retroceso sobre la celda anterior → borra la punta (undo táctil).
        if (cell == s.previous) {
            retractions++
            lastRejected = null
            _state.value = s.copy(path = s.path.dropLast(1))
            _events.trySend(NeonLineEvent.PathRetracted)
            return
        }

        // Caso 3: diagonal o salto → silencio (ver el porqué en el KDoc de la clase).
        if (!head.isOrthogonallyAdjacentTo(cell)) return

        if (level.isObstacle(cell)) {                              // caso 4
            reject(cell)
            return
        }
        if (cell in s.visited) {                                   // caso 5: auto-cruce
            reject(cell)
            return
        }

        // Caso 6: avanzar la punta.
        lastRejected = null
        val next = s.copy(path = s.path + cell)
        _state.value = next
        _events.trySend(NeonLineEvent.CellAdvanced)

        // Victoria: el trazo cubre todas las celdas libres. Se cierra aquí y no al
        // levantar el dedo para que la celebración sea inmediata: el jugador ve
        // encenderse la última celda y el circuito completo a la vez.
        if (next.isSolved) {
            _state.value = next.copy(isTracing = false)
            _events.trySend(NeonLineEvent.Solved)
            finish()
        }
    }

    /**
     * El jugador levantó el dedo: consolida el trazo tal cual quedó. Un trazo a
     * medias es válido y se conserva —no hay castigo por soltar—, pero deja de ser
     * extensible: para seguir hay que volver a posar el dedo, y eso cuenta como
     * reinicio ([onPathStarted]). Es lo que mantiene la promesa del juego: la
     * solución es UN trazo continuo, no una colcha de retales.
     */
    fun onPathEnded() {
        val s = _state.value
        if (!s.isTracing) return
        _state.value = s.copy(isTracing = false)
    }

    /** Notifica un movimiento ilegal deliberado, deduplicando por celda ([lastRejected]). */
    private fun reject(cell: GridPosition) {
        if (lastRejected == cell) return
        lastRejected = cell
        _events.trySend(NeonLineEvent.MoveRejected(cell))
    }

    // ------------------------------------------------------------- puntuación

    /**
     * Puntaje = **nivel + eficiencia + velocidad − ayudas**, los tres ejes de mérito
     * que exige la regla de puntuación del proyecto. Un puntaje que solo mirara el
     * nivel haría que el ranking mundial lo liderara quien más tiempo lleva jugando,
     * no quien mejor juega.
     *
     *  - **Nivel** ([LEVEL_POINTS] por nivel): escala dominante, para que un nivel 12
     *    resuelto regular siempre valga más que un nivel 3 impecable.
     *  - **Eficiencia** ([efficiencyRatio]): cuánto del trazo fue hacia delante. Es
     *    la medida de si el jugador *vio* la solución o la tanteó.
     *  - **Velocidad**: proporción entre el tiempo objetivo del nivel (que crece con
     *    el nº de celdas, ver [TARGET_MS_PER_CELL]) y el real.
     *  - **Reinicios**: castigo por tirar el trazo y volver a empezar.
     *
     * El castigo total se topa en [MAX_PENALTY] = `LEVEL_POINTS - 1` a propósito:
     * por muchos reinicios que acumule una partida, nunca puede hundir su puntaje por
     * debajo del de un nivel inferior. La escala por nivel debe seguir mandando en el
     * orden del ranking; la penalización solo desempata dentro de un mismo nivel.
     */
    override fun calculateScore(): Int {
        val s = _state.value
        if (!s.isSolved) return 0

        val targetMs = max(1, s.playableCount) * TARGET_MS_PER_CELL
        val elapsedMs = max(1L, elapsedActive().inWholeMilliseconds)
        val speed = (targetMs.toDouble() / elapsedMs).coerceIn(0.0, 1.0)

        val penalty = (restarts * RESTART_PENALTY).coerceAtMost(MAX_PENALTY)
        val score = currentLevel * LEVEL_POINTS +
            EFFICIENCY_BONUS * efficiencyRatio() +
            SPEED_BONUS * speed -
            penalty
        return score.toInt().coerceAtLeast(0)
    }

    /**
     * Fracción del gesto que fue avance útil: `celdas / (celdas + retrocesos)`.
     *
     * `1.0` = trazo perfecto de una tirada. Se mide contra los retrocesos y no contra
     * el nº de celdas del tablero porque la longitud mínima de la solución es fija
     * (hay que pisar todas las celdas libres, ni una más): lo único que un jugador
     * puede hacer de más es desandar.
     */
    private fun efficiencyRatio(): Double {
        val cells = _state.value.path.size
        val total = cells + retractions
        return if (total == 0) 1.0 else cells.toDouble() / total
    }

    /**
     * Precisión = la misma eficiencia del trazo, en 0..100. En este juego "hacerlo
     * bien" no es acertar respuestas sino no tener que rectificar, así que la
     * proporción de avance útil es la lectura natural de calidad de la partida.
     */
    override fun currentAccuracy(): Double = efficiencyRatio() * 100

    /** Récord = nivel completado (la partida solo termina al resolverlo). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        /** Escala base por nivel: el eje dominante del puntaje. */
        const val LEVEL_POINTS = 1_000

        /** Máximo que aporta trazar sin retroceder ni una celda. */
        const val EFFICIENCY_BONUS = 400

        /** Máximo que aporta resolver dentro del tiempo objetivo. */
        const val SPEED_BONUS = 300

        /** Tiempo objetivo por celda libre: define el ritmo "resuelto con soltura". */
        const val TARGET_MS_PER_CELL = 900L

        /** Castigo por tirar el trazo y empezar de nuevo. */
        const val RESTART_PENALTY = 60

        /**
         * Tope del castigo acumulado. Queda 1 punto por debajo de [LEVEL_POINTS] para
         * que ninguna partida penalizada caiga por debajo del nivel anterior (ver el
         * KDoc de [calculateScore]).
         */
        const val MAX_PENALTY = LEVEL_POINTS - 1
    }
}

/**
 * Eventos de dominio del motor: describen QUÉ pasó (semántica de juego), no cómo
 * suena o vibra — esa traducción la hace el ViewModel al mapearlos a Effects (tabla
 * única auditable en `NeonLineViewModel.onEngineEvent`).
 */
sealed interface NeonLineEvent {

    /** El dedo se posó en una celda libre: empieza un trazo. */
    data object PathStarted : NeonLineEvent

    /** La línea avanzó una celda: el "tick" ASMR que da textura al arrastre. */
    data object CellAdvanced : NeonLineEvent

    /** La línea se replegó una celda (retroceso desandando el dedo). */
    data object PathRetracted : NeonLineEvent

    /**
     * Movimiento ilegal deliberado sobre [cell] (obstáculo o celda ya trazada). Los
     * saltos y diagonales del arrastre rápido NO generan este evento; ver el KDoc de
     * [NeonLineEngine].
     */
    data class MoveRejected(val cell: GridPosition) : NeonLineEvent

    /** El trazo cubrió todas las celdas libres: circuito completo. */
    data object Solved : NeonLineEvent
}
