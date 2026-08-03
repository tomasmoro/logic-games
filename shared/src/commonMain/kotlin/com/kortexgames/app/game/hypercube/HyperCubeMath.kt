package com.kortexgames.app.game.hypercube

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Neon Hyper-Cube — primitivas matemáticas 3D (Fase 1)
 *
 * Mini-álgebra lineal propia para el motor 3D del cubo. **No usamos ninguna librería 3D**
 * (OpenGL, Filament, Scene…): todo el pipeline es aritmética pura sobre `Float`, así vive
 * íntegro en `commonMain` y se dibuja después con polígonos (`Path`) en el `Canvas` de Compose.
 *
 * ## Por qué un álgebra propia y tan pequeña
 * El pipeline del cubo solo necesita **rotaciones y proyección**: no hay traslaciones de modelo,
 * ni escalados no uniformes, ni recorte contra un frustum. Con eso, una matriz 3×3 basta y
 * evitamos el coste (y el ruido conceptual) de las 4×4 homogéneas: no hay componente `w` que
 * mantener ni división extra por término. La perspectiva se aplica una sola vez, al final, sobre
 * el punto ya rotado (ver Fase 3).
 *
 * ## Convenciones (fijas en todo el juego)
 *  - Sistema **diestro** (right-handed): +X a la derecha, +Y hacia arriba, +Z hacia el
 *    observador. La cámara mira desde +Z hacia el origen.
 *  - Ángulos en **radianes** y giros **antihorarios** vistos desde la punta del eje positivo
 *    (regla de la mano derecha). Esta convención es la que da sentido a
 *    [com.kortexgames.app.game.hypercube.TurnDirection].
 *  - Todas las estructuras son **inmutables** (`data class` con operaciones que devuelven copias),
 *    coherente con el estilo del proyecto y con MVI: un frame nunca muta el estado del anterior.
 *
 * ## Nota de rendimiento
 * Estas clases se instancian por vértice y por frame (54 pegatinas × 4 esquinas ≈ 216 puntos).
 * Es un volumen trivial para un móvil moderno, y a cambio ganamos código sin estado mutable
 * compartido —imprescindible porque el mismo cálculo se ejecuta desde el `Canvas` en cada
 * redibujado—. Si algún día hiciera falta, la optimización natural sería cachear la matriz de
 * cámara por frame (ya se hace: se construye una vez y se reutiliza para los 216 puntos), no
 * volver a estructuras mutables.
 */

/**
 * Vector/punto en el espacio 3D con componentes en coma flotante.
 *
 * Se usa para dos cosas distintas pero con la misma álgebra: **posiciones** (esquinas de las
 * pegatinas en espacio de modelo) y **direcciones** (normales de cara, usadas en el *backface
 * culling* de la Fase 3).
 *
 * @property x componente en el eje X (positivo hacia la derecha).
 * @property y componente en el eje Y (positivo hacia arriba).
 * @property z componente en el eje Z (positivo hacia el observador).
 */
data class Vector3(val x: Float, val y: Float, val z: Float) {

    /** Suma componente a componente. */
    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    /** Resta componente a componente. Útil para obtener el vector `destino - origen`. */
    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    /** Escalado uniforme por un factor. */
    operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    /**
     * Producto escalar: `a·b = |a||b|·cos(θ)`.
     *
     * En este juego su uso clave es el **backface culling**: si la normal de una pegatina (ya
     * rotada por la cámara) tiene `normal·(0,0,1) <= 0`, la cara apunta en dirección contraria
     * al observador y no se dibuja. El signo del coseno es todo lo que necesitamos.
     */
    infix fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    /**
     * Producto vectorial: devuelve un vector **perpendicular** al plano que forman `this` y
     * [other], con sentido dado por la regla de la mano derecha.
     *
     * Se usa al construir la base tangente de cada pegatina (ver
     * [HyperCubeGeometry.faceletCorners]): dada la normal, `n × u` produce el segundo eje del
     * plano de la cara garantizando que la terna (u, v, n) sea diestra —y por tanto que las
     * esquinas queden en orden **antihorario vistas desde fuera**, que es lo que hace fiable el
     * culling en Fase 3—.
     */
    infix fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Longitud euclídea del vector: `√(x²+y²+z²)`. */
    fun length(): Float = sqrt(x * x + y * y + z * z)

