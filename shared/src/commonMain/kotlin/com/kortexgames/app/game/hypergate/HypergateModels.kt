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
 * Clase de proyectil. Dominio cerrado: decide **cómo se resuelve el impacto**, no solo el aspecto.
 */
enum class ProjectileKind {
    /** Cometa de polaridad: se absorbe si el portal tiene su mismo color ([Projectile.required]). */
    COMET,

    /**
     * Meteorito rojo: **no tiene polaridad que lo absorba**. Solo el modo escudo
     * ([HypergateState.barrierActive]) lo detiene; sin él, choca siempre.
     *
     * Existe para que el juego no sea un único gesto: obliga a alternar entre "tocar para
     * igualar" y "mantener para protegerse", que es justo la flexibilidad que el juego entrena.
     */
    METEOR,
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
 *   con el estado vigente del escudo: coincide → absorción (+puntos); difiere → daño. Solo
 *   tiene sentido para [ProjectileKind.COMET]; en un meteorito se ignora.
 * @property kind clase de proyectil (cometa de polaridad o meteorito rojo).
 */
data class Projectile(
    val id: Long,
    val angleRad: Float,
    val distancePx: Float,
    val speedPx: Float,
    val required: ShieldState,
    val kind: ProjectileKind = ProjectileKind.COMET,
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
 * @property barrierActive si el **modo escudo** está encendido ahora mismo (el jugador mantiene
 *   pulsado y no está recargando). Mientras lo está, NADA de lo que llega hace efecto: ni los
 *   meteoritos dañan ni los cometas suman o restan.
 * @property barrierHeldMs cuánto lleva encendido el modo escudo **de forma continua**. Vuelve a 0
 *   al soltar; al llegar a [BARRIER_MAX_HOLD_MS] el escudo se agota.
 * @property barrierCooldownMs milisegundos que faltan para poder volver a usar el modo escudo
 *   tras agotarlo; 0 = disponible.
 * @property deflected nº de proyectiles (de cualquier clase) deshechos contra el modo escudo. No
 *   puntúa ni cuenta para la precisión: existe para que la pantalla sepa que hubo un impacto que
 *   pintar aunque ningún otro contador se haya movido.
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
    val barrierActive: Boolean = false,
    val barrierHeldMs: Long = 0L,
    val barrierCooldownMs: Long = 0L,
    val deflected: Int = 0,
)

/**
 * Duración de una ronda de Hypergate. Se expone a nivel de archivo (no en un `companion`) para
 * poder usarla como valor por defecto de [HypergateState.remainingMs] sin exponer internos del
 * motor. Coherente con la ronda de 30 s de Polarity para que el ritmo entre juegos de Reflejos
 * se sienta consistente.
 */
internal const val ROUND_DURATION_MS: Long = 30_000L

/**
 * Tiempo máximo que el modo escudo aguanta encendido de forma continua. Si el jugador lo mantiene
 * hasta aquí, se agota y entra en recarga ([BARRIER_COOLDOWN_MS]).
 *
 * El tope es lo que impide jugar la ronda entera a cubierto: el escudo es una respuesta puntual
 * a un meteorito, no un sitio donde quedarse.
 */
internal const val BARRIER_MAX_HOLD_MS: Long = 3_000L

/** Recarga del modo escudo tras agotarlo: durante este tiempo no se puede encender. */
internal const val BARRIER_COOLDOWN_MS: Long = 5_000L

/**
 * Radio del modo escudo, en radios del portal. Es una burbuja MAYOR que el anillo: con ella
 * encendida los proyectiles se deshacen ahí, no al llegar al portal, para que el impacto ocurra
 * donde se ve la burbuja. La comparten el motor (colisión) y el render (dibujo).
 */
internal const val BARRIER_RADIUS_FACTOR: Float = 1.75f
