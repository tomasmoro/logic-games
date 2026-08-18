package com.kortexgames.app.game.hexaorbit

/**
 * # Física de recorrido de Hexa Orbit — FASE 2
 *
 * Cierra la frontera que dejó abierta FASE 1: allí se definió **qué forma** tiene cada curva
 * ([HexGeometry.curveFor]); aquí se define **cómo se recorre**, que es lo que convierte una
 * rapidez en segundos en un avance concreto sobre la Bézier.
 *
 * ## El problema: `t` no avanza a ritmo constante
 *
 * En una Bézier cúbica el parámetro `t` **no** es proporcional a la distancia recorrida: la
 * curva "corre" por el centro y se "frena" en los extremos. Si el puntero avanzara `t += v·dt`,
 * se vería acelerar y frenar dentro de cada azulejo sin motivo, y peor aún: el efecto sería
 * distinto en un giro cerrado que en una recta, así que la sensación de velocidad cambiaría
 * según la pieza que tocara. En un juego que se gana o se pierde por reflejos, eso es
 * inaceptable.
 *
 * La solución estándar es **reparametrizar por longitud de arco**: se tabula la relación
 * `distancia → t` una vez y se consulta en cada frame.
 *
 * ## Por qué la tabla es gratis aquí: solo hay TRES curvas
 *
 * Un tablero de radio 3 tiene 37 azulejos × 3 caminos = 111 curvas dibujadas… pero todas son
 * **congruentes** con una de tres: rotarlas y trasladarlas no cambia su longitud (son
 * isometrías). Las 111 curvas son, salvo movimiento rígido, la recta, el giro amplio y el giro
 * cerrado. Así que basta con **tres tablas de arco calculadas una sola vez** al cargar la clase,
 * y la consulta por frame es una búsqueda binaria sobre 48 muestras: coste despreciable frente
 * a mantener una tabla por curva (o, peor, integrar la longitud en cada frame).
 */

/**
 * Punto de la curva en el parámetro [t] de la Bézier (`0f` = [HexCurve.start], `1f` =
 * [HexCurve.end]).
 *
 * Es la forma polinómica directa de la cúbica de Bernstein. No se usa el algoritmo de De
 * Casteljau —numéricamente más estable— porque con coordenadas en el rango `[-6, 6]` y `Float`
 * la diferencia es invisible, y esta versión evalúa la curva sin reservar puntos intermedios,
 * cosa que importa cuando se llama varias veces por frame.
 *
 * @param t parámetro de la curva; se recorta a `[0, 1]` para tolerar errores de redondeo del
 *   acumulador de distancia sin salirse del tramo.
 */
fun HexCurve.pointAt(t: Float): HexPoint {
    val clamped = t.coerceIn(0f, 1f)
    val u = 1f - clamped
    val w0 = u * u * u
    val w1 = 3f * u * u * clamped
    val w2 = 3f * u * clamped * clamped
    val w3 = clamped * clamped * clamped
    return HexPoint(
        x = w0 * start.x + w1 * control1.x + w2 * control2.x + w3 * end.x,
        y = w0 * start.y + w1 * control1.y + w2 * control2.y + w3 * end.y,
    )
}

/**
 * Tabla de longitud de arco de una curva: muestras `(t, distancia acumulada)` que permiten
 * traducir "he recorrido X" a "estoy en el parámetro t".
 *
 * @property cumulative distancia acumulada en cada muestra; `cumulative[0] == 0f` y el último
 *   elemento es la longitud total. Es monótona creciente, que es lo que habilita la búsqueda
 *   binaria.
 */
class ArcLengthTable private constructor(private val cumulative: FloatArray) {

    /** Longitud total de la curva, en radios de hexágono. */
    val length: Float get() = cumulative[cumulative.size - 1]

