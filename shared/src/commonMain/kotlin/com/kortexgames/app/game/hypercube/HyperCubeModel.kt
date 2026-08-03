package com.kortexgames.app.game.hypercube

import kotlin.math.PI

/**
 * # Neon Hyper-Cube — modelo de dominio
 *
 * Representación pura e inmutable de un cubo mágico **3×3×3** (categoría *Visión Espacial*).
 * No depende de Compose ni de ninguna API de plataforma: es la fuente de verdad que el motor
 * (Fase 2) transforma con giros de 90° y que el `Canvas` (Fase 3) proyecta a 2D.
 *
 * ## Cómo se modela el cubo: 27 cubies con pegatinas orientadas
 * En lugar de las seis matrices 3×3 "de colores" que se ven en muchos solvers, guardamos los
 * **27 cubies físicos**, cada uno con su posición en el retículo y sus pegatinas apuntando a una
 * dirección. Razones:
 *
 *  1. **El render lo necesita.** Hay que dibujar cada pegatina como un polígono en el espacio;
 *     eso exige saber dónde está *físicamente*, no solo de qué color es la casilla `(fila, col)`
 *     de una cara. Con seis matrices habría que reconstruir la geometría en cada frame.
 *  2. **La animación de una capa es trivial.** Girar la rebanada = "los cubies cuya componente
 *     del eje vale ±1", un filtro de una línea. Con seis matrices, una sola rotación toca cinco
 *     caras distintas con índices cruzados: código frágil y difícil de leer.
 *  3. **Un giro es exacto.** Rotar una posición y una normal 90° es permutar dos componentes y
 *     cambiar un signo, todo en enteros ([IntVec3]): sin trigonometría y sin deriva numérica.
 *
 * El coste es representación redundante (54 pegatinas en 26 cubies), irrelevante a esta escala.
 *
 * ## Sistema de coordenadas
 * Los centros de los cubies ocupan el retículo `{-1, 0, 1}³`. El cubo, por tanto, ocupa el
 * volumen `[-1.5, 1.5]³` en espacio de modelo (cada cubie mide 1 unidad de lado), y las
 * pegatinas viven en los planos exteriores `±1.5`. Todo el escalado a píxeles es cosa del render.
 */

/**
 * Color lógico de una pegatina: las seis caras del cubo clásico, renombradas a la paleta neón
 * del sistema de diseño (CLAUDE.md §9.2).
 *
 * El dominio se queda **sin conocer `LogicColors`** a propósito (mismo criterio que en Hypergate):
 * el mapeo enum → `Color` de Compose vive en la capa de UI (Fase 3), de modo que el modelo y el
 * motor son 100 % portables y testeables sin tocar el framework gráfico.
 *
 * Equivalencia con el cubo clásico y color neón previsto:
 *
 * | Constante | Cara clásica | Token de diseño |
 * |-----------|--------------|-----------------|
 * | [CYAN]    | Blanco (U)   | `NeonCyan`      |
 * | [AMBER]   | Amarillo (D) | `Amber`         |
 * | [GREEN]   | Verde (F)    | `NeonGreen`     |
 * | [BLUE]    | Azul (B)     | `Blue`          |
 * | [MAGENTA] | Rojo (R)     | `Magenta`       |
 * | [VIOLET]  | Naranja (L)  | `Violet`        |
 *
 * Los seis tonos están bien separados en el círculo cromático para que sigan siendo
 * distinguibles al pintarse como trazo brillante sobre fondo oscuro, que es como se renderizan
 * (estructura de alambre, no relleno plano).
 */
enum class FaceColor {
    /** Cara superior en el estado resuelto (blanco clásico). */
    CYAN,

    /** Cara inferior en el estado resuelto (amarillo clásico). */
    AMBER,

    /** Cara frontal en el estado resuelto (verde clásico). */
    GREEN,

