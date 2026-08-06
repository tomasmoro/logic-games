package com.kortexgames.app.game.quantummerge

import kotlin.math.hypot

/**
 * # Quantum Merge — modelos de dominio y geometría del mundo (Fase 1)
 *
 * Modelos puros e inmutables del minijuego **Quantum Merge**: esferas de energía que caen por
 * gravedad dentro de un contenedor abierto por arriba, rebotan entre sí y, cuando dos del MISMO
 * [QuantumTier] se tocan, se fusionan en una del tier siguiente (mecánica tipo *Suika*).
 *
 * No dependen de Compose ni de ninguna API de plataforma: son la fuente de verdad que el motor
 * de física (Fase 2) hace avanzar frame a frame y que el `Canvas` (Fase 3) traduce a píxeles.
 *
 * ## Decisión 1 — unidades de MUNDO fijas, no píxeles (contraste con Hypergate/Polarity)
 * Los otros juegos con física del repo (*Hypergate*, *Atracción Geométrica*) guardan el viewport
 * en píxeles dentro del estado y simulan directamente en esa escala. Aquí **no**, y es deliberado:
 *
 *  - La física de fusión es **sensible al tuning**: gravedad, restitución y fricción producen un
 *    "tacto" concreto. Si el mundo midiera píxeles, un móvil de 1080 px de ancho y otro de 1440
 *    caerían con aceleraciones relativas distintas salvo que se reescalara cada constante — y una
 *    tablet jugaría a otra velocidad aparente. Con un mundo fijo ([QuantumWorld]) la simulación es
 *    **idéntica en todos los dispositivos** y el tuning se hace una sola vez.
 *  - Además, la simulación pasa a ser **determinista y testeable** sin inventar un viewport: un
 *    test puede soltar una esfera en `x = 50` y afirmar dónde reposa.
 *  - El coste es una transformación mundo→píxel en el render (una escala y un desplazamiento que
 *    la pantalla ya calcula para dibujar el contenedor). Barato y en un único sitio.
 *
 * En Hypergate la elección contraria era la correcta: allí el "tablero" ES la pantalla completa
 * (los proyectiles nacen en el borde real), así que el viewport en píxeles era el dominio. Aquí el
 * tablero es una caja de proporción fija que se encaja dentro de la pantalla.
 *
 * ## Decisión 2 — el eje Y crece hacia ABAJO
 * Se adopta la convención de pantalla (y también la de `DrawScope` de Compose): `y = 0` es el
 * borde superior del contenedor e `y` crece hacia el suelo. Así **la gravedad es positiva** y el
 * render no invierte nada. La alternativa (Y matemática hacia arriba) obligaría a un `height - y`
 * en cada punto dibujado: más ocasiones de equivocarse, cero ventajas.
 *
 * ## Decisión 3 — radio y masa DERIVAN del tier, no se guardan en la esfera
 * Una [Sphere] con `radius` propio podría acabar con un radio que no corresponde a su tier (estado
 * ilegal representable) tras una fusión mal escrita. Al exponerlos como propiedades calculadas
 * ([Sphere.radius], [Sphere.mass]) ese bug es **imposible por construcción** y la data class se
 * mantiene mínima: lo único que el tick escribe es posición y velocidad.
 */

/**
 * Geometría del contenedor y constantes del mundo simulado. **Fuente única** compartida por el
 * motor (colisiones contra paredes/suelo) y por el render (transformación mundo→píxel).
 *
 * Solo contiene **geometría y reglas de forma**, nunca tuning de física: gravedad, restitución o
 * fricción viven en el `companion` del motor (Fase 2), igual que en `HypergateEngine`. El criterio
 * es "¿lo necesita también el render para dibujar?": el ancho del contenedor y la línea de peligro
 * sí (hay que pintarlos); el coeficiente de rebote no.
 *
 * Las medidas son **unidades de mundo** arbitrarias pero fijas (ver Decisión 1 de la cabecera).
 * Se ha elegido `100` de ancho para que cualquier constante se lea como un porcentaje del
 * contenedor ("radio 3.4" = 3,4 % del ancho) y el tuning sea legible de un vistazo.
 */
object QuantumWorld {

