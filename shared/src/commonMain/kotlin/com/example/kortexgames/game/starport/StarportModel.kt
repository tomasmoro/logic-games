package com.example.kortexgames.game.starport

/**
 * # Modelos de dominio de "Neon Starport Escape" (Pensamiento Lógico)
 *
 * Mecánica clásica de *Rush Hour / Parking Jam* con temática espacial: un
 * hangar 6×6 lleno de naves que solo pueden deslizarse a lo largo de su eje
 * longitudinal, y una nave VIP que debe alcanzar la esclusa de salida.
 *
 * Decisiones clave:
 *  - **Dominio 100% puro**: nada de Compose, px ni colores. Las naves normales
 *    se pintan con tokens fríos (`SurfaceVariantDark` + detalle `NeonCyan`) y
 *    la VIP con `NeonGreen`/`LogicGradients`, pero ese mapeo vive en la UI;
 *    aquí solo existe el booleano semántico [Ship.isVip].
 *  - **Movimiento 1-D por construcción**: una nave no tiene libertad en 2D;
 *    su única variable de estado mutable es la coordenada a lo largo de su
 *    eje ([Ship.axisPosition]). Esto reduce todo el motor de colisiones a
 *    aritmética de intervalos sobre una recta (ver invariante más abajo).
 *  - **Inmutabilidad**: cada jugada produce un [StarportGameState] nuevo
 *    (snapshots comparables para MVI, sin aliasing), igual que el resto de
 *    motores del proyecto. Copiar ≤ 12 naves por jugada es despreciable.
 *
 * ## Invariante de no-superposición (el "porqué" del diseño)
 *
 * Dos naves jamás pueden solaparse porque el sistema lo garantiza en dos capas:
 *
 *  1. **En origen**: [StarportLevel] valida en su `init` que las celdas
 *     ocupadas del nivel diseñado a mano sean únicas y estén dentro del
 *     hangar. Un nivel mal escrito revienta al construirse, no en producción.
 *  2. **En movimiento**: el motor (FASE 2) calcula, para la nave arrastrada,
 *     el intervalo libre `[min, max]` de su eje escaneando las celdas ocupadas
 *     por las demás naves, y **clampa el offset de arrastre a ese intervalo en
 *     todo momento**. Como el movimiento es estrictamente 1-D, para solaparse
 *     habría que *atravesar* una celda ocupada — y esa celda es precisamente
 *     la que acota el intervalo. No existe estado alcanzable con solape.
 */

/** Lado del hangar cuadrado BASE (primeros niveles). 6×6 es el tablero canónico del género Rush Hour. */
const val HANGAR_SIZE: Int = 6

/** Lado máximo al que crece el hangar con la progresión de niveles. */
const val MAX_HANGAR_SIZE: Int = 10

/** Longitud de las naves cortas (cazas). */
const val SHIP_LENGTH_SHORT: Int = 2

/** Longitud de las naves largas (cargueros). */
const val SHIP_LENGTH_LONG: Int = 3

/** Longitud de los meteoritos extra-largos (4×1); solo obstáculos, nunca la VIP. */
const val SHIP_LENGTH_XLONG: Int = 4

/**
 * Eje longitudinal de una nave: define su ÚNICA dirección de movimiento
 * (regla de oro del juego). Se modela como enum y no como booleano para que
 * el código del motor lea `when (orientation)` sin ambigüedad.
 */
enum class Orientation {
    /** La nave ocupa celdas contiguas de una fila; solo desliza izq./der. */
    HORIZONTAL,

    /** La nave ocupa celdas contiguas de una columna; solo desliza arriba/abajo. */
    VERTICAL,
}

/**
 * Coordenada de celda del hangar: fila y columna en `0 until HANGAR_SIZE`.
 *
 * Se usa (row, col) y no (x, y) para evitar la ambigüedad clásica de qué eje
 * es cuál: `row` crece hacia abajo y `col` hacia la derecha, como se dibuja.
 */
data class CellPos(val row: Int, val col: Int) {

    /** ¿La celda cae dentro de un hangar de lado [size]? */
    fun isInside(size: Int): Boolean = row in 0 until size && col in 0 until size
}

