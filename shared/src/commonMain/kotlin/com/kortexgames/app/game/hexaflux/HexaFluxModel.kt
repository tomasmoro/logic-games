package com.kortexgames.app.game.hexaflux

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * # Neon Hexa Flux — modelos de dominio
 *
 * Minijuego de tablero hexagonal (Visión Espacial / Reconocimiento de Patrones): el
 * jugador coloca piezas de 1–3 fichas de energía sobre una malla de hexágonos; al
 * juntarse [HexaFluxConfig.MERGE_GROUP] o más fichas del mismo [FluxTier] se
 * **fusionan** en una del tier siguiente, y esa fusión puede encadenar otras (combo).
 *
 * Lo que lo distingue de un "merge" de tablero fijo es la progresión en cuatro
 * dimensiones que arma [HexaLevelGenerator]: forma del tablero ([BoardMask]),
 * modificadores tácticos ([Gimmick]), objetivo de victoria ([WinCondition]) y ritmo
 * de dificultad en olas ([WavePhase]).
 *
 * Este archivo es **dominio puro**: no depende de Compose ni de plataforma. Los
 * tiers no llevan color (el mapeo tier → degradado neón es cosa de la pantalla) y la
 * geometría se expresa en unidades de "radio de hexágono", no en píxeles, para que
 * el motor sea testeable sin conocer el tamaño del lienzo.
 */

/**
 * Coordenada **axial** de una celda hexagonal.
 *
 * Se usa axial `(q, r)` y no offset (fila/columna con filas desplazadas) porque en
 * axial los seis vecinos son siempre los mismos seis vectores ([DIRECTIONS]), sin
 * casos especiales para filas pares/impares, y la rotación de piezas y la distancia
 * salen de una fórmula cerrada. La tercera coordenada cúbica [s] se deriva
 * (`q + r + s = 0`), así que no se almacena.
 *
 * La orientación es **pointy-top** (vértice hacia arriba): `q` crece hacia el este y
 * `r` hacia el sureste. Ver [unitX]/[unitY] para el paso a pantalla.
 *
 * @property q columna axial.
 * @property r fila axial.
 */
data class HexCoord(val q: Int, val r: Int) {

    /** Tercera coordenada cúbica, derivada de la invariante `q + r + s = 0`. */
    val s: Int get() = -q - r

    operator fun plus(other: HexCoord): HexCoord = HexCoord(q + other.q, r + other.r)

    /** Las seis celdas adyacentes, en el orden horario de [DIRECTIONS]. */
    fun neighbors(): List<HexCoord> = DIRECTIONS.map { this + it }

    /** Distancia en pasos de hexágono (mitad de la distancia Manhattan en cúbicas). */
    fun distanceTo(other: HexCoord): Int =
        (abs(q - other.q) + abs(r - other.r) + abs(s - other.s)) / 2

    /**
     * Gira esta coordenada 60° en sentido horario alrededor del origen.
     *
     * En cúbicas un giro de 60° es una permutación cíclica con cambio de signo,
     * `(x, y, z) → (−z, −x, −y)`; traducido a axial queda `(q, r) → (−r, q + r)`.
     * Seis aplicaciones devuelven la coordenada original, por eso las piezas rotan
     * sin acumular error ni necesitar trigonometría.
     */
    fun rotatedClockwise(): HexCoord = HexCoord(-r, q + r)

    /**
     * Centro X de la celda en unidades de radio de hexágono (pointy-top):
     * `x = √3 · (q + r/2)`. La pantalla lo multiplica por el radio en píxeles.
     */
    fun unitX(): Float = SQRT_3 * (q + r / 2f)

    /** Centro Y en unidades de radio de hexágono: `y = 3/2 · r` (Y crece hacia abajo). */
    fun unitY(): Float = 1.5f * r

