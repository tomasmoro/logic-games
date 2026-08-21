package com.kortexgames.app.game.hexaorbit

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.physics.FrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * # HexaOrbitEngine — bucle de juego y física del puntero (FASE 2)
 *
 * Motor de **Hexa Orbit**: hace avanzar el puntero de luz por las curvas del tablero a rapidez
 * creciente, aplica los giros que pide el jugador, recolecta los orbes de energía, los repuebla
 * y termina la partida cuando el puntero cruza la frontera exterior.
 *
 * Es lógica pura y portable: no conoce Compose, ni píxeles, ni el `AdManager`. El feedback se
 * emite como [HexaOrbitEffect] semántico por [effects] y lo traduce el ViewModel.
 *
 * ## Bucle dirigido por el render, con `dt` saneado
 *
 * La simulación avanza una vez por frame (`withFrameNanos` → [onFrame]) y el `dt` lo deriva el
 * [FrameClock] compartido, que descarta el primer frame y recorta los saltos largos. Aquí el
 * recorte no es cosmético: sin él, el frame gigante que sigue a un anuncio o a un cambio de app
 * movería el puntero varios azulejos de golpe —atravesando orbes sin recogerlos y, en el peor
 * caso, saliéndose del tablero sin que el jugador llegara a ver el peligro.
 *
 * ## Sub-pasos: por qué no basta con mover el puntero una vez por frame
 *
 * A rapidez máxima el puntero recorre ~0.14 radios por frame, más de la mitad del radio de
 * recogida de un orbe ([HexaOrbitBalance.COLLECT_RADIUS]). Comprobando la recogida solo en la
 * posición final del frame, un orbe podría quedar **entre** la posición anterior y la nueva y no
 * detectarse nunca: el clásico *tunneling* de colisiones. Por eso cada frame se parte en
 * sub-pasos de como mucho [MAX_SUBSTEP_DISTANCE] y la recogida se evalúa en cada uno. El coste
 * es de 1-2 sub-pasos por frame en la práctica.
 *
 * ## El azulejo que ocupa el puntero NO se puede girar (decisión de diseño)
 *
 * Un tap sobre la pieza que el puntero está atravesando es un **no-op silencioso**. Las
 * alternativas son peores:
 *  - *Girar y conservar el avance* teletransportaría el orbe a otra curva, dejándolo separado de
 *    su propia estela: se lee como un fallo del juego, no como una jugada.
 *  - *Girar y reiniciar el tramo* regalaría un "deshacer" infinito con el que el jugador podría
 *    quedarse dando vueltas en la misma pieza indefinidamente.
 *
 * Bloquearla es además coherente con la mecánica central: el haz proyectado da
 * [HexaOrbitBalance.LOOKAHEAD_TILES] azulejos de aviso precisamente para que las decisiones se
 * tomen por delante del puntero. La muerte por no haber girado a tiempo es la regla del juego,
 * no una injusticia.
 *
 * ## Los orbes viven en las ARISTAS, no sobre las curvas
 *
 * Un orbe nace en un punto del contorno del hexágono (una arista, "entre los lados"), nunca
 * sobre uno de sus tres caminos internos: así el punto se distingue siempre del tubo de luz de
 * la pieza en vez de mezclarse visualmente con él. El contorno del hexágono además no gira —solo
 * giran sus caminos internos—, así que el orbe queda anclado al espacio con más fuerza todavía:
 * girar un azulejo mueve sus curvas pero **nunca** el orbe (ver [EnergyOrb]). El jugador tiene
 * que encaminar el haz para que la curva pase junto a esa arista, no limitarse a pisar la
 * casilla.
 *
 * @param random inyectable para tests deterministas de generación de tablero y de spawns.
 */
class HexaOrbitEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<HexaOrbitState>(GameIds.HEXA_ORBIT, difficulty, scope, audio) {

    private val _state = MutableStateFlow(HexaOrbitState())
    override val state: StateFlow<HexaOrbitState> = _state.asStateFlow()