    /** Cara trasera en el estado resuelto (azul clásico). */
    BLUE,

    /** Cara derecha en el estado resuelto (rojo clásico). */
    MAGENTA,

    /** Cara izquierda en el estado resuelto (naranja clásico). */
    VIOLET,
}

/**
 * Sentido de un giro de 90°, **siempre respecto al eje positivo** de la rotación.
 *
 * Se define con la regla de la mano derecha para que el sentido no dependa de qué capa se gire:
 * [CLOCKWISE] significa "horario mirando el cubo **desde la punta del eje positivo** hacia el
 * origen". Así, un giro `Y/CLOCKWISE` es el mismo movimiento matemático tanto si se aplica a la
 * capa de arriba como a la de abajo, y toda la lógica de rotación es una única fórmula sin casos
 * especiales. La traducción "hacia dónde arrastró el dedo" → [TurnDirection] es responsabilidad
 * del gestor de gestos (Fase 3), que sí conoce la orientación de la cámara.
 */
enum class TurnDirection {
    /** Horario visto desde el eje positivo (rotación de -90° en la convención antihoraria). */
    CLOCKWISE,

    /** Antihorario visto desde el eje positivo (+90°). */
    COUNTER_CLOCKWISE;

    /** Sentido opuesto. Lo usa el motor para invertir jugadas (deshacer / generar la mezcla). */
    fun inverted(): TurnDirection =
        if (this == CLOCKWISE) COUNTER_CLOCKWISE else CLOCKWISE

    /**
     * Ángulo de un cuarto de vuelta **con signo**, en radianes: `+π/2` antihorario, `−π/2`
     * horario, según la regla de la mano derecha de esta enum.
     *
     * Lo usa el render para el giro *parcial* de la capa en vuelo (`progreso × signedQuarterRad`).
     * Vive aquí, junto a la definición del sentido, para que el signo que anima la pantalla y el
     * que permuta el estado no puedan divergir: si se invirtiera uno solo, la capa se vería girar
     * hacia un lado y encajaría hacia el otro.
     */
    val signedQuarterRad: Float
        get() = if (this == COUNTER_CLOCKWISE) QUARTER_TURN_RAD else -QUARTER_TURN_RAD
}

/** Un cuarto de vuelta en radianes (π/2). */
const val QUARTER_TURN_RAD: Float = (PI / 2).toFloat()

/**
 * Una pegatina (*facelet*): la cara coloreada de un cubie que mira hacia el exterior.
 *
 * @property normal dirección **actual** hacia la que apunta, en coordenadas de modelo y como
 *   eje unitario entero (una de las seis constantes de [IntVec3]). Es lo que rota junto a la
 *   posición del cubie: girar una capa cambia hacia dónde miran sus pegatinas, y ahí es donde
 *   vive la *orientación* de la pieza.
 * @property color color lógico, **invariante durante toda la partida**: una pegatina nunca
 *   cambia de color, solo de sitio y de orientación. Comparar el color con la normal es
 *   exactamente el criterio de "cubo resuelto" (Fase 2).
 */
data class Sticker(
    val normal: IntVec3,
    val color: FaceColor,
)

/**
 * Una de las 27 piezas del cubo.
 *
 * @property position centro de la pieza en el retículo `{-1, 0, 1}³`. Determina a qué capas
 *   pertenece: el cubie participa en un giro de [Axis.Y] índice `1` si `position.y == 1`.
 * @property stickers pegatinas visibles de esta pieza: 3 en las esquinas, 2 en las aristas, 1 en
 *   los centros y **0 en el núcleo** `(0,0,0)`. El núcleo se conserva en el modelo aunque no se
 *   dibuje nunca, para que la colección represente el cubo físico completo y los filtros por
 *   capa no necesiten excepciones.
 */
data class Cubie(
    val position: IntVec3,
    val stickers: List<Sticker>,
)

