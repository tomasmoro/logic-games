package com.kortexgames.app.game.starport

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
import kotlin.math.roundToInt

/**
 * # Motor de "Neon Starport Escape" — colisiones 1-D, arrastre y snap
 *
 * Juego **LEVELED por turnos** (sin bucle de tiempo): el jugador desliza naves
 * a lo largo de su eje hasta despejar el camino de la VIP a la esclusa.
 *
 * ## Colisiones por intervalo libre (el "porqué" del algoritmo)
 *
 * No hay detección de colisiones "al vuelo" comparando rectángulos: como cada
 * nave solo puede moverse en 1-D (su eje), TODO el problema se reduce a
 * calcular, al levantar la nave, el **intervalo libre `[min, max]`** de su
 * coordenada de eje ([freeAxisRange]) y clampear contra él el resto del gesto:
 *
 *  - **min**: la celda siguiente al obstáculo más cercano por detrás.
 *  - **max**: la última posición donde la nave completa (longitud incluida)
 *    cabe antes del obstáculo más cercano por delante, o del mamparo.
 *
 * **Por qué esto garantiza que dos naves jamás se superpongan**: el estado
 * inicial no tiene solapes (validado por el `init` de [StarportLevel]) y la
 * única mutación posible del tablero es mover UNA nave DENTRO de su intervalo
 * libre. Para solaparse con otra, la nave tendría que ocupar una celda de esa
 * otra — pero precisamente esas celdas son las que acotan `min` y `max`, y el
 * offset de arrastre se clampea al intervalo en cada evento y el snap final
 * se redondea dentro de él. Por inducción, ningún estado alcanzable contiene
 * solapes. El intervalo se calcula UNA vez por gesto (no por frame): entre
 * levantar y soltar nada más puede moverse, así que sigue siendo válido.
 *
 * ## Feedback
 * Igual que Tornillos Neón: el motor emite eventos de dominio ([StarportEvent])
 * y el ViewModel los traduce a Effects MVI. El `audio` heredado de
 * [BaseGameEngine] queda sin usar aquí a propósito.
 */
class StarportEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
) : BaseGameEngine<StarportGameState>(GameIds.STARPORT_ESCAPE, difficulty, scope, audio) {

    private val _state = MutableStateFlow(StarportGameState())
    override val state: StateFlow<StarportGameState> = _state.asStateFlow()

    // Buffered + trySend: los eventos son feedback efímero; si el canal se
    // llenara (imposible en la práctica) perder uno es preferible a bloquear.
    private val _events = Channel<StarportEvent>(Channel.BUFFERED)

    /** Eventos de dominio para que el ViewModel los convierta en Effects MVI. */
    val events: Flow<StarportEvent> = _events.receiveAsFlow()

    /** Nivel en juego (1-based); base del récord y de "Siguiente nivel". */
    private var currentLevel = 1

    /** Óptimo BFS del nivel en juego (par contra el que se puntúa). */
    private var levelOptimalMoves = 1

    /**
     * Intervalo libre del arrastre en curso, como offset RELATIVO en celdas
     * respecto a la posición de origen. Es estado privado del gesto (no del
     * tablero): calcularlo al levantar y cachearlo evita re-escanear naves en
     * cada evento de arrastre (~60/s) sin riesgo, porque el tablero no puede
     * cambiar a mitad de gesto.
     */
    private var dragRange: ClosedFloatingPointRange<Float> = 0f..0f

    /**
     * true si ya se notificó el choque del contacto actual contra un límite.
     * Evita ametrallar con háptica HEAVY en cada evento mientras el dedo sigue
     * empujando; se rearma cuando el gesto vuelve a zona libre.
     */
    private var bumpNotified = false

    /** Arranca el nivel elegido en el selector (o el siguiente desde el resultado). */
    fun startAtLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        start() // BaseGameEngine.start() → onStart()
    }

    override fun onStart() {
        val level = StarportLevels.forNumber(currentLevel)
        levelOptimalMoves = level.optimalMoves
        _state.value = StarportGameState(
            exit = level.exit,
            ships = level.ships,
            hangarSize = level.hangarSize,
        )
    }

    /** Reinicia el MISMO nivel desde su disposición inicial (movimientos a 0). */
    fun restart() = onStart()

    // ------------------------------------------------------------------ input

    /**
     * El jugador posó el dedo sobre [shipId]: se levanta la nave y se calcula
     * y cachea su intervalo libre. Se ignora si ya hay otro arrastre en curso
     * (multitouch): permitir dos gestos rompería la premisa de que el tablero
     * no cambia entre levantar y soltar.
     */
    fun onShipLifted(shipId: Int) {
        val s = _state.value
        if (status.value != GameStatus.RUNNING || s.vipEscaped || s.drag != null) return
        val ship = s.shipById(shipId) ?: return

        val range = freeAxisRange(ship, s.ships, s.hangarSize)
        dragRange = (range.first - ship.axisPosition).toFloat()..
            (range.last - ship.axisPosition).toFloat()
        bumpNotified = false
        _state.value = s.copy(drag = ShipDrag(shipId))
        _events.trySend(StarportEvent.ShipLifted)
    }

    /**
     * El dedo se movió [deltaCells] celdas a lo largo del eje: acumula el
     * offset clampeándolo al intervalo cacheado. Si el delta intenta rebasar
     * un límite (otra nave o el mamparo) el offset se queda clavado en él y se
     * emite UN [StarportEvent.ShipBumped] por contacto — el "golpe" físico que
     * comunica que el camino está bloqueado aunque el dedo siga empujando.
     */
    fun onShipDragged(shipId: Int, deltaCells: Float) {
        val s = _state.value
        val drag = s.drag ?: return
        if (drag.shipId != shipId || status.value != GameStatus.RUNNING) return

        val raw = drag.axisOffset + deltaCells
        val clamped = raw.coerceIn(dragRange)
        // Umbral anti-ruido: un exceso microscópico por redondeo de px→celdas
        // no debe sonar a choque.
        val pushing = raw - clamped > BUMP_THRESHOLD || clamped - raw > BUMP_THRESHOLD
        if (pushing && !bumpNotified) {
            bumpNotified = true
            _events.trySend(StarportEvent.ShipBumped)
        }
        if (!pushing) bumpNotified = false

        if (clamped != drag.axisOffset) {
            _state.value = s.copy(drag = drag.copy(axisOffset = clamped))
        }
    }

    /**
     * El jugador soltó la nave: snap a la celda válida más cercana (redondeo
     * del offset, que ya está dentro del intervalo libre) y consolidación.
     * Solo cuenta como movimiento si la nave terminó en una celda distinta —
     * un toque exploratorio que vuelve a su sitio sale gratis, como la
     * deselección en Tornillos Neón.
     *
     * Si la nave es la VIP y quedó pegada a la esclusa, marca el escape: la UI
     * anima el vuelo de salida y confirmará con [onExitAnimFinished] (la
     * partida NO termina aquí para que la celebración aparezca después de ver
     * a la nave salir, no encima).
     */
    fun onShipReleased(shipId: Int) {
        val s = _state.value
        val drag = s.drag ?: return
        if (drag.shipId != shipId) return
        val ship = s.shipById(shipId) ?: return

        val snapped = (ship.axisPosition + drag.axisOffset).roundToInt()
            .coerceIn(
                (ship.axisPosition + dragRange.start).roundToInt(),
                (ship.axisPosition + dragRange.endInclusive).roundToInt(),
            )
        val moved = snapped != ship.axisPosition
        val newShip = ship.movedTo(snapped)
        val escaped = newShip.isVip && isFlushAgainstExit(newShip, s.exit, s.hangarSize)

        _state.value = s.copy(
            ships = s.ships.map { if (it.id == shipId) newShip else it },
            drag = null,
            moves = if (moved) s.moves + 1 else s.moves,
            vipEscaped = escaped,
        )
        _events.trySend(
            if (moved) StarportEvent.ShipDocked else StarportEvent.ShipSettledBack,
        )
        if (escaped) _events.trySend(StarportEvent.VipEscaped)
    }

    /**
     * La UI terminó de animar a la VIP cruzando la esclusa: ahora sí se cierra
     * la partida (cronómetro, score, outcome). Separar escape (dominio) de
     * animación (UI) evita temporizadores en el motor.
     */
    fun onExitAnimFinished() {
        if (!_state.value.vipEscaped || status.value != GameStatus.RUNNING) return
        finish()
    }

    // ----------------------------------------------------------------- reglas

    // El cálculo del intervalo libre vive en el dominio ([freeAxisRange] en
    // StarportModel.kt): es la MISMA regla de física que usa el solver BFS del
    // generador de niveles, y compartirla garantiza que "resoluble para el
    // generador" y "jugable para el motor" signifiquen exactamente lo mismo.

    /**
     * ¿La nave quedó pegada al borde de su esclusa? Es la condición de
     * victoria de Rush Hour: llegar al borde con vía libre (el intervalo ya
     * garantizó que no hay nada entre medias) equivale a poder salir.
     */
    private fun isFlushAgainstExit(ship: Ship, exit: HangarExit, hangarSize: Int): Boolean {
        if (ship.orientation != exit.requiredOrientation) return false
        if (ship.lanePosition != exit.index) return false
        return when (exit.side) {
            ExitSide.LEFT, ExitSide.TOP -> ship.axisPosition == 0
            ExitSide.RIGHT, ExitSide.BOTTOM -> ship.axisPosition == hangarSize - ship.length
        }
    }

    // ------------------------------------------------------------- puntuación

    /**
     * Puntaje: base proporcional al nivel menos penalización por movimientos
     * de más respecto al óptimo BFS. Mismo esquema que Tornillos Neón / Water
     * Sort para que los puntajes entre juegos LEVELED sean comparables.
     */
    override fun calculateScore(): Int {
        val extraMoves = (_state.value.moves - levelOptimalMoves).coerceAtLeast(0)
        return (currentLevel * 1_000 - extraMoves * PENALTY_PER_EXTRA_MOVE).coerceAtLeast(0)
    }

    /** Precisión = eficiencia: movimientos óptimos / movimientos reales. */
    override fun currentAccuracy(): Double {
        val moves = _state.value.moves
        if (moves == 0) return 100.0
        return (levelOptimalMoves.toDouble() / moves * 100).coerceAtMost(100.0)
    }

    /** Récord = nivel completado (solo se termina al escapar la VIP). */
    override fun reachedMetric(): Int = currentLevel

    private companion object {
        const val PENALTY_PER_EXTRA_MOVE = 50

        /** Exceso mínimo (en celdas) sobre el límite para considerarlo choque. */
        const val BUMP_THRESHOLD = 0.15f
    }
}

/**
 * Eventos de dominio del motor: describen QUÉ pasó (semántica de juego), no
 * cómo suena o vibra — esa traducción la hace el ViewModel al mapearlos a
 * Effects (tabla única auditable en `StarportViewModel.onEngineEvent`).
 */
sealed interface StarportEvent {
    /** Se levantó una nave (empieza el arrastre). */
    data object ShipLifted : StarportEvent

    /** El arrastre empujó contra otra nave o el mamparo (una vez por contacto). */
    data object ShipBumped : StarportEvent

    /** La nave se soltó y acopló en una celda DISTINTA (cuenta movimiento). */
    data object ShipDocked : StarportEvent

    /** La nave se soltó donde estaba: vuelve a su sitio sin gastar movimiento. */
    data object ShipSettledBack : StarportEvent

    /** La VIP quedó pegada a la esclusa: victoria (pendiente de animación). */
    data object VipEscaped : StarportEvent
}