    /**
     * Canal de efectos de juego. `BUFFERED` para no perder un evento si el colector va un
     * instante por detrás; se consume una sola vez (semántica one-shot).
     */
    private val _effects = Channel<HexaOrbitEffect>(Channel.BUFFERED)

    /** Flujo de efectos que el ViewModel colecta para audio/háptica y reenvío a la UI. */
    val effects: Flow<HexaOrbitEffect> = _effects.receiveAsFlow()

    private val frameClock = FrameClock(maxDtSec = MAX_DT_SEC)

    /** Ids monotónicos de orbe: estables dentro de la partida, base de las `key` de animación. */
    private var nextOrbId = 1L

    /**
     * Segundos acumulados **sin** una fuga inminente proyectada. Es el numerador de
     * [currentAccuracy]: mide cuánto tiempo mantuvo el jugador un circuito seguro, que es la
     * habilidad real del juego. Se acumula aquí y no en el estado porque la UI no lo pinta.
     *
     * El criterio es [LookaheadPath.imminent] y no [LookaheadPath.escapes]: con el horizonte de
     * nueve azulejos el haz alcanza la frontera casi siempre, así que medir contra `escapes`
     * dejaría la precisión clavada cerca de 0 en cualquier partida y no distinguiría a nadie.
     */
    private var safeSeconds: Float = 0f

    // --- Ciclo de vida -----------------------------------------------------------------------

    /**
     * Prepara una partida nueva: tablero aleatorio, puntero en el **centro** y los tres orbes.
     *
     * El puntero arranca en el azulejo central porque es el punto del tablero más lejano a la
     * frontera (radio completo en las seis direcciones): el jugador dispone del horizonte de
     * aviso íntegro antes de su primera decisión, en vez de nacer ya con una fuga proyectada.
     */
    override fun onStart() {
        frameClock.reset()
        safeSeconds = 0f
        nextOrbId = 1L

        val board = HexBoard.random(HexaOrbitBalance.BOARD_RADIUS, random)
        val entryEdge = random.nextInt(HEX_EDGES)
        val startTile = board.tileAt(HexCoord.ORIGIN) ?: error("El tablero siempre tiene centro")
        val step = TraversalStep(HexCoord.ORIGIN, entryEdge, startTile.exitEdgeFor(entryEdge))

        val pointer = PointerState(
            coord = HexCoord.ORIGIN,
            entryEdge = entryEdge,
            exitEdge = step.exitEdge,
            progress = 0f,
            position = pointOnStep(step, 0f),
            trail = emptyList(),
        )

        var fresh = HexaOrbitState(
            board = board,
            pointer = pointer,
            projection = board.project(pointer.coord, pointer.entryEdge),
        )
        repeat(HexaOrbitBalance.MAX_ORBS) { fresh = fresh.withNewOrb() }
        _state.value = fresh
    }

    override fun onResume() = frameClock.reset()

    override fun onPause() = frameClock.reset()

    // --- Entradas del jugador ----------------------------------------------------------------

    /**
     * Gira 60° en horario el azulejo de [coord] y reescribe el haz proyectado.
     *
     * Ignora en silencio los taps que no cambian nada: partida no corriendo, coordenada fuera
     * del tablero, o la pieza que ocupa el puntero (ver la decisión de diseño en el KDoc de la
     * clase). "En silencio" es deliberado: un pitido de error ante un fallo de puntería tan
     * habitual castigaría al jugador por la imprecisión del dedo, no por su jugada.
     *
     * El recálculo del haz es lo que da la realimentación inmediata que hace legible el juego:
     * el jugador ve el trazado futuro cambiar en el mismo frame del tap, sin esperar a que el
     * puntero llegue.
     */
    fun rotateTile(coord: HexCoord) {
        if (status.value != GameStatus.RUNNING) return
        val current = _state.value
        if (coord !in current.board) return
        if (coord == current.pointer.coord) return

        val board = current.board.withTileRotated(coord)
        _state.value = current.copy(
            board = board,
            projection = board.project(current.pointer.coord, current.pointer.entryEdge),
        )
        emit(HexaOrbitEffect.PlaySound(HexaOrbitEffect.PlaySound.Cue.ROTATE))
        emit(HexaOrbitEffect.Vibrate(HexaOrbitEffect.Vibrate.Cue.LIGHT))
    }

