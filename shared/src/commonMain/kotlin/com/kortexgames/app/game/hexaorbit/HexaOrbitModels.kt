package com.kortexgames.app.game.hexaorbit

import kotlin.random.Random

/**
 * # Modelos de dominio de "Hexa Orbit" (Visión Espacial / Reflejos) — FASE 1
 *
 * Juego táctico en tiempo real sobre un tablero hexagonal: un puntero de luz recorre sin parar
 * los caminos curvos grabados en cada azulejo, y el jugador **gira los hexágonos** (tap = 60° en
 * sentido horario) para reencaminarlo, recoger orbes de energía y evitar que se escape por la
 * frontera exterior del tablero.
 *
 * ## Decisiones clave de este archivo
 *
 *  - **Dominio 100 % puro**: nada de Compose, `Color`, `Offset` ni píxeles. Las posiciones se
 *    expresan en [HexPoint] (unidades de *radio de hexágono*, ver [HexGeometry]); la pantalla
 *    multiplica por el radio real que quepa y ya. Así el motor y sus tests no arrastran
 *    dependencias de framework, igual que en `NeonCircuitModel`.
 *  - **Coordenadas axiales `(q, r)`** en vez de una matriz 2D con filas desplazadas: en axiales
 *    los seis vecinos son seis sumas de vectores constantes ([HexDirection]) y la distancia es
 *    una fórmula cerrada. Con "filas pares/impares" cada vecindad depende de la paridad de la
 *    fila y el código se llena de casos especiales que el algoritmo de trazado ([project])
 *    recorre miles de veces por segundo.
 *  - **Rotación como ESTADO, no como datos regenerados**: un azulejo guarda su patrón canónico
 *    ([TilePattern]) y un entero `rotation` de 0..5. Girar es `rotation + 1`, no reconstruir la
 *    lista de conexiones; la traducción "arista del tablero → arista del patrón" es una simple
 *    conjugación modular (ver [HexTile.exitEdgeFor]). Esto mantiene el giro en O(1) y hace que
 *    dos azulejos con el mismo patrón y rotación sean `equals`, lo que la UI aprovecha para
 *    saltarse recomposiciones.
 *  - **Inmutabilidad**: cada frame produce un estado nuevo (snapshots comparables para MVI).
 *
 * ## Convención de aristas (la base de todo lo demás)
 *
 * Los hexágonos son **pointy-top** (vértice arriba). Sus seis aristas se indexan `0..5` en
 * sentido **horario empezando por el Este**, que es exactamente el orden de [HexDirection]:
 *
 * ```
 *      4 ┌─┐ 5          0 = E, 1 = SE, 2 = SW, 3 = W, 4 = NW, 5 = NE
 *      3 │ │ 0          arista i  ->  ángulo de pantalla 60° * i  (y hacia abajo)
 *      2 └─┘ 1          opuesta de i  ->  (i + 3) % 6
 * ```
 *
 * Que el índice de arista coincida con el ángulo de pantalla no es cosmético: hace que "girar
 * el azulejo 60° en horario" sea literalmente "sumar 1 al índice de arista", sin tablas de
 * conversión entre el modelo y el `Canvas`.
 */

/** Número de aristas (y de vecinos) de un hexágono. Se usa como módulo en todo el archivo. */
const val HEX_EDGES: Int = 6

/**
 * Coordenada axial de una celda del tablero hexagonal.
 *
 * El sistema axial es la proyección del sistema cúbico `(x, y, z)` con `x + y + z = 0`: se
 * guardan solo dos ejes ([q], [r]) y el tercero ([s]) se deduce. Se prefiere al "offset"
 * (filas con desplazamiento par/impar) porque aquí la aritmética de vecinos y distancias es
 * uniforme: sumar un vector siempre significa lo mismo, sin depender de la paridad de la fila.
 *
 * @property q eje "columna" (crece hacia el Este).
 * @property r eje "fila" (crece hacia el Sur-Este).
 */
data class HexCoord(val q: Int, val r: Int) {

    /** Tercer eje cúbico implícito, `-q - r`. Útil para distancias y para validar anillos. */
    val s: Int get() = -q - r

    /** Celda vecina al otro lado de la arista [direction]. */
    operator fun plus(direction: HexDirection): HexCoord = HexCoord(q + direction.dq, r + direction.dr)