    /** Ancho interior del contenedor, en unidades de mundo. Referencia de todas las medidas. */
    const val WIDTH: Float = 100f

    /**
     * Alto interior del contenedor. La proporción 100:132 (≈ 3:4 en vertical) deja sitio para una
     * pila cómoda de esferas sin que el tablero se vuelva un pozo estrecho donde todo se apelmaza
     * — y encaja en la mitad superior de una pantalla de móvil junto al HUD y la zona de puntería.
     */
    const val HEIGHT: Float = 132f

    /** Relación de aspecto (ancho/alto). La usa el render para encajar la caja sin deformarla. */
    const val ASPECT: Float = WIDTH / HEIGHT

    /**
     * Recorta una coordenada horizontal para que una esfera de [radius] quede **completamente**
     * dentro de las paredes.
     *
     * Se usa en dos sitios que deben coincidir exactamente: al mover el dispensador (el dedo puede
     * salirse del contenedor, la esfera no) y al resolver la colisión contra las paredes en la
     * física. Tenerlo aquí evita que ambos límites se desincronicen y que una esfera aparezca
     * medio incrustada en un muro.
     */
    fun clampInsideWalls(x: Float, radius: Float): Float = x.coerceIn(radius, WIDTH - radius)
}

/**
 * Nivel de dificultad elegible antes de empezar. Cambia **tres** cosas a la vez, todas en la misma
 * dirección: menos sitio y menos margen para planificar.
 *
 *  1. **Tamaño de las esferas** ([radiusScale]): un 10 % mayor por peldaño. Como el contenedor no
 *     cambia, caben menos y la pila sube antes.
 *  2. **Techo útil** ([dangerLineY]): la línea de peligro baja, recortando la altura de apilado.
 *  3. **Variedad del dispensador** (la calcula el motor a partir del nivel): más tipos distintos en
 *     juego, más difícil encontrar pareja.
 *
 * ## Por qué se escalan las ESFERAS y no el contenedor
 * "Esferas un 20 % más grandes" y "contenedor un 20 % más pequeño" son lo mismo geométricamente,
 * pero no en sensación: encoger el mundo dejando la gravedad igual haría que las esferas cruzaran
 * el contenedor **más rápido**, y la dificultad pasaría a medirse en reflejos. Escalando los
 * radios, el tiempo de caída es idéntico en los tres niveles y lo único que cambia es el espacio,
 * que es lo que se quería.
 *
 * @property displayName rótulo visible (también lo usa `GameRankingScopes` para titular la tabla).
 * @property radiusScale factor que multiplica el [QuantumTier.baseRadius] de toda la escala.
 * @property dangerLineY altura de la **línea de peligro**: el borde superior "real" del juego. Una
 *   esfera que se queda quieta por encima de ella demasiado tiempo provoca la derrota (ver
 *   [Sphere.aboveLineSec]). Nunca es `0`: hay que dejar un carril por el que la esfera del
 *   dispensador espere y por el que una recién soltada pueda cruzar sin activar la derrota al
 *   instante. Ese carril es la franja `[0, dangerLineY)`.
 */
enum class QuantumDifficulty(
    val displayName: String,
    val radiusScale: Float,
    val dangerLineY: Float,
) {
    /** Tamaño de referencia de la escala de tiers y el techo más alto. */
    FACIL("Fácil", radiusScale = 1.0f, dangerLineY = 28f),

    MEDIO("Medio", radiusScale = 1.1f, dangerLineY = 34f),

    /**
     * Esferas un 20 % mayores que en [FACIL] y 12 unidades menos de altura útil.
     *
     * Consecuencia asumida: con este factor, dos [QuantumTier.SINGULARITY] ocupan 95,5 de los 100
     * de ancho del contenedor. La última fusión sigue siendo **geométricamente posible**, pero
     * exige tenerlas casi pegadas a las paredes; en la práctica es una hazaña reservada a este
     * nivel, no una ruta habitual.
     */
    DIFICIL("Difícil", radiusScale = 1.2f, dangerLineY = 40f);

    /**
     * Altura a la que el dispensador sostiene la esfera antes de soltarla.
     *
     * Se **calcula** en vez de fijarse a mano: es exactamente el radio de la mayor esfera lanzable
     * en este nivel, de modo que esa esfera queda tangente al borde superior —entera y visible— sin
     * salirse del tablero. Un valor escrito a mano se desincronizaría en cuanto cambiara la escala.
     */
    val dropY: Float get() = QuantumTier.SPAWN_POOL.last().baseRadius * radiusScale

    /** Alto útil de apilado (del suelo a la línea de peligro). Lo muestra el selector de la intro. */
    val stackHeight: Float get() = QuantumWorld.HEIGHT - dangerLineY

    companion object {
        /**
         * Nivel a partir del `difficultyLevel` 1-based de `GameResult` (la convención del resto de
         * juegos: `ordinal + 1`). Cae en [FACIL] ante un valor fuera de rango —partidas antiguas o
         * datos corruptos— en vez de reventar.
         */
        fun fromLevel(level: Int): QuantumDifficulty = entries.getOrElse(level - 1) { FACIL }
    }
}

