package com.kortexgames.app.game.neonpulse

/**
 * # Neon Pulse — modelos de dominio
 *
 * Minijuego de la categoría **Reflejos**: entrenador de velocidad y precisión
 * visomotora. Sobre un lienzo libre aparecen nodos de energía con un "anillo de
 * tiempo" que se contrae hacia el centro; el jugador debe tocarlos antes de que
 * el anillo colapse.
 *
 * ## Partida infinita por hordas
 * La partida **no tiene reloj**: se juega hasta quedarse sin vidas. El contenido
 * se organiza en **hordas** ([WaveSpec]) cada vez más exigentes, con una rampa
 * *continua y paulatina* (nada de saltos bruscos entre tramos): cada horda trae
 * más nodos, los suelta más seguido y los deja menos tiempo encendidos, hasta
 * topes prefijados que mantienen el juego difícil pero jugable. A partir de
 * [NeonPulseConfig.MOVE_UNLOCK_WAVE] los nodos además **se mueven**, y cada
 * [NeonPulseConfig.HEART_EVERY_WAVES] hordas puede caer un **corazón** que
 * devuelve una vida (solo si al jugador le falta alguna).
 *
 * Este archivo contiene únicamente **dominio puro**: no depende de Compose ni de
 * ninguna API de plataforma, de modo que vive con naturalidad en `commonMain` y
 * es testeable de forma aislada (el motor vive en el ViewModel y la UI en la
 * pantalla).
 *
 * ## Sistema de coordenadas
 * Las posiciones ([Node.x]/[Node.y]), el radio ([Node.radius]) y la velocidad
 * ([Node.vx]/[Node.vy]) se expresan en **espacio normalizado `[0f..1f]`**, NO en
 * píxeles. El motor genera y razona sobre colisiones sin conocer el tamaño real
 * del lienzo; el `Canvas` multiplica por su ancho/alto para pintar. Esto hace el
 * juego independiente de la resolución y del factor de densidad de cada
 * dispositivo.
 */

/**
 * Tipo de nodo, con la semántica de puntuación asociada.
 *
 * Se modela como `enum` (dominio cerrado) en lugar de un `Boolean` para que el
 * `when` de la lógica de colisión sea exhaustivo y quede sitio a futuros tipos
 * (p. ej. bonus dorado) sin romper llamadas existentes.
 */
enum class NodeType {
    /** Objetivo válido (naranja coral). Tocarlo suma puntos; dejarlo expirar
     *  cuesta una vida. */
    NORMAL,

    /** Nodo trampa (rojo/[com.kortexgames.app.core.theme.LogicColors.Error]).
     *  Aparece a partir de [NeonPulseConfig.TRAP_UNLOCK_WAVE]. Tocarlo penaliza;
     *  se debe dejar expirar solo (expirar NO penaliza, a diferencia del [NORMAL]). */
    TRAP,

    /** Corazón de rescate (verde neón). Tocarlo devuelve una vida hasta el tope
     *  [NeonPulseConfig.MAX_LIVES]; dejarlo expirar no penaliza —es un premio, no
     *  un objetivo obligatorio—. Solo aparece cuando el jugador ha perdido vidas. */
    HEART,
}

/**
 * Un nodo de energía activo en el lienzo.
 *
 * Es un `data class` inmutable: el motor no muta nodos, sino que **reemplaza** la
 * lista de activos en cada `tick` (patrón local-first / estado inmutable de MVI).
 * El descuento de tiempo y el desplazamiento se reflejan creando una copia.
 *
 * @property id identificador único y estable durante la vida del nodo. Un
 *   contador monotónico (`Long`) basta y evita el coste de generar UUIDs por cada
 *   spawn; es la clave que la UI envía en `TapNode(id)` para desambiguar el toque.
 * @property type [NodeType.NORMAL], [NodeType.TRAP] o [NodeType.HEART]; decide
 *   color, forma y consecuencia del toque.
 * @property x posición horizontal del centro en espacio normalizado `[0f..1f]`.
 * @property y posición vertical del centro en espacio normalizado `[0f..1f]`.
 * @property radius radio del núcleo del nodo en espacio normalizado (relativo a la
 *   menor dimensión del lienzo). Define tanto el dibujo como el área de acierto.
 * @property totalLifeMs tiempo de vida total del nodo en milisegundos; es el valor
 *   de referencia para el anillo (fracción llena = [remainingMs] / [totalLifeMs]).
 * @property remainingMs tiempo restante antes de expirar. Cuando llega a `0` el
 *   nodo se retira: si era [NodeType.NORMAL] resta una vida; el resto de tipos
 *   simplemente desaparecen.
 * @property vx velocidad horizontal en **unidades normalizadas por segundo**
 *   (`0f` = nodo quieto). Se expresa por segundo —y no por frame— para que el
 *   movimiento sea independiente del framerate: el motor lo multiplica por el
 *   delta real de cada tick.
 * @property vy velocidad vertical, misma unidad que [vx].
 */