    /**
     * Distancia hexagonal (número mínimo de saltos entre celdas contiguas) hasta [other].
     *
     * Es la distancia de Manhattan en el espacio cúbico dividida entre 2: cada salto cambia dos
     * de los tres ejes cúbicos en ±1, así que la suma de valores absolutos cuenta cada paso dos
     * veces.
     */
    fun distanceTo(other: HexCoord): Int =
        (kotlin.math.abs(q - other.q) + kotlin.math.abs(s - other.s) + kotlin.math.abs(r - other.r)) / 2

    /** Anillo en el que vive la celda: `0` es el centro, `radius` es la frontera exterior. */
    val ring: Int get() = distanceTo(ORIGIN)

    companion object {
        /** Centro del tablero. */
        val ORIGIN: HexCoord = HexCoord(0, 0)
    }
}

/**
 * Las seis direcciones de vecindad, ordenadas por ángulo de pantalla creciente (horario, con
 * el eje Y hacia abajo como en `Canvas`).
 *
 * El [ordinal] ES el índice de arista: la arista 0 mira al Este, la 1 al Sur-Este, etc. Esta
 * coincidencia deliberada permite que la rotación de un azulejo sea aritmética modular pura.
 *
 * @property dq desplazamiento en el eje `q` al cruzar esa arista.
 * @property dr desplazamiento en el eje `r` al cruzar esa arista.
 */
enum class HexDirection(val dq: Int, val dr: Int) {
    EAST(1, 0),
    SOUTH_EAST(0, 1),
    SOUTH_WEST(-1, 1),
    WEST(-1, 0),
    NORTH_WEST(0, -1),
    NORTH_EAST(1, -1);

    /** Índice de arista asociado (`0..5`). Alias legible de [ordinal]. */
    val edgeIndex: Int get() = ordinal

    /**
     * Dirección opuesta. Es la clave del salto entre azulejos: si el puntero sale del azulejo A
     * por la arista `e`, entra en el vecino por la arista `e + 3` — la misma frontera física
     * vista desde el otro lado.
     */
    val opposite: HexDirection get() = entries[(ordinal + 3) % HEX_EDGES]

    companion object {
        /** Dirección de una arista `0..5` (acepta índices fuera de rango y los normaliza). */
        fun ofEdge(edge: Int): HexDirection = entries[edge.mod(HEX_EDGES)]
    }
}

/** Arista opuesta a [edge] dentro del mismo hexágono, es decir, la otra cara de esa frontera. */
fun oppositeEdge(edge: Int): Int = (edge + HEX_EDGES / 2).mod(HEX_EDGES)

/**
 * Cuán cerrado es el giro que describe un camino interno, deducido de la separación cíclica
 * entre sus dos aristas.
 *
 * No es decoración: la curvatura decide la forma de la curva Bézier ([HexGeometry.curveFor]) y,
 * en FASE 2, cuánto camino hay que recorrer dentro del azulejo (un giro cerrado es más corto
 * que una recta, así que el puntero lo atraviesa antes a igualdad de velocidad).
 *
 * @property edgeGap separación cíclica entre las dos aristas (1, 2 o 3).
 */
enum class PathCurvature(val edgeGap: Int) {
    /** Aristas contiguas (gap 1): giro de 120°, el más cerrado. */
    SHARP(1),

    /** Aristas con una en medio (gap 2): giro amplio de 60°. */
    WIDE(2),

    /** Aristas opuestas (gap 3): recta que cruza el azulejo por el centro. */
    STRAIGHT(3);

    companion object {
        /** Curvatura de un salto entre las aristas [a] y [b] del mismo hexágono. */
        fun between(a: Int, b: Int): PathCurvature {
            val diff = (a - b).mod(HEX_EDGES)
            val gap = minOf(diff, HEX_EDGES - diff)
            return entries.first { it.edgeGap == gap }
        }
    }
}

/**
 * Un camino interno del azulejo: el par **no ordenado** de aristas que conecta.
 *
 * Los caminos son bidireccionales (el puntero puede entrar por cualquiera de las dos puntas),
 * así que el par se normaliza con `a < b` en el constructor. Sin esa normalización
 * `EdgePair(0, 3)` y `EdgePair(3, 0)` serían objetos distintos para `equals`/`hashCode` y los
 * conjuntos de conexiones dejarían de comparar bien entre estados — justo lo que MVI necesita.
 *
 * @property a arista menor (`0..5`).
 * @property b arista mayor (`0..5`), siempre `> a`.
 */
