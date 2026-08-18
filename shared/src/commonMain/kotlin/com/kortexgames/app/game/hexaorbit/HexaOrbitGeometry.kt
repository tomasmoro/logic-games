package com.kortexgames.app.game.hexaorbit

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Geometría de Hexa Orbit — FASE 1
 *
 * Convierte el grafo hexagonal (coordenadas axiales, índices de arista) en **puntos del plano**,
 * y define la forma de las curvas Bézier que recorren los azulejos.
 *
 * ## Unidades: radio de hexágono, no píxeles
 *
 * Todo aquí se mide en **radios de hexágono** (centro → vértice = `1f`). La pantalla decide en
 * FASE 3 cuántos píxeles vale ese radio según el espacio disponible y multiplica. Así:
 *  - el dominio no depende de la densidad de pantalla ni del tamaño del `Canvas`;
 *  - un test puede afirmar "el orbe está a 0.3 radios del puntero" sin simular una pantalla;
 *  - cambiar el tamaño del tablero es un solo factor de escala en el dibujo, no un recálculo.
 *
 * ## Qué NO está aquí (frontera con FASE 2)
 *
 * Este archivo describe **dónde están las cosas y qué forma tiene cada curva**. *Recorrer* esa
 * curva —evaluar la Bézier en `t`, medir su longitud de arco y convertir velocidad en avance de
 * `progress`— es física del puntero y llega en FASE 2, junto al bucle de juego.
 */

/**
 * Punto del plano en unidades de radio de hexágono.
 *
 * Existe en vez de reutilizar `androidx.compose.ui.geometry.Offset` porque el dominio no debe
 * importar Compose (regla de arquitectura del proyecto): así el motor compila y se testea sin
 * el runtime de UI, y la pantalla convierte a `Offset` en su frontera.
 *
 * @property x eje horizontal, positivo hacia la derecha.
 * @property y eje vertical, positivo **hacia abajo** (convención de `Canvas`, no matemática).
 */
data class HexPoint(val x: Float, val y: Float) {

    operator fun plus(other: HexPoint): HexPoint = HexPoint(x + other.x, y + other.y)

    operator fun minus(other: HexPoint): HexPoint = HexPoint(x - other.x, y - other.y)

    /** Escalado desde el origen; con `factor < 1` acerca el punto al centro del azulejo. */
    operator fun times(factor: Float): HexPoint = HexPoint(x * factor, y * factor)

    /** Distancia euclídea, en radios de hexágono. */
    fun distanceTo(other: HexPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Origen: el centro del azulejo `(0, 0)`. */
        val ZERO: HexPoint = HexPoint(0f, 0f)
    }
}

/**
 * Curva Bézier **cúbica** que describe un camino interno del azulejo, en coordenadas locales
 * (relativas al centro del azulejo).
 *
 * Cúbica y no cuadrática porque con dos puntos de control la curva puede entrar y salir
 * **perpendicular a cada arista**: es lo que hace que dos azulejos contiguos empalmen sin un
 * codo visible en la frontera. Con un solo control eso solo se consigue en los caminos rectos.
 *
 * @property start punto de entrada (punto medio de la arista de origen).
 * @property control1 control asociado a [start].
 * @property control2 control asociado a [end].
 * @property end punto de salida (punto medio de la arista de destino).
 */
data class HexCurve(
    val start: HexPoint,
    val control1: HexPoint,
    val control2: HexPoint,
    val end: HexPoint,
) {
    /** La misma curva recorrida al revés: el puntero puede entrar por cualquiera de las puntas. */
    fun reversed(): HexCurve = HexCurve(end, control2, control1, start)
}

/**
 * Conversiones entre el grafo hexagonal y el plano, para hexágonos **pointy-top**.
 *
 * La disposición pointy-top se elige porque el tablero resultante es más ancho que alto, que es
 * la forma que mejor aprovecha una pantalla de móvil en vertical dejando sitio arriba para el
 * marcador.
 */