/**
 * Escalafón de esferas de energía. Cada fusión sube exactamente un peldaño: dos [QUARK] dan un
 * [NEUTRINO], dos [NEUTRINO] un [PHOTON], y así hasta [SINGULARITY].
 *
 * ## Por qué los radios crecen en progresión geométrica (~×1,24 → ×1,19)
 * El salto entre tiers consecutivos debe ser **inmediatamente legible** (el jugador tiene que ver
 * que ha subido de nivel sin leer un número), y eso exige un factor claramente mayor que 1,1. Pero
 * un factor constante alto explota: con ×1,24 durante once peldaños el radio final sería ~×8 el
 * inicial y la esfera mayor no cabría en el contenedor. Por eso la razón se **relaja** en los
 * últimos tiers (de ~1,24 a ~1,19): así los tiers pequeños —los que el jugador ve constantemente—
 * se distinguen bien, y aun así **dos [SINGULARITY] caben lado a lado** (2 × 39.8 = 79.6 < 100 en
 * [QuantumDifficulty.FACIL]), que es el requisito para que la última fusión sea físicamente
 * alcanzable y no un imposible.
 *
 * ## Sobre la escala global
 * Toda la tabla está a **×1,56 respecto al calibrado original** (dos pasadas de agrandado a
 * petición de diseño: ×1,3 y luego ×1,2), y el nivel de dificultad la multiplica otra vez por
 * [QuantumDifficulty.radiusScale]. Escalar la tabla entera en vez de retocar tiers sueltos mantiene
 * intactas las dos propiedades que sostienen el juego: la razón entre peldaños consecutivos
 * (legibilidad del salto) y el que quepan dos esferas máximas lado a lado (alcanzabilidad del
 * último nivel). El efecto secundario es que en el mismo contenedor caben menos esferas, así que
 * las partidas son más cortas: si se quisiera compensar, el mando es [QuantumWorld.HEIGHT], no
 * esta tabla.
 *
 * @property baseRadius radio en unidades de mundo (ver [QuantumWorld]) **antes** de aplicar la
 *   escala del nivel de dificultad. El radio efectivo de una esfera concreta es [Sphere.radius].
 * @property mergeScore puntos que otorga **nacer** una esfera de este tier al fusionarse dos del
 *   tier anterior. Crece de forma superlineal (números triangulares ×10) porque cada peldaño exige
 *   haber logrado todos los anteriores: la recompensa debe premiar la cadena completa, no el
 *   último toque. [QUARK] vale 0 porque nunca nace de una fusión (solo lo entrega el dispensador).
 * @property accent identidad de color del tier, en forma **semántica**. El dominio no conoce
 *   `androidx.compose.ui.graphics.Color`: igual que `BlockAccent` en Tetris Neón, el mapeo a los
 *   tokens de `LogicColors` vive en la capa de UI (Fase 3). Así el motor sigue siendo puro y el
 *   sistema de diseño mantiene UNA sola fuente de color (CLAUDE.md §9.2).
 */
