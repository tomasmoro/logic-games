package com.kortexgames.app.game.hypercube

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.max
import kotlin.random.Random

/**
 * # Motor del Neon Hyper-Cube — mezcla, giros de capa y detección de victoria
 *
 * Juego **LEVELED sin límite de tiempo**: el nivel fija la profundidad de la mezcla y la partida
 * termina cuando el cubo queda uniforme. El motor es 100 % portable (ni Compose ni plataforma):
 * recibe intenciones ya traducidas a dominio (eje, capa, sentido) y publica un
 * [HyperCubeGameState] inmutable.
 *
 * ## Una sola tubería para todos los giros
 * Tanto la mezcla automática como las jugadas del jugador pasan por el mismo camino: se coloca un
 * [ActiveTurn] y el reloj de frames lo lleva de 0 a 1; al llegar a 1 se aplica la permutación
 * **exacta** al cubo ([CubeState.applyTurn]) y se libera la tubería. Consecuencias buscadas:
 *  - el estado lógico nunca contiene cubos "a medio girar" (imposibles con coordenadas enteras),
 *  - la mezcla se **ve** girando en lugar de aparecer barajada de golpe (es el mejor gancho
 *    visual del juego, y de paso le enseña al jugador qué gestos existen),
 *  - y solo hay un sitio donde se decide qué significa "giro terminado".
 *
 * ## Por qué el cronómetro arranca DESPUÉS de la mezcla
 * `BaseGameEngine.start()` pone el reloj a cero, así que se llama al terminar de barajar, no al
 * entrar en el nivel: cobrarle al jugador el segundo y medio de animación falsearía la
 * comparación de tiempos entre partidas del mismo nivel (`tracksLevelTime`). Durante la mezcla el
 * `status` sigue siendo el previo (IDLE, o FINISHED si viene de una partida) y el bucle de frames
 * se autoriza con [HyperCubeGameState.isScrambling] en vez de con el estado del ciclo de vida.
 *
 * ## Feedback
 * El motor emite [HyperCubeEffect] semánticos por un `Channel`; el ViewModel los traduce a
 * sonido/háptica concretos. El `audio` heredado de [BaseGameEngine] queda sin usar a propósito.
 *
 * @param scope scope de corrutinas heredado del ViewModel (lo exige [BaseGameEngine]).
 * @param audio manager de audio heredado; no se usa aquí (ver arriba).
 * @param difficulty dificultad 1..5 con la que se registra el resultado.
 * @param random fuente de aleatoriedad de la mezcla. Inyectable para que un test pueda fijar la
 *   semilla y afirmar sobre una secuencia concreta de giros.
 */
class HyperCubeEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<HyperCubeGameState>(GameIds.HYPER_CUBE, difficulty, scope, audio) {

    private val _state = MutableStateFlow(HyperCubeGameState())
    override val state: StateFlow<HyperCubeGameState> = _state.asStateFlow()

    // Buffered + trySend: el feedback es efímero; si el canal se llenara (imposible en la
    // práctica) perder un "clack" es preferible a bloquear el bucle de animación.
    private val _effects = Channel<HyperCubeEffect>(Channel.BUFFERED)

    /** Señales de feedback para que el ViewModel las convierta en sonido/háptica. */
    val effects: Flow<HyperCubeEffect> = _effects.receiveAsFlow()

    /** Nivel en juego (1-based); base del récord y de "Siguiente nivel". */
    private var currentLevel = 1

    /**
     * ¿La partida en curso es el **modo libre** (cubo entero mezclado, sin nivel)?
     *
     * Se guarda aparte de [currentLevel] porque cambia dos contratos: el modo libre no produce
     * récord de nivel ([reachedMetric] devuelve `null`) y su mezcla no sale de la rampa sino de
     * [FREE_SCRAMBLE_DEPTH].
     */
    private var freeMode = false

    /** Giros de la mezcla aún por reproducir. Se vacía por la cabeza, uno por animación. */
    private val pendingScramble = ArrayDeque<LayerTurn>()