object HexGeometry {

    /** `√3`, factor de la anchura de un hexágono pointy-top respecto a su radio. */
    private val SQRT_3: Float = sqrt(3f)

    /**
     * Distancia del centro al punto medio de una arista (la "apotema"), `√3 / 2` para un
     * hexágono de radio 1. Es donde empiezan y acaban todas las curvas.
     */
    val APOTHEM: Float = SQRT_3 / 2f

    /**
     * Centro del azulejo [coord] en el espacio del tablero.
     *
     * Fórmula estándar axial → cartesiano para pointy-top: avanzar en `q` desplaza `√3` en
     * horizontal; avanzar en `r` desplaza `1.5` en vertical y **medio paso** en horizontal —ese
     * `r / 2` es el "escalonado" de las filas hexagonales, que en axiales sale gratis en vez de
     * requerir casos según la paridad de la fila.
     */
    fun center(coord: HexCoord): HexPoint = HexPoint(
        x = SQRT_3 * (coord.q + coord.r / 2f),
        y = 1.5f * coord.r,
    )

    /**
     * Punto medio de la arista [edge], **relativo al centro** del azulejo.
     *
     * Como el índice de arista coincide con el ángulo de pantalla (`60° · edge`, ver la cabecera
     * de `HexaOrbitModels`), basta con un polar → cartesiano. Coincide por construcción con la
     * mitad del vector al vecino: es el mismo punto visto desde los dos azulejos, que es lo que
     * garantiza que los caminos empalmen sin salto en la frontera.
     */
    fun edgeMidpoint(edge: Int): HexPoint {
        val angle = EDGE_ANGLE_RAD * edge.mod(HEX_EDGES)
        return HexPoint(APOTHEM * cos(angle), APOTHEM * sin(angle))
    }

    /**
     * Vértice [index] del hexágono, relativo al centro y a distancia 1 (el radio).
     *
     * Los vértices van a mitad de camino angular entre dos aristas (`30° + 60°·i`), así que el
     * vértice `i` es justamente la esquina compartida por las aristas `i` e `i + 1`. La UI los
     * usa para dibujar el contorno de la celda.
     */
    fun corner(index: Int): HexPoint {
        val angle = EDGE_ANGLE_RAD * index.mod(HEX_EDGES) + EDGE_ANGLE_RAD / 2f
        return HexPoint(cos(angle), sin(angle))
    }

    /**
     * Curva local que recorre el camino [pair] del azulejo, de la arista menor a la mayor.
     *
     * ## Por qué los controles son "el extremo acercado al centro"
     *
     * Ambos controles se calculan como `extremo × k`. Al ser las coordenadas locales (centro en
     * el origen), escalar un extremo lo desliza **sobre la recta que lo une con el centro**, que
     * es exactamente la normal a esa arista: la curva entra y sale perpendicular a la frontera
     * y empalma sin codo con la del azulejo vecino. Todo el diseño de la curva se reduce a
     * elegir un `k` por curvatura:
     *
     *  - **Recta** (`k = 1/3`): como las dos aristas son opuestas, `start`, controles y `end`
     *    quedan alineados con el centro y la Bézier degenera exactamente en el segmento. No hace
     *    falta un caso especial para las rectas.
     *  - **Giro amplio** (`k = 0.5`): arco suave que pasa a media distancia del centro.
     *  - **Giro cerrado** (`k = 0.75`): controles cerca de las aristas, así que la curva se abre
     *    hacia la esquina compartida en vez de hundirse hacia el centro. Con un `k` pequeño el
     *    giro se vería como un pico apuntando al centro, no como el codo redondeado de un tubo
     *    de neón.
     */
    fun curveFor(pair: EdgePair): HexCurve {
        val start = edgeMidpoint(pair.a)
        val end = edgeMidpoint(pair.b)
        val k = when (pair.curvature) {
            PathCurvature.STRAIGHT -> STRAIGHT_CONTROL_FACTOR
            PathCurvature.WIDE -> WIDE_CONTROL_FACTOR
            PathCurvature.SHARP -> SHARP_CONTROL_FACTOR
        }
        return HexCurve(
            start = start,
            control1 = start * k,
            control2 = end * k,
            end = end,
        )
    }