enum class QuantumTier(
    val baseRadius: Float,
    val mergeScore: Int,
    val accent: TierAccent,
) {
    /** Tier 1. El más pequeño; es el que más sale del dispensador. */
    QUARK(baseRadius = 5.3f, mergeScore = 0, accent = TierAccent.CYAN),

    /** Tier 2. */
    NEUTRINO(baseRadius = 6.6f, mergeScore = 30, accent = TierAccent.GREEN),

    /** Tier 3. */
    PHOTON(baseRadius = 8.2f, mergeScore = 60, accent = TierAccent.LIME),

    /** Tier 4. */
    ELECTRON(baseRadius = 10.2f, mergeScore = 100, accent = TierAccent.AMBER),

    /** Tier 5. Último tier que el dispensador puede entregar ([SPAWN_POOL]). */
    PROTON(baseRadius = 12.5f, mergeScore = 150, accent = TierAccent.CORAL),

    /** Tier 6. */
    ATOM(baseRadius = 15.5f, mergeScore = 210, accent = TierAccent.MAGENTA),

    /** Tier 7. */
    MOLECULE(baseRadius = 19.1f, mergeScore = 280, accent = TierAccent.VIOLET),

    /** Tier 8. */
    CRYSTAL(baseRadius = 23.4f, mergeScore = 360, accent = TierAccent.BLUE),

    /** Tier 9. */
    PLASMA(baseRadius = 28.1f, mergeScore = 450, accent = TierAccent.CYAN),

    /** Tier 10. */
    STAR(baseRadius = 33.6f, mergeScore = 550, accent = TierAccent.AMBER),

    /** Tier 11: el máximo. Dos de estas ya no suben de tier (ver [next]). */
    SINGULARITY(baseRadius = 39.8f, mergeScore = 660, accent = TierAccent.WHITE_HOT);

    /** Radio efectivo de este tier con la escala de un nivel de dificultad. */
    fun radiusFor(scale: Float): Float = baseRadius * scale

    /**
     * Tier resultante de fusionar dos esferas de este tier, o `null` si ya es el máximo.
     *
     * `null` NO significa "no pasa nada": significa que la Fase 2 debe aplicar la regla de
     * **aniquilación** (dos [SINGULARITY] que chocan se desintegran ambas y otorgan un bono),
     * como en el juego clásico. Se modela con `null` en vez de con un tier imaginario para que el
     * compilador obligue a decidir qué hacer en ese caso.
     */
    fun next(): QuantumTier? = entries.getOrNull(ordinal + 1)

    companion object {
        /**
         * Tiers que el dispensador puede entregar al jugador: solo los cinco más pequeños.
         *
         * Es la regla que sostiene toda la curva de dificultad. Si el dispensador pudiera soltar
         * tiers altos, el jugador llegaría a la [SINGULARITY] por acumulación bruta y sin planificar;
         * limitándolo a la base, **cada esfera grande del tablero es necesariamente fruto de una
         * cadena de fusiones** que él construyó. Vive aquí (dominio) y no en el motor porque es una
         * regla del juego, no un parámetro de tuning.
         */
        val SPAWN_POOL: List<QuantumTier> = listOf(QUARK, NEUTRINO, PHOTON, ELECTRON, PROTON)
    }
}

/**
 * Identidad cromática de un [QuantumTier], en forma semántica y sin dependencias de Compose.
 *
 * Los nombres son los de la paleta neón del sistema de diseño (CLAUDE.md §9.2); la Fase 3 los
 * traduce a `LogicColors` en un único `when`. Se repiten tonos entre tiers lejanos (p. ej. CYAN en
 * [QuantumTier.QUARK] y en [QuantumTier.PLASMA]) porque once tiers superan los tonos neón bien
 * diferenciables de la paleta — y no supone ambigüedad: dos esferas con 25× de diferencia de área
 * jamás se confunden aunque compartan color.
 */
enum class TierAccent {
    /** Cian eléctrico (`LogicColors.NeonCyan`). */
    CYAN,

    /** Verde neón (`LogicColors.NeonGreen`). */
    GREEN,

    /** Verde lima (`LogicColors.Lime`). */
    LIME,

    /** Amarillo eléctrico (`LogicColors.Amber`). */
    AMBER,

    /** Naranja coral (`LogicColors.Coral`). */
    CORAL,

    /** Magenta (`LogicColors.Magenta`). */
    MAGENTA,

    /** Morado neón (`LogicColors.Violet`). */
    VIOLET,