/**
 * Estado lógico completo del cubo: las 27 piezas con su posición y orientación actuales.
 *
 * Es una `data class` inmutable —cada giro produce una copia nueva—, coherente con MVI: el
 * `StateFlow` emite un valor distinto y la UI se recompone, sin mutación compartida entre frames.
 *
 * @property cubies las 27 piezas. El orden de la lista **no tiene significado** (la identidad de
 *   una pieza la da su [Cubie.position]); el render la reordena por profundidad en cada frame
 *   (algoritmo del pintor, Fase 3).
 */
data class CubeState(
    val cubies: List<Cubie>,
) {

    /**
     * Aplica un giro de 90° a la capa indicada y devuelve el cubo resultante.
     *
     * Todo el juego se reduce a esta operación. La rebanada afectada son los cubies cuya
     * componente en el eje del giro coincide con [LayerTurn.layer]; de cada uno se rota **la
     * posición y la normal de todas sus pegatinas** con la misma transformación entera
     * ([IntVec3.rotatedQuarter]).
     *
     * Rotar posición *y* normales a la vez es lo que reproduce la física de un cubo real: la
     * pieza cambia de sitio **y** de orientación en el mismo movimiento. Si solo se rotase la
     * posición, las esquinas volverían a su casilla pero con los colores girados —el error
     * clásico al modelar un cubo mágico—.
     *
     * Los cubies fuera de la capa se devuelven **por referencia** (no se copian): en un giro solo
     * cambian 9 de las 27 piezas, y compartir las 18 restantes hace la operación barata y deja
     * intacto el estado anterior (inmutabilidad sin coste).
     */
    fun applyTurn(turn: LayerTurn): CubeState = CubeState(
        cubies.map { cubie ->
            if (cubie.position.component(turn.axis) != turn.layer) {
                cubie
            } else {
                Cubie(
                    position = cubie.position.rotatedQuarter(turn.axis, turn.direction),
                    stickers = cubie.stickers.map { sticker ->
                        sticker.copy(normal = sticker.normal.rotatedQuarter(turn.axis, turn.direction))
                    },
                )
            }
        },
    )

    /**
     * ¿Está el cubo resuelto? Sí cuando **cada cara muestra un único color**.
     *
     * ## Por qué el criterio es "uniforme", no "cada color en SU cara"
     * La comprobación ingenua sería comparar cada pegatina con el color que le tocaba en el
     * estado inicial (verde delante, cian arriba…). Sería un **bug visible**: el jugador puede
     * girar la rebanada central o el cubo entero, lo que reorienta el esquema de colores sin
     * desordenar nada. Con el criterio absoluto, un cubo perfectamente uniforme se declararía
     * "sin resolver" solo porque el verde acabó a la derecha; el jugador vería un cubo resuelto y
     * la app diciéndole que no. El criterio de uniformidad es el que usa cualquier cubero real:
     * la orientación global del cubo es irrelevante.
     *
     * Implementación: se recorren las 54 pegatinas una vez, registrando el primer color visto por
     * cada dirección y abortando en cuanto una cara muestra dos colores distintos. O(54) y sin
     * asignaciones más allá del mapa de 6 entradas; se evalúa solo al completarse un giro, nunca
     * por frame.
     */
    val isSolved: Boolean
        get() {
            val colorByFace = HashMap<IntVec3, FaceColor>(6)
            for (cubie in cubies) {
                for (sticker in cubie.stickers) {
                    val expected = colorByFace.getOrPut(sticker.normal) { sticker.color }
                    if (expected != sticker.color) return false
                }
            }
            return true
        }

    companion object {
        /**
         * Construye el cubo **resuelto**: cada pegatina en la cara cuyo color le corresponde.
         *
         * Recorre el retículo `{-1,0,1}³` y añade a cada pieza una pegatina por cada componente
         * que valga ±1 —es decir, por cada cara del cubie que dé al exterior—. De ahí salen
         * solas las 6 caras × 9 = 54 pegatinas: 8 esquinas con 3, 12 aristas con 2, 6 centros
         * con 1 y el núcleo con ninguna.
         */
        fun solved(): CubeState {
            val range = -1..1
            val cubies = buildList(capacity = 27) {
                for (x in range) for (y in range) for (z in range) {
                    val position = IntVec3(x, y, z)
                    val stickers = buildList(capacity = 3) {
                        if (x == 1) add(Sticker(IntVec3.RIGHT, FaceColor.MAGENTA))
                        if (x == -1) add(Sticker(IntVec3.LEFT, FaceColor.VIOLET))
                        if (y == 1) add(Sticker(IntVec3.UP, FaceColor.CYAN))
                        if (y == -1) add(Sticker(IntVec3.DOWN, FaceColor.AMBER))
                        if (z == 1) add(Sticker(IntVec3.FRONT, FaceColor.GREEN))
                        if (z == -1) add(Sticker(IntVec3.BACK, FaceColor.BLUE))
                    }
                    add(Cubie(position, stickers))
                }
            }
            return CubeState(cubies)
        }
    }
}