    /**
     * Devuelve el vector unitario en la misma dirección.
     *
     * Si el vector es (casi) nulo devuelve [ZERO] en lugar de dividir por cero: no hay dirección
     * definida y propagar un `NaN` al `Canvas` provocaría polígonos corruptos difíciles de
     * depurar.
     */
    fun normalized(): Vector3 {
        val len = length()
        return if (len < EPSILON) ZERO else Vector3(x / len, y / len, z / len)
    }

    companion object {
        /** Vector nulo. También el valor de repliegue de [normalized]. */
        val ZERO = Vector3(0f, 0f, 0f)

        /** Eje +Z: dirección en la que mira la cámara (hacia el observador). */
        val UNIT_Z = Vector3(0f, 0f, 1f)

        /** Umbral por debajo del cual una longitud se considera cero (evita dividir por ~0). */
        const val EPSILON = 1e-6f
    }
}

/**
 * Vector de **enteros** en el retículo 3D. Es el tipo de las coordenadas lógicas del cubo:
 * posición de cada cubie (cada componente ∈ {-1, 0, 1}) y normal de cada pegatina (un eje
 * unitario con signo, p. ej. (0, 1, 0) = cara superior).
 *
 * ## Por qué enteros y no [Vector3]
 * El estado del cubo se rota decenas de veces por partida (mezcla + jugadas). Con `Float`, cada
 * giro de 90° aplicaría `cos/sin` y acumularía error: tras suficientes giros, un `0f` se
 * convertiría en `1e-7f` y **el detector de "cubo resuelto" dejaría de funcionar** por
 * comparaciones que ya no son exactas. Con enteros, un giro de 90° es un simple intercambio de
 * componentes con cambio de signo: **exacto, sin deriva y comparable con `==`**.
 *
 * La conversión a coma flotante ocurre solo en la frontera de render ([toVector3]).
 *
 * @property x componente X ∈ {-1, 0, 1} para posiciones de cubie.
 * @property y componente Y ∈ {-1, 0, 1} para posiciones de cubie.
 * @property z componente Z ∈ {-1, 0, 1} para posiciones de cubie.
 */
data class IntVec3(val x: Int, val y: Int, val z: Int) {

    /** Componente correspondiente a [axis]. Permite consultar "en qué capa está" sin `when`. */
    fun component(axis: Axis): Int = when (axis) {
        Axis.X -> x
        Axis.Y -> y
        Axis.Z -> z
    }

    /**
     * Gira el vector **exactamente 90°** alrededor de [axis] en el sentido [direction].
     *
     * Es la operación medular del juego: se aplica tanto a la posición de un cubie como a la
     * normal de cada una de sus pegatinas cuando su capa completa un giro.
     *
     * ## De la matriz a la permutación
     * Sustituyendo `θ = ±90°` en las matrices de [Mat3] —donde `cos(90°) = 0` y `sin(90°) = 1`—
     * todos los términos se anulan o valen ±1, y el producto matriz-vector degenera en
     * "intercambia dos componentes y niega una". Para el sentido antihorario (+90°, visto desde
     * el eje positivo):
     *
     * ```
     * X: (x, y, z) → (x, -z,  y)
     * Y: (x, y, z) → (z,  y, -x)
     * Z: (x, y, z) → (-y, x,  z)
     * ```
     *
     * El sentido horario es la transformación inversa (equivale a aplicar la antihoraria tres
     * veces, pero se escribe directa para que un giro cueste siempre lo mismo).
     *
     * **Por qué importa que sea aritmética entera:** este método se ejecuta decenas de veces por
     * partida sobre el mismo dato. Al no usar `cos/sin` no hay error de redondeo que acumular,
     * así que tras cualquier número de giros las coordenadas siguen siendo exactamente
     * `{-1, 0, 1}` y el detector de "cubo resuelto" puede comparar con `==` sin tolerancias.
     */
    fun rotatedQuarter(axis: Axis, direction: TurnDirection): IntVec3 {
        val ccw = direction == TurnDirection.COUNTER_CLOCKWISE
        return when (axis) {
            Axis.X -> if (ccw) IntVec3(x, -z, y) else IntVec3(x, z, -y)
            Axis.Y -> if (ccw) IntVec3(z, y, -x) else IntVec3(-z, y, x)
            Axis.Z -> if (ccw) IntVec3(-y, x, z) else IntVec3(y, -x, z)
        }
    }