    /**
     * Avanza la simulación un frame. La llama la pantalla desde `withFrameNanos`.
     *
     * @param frameNanos tiempo monotónico del frame (ver [HexaOrbitIntent.Tick]).
     */
    fun onFrame(frameNanos: Long) {
        if (status.value != GameStatus.RUNNING) return
        val dtSec = frameClock.tick(frameNanos) ?: return

        val advanced = step(_state.value, dtSec)
        _state.value = advanced

        // La fuga se detecta durante el paso de física, pero cerrar la partida se hace FUERA del
        // reductor: `finish()` es un efecto de ciclo de vida (publica el GameResult y cambia el
        // status), y mezclarlo con la función pura que produce el estado haría el paso de física
        // imposible de probar sin arrastrar el motor entero.
        if (advanced.escaped) {
            emit(HexaOrbitEffect.PlaySound(HexaOrbitEffect.PlaySound.Cue.GAME_OVER))
            emit(HexaOrbitEffect.Vibrate(HexaOrbitEffect.Vibrate.Cue.ERROR))
            finish()
        }
    }

    // --- Paso de física ----------------------------------------------------------------------

    /**
     * Un paso de simulación de [dtSec] segundos, como **función pura** del estado:
     *
     *  1. Cronómetro y rampa de rapidez.
     *  2. Avance por sub-pasos (cruzando azulejos y recogiendo orbes por el camino).
     *  3. Muestreo de la estela.
     *
     * Devuelve un estado nuevo (inmutabilidad MVI). Los efectos de recogida sí se publican como
     * side-effect controlado en [_effects]: son one-shot y no caben en el estado.
     */
    private fun step(state: HexaOrbitState, dtSec: Float): HexaOrbitState {
        val elapsed = state.elapsedSeconds + dtSec

        // Rampa LINEAL con techo: el jugador percibe el aumento como una tensión que sube sola,
        // pero acotada para que la partida no degenere en azar (ver HexaOrbitBalance.MAX_SPEED).
        val speed = min(
            HexaOrbitBalance.INITIAL_SPEED + HexaOrbitBalance.SPEED_RAMP_PER_SEC * elapsed,
            HexaOrbitBalance.MAX_SPEED,
        )

        if (!state.projection.imminent) safeSeconds += dtSec

        var next = state.copy(elapsedSeconds = elapsed, speed = speed)
        var remaining = speed * dtSec
        while (remaining > DISTANCE_EPSILON && !next.escaped) {
            val substep = min(remaining, MAX_SUBSTEP_DISTANCE)
            next = next.advance(substep)
            next = next.collectOrbsUnderPointer()
            remaining -= substep
        }

        return next.withTrailSample()
    }

    /**
     * Mueve el puntero [distance] radios a lo largo de su curva, cruzando al azulejo vecino
     * tantas veces como haga falta.
     *
     * El bucle está acotado por [MAX_TILE_CROSSINGS]: con sub-pasos de
     * [MAX_SUBSTEP_DISTANCE] es imposible cruzar más de un azulejo por llamada (la curva más
     * corta mide bastante más que un sub-paso), así que el tope no se alcanza jamás en juego
     * real. Está para que un `dt` corrupto o una curva degenerada no puedan colgar el hilo de
     * render, que es un fallo del que el usuario no puede recuperarse.
     */
    private fun HexaOrbitState.advance(distance: Float): HexaOrbitState {
        var result = this
        var remaining = distance
        var crossings = 0

        while (remaining > DISTANCE_EPSILON && !result.escaped && crossings++ < MAX_TILE_CROSSINGS) {
            val pointer = result.pointer
            val length = HexCurveMetrics.lengthOf(pointer.entryEdge, pointer.exitEdge)
            val travelled = pointer.progress * length + remaining

            result = if (travelled < length) {
                remaining = 0f
                result.withPointerProgress(travelled / length)
            } else {
                remaining = travelled - length
                result.crossToNeighbour()
            }
        }
        return result
    }