    /**
     * Timestamp del frame anterior. `null` significa "no hay delta fiable": ocurre en el primer
     * frame y tras reanudar de una pausa, y hace que ese frame solo sirva para re-anclar el reloj.
     */
    private var lastFrameNanos: Long? = null

    /**
     * Jugadas del jugador en esta partida, en orden: la **pila de deshacer**. No incluye los giros
     * de la mezcla —deshacer hasta "desmezclar" el cubo sería resolverlo con un botón—.
     */
    private val undoStack = ArrayDeque<LayerTurn>()

    /** ¿El giro que hay en vuelo es un "deshacer"? Decide si suma o resta al contador de jugadas. */
    private var undoInFlight = false

    /** Nº de deshaceres usados: penalizan el puntaje como cualquier otra ayuda. */
    private var undosUsed = 0

    /** ¿Se gastó ya el deshacer gratuito de esta partida? El resto cuestan un anuncio. */
    private var freeUndoUsed = false

    /**
     * Milisegundos ya jugados antes de reanudar una partida guardada.
     *
     * Existe porque el cronómetro de [BaseGameEngine] arranca de cero en cada `start()` y no se
     * puede precargar. Sin este desfase, salir y volver a entrar pondría el reloj a cero y
     * regalaría un "mejor tiempo" del nivel que nunca se hizo.
     */
    private var resumedOffsetMs = 0L

    // --------------------------------------------------------------- ciclo de nivel

