package com.kortexgames.app.game.screws

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Modelo puro de "Tornillos Neón" (Neon Screws & Bolts)
 *
 * Responsabilidad: representar el tablero (agujeros, tornillos y placas) y la
 * geometría necesaria para las reglas del juego, **sin ninguna dependencia de
 * framework** (ni Compose ni coroutines) para que sea 100% testeable y
 * compartible, igual que [com.kortexgames.app.game.watersort.WaterSortModel].
 *
 * Decisiones clave:
 *  - **Coordenadas en unidades virtuales de tablero** (ancho fijo [BOARD_WIDTH]):
 *    el dominio no conoce píxeles ni densidades; la UI escala el lienzo completo
 *    con un único factor. Así los niveles se definen una vez y se ven idénticos
 *    en cualquier pantalla Android/iOS.
 *  - **La geometría ES la fuente de verdad de las uniones**: un tornillo sujeta
 *    una placa si (y solo si) su agujero de tablero coincide —con tolerancia
 *    [HOLE_ALIGN_EPSILON]— con un agujero de la placa transformado por su
 *    posición/rotación. No guardamos enlaces `tornillo→placa` redundantes que
 *    pudieran desincronizarse del dibujo: lo que se ve alineado, sujeta.
 *  - **Física simplificada y determinista**, sin motor externo: solo distancia
 *    punto-punto (uniones), punto-en-rectángulo-rotado (bloqueo de agujeros) y
 *    `atan2` (ángulo de reposo al colgar de un tornillo). Es suficiente para la
 *    mecánica, corre en cualquier plataforma y es trivial de testear.
 */

/** Ancho virtual del tablero. La UI mapea estas unidades a px con un solo factor. */
const val BOARD_WIDTH: Float = 1000f

/**
 * Tolerancia (en unidades de tablero) para considerar que un agujero de placa
 * está alineado con un agujero del tablero. Los niveles se diseñan con las
 * placas exactamente encajadas, pero los cálculos con rotaciones acumulan error
 * de coma flotante; un epsilon generoso (< separación mínima entre agujeros)
 * evita falsos negativos sin ambigüedad.
 */
const val HOLE_ALIGN_EPSILON: Float = 12f

/**
 * Vector/punto 2D inmutable en unidades de tablero. Se define aquí (y no se usa
 * `Offset` de Compose) para mantener el dominio libre de frameworks (§4 CLAUDE.md).
 */
data class Vec2(val x: Float, val y: Float) {

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    operator fun times(factor: Float): Vec2 = Vec2(x * factor, y * factor)

    /** Distancia euclídea a [other] (para la alineación de agujeros). */
    fun distanceTo(other: Vec2): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Rota este punto [angleRad] radianes alrededor del origen (rotación 2D
     * estándar). Positivo = antihorario con Y hacia abajo invertido; en la
     * práctica solo importa que dominio y UI compartan la misma convención.
     */
    fun rotate(angleRad: Float): Vec2 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** Ángulo del vector respecto al eje X positivo, en radianes (`atan2`). */
    fun angle(): Float = atan2(y, x)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

/**
 * Agujero perforado en el tablero de fondo. Es fijo durante toda la partida:
 * los niveles se definen con su lista completa de agujeros.
 *
 * @property id identificador estable dentro del nivel (para intents y estado).
 * @property position centro del agujero en unidades de tablero.
 */
data class BoardHole(
    val id: Int,
    val position: Vec2,
)

/**
 * Un tornillo. Su única propiedad mutable (vía `copy`) es en qué agujero del
 * tablero está clavado. No guarda referencia a placas: qué placas atraviesa se
 * deriva de la geometría (ver cabecera del archivo).
 *
 * @property id identificador estable dentro del nivel.
 * @property holeId agujero del tablero donde está clavado. Siempre no-null en
 *           reposo: la "mano" del jugador no existe en el dominio — la selección
 *           es estado de UI ([ScrewGameState.selectedScrewId]) y la recolocación
 *           es atómica (sale de un agujero y entra en otro en el mismo paso).
 */
data class Screw(
    val id: Int,
    val holeId: Int,
)

/**
 * Situación física de una placa. Dirige tanto las reglas como la animación:
 * la UI anima la transición entre estos estados con `spring`.
 */
enum class PlateStatus {
    /** Sujeta por 2+ tornillos: inmóvil en su rotación de diseño. */
    ANCHORED,