/**
 * Un giro de capa: la jugada atómica del juego.
 *
 * @property axis eje alrededor del cual gira la rebanada.
 * @property layer índice de la capa a lo largo de ese eje: `-1`, `0` (la rebanada central) o `1`.
 *   Un cubie participa si `position.component(axis) == layer`.
 * @property direction sentido del giro, con la convención de la mano derecha de [TurnDirection].
 */
data class LayerTurn(
    val axis: Axis,
    val layer: Int,
    val direction: TurnDirection,
) {
    /** Jugada inversa. La Fase 2 la usa para deshacer y para construir mezclas reversibles. */
    fun inverted(): LayerTurn = copy(direction = direction.inverted())
}

/**
 * Giro **en curso**: la jugada que se está animando ahora mismo.
 *
 * Vive en el estado (no en una variable del `Canvas`) porque es información que la lógica
 * necesita: mientras hay un giro en vuelo se ignoran nuevos gestos de capa, y al llegar a
 * `progress == 1f` el motor aplica la permutación exacta al [CubeState] y emite el "clack".
 *
 * ## Por qué el estado lógico no cambia hasta terminar
 * Durante la animación, el cubo lógico sigue en su posición previa y el render dibuja los cubies
 * de la capa afectada con una rotación extra de `progress × 90°` ([Mat3.rotation]). Así el
 * modelo nunca contiene estados "a medio girar" —imposibles de representar con [IntVec3]— y la
 * detección de victoria solo se evalúa sobre configuraciones válidas.
 *
 * @property turn la jugada que se está ejecutando.
 * @property progress avance normalizado en `[0f, 1f]`; `0f` = sin girar, `1f` = 90° completos.
 */
data class ActiveTurn(
    val turn: LayerTurn,
    val progress: Float = 0f,
)