    companion object {
        /** Centro del sistema de coordenadas (y del tablero en las máscaras simétricas). */
        val ORIGIN = HexCoord(0, 0)

        /**
         * Los seis vectores de vecindad, en sentido horario empezando por el este.
         * El orden importa: [rotatedClockwise] lleva cada uno al siguiente de la lista.
         */
        val DIRECTIONS: List<HexCoord> = listOf(
            HexCoord(1, 0), HexCoord(0, 1), HexCoord(-1, 1),
            HexCoord(-1, 0), HexCoord(0, -1), HexCoord(1, -1),
        )

        private val SQRT_3 = sqrt(3f)

        /**
         * Inversa de [unitX]/[unitY]: la celda que contiene el punto `([ux], [uy])`,
         * dado en unidades de radio de hexágono.
         *
         * Se despeja la fórmula directa para obtener un axial **fraccionario** y luego
         * se redondea en cúbicas: se redondean las tres coordenadas y se recalcula la
         * que más se desvió, para restaurar `q + r + s = 0`. Redondear `q` y `r` por
         * separado fallaría cerca de los vértices, donde el punto cae en un hexágono
         * vecino aunque cada coordenada "redondee bien" por su cuenta.
         */
        fun fromUnit(ux: Float, uy: Float): HexCoord {
            val qf = SQRT_3 / 3f * ux - uy / 3f
            val rf = 2f / 3f * uy
            val sf = -qf - rf
            var q = qf.roundToInt()
            var r = rf.roundToInt()
            val s = sf.roundToInt()
            val dq = abs(q - qf)
            val dr = abs(r - rf)
            val ds = abs(s - sf)
            if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
            return HexCoord(q, r)
        }
    }
}

/**
 * Nivel de energía de una ficha. Al fusionarse un grupo, el resultado es una única
 * ficha del tier [next].
 *
 * Es un `enum` y no un `Int` para que la UI pueda asignar un degradado por tier con
 * un `when` exhaustivo (cian → verde → magenta → ámbar → violeta, §9.2) y para que
 * el tope de la escalera sea explícito ([CORE] no tiene siguiente).
 *
 * @property points puntos base que da cada ficha de este tier al fusionarse. Se
 *   duplican por escalón para que perseguir tiers altos compense frente a encadenar
 *   fusiones baratas.
 */
enum class FluxTier(val points: Int) {
    SPARK(10),
    PULSE(20),
    SURGE(40),
    NOVA(80),
    CORE(160);

    /** Tier resultante de una fusión, o `null` en [CORE]: su grupo se desintegra. */
    val next: FluxTier? get() = entries.getOrNull(ordinal + 1)
}

/**
 * Modificador táctico que ocupa una celda.
 *
 * Regla única de interacción, deliberadamente simple para que se aprenda sola: **una
 * fusión adyacente** golpea a todo gimmick con [breaksOnAdjacentMerge]. No hay otra
 * forma de retirarlos.
 */
sealed interface Gimmick {

    /** `true` si la celda no admite fichas mientras el gimmick siga ahí. */
    val blocksPlacement: Boolean

    /** `true` si una fusión en una celda vecina lo daña o lo retira. */
    val breaksOnAdjacentMerge: Boolean

    /**
     * Nodo congelado. Cada fusión adyacente le quita una capa; al llegar a cero la
     * celda queda libre.
     *
     * @property layers capas restantes (2 en dificultad alta).
     */
    data class Ice(val layers: Int = 1) : Gimmick {
        override val blocksPlacement: Boolean get() = true
        override val breaksOnAdjacentMerge: Boolean get() = true
    }

    /** Obstáculo de metal: inamovible e inmune a las fusiones. Solo estorba. */
    data object Stone : Gimmick {
        override val blocksPlacement: Boolean get() = true
        override val breaksOnAdjacentMerge: Boolean get() = false
    }