data class Node(
    val id: Long,
    val type: NodeType,
    val x: Float,
    val y: Float,
    val radius: Float,
    val totalLifeMs: Long,
    val remainingMs: Long,
    val vx: Float = 0f,
    val vy: Float = 0f,
) {
    /**
     * Fracción de vida restante en `[0f..1f]`. La UI la usa para interpolar el
     * radio del **anillo de tiempo** que se contrae hacia el núcleo: `1f` = anillo
     * en su tamaño máximo (recién aparecido), `0f` = anillo colapsado (expirado).
     */
    val lifeFraction: Float
        get() = if (totalLifeMs <= 0L) 0f else (remainingMs.toFloat() / totalLifeMs).coerceIn(0f, 1f)
}

/**
 * Constantes de diseño y balance de Neon Pulse.
 *
 * Se centralizan aquí (una sola fuente de verdad) para que el balance del juego
 * —ritmo de aparición, vidas, rampa de dificultad— sea ajustable sin tocar la
 * lógica del motor. La rampa por horda se resuelve en [WaveSpec.forWave].
 *
 * ### Por qué toda la rampa es lineal con tope
 * Una progresión lineal (`inicio - paso * (horda-1)`, recortada por un mínimo)
 * sube la exigencia de forma **paulatina y predecible**: el jugador nota cada
 * horda algo más rápida sin muros repentinos, y el `coerceAtLeast` garantiza que
 * a partir de cierta horda el juego se estabiliza en su punto más difícil en vez
 * de volverse literalmente imposible (nodos que expiran antes de poder tocarlos).
 */
object NeonPulseConfig {
    /** Vidas iniciales. Se pierde una al dejar expirar un [NodeType.NORMAL] o al
     *  tocar un [NodeType.TRAP]; a `0` vidas la partida termina. */
    const val INITIAL_LIVES = 3

    /** Tope de vidas acumulables con corazones. Igual a [INITIAL_LIVES]: el
     *  corazón solo puede devolverte a como empezaste, nunca darte una reserva
     *  mayor —si no, acumular vidas de sobra anularía la tensión de la partida—. */
    const val MAX_LIVES = INITIAL_LIVES

    /** Radio del núcleo de un nodo en espacio normalizado (relativo a la menor
     *  dimensión del lienzo). También es el radio del área de acierto. */
    const val NODE_RADIUS = 0.042f

    /** Margen superior (espacio normalizado, fracción de la altura del lienzo)
     *  reservado para el HUD y el botón de pausa: ningún nodo se genera —ni rebota—
     *  con el centro por encima de esta línea, así nunca aparece tapado por esos
     *  controles. */
    const val TOP_SPAWN_MARGIN = 0.16f

    /** Puntos que otorga acertar un nodo normal (antes de multiplicador de combo). */
    const val POINTS_PER_HIT = 100

    /** Bonus por completar una horda, multiplicado por su número: premia aguantar
     *  cada vez más lejos, que es la métrica real de una partida infinita. */
    const val WAVE_CLEAR_BONUS = 50

    // --- Rampa de dificultad por horda ------------------------------------------

    /** Nodos que trae la primera horda. */
    const val WAVE_BASE_NODES = 6

    /** Nodos adicionales por cada horda superada. */
    const val WAVE_NODES_STEP = 1

    /** Tope de nodos por horda: por encima la horda se haría larguísima sin ser
     *  más difícil (la dificultad real la marcan cadencia y vida, no la duración). */
    const val WAVE_MAX_NODES = 20

    /** Milisegundos entre apariciones en la horda 1. */
    const val SPAWN_INTERVAL_START_MS = 1_500L

    /** Cuánto se acorta el intervalo de aparición por cada horda. */
    const val SPAWN_INTERVAL_STEP_MS = 80L

    /** Intervalo mínimo entre apariciones (tope de dificultad de la cadencia). */
    const val SPAWN_INTERVAL_MIN_MS = 380L

    /** Tiempo que un nodo permanece encendido en la horda 1. */
    const val NODE_LIFE_START_MS = 1_700L

    /** Cuánto se acorta la vida de los nodos por cada horda. */
    const val NODE_LIFE_STEP_MS = 70L

    /** Vida mínima de un nodo: por debajo el juego dejaría de ser un reto de
     *  reflejos para convertirse en lotería (no da tiempo material a verlo y
     *  tocarlo). Es el "difícil pero no imposible" del diseño. */
    const val NODE_LIFE_MIN_MS = 620L

    /** Vida de un corazón. Más generosa que la de un nodo normal: es una segunda
     *  oportunidad y perderla por milisegundos se sentiría injusto. */
    const val HEART_LIFE_MS = 2_600L

    /** Primera horda en la que pueden aparecer trampas. */
    const val TRAP_UNLOCK_WAVE = 3

    /** Probabilidad de trampa en la horda [TRAP_UNLOCK_WAVE]. */
    const val TRAP_CHANCE_START = 0.10f