    /** Reposiciona el puntero dentro de su tramo actual con el avance normalizado [progress]. */
    private fun HexaOrbitState.withPointerProgress(progress: Float): HexaOrbitState {
        val step = TraversalStep(pointer.coord, pointer.entryEdge, pointer.exitEdge)
        return copy(
            pointer = pointer.copy(
                progress = progress,
                position = pointOnStep(step, progress),
            ),
        )
    }

    /**
     * Salta al azulejo vecino por la arista de salida actual, o marca la fuga si no hay vecino.
     *
     * Al cambiar de azulejo se **reproyecta el haz**: el horizonte se desplaza un paso y hay un
     * azulejo nuevo que iluminar al fondo. Es el otro momento (junto al giro) en que el trazado
     * se recalcula, tal y como exige el spec.
     */
    private fun HexaOrbitState.crossToNeighbour(): HexaOrbitState {
        val exiting = TraversalStep(pointer.coord, pointer.entryEdge, pointer.exitEdge)
        val target = exiting.nextCoord
        val tile = board.tileAt(target)
            // Sin vecino: el puntero cruza la frontera exterior. Se le deja clavado en el punto
            // exacto de salida (progress = 1) para que el fogonazo de fin de partida ocurra
            // sobre la arista por la que se escapó, y no en una posición ya fuera del tablero.
            ?: return withPointerProgress(1f).copy(escaped = true)

        val entryEdge = exiting.nextEntryEdge
        val pointer = pointer.copy(
            coord = target,
            entryEdge = entryEdge,
            exitEdge = tile.exitEdgeFor(entryEdge),
            progress = 0f,
        )
        return copy(
            pointer = pointer.copy(
                position = pointOnStep(TraversalStep(target, entryEdge, pointer.exitEdge), 0f),
            ),
            projection = board.project(target, entryEdge),
        )
    }

    // --- Orbes de energía --------------------------------------------------------------------

    /**
     * Recoge los orbes que estén dentro del radio de captura y los repuebla de inmediato.
     *
     * Se comprueba la distancia solo contra los orbes del azulejo actual y de sus vecinos: un
     * orbe más lejos no puede estar a 0.32 radios del puntero, y así el filtro descarta en O(1)
     * por orbe sin calcular raíces cuadradas de más. Con un máximo de 3 orbes el ahorro es
     * simbólico, pero deja el bucle preparado para tableros mayores.
     */
    private fun HexaOrbitState.collectOrbsUnderPointer(): HexaOrbitState {
        val position = pointer.position
        val hits = orbs.filter { orb ->
            orb.coord.distanceTo(pointer.coord) <= 1 &&
                orb.position.distanceTo(position) <= HexaOrbitBalance.COLLECT_RADIUS
        }
        if (hits.isEmpty()) return this

        emit(HexaOrbitEffect.PlaySound(HexaOrbitEffect.PlaySound.Cue.COLLECT_POINT))
        emit(HexaOrbitEffect.Vibrate(HexaOrbitEffect.Vibrate.Cue.SUCCESS))

        var result = copy(
            orbs = orbs - hits.toSet(),
            collected = collected + hits.size,
            score = score + hits.size * HexaOrbitBalance.POINTS_PER_ORB,
        )
        // Respawn inmediato: el tablero siempre tiene MAX_ORBS objetivos vivos, así que el
        // jugador nunca se queda sin nada que perseguir (y la presión no baja al acertar).
        repeat(hits.size) { result = result.withNewOrb() }
        return result
    }