    /**
     * Prepara y lanza el nivel [level]: deja el cubo resuelto y encola una mezcla nueva, que se
     * reproducirá animada con los siguientes ticks.
     *
     * No llama a `start()` todavía —lo hace [finishScramble] cuando el último giro encaja— por la
     * razón explicada en el KDoc de la clase.
     */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceIn(1, MAX_LEVEL)
        freeMode = false
        beginRound(scrambleDepthFor(currentLevel))
    }

    /**
     * Arranca el **modo libre**: cubo completamente mezclado, sin nivel ni récord.
     *
     * Es la salida para quien sabe resolver un cubo de verdad, sin contaminar la progresión: al
     * no haber un "nivel alcanzado" que comparar, la partida puntúa y cuenta para las estadísticas
     * pero no toca el récord de nivel máximo (ver [reachedMetric]).
     */
    fun startFreeMode() {
        freeMode = true
        beginRound(FREE_SCRAMBLE_DEPTH)
    }

    /** Reinicia la partida actual (nivel o modo libre) con una mezcla **nueva**. */
    fun restart() = if (freeMode) startFreeMode() else startAtLevel(currentLevel)

    /** Deja el cubo resuelto y encola una mezcla de [depth] giros para reproducirla animada. */
    private fun beginRound(depth: Int) {
        pendingScramble.clear()
        pendingScramble.addAll(buildScramble(depth))
        lastFrameNanos = null
        resetRoundHelpers()

        _state.value = HyperCubeGameState(
            cube = CubeState.solved(),
            activeTurn = null,
            moves = 0,
            scrambleDepth = depth,
            isScrambling = true,
            isFreeMode = freeMode,
            solved = false,
        )
    }

    /**
     * Restaura una partida guardada y la reanuda al instante (sin volver a mezclar: el cubo ya
     * viene desordenado desde el guardado).
     *
     * @return `true` si el guardado era válido y la partida quedó en marcha; `false` si estaba
     *   corrupto o era de un formato anterior, en cuyo caso no se toca nada y el llamador debe
     *   tratarlo como "no hay partida pendiente".
     */
    fun restore(saved: HyperCubeSavedState): Boolean {
        val cube = saved.cubies.toCubeStateOrNull() ?: return false
        val turns = saved.turns.map { it.toLayerTurnOrNull() ?: return false }

        currentLevel = saved.level.coerceIn(1, MAX_LEVEL)
        freeMode = saved.isFreeMode
        pendingScramble.clear()
        lastFrameNanos = null
        resetRoundHelpers()
        undoStack.addAll(turns)
        undosUsed = saved.undosUsed
        freeUndoUsed = saved.freeUndoUsed
        resumedOffsetMs = saved.elapsedMs.coerceAtLeast(0L)

        _state.value = HyperCubeGameState(
            cube = cube,
            activeTurn = null,
            moves = saved.moves.coerceAtLeast(0),
            scrambleDepth = saved.scrambleDepth,
            isScrambling = false,
            isFreeMode = saved.isFreeMode,
            solved = false,
            canUndo = turns.isNotEmpty(),
            undoCostsAd = saved.freeUndoUsed,
        )
        start() // el cronómetro base arranca de cero; el tiempo ya jugado lo pone resumedOffsetMs.
        return true
    }

    /**
     * Vuelca la partida en curso a su forma persistible, o `null` si no hay nada que guardar
     * (mezcla en marcha o cubo ya resuelto: retomar eso no significaría nada).
     */
    fun snapshot(): HyperCubeSavedState? {
        val s = _state.value
        if (s.isScrambling || s.solved) return null
        return HyperCubeSavedState(
            cubies = s.cube.toSaved(),
            turns = undoStack.map { it.toSaved() },
            level = currentLevel,
            isFreeMode = freeMode,
            moves = s.moves,
            scrambleDepth = s.scrambleDepth,
            elapsedMs = elapsedMs(),
            undosUsed = undosUsed,
            freeUndoUsed = freeUndoUsed,
        )
    }

    /** Deja a cero lo que es "por partida": pila de deshacer, ayudas usadas y desfase de reloj. */
    private fun resetRoundHelpers() {
        undoStack.clear()
        undoInFlight = false
        undosUsed = 0
        freeUndoUsed = false
        resumedOffsetMs = 0L
    }

    /** Al reanudar, el frame siguiente solo re-ancla el reloj: si no, el `dt` incluiría la pausa. */
    override fun onResume() {
        lastFrameNanos = null
    }

    /**
     * Tiempo de resolución de la partida en curso, en milisegundos y **descontando las pausas**
     * (lo garantiza el cronómetro de [BaseGameEngine]). Lo consulta el HUD cada frame.
     *
     * Devuelve 0 mientras se reproduce la mezcla: hasta que esta termina no se ha llamado a
     * `start()`, así que el cronómetro base todavía guarda el total de la partida ANTERIOR y lo
     * estaría enseñando como si fuera el de esta.
     */
    fun elapsedMs(): Long =
        if (_state.value.isScrambling) 0L else elapsedActive().inWholeMilliseconds + resumedOffsetMs

    // --------------------------------------------------------------- entrada del jugador

    /**
     * El jugador arrastró sobre una rebanada: intenta arrancar su giro de 90°.
     *
     * Se **ignora** en silencio (no es un fallo del jugador, es una petición fuera de tiempo) si
     * la partida no está en curso, si la mezcla sigue reproduciéndose, si ya hay un giro en vuelo
     * o si el cubo ya está resuelto. Encolar gestos sería peor: el jugador arrastra rápido y
     * acabaría viendo giros que ya no recuerda haber pedido.
     *
     * @param layer índice de capa; se recorta a `-1..1` por seguridad ante un cálculo de gestos
     *   fuera de rango en la capa de UI.
     */
    fun requestTurn(axis: Axis, layer: Int, direction: TurnDirection) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.isScrambling || s.activeTurn != null || s.solved) return

        val turn = LayerTurn(axis, layer.coerceIn(-1, 1), direction)
        _state.value = s.copy(activeTurn = ActiveTurn(turn))
        _effects.trySend(HyperCubeEffect.PlaySound(HyperCubeEffect.PlaySound.Cue.SLICE))
        _effects.trySend(HyperCubeEffect.Vibrate(HyperCubeEffect.Vibrate.Cue.TICK))
    }

    /**
     * Deshace la última jugada del jugador, animando el giro inverso.
     *
     * Solo alcanza a las jugadas propias: los giros de la mezcla no entran en la pila, porque
     * deshacer hasta "desmezclar" el cubo sería resolverlo con un botón.
     *
     * El primer deshacer de cada partida es gratis y el resto los cobra la capa superior con un
     * anuncio (ver [HyperCubeIntent.RequestUndo]); el motor no sabe de anuncios, solo lleva la
     * cuenta de si el gratuito ya se gastó ([HyperCubeGameState.undoCostsAd]) y de cuántas ayudas
     * se han usado, que es lo que penaliza el puntaje.
     *
     * @return `true` si se ha puesto en marcha el giro inverso.
     */
    fun undoLastTurn(): Boolean {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.isScrambling || s.activeTurn != null || s.solved) {
            return false
        }
        val last = undoStack.removeLastOrNull() ?: return false

        undoInFlight = true
        undosUsed++
        freeUndoUsed = true
        _state.value = s.copy(
            activeTurn = ActiveTurn(last.inverted()),
            canUndo = undoStack.isNotEmpty(),
            undoCostsAd = true,
        )
        _effects.trySend(HyperCubeEffect.PlaySound(HyperCubeEffect.PlaySound.Cue.SLICE))
        _effects.trySend(HyperCubeEffect.Vibrate(HyperCubeEffect.Vibrate.Cue.TICK))
        return true
    }

    // --------------------------------------------------------------- bucle de frames

    /**
     * Avanza la animación con el timestamp monotónico del frame.
     *
     * Deriva el `dt` de dos timestamps consecutivos (ver [HyperCubeIntent.Tick]) y descarta dos
     * casos degenerados: el primer frame tras arrancar/reanudar (sin referencia previa) y los
     * saltos anormalmente largos —app en segundo plano, GC—, que de otro modo completarían un
     * giro entero de una sola vez.
     */
    fun onFrame(frameNanos: Long) {
        val s = _state.value
        // Autorización deliberadamente NO basada solo en el status: durante la mezcla el ciclo de
        // vida aún no es RUNNING (el cronómetro no debe correr) pero la animación sí debe avanzar.
        if (!s.isScrambling && status.value != GameStatus.RUNNING) return

        val previous = lastFrameNanos
        lastFrameNanos = frameNanos
        if (previous == null) return

        val dtMs = (frameNanos - previous) / 1_000_000f
        if (dtMs <= 0f || dtMs > MAX_FRAME_MS) return

        advance(dtMs)
    }

    /** Un paso de simulación: progresa el giro en vuelo o encadena el siguiente de la mezcla. */
    private fun advance(dtMs: Float) {
        val s = _state.value
        val active = s.activeTurn
        if (active == null) {
            if (s.isScrambling) startNextScrambleTurn(s)
            return
        }

        val durationMs = if (s.isScrambling) SCRAMBLE_TURN_MS else PLAYER_TURN_MS
        val progress = active.progress + dtMs / durationMs
        if (progress < 1f) {
            _state.value = s.copy(activeTurn = active.copy(progress = progress))
        } else {
            completeTurn(s, active.turn)
        }
    }

    /** Saca el siguiente giro de la cola de mezcla; si no quedaba ninguno, cierra la mezcla. */
    private fun startNextScrambleTurn(s: HyperCubeGameState) {
        val next = pendingScramble.removeFirstOrNull()
        if (next == null) {
            finishScramble(s)
        } else {
            _state.value = s.copy(activeTurn = ActiveTurn(next))
        }
    }

    /**
     * Cierra un giro: aplica la permutación exacta al cubo y decide qué pasa después.
     *
     * Los giros de la mezcla **no cuentan como jugadas** ni emiten feedback (una ráfaga de
     * "clacks" sería exactamente el ruido que el §9 del sistema de diseño pide evitar); los del
     * jugador incrementan el contador, suenan y disparan la comprobación de victoria.
     */
    private fun completeTurn(s: HyperCubeGameState, turn: LayerTurn) {
        val cube = s.cube.applyTurn(turn)

        if (s.isScrambling) {
            _state.value = s.copy(cube = cube, activeTurn = null)
            if (pendingScramble.isEmpty()) finishScramble(_state.value)
            return
        }

        // Un "deshacer" no suma jugada: resta la que borra. El coste de haber usado la ayuda no
        // vive en este contador —que debe seguir describiendo la posición real del jugador— sino
        // en [undosUsed], que penaliza el puntaje (ver [calculateScore]).
        val wasUndo = undoInFlight
        undoInFlight = false
        if (!wasUndo) undoStack.addLast(turn)

        val solved = cube.isSolved
        _state.value = s.copy(
            cube = cube,
            activeTurn = null,
            moves = if (wasUndo) (s.moves - 1).coerceAtLeast(0) else s.moves + 1,
            solved = solved,
            canUndo = undoStack.isNotEmpty(),
        )
        _effects.trySend(HyperCubeEffect.PlaySound(HyperCubeEffect.PlaySound.Cue.CLACK))
        _effects.trySend(HyperCubeEffect.Vibrate(HyperCubeEffect.Vibrate.Cue.CLACK))

        if (solved) {
            _effects.trySend(HyperCubeEffect.PlaySound(HyperCubeEffect.PlaySound.Cue.SOLVED))
            _effects.trySend(HyperCubeEffect.Vibrate(HyperCubeEffect.Vibrate.Cue.SOLVED))
            finish() // publica el GameResult en `outcome`; el ViewModel lo persiste.
        }
    }

    /**
     * Fin de la mezcla: el cubo queda listo, se marca el arranque del cronómetro y suena **un
     * solo** golpe que le dice al jugador "ahora te toca a ti".
     */
    private fun finishScramble(s: HyperCubeGameState) {
        _state.value = s.copy(activeTurn = null, isScrambling = false, moves = 0)
        _effects.trySend(HyperCubeEffect.PlaySound(HyperCubeEffect.PlaySound.Cue.CLACK))
        _effects.trySend(HyperCubeEffect.Vibrate(HyperCubeEffect.Vibrate.Cue.CLACK))
        start() // BaseGameEngine: reloj a cero y status RUNNING.
    }

    // --------------------------------------------------------------- mezcla

    /**
     * Genera una secuencia aleatoria de [depth] giros que deja el cubo realmente desordenado.
     *
     * Dos reglas, cada una por un motivo distinto:
     *
     *  1. **Solo capas exteriores** (`-1` y `1`, nunca la central). Girar la rebanada central
     *     equivale a girar una cara y además rotar el cubo entero: no añade dificultad real y sí
     *     desalinea los centros de color, lo que desorienta a quien está aprendiendo. El jugador
     *     **sí** puede girarla —es un cubo de verdad—, y por eso el detector de victoria es
     *     invariante a la orientación (ver [CubeState.isSolved]).
     *  2. **Nunca dos giros seguidos sobre la misma capa.** Evita de un plumazo que el segundo
     *     anule al primero (perdiendo profundidad de mezcla) y que tres seguidos equivalgan a uno
     *     inverso. Repetir eje con la capa opuesta sí se permite: son giros independientes.
     *
     * Como red de seguridad se descarta la secuencia si por casualidad devuelve el cubo a un
     * estado resuelto (probabilísticamente insignificante salvo con profundidades mínimas, pero
     * empezar un nivel ya resuelto sería un bug grosero).
     */
    private fun buildScramble(depth: Int): List<LayerTurn> {
        repeat(MAX_SCRAMBLE_ATTEMPTS) {
            val turns = randomTurns(depth)
            val scrambled = turns.fold(CubeState.solved()) { cube, turn -> cube.applyTurn(turn) }
            if (!scrambled.isSolved) return turns
        }
        // Repliegue teórico: una mezcla mínima garantizada distinta del estado resuelto.
        return listOf(LayerTurn(Axis.Y, 1, TurnDirection.CLOCKWISE))
    }

    /** Secuencia aleatoria con la regla "no repetir la capa anterior" (ver [buildScramble]). */
    private fun randomTurns(depth: Int): List<LayerTurn> {
        val turns = ArrayList<LayerTurn>(depth)
        var lastAxis: Axis? = null
        var lastLayer = 0
        repeat(depth) {
            var axis: Axis
            var layer: Int
            do {
                axis = AXES[random.nextInt(AXES.size)]
                layer = FACE_LAYERS[random.nextInt(FACE_LAYERS.size)]
            } while (axis == lastAxis && layer == lastLayer)

            val direction =
                if (random.nextBoolean()) TurnDirection.CLOCKWISE else TurnDirection.COUNTER_CLOCKWISE
            turns += LayerTurn(axis, layer, direction)
            lastAxis = axis
            lastLayer = layer
        }
        return turns
    }

    // --------------------------------------------------------------- puntuación

    /**
     * Puntuación = **nivel + eficiencia + rapidez**, en ese orden de peso.
     *
     * ```
     * score = nivel·LEVEL_POINTS  +  EFFICIENCY_BONUS·eficiencia  +  SPEED_BONUS·rapidez
     * ```
     *
     *  - **Nivel**: el grueso del puntaje. Un nivel más profundo es objetivamente más difícil, así
     *    que domina sobre el estilo con el que se resolvió.
     *  - **Eficiencia** (ver [efficiencyRatio]): premia resolver con pocos movimientos. Es la
     *    métrica que distingue "lo pensé" de "lo giré hasta que salió".
     *  - **Rapidez**: fracción del tiempo objetivo que sobró, saturada en `[0, 1]`. Cuenta menos
     *    que la eficiencia a propósito: este es un juego de visión espacial, no de reflejos, y
     *    premiar la prisa por encima del razonamiento iría contra la habilidad que entrena.
     *
     * Devuelve 0 si se llama sin cubo resuelto (defensivo: `finish()` solo se invoca al resolver).
     */
    override fun calculateScore(): Int {
        val s = _state.value
        if (!s.solved) return 0

        val targetSeconds = max(1, s.scrambleDepth) * TARGET_SECONDS_PER_TURN
        val elapsedSeconds = max(elapsedMs(), 1L) / 1000.0
        val speed = (targetSeconds / elapsedSeconds).coerceIn(0.0, 1.0)

        // El modo libre no tiene nivel, pero es al menos tan exigente como el último de la rampa:
        // se le acredita esa base para que su puntaje sea comparable y no salga artificialmente bajo.
        val levelPoints = if (freeMode) MAX_LEVEL * LEVEL_POINTS else currentLevel * LEVEL_POINTS

        // Las ayudas se pagan: cada deshacer descuenta, igual que las pistas en Neon Sudoku. Sin
        // esto, deshacer sería gratis y la eficiencia dejaría de medir nada —bastaría con probar
        // giros y retroceder hasta dar con la solución sin coste alguno—.
        val score = levelPoints + EFFICIENCY_BONUS * efficiencyRatio() + SPEED_BONUS * speed -
            undosUsed * UNDO_PENALTY
        return score.toInt().coerceAtLeast(0)
    }

    /**
     * Precisión = eficiencia de la solución (0..100).
     *
     * En este juego no existen "fallos" —ningún giro es ilegal—, así que la medida natural de
     * calidad no es acertar sino **cuánto se acercó el jugador a la solución corta**. Ver
     * [efficiencyRatio].
     */
    override fun currentAccuracy(): Double = efficiencyRatio() * 100.0

    /**
     * Récord = nivel resuelto (la partida solo termina al resolver el cubo).
     *
     * `null` en modo libre: no hay nivel que comparar, y devolver uno inventado corrompería el
     * récord de progresión con partidas que no pertenecen a la rampa.
     */
    override fun reachedMetric(): Int? = if (freeMode) null else currentLevel

    /**
     * Eficiencia en `[0, 1]`: `profundidad_de_la_mezcla / movimientos_usados`.
     *
     * La profundidad de la mezcla es una **cota superior del óptimo**: deshacer la mezcla al revés
     * siempre resuelve el cubo en exactamente esos movimientos (y a veces existe una solución aún
     * más corta). Usarla como referencia da un baremo honesto y baratísimo: resolver en tantos
     * giros como tenía la mezcla puntúa 1.0, y el ratio decae suavemente al dar vueltas de más.
     * Calcular el óptimo real exigiría un solver completo — desproporcionado y, sobre todo,
     * injusto: castigaría por no encontrar una secuencia que ningún humano ve.
     */
    private fun efficiencyRatio(): Double {
        val s = _state.value
        val reference = s.scrambleDepth
        if (reference <= 0) return 1.0
        return (reference.toDouble() / max(s.moves, reference)).coerceIn(0.0, 1.0)
    }

    private companion object {
        /** Duración de un giro pedido por el jugador. Rápido pero legible (§9.4: 100–250 ms). */
        const val PLAYER_TURN_MS = 180f

        /** Los giros de la mezcla van más deprisa: son espectáculo, no jugadas que seguir. */
        const val SCRAMBLE_TURN_MS = 110f

        /**
         * Delta máximo aceptado entre frames. Por encima (app en segundo plano, GC largo) se
         * descarta el frame: mejor perder una animación que teletransportar una capa.
         */
        const val MAX_FRAME_MS = 100f

        /** Puntos por nivel resuelto: el componente dominante del score. */
        const val LEVEL_POINTS = 500

        /** Puntos máximos por resolver con el mínimo de movimientos. */
        const val EFFICIENCY_BONUS = 600.0

        /** Puntos máximos por resolver dentro del tiempo objetivo. */
        const val SPEED_BONUS = 300.0

        /**
         * Descuento por cada deshacer. Del orden del bonus que se gana con un par de movimientos
         * bien pensados: se nota, pero no convierte la ayuda en un castigo que nadie usaría.
         */
        const val UNDO_PENALTY = 100.0

        /**
         * Segundos "de regalo" por cada giro de la mezcla. Con 8 s por giro, un nivel de 6 giros
         * da 48 s para el bonus completo: holgado para pensar, exigente si se va a ciegas.
         */
        const val TARGET_SECONDS_PER_TURN = 8.0

        /** Intentos de generar una mezcla no resuelta antes de recurrir al repliegue. */
        const val MAX_SCRAMBLE_ATTEMPTS = 4

        /** Capas mezclables: solo las exteriores (ver [buildScramble]). */
        val FACE_LAYERS = intArrayOf(-1, 1)

        /** Ejes disponibles, cacheados para no crear el array de `values()` en cada sorteo. */
        val AXES = Axis.entries.toTypedArray()
    }
}

