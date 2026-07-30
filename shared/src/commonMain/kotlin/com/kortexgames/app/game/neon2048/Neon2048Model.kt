package com.kortexgames.app.game.neon2048

import kotlinx.serialization.Serializable

/**
 * # Neon Grid 2048 — modelos de dominio (FASE 1)
 *
 * Minijuego de la categoría **Cálculo Mental**: reimaginación neón del clásico
 * 2048. Tablero cuadrado (4×4 a 8×8, elegido por el jugador en la antesala; ver
 * [Neon2048Config.BOARD_SIZE_OPTIONS]), fichas potencia de dos que se deslizan y
 * se fusionan al sumar valores iguales.
 *
 * Este archivo es **dominio puro**: no depende de Compose ni de ninguna API de
 * plataforma, así vive con naturalidad en `commonMain` y el motor (FASE 2) es
 * testeable de forma aislada.
 *
 * ## Decisión de diseño central: la ficha tiene identidad propia
 * El estado NO se modela como una matriz `Array<IntArray>` de valores, sino como
 * una **lista de [Tile] con `id` estable e independiente de sus coordenadas**.
 * El motivo es de renderizado: Compose necesita `key(tile.id)` para reconocer
 * que la ficha de `(0,3)` y la de `(0,0)` son *la misma* tras un swipe y poder
 * animar su traslación. Con una matriz de valores, cada movimiento se leería
 * como "la ficha vieja desapareció y nació otra": las fichas se teletransportan
 * en vez de deslizarse. La matriz sigue existiendo, pero solo como estructura
 * **intermedia** dentro del algoritmo de colapso (ver ViewModel, FASE 2).
 *
 * ## Sistema de coordenadas
 * Se usa `(row, col)` y no `(x, y)` para evitar la ambigüedad clásica de qué eje
 * es cuál: `row` crece hacia **abajo** y `col` hacia la **derecha**, tal y como
 * se dibuja. La traducción celda → píxeles es responsabilidad de la UI (FASE 3),
 * que es la única que conoce el tamaño real del tablero.
 */

/**
 * Coordenada de celda del tablero: fila y columna en `0 until boardSize`.
 *
 * Existe como tipo propio (en vez de un `Pair<Int, Int>`) para que las firmas
 * del motor se lean solas.
 */
@Serializable
data class GridPos(val row: Int, val col: Int)

/**
 * Dirección del deslizamiento del jugador.
 *
 * Se modela como enum (dominio cerrado) y no como vector libre porque el
 * algoritmo de colapso solo admite estas cuatro; además el `when` del motor
 * queda exhaustivo sin rama `else`.
 *
 * @property deltaRow desplazamiento por paso en filas (+1 abajo, -1 arriba).
 * @property deltaCol desplazamiento por paso en columnas (+1 derecha, -1 izquierda).
 */
enum class Direction(val deltaRow: Int, val deltaCol: Int) {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1),
    ;

    /**
     * true si el movimiento recorre el eje vertical (UP/DOWN).
     *
     * El motor colapsa siempre "líneas" de 4 celdas: para los verticales la
     * línea es una columna y para los horizontales una fila. Este flag evita
     * duplicar el algoritmo cuatro veces.
     */
    val isVertical: Boolean get() = this == UP || this == DOWN
}

/**
 * Una ficha viva sobre el tablero.
 *
 * `data class` inmutable: el motor nunca muta fichas, produce una **lista nueva**
 * en cada jugada (patrón de estado inmutable de MVI). Una ficha "se mueve"
 * emitiendo una copia con otras coordenadas pero **el mismo [id]**, que es
 * justo lo que permite a Compose animarla en vez de recrearla.
 *
 * @property id identificador único y estable durante toda la vida de la ficha,
 *   **independiente de sus coordenadas**. Un contador monotónico (`Long`) basta
 *   y evita el coste de generar UUIDs en cada spawn (hay uno por jugada). Es la
 *   `key` que usará el composable de la FASE 3.
 * @property value valor numérico; siempre potencia de dos (2, 4, 8, … 2048, …).
 * @property row fila actual `0 until boardSize` (crece hacia abajo).
 * @property col columna actual `0 until boardSize` (crece hacia la derecha).
 * @property isNew true solo durante el turno en el que la ficha apareció. La UI
 *   lo usa para lanzar la animación de nacimiento (`scale` 0 → 1 con `spring`);
 *   se apaga en la siguiente jugada. Vive en el estado —y no como efecto— porque
 *   describe *cómo se pinta la ficha ahora*, no un evento puntual.
 * @property isMerged true si esta ficha es el **resultado** de fusionar dos en el
 *   turno actual. Dispara el "pop" (escala 1 → 1.2 → 1). Igual que [isNew], se
 *   limpia en la siguiente jugada.
 */
@Serializable
data class Tile(
    val id: Long,
    val value: Int,
    val row: Int,
    val col: Int,
    val isNew: Boolean = false,
    val isMerged: Boolean = false,
) {

    /** Posición de la ficha como coordenada de celda. */
    val position: GridPos get() = GridPos(row, col)

    /**
     * Exponente de la ficha: `log2(value)` (2 → 1, 4 → 2, 8 → 3, … 2048 → 11).
     *
     * Se calcula con `countTrailingZeroBits()` en lugar de logaritmos en coma
     * flotante: el valor SIEMPRE es potencia de dos, así que el número de ceros
     * a la derecha *es* el exponente — exacto, entero y sin riesgo de redondeo
     * (un `log2(1024f)` que devuelva 9.999997 rompería el índice de color).
     *
     * Es la entrada de la rampa de color neón de la UI (FASE 3): el color no se
     * hardcodea por valor, se deriva de este exponente.
     */
    val power: Int get() = value.countTrailingZeroBits()
}