    /**
     * Bomba de turno. Pierde un turno por cada jugada; una fusión adyacente la
     * desactiva. Si llega a cero **se funde en metal** junto con sus vecinas libres:
     * no termina la partida, pero inutiliza esa zona del tablero.
     *
     * @property turnsLeft jugadas que quedan antes de detonar.
     */
    data class TurnBomb(val turnsLeft: Int) : Gimmick {
        override val blocksPlacement: Boolean get() = true
        override val breaksOnAdjacentMerge: Boolean get() = true
    }

    /**
     * Portal convergente. La celda admite fichas, pero la que se coloca aquí
     * reaparece en [exit] **un tier por encima** (si la salida está libre; si no, se
     * queda donde está). Los portales van siempre en parejas simétricas.
     *
     * @property pairId identificador de la pareja; la UI tiñe igual sus dos extremos.
     * @property exit celda del otro extremo.
     */
    data class Portal(val pairId: Int, val exit: HexCoord) : Gimmick {
        override val blocksPlacement: Boolean get() = false
        override val breaksOnAdjacentMerge: Boolean get() = false
    }

    /**
     * Ficha corrupta (enemiga). Viene precolocada en los niveles de limpieza y va
     * apareciendo sola en los de supervivencia. Se elimina con una fusión adyacente.
     */
    data object Corrupt : Gimmick {
        override val blocksPlacement: Boolean get() = true
        override val breaksOnAdjacentMerge: Boolean get() = true
    }
}

/**
 * Familias de gimmick que el generador puede sortear, con el nivel en que se
 * desbloquean. El **orden de declaración es el de desbloqueo**: el generador lo usa
 * para saber cuál es la mecánica "más nueva" que toca presentar en un nivel de
 * introducción ([WavePhase.INTRO]).
 *
 * [Gimmick.Corrupt] no figura aquí a propósito: no es un modificador opcional sino
 * la materia prima de dos objetivos ([WinCondition.ClearBoard] y
 * [WinCondition.Survive]), así que su presencia la decide el objetivo, no el nivel.
 *
 * @property unlockLevel primer nivel en que puede aparecer.
 */
enum class GimmickKind(val unlockLevel: Int) {
    ICE(3),
    STONE(6),
    TURN_BOMB(11),
    PORTAL(16),
}

/**
 * Efecto visual pendiente de una celda tras la última jugada.
 *
 * Vive en el estado (y no como efecto one-shot) porque la pantalla lo anima de forma
 * reactiva con `animate*AsState` (§9.4.6): la celda *es* "recién fusionada" hasta la
 * jugada siguiente, que la devuelve a [NONE].
 */
enum class CellAnim {
    NONE,

    /** Ficha recién colocada por el jugador. */
    PLACED,

    /** Resultado de una fusión: rebote de escala con `spring`. */
    MERGED,

    /** Un gimmick acaba de romperse aquí (hielo, bomba desactivada, corrupta). */
    SHATTERED,

    /** Ficha corrupta o metal recién aparecidos. */
    SPAWNED,

    /** Ficha que acaba de salir por un portal. */
    TELEPORTED,
}

/**
 * Una celda habilitada del tablero. Las celdas que la máscara deja fuera
 * sencillamente **no existen** en el mapa de estado (no hay un tipo "deshabilitada"),
 * así el motor no puede colocar ni propagar nada sobre ellas por error.
 *
 * @property coord posición axial; duplicada respecto a la clave del mapa para poder
 *   pasar celdas sueltas a la UI.
 * @property tier ficha de energía que la ocupa, o `null` si no hay ninguna.
 * @property gimmick modificador que la ocupa, o `null`. Salvo [Gimmick.Portal], es
 *   excluyente con [tier].
 * @property anim efecto visual pendiente (ver [CellAnim]).
 */