data class EdgePair(val a: Int, val b: Int) {

    init {
        require(a in 0 until HEX_EDGES && b in 0 until HEX_EDGES) { "Aristas fuera de rango: $a-$b" }
        require(a < b) { "EdgePair debe construirse normalizado (a < b); recibido $a-$b" }
    }

    /** Forma del giro que describe este camino. */
    val curvature: PathCurvature get() = PathCurvature.between(a, b)

    /** La otra punta del camino si [edge] es una de las dos; `null` si el camino no lo toca. */
    fun partnerOf(edge: Int): Int? = when (edge) {
        a -> b
        b -> a
        else -> null
    }

    /** El mismo camino tras girar el azulejo [steps] pasos de 60° en horario. */
    fun rotated(steps: Int): EdgePair = edgePairOf((a + steps).mod(HEX_EDGES), (b + steps).mod(HEX_EDGES))
}

/** Crea un [EdgePair] normalizado sin obligar al llamante a ordenar las aristas. */
fun edgePairOf(x: Int, y: Int): EdgePair =
    if (x < y) EdgePair(x, y) else EdgePair(y, x)

/**
 * Patrón canónico de un azulejo: la partición fija de sus 6 aristas en **3 caminos**.
 *
 * ## Por qué solo hay cuatro patrones
 *
 * Matemáticamente hay 15 formas de emparejar 6 aristas en 3 parejas, pero **la rotación es
 * estado en tiempo de ejecución** ([HexTile.rotation]): dos particiones que se obtienen una de
 * otra girando describen el mismo azulejo en distinta posición inicial. Agrupando las 15 por
 * rotación quedan exactamente **4 clases** (de tamaños 1, 2, 6 y 6), y aquí se declara un
 * representante de cada una. Incluir más sería duplicar piezas que el jugador no distinguiría,
 * y sesgaría el generador aleatorio hacia las formas con más representantes.
 *
 * @property connections los tres caminos en la orientación canónica (`rotation = 0`).
 */
enum class TilePattern(val connections: List<EdgePair>) {

    /** Tres rectas que se cruzan en el centro. La pieza "autopista": rápida y previsible. */
    TRIPLE_STRAIGHT(listOf(EdgePair(0, 3), EdgePair(1, 4), EdgePair(2, 5))),

    /** Tres giros cerrados que rebotan contra las esquinas. La pieza "muelle": desvía mucho. */
    TRIPLE_SHARP(listOf(EdgePair(0, 1), EdgePair(2, 3), EdgePair(4, 5))),

    /** Dos giros amplios y uno cerrado: la pieza de transición más común. */
    WIDE_PAIR(listOf(EdgePair(0, 2), EdgePair(1, 3), EdgePair(4, 5))),

    /** Dos giros cerrados enmarcando una recta: buena para "puentear" el tablero. */
    SHARP_BRIDGE(listOf(EdgePair(0, 1), EdgePair(2, 5), EdgePair(3, 4)));

    /**
     * Tabla de salida precalculada en la orientación canónica: `partners[e]` es la arista a la
     * que lleva entrar por `e`.
     *
     * Se precalcula (en lugar de recorrer [connections] en cada consulta) porque el trazado
     * proyectado la consulta 5 veces por frame **por cada azulejo del recorrido**, a 60 FPS.
     * `firstNotNullOf` además actúa de validación: si un patrón dejara una arista sin pareja,
     * la clase reventaría al cargarse y no en mitad de una partida.
     */
    val partners: List<Int> = List(HEX_EDGES) { edge ->
        connections.firstNotNullOf { it.partnerOf(edge) }
    }
}

/**
 * Un azulejo colocado en el tablero: su patrón de caminos más la rotación actual.
 *
 * @property coord posición axial en el tablero.
 * @property pattern partición canónica de aristas ([TilePattern]).
 * @property rotation número de pasos de 60° **en horario** aplicados al patrón (`0..5`).
 */