    /** Azul neón (`LogicColors.Blue`). */
    BLUE,

    /**
     * Blanco incandescente: reservado al tier máximo. Es el único "color" que no es un acento de
     * la paleta sino luz pura, para que la [QuantumTier.SINGULARITY] se lea como el premio final.
     */
    WHITE_HOT,
}

/**
 * Una esfera de energía dentro del contenedor: el **cuerpo rígido** de la simulación.
 *
 * Es un círculo perfecto con posición y velocidad en unidades de mundo (ver [QuantumWorld]). Solo
 * el tick de física escribe sus campos; radio y masa se derivan del [tier] (ver Decisión 3 en la
 * cabecera del archivo).
 *
 * **No se simula la rotación** (ni momento angular, ni rozamiento por giro): las esferas son
 * discos lisos. Es una simplificación consciente —el momento angular multiplicaría por dos las
 * ecuaciones de la Fase 2— y perceptualmente irrelevante en una esfera de luz sin textura, donde
 * el giro no se vería. Su efecto secundario (que una esfera no "ruede" por una pendiente de
 * esferas) se compensa en Fase 2 con fricción tangencial en el choque.
 *
 * @property id identificador estable durante toda la vida de la esfera. Permite a Compose usar
 *   `key(...)` para animar cada esfera de forma independiente y evita confundir dos esferas al
 *   reordenarse la lista tras una fusión. Una esfera nacida de una fusión recibe un id NUEVO: no
 *   es ninguna de las dos madres, es un cuerpo distinto.
 * @property tier peldaño del escalafón: determina radio, masa, color y puntos.
 * @property radiusScale escala de radios de la partida ([QuantumDifficulty.radiusScale]). Es
 *   **uniforme en toda la partida** —el motor la fija igual en cada esfera que crea—, así que
 *   guardarla por esfera es redundante; se hace a propósito para que la esfera siga siendo
 *   **autodescriptiva**: su radio, su masa y sus colisiones se calculan sin necesitar el nivel de
 *   dificultad como contexto externo, que es justo lo que mantiene a [penetrationWith] y compañía
 *   como funciones puras del objeto.
 * @property x posición del CENTRO en el eje horizontal (0 = pared izquierda, [QuantumWorld.WIDTH]
 *   = pared derecha).
 * @property y posición del CENTRO en el eje vertical, creciendo hacia abajo (ver Decisión 2).
 * @property vx velocidad horizontal en unidades de mundo por segundo (positiva = a la derecha).
 * @property vy velocidad vertical en unidades de mundo por segundo (**positiva = cayendo**,
 *   coherente con el eje Y invertido; una velocidad negativa es un rebote hacia arriba).
 * @property aboveLineSec segundos que esta esfera lleva **asentada** por encima de la línea de
 *   peligro ([QuantumDifficulty.dangerLineY]). Se acumula solo mientras está quieta ahí arriba y se
 *   pone a cero en cuanto baja o vuelve a moverse; al superar el límite, derrota.
 *
 *   Vive en la esfera y no como un contador global a propósito: una esfera que **cruza** la línea
 *   de camino hacia abajo (todo lanzamiento lo hace) no debe alimentar el mismo temporizador que
 *   la que se ha quedado encallada arriba. Con el contador por cuerpo, la que cae lo resetea sola.
 */
