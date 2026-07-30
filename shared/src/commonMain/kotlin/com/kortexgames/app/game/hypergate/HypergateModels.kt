package com.kortexgames.app.game.hypergate

/**
 * # Hypergate — modelos de dominio (Fase 1)
 *
 * Modelos puros e inmutables del minijuego **Hypergate** (categorías *Reflejos* y
 * *Flexibilidad Cognitiva*). No dependen de Compose ni de ninguna API de plataforma:
 * son la fuente de verdad que el motor de física (Fase 2) hace avanzar frame a frame
 * y que el Canvas radial (Fase 3) traduce a píxeles.
 *
 * Mecánica en una frase: un **escudo central** alterna entre dos estados de color; los
 * **proyectiles** viajan en línea recta desde el borde hacia el centro, cada uno "cargado"
 * con el estado que lo absorbe. El jugador toca en cualquier parte para conmutar el escudo
 * y hacer coincidir su estado con el del proyectil justo antes del impacto.
 *
 * ## Por qué coordenadas polares (ángulo + distancia) y no cartesianas (x, y)
 * A diferencia de *Atracción Geométrica* (Polarity Collision), cuyas partículas sufren
 * gravedad y curvas magnéticas —y por eso necesita integrar velocidad en `x/y`—, aquí el
 * movimiento es **radial puro y a velocidad constante**: cada proyectil siempre apunta al
 * centro exacto y nunca se desvía. Modelarlo en polar (un `angle` fijo + una `distance` que
 * decrece) hace que:
 *  - el avance del frame sea un único `distance -= speed * dt` (sin trigonometría por tick),
 *  - la colisión sea una comparación escalar (`distance <= radioEscudo`), no una raíz de una
 *    cuadrática,
 *  - y la trigonometría (`cos/sin`) se pague **una sola vez, en el render**, no en la física.
 *
 * El trade-off: perdemos la posibilidad de trayectorias curvas. Es deliberado —Hypergate es
 * reacción y discriminación visual, no anticipación espacial— y es justo lo que permitirá en
 * Fase 2 compartir con Polarity solo lo genuinamente común (reloj de frames y spawn desde
 * bordes) sin forzar una abstracción de física que no encaja en ambos.
 */

/**
 * Estado del escudo central del Hypergate. Es un dominio **cerrado** de exactamente dos
 * polaridades: se modela como `enum` (nunca strings ni booleanos sueltos) para que el
 * conmutador, el color de render y el tipo requerido por cada proyectil hablen el mismo
 * lenguaje de tipos.
 *
 * El mapeo a color (Verde Neón para [A], Cian Eléctrico para [B]) vive en la capa de UI
 * (Fase 3), no aquí: el dominio no conoce `LogicColors`.
 */
enum class ShieldState {
    /** Primera polaridad. Se pintará con `NeonGreen`. */
    A,

    /** Segunda polaridad. Se pintará con `NeonCyan` / `Electric`. */
    B;

    /**
     * Devuelve la polaridad opuesta. Un `enum` de dos valores hace la conmutación total y
     * exhaustiva sin ramas olvidadas: alternar es simplemente "la otra".
     */
    fun toggled(): ShieldState = if (this == A) B else A
}

/**
 * Un proyectil que viaja en línea recta desde el borde de la pantalla hacia el centro.
 *
 * Representación **polar** respecto al centro del viewport (ver el bloque de cabecera de este
 * archivo para el porqué). La posición cartesiana de render se deriva en Fase 3 como
 * `x = centro.x + cos(angleRad) * distancePx` y análogamente en `y`.
 *
 * @property id identificador estable dentro de la partida. Permite a Compose usar `key(...)`
 *   por proyectil (animaciones/estabilidad) y evita confundir dos proyectiles al reordenar la
 *   lista tras eliminar los que impactan.
 * @property angleRad ángulo de ORIGEN, fijo durante toda la vida del proyectil, medido desde
 *   el centro hacia su punto de aparición en el borde (convención estándar de `atan2`: 0 = eje
 *   +X, crece en sentido antihorario matemático). Al ser radial puro, este ángulo también es
 *   la dirección exacta de viaje (hacia el centro), por eso no cambia nunca.
 * @property distancePx distancia ACTUAL al centro en píxeles. Es lo único que el tick modifica:
 *   arranca en el radio de spawn (fuera de pantalla) y decrece hasta `<= radioEscudo`, momento
 *   del impacto. Nunca negativa.
 * @property speedPx velocidad radial en píxeles por segundo (siempre positiva; el sentido
 *   "hacia el centro" ya está implícito en que restamos a [distancePx]). Constante por
 *   proyectil; la rampa de dificultad la fija en el spawn, no la altera en vuelo.
 * @property required estado del escudo que ABSORBE este proyectil. En el impacto se compara
 *   con el estado vigente del escudo: coincide → absorción (+puntos); difiere → daño.
 */
data class Projectile(
    val id: Long,
    val angleRad: Float,
    val distancePx: Float,
    val speedPx: Float,
    val required: ShieldState,
)

/**
 * Estado de juego del Hypergate que el motor (Fase 2) publica como `StateFlow` y que el Canvas
 * (Fase 3) observa. Data class inmutable: cada tick produce una copia nueva, coherente con MVI.
 *
 * Igual que en Polarity, se conserva la **geometría del viewport en píxeles** dentro del estado
 * para mantener el motor desacoplado del framework: la pantalla reporta su tamaño real y el
 * motor calcula spawns y colisiones con ese dato, sin tocar APIs de Compose desde el engine.
 *
 * @property shield polaridad vigente del escudo central; el tap la conmuta ([ShieldState.toggled]).
 * @property shieldRadiusPx radio del anillo del escudo en píxeles. Es a la vez el umbral de
 *   colisión (un proyectil impacta cuando `distancePx <= shieldRadiusPx`) y el radio de dibujo
 *   del anillo, para que "lo que se ve" y "lo que colisiona" coincidan exactamente. 0 hasta que
 *   llega el primer viewport.
 * @property projectiles proyectiles vivos en pantalla, cada uno en coordenadas polares.
 * @property viewportWidthPx ancho útil del área de juego en píxeles (0 hasta el primer reporte).
 * @property viewportHeightPx alto útil del área de juego en píxeles (0 hasta el primer reporte).
 * @property score puntaje acumulado de la ronda (absorciones suman; los choques penalizan).
 * @property absorbed nº de proyectiles absorbidos con la polaridad correcta (para precisión).
 * @property crashed nº de proyectiles que impactaron con polaridad equivocada (para precisión).
 * @property remainingMs milisegundos restantes de la ronda; la partida termina al llegar a 0.
 */
data class HypergateState(
    val shield: ShieldState = ShieldState.A,
    val shieldRadiusPx: Float = 0f,
    val projectiles: List<Projectile> = emptyList(),
    val viewportWidthPx: Float = 0f,
    val viewportHeightPx: Float = 0f,
    val score: Int = 0,
    val absorbed: Int = 0,
    val crashed: Int = 0,
    val remainingMs: Long = ROUND_DURATION_MS,
)

/**
 * Duración de una ronda de Hypergate. Se expone a nivel de archivo (no en un `companion`) para
 * poder usarla como valor por defecto de [HypergateState.remainingMs] sin exponer internos del
 * motor. Coherente con la ronda de 30 s de Polarity para que el ritmo entre juegos de Reflejos
 * se sienta consistente.
 */
internal const val ROUND_DURATION_MS: Long = 30_000L