    /** Conversión a coma flotante para el pipeline de render (única frontera int → float). */
    fun toVector3(): Vector3 = Vector3(x.toFloat(), y.toFloat(), z.toFloat())

    /**
     * Producto vectorial entero, con la misma fórmula que [Vector3.cross].
     *
     * Existe en versión entera porque se usa sobre **ejes cardinales** en dos sitios donde el
     * resultado debe ser exacto y comparable: la base tangente de cada pegatina
     * ([HyperCubeGeometry.tangentBasis]) y, sobre todo, la deducción del eje de giro a partir de
     * un arrastre (`eje = normal × dirección`), que se traduce directamente a un [Axis] y un
     * [TurnDirection]. Con `Float` habría que redondear y decidir umbrales; con enteros el
     * resultado ya *es* uno de los seis ejes unitarios.
     */
    /** Escalado entero. Se usa sobre todo con `±1` para invertir el sentido de un eje tangente. */
    operator fun times(scalar: Int): IntVec3 = IntVec3(x * scalar, y * scalar, z * scalar)

    infix fun cross(other: IntVec3): IntVec3 = IntVec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    companion object {
        /** Las seis direcciones cardinales: normales posibles de una pegatina. */
        val RIGHT = IntVec3(1, 0, 0)
        val LEFT = IntVec3(-1, 0, 0)
        val UP = IntVec3(0, 1, 0)
        val DOWN = IntVec3(0, -1, 0)
        val FRONT = IntVec3(0, 0, 1)
        val BACK = IntVec3(0, 0, -1)
    }
}

/**
 * Los tres ejes de giro del cubo. Un dominio cerrado de tres valores, modelado como `enum`
 * (nunca un `Int` suelto) para que capa, cámara y animación hablen el mismo lenguaje de tipos.
 *
 * En la nomenclatura clásica del cubo de Rubik: girar en [X] mueve las capas R/M/L, en [Y] las
 * U/E/D y en [Z] las F/S/B.
 */
enum class Axis {
    /** Eje horizontal (izquierda ↔ derecha). */
    X,

    /** Eje vertical (abajo ↔ arriba). */
    Y,

    /** Eje de profundidad (fondo ↔ observador). */
    Z,
}

/**
 * Matriz 3×3 en orden *row-major* (`mRC` = fila R, columna C).
 *
 * Solo representa **rotaciones** (matrices ortonormales), que es lo único que el motor necesita:
 * orientar la cámara y animar el giro parcial de una capa. Al no haber traslación ni proyección
 * dentro de la matriz, 3×3 es suficiente y el producto matriz-vector son 9 multiplicaciones.
 *
 * Composición: `A * B` aplica **primero B y luego A** (convención estándar de matrices columna,
 * `v' = A·(B·v)`).
 */
