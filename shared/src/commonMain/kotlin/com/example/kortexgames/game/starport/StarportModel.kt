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

/** Lado del hangar cuadrado. 6×6 es el tablero canónico del género Rush Hour. */
const val HANGAR_SIZE: Int = 6

/** Longitud de las naves cortas (cazas). */
const val SHIP_LENGTH_SHORT: Int = 2

/** Longitud de las naves largas (cargueros). */
const val SHIP_LENGTH_LONG: Int = 3

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

    /** ¿La celda cae dentro del hangar? */
    val isInsideHangar: Boolean
        get() = row in 0 until HANGAR_SIZE && col in 0 until HANGAR_SIZE
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
 * Una nave del hangar (caza 1×2 o carguero 1×3).
 *
 * La posición se ancla en la **celda de origen** ([row], [col]): la más
 * arriba/izquierda de las que ocupa. Convención idéntica al bounding box de
 * los poliominós de Block Grid — hace trivial la matemática de anclaje y
 * evita discutir "cuál es la proa".
 *
 * @property id identificador estable dentro del nivel. La UI lo usa como key
 *           de composición para animar la MISMA nave entre estados.
 * @property orientation eje longitudinal = única dirección de movimiento.
 * @property length celdas que ocupa ([SHIP_LENGTH_SHORT] o [SHIP_LENGTH_LONG]).
 * @property row fila de la celda de origen.
 * @property col columna de la celda de origen.
 * @property isVip true solo para la nave que debe escapar. Decide el acento
 *           visual (verde neón + glow) y la condición de victoria.
 */
data class Ship(
    val id: Int,
    val orientation: Orientation,
    val length: Int,
    val row: Int,
    val col: Int,
    val isVip: Boolean = false,
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
     * de una nave horizontal, la columna de una vertical.
     */
    val lanePosition: Int
        get() = when (orientation) {
            Orientation.HORIZONTAL -> row
            Orientation.VERTICAL -> col
        }

    /**
     * Celdas del hangar que ocupa la nave, de origen a popa. Es la proyección
     * canónica que usan tanto la validación de niveles como el motor para
     * construir el mapa de ocupación.
     */
    val occupiedCells: List<CellPos>
        get() = (0 until length).map { i ->
            when (orientation) {
                Orientation.HORIZONTAL -> CellPos(row, col + i)
                Orientation.VERTICAL -> CellPos(row + i, col)
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
 */
data class StarportGameState(
    val exit: HangarExit = HangarExit(ExitSide.RIGHT, 2),
    val ships: List<Ship> = emptyList(),
    val drag: ShipDrag? = null,
    val moves: Int = 0,
    val vipEscaped: Boolean = false,
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
 */
data class StarportLevel(
    val number: Int,
    val exit: HangarExit,
    val ships: List<Ship>,
    val optimalMoves: Int,
) {

    init {
        val cells = ships.flatMap { it.occupiedCells }
        require(cells.all { it.isInsideHangar }) {
            "Nivel $number: hay naves fuera del hangar ${HANGAR_SIZE}x$HANGAR_SIZE"
        }
        require(cells.size == cells.toSet().size) {
            "Nivel $number: hay naves superpuestas en la disposición inicial"
        }
        require(ships.count { it.isVip } == 1) {
            "Nivel $number: debe haber exactamente una nave VIP"
        }
        require(ships.all { it.length == SHIP_LENGTH_SHORT || it.length == SHIP_LENGTH_LONG }) {
            "Nivel $number: las naves miden $SHIP_LENGTH_SHORT (caza) o $SHIP_LENGTH_LONG (carguero)"
        }
        val vip = ships.first { it.isVip }
        // La VIP debe poder cruzar la esclusa: mismo eje y mismo carril. Sin
        // esta validación un nivel podría ser irresoluble por diseño.
        require(vip.orientation == exit.requiredOrientation && vip.lanePosition == exit.index) {
            "Nivel $number: la nave VIP no está alineada con la esclusa"
        }
    }
}