data class HexCell(
    val coord: HexCoord,
    val tier: FluxTier? = null,
    val gimmick: Gimmick? = null,
    val anim: CellAnim = CellAnim.NONE,
) {
    /** `true` si admite una ficha ahora mismo. */
    val isFree: Boolean get() = tier == null && gimmick?.blocksPlacement != true

    /** `true` si cuenta para el objetivo de limpieza ([WinCondition.ClearBoard]). */
    val isClearTarget: Boolean get() = gimmick is Gimmick.Ice || gimmick is Gimmick.Corrupt
}

/**
 * Silueta del tablero. Cambia cada [LEVELS_PER_MASK] niveles para que el jugador
 * tenga que re-aprender a leer el espacio: la misma mecánica de fusión se juega muy
 * distinto con un hueco central o con un cuello de botella.
 */
enum class BoardMask {
    /** Hexágono simétrico clásico (niveles 1–10). */
    CONCENTRIC,

    /** Anillo con un vacío central inalcanzable (11–20): no hay "centro" donde acumular. */
    DONUT,

    /** Dos lóbulos unidos por una sola celda (21–30): el cuello decide la partida. */
    BUTTERFLY,

    /** Zonas desconectadas (31+) que solo se comunican por portales. */
    ISLANDS;

    companion object {
        /** Niveles que dura cada máscara antes de pasar a la siguiente. */
        const val LEVELS_PER_MASK = 10

        /** Máscara del [level] (1-based); a partir de la última se queda en [ISLANDS]. */
        fun forLevel(level: Int): BoardMask =
            entries[((level - 1) / LEVELS_PER_MASK).coerceIn(0, entries.lastIndex)]
    }
}

/**
 * Posición del nivel dentro de la ola de 5. Es el factor que convierte una rampa
 * lineal (aburrida y agotadora) en un diente de sierra con respiros; la ecuación
 * completa está en [HexaLevelGenerator].
 *
 * @property intensity multiplicador sobre la dificultad base del ciclo.
 */
enum class WavePhase(val intensity: Float) {
    /** N1: presenta la mecánica más nueva, sola y en poca cantidad. */
    INTRO(0.6f),

    /** N2: la misma mecánica con algo más de presión. */
    MASTERY(0.85f),

    /** N3: dificultad nominal del ciclo. */
    MASTERY_PLUS(1f),

    /** N4: nivel jefe, claramente por encima de lo visto. */
    BOSS(1.35f),

    /** N5: catarsis. Pocos colores y casi sin estorbos para que lluevan los combos. */
    CATHARSIS(0.5f);

    companion object {
        /** Niveles por ola. */
        const val CYCLE = 5

        /** Fase del [level] (1-based). */
        fun forLevel(level: Int): WavePhase = entries[(level - 1) % CYCLE]
    }
}

/**
 * Objetivo de victoria del nivel. Rota con periodo 3 mientras la ola rota con
 * periodo 5; al ser coprimos, cada fase se cruza con cada objetivo antes de repetirse
 * (15 niveles), de modo que "el jefe" no es siempre del mismo tipo.
 */
sealed interface WinCondition {

    /**
     * Alcanzar [target] puntos antes de agotar las jugadas o llenar el tablero.
     *
     * @property target puntuación a alcanzar.
     */
    data class TargetScore(val target: Int) : WinCondition

    /**
     * Eliminar todas las celdas objetivo precolocadas ([HexCell.isClearTarget]). El
     * total no se guarda aquí: se cuenta sobre el tablero inicial, que es la única
     * fuente de verdad (ver [HexaLevelConfig.initialProgress]).
     */
    data object ClearBoard : WinCondition

    /**
     * Aguantar [turns] jugadas mientras aparecen fichas corruptas.
     *
     * @property turns jugadas a resistir.
     * @property spawnEvery cada cuántas jugadas aparece una corrupta nueva.
     */
    data class Survive(val turns: Int, val spawnEvery: Int) : WinCondition
}

/**
 * Avance hacia el objetivo, en la unidad que corresponda (puntos, celdas limpiadas o
 * turnos resistidos). Un único par `actual/meta` permite que el HUD pinte la misma
 * barra para los tres [WinCondition].
 */