data class HexTile(
    val coord: HexCoord,
    val pattern: TilePattern,
    val rotation: Int = 0,
) {

    init {
        require(rotation in 0 until HEX_EDGES) { "Rotación fuera de rango: $rotation" }
    }

    /**
     * Los tres caminos **en coordenadas del tablero** (ya rotados). Es lo que dibuja la UI;
     * la lógica de recorrido usa [exitEdgeFor], que evita construir esta lista por frame.
     */
    val connections: List<EdgePair> get() = pattern.connections.map { it.rotated(rotation) }

    /**
     * Arista por la que sale el puntero si entra por [entryEdge] (ambas en índices del tablero).
     *
     * ## La "matriz de rotación" de un hexágono
     *
     * Girar un azulejo no reescribe sus caminos: se aplica un **cambio de base modular**. Con
     * `rotation = k`, la arista `e` del tablero corresponde a la arista `e − k` del patrón
     * canónico. Así que salir se resuelve en tres pasos, todos O(1):
     *
     * 1. **Al marco del patrón**: `e' = (entryEdge − k) mod 6`.
     * 2. **Consulta canónica**: `p' = pattern.partners[e']`.
     * 3. **De vuelta al marco del tablero**: `(p' + k) mod 6`.
     *
     * Es la conjugación `R∘P∘R⁻¹` de la permutación de caminos por la rotación — el equivalente
     * discreto de la matriz de rotación que pedía el spec. Nótese el `mod` de Kotlin (no `%`):
     * `%` devuelve negativos para `entryEdge < k` e indexaría fuera de la lista.
     */
    fun exitEdgeFor(entryEdge: Int): Int {
        val canonicalEntry = (entryEdge - rotation).mod(HEX_EDGES)
        return (pattern.partners[canonicalEntry] + rotation).mod(HEX_EDGES)
    }

    /** El mismo azulejo girado 60° en horario: lo que produce un tap del jugador. */
    fun rotatedClockwise(): HexTile = copy(rotation = (rotation + 1).mod(HEX_EDGES))
}

/**
 * Tablero hexagonal completo: un "hexágono de hexágonos" de [radius] anillos alrededor del
 * centro.
 *
 * Los azulejos se guardan en un `Map` disperso indexado por coordenada, no en una matriz 2D: la
 * forma es hexagonal, así que una matriz rectangular tendría ~40 % de huecos que habría que
 * enmascarar en cada acceso, y la pregunta que el juego hace constantemente es exactamente la
 * que un mapa responde en O(1) — "¿hay azulejo en `(q, r)`?". Esa misma pregunta ES la
 * condición de derrota: si el puntero sale hacia una coordenada ausente, se escapó del tablero.
 *
 * @property radius número de anillos alrededor del centro (`0` = un solo azulejo).
 * @property tiles azulejos indexados por coordenada axial.
 */
data class HexBoard(
    val radius: Int,
    val tiles: Map<HexCoord, HexTile>,
) {

    /** Azulejo en [coord], o `null` si esa coordenada cae fuera del tablero. */
    fun tileAt(coord: HexCoord): HexTile? = tiles[coord]

    /** `true` si [coord] pertenece al tablero. Permite escribir `coord in board`. */
    operator fun contains(coord: HexCoord): Boolean = tiles.containsKey(coord)

    /** `true` si [coord] está en el anillo exterior, desde donde el puntero puede escaparse. */
    fun isOnBorder(coord: HexCoord): Boolean = coord.ring == radius

    /**
     * Copia del tablero con el azulejo de [coord] girado 60° en horario. Si la coordenada no
     * existe devuelve el mismo tablero: un tap fuera de la rejilla es un no-op, no un error.
     */
    fun withTileRotated(coord: HexCoord): HexBoard {
        val tile = tiles[coord] ?: return this
        return copy(tiles = tiles + (coord to tile.rotatedClockwise()))
    }

    companion object {

        /** Tablero vacío (sin azulejos): estado previo a la partida, ver [HexaOrbitState]. */
        fun empty(): HexBoard = HexBoard(radius = 0, tiles = emptyMap())

        /**
         * Todas las coordenadas de un tablero hexagonal de [radius] anillos.
         *
         * El doble bucle acotado (`q` en `[-radius, radius]`, `r` recortado a la intersección)
         * genera exactamente las `3·radius² + 3·radius + 1` celdas válidas sin filtrar después:
         * el recorte de `r` es lo que "corta las esquinas" del rombo y deja el hexágono.
         */
        fun coordsWithin(radius: Int): List<HexCoord> = buildList {
            for (q in -radius..radius) {
                val rMin = maxOf(-radius, -q - radius)
                val rMax = minOf(radius, -q + radius)
                for (r in rMin..rMax) add(HexCoord(q, r))
            }
        }

        /**
         * Genera un tablero con patrón y rotación aleatorios en cada celda.
         *
         * No se intenta garantizar que exista un circuito cerrado: el reto del juego es
         * precisamente construirlo a la carrera girando piezas. Lo que sí garantiza FASE 2 al
         * arrancar es que el puntero nazca con margen suficiente antes de la frontera.
         *
         * @param random fuente de azar inyectada para que los tests sean reproducibles.
         */
        fun random(radius: Int, random: Random): HexBoard {
            val patterns = TilePattern.entries
            val tiles = coordsWithin(radius).associateWith { coord ->
                HexTile(
                    coord = coord,
                    pattern = patterns[random.nextInt(patterns.size)],
                    rotation = random.nextInt(HEX_EDGES),
                )
            }
            return HexBoard(radius = radius, tiles = tiles)
        }
    }
}