    /** Incremento de probabilidad de trampa por horda. */
    const val TRAP_CHANCE_STEP = 0.025f

    /** Tope de probabilidad de trampa: más allá el lienzo sería mayoritariamente
     *  rojo y el juego pasaría a ser "no tocar nada". */
    const val TRAP_CHANCE_MAX = 0.32f

    /** Primera horda en la que los nodos **se mueven** (antes son estáticos). */
    const val MOVE_UNLOCK_WAVE = 6

    /** Velocidad (unidades normalizadas por segundo) en la horda [MOVE_UNLOCK_WAVE]. */
    const val MOVE_SPEED_START = 0.06f

    /** Incremento de velocidad por horda a partir de [MOVE_UNLOCK_WAVE]. */
    const val MOVE_SPEED_STEP = 0.018f

    /** Velocidad máxima: a 0.30 un nodo tarda ~3,3 s en cruzar el lienzo entero,
     *  suficiente para obligar a perseguirlo sin que sea imposible interceptarlo. */
    const val MOVE_SPEED_MAX = 0.30f

    /** Cada cuántas hordas se ofrece un corazón de rescate. */
    const val HEART_EVERY_WAVES = 5

    /** Duración del cartel "HORDA N" entre hordas. Es el respiro que separa una
     *  oleada de la siguiente; sin él la partida infinita se volvería agotadora. */
    const val WAVE_BANNER_MS = 1_300L
}

/**
 * Parámetros ya resueltos de una horda concreta: lo único que el motor necesita
 * saber para generarla. Se calcula una vez al empezar la horda (no en cada tick)
 * y se guarda, de modo que la rampa quede en un único sitio testeable.
 *
 * @property wave número de horda (1-based).
 * @property nodeCount cuántos nodos se generarán en total durante la horda.
 * @property spawnIntervalMs separación entre apariciones consecutivas.
 * @property nodeLifeMs tiempo encendido de cada nodo de la horda.
 * @property trapChance probabilidad `[0f..1f]` de que un nodo sea trampa.
 * @property speed velocidad de los nodos en unidades normalizadas por segundo
 *   (`0f` = estáticos, hasta [NeonPulseConfig.MOVE_UNLOCK_WAVE]).
 * @property offersHeart `true` si esta horda incluye un corazón de rescate
 *   —el motor solo lo generará si además al jugador le faltan vidas—.
 */
data class WaveSpec(
    val wave: Int,
    val nodeCount: Int,
    val spawnIntervalMs: Long,
    val nodeLifeMs: Long,
    val trapChance: Float,
    val speed: Float,
    val offersHeart: Boolean,
) {
    companion object {
        /**
         * Resuelve la rampa de dificultad para la horda [wave] (1-based).
         *
         * Todas las magnitudes progresan linealmente y se recortan contra su tope,
         * de forma que la curva sube sin escalones y luego se aplana en el punto
         * más difícil jugable (ver el KDoc de [NeonPulseConfig]).
         */
        fun forWave(wave: Int): WaveSpec {
            val steps = (wave - 1).coerceAtLeast(0)
            return WaveSpec(
                wave = wave,
                nodeCount = (NeonPulseConfig.WAVE_BASE_NODES + steps * NeonPulseConfig.WAVE_NODES_STEP)
                    .coerceAtMost(NeonPulseConfig.WAVE_MAX_NODES),
                spawnIntervalMs = (NeonPulseConfig.SPAWN_INTERVAL_START_MS - steps * NeonPulseConfig.SPAWN_INTERVAL_STEP_MS)
                    .coerceAtLeast(NeonPulseConfig.SPAWN_INTERVAL_MIN_MS),
                nodeLifeMs = (NeonPulseConfig.NODE_LIFE_START_MS - steps * NeonPulseConfig.NODE_LIFE_STEP_MS)
                    .coerceAtLeast(NeonPulseConfig.NODE_LIFE_MIN_MS),
                trapChance = if (wave < NeonPulseConfig.TRAP_UNLOCK_WAVE) {
                    0f
                } else {
                    (
                        NeonPulseConfig.TRAP_CHANCE_START +
                            (wave - NeonPulseConfig.TRAP_UNLOCK_WAVE) * NeonPulseConfig.TRAP_CHANCE_STEP
                        ).coerceAtMost(NeonPulseConfig.TRAP_CHANCE_MAX)
                },
                speed = if (wave < NeonPulseConfig.MOVE_UNLOCK_WAVE) {
                    0f
                } else {
                    (
                        NeonPulseConfig.MOVE_SPEED_START +
                            (wave - NeonPulseConfig.MOVE_UNLOCK_WAVE) * NeonPulseConfig.MOVE_SPEED_STEP
                        ).coerceAtMost(NeonPulseConfig.MOVE_SPEED_MAX)
                },
                offersHeart = wave % NeonPulseConfig.HEART_EVERY_WAVES == 0,
            )
        }
    }
}
