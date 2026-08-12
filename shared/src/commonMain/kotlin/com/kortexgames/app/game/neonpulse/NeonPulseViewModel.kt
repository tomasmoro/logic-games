package com.kortexgames.app.game.neonpulse

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * # Neon Pulse — Motor de tiempo (ViewModel)
 *
 * ViewModel MVI del entrenador visomotor. A diferencia del resto de juegos (que
 * delegan en un `BaseGameEngine`), aquí el **bucle de juego vive en el propio
 * ViewModel**: así lo pide el contrato (ver [NeonPulseIntent.Tick]), donde el
 * pulso del reloj es una intención más del ciclo MVI unidireccional. El ViewModel
 * es la única fuente que emite [NeonPulseIntent.Tick], igual que el jugador es la
 * única fuente de los taps.
 *
 * ## Partida infinita por hordas
 * No hay reloj de partida: se juega **hasta quedarse sin vidas**. El contenido se
 * sirve en hordas ([WaveSpec]) que el motor encadena solas:
 *
 * 1. **Cartel** de horda ([NeonPulseUiState.waveBannerMs]) — respiro sin nodos.
 * 2. **Oleada**: se sueltan [WaveSpec.nodeCount] nodos con la cadencia y la vida
 *    de esa horda; desde [NeonPulseConfig.MOVE_UNLOCK_WAVE] además se desplazan y
 *    rebotan contra los bordes.
 * 3. **Cierre**: cuando ya no queda nada por generar ni nada vivo en el lienzo, se
 *    concede el bonus de horda y se empieza la siguiente, un punto más difícil.
 *
 * Toda la rampa (cuántos nodos, cada cuánto, cuánto duran, trampas, velocidad y
 * corazones de rescate) se resuelve en [WaveSpec.forWave]; el motor solo la aplica.
 *
 * Al agotar las vidas, la primera vez se ofrece **revivir viendo un anuncio**
 * ([NeonPulseUiState.awaitingRevive], ver [loseAllLives]) antes de dar la partida
 * por terminada; solo se ofrece una vez por partida.
 *
 * Responsabilidades:
 *  - Conducir el **game loop** ([loopJob]) que emite ticks con el delta real.
 *  - **Reducir** cada [NeonPulseIntent.Tick]: envejecer y mover nodos, retirar
 *    expirados (perdiendo vida si eran normales), agendar spawns y encadenar hordas.
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

    /** Parámetros de la horda en curso (rampa ya resuelta). */
    private var spec: WaveSpec = WaveSpec.forWave(1)

    /** Nodos que quedan por soltar en la horda actual. */
    private var pendingSpawns = 0

    /** Nodos ya soltados en la horda actual; sitúa el momento del corazón. */
    private var spawnedInWave = 0

    /** ¿Queda por soltar el corazón de rescate de esta horda? */
    private var heartPending = false

    /** Acumulador para agendar spawns: suma el delta y "dispara" un nodo cada vez
     *  que supera [WaveSpec.spawnIntervalMs]. */
    private var spawnAccumulatorMs = 0L

    /** Contador monotónico para asignar [Node.id] únicos y estables. */
    private var nextNodeId = 0L

    /** Duración real de la partida; sustituye al antiguo reloj de cuenta atrás como
     *  `completionTimeMs` del resultado (una partida infinita se mide por lo que
     *  aguantas, no por un tiempo fijado de antemano). */
    private var elapsedMs = 0L

    // Estadísticas para puntuación, combo y precisión final.
    private var hits = 0            // nodos normales acertados
    private var misses = 0          // trampas tocadas + normales expirados + toques al vacío
    private var combo = 0           // aciertos consecutivos (se rompe con cualquier error)
    private var maxCombo = 0        // mejor racha (métrica secundaria)

    /** ¿Ya se ofreció (aceptada o no) la segunda oportunidad de esta partida? Solo
     *  se ofrece una vez: sin este flag, revivir una y otra vez anularía el sentido
     *  de las vidas. Ver [loseAllLives]. */
    private var reviveOffered = false

    init {
        // Comparativa mundial para la antesala (ver KDoc de `NeonPulseUiState.rankingPreview`).
        // Un único pedido basta: el juego no vuelve a IDLE tras jugar dentro de la
        // misma visita (empezar de nuevo salta directo a RUNNING), así que no hay
        // que refrescarlo.
        viewModelScope.launch {
            val ranking = progress.previewRanking(GameIds.NEON_PULSE)
            setState { copy(rankingPreview = ranking, rankingPreviewLoading = false) }
        }
    }

    override fun onIntent(intent: NeonPulseIntent) {
        when (intent) {
            NeonPulseIntent.Start,
            NeonPulseIntent.PlayAgain -> startGame()
            NeonPulseIntent.Pause -> pause()
            NeonPulseIntent.Resume -> resume()
            NeonPulseIntent.Revive -> grantRevive()
            NeonPulseIntent.DeclineRevive -> declineRevive()
            is NeonPulseIntent.TapNode -> onTapNode(intent.id)
            NeonPulseIntent.TapMiss -> onTapMiss()
            is NeonPulseIntent.Tick -> onTick(intent.deltaMillis)
        }
    }

    // ---------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------

    /** Reinicia todo el estado y arranca una partida limpia en la horda 1. */
    private fun startGame() {
        nextNodeId = 0L
        elapsedMs = 0L
        hits = 0; misses = 0; combo = 0; maxCombo = 0
        reviveOffered = false
        setState {
            NeonPulseUiState(
                lives = NeonPulseConfig.INITIAL_LIVES,
                status = GameStatus.RUNNING,
            )
        }
        beginWave(1)
        startLoop()
    }

    /**
     * Prepara la horda [wave]: resuelve su rampa, reinicia los contadores de oleada
     * y muestra el cartel. Durante el cartel el motor no genera nada, así que el
     * jugador entra en cada horda con el lienzo limpio y sabiendo a qué se enfrenta.
     */
    private fun beginWave(wave: Int) {
        spec = WaveSpec.forWave(wave)
        pendingSpawns = spec.nodeCount
        spawnedInWave = 0
        heartPending = spec.offersHeart
        // El acumulador arranca "lleno" para que el primer nodo salga en cuanto el
        // cartel se retira, sin un silencio extra al principio de cada horda.
        spawnAccumulatorMs = spec.spawnIntervalMs
        setState {
            copy(
                wave = wave,
                waveNodesTotal = spec.nodeCount,
                waveNodesResolved = 0,
                waveBannerMs = NeonPulseConfig.WAVE_BANNER_MS,
            )
        }
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
    // Reducción del Tick (mover, envejecer, expirar, spawnear, encadenar hordas)
    // ---------------------------------------------------------------------------

    private fun onTick(deltaMillis: Long) {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return
        elapsedMs += deltaMillis

        // 0) Cartel entre hordas: solo corre su cuenta atrás. El lienzo está vacío
        //    por definición (una horda no se cierra hasta que no queda ni un nodo),
        //    así que no hay nada más que simular durante el respiro.
        if (s.waveBannerMs > 0L) {
            setState { copy(waveBannerMs = (waveBannerMs - deltaMillis).coerceAtLeast(0L)) }
            return
        }

        // 1) Envejecer/desplazar nodos y separar los que expiran en este frame.
        val survivors = ArrayList<Node>(s.activeNodes.size)
        var livesLost = 0
        var resolved = 0
        for (node in s.activeNodes) {
            val left = node.remainingMs - deltaMillis
            if (left > 0L) {
                survivors += advance(node, deltaMillis).copy(remainingMs = left)
                continue
            }
            when (node.type) {
                // Dejar expirar un objetivo normal cuesta una vida y rompe el combo.
                NodeType.NORMAL -> { livesLost++; resolved++ }
                // Las trampas expiradas desaparecen sin penalización (es lo deseado:
                // la trampa se gana ignorándola), pero sí cuentan como resueltas.
                NodeType.TRAP -> resolved++
                // El corazón no forma parte del cupo de la horda: ni penaliza ni suma
                // al progreso, solo se pierde la oportunidad.
                NodeType.HEART -> Unit
            }
        }
        if (livesLost > 0) {
            misses += livesLost
            combo = 0
            // Feedback de "vida perdida": se emite como efecto one-shot (no se
            // acopla la UI ni se reproduce sonido dentro del reducer).
            sendEffect(NeonPulseEffect.PlaySound.Error)
            sendEffect(NeonPulseEffect.Vibrate.Heavy)
        }

        val newLives = (s.lives - livesLost).coerceAtLeast(0)

        // 2) Agendar spawns de la horda según su cadencia.
        spawnAccumulatorMs += deltaMillis
        while (spawnAccumulatorMs >= spec.spawnIntervalMs && pendingSpawns > 0) {
            // Lienzo saturado: no forzamos el hueco, reintentamos en el próximo frame
            // conservando el acumulador (la aparición se retrasa, no se pierde).
            val node = spawnNode(survivors) ?: break
            spawnAccumulatorMs -= spec.spawnIntervalMs
            survivors += node
            pendingSpawns--
            spawnedInWave++
            maybeSpawnHeart(newLives, survivors)?.let { survivors += it }
        }
        // Evita ráfagas: si el lienzo estuvo saturado (o la horda ya soltó todo), el
        // acumulador no debe engordar y soltar varios nodos de golpe al liberarse.
        spawnAccumulatorMs = spawnAccumulatorMs.coerceAtMost(spec.spawnIntervalMs)

        // 3) Publicar el nuevo estado.
        setState {
            copy(
                activeNodes = survivors,
                lives = newLives,
                waveNodesResolved = waveNodesResolved + resolved,
            )
        }

        // 4) Fin de partida (sin vidas) o cierre de horda (nada por soltar ni vivo).
        if (newLives <= 0) {
            loseAllLives()
        } else if (pendingSpawns == 0 && survivors.isEmpty()) {
            completeWave()
        }
    }

    /**
     * Cierra la horda superada: bonus proporcional a su número (aguantar más lejos
     * es lo que se premia en una partida infinita) y arranque de la siguiente.
     */
    private fun completeWave() {
        val cleared = currentState.wave
        setState { copy(score = score + NeonPulseConfig.WAVE_CLEAR_BONUS * cleared) }
        sendEffect(NeonPulseEffect.WaveCleared)
        beginWave(cleared + 1)
    }

    /**
     * Desplaza un nodo según su velocidad y lo hace **rebotar** contra los límites
     * jugables del lienzo. El rebote (en vez de dejarlo salir y reaparecer) mantiene
     * todos los objetivos siempre visibles y alcanzables: un nodo que se fuera por el
     * borde se perdería sin que el jugador pudiera hacer nada, y eso sería una vida
     * robada, no dificultad.
     *
     * El delta se convierte a segundos porque [Node.vx]/[Node.vy] están expresadas
     * por segundo (independencia del framerate).
     */
    private fun advance(node: Node, deltaMillis: Long): Node {
        if (node.vx == 0f && node.vy == 0f) return node
        val dt = deltaMillis / 1000f
        val r = node.radius
        val minX = r
        val maxX = 1f - r
        val minY = maxOf(r, NeonPulseConfig.TOP_SPAWN_MARGIN)
        val maxY = 1f - r

        var x = node.x + node.vx * dt
        var y = node.y + node.vy * dt
        var vx = node.vx
        var vy = node.vy
        if (x < minX) { x = minX; vx = -vx }
        if (x > maxX) { x = maxX; vx = -vx }
        if (y < minY) { y = minY; vy = -vy }
        if (y > maxY) { y = maxY; vy = -vy }
        return node.copy(x = x, y = y, vx = vx, vy = vy)
    }

    /**
     * Genera un nodo de la horda en curso, en coordenadas aleatorias **sin
     * superponerse** con los ya activos. Se prueban hasta [MAX_SPAWN_TRIES]
     * posiciones; si ninguna queda libre (lienzo muy poblado), se devuelve `null`
     * y el spawn se reintenta más adelante —preferimos retrasar una aparición antes
     * que apilar nodos que la UI no podría desambiguar al tocar—.
     *
     * El tipo (normal o trampa) y la velocidad salen de la rampa de la horda
     * ([WaveSpec]); la dirección del movimiento es aleatoria y uniforme en el
     * círculo, para que ninguna horda tenga una deriva sistemática.
     *
     * @param existing nodos ya presentes contra los que comprobar la distancia.
     * @return el nodo colocado, o `null` si no se encontró hueco.
     */
    private fun spawnNode(existing: List<Node>): Node? {
        val isTrap = random.nextFloat() < spec.trapChance
        return placeNode(
            existing = existing,
            type = if (isTrap) NodeType.TRAP else NodeType.NORMAL,
            lifeMs = spec.nodeLifeMs,
            speed = spec.speed,
        )
    }

    /**
     * Suelta el **corazón de rescate** de la horda si toca: cada
     * [NeonPulseConfig.HEART_EVERY_WAVES] hordas y solo **si el jugador lo
     * necesita** (le falta alguna vida). Aparece a mitad de oleada —no al principio—
     * para que se cruce con el juego real en vez de regalarse en el momento de calma.
     *
     * Es estático aunque la horda mueva a los demás: un premio que huye sería una
     * penalización encubierta justo cuando el jugador va peor.
     *
     * @return el corazón colocado, o `null` si no procede o no había hueco.
     */
    private fun maybeSpawnHeart(lives: Int, existing: List<Node>): Node? {
        if (!heartPending || lives >= NeonPulseConfig.MAX_LIVES) return null
        if (spawnedInWave < spec.nodeCount / 2) return null
        val heart = placeNode(
            existing = existing,
            type = NodeType.HEART,
            lifeMs = NeonPulseConfig.HEART_LIFE_MS,
            speed = 0f,
        ) ?: return null
        heartPending = false
        return heart
    }

    /** Busca hueco libre y construye el nodo. Comparte la búsqueda de posición entre
     *  objetivos, trampas y corazones para que todos respeten la misma separación. */
    private fun placeNode(
        existing: List<Node>,
        type: NodeType,
        lifeMs: Long,
        speed: Float,
    ): Node? {
        val r = NeonPulseConfig.NODE_RADIUS
        // Margen horizontal/inferior para que el nodo no quede cortado por el borde
        // del lienzo; el margen superior es mayor para no aparecer bajo el HUD/botón
        // de pausa (CLAUDE.md: nunca tapar controles fijos con contenido jugable).
        val minX = r
        val maxX = 1f - r
        val minY = maxOf(r, NeonPulseConfig.TOP_SPAWN_MARGIN)
        val maxY = 1f - r
        repeat(MAX_SPAWN_TRIES) {
            val x = minX + random.nextFloat() * (maxX - minX)
            val y = minY + random.nextFloat() * (maxY - minY)
            if (existing.none { overlaps(it, x, y, r) }) {
                val angle = random.nextFloat() * 2f * PI.toFloat()
                return Node(
                    id = nextNodeId++,
                    type = type,
                    x = x,
                    y = y,
                    radius = r,
                    totalLifeMs = lifeMs,
                    remainingMs = lifeMs,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
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
     * `Canvas`). Aquí solo aplicamos la **semántica**:
     *  - Nodo normal → acierto: puntúa con multiplicador de combo, refuerza racha y
     *    pide la animación de explosión ([NeonPulseEffect.ShowComboAnim]).
     *  - Nodo trampa → error: penaliza (vida + combo) con feedback fuerte.
     *  - Corazón → rescate: devuelve una vida (hasta [NeonPulseConfig.MAX_LIVES]).
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
                setState {
                    copy(
                        activeNodes = remaining,
                        score = score + gained,
                        waveNodesResolved = waveNodesResolved + 1,
                    )
                }
                sendEffect(NeonPulseEffect.PlaySound.Hit)
                sendEffect(NeonPulseEffect.Vibrate.Tick)
                sendEffect(NeonPulseEffect.ShowComboAnim(node.x, node.y, NodeType.NORMAL))
            }
            NodeType.TRAP -> {
                misses++
                combo = 0
                val newLives = (currentState.lives - 1).coerceAtLeast(0)
                setState {
                    copy(
                        activeNodes = remaining,
                        lives = newLives,
                        waveNodesResolved = waveNodesResolved + 1,
                    )
                }
                sendEffect(NeonPulseEffect.PlaySound.Error)
                sendEffect(NeonPulseEffect.Vibrate.Heavy)
                if (newLives <= 0) loseAllLives()
            }
            NodeType.HEART -> {
                // No suma puntos ni cuenta como acierto de precisión: es un rescate,
                // no un objetivo; puntuarlo premiaría ir mal para que aparezcan más.
                val newLives = (currentState.lives + 1).coerceAtMost(NeonPulseConfig.MAX_LIVES)
                setState { copy(activeNodes = remaining, lives = newLives) }
                sendEffect(NeonPulseEffect.PlaySound(SoundEffect.LEVEL_UP))
                sendEffect(NeonPulseEffect.Vibrate.Tick)
                sendEffect(NeonPulseEffect.ShowComboAnim(node.x, node.y, NodeType.HEART))
            }
        }

        // Tocar el último nodo pendiente cierra la horda en el acto: esperar al
        // siguiente tick dejaría un parpadeo de lienzo vacío antes del cartel. El
        // guard de `awaitingRevive` evita que esto dispare tras un TRAP que agotó
        // las vidas: [loseAllLives] ya vació el lienzo para ofrecer la revancha, y
        // ese vacío no debe leerse como "horda superada".
        if (remaining.isEmpty() &&
            pendingSpawns == 0 &&
            currentState.status == GameStatus.RUNNING &&
            !currentState.awaitingRevive
        ) {
            completeWave()
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
    // Segunda oportunidad: revivir viendo un anuncio
    // ---------------------------------------------------------------------------

    /**
     * Se han agotado las vidas. La primera vez que ocurre en la partida se ofrece
     * **revivir viendo un anuncio** ([NeonPulseUiState.awaitingRevive]) en vez de
     * terminar directamente; si ya se usó esa segunda oportunidad, la partida
     * acaba de verdad. Mismo patrón que `Neon2048ViewModel`/`BubbleMathEngine`.
     *
     * Congela el motor mientras se decide (cancela [loopJob] y vacía el lienzo):
     * ni ticks ni spawns hasta que el jugador resuelva la oferta, para que nada
     * expire ni aparezca mientras mira el anuncio.
     */
    private fun loseAllLives() {
        if (reviveOffered) {
            finish()
            return
        }
        loopJob?.cancel()
        loopJob = null
        setState { copy(activeNodes = emptyList(), awaitingRevive = true) }
    }

    /**
     * El anuncio concedió la recompensa: repone **una vida** y la partida continúa
     * la horda en curso (conserva puntuación, horda y progreso ya resuelto). Se
     * marca la segunda oportunidad como consumida: no se vuelve a ofrecer en la
     * sesión. Sin efecto si no se estaba ofreciendo.
     *
     * Solo una vida (no todas las iniciales): es una prórroga puntual, no un
     * reinicio del reto de vidas que sostiene la tensión de la horda.
     */
    private fun grantRevive() {
        if (!currentState.awaitingRevive) return
        reviveOffered = true
        // Respiro antes del próximo spawn: reaparecer con un nodo ya a punto de
        // expirar sería una segunda muerte injusta, no una segunda oportunidad.
        spawnAccumulatorMs = 0L
        setState { copy(lives = 1, awaitingRevive = false) }
        startLoop()
    }

    /**
     * El jugador rechaza la oferta (o el anuncio se cerró / no había): fin de
     * partida real. Sin efecto si no se estaba ofreciendo.
     */
    private fun declineRevive() {
        if (!currentState.awaitingRevive) return
        reviveOffered = true
        setState { copy(awaitingRevive = false) }
        finish()
    }

    // ---------------------------------------------------------------------------
    // Fin de partida (local-first)
    // ---------------------------------------------------------------------------

    /** Cierra la partida, detiene el loop y persiste el resultado. Idempotente:
     *  si ya está FINISHED no hace nada (evita doble guardado). */
    private fun finish() {
        if (currentState.status == GameStatus.FINISHED) return
        loopJob?.cancel()
        loopJob = null
        setState { copy(status = GameStatus.FINISHED, activeNodes = emptyList()) }

        val attempts = hits + misses
        val result = GameResult(
            gameId = GameIds.NEON_PULSE,
            score = currentState.score,
            completionTimeMs = elapsedMs,
            accuracyPercentage = if (attempts == 0) 100.0 else hits.toDouble() / attempts * 100.0,
            difficultyLevel = difficulty,
            // Récord = horda alcanzada: en una partida infinita es la unidad natural
            // de progresión (antes era la mejor racha, que no dice hasta dónde llegaste).
            reachedMetric = currentState.wave,
        )
        viewModelScope.launch {
            audio.playSound(SoundEffect.LEVEL_UP)
            // `saveResult` emite el resultado LOCAL primero (el cartel no espera a
            // Supabase) y, si hay sesión, el percentil/ranking real después (ver KDoc
            // de `ProgressRepository.saveResult`).
            progress.saveResult(result).collect { outcome ->
                setState { copy(gameOver = outcome.toGameOverInfo(result)) }
            }
        }
    }

    private companion object {
        /** Periodo objetivo del loop (~60 fps). El delta real se mide con el reloj
         *  monotónico, así que este valor solo fija la frecuencia de muestreo. */
        const val FRAME_MS = 16L

        /** Intentos de recolocación antes de posponer un spawn por falta de hueco. */
        const val MAX_SPAWN_TRIES = 20

        /** Separación mínima extra (espacio normalizado) entre nodos al spawnear. */
        const val SPAWN_PADDING = 0.02f

        /** Aciertos consecutivos necesarios para subir un escalón de multiplicador. */
        const val COMBO_STEP = 5
    }
}