/**
 * Estado de juego que publica el motor ([HyperCubeEngine]) y que la UI observa a través del
 * `HyperCubeUiState`. Data class inmutable: cada giro (o cada frame de animación) produce una
 * copia nueva, coherente con MVI.
 *
 * Deliberadamente **no** contiene la cámara: orbitar la vista no cambia el juego, es presentación
 * pura y vive en el estado de UI, donde el ViewModel la actualiza sin molestar al motor.
 *
 * @property cube configuración lógica de las 27 piezas. Solo cambia en saltos discretos y
 *   exactos, al completarse un giro.
 * @property activeTurn giro en curso con su progreso de animación, o `null` si el cubo está
 *   quieto. Mientras no sea `null` se rechazan nuevas jugadas.
 * @property moves jugadas del jugador ya completadas en este nivel. No incluye los giros de la
 *   mezcla: es la métrica de eficiencia de la puntuación.
 * @property scrambleDepth nº de giros con los que se mezcló el nivel. Sirve de referencia de
 *   "solución de longitud razonable" al puntuar (deshacer la mezcla siempre es una solución
 *   válida de esa longitud, así que es una cota superior del óptimo).
 * @property isScrambling `true` mientras se reproduce la mezcla inicial; la UI bloquea los gestos
 *   y muestra el rótulo correspondiente.
 * @property isFreeMode `true` si la partida es el modo libre (cubo entero mezclado, sin nivel ni
 *   récord de progresión). La UI lo usa para rotular la cabecera y para ofrecer "otra mezcla" en
 *   lugar de "siguiente nivel" al terminar.
 * @property solved `true` cuando el cubo ha quedado uniforme y la partida ha terminado.
 * @property canUndo hay al menos una jugada propia que deshacer. Los giros de la mezcla no
 *   cuentan: deshacer hasta "desmezclar" el cubo sería resolverlo con un botón.
 * @property undoCostsAd el deshacer gratuito de esta partida ya se gastó, así que el siguiente
 *   pasa por un anuncio recompensado. La UI lo usa para cambiar el icono del botón y avisar del
 *   coste ANTES de pulsarlo, no después.
 */
data class HyperCubeGameState(
    val cube: CubeState = CubeState.solved(),
    val activeTurn: ActiveTurn? = null,
    val moves: Int = 0,
    val scrambleDepth: Int = 0,
    val isScrambling: Boolean = false,
    val isFreeMode: Boolean = false,
    val solved: Boolean = false,
    val canUndo: Boolean = false,
    val undoCostsAd: Boolean = false,
)

/**
 * Constantes geométricas y utilidades de espacio de modelo, compartidas por motor y render.
 *
 * Se centralizan aquí (fuente única) para que "lo que se ve" y "lo que se toca" coincidan: el
 * mismo tamaño de pegatina que dibuja el `Canvas` es el que usa la detección de gestos al
 * decidir sobre qué capa arrastró el dedo (Fase 3).
 */
object HyperCubeGeometry {

    /** Lado de un cubie en espacio de modelo. Fija la escala de todo lo demás. */
    const val CUBIE_SIZE = 1f

    /**
     * Semi-lado de una pegatina. Menor que `CUBIE_SIZE / 2` a propósito: el hueco resultante
     * separa visualmente las pegatinas contiguas, que es lo que produce el aspecto de rejilla
     * holográfica (cada facelet es un marco de luz independiente, no una superficie continua).
     */
    const val STICKER_HALF = 0.42f

    /**
     * Semi-lado de la **cara completa de un cubie**: el cuerpo opaco sobre el que se apoya la
     * pegatina, equivalente al plástico negro de un cubo real.
     *
     * Vale `CUBIE_SIZE / 2` (más un pelín, ver abajo) porque las caras de cubies contiguos deben
     * **teselar sin holguras**: el cubie en `-1` cubre `[-1.5, -0.5]`, el de `0` cubre
     * `[-0.5, 0.5]` y el de `1` cubre `[0.5, 1.5]`, así que juntas sellan la cara entera y el cubo
     * queda estanco. Si se usara [STICKER_HALF] para el cuerpo, por las juntas se vería el fondo.
     *
     * El exceso de 0.01 hace que las caras vecinas se solapen imperceptiblemente, para que el
     * antialiasing no deje costuras de un píxel entre dos polígonos que comparten arista.
     */
    const val BODY_HALF = 0.51f

    /**
     * Distancia del centro del cubo al plano de las caras exteriores: `1 (centro del cubie
     * exterior) + 0.5 (medio cubie)`. Las pegatinas se dibujan justo ahí.
     */
    const val FACE_PLANE = 1.5f

    /**
     * Límite del *pitch* de la cámara (≈ 75°). Impedir que el jugador cruce los polos evita dos
     * problemas: que el cubo aparezca "boca abajo" sin que él sepa cómo volver, y que el mapeo
     * gesto → giro de capa se invierta de forma desconcertante cerca de la vertical.
     */
    const val MAX_PITCH_RAD = 1.309f

