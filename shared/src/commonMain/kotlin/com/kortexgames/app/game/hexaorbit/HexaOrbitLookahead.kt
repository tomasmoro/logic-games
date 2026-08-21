package com.kortexgames.app.game.hexaorbit

/**
 * # Trazado proyectado (*lookahead*) de Hexa Orbit — FASE 1
 *
 * El haz de luz que se enciende por delante del puntero. Es la mecánica que convierte un juego
 * de reflejos en uno **táctico**: sin él el jugador solo reacciona a la celda que pisa; con él
 * ve [HexaOrbitBalance.LOOKAHEAD_TILES] azulejos por delante y puede planear una secuencia de
 * giros, no solo el siguiente.
 *
 * Este archivo contiene el algoritmo completo. El motor (FASE 2) se limita a llamarlo.
 */

/**
 * Un tramo del recorrido: el paso del puntero por **un** azulejo, con la arista por la que entra
 * y la que toma para salir.
 *
 * Es el "pedazo" que la UI dibuja: cada [TraversalStep] se traduce en una curva orientada
 * ([HexGeometry.orientedCurve]) que se pinta encendida con intensidad decreciente.
 *
 * @property coord azulejo atravesado.
 * @property entryEdge arista de entrada, en índices del tablero.
 * @property exitEdge arista de salida, ya resuelta aplicando la rotación del azulejo.
 */
data class TraversalStep(
    val coord: HexCoord,
    val entryEdge: Int,
    val exitEdge: Int,
) {
    /** Dirección hacia la que abandona el azulejo. */
    val exitDirection: HexDirection get() = HexDirection.ofEdge(exitEdge)

    /** Coordenada a la que salta al terminar este tramo (puede caer fuera del tablero). */
    val nextCoord: HexCoord get() = coord + exitDirection

    /**
     * Arista por la que entrará en [nextCoord]: la misma frontera vista desde el otro lado, es
     * decir la **opuesta** a [exitEdge]. Esta línea es todo el "pegamento" entre azulejos.
     */
    val nextEntryEdge: Int get() = oppositeEdge(exitEdge)

    /** Curva orientada de este tramo en coordenadas de tablero, lista para dibujar. */
    fun curve(): HexCurve = HexGeometry.orientedCurve(coord, entryEdge, exitEdge)
}

/**
 * Resultado del trazado: el tramo que el puntero está recorriendo **ahora** más los siguientes.
 *
 * ## Por qué el tramo actual va incluido
 *
 * El haz tiene que arrancar **en el puntero**, no en la frontera del siguiente azulejo: si el
 * primer elemento fuera ya el azulejo siguiente, quedaría un hueco oscuro entre el orbe y su
 * propia luz. Por eso `steps[0]` es siempre el azulejo ocupado y los proyectados son
 * [upcoming]. Es decir: `steps.size <= LOOKAHEAD_TILES + 1`.
 *
 * @property steps tramos consecutivos, empezando por el azulejo actual.
 * @property escapes `true` si el recorrido proyectado termina **saliéndose del tablero** dentro
 *           del horizonte calculado. Con un horizonte largo esto es lo normal, no una urgencia:
 *           significa "por ahí se sale, más adelante". La urgencia la marca [imminent].
 */
data class LookaheadPath(
    val steps: List<TraversalStep>,
    val escapes: Boolean,
) {

    /** Tramo que el puntero recorre ahora mismo; `null` solo antes de empezar la partida. */
    val current: TraversalStep? get() = steps.firstOrNull()

    /** Los azulejos **futuros** proyectados (como mucho [HexaOrbitBalance.LOOKAHEAD_TILES]). */
    val upcoming: List<TraversalStep> get() = if (steps.isEmpty()) emptyList() else steps.drop(1)

    /**
     * Profundidad, en azulejos futuros, a la que el recorrido abandona el tablero; `null` si la
     * fuga no entra en el horizonte. `0` significaría que el puntero se sale del azulejo que ya
     * está pisando.
     *
     * Se deriva del tamaño de [steps] en vez de guardarse: cuando hay fuga, el trazado se corta
     * justo ahí, así que el último tramo emitido **es** el que da al vacío.
     */
    val escapeDepth: Int? get() = if (escapes && steps.isNotEmpty()) steps.size - 1 else null

    /**
     * `true` cuando la fuga está lo bastante cerca como para ser una **emergencia**: dentro de
     * [HexaOrbitBalance.ESCAPE_ALERT_TILES] azulejos.
     *
     * Es esta propiedad —y no [escapes]— la que enciende el haz rojo, el aviso del marcador y la
     * penalización de precisión. Con el horizonte alargado a nueve azulejos, [escapes] es cierto
     * casi todo el rato (el tablero solo mide 3 anillos), así que usarlo como alarma la dejaría
     * permanentemente encendida: una alarma que nunca se apaga no informa de nada.
     */
    val imminent: Boolean get() = escapeDepth?.let { it <= HexaOrbitBalance.ESCAPE_ALERT_TILES } == true

    companion object {
        /** Proyección vacía: estado previo a la partida. */
        val EMPTY: LookaheadPath = LookaheadPath(steps = emptyList(), escapes = false)
    }
}