/**
 * Orbe de energía recolectable, anclado a un punto **fijo del espacio** dentro de un azulejo.
 *
 * ## Por qué un punto fijo y no "el azulejo entero"
 *
 * Si el orbe se recogiera con solo pisar su casilla, girar piezas no tendría nada que ver con
 * recolectar y el juego perdería su tensión central. Anclándolo a un punto concreto —elegido en
 * el instante del spawn sobre uno de los caminos del azulejo— el jugador tiene que **encaminar
 * el haz por encima**, que es justo la decisión interesante. El anclaje es espacial, así que
 * girar el azulejo mueve los caminos pero **no** el orbe.
 *
 * El radio de recogida ([HexaOrbitBalance.COLLECT_RADIUS]) es deliberadamente generoso para que
 * varias orientaciones del azulejo sirvan: el objetivo es "pasar cerca", no clavar una curva
 * concreta, que sería frustrante a la velocidad a la que corre el puntero.
 *
 * @property id identidad estable del orbe; la UI la usa como `key` de animación para que un
 *           respawn se lea como un orbe **nuevo** (aparición con pulso) y no como uno que se
 *           teletransporta.
 * @property coord azulejo que lo contiene (permite decidir en O(1) si toca comprobar distancia).
 * @property local desplazamiento respecto al centro del azulejo, en unidades de radio de hex.
 */
data class EnergyOrb(
    val id: Long,
    val coord: HexCoord,
    val local: HexPoint,
) {
    /** Posición absoluta en el espacio del tablero (unidades de radio de hex). */
    val position: HexPoint get() = HexGeometry.center(coord) + local
}

/**
 * Estado físico del puntero de luz: dónde está en el **grafo** (qué azulejo y por qué camino) y
 * dónde está en el **plano 2D**.
 *
 * Las dos representaciones conviven a propósito: la del grafo es la que decide las
 * transiciones (salir del azulejo, escaparse del tablero) y la 2D es la que se dibuja y la que
 * mide la distancia a los orbes. Derivar una de la otra en cada frame costaría evaluar la curva
 * dos veces.
 *
 * @property coord azulejo que está atravesando.
 * @property entryEdge arista (índice de tablero) por la que entró.
 * @property exitEdge arista por la que saldrá. Es **denormalizado**: se deduce de
 *           `board.tileAt(coord).exitEdgeFor(entryEdge)`, pero se cachea porque se consulta cada
 *           frame. Se mantiene coherente sin recálculos porque el azulejo que ocupa el puntero
 *           está **bloqueado al giro** (ver `HexaOrbitEngine.rotateTile`): nadie puede cambiar
 *           la salida del tramo en curso a mitad de recorrido.
 * @property progress avance sobre la curva actual como **fracción de longitud de arco**, `0f` en
 *           [entryEdge] y `1f` en [exitEdge]. Ojo: NO es el parámetro `t` de la Bézier —una
 *           cúbica no avanza a ritmo constante en `t`—; la conversión a `t` la hace
 *           `HexCurveMetrics` al calcular la posición (FASE 2).
 * @property position posición 2D en el espacio del tablero (unidades de radio de hex).
 * @property trail posiciones recientes, de la más antigua a la más nueva, para pintar la estela.
 *           Vive en el estado (y no en la UI) para que sobreviva a las recomposiciones y para
 *           que la longitud de la estela sea una constante de balance, no un detalle de dibujo.
 */
data class PointerState(
    val coord: HexCoord = HexCoord.ORIGIN,
    val entryEdge: Int = 0,
    val exitEdge: Int = 3,
    val progress: Float = 0f,
    val position: HexPoint = HexPoint.ZERO,
    val trail: List<HexPoint> = emptyList(),
)