/**
 * Borde del hangar donde puede abrirse la esclusa de salida.
 */
enum class ExitSide {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

/**
 * La esclusa: única apertura del hangar por la que escapa la nave VIP.
 *
 * @property side borde del hangar donde está la apertura.
 * @property index fila de la apertura si [side] es LEFT/RIGHT, columna si es
 *           TOP/BOTTOM. Con el borde + un índice queda unívocamente situada
 *           sin necesitar coordenadas "fuera del tablero".
 */
data class HangarExit(
    val side: ExitSide,
    val index: Int,
) {

    /**
     * Eje de escape compatible con esta esclusa. Una nave solo puede cruzarla
     * si su [Orientation] coincide (una nave vertical no puede salir por un
     * hueco lateral): es la invariante que [StarportLevel] exige a la VIP.
     */
    val requiredOrientation: Orientation
        get() = when (side) {
            ExitSide.LEFT, ExitSide.RIGHT -> Orientation.HORIZONTAL
            ExitSide.TOP, ExitSide.BOTTOM -> Orientation.VERTICAL
        }
}

/**
 * Una nave u obstáculo del hangar (caza 1×2, carguero 1×3, meteorito 1×4,
 * 2×2 o 3×2).
 *
 * La posición se ancla en la **celda de origen** ([row], [col]): la más
 * arriba/izquierda de las que ocupa. Convención idéntica al bounding box de
 * los poliominós de Block Grid — hace trivial la matemática de anclaje y
 * evita discutir "cuál es la proa".
 *
 * @property id identificador estable dentro del nivel. La UI lo usa como key
 *           de composición para animar la MISMA nave entre estados.
 * @property orientation eje longitudinal = única dirección de movimiento.
 * @property length celdas que ocupa A LO LARGO de su eje de movimiento.
 * @property row fila de la celda de origen.
 * @property col columna de la celda de origen.
 * @property isVip true solo para la nave que debe escapar. Decide el acento
 *           visual (verde neón + glow) y la condición de victoria.
 * @property width celdas que ocupa PERPENDICULARES al eje (1 = nave clásica de
 *           Rush Hour; 2 = meteorito "gordo" 2×2/3×2). El movimiento sigue
 *           siendo estrictamente 1-D a lo largo de [orientation]: el ancho solo
 *           multiplica los carriles que estorba, no añade grados de libertad.
 */
data class Ship(
    val id: Int,
    val orientation: Orientation,
    val length: Int,
    val row: Int,
    val col: Int,
    val isVip: Boolean = false,
    val width: Int = 1,
) {

    /**
     * Coordenada de la nave a lo largo de su eje de movimiento (la única que
     * puede cambiar en toda la partida): `col` si es horizontal, `row` si es
     * vertical. Reducir la posición a este escalar es lo que convierte el
     * motor de colisiones en aritmética 1-D.
     */
    val axisPosition: Int
        get() = when (orientation) {
            Orientation.HORIZONTAL -> col
            Orientation.VERTICAL -> row
        }

    /**
     * Coordenada perpendicular al eje (fija durante toda la partida): la fila
     * de una nave horizontal, la columna de una vertical. Para piezas anchas
     * ([width] > 1) es el carril de ORIGEN; el resto son [lanes].
     */
    val lanePosition: Int
        get() = when (orientation) {
            Orientation.HORIZONTAL -> row
            Orientation.VERTICAL -> col
        }

    /**
     * Todos los carriles que la pieza estorba (perpendiculares al eje). Una
     * nave clásica ocupa uno solo; un meteorito 3×2 ocupa dos. Es el rango que
     * el motor consulta para saber si una celda ajena bloquea el deslizamiento.
     */
    val lanes: IntRange
        get() = lanePosition until lanePosition + width

    /**
     * Celdas del hangar que ocupa la pieza (length × width), de origen a popa.
     * Es la proyección canónica que usan tanto la validación de niveles como
     * el motor para construir el mapa de ocupación.
     */
    val occupiedCells: List<CellPos>
        get() = buildList {
            for (i in 0 until length) {
                for (w in 0 until width) {
                    when (orientation) {
                        Orientation.HORIZONTAL -> add(CellPos(row + w, col + i))
                        Orientation.VERTICAL -> add(CellPos(row + i, col + w))
                    }
                }
            }
        }

    /**
     * Copia de la nave desplazada a [newAxisPosition] en su eje. Es la ÚNICA
     * forma de mover una nave que expone el dominio: no existe API para
     * cambiar la fila de una horizontal ni la columna de una vertical, así el
     * compilador impide por construcción los movimientos ilegales.
     */
    fun movedTo(newAxisPosition: Int): Ship = when (orientation) {
        Orientation.HORIZONTAL -> copy(col = newAxisPosition)
        Orientation.VERTICAL -> copy(row = newAxisPosition)
    }
}

/**
 * Arrastre en curso de una nave, o null si no se toca nada.
 *
 * Vive en el estado (y no como estado local del composable) porque el offset
 * lo decide el MOTOR, no el dedo: la UI reporta deltas crudos y el motor los
 * acumula ya clampeados al intervalo libre. Si el dedo "empuja" contra otra
 * nave, el offset deja de crecer — ahí nace el feedback de choque (FASE 2).
 *
 * @property shipId nave levantada.
 * @property axisOffset desplazamiento visual acumulado respecto a
 *           [Ship.axisPosition], en **celdas fraccionales** (unidad de
 *           dominio; la UI lo multiplica por el tamaño de celda en px). Al
 *           soltar, el motor lo redondea a la celda válida más cercana (snap).
 */
data class ShipDrag(
    val shipId: Int,
    val axisOffset: Float = 0f,
)

/**
 * Estado puro del hangar en un instante. Es lo que el motor emite y la UI
 * dibuja; **no contiene nada de presentación** (colores, px, animaciones) —
 * solo geometría y relaciones. Inmutable: cada jugada produce un estado nuevo.
 *
 * @property exit esclusa del nivel (fija durante la partida).
 * @property ships todas las naves con su posición actual.
 * @property drag arrastre en curso, o null en reposo.
 * @property moves deslizamientos completados (un arrastre que termina en la
 *           misma celda donde empezó NO cuenta). Es la métrica de puntuación.
 * @property vipEscaped true cuando la VIP ya cruzó la esclusa: la UI lanza la
 *           animación de salida y, al terminar, confirma el nivel superado.
 * @property hangarSize lado del hangar del nivel en juego (crece con la
 *           progresión, [HANGAR_SIZE]..[MAX_HANGAR_SIZE]). Vive en el estado
 *           para que la UI dimensione rejilla y celdas sin conocer el nivel.
 */
data class StarportGameState(
    val exit: HangarExit = HangarExit(ExitSide.RIGHT, 2),
    val ships: List<Ship> = emptyList(),
    val drag: ShipDrag? = null,
    val moves: Int = 0,
    val vipEscaped: Boolean = false,
    val hangarSize: Int = HANGAR_SIZE,
) {

    /** Nave por id, o null si no existe (p. ej. la VIP ya retirada del hangar). */
    fun shipById(id: Int): Ship? = ships.firstOrNull { it.id == id }

    /** La nave VIP, o null si ya escapó y fue retirada del tablero. */
    val vipShip: Ship?
        get() = ships.firstOrNull { it.isVip }
}

/**
 * Definición inmutable de un nivel diseñado a mano.
 *
 * El `init` es la primera capa del invariante de no-superposición (ver
 * encabezado del archivo): valida el nivel al construirse para que un error
 * de diseño (naves montadas, VIP desalineada con la esclusa) falle en
 * desarrollo con un mensaje claro, nunca como bug silencioso en partida.
 *
 * @property number número de nivel 1-based (orden de desbloqueo).
 * @property exit esclusa del hangar.
 * @property ships disposición inicial de las naves. Exactamente una es VIP.
 * @property optimalMoves mínimo de movimientos conocido para resolverlo; el
 *           motor lo usará como "par" para puntuar con estrellas (FASE 2).
 * @property hangarSize lado del hangar de este nivel
 *           ([HANGAR_SIZE]..[MAX_HANGAR_SIZE]); crece con la progresión.
 */
data class StarportLevel(
    val number: Int,
    val exit: HangarExit,
    val ships: List<Ship>,
    val optimalMoves: Int,
    val hangarSize: Int = HANGAR_SIZE,
) {

    init {
        require(hangarSize in HANGAR_SIZE..MAX_HANGAR_SIZE) {
            "Nivel $number: lado de hangar $hangarSize fuera de $HANGAR_SIZE..$MAX_HANGAR_SIZE"
        }
        require(exit.index in 0 until hangarSize) {
            "Nivel $number: la esclusa apunta fuera del hangar"
        }
        val cells = ships.flatMap { it.occupiedCells }
        require(cells.all { it.isInside(hangarSize) }) {
            "Nivel $number: hay naves fuera del hangar ${hangarSize}x$hangarSize"
        }
        require(cells.size == cells.toSet().size) {
            "Nivel $number: hay naves superpuestas en la disposición inicial"
        }
        require(ships.count { it.isVip } == 1) {
            "Nivel $number: debe haber exactamente una nave VIP"
        }
        // Huellas permitidas: 2/3/4×1 (naves-meteorito lineales) y 2/3×2 (los
        // meteoritos "gordos"). Un 4×2 taparía media fila del hangar base y un
        // 1×1 podría quedar encajonado sin jugada posible: fuera de catálogo.
        require(
            ships.all {
                (it.width == 1 && it.length in SHIP_LENGTH_SHORT..SHIP_LENGTH_XLONG) ||
                    (it.width == 2 && it.length in SHIP_LENGTH_SHORT..SHIP_LENGTH_LONG)
            },
        ) {
            "Nivel $number: huella de nave fuera del catálogo (2..4×1 o 2..3×2)"
        }
        val vip = ships.first { it.isVip }
        // La VIP siempre es la cápsula clásica 1 de ancho: es la que debe caber
        // por la esclusa (una pieza de 2 carriles no cruza una apertura de 1).
        require(vip.width == 1 && vip.length in SHIP_LENGTH_SHORT..SHIP_LENGTH_LONG) {
            "Nivel $number: la VIP debe ser 1×2 o 1×3"
        }
        // La VIP debe poder cruzar la esclusa: mismo eje y mismo carril. Sin
        // esta validación un nivel podría ser irresoluble por diseño.
        require(vip.orientation == exit.requiredOrientation && vip.lanePosition == exit.index) {
            "Nivel $number: la nave VIP no está alineada con la esclusa"
        }
    }
}

/**
 * Intervalo libre `[min, max]` de la coordenada de eje de [ship] dado el resto
 * de piezas y el lado del hangar. Es LA regla de movimiento del juego —
 * compartida por el motor (clamping del arrastre) y por el solver BFS del
 * generador de niveles, para que ambos jueguen exactamente con la misma física.
 *
 * Solo estorban las celdas ajenas cuyo carril cae dentro de [Ship.lanes] (las
 * demás son inalcanzables por construcción del movimiento 1-D); de esas, la
 * más cercana por detrás fija `min` y la más cercana por delante fija `max`.
 *
 * O(piezas × celdas) por llamada — trivial para ≤ 15 piezas de ≤ 6 celdas.
 */
fun freeAxisRange(ship: Ship, ships: List<Ship>, hangarSize: Int): IntRange {
    var min = 0
    var max = hangarSize - ship.length
    for (other in ships) {
        if (other.id == ship.id) continue
        for (cell in other.occupiedCells) {
            val inLane = when (ship.orientation) {
                Orientation.HORIZONTAL -> cell.row in ship.lanes
                Orientation.VERTICAL -> cell.col in ship.lanes
            }
            if (!inLane) continue
            val obstacle = when (ship.orientation) {
                Orientation.HORIZONTAL -> cell.col
                Orientation.VERTICAL -> cell.row
            }
            if (obstacle < ship.axisPosition) {
                min = maxOf(min, obstacle + 1)
            } else {
                max = minOf(max, obstacle - ship.length)
            }
        }
    }
    return min..max
}