    /**
     * Parámetro `t` de la Bézier en el que la curva lleva recorrida la fracción [fraction] de su
     * longitud.
     *
     * Busca el tramo de la tabla que contiene esa distancia (búsqueda binaria, O(log n)) e
     * **interpola linealmente dentro del tramo**. Con 48 muestras el error de esa interpolación
     * queda muy por debajo de un píxel a cualquier tamaño de tablero razonable, así que no
     * compensa refinar con Newton-Raphson.
     *
     * @param fraction avance normalizado `0..1` (el `progress` del puntero).
     */
    fun parameterAt(fraction: Float): Float {
        val target = fraction.coerceIn(0f, 1f) * length
        var low = 0
        var high = cumulative.size - 1
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (cumulative[mid] <= target) low = mid else high = mid
        }
        val spanLength = cumulative[high] - cumulative[low]
        // Tramo degenerado (dos muestras a distancia 0): devolver el extremo evita un 0/0.
        val within = if (spanLength <= 0f) 0f else (target - cumulative[low]) / spanLength
        val step = 1f / (cumulative.size - 1)
        return (low + within) * step
    }

    internal companion object {

        /**
         * Muestras por curva. 48 es holgado: con la curvatura suave de estas Bézier el error de
         * longitud ya es despreciable a 24, y la tabla se construye una única vez en la vida del
         * proceso, así que duplicar muestras no cuesta nada medible.
         */
        private const val SAMPLES = 48

        /** Tabula [curve] muestreándola uniformemente en `t` y acumulando distancias rectas. */
        fun of(curve: HexCurve): ArcLengthTable {
            val cumulative = FloatArray(SAMPLES + 1)
            var previous = curve.pointAt(0f)
            var total = 0f
            for (i in 1..SAMPLES) {
                val point = curve.pointAt(i.toFloat() / SAMPLES)
                total += previous.distanceTo(point)
                cumulative[i] = total
                previous = point
            }
            return ArcLengthTable(cumulative)
        }
    }
}

/**
 * Métricas de recorrido compartidas por todas las curvas del tablero, indexadas por curvatura.
 *
 * Las tres tablas se construyen al cargar el objeto a partir de un representante de cada
 * curvatura. Son válidas para **cualquier** azulejo y rotación porque rotar y trasladar no
 * alteran la longitud de una curva (ver la cabecera del archivo).
 */
object HexCurveMetrics {

    private val tables: Map<PathCurvature, ArcLengthTable> = mapOf(
        PathCurvature.STRAIGHT to ArcLengthTable.of(HexGeometry.curveFor(EdgePair(0, 3))),
        PathCurvature.WIDE to ArcLengthTable.of(HexGeometry.curveFor(EdgePair(0, 2))),
        PathCurvature.SHARP to ArcLengthTable.of(HexGeometry.curveFor(EdgePair(0, 1))),
    )

    /** Longitud, en radios de hexágono, de cualquier camino con esta [curvature]. */
    fun lengthOf(curvature: PathCurvature): Float = tables.getValue(curvature).length

    /** Longitud del tramo que va de [entryEdge] a [exitEdge] dentro de un azulejo. */
    fun lengthOf(entryEdge: Int, exitEdge: Int): Float =
        lengthOf(PathCurvature.between(entryEdge.mod(HEX_EDGES), exitEdge.mod(HEX_EDGES)))

    /** Parámetro `t` de la Bézier correspondiente al avance normalizado [fraction]. */
    fun parameterAt(curvature: PathCurvature, fraction: Float): Float =
        tables.getValue(curvature).parameterAt(fraction)
}

/**
 * Posición 2D del puntero descrita por [step] con el avance normalizado [progress].
 *
 * Es la traducción "grafo → plano" que consume tanto el motor (para medir la distancia a los
 * orbes) como el renderizado (para dibujar el orbe y su estela).
 */
fun pointOnStep(step: TraversalStep, progress: Float): HexPoint {
    val curve = step.curve()
    val curvature = PathCurvature.between(step.entryEdge, step.exitEdge)
    return curve.pointAt(HexCurveMetrics.parameterAt(curvature, progress))
}