    /**
     * Curva de un camino ya situada en el tablero y **orientada en el sentido de la marcha**:
     * empieza en [entryEdge] y acaba en [exitEdge].
     *
     * La orientación importa porque el `progress` del puntero va de 0 (entrada) a 1 (salida): si
     * la curva viniera siempre de la arista menor a la mayor, la mitad de los tramos se
     * recorrerían al revés.
     */
    fun orientedCurve(coord: HexCoord, entryEdge: Int, exitEdge: Int): HexCurve {
        val pair = edgePairOf(entryEdge.mod(HEX_EDGES), exitEdge.mod(HEX_EDGES))
        val local = curveFor(pair)
        val directed = if (pair.a == entryEdge.mod(HEX_EDGES)) local else local.reversed()
        val origin = center(coord)
        return HexCurve(
            start = directed.start + origin,
            control1 = directed.control1 + origin,
            control2 = directed.control2 + origin,
            end = directed.end + origin,
        )
    }

    /**
     * Celda que contiene el punto [point] del espacio del tablero: la inversa de [center].
     *
     * Es lo que convierte un tap en una jugada, así que **tiene que acertar siempre**: si
     * devolviera la celda de al lado, el jugador giraría una pieza que no quería.
     *
     * ## Por qué no basta con redondear `q` y `r`
     *
     * Invertir [center] es trivial y da coordenadas axiales fraccionarias. El problema es
     * redondearlas: las celdas hexagonales no son rectángulos, así que redondear `q` y `r` por
     * separado asigna mal los puntos cercanos a los vértices —justo las esquinas donde se juntan
     * tres celdas, que es donde el dedo aterriza con más ambigüedad—.
     *
     * La solución estándar es el **redondeo cúbico**: se pasa a las tres coordenadas
     * `(q, r, s)` con `q + r + s = 0`, se redondean las tres y, como el redondeo puede romper esa
     * suma, se **descarta la que más se movió** y se recalcula a partir de las otras dos. Eso
     * equivale a proyectar sobre el plano `q + r + s = 0` y siempre cae en la celda correcta.
     */
    fun hexAt(point: HexPoint): HexCoord {
        val rFrac = point.y / 1.5f
        val qFrac = point.x / SQRT_3 - rFrac / 2f
        val sFrac = -qFrac - rFrac

        var q = kotlin.math.round(qFrac)
        var r = kotlin.math.round(rFrac)
        val s = kotlin.math.round(sFrac)

        val dq = kotlin.math.abs(q - qFrac)
        val dr = kotlin.math.abs(r - rFrac)
        val ds = kotlin.math.abs(s - sFrac)

        // Se corrige el eje con MAYOR error de redondeo: es el que menos información aporta
        // sobre en qué celda cayó el punto.
        if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s

        return HexCoord(q.toInt(), r.toInt())
    }

    /** Ángulo entre aristas consecutivas: 60° en radianes. */
    private const val EDGE_ANGLE_RAD: Float = (2.0 * kotlin.math.PI / HEX_EDGES).toFloat()

    /** Factor de control de un camino recto (deja la Bézier alineada = segmento exacto). */
    private const val STRAIGHT_CONTROL_FACTOR: Float = 1f / 3f

    /** Factor de control de un giro amplio (60°). */
    private const val WIDE_CONTROL_FACTOR: Float = 0.5f

    /** Factor de control de un giro cerrado (120°); alto para redondear hacia la esquina. */
    private const val SHARP_CONTROL_FACTOR: Float = 0.75f
}