data class ObjectiveProgress(val current: Int, val goal: Int) {
    /** Fracción `[0f..1f]` para la barra de progreso. */
    val fraction: Float get() = if (goal <= 0) 1f else (current.toFloat() / goal).coerceIn(0f, 1f)

    /** `true` cuando se alcanzó la meta. */
    val isComplete: Boolean get() = current >= goal
}

/**
 * Una ficha dentro de una pieza.
 *
 * @property offset desplazamiento respecto al ancla de la pieza.
 * @property tier energía de la ficha.
 */
data class PieceTile(val offset: HexCoord, val tier: FluxTier)

/**
 * Pieza colocable: de una a tres fichas contiguas.
 *
 * @property id identificador estable entre rotaciones, para que la UI anime la misma
 *   pieza en vez de recrearla al girar.
 * @property tiles fichas que la componen; siempre hay una con offset [HexCoord.ORIGIN]
 *   (el ancla, que es la celda que toca el jugador).
 */
data class HexPiece(val id: Long, val tiles: List<PieceTile>) {

    /** La misma pieza girada 60° en horario alrededor de su ancla. */
    fun rotatedClockwise(): HexPiece =
        copy(tiles = tiles.map { it.copy(offset = it.offset.rotatedClockwise()) })

    /** Celdas del tablero que ocuparía con el ancla en [anchor]. */
    fun cellsAt(anchor: HexCoord): List<HexCoord> = tiles.map { anchor + it.offset }
}

/**
 * De qué están hechas las piezas de un nivel.
 *
 * @property tiers tiers que pueden salir. Es la palanca de dificultad más potente:
 *   con dos colores casi cualquier colocación fusiona; con cuatro hay que planificar.
 * @property maxTiles tamaño máximo de pieza (1..3).
 */
data class PieceBag(val tiers: List<FluxTier>, val maxTiles: Int)

/**
 * Receta completa e inmutable de un nivel, tal como la produce [HexaLevelGenerator].
 *
 * @property level número de nivel (1-based).
 * @property mask silueta del tablero.
 * @property phase posición en la ola de 5.
 * @property difficulty valor `D` de la ecuación de dificultad con el que se generó;
 *   se conserva para que la puntuación final pueda ponderar por dificultad.
 * @property winCondition objetivo de victoria.
 * @property moveLimit jugadas disponibles.
 * @property gimmicks familias de gimmick presentes (para la leyenda/ayuda del nivel).
 * @property initialBoard tablero de partida; sus claves son exactamente las celdas
 *   habilitadas por la máscara.
 * @property pieceBag composición de las piezas que irán saliendo.
 */
data class HexaLevelConfig(
    val level: Int,
    val mask: BoardMask,
    val phase: WavePhase,
    val difficulty: Float,
    val winCondition: WinCondition,
    val moveLimit: Int,
    val gimmicks: Set<GimmickKind>,
    val initialBoard: Map<HexCoord, HexCell>,
    val pieceBag: PieceBag,
) {
    /** Progreso con el que arranca el nivel, con la meta ya resuelta para su objetivo. */
    fun initialProgress(): ObjectiveProgress = when (winCondition) {
        is WinCondition.TargetScore -> ObjectiveProgress(0, winCondition.target)
        WinCondition.ClearBoard -> ObjectiveProgress(0, initialBoard.values.count { it.isClearTarget })
        is WinCondition.Survive -> ObjectiveProgress(0, winCondition.turns)
    }
}

/** Constantes de reglas compartidas por el generador y el motor. */
object HexaFluxConfig {
    /** Fichas contiguas del mismo tier necesarias para fusionar. */
    const val MERGE_GROUP = 3

    /** Piezas visibles a la vez en la bandeja. */
    const val TRAY_SIZE = 3
}