data class Sphere(
    val id: Long,
    val tier: QuantumTier,
    val radiusScale: Float,
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val aboveLineSec: Float = 0f,
) {

    /**
     * Radio en unidades de mundo: el del [tier] escalado por [radiusScale]. Derivado, nunca
     * almacenado (ver Decisión 3): así no puede existir una esfera cuyo radio contradiga su tier.
     */
    val radius: Float get() = tier.radiusFor(radiusScale)

    /**
     * Masa para el intercambio de impulsos.
     *
     * Se modela como **área** (`m ∝ r²`, disco 2D de densidad uniforme) y no como el radio a secas:
     * es lo que hace que una esfera grande empuje a una pequeña en vez de ser desviada por ella,
     * que es exactamente la sensación que el jugador espera al ver caer una masa enorme.
     *
     * La densidad y el factor `π` se omiten a propósito (equivalen a 1): en las ecuaciones de
     * impulso elástico la masa **solo aparece en cocientes** (`m₁/(m₁+m₂)`, `2m₂/(m₁+m₂)`), así que
     * cualquier constante multiplicativa común se cancela. Por el mismo motivo la escala de
     * dificultad tampoco altera la física del choque: al ser común a las dos esferas, su cuadrado
     * se simplifica en cada cociente.
     */
    val mass: Float get() = radius * radius

    /**
     * Distancia euclídea entre los CENTROS de esta esfera y [other], por el **teorema de
     * Pitágoras**: `d = √(Δx² + Δy²)`.
     *
     * Se usa `hypot` en vez de `sqrt(dx*dx + dy*dy)` escrito a mano porque evita el desbordamiento
     * intermedio de `dx²` y es la forma numéricamente estable de la misma fórmula.
     */
    fun distanceTo(other: Sphere): Float = hypot(other.x - x, other.y - y)

    /**
     * **Profundidad de penetración** con [other]: cuánto se están solapando ambos círculos, en
     * unidades de mundo. `> 0` significa colisión; `<= 0` significa que no se tocan.
     *
     * ## La matemática
     * Dos círculos se solapan cuando la distancia entre sus centros es menor que la suma de sus
     * radios. Restando ambos términos se obtiene, en una sola magnitud, **la respuesta y su
     * gravedad**:
     *
     * ```
     * penetración = (r₁ + r₂) − d      con d = √(Δx² + Δy²)
     * ```
     *
     * Devolver la profundidad en lugar de un `Boolean` no es un capricho: es exactamente el número
     * que la Fase 2 necesita para **separar** los cuerpos (hay que desplazarlos justo esa cantidad
     * a lo largo de la normal de colisión, repartida según sus masas). Un predicado obligaría a
     * recalcular la misma raíz cuadrada inmediatamente después — el cálculo más caro del bucle,
     * que corre `n²/2` veces por frame.
     */
    fun penetrationWith(other: Sphere): Float = (radius + other.radius) - distanceTo(other)

    /**
     * ¿El centro de la esfera está por encima de la línea de peligro de [difficulty]? Es el
     * predicado que alimenta [aboveLineSec].
     *
     * Se mide con el **centro** y no con el borde superior porque una esfera grande apoyada en la
     * pila siempre asoma un poco por encima de la línea sin estar realmente "desbordando": exigir
     * que su centro cruce la línea evita derrotas injustas.
     */
    fun isAboveDangerLine(difficulty: QuantumDifficulty): Boolean = y < difficulty.dangerLineY
}

/**
 * Destello efímero que se dibuja en el punto exacto donde ocurrió una fusión.
 *
 * Es **estado**, no un efecto one-shot, y esa es la decisión importante: al vivir en el `State` el
 * destello se anima solo (`progress` avanza con el mismo tick de la física, ver §9.4 "animaciones
 * dirigidas por estado") y sobrevive a las recomposiciones sin que la pantalla tenga que mantener
 * su propia lista mutable de animaciones en curso. La Fase 3 solo lo pinta; no gestiona su tiempo.
 *
 * @property id identificador estable para el `key(...)` de Compose.
 * @property x centro del destello: el punto medio exacto entre las dos esferas fusionadas.
 * @property y ídem en vertical.
 * @property radius radio de la esfera **nacida**. El destello escala con él para que una fusión de
 *   tier alto se sienta más grande que una de tier bajo sin necesidad de otro parámetro.
 * @property accent color del destello: el de la esfera nacida (la Fase 3 lo mezcla con blanco en
 *   el núcleo, como una descarga de luz).
 * @property ageSec tiempo transcurrido desde la fusión. Lo incrementa el tick; al llegar a
 *   [LIFETIME_SEC] el destello se retira de la lista.
 */