/**
 * Profundidad de la mezcla del nivel [level]: el nivel 1 baraja con 2 giros y cada nivel añade
 * uno, hasta los 9 del último ([MAX_LEVEL]).
 *
 * ## Por qué la rampa es corta (y por qué existe el modo libre)
 * El puzzle de los niveles es **"deshaz la mezcla"**, al estilo de un "mate en 3": el jugador ve
 * un cubo casi resuelto, deduce qué le hicieron y lo revierte. Ese ejercicio *sí* es visión
 * espacial y la dificultad se nota giro a giro… mientras la mezcla sea corta.
 *
 * Pasados ~8-10 giros aleatorios el cubo queda, para un humano, **completamente mezclado** (un
 * estado aleatorio necesita del orden de 18 cuartos de vuelta). A partir de ahí dos niveles
 * consecutivos serían indistinguibles y el juego dejaría de medir visión espacial para exigir
 * *saber resolver un Rubik* de memoria: un muro, no una rampa. Por eso la progresión se detiene
 * en [MAX_LEVEL] y el cubo entero mezclado se ofrece aparte, como **modo libre** sin nivel
 * ([HyperCubeEngine.startFreeMode]), para quien busque ese reto.
 */
fun scrambleDepthFor(level: Int): Int = level.coerceIn(1, MAX_LEVEL) + 1

/** Último nivel de la progresión; su mezcla es de 9 giros (ver [scrambleDepthFor]). */
const val MAX_LEVEL = 8

/**
 * Valor de `difficultyLevel` con el que se registran las partidas del **modo libre**.
 *
 * Va justo detrás del último nivel para que caigan en un universo de ranking propio (ver
 * `GameRankingScopes`) en vez de mezclarse con las de un nivel de la rampa, que no son
 * comparables: el modo libre baraja el cubo a fondo.
 */
const val FREE_MODE_DIFFICULTY = MAX_LEVEL + 1

/**
 * Giros de la mezcla del modo libre: suficientes para dejar el cubo en un estado prácticamente
 * aleatorio (más allá de esto no se gana desorden, solo tiempo de animación).
 */
const val FREE_SCRAMBLE_DEPTH = 20