/**
 * Ficha absorbida en una fusión: la que desaparece al chocar con otra igual.
 *
 * Se modela con **origen y destino explícitos** (y no reutilizando [Tile], que
 * solo tiene una posición) porque la UI necesita animar el fantasma **viajando**
 * desde su casilla original hasta la casilla de fusión, igual que la ficha
 * superviviente. Si solo se guardara el destino (como se hacía antes), el
 * fantasma nacería ya colocado ahí desde su primer frame —al ser una ficha
 * nueva para Compose, sin historial de posición previa que animar— y quedaría
 * "plantado" visible en la casilla de destino mientras la superviviente todavía
 * viaja hacia allí, en vez de resolverse como un único encuentro fluido.
 *
 * @property id identificador de la ficha absorbida (el mismo que tenía como [Tile]
 *   un instante antes de la fusión).
 * @property value valor que tenía la ficha absorbida (el ORIGINAL, no el
 *   resultante de la fusión: ese lo lleva la [Tile] superviviente).
 * @property from casilla donde estaba la ficha antes de esta jugada.
 * @property to casilla de fusión a la que se dirige (la misma que ocupa la
 *   [Tile] superviviente al terminar la jugada).
 */
@Serializable
data class Ghost(
    val id: Long,
    val value: Int,
    val from: GridPos,
    val to: GridPos,
)

/**
 * Constantes de reglas y balance de Neon Grid 2048.
 *
 * Centralizadas aquí (fuente única de verdad) para poder ajustar el juego sin
 * tocar la lógica del motor ni la UI.
 */
object Neon2048Config {

    /**
     * Lado del tablero con el que arranca la antesala, antes de que el jugador
     * elija uno en el selector de tamaño (ver [BOARD_SIZE_OPTIONS]). 4×4 es el
     * estándar del género.
     */
    const val DEFAULT_BOARD_SIZE: Int = 4

    /**
     * Tamaños de tablero seleccionables en la antesala, de menor a mayor. Es la
     * **fuente única de verdad**: el selector de la UI genera un chip por cada
     * valor de esta lista, así que añadir (o quitar) un tamaño es un cambio de
     * una sola línea, aquí, sin tocar la pantalla.
     */
    val BOARD_SIZE_OPTIONS: List<Int> = listOf(4, 5, 6, 7, 8)

    /** Fichas con las que arranca la partida. */
    const val INITIAL_TILES: Int = 2

    /** Valor por defecto de una ficha recién aparecida. */
    const val SPAWN_VALUE: Int = 2

    /** Valor "afortunado" ocasional de una ficha recién aparecida. */
    const val SPAWN_VALUE_LUCKY: Int = 4

    /**
     * Probabilidad de que la ficha nueva sea un 4 en vez de un 2.
     *
     * 10 % es el valor del 2048 original: suficiente para acelerar la partida de
     * vez en cuando sin desbaratar la planificación del jugador.
     */
    const val SPAWN_LUCKY_PROBABILITY: Float = 0.1f

    /** Valor objetivo que declara la victoria (se puede seguir jugando después). */
    const val WINNING_VALUE: Int = 2048

    /**
     * A partir de este valor, una fusión dispara háptica **HEAVY** en vez de
     * ligera. Reservar la vibración fuerte a los hitos grandes evita que el
     * feedback se banalice: si todo vibra fuerte, nada se siente importante
     * (mismo principio que "el neón vale porque es raro", CLAUDE.md §9.1).
     */
    const val HEAVY_HAPTIC_THRESHOLD: Int = 128

    /**
     * A partir de este valor (pieza mayor a 64, es decir 128 en adelante), una
     * fusión dispara una salva corta de fuegos artificiales
     * ([com.kortexgames.app.game.neon2048.Neon2048Effect.ShowMergeCelebration]).
     * Coincide hoy con [HEAVY_HAPTIC_THRESHOLD] pero es una constante aparte a
     * propósito: son dos decisiones de producto distintas (intensidad de
     * háptica vs. cuándo vale la pena una celebración visual) que podrían
     * divergir sin acoplarlas por compartir el mismo número.
     */
    const val FIREWORKS_MERGE_THRESHOLD: Int = 128

    /**
     * Umbral mínimo de arrastre para que un gesto cuente como swipe. Filtra los
     * micro-desplazamientos del dedo al tocar la pantalla, que si no provocarían
     * jugadas accidentales.
     *
     * Se expresa en **dp y no en píxeles** a propósito: el gesto lo hace un dedo,
     * cuyo tamaño físico no cambia con la densidad de la pantalla. En px, el mismo
     * valor sería un roce en un móvil de alta densidad y un arrastre largo en uno
     * bajo. La UI (FASE 3) lo convierte con `.dp.toPx()`.
     */
    const val SWIPE_THRESHOLD_DP: Float = 24f
}