data class MergeFlash(
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val accent: TierAccent,
    val ageSec: Float = 0f,
) {

    /** Progreso normalizado 0..1 del destello. La UI lo usa para expandir y desvanecer. */
    val progress: Float get() = (ageSec / LIFETIME_SEC).coerceIn(0f, 1f)

    /** `true` cuando el destello ya se consumió y el motor debe descartarlo. */
    val isExpired: Boolean get() = ageSec >= LIFETIME_SEC

    companion object {
        /**
         * Duración del destello. 280 ms cae dentro del rango de micro-feedback del sistema de
         * diseño (~100–250 ms) con un pelín de cola para el desvanecido: suficiente para que el ojo
         * registre dónde ocurrió la fusión, demasiado corto para tapar el tablero en una cadena de
         * fusiones encadenadas, que es justo cuando más destellos coinciden en pantalla.
         */
        const val LIFETIME_SEC: Float = 0.28f
    }
}

/**
 * Estado de juego de Quantum Merge que el motor (Fase 2) publica como `StateFlow` y que el
 * `Canvas` (Fase 3) observa. Data class inmutable: cada tick produce una copia nueva.
 *
 * A diferencia de `HypergateState`, **no guarda el viewport**: el mundo es fijo ([QuantumWorld]) y
 * la conversión a píxeles es cosa del render (ver Decisión 1 de la cabecera). Esa es la razón de
 * que el contrato no tenga un intent `UpdateViewport`.
 *
 * @property activeSpheres cuerpos vivos dentro del contenedor, ya sujetos a la física. El orden no
 *   es significativo (la resolución de choques es simétrica), pero se conserva estable entre
 *   frames para no desordenar el dibujo.
 * @property currentDropSphere esfera que el dispensador sostiene y que el jugador está apuntando.
 *   Es una [Sphere] completa —no un tier suelto— para que el render la pinte con exactamente la
 *   misma rutina que las del tablero, y para que soltarla sea literalmente *moverla de esta
 *   propiedad a [activeSpheres]* sin construir nada nuevo. Es `null` durante el breve recarga tras
 *   un lanzamiento: el hueco visible del dispensador ES el indicador de que aún no se puede soltar.
 * @property nextSphereTier tier de la SIGUIENTE esfera (la del previsor). Es información pública a
 *   propósito: saber qué viene convierte el juego en planificación en vez de suerte.
 * @property aimX posición horizontal actual de la mira, en unidades de mundo y ya recortada a las
 *   paredes. Se guarda aparte de [currentDropSphere] porque la guía vertical debe seguir
 *   dibujándose (y el dedo seguir moviéndola) **durante la recarga**, cuando no hay esfera que
 *   sostener; al aparecer la siguiente, nace justo aquí.
 * @property flashes destellos de fusión activos (ver [MergeFlash]).
 * @property score puntos acumulados de la partida (suma de [QuantumTier.mergeScore] de cada esfera
 *   nacida, más los bonos de aniquilación).
 * @property merges número de fusiones logradas. Es el numerador de la precisión de la partida:
 *   mide cuánto de lo lanzado acabó combinando en vez de amontonarse.
 * @property drops número de esferas lanzadas.
 * @property bestTier tier más alto alcanzado en la partida. Es el hito que el jugador presume y la
 *   métrica natural de progresión del juego (ver `GameProgressions`, Fase 2).
 * @property dangerProgress 0..1 de lo cerca que está la derrota: la fracción de tiempo de gracia
 *   consumida por la esfera más comprometida por encima de la línea de peligro. Lo calcula el
 *   motor (que es quien conoce el límite) para que la UI solo tenga que pintar la alarma —
 *   parpadeo del marco superior— sin replicar la regla.
 * @property difficulty nivel con el que se juega esta partida. Viaja en el estado (y no solo en el
 *   motor) porque el render lo necesita para dibujar la línea de peligro a su altura, y la antesala
 *   para marcar el nivel elegido. Fuente única: cambiar de nivel es reconstruir el motor.
 */
data class QuantumMergeState(
    val activeSpheres: List<Sphere> = emptyList(),
    val currentDropSphere: Sphere? = null,
    val nextSphereTier: QuantumTier = QuantumTier.QUARK,
    val aimX: Float = QuantumWorld.WIDTH / 2f,
    val flashes: List<MergeFlash> = emptyList(),
    val score: Int = 0,
    val merges: Int = 0,
    val drops: Int = 0,
    val bestTier: QuantumTier = QuantumTier.QUARK,
    val dangerProgress: Float = 0f,
    val difficulty: QuantumDifficulty = QuantumDifficulty.FACIL,
)