/**
 * Estado de dominio completo de una partida de Hexa Orbit.
 *
 * Es el `data class` que FASE 2 hará avanzar frame a frame y que FASE 3 dibujará. Todo lo que la
 * pantalla necesita está aquí y nada más: el ciclo de vida y el overlay de resultados viven en
 * el envoltorio de UI ([HexaOrbitUiState]).
 *
 * @property board tablero con la rotación actual de cada pieza.
 * @property pointer puntero de luz (grafo + 2D).
 * @property projection haz proyectado: el azulejo actual más los próximos
 *           [HexaOrbitBalance.LOOKAHEAD_TILES]. Se recalcula al avanzar de azulejo y en cada
 *           giro; ver [project].
 * @property orbs orbes recolectables vivos (como mucho [HexaOrbitBalance.MAX_ORBS]).
 * @property speed rapidez actual del puntero en **radios de hexágono por segundo**; sube con
 *           [elapsedSeconds]. La unidad es lineal (distancia recorrida), no "azulejos por
 *           segundo": el puntero mantiene una rapidez constante sobre la curva, así que un giro
 *           cerrado —que es un camino más corto— se atraviesa antes que una recta. Esa
 *           diferencia es información táctica real y perderla igualando los tiempos por azulejo
 *           haría que la curva dibujada mintiera sobre lo que va a tardar.
 * @property score puntuación acumulada.
 * @property collected orbes recogidos (métrica de la partida, dato del resultado).
 * @property elapsedSeconds tiempo de partida; es la entrada de la rampa de velocidad y parte de
 *           la puntuación final.
 * @property escaped `true` cuando el puntero cruzó la frontera exterior: fin de partida.
 */
data class HexaOrbitState(
    val board: HexBoard = HexBoard.empty(),
    val pointer: PointerState = PointerState(),
    val projection: LookaheadPath = LookaheadPath.EMPTY,
    val orbs: List<EnergyOrb> = emptyList(),
    val speed: Float = HexaOrbitBalance.INITIAL_SPEED,
    val score: Int = 0,
    val collected: Int = 0,
    val elapsedSeconds: Float = 0f,
    val escaped: Boolean = false,
)

/**
 * Constantes de balance del juego, en un único sitio para poder ajustar la sensación sin tocar
 * lógica (mismo criterio que `LegionBalance`).
 *
 * Las de física las consume FASE 2; se declaran ya porque [HexaOrbitState] las usa como valores
 * por defecto y porque son el contrato de "cómo de frenético" es el juego.
 */
object HexaOrbitBalance {

    /** Anillos del tablero. 3 → 37 azulejos: cabe en pantalla de móvil sin que la pieza sea diminuta. */
    const val BOARD_RADIUS: Int = 3

    /**
     * Azulejos que ilumina el haz por delante del puntero. El spec fija 4: suficiente para
     * planear dos o tres giros, corto como para no revelar todo el tablero y matar la tensión.
     */
    const val LOOKAHEAD_TILES: Int = 4

    /** Orbes simultáneos en el tablero. */
    const val MAX_ORBS: Int = 3

    /**
     * Rapidez inicial en **radios de hexágono por segundo**. Cruzar un azulejo en recta mide
     * `√3 ≈ 1.73` radios, así que 2.8 equivale a ~1.6 azulejos rectos por segundo: cómodo para
     * leer el primer trazado antes de que la rampa apriete.
     */
    const val INITIAL_SPEED: Float = 2.8f

    /** Incremento de rapidez por segundo de partida (rampa lineal), en radios/s². */
    const val SPEED_RAMP_PER_SEC: Float = 0.08f

    /**
     * Techo de rapidez (~5 azulejos rectos por segundo). Por encima el jugador no llega a
     * reaccionar dentro del horizonte de 4 azulejos y el juego pasa a decidirse por azar, que es
     * justo lo contrario de un juego táctico.
     */
    const val MAX_SPEED: Float = 8.6f

    /** Radio de recogida de un orbe, en unidades de radio de hex. */
    const val COLLECT_RADIUS: Float = 0.32f

    /** Puntos por orbe recogido. */
    const val POINTS_PER_ORB: Int = 100

    /** Muestras de posición que conserva la estela del puntero. */
    const val TRAIL_POINTS: Int = 24
}