    /** Sujeta por exactamente 1 tornillo: cuelga de él rotada por gravedad. */
    HANGING,

    /**
     * Sin tornillos: está cayendo. Sigue en el estado (la UI anima la caída) y
     * se elimina cuando la UI notifica el fin de la animación
     * ([ScrewGameIntent.PlateFallFinished]) — así el dominio no necesita reloj.
     */
    FALLING,
}

/**
 * Placa rectangular que tapa parte del tablero. Se define por su centro, su
 * medio-tamaño y sus agujeros en coordenadas locales; la pose actual es
 * `center` + `rotation`.
 *
 * Se eligió **rectángulo rotado** como única forma (en vez de polígonos
 * arbitrarios) porque cubre toda la variedad de niveles del género y permite
 * que el test "¿esta placa tapa este punto?" sea exacto y barato: se transforma
 * el punto al espacio local de la placa (trasladar + rotar el ángulo inverso) y
 * se compara contra los semiejes — sin SAT ni triangulación.
 *
 * @property id identificador estable dentro del nivel.
 * @property center centro actual en unidades de tablero.
 * @property halfSize semiejes del rectángulo (ancho/2, alto/2) en local.
 * @property holeOffsets agujeros de la placa como offsets desde su centro, en
 *           coordenadas locales (rotan con la placa).
 * @property rotation rotación actual en radianes (0 = como se diseñó el nivel).
 * @property zIndex orden de apilado: mayor = más arriba. Decide el orden de
 *           dibujo y qué placa bloquea agujeros que otra placa inferior no tapa.
 * @property status situación física actual (ver [PlateStatus]).
 * @property pivotHoleId agujero del tablero del que cuelga cuando está
 *           [PlateStatus.HANGING] (el centro de rotación de la animación
 *           péndulo); null en cualquier otro estado.
 * @property colorIndex índice estable de color de acento (0..n): la UI lo mapea
 *           a la paleta neón de `LogicColors` — el dominio no conoce colores.
 */
data class Plate(
    val id: Int,
    val center: Vec2,
    val halfSize: Vec2,
    val holeOffsets: List<Vec2>,
    val rotation: Float = 0f,
    val zIndex: Int = 0,
    val status: PlateStatus = PlateStatus.ANCHORED,
    val pivotHoleId: Int? = null,
    val colorIndex: Int = 0,
) {

    /** Posiciones actuales (en tablero) de los agujeros de la placa. */
    val worldHoles: List<Vec2>
        get() = holeOffsets.map { center + it.rotate(rotation) }

    /**
     * ¿La placa cubre el punto [point]? Transforma el punto al espacio local
     * (rotación inversa alrededor del centro) y compara con los semiejes.
     * Base del **bloqueo de agujeros**: un agujero de tablero tapado por una
     * placa sin agujero propio alineado no admite tornillos.
     */
    fun covers(point: Vec2): Boolean {
        val local = (point - center).rotate(-rotation)
        return abs(local.x) <= halfSize.x && abs(local.y) <= halfSize.y
    }

    /**
     * ¿Algún agujero de la placa está alineado con [holePosition]? Si lo está,
     * ese agujero del tablero es accesible "a través" de la placa y un tornillo
     * clavado ahí la atraviesa (la sujeta).
     */
    fun hasHoleAlignedWith(holePosition: Vec2): Boolean =
        worldHoles.any { it.distanceTo(holePosition) <= HOLE_ALIGN_EPSILON }
}

/**
 * Definición completa de un nivel: geometría inicial de tablero, placas y
 * tornillos. Inmutable; el motor parte de aquí y evoluciona copias.
 *
 * @property boardHeight alto del tablero en unidades (el ancho es [BOARD_WIDTH]);
 *           varía por nivel para admitir tableros más largos con scroll futuro.
 * @property holes todos los agujeros perforados en el tablero.
 * @property plates placas iniciales (con su `zIndex` de apilado ya asignado).
 * @property screws tornillos iniciales, cada uno clavado en un agujero que
 *           debe atravesar las placas que sujeta (invariante del diseñador de
 *           niveles; el generador lo garantiza por construcción).
 */
data class ScrewLevel(
    val boardHeight: Float,
    val holes: List<BoardHole>,
    val plates: List<Plate>,
    val screws: List<Screw>,
)