    /**
     * Esquinas de una pegatina en **espacio de modelo**, listas para transformar y proyectar.
     *
     * Construcción: se sitúa el centro de la pegatina en el plano exterior de su cara y se
     * generan las cuatro esquinas desplazándose por los dos ejes tangentes:
     *
     * ```
     * centro = (posición del cubie proyectada sobre el plano de la cara) = p + n·(FACE_PLANE - |p·n|)
     * u = eje tangente arbitrario ⟂ n ;  v = n × u   (terna diestra)
     * esquinas = centro ± u·h ± v·h ,  h = STICKER_HALF
     * ```
     *
     * El orden devuelto es `(-u,-v) → (+u,-v) → (+u,+v) → (-u,+v)`, que recorre el cuadrilátero
     * en sentido **antihorario visto desde fuera** (desde la punta de `n`). Esa consistencia es
     * la que permite en Fase 3 descartar caras traseras con una sola comprobación de signo, sin
     * casos particulares por cara.
     *
     * @param cubie pieza a la que pertenece la pegatina.
     * @param sticker pegatina cuya geometría se quiere (su [Sticker.normal] elige la cara).
     * @param half semi-lado del cuadrado: [STICKER_HALF] para la pegatina y [BODY_HALF] para la
     *   cara completa del cubie (el cuerpo opaco que sella el cubo).
     * @return las cuatro esquinas en orden antihorario visto desde el exterior.
     */
    fun faceletCorners(
        cubie: Cubie,
        sticker: Sticker,
        half: Float = STICKER_HALF,
    ): List<Vector3> {
        val center = faceletCenter(cubie, sticker)
        val (uInt, vInt) = tangentBasis(sticker.normal)
        val u = uInt.toVector3()
        val v = vInt.toVector3()

        val h = half
        return listOf(
            center - u * h - v * h,
            center + u * h - v * h,
            center + u * h + v * h,
            center - u * h + v * h,
        )
    }

    /**
     * Centro de una pegatina en espacio de modelo: comparte con el cubie las dos coordenadas
     * tangentes y toma [FACE_PLANE] (con signo) en la coordenada de la normal, de forma que queda
     * pegada a la superficie exterior de la cara.
     */
    fun faceletCenter(cubie: Cubie, sticker: Sticker): Vector3 {
        val p = cubie.position.toVector3()
        val n = sticker.normal
        return Vector3(
            if (n.x != 0) n.x * FACE_PLANE else p.x,
            if (n.y != 0) n.y * FACE_PLANE else p.y,
            if (n.z != 0) n.z * FACE_PLANE else p.z,
        )
    }

    /**
     * Base tangente `(u, v)` del plano de una cara, en **enteros** y formando terna diestra con la
     * normal (`u × v = n`).
     *
     * `u` se elige de forma arbitraria pero determinista —cualquier eje cardinal perpendicular a
     * la normal sirve— y `v = n × u` cierra la terna. Que sea diestra es lo que garantiza que las
     * esquinas de [faceletCorners] salgan en orden **antihorario visto desde fuera**, condición
     * del *backface culling* del render.
     *
     * Es `public` porque la capa de gestos necesita **exactamente la misma** base para traducir un
     * arrastre en pantalla a un giro de capa: si cada capa eligiera la suya, el dedo y el cubo
     * dejarían de estar de acuerdo. Una sola definición, dos consumidores.
     */
    fun tangentBasis(normal: IntVec3): Pair<IntVec3, IntVec3> {
        // "El eje Y, salvo que la normal ya sea Y" — basta porque la normal siempre es cardinal.
        val u = if (normal.y != 0) IntVec3.RIGHT else IntVec3.UP
        return u to (normal cross u)
    }
}