data class Mat3(
    val m00: Float, val m01: Float, val m02: Float,
    val m10: Float, val m11: Float, val m12: Float,
    val m20: Float, val m21: Float, val m22: Float,
) {

    /**
     * Transforma un vector: `v' = M·v`, con `v` tratado como vector columna.
     *
     * `v'.x = m00·x + m01·y + m02·z` (y análogamente para las otras dos filas).
     */
    operator fun times(v: Vector3): Vector3 = Vector3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    /**
     * Producto de matrices: `C = this · other`, es decir, aplicar [other] primero y `this`
     * después. `C[i][j] = Σ_k this[i][k] · other[k][j]`.
     */
    operator fun times(other: Mat3): Mat3 = Mat3(
        m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
        m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
        m00 * other.m02 + m01 * other.m12 + m02 * other.m22,

        m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
        m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
        m10 * other.m02 + m11 * other.m12 + m12 * other.m22,

        m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
        m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
        m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
    )

    companion object {
        /** Matriz identidad: no rota nada. */
        val IDENTITY = Mat3(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

        /**
         * Rotación de [rad] radianes alrededor del eje **X** (antihoraria vista desde +X).
         *
         * ```
         * | 1   0     0   |
         * | 0  cos  -sin  |
         * | 0  sin   cos  |
         * ```
         * Es la componente de *pitch* de la cámara: inclinar el cubo hacia delante/atrás.
         */
        fun rotationX(rad: Float): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(
                1f, 0f, 0f,
                0f, c, -s,
                0f, s, c,
            )
        }

        /**
         * Rotación de [rad] radianes alrededor del eje **Y** (antihoraria vista desde +Y).
         *
         * ```
         * |  cos  0  sin |
         * |   0   1   0  |
         * | -sin  0  cos |
         * ```
         * Es la componente de *yaw* de la cámara: el giro horizontal del cubo.
         */
        fun rotationY(rad: Float): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(
                c, 0f, s,
                0f, 1f, 0f,
                -s, 0f, c,
            )
        }

        /**
         * Rotación de [rad] radianes alrededor del eje **Z** (antihoraria vista desde +Z).
         *
         * ```
         * | cos  -sin  0 |
         * | sin   cos  0 |
         * |  0     0   1 |
         * ```
         */
        fun rotationZ(rad: Float): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(
                c, -s, 0f,
                s, c, 0f,
                0f, 0f, 1f,
            )
        }

        /**
         * Rotación de [rad] radianes alrededor del eje indicado por [axis].
         *
         * La usa la **animación del giro de capa**: mientras una rebanada está en vuelo, sus
         * pegatinas se dibujan con un ángulo intermedio (`progreso × 90°`) mientras el resto del
         * cubo permanece quieto. El estado lógico solo cambia al completarse el giro, de forma
         * discreta y exacta (ver [IntVec3]).
         */
        fun rotation(axis: Axis, rad: Float): Mat3 = when (axis) {
            Axis.X -> rotationX(rad)
            Axis.Y -> rotationY(rad)
            Axis.Z -> rotationZ(rad)
        }

        /**
         * Matriz de **cámara orbital** a partir de los dos ángulos que el jugador controla al
         * arrastrar fuera del cubo.
         *
         * Se compone como `Rx(pitch) · Ry(yaw)`: primero gira el cubo sobre su eje vertical
         * (yaw) y **después** se inclina en el espacio de la vista (pitch). Ese orden es el que
         * produce la sensación de "plataforma giratoria" que espera el usuario; el orden inverso
         * haría que el eje de inclinación acompañase al giro y el control se sentiría errático.
         *
         * @param yawRad giro horizontal acumulado, en radianes.
         * @param pitchRad inclinación vertical acumulada, en radianes (se limita en el motor a
         *   [HyperCubeGeometry.MAX_PITCH_RAD] para no cruzar los polos y perder la referencia).
         */
        fun camera(yawRad: Float, pitchRad: Float): Mat3 = rotationX(pitchRad) * rotationY(yawRad)
    }
}