    /**
     * Añade un orbe en un punto aleatorio de una arista INTERIOR de un azulejo elegido al azar
     * — nunca sobre una de sus curvas internas, y nunca sobre una arista de la frontera exterior
     * del tablero.
     *
     * Tres restricciones al elegir el azulejo y la arista, todas de diseño:
     *  - **Lejos del puntero** ([MIN_SPAWN_DISTANCE] azulejos): un orbe que apareciera justo
     *    delante del haz sería un punto regalado, y encima resultaría invisible (nace y se
     *    recoge en el mismo frame).
     *  - **Una casilla sin orbe**: dos orbes en la misma pieza se solaparían visualmente y
     *    valdrían lo mismo que uno.
     *  - **Arista compartida con un vecino real** (no la frontera exterior): cruzar la frontera
     *    ES la condición de derrota ([HexaOrbitState.escaped]), así que el puntero solo llega al
     *    punto medio de una arista exterior en el mismo frame en que la partida termina —un orbe
     *    ahí solo se "recogería" simultáneamente con perder. Restringir el spawn a aristas
     *    internas garantiza que todo orbe sea alcanzable sin morir en el intento.
     *
     * El punto concreto se interpola entre los dos vértices que enmarcan la arista elegida, en
     * el tramo central (`[0.3, 0.7]` de esa arista): así el orbe nace siempre sobre el borde del
     * hexágono —nunca encima del tubo de luz "apagado" que dibuja `drawIdlePaths`— y lejos de
     * los vértices, donde se confundiría con la esquina de tres piezas a la vez.
     *
     * Un punto de arista NO gira con la pieza (el contorno del hexágono es fijo; solo giran sus
     * caminos internos, ver [HexaOrbitState.board]), así que este spawn es además independiente
     * de la rotación y del patrón del azulejo: `withNewOrb` no necesita mirar
     * [HexTile.connections] en absoluto.
     *
     * Si no hubiera ninguna casilla candidata con al menos una arista interior (imposible con el
     * tablero de radio 3 —hasta sus celdas de esquina tienen 3 vecinos dentro—, pero cierto en un
     * tablero degenerado de un solo azulejo) devuelve el estado intacto en vez de forzar una
     * posición mala.
     */
    private fun HexaOrbitState.withNewOrb(): HexaOrbitState {
        val taken = orbs.map { it.coord }.toSet()
        val candidates = board.tiles.keys.filter { coord ->
            coord !in taken && coord.distanceTo(pointer.coord) >= MIN_SPAWN_DISTANCE
        }.shuffled(random)

        // Se recorren los candidatos en orden aleatorio y se queda con el primero que tenga
        // alguna arista interior: así un azulejo sin vecinos dentro del tablero (solo posible en
        // un tablero degenerado) no bloquea el spawn si hay otras casillas disponibles.
        for (coord in candidates) {
            val interiorEdges = (0 until HEX_EDGES).filter { edge -> coord + HexDirection.ofEdge(edge) in board }
            if (interiorEdges.isEmpty()) continue

            val edge = interiorEdges[random.nextInt(interiorEdges.size)]
            // Los vértices que enmarcan la arista `edge` son las esquinas `edge - 1` y `edge`
            // (ver HexGeometry.edgeMidpoint, que cae exactamente entre ambos).
            val cornerA = HexGeometry.corner((edge - 1).mod(HEX_EDGES))
            val cornerB = HexGeometry.corner(edge)
            val fraction = ORB_SPAWN_MIN_FRACTION +
                random.nextFloat() * (ORB_SPAWN_MAX_FRACTION - ORB_SPAWN_MIN_FRACTION)
            val local = cornerA + (cornerB - cornerA) * fraction

            return copy(orbs = orbs + EnergyOrb(id = nextOrbId++, coord = coord, local = local))
        }
        return this
    }

    // --- Estela ------------------------------------------------------------------------------

    /**
     * Añade la posición actual a la estela y descarta las muestras más viejas.
     *
     * Se muestrea **una vez por frame** y no una por sub-paso: la estela es un recurso visual y
     * a 60 FPS un punto por frame ya la dibuja continua, mientras que muestrear los sub-pasos
     * multiplicaría la lista sin diferencia perceptible. Como la rapidez sube con el tiempo, la
     * estela se alarga sola al acelerar — un indicador gratuito de la tensión de la partida.
     */
    private fun HexaOrbitState.withTrailSample(): HexaOrbitState {
        val updated = (pointer.trail + pointer.position).takeLast(HexaOrbitBalance.TRAIL_POINTS)
        return copy(pointer = pointer.copy(trail = updated))
    }

