package com.kortexgames.app.game.neonpulse

/**
 * # Neon Pulse — modelos de dominio (FASE 1)
 *
 * Minijuego de la categoría **Reflejos**: entrenador de velocidad y precisión
 * visomotora. Sobre un lienzo libre aparecen nodos de energía con un "anillo de
 * tiempo" que se contrae hacia el centro; el jugador debe tocarlos antes de que
 * el anillo colapse. A partir de cierto momento aparecen nodos trampa que NO
 * deben tocarse.
 *
 * Este archivo contiene únicamente **dominio puro**: no depende de Compose ni de
 * ninguna API de plataforma, de modo que vive con naturalidad en `commonMain` y
 * es testeable de forma aislada (motor y UI se construyen encima en FASE 2/3).
 *
 * ## Sistema de coordenadas
 * Las posiciones ([Node.x]/[Node.y]) y el radio ([Node.radius]) se expresan en
 * **espacio normalizado `[0f..1f]`**, NO en píxeles. El motor (FASE 2) genera y
 * razona sobre colisiones sin conocer el tamaño real del lienzo; el `Canvas`
 * (FASE 3) multiplica por su ancho/alto para pintar. Esto hace el juego
 * independiente de la resolución y del factor de densidad de cada dispositivo.
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
     *  Aparece en la fase avanzada de la partida. Tocarlo penaliza; se debe dejar
     *  expirar solo (expirar NO penaliza, a diferencia del [NORMAL]). */
    TRAP,
}

/**
 * Un nodo de energía activo en el lienzo.
 *
 * Es un `data class` inmutable: el motor no muta nodos, sino que **reemplaza** la
 * lista de activos en cada `tick` (patrón local-first / estado inmutable de MVI).
 * El descuento de tiempo se refleja creando una copia con [remainingMs] menor.
 *
 * @property id identificador único y estable durante la vida del nodo. Un
 *   contador monotónico (`Long`) basta y evita el coste de generar UUIDs por cada
 *   spawn; es la clave que la UI envía en `TapNode(id)` para desambiguar el toque.
 * @property type [NodeType.NORMAL] u [NodeType.TRAP]; decide color y puntuación.
 * @property x posición horizontal del centro en espacio normalizado `[0f..1f]`.
 * @property y posición vertical del centro en espacio normalizado `[0f..1f]`.
 * @property radius radio del núcleo del nodo en espacio normalizado (relativo a la
 *   menor dimensión del lienzo). Define tanto el dibujo como el área de acierto.
 * @property totalLifeMs tiempo de vida total del nodo en milisegundos; es el valor
 *   de referencia para el anillo (fracción llena = [remainingMs] / [totalLifeMs]).
 * @property remainingMs tiempo restante antes de expirar. Cuando llega a `0` el
 *   nodo se retira: si era [NodeType.NORMAL] resta una vida; si era
 *   [NodeType.TRAP] simplemente desaparece.
 */
data class Node(
    val id: Long,
    val type: NodeType,
    val x: Float,
    val y: Float,
    val radius: Float,
    val totalLifeMs: Long,
    val remainingMs: Long,
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
 * —duración, cadencias de aparición, vidas, ventana de trampas— sea ajustable sin
 * tocar la lógica del motor. Los valores implementan las reglas de la §1 del
 * enunciado; la progresión de dificultad se detalla en [SpawnCadence].
 */
object NeonPulseConfig {
    /** Duración total de una partida (30 s). */
    const val GAME_DURATION_MS = 30_000L

    /** Vidas iniciales. Se pierde una al dejar expirar un [NodeType.NORMAL] o al
     *  tocar un [NodeType.TRAP]; a `0` vidas la partida termina antes de tiempo. */
    const val INITIAL_LIVES = 3

    /** Tiempo de vida por defecto de un nodo (1000 ms): lo que tarda el anillo en
     *  contraerse por completo. */
    const val NODE_LIFE_MS = 1_000L

    /** Momento (ms desde el inicio) a partir del cual los nodos viven menos tiempo
     *  encendidos ([NODE_LIFE_FAST_MS]): último tramo de dificultad. */
    const val FAST_LIFE_UNLOCK_MS = 20_000L

    /** Tiempo de vida de un nodo pasado [FAST_LIFE_UNLOCK_MS] (700 ms): obliga a
     *  reaccionar más rápido en el tramo final de la partida. */
    const val NODE_LIFE_FAST_MS = 700L

    /** Radio del núcleo de un nodo en espacio normalizado (relativo a la menor
     *  dimensión del lienzo). También es el radio del área de acierto.
     *  Reducido un 30% (antes 0.06f) para aligerar el lienzo y dejar sitio al halo
     *  del tubo neón sin que los nodos se sientan sobredimensionados. */
    const val NODE_RADIUS = 0.042f

    /** Margen superior (espacio normalizado, fracción de la altura del lienzo)
     *  reservado para el HUD y el botón de pausa: ningún nodo se genera con el
     *  centro por encima de esta línea, así nunca aparece tapado por esos controles. */
    const val TOP_SPAWN_MARGIN = 0.16f

    /** Momento (ms desde el inicio) a partir del cual pueden aparecer trampas. */
    const val TRAP_UNLOCK_MS = 15_000L

    /** Probabilidad de que un spawn sea trampa una vez desbloqueadas. */
    const val TRAP_SPAWN_CHANCE = 0.3f

    /** Puntos que otorga acertar un nodo normal (antes de multiplicador de combo). */
    const val POINTS_PER_HIT = 100
}

/**
 * Escalones de **cadencia de aparición** según el tiempo transcurrido de partida.
 *
 * Implementa la progresión de dificultad del enunciado: la separación entre
 * spawns se acorta en tramos. Se ordena de mayor a menor `fromMs` para poder
 * resolver el intervalo vigente con un simple `first { elapsed >= it.fromMs }`.
 *
 * @property fromMs instante (ms) a partir del cual aplica este intervalo.
 * @property intervalMs milisegundos entre apariciones consecutivas en el tramo.
 */
enum class SpawnCadence(val fromMs: Long, val intervalMs: Long) {
    /** Desde el segundo 20: un objetivo por segundo (más difícil). */
    FAST(fromMs = 20_000L, intervalMs = 1_000L),

    /** Desde el segundo 10: un objetivo cada 2 segundos. */
    MEDIUM(fromMs = 10_000L, intervalMs = 2_000L),

    /** Arranque: un objetivo cada 3 segundos. */
    SLOW(fromMs = 0L, intervalMs = 3_000L);

    companion object {
        /** Intervalo de spawn vigente para el [elapsedMs] dado. */
        fun intervalFor(elapsedMs: Long): Long =
            entries.first { elapsedMs >= it.fromMs }.intervalMs
    }
}
