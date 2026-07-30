package com.kortexgames.app.game.neonpulse

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * # Neon Pulse — Motor de tiempo (ViewModel, FASE 2)
 *
 * ViewModel MVI del entrenador visomotor. A diferencia del resto de juegos (que
 * delegan en un `BaseGameEngine`), aquí el **bucle de juego vive en el propio
 * ViewModel**: así lo pide el contrato (ver [NeonPulseIntent.Tick]), donde el
 * pulso del reloj es una intención más del ciclo MVI unidireccional. El ViewModel
 * es la única fuente que emite [NeonPulseIntent.Tick], igual que el jugador es la
 * única fuente de los taps.
 *
 * Responsabilidades:
 *  - Conducir el **game loop** ([loopJob]) que emite ticks con el delta real.
 *  - **Reducir** cada [NeonPulseIntent.Tick]: envejecer nodos, retirar expirados
 *    (perdiendo vida si eran normales), agendar spawns según la cadencia y
 *    consumir el reloj de partida.
 *  - Validar la **colisión** de los taps ([NeonPulseIntent.TapNode] / [TapMiss]).
 *  - Persistir el [GameResult] con estrategia local-first al terminar.
 *
 * @param progress repositorio local-first para guardar el resultado + percentil.
 * @param audio manager de sonido/háptica (el feedback fino se emite como [NeonPulseEffect]).
 * @param difficulty dificultad de inicio 1..5 (se reporta en el [GameResult]).
 * @param random inyectable para pruebas deterministas de spawn/colisión.
 */
class NeonPulseViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    private val difficulty: Int = 1,
    private val random: Random = Random.Default,
) : MviViewModel<NeonPulseIntent, NeonPulseUiState, NeonPulseEffect>(NeonPulseUiState()) {

    // --- Estado interno de la simulación (NO es estado de UI) --------------------
    // Se mantiene fuera del UiState porque son detalles del motor: la UI no los
    // dibuja y meterlos en el State solo forzaría recomposiciones inútiles.

    /** Corrutina del bucle de juego; cancelable en pausa y al terminar. */
    private var loopJob: Job? = null

    /**
     * Marca monotónica del último frame procesado. Fuente del **delta real** entre
     * ticks. Se usa [TimeSource.Monotonic] (no el reloj de pared) por ser preciso,
     * monótono e inmune a cambios de hora/zona horaria del dispositivo.
     */
    private var lastMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** Acumulador para agendar spawns: suma el delta y "dispara" un nodo cada vez
     *  que supera el intervalo de cadencia vigente ([SpawnCadence]). */
    private var spawnAccumulatorMs = 0L

    /** Contador monotónico para asignar [Node.id] únicos y estables. */
    private var nextNodeId = 0L

    // Estadísticas para puntuación, combo y precisión final.
    private var hits = 0            // nodos normales acertados
    private var misses = 0          // trampas tocadas + normales expirados + toques al vacío
    private var combo = 0           // aciertos consecutivos (se rompe con cualquier error)
    private var maxCombo = 0        // mejor racha (métrica de récord)

    override fun onIntent(intent: NeonPulseIntent) {
        when (intent) {
            NeonPulseIntent.Start,
            NeonPulseIntent.PlayAgain -> startGame()
            NeonPulseIntent.Pause -> pause()
            NeonPulseIntent.Resume -> resume()
            is NeonPulseIntent.TapNode -> onTapNode(intent.id)
            NeonPulseIntent.TapMiss -> onTapMiss()
            is NeonPulseIntent.Tick -> onTick(intent.deltaMillis)
        }
    }

    // ---------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------

    /** Reinicia todo el estado y arranca una partida limpia. */
    private fun startGame() {
        spawnAccumulatorMs = 0L
        nextNodeId = 0L
        hits = 0; misses = 0; combo = 0; maxCombo = 0
        setState {
            NeonPulseUiState(
                lives = NeonPulseConfig.INITIAL_LIVES,
                remainingMs = NeonPulseConfig.GAME_DURATION_MS,
                status = GameStatus.RUNNING,
            )
        }
        startLoop()
    }

    /** Congela el juego: cancela el loop y marca PAUSED. Los anillos dejan de
     *  contraerse porque nadie emite ticks mientras tanto. */
    private fun pause() {
        if (currentState.status != GameStatus.RUNNING) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.PAUSED) }
    }

    /** Reanuda tras pausa. Reinicia [lastMark] para que el primer delta NO incluya
     *  el tiempo que el juego estuvo pausado (si no, todos los anillos saltarían). */
    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        setState { copy(status = GameStatus.RUNNING) }
        startLoop()
    }

    /**
     * ## El bucle de juego, el delta y la concurrencia (el "porqué")
     *
     * El loop es **una sola corrutina** en `viewModelScope`. En cada iteración:
     *  1. `delay(FRAME_MS)` — **suspende** (no bloquea) ~16 ms. Al ser suspensión
     *     cooperativa, el hilo principal queda libre y la UI sigue fluida; nada de
     *     trabajo bloqueante (CLAUDE.md §Coroutines).
     *  2. Calcula el **delta real** con [TimeSource.Monotonic]: `lastMark.elapsedNow()`.
     *     Nunca asumimos que pasaron exactamente 16 ms — el scheduler puede
     *     retrasar el `delay`—, así que medimos el tiempo transcurrido de verdad.
     *     Esto hace la simulación independiente del framerate y del jitter.
     *  3. Emite el delta como **intención** `Tick(delta)` (contrato MVI): el mismo
     *     `onIntent` que procesa los taps procesa el tick.
     *
     * **Concurrencia sin locks:** `viewModelScope` despacha en `Main.immediate`, así
     * que ticks y taps se ejecutan **confinados al mismo hilo** y se serializan de
     * forma natural; el reducer nunca compite consigo mismo y no hacen falta mutex
     * ni estructuras concurrentes. El `StateFlow.update` de [setState] es además
     * atómico. La pausa/parada simplemente cancela [loopJob] (cooperativo en el
     * `delay`), deteniendo la emisión de ticks al instante.
     */
    private fun startLoop() {
        loopJob?.cancel()
        lastMark = TimeSource.Monotonic.markNow()
        loopJob = viewModelScope.launch {
            while (isActive) {
                delay(FRAME_MS)
                val delta = lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                lastMark = TimeSource.Monotonic.markNow()
                if (delta > 0L) onIntent(NeonPulseIntent.Tick(delta))
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Reducción del Tick (envejecer nodos, expirar, spawnear, cronómetro)
    // ---------------------------------------------------------------------------

    private fun onTick(deltaMillis: Long) {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return

        // 1) Consumir el reloj de partida (nunca por debajo de 0).
        val remaining = (s.remainingMs - deltaMillis).coerceAtLeast(0L)
        val elapsed = NeonPulseConfig.GAME_DURATION_MS - remaining

        // 2) Envejecer nodos y separar los que expiran en este frame.
        val survivors = ArrayList<Node>(s.activeNodes.size)
        var livesLost = 0
        for (node in s.activeNodes) {
            val left = node.remainingMs - deltaMillis
            if (left > 0L) {
                survivors += node.copy(remainingMs = left)
            } else if (node.type == NodeType.NORMAL) {
                // Dejar expirar un objetivo normal cuesta una vida y rompe el combo.
                livesLost++
            }
            // Las trampas expiradas desaparecen sin penalización (comportamiento deseado).
        }
        if (livesLost > 0) {
            misses += livesLost
            combo = 0
            // Feedback de "vida perdida": se emite como efecto one-shot (no se
            // acopla la UI ni se reproduce sonido dentro del reducer).
            sendEffect(NeonPulseEffect.PlaySound.Error)
            sendEffect(NeonPulseEffect.Vibrate.Heavy)
        }

        // 3) Agendar spawns según la cadencia vigente (dificultad progresiva).
        spawnAccumulatorMs += deltaMillis
        val interval = SpawnCadence.intervalFor(elapsed)
        while (spawnAccumulatorMs >= interval && remaining > 0L) {
            spawnAccumulatorMs -= interval
            spawnNode(elapsed, survivors)?.let { survivors += it }
        }

        // 4) Publicar el nuevo estado.
        val newLives = (s.lives - livesLost).coerceAtLeast(0)
        setState { copy(activeNodes = survivors, remainingMs = remaining, lives = newLives) }

        // 5) Condiciones de fin: sin tiempo o sin vidas.
        if (remaining <= 0L || newLives <= 0) finish()
    }

    /**
     * Genera un nodo en coordenadas aleatorias **sin superponerse** con los ya
     * activos. Se prueban hasta [MAX_SPAWN_TRIES] posiciones; si ninguna queda
     * libre (lienzo muy poblado), se **omite** el spawn en vez de forzar un
     * solapamiento —preferimos saltar una aparición antes que apilar nodos que la
     * UI no podría desambiguar al tocar—.
     *
     * El tipo se decide aquí: pasado [NeonPulseConfig.TRAP_UNLOCK_MS], con
     * probabilidad [NeonPulseConfig.TRAP_SPAWN_CHANCE] el nodo es trampa.
     *
     * @param elapsedMs tiempo de partida transcurrido (habilita/decide trampas).
     * @param existing nodos ya presentes contra los que comprobar la distancia.
     * @return el nodo colocado, o `null` si no se encontró hueco.
     */
    private fun spawnNode(elapsedMs: Long, existing: List<Node>): Node? {
        val r = NeonPulseConfig.NODE_RADIUS
        // Margen horizontal/inferior para que el nodo no quede cortado por el borde
        // del lienzo; el margen superior es mayor para no aparecer bajo el HUD/botón
        // de pausa (CLAUDE.md: nunca tapar controles fijos con contenido jugable).
        val minX = r
        val maxX = 1f - r
        val minY = maxOf(r, NeonPulseConfig.TOP_SPAWN_MARGIN)
        val maxY = 1f - r
        // Pasado FAST_LIFE_UNLOCK_MS los nodos viven menos tiempo encendidos: último
        // escalón de dificultad, exige reacciones más rápidas.
        val lifeMs = if (elapsedMs >= NeonPulseConfig.FAST_LIFE_UNLOCK_MS) {
            NeonPulseConfig.NODE_LIFE_FAST_MS
        } else {
            NeonPulseConfig.NODE_LIFE_MS
        }
        repeat(MAX_SPAWN_TRIES) {
            val x = minX + random.nextFloat() * (maxX - minX)
            val y = minY + random.nextFloat() * (maxY - minY)
            if (existing.none { overlaps(it, x, y, r) }) {
                val isTrap = elapsedMs >= NeonPulseConfig.TRAP_UNLOCK_MS &&
                    random.nextFloat() < NeonPulseConfig.TRAP_SPAWN_CHANCE
                return Node(
                    id = nextNodeId++,
                    type = if (isTrap) NodeType.TRAP else NodeType.NORMAL,
                    x = x,
                    y = y,
                    radius = r,
                    totalLifeMs = lifeMs,
                    remainingMs = lifeMs,
                )
            }
        }
        return null
    }

    /** ¿El centro (x,y) con radio r se solapa con [node]? Distancia entre centros
     *  menor que la suma de radios (más un pequeño respiro para que no se toquen). */
    private fun overlaps(node: Node, x: Float, y: Float, r: Float): Boolean {
        val dx = node.x - x
        val dy = node.y - y
        val minDist = node.radius + r + SPAWN_PADDING
        return sqrt(dx * dx + dy * dy) < minDist
    }

    // ---------------------------------------------------------------------------
    // Colisión del toque (Hit / Miss)
    // ---------------------------------------------------------------------------

    /**
     * El jugador tocó dentro del nodo [id] (el hit-testing geométrico lo resolvió el
     * `Canvas`, FASE 3). Aquí solo aplicamos la **semántica**:
     *  - Nodo normal → acierto: puntúa con multiplicador de combo, refuerza racha y
     *    pide la animación de explosión ([NeonPulseEffect.ShowComboAnim]).
     *  - Nodo trampa → error: penaliza (vida + combo) con feedback fuerte.
     *  - Id ya ausente (expiró en el mismo frame) → se ignora sin penalizar.
     */
    private fun onTapNode(id: Long) {
        if (currentState.status != GameStatus.RUNNING) return
        val node = currentState.activeNodes.firstOrNull { it.id == id } ?: return
        val remaining = currentState.activeNodes.filterNot { it.id == id }

        when (node.type) {
            NodeType.NORMAL -> {
                hits++
                combo++
                if (combo > maxCombo) maxCombo = combo
                val gained = NeonPulseConfig.POINTS_PER_HIT * comboMultiplier()
                setState { copy(activeNodes = remaining, score = score + gained) }
                sendEffect(NeonPulseEffect.PlaySound.Hit)
                sendEffect(NeonPulseEffect.Vibrate.Tick)
                sendEffect(NeonPulseEffect.ShowComboAnim(node.x, node.y))
            }
            NodeType.TRAP -> {
                misses++
                combo = 0
                val newLives = (currentState.lives - 1).coerceAtLeast(0)
                setState { copy(activeNodes = remaining, lives = newLives) }
                sendEffect(NeonPulseEffect.PlaySound.Error)
                sendEffect(NeonPulseEffect.Vibrate.Heavy)
                if (newLives <= 0) finish()
            }
        }
    }

    /**
     * Toque al vacío (ningún nodo bajo el dedo). Rompe el combo y cuenta como fallo
     * de precisión, pero NO resta vida: penalizar la vida por un roce impreciso
     * sería demasiado severo para un juego de reflejos. Sin feedback sonoro para no
     * ensuciar el audio con cada toque errante.
     */
    private fun onTapMiss() {
        if (currentState.status != GameStatus.RUNNING) return
        misses++
        combo = 0
    }

    /** Multiplicador de puntuación por racha: +1 cada [COMBO_STEP] aciertos
     *  seguidos (1×, 2×, 3×…). Recompensa mantener la precisión sin fallar. */
    private fun comboMultiplier(): Int = 1 + combo / COMBO_STEP

    // ---------------------------------------------------------------------------
    // Fin de partida (local-first)
    // ---------------------------------------------------------------------------

    /** Cierra la partida, detiene el loop y persiste el resultado. Idempotente:
     *  si ya está FINISHED no hace nada (evita doble guardado por tiempo + vidas). */
    private fun finish() {
        if (currentState.status == GameStatus.FINISHED) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.FINISHED, activeNodes = emptyList()) }

        val elapsed = NeonPulseConfig.GAME_DURATION_MS - currentState.remainingMs
        val attempts = hits + misses
        val result = GameResult(
            gameId = GameIds.NEON_PULSE,
            score = currentState.score,
            completionTimeMs = elapsed,
            accuracyPercentage = if (attempts == 0) 100.0 else hits.toDouble() / attempts * 100.0,
            difficultyLevel = difficulty,
            reachedMetric = maxCombo, // récord = mejor racha de aciertos alcanzada
        )
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }

    private companion object {
        /** Periodo objetivo del loop (~60 fps). El delta real se mide con el reloj
         *  monotónico, así que este valor solo fija la frecuencia de muestreo. */
        const val FRAME_MS = 16L

        /** Intentos de recolocación antes de omitir un spawn por falta de hueco. */
        const val MAX_SPAWN_TRIES = 20

        /** Separación mínima extra (espacio normalizado) entre nodos al spawnear. */
        const val SPAWN_PADDING = 0.02f

        /** Aciertos consecutivos necesarios para subir un escalón de multiplicador. */
        const val COMBO_STEP = 5
    }
}