    // --- Resultado ---------------------------------------------------------------------------

    /**
     * Puntuación = orbes recogidos + bono por segundo sobrevivido.
     *
     * Los dos sumandos son deliberados: solo con orbes, dar vueltas en un bucle seguro sin
     * recoger nada no puntuaría (correcto) pero tampoco recompensaría aguantar a rapidez alta
     * (incorrecto: sobrevivir a 8 radios/s es difícil). Solo con tiempo, el juego premiaría
     * aparcar el puntero en un circuito cerrado y esperar. Sumando ambos, la partida buena es la
     * que recoge **mientras** sobrevive, que es la que el juego pide.
     */
    override fun calculateScore(): Int {
        val current = _state.value
        val survivalBonus = (current.elapsedSeconds * SURVIVAL_POINTS_PER_SEC).roundToInt()
        return (current.score + survivalBonus).coerceAtLeast(0)
    }

    /**
     * Precisión = porcentaje del tiempo de partida sin una fuga **inminente** proyectada
     * (dentro de [HexaOrbitBalance.ESCAPE_ALERT_TILES] azulejos).
     *
     * Se elige esta métrica y no "orbes recogidos sobre orbes aparecidos" porque los orbes se
     * repueblan solos: esa ratio tendería a un número sin significado. El tiempo con el circuito
     * a salvo, en cambio, mide exactamente la habilidad que el juego entrena — anticiparse con
     * los cuatro azulejos de aviso — y distingue a quien navega con control de quien se salva
     * por los pelos una y otra vez.
     */
    override fun currentAccuracy(): Double {
        val elapsed = _state.value.elapsedSeconds
        if (elapsed <= 0f) return 100.0
        return (safeSeconds / elapsed).coerceIn(0f, 1f).toDouble() * 100.0
    }

    /** Récord = orbes recogidos en la corrida (progresión ENDLESS); `null` si no recogió ninguno. */
    override fun reachedMetric(): Int? = _state.value.collected.takeIf { it > 0 }

    private fun emit(effect: HexaOrbitEffect) {
        _effects.trySend(effect)
    }

    private companion object {

        /**
         * Techo del `dt` por frame (50 ms ≈ 20 FPS), el mismo que usan Polarity e Hypergate: por
         * debajo la física es fiel; por encima preferimos "cámara lenta" a un salto que se salte
         * orbes o fronteras.
         */
        const val MAX_DT_SEC = 0.05f

        /**
         * Distancia máxima de un sub-paso, en radios de hexágono. Es la mitad del radio de
         * recogida: garantiza que ningún orbe pueda quedar "entre" dos muestras consecutivas de
         * la posición del puntero (ver el apartado de sub-pasos en el KDoc de la clase).
         */
        const val MAX_SUBSTEP_DISTANCE = HexaOrbitBalance.COLLECT_RADIUS / 2f

        /** Umbral por debajo del cual un avance se considera consumido (evita bucles de ~0). */
        const val DISTANCE_EPSILON = 1e-5f

        /** Tope de seguridad de cruces de azulejo por sub-paso; ver KDoc de `advance`. */
        const val MAX_TILE_CROSSINGS = 8

        /** Separación mínima, en azulejos, entre el puntero y un orbe recién aparecido. */
        const val MIN_SPAWN_DISTANCE = 2

        /** Tramo de la curva donde puede nacer un orbe (fracción de longitud de arco). */
        const val ORB_SPAWN_MIN_FRACTION = 0.3f
        const val ORB_SPAWN_MAX_FRACTION = 0.7f

        /** Puntos por segundo sobrevivido; ver el KDoc de [calculateScore]. */
        const val SURVIVAL_POINTS_PER_SEC = 8f
    }
}