/**
 * Calcula el trazado proyectado desde el azulejo [from], al que se entra por [entryEdge].
 *
 * ## El algoritmo
 *
 * Es un recorrido determinista del grafo, un paso por azulejo. Partiendo de un par
 * `(coordenada, arista de entrada)` se repite:
 *
 * 1. **Resolver la salida**: se pide al azulejo `exitEdgeFor(entrada)`. Ahí es donde se aplica
 *    la rotación de la pieza — el cambio de base `entrada − rotación → tabla canónica →
 *    + rotación` documentado en [HexTile.exitEdgeFor]. Este es el único punto del algoritmo que
 *    depende de cómo esté girado el tablero, y por eso girar **cualquier** pieza del recorrido
 *    reescribe el haz entero a partir de ella.
 * 2. **Emitir el tramo** `(coord, entrada, salida)`.
 * 3. **Saltar al vecino**: `coord + dirección(salida)`, entrando por la arista opuesta
 *    (`salida + 3`), que es esa misma frontera vista desde el otro lado.
 * 4. **Comprobar la frontera**: si el vecino no existe en el tablero, el recorrido termina con
 *    `escapes = true` — el puntero se saldría por ahí, que es la condición de derrota. Lo cerca
 *    que quede esa salida lo dice [LookaheadPath.escapeDepth], y es lo que separa "ahí está el
 *    borde" de "gira ya" ([LookaheadPath.imminent]).
 *
 * Y se corta al alcanzar [maxTiles] tramos futuros.
 *
 * ## Iterativo y acotado, no recursivo
 *
 * El spec lo planteaba como cálculo recursivo, pero se implementa con un bucle por dos razones
 * concretas:
 *
 *  - **Los circuitos cerrados son legales y frecuentes.** Tres giros cerrados encadenados forman
 *    un anillo por el que el puntero da vueltas eternamente; eso no es un error del tablero,
 *    es una jugada (de hecho es la forma de "aparcar" el puntero mientras se piensa). Una
 *    recursión sin más red que la condición de frontera no terminaría nunca. El tope de
 *    [maxTiles] hace innecesaria cualquier detección de ciclos.
 *  - **Se ejecuta a 60 FPS.** Un bucle con un `ArrayList` preasignado no toca la pila ni genera
 *    los marcos de llamada de la recursión, y aquí el coste es fijo: como mucho
 *    `maxTiles + 1` iteraciones de trabajo O(1).
 *
 * Nota deliberada: **no se filtran repeticiones**. Si el trazado vuelve a pisar un azulejo ya
 * visitado (o el mismo con otra arista de entrada), se emite otra vez: es un tramo distinto del
 * recorrido y la luz debe dibujarse en los dos. Deduplicar rompería tanto el haz de los bucles
 * como el conteo de azulejos por delante.
 *
 * @param from azulejo donde está el puntero.
 * @param entryEdge arista (índice de tablero) por la que entró en [from].
 * @param maxTiles cuántos azulejos **futuros** iluminar; por defecto
 *   [HexaOrbitBalance.LOOKAHEAD_TILES].
 * @return el trazado, vacío si [from] no pertenece al tablero.
 */
fun HexBoard.project(
    from: HexCoord,
    entryEdge: Int,
    maxTiles: Int = HexaOrbitBalance.LOOKAHEAD_TILES,
): LookaheadPath {
    val steps = ArrayList<TraversalStep>(maxTiles + 1)
    var coord = from
    var entry = entryEdge.mod(HEX_EDGES)

    // maxTiles + 1: el tramo actual más los proyectados (ver KDoc de LookaheadPath).
    repeat(maxTiles + 1) {
        val tile = tileAt(coord) ?: return LookaheadPath(steps, escapes = true)
        val step = TraversalStep(coord, entry, tile.exitEdgeFor(entry))
        steps += step
        coord = step.nextCoord
        entry = step.nextEntryEdge
    }

    // Se agotó el horizonte sin llegar a la frontera: el camino sigue, simplemente no se ilumina
    // más allá. Que la coordenada siguiente esté fuera del tablero NO se comprueba aquí a
    // propósito: el jugador aún tiene tiempo de girar esa pieza, y teñir el haz de alarma por
    // algo que ocurre más allá del horizonte visible sería una alerta que no puede interpretar.
    return LookaheadPath(steps, escapes = false)
}

/**
 * Atajo que proyecta desde el puntero actual: la forma en que lo llamará el motor tras cada
 * avance de azulejo y tras **cada giro** (un tap puede reescribir el haz aunque el puntero no se
 * haya movido, y esa realimentación inmediata es lo que hace legible el juego).
 */
fun HexaOrbitState.projectFromPointer(
    maxTiles: Int = HexaOrbitBalance.LOOKAHEAD_TILES,
): LookaheadPath = board.project(pointer.coord, pointer.entryEdge, maxTiles)
