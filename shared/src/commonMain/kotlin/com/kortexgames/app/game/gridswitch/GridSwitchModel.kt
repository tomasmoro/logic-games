package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighbors
import kotlinx.serialization.Serializable

/**
 * # Modelos de dominio de "Neon Grid Switch" (Reconocimiento de Patrones)
 *
 * Variante de Lights Out con progresión de tamaño de matriz: 3 etapas por tamaño
 * (3×3 → 4×4 → 5×5 → 6×6, la 1ª de todas un tutorial de un solo toque) y, agotado
 * el 6×6, sigue subiendo la dificultad aumentando cuánto se desordena el tablero
 * (ver [GridSwitchStages]). Las etapas son **seleccionables** (pedido del
 * usuario) igual que Crucigrama/Sopa de Letras, no una secuencia forzosa.
 *
 * Decisiones clave:
 *  - **Dominio 100% puro**: nada de Compose ni colores. El acento visual (celda
 *    apagada = `SurfaceVariantDark`, encendida = neón con halo) vive en la capa de
 *    UI (FASE 3); aquí solo hay `Boolean` (encendida/apagada).
 *  - **`List<List<Boolean>>` y no `BooleanArray`**: un `BooleanArray` no tiene
 *    `equals`/`hashCode` estructural (dos arrays con el mismo contenido NO son
 *    iguales), lo que rompería la comparación de snapshots que necesita MVI para
 *    decidir si recomponer. `List` sí, y el proyecto ya prioriza `List` inmutable
 *    en las APIs públicas (§4 CLAUDE.md).
 *  - **Reutiliza [GridPosition]/[orthogonalNeighbors] de `game.grid`**: son el
 *    vocabulario común de rejilla del proyecto (ya lo usan Conectores y Línea
 *    Neón); reimplementar coordenadas o vecindad aquí duplicaría lógica ya
 *    probada y correcta para tableros cuadrados de cualquier lado.
 */

/** Lado de la cuadrícula en la primera etapa. */
const val GRID_SWITCH_MIN_SIZE: Int = 3

/** Lado máximo de la cuadrícula: a partir de aquí el tablero deja de crecer. */
const val GRID_SWITCH_MAX_SIZE: Int = 6

/**
 * Tablero de luces inmutable de lado [size]. `true` = luz ENCENDIDA (neón activo),
 * `false` = APAGADA.
 *
 * @property size lado de la cuadrícula cuadrada (3..[GRID_SWITCH_MAX_SIZE]).
 * @property cells matriz fila-mayor: `cells[row][col]`, tamaño `size × size`.
 */
@Serializable
data class LightGrid(
    val size: Int,
    val cells: List<List<Boolean>>,
) {

    /** Estado de la celda en [pos]; `false` (apagada) si la posición cae fuera. */
    fun cellAt(pos: GridPosition): Boolean =
        if (pos.isInside(size)) cells[pos.row][pos.col] else false

    /** Victoria: todas las luces apagadas. Consulta pura, la evalúa el motor (FASE 2). */
    val isSolved: Boolean
        get() = cells.all { row -> row.none { it } }

    /** Nº de luces encendidas. Útil para depurar/testear el nivel de desorden. */
    val litCount: Int
        get() = cells.sumOf { row -> row.count { it } }

    /**
     * Aplica la conmutación de [pos]: invierte esa celda y sus vecinas ortogonales
     * existentes ([orthogonalNeighbors] ya recorta las que caen fuera del tablero,
     * así que esquinas y bordes afectan solo a los vecinos que realmente tienen).
     *
     * Es una operación **pura** (devuelve un tablero nuevo) y **de dominio**, no
     * "solo del motor": tanto el generador ([GridSwitchGenerator], que desordena
     * aplicando toques) como el futuro motor (FASE 2, que la dispara desde
     * `ToggleCell`) necesitan exactamente el mismo cálculo, y mantenerlo en un
     * único sitio evita que ambos diverjan si mañana cambia la vecindad.
     *
     * @throws IllegalArgumentException si [pos] cae fuera del tablero.
     */
    fun toggled(pos: GridPosition): LightGrid {
        require(pos.isInside(size)) { "Posición $pos fuera de un tablero de lado $size" }
        val affected = (pos.orthogonalNeighbors(size) + pos).toSet()
        val touchedColsByRow = affected.groupBy({ it.row }) { it.col }
        val newCells = cells.mapIndexed { row, rowCells ->
            val touchedCols = touchedColsByRow[row]?.toSet() ?: return@mapIndexed rowCells
            rowCells.mapIndexed { col, lit -> if (col in touchedCols) !lit else lit }
        }
        return copy(cells = newCells)
    }

    companion object {
        /** Tablero resuelto (todas las luces apagadas) de lado [size]. Punto de partida del generador. */
        fun solved(size: Int): LightGrid = LightGrid(size, List(size) { List(size) { false } })
    }
}

/**
 * Progresión de tamaño de cuadrícula y de "desorden" (toques de scramble) por
 * etapa. Separado de [LightGrid] a propósito: aquí vive la CURVA de dificultad
 * (números concretos, ajustables sin tocar la mecánica de conmutación), mientras
 * que [LightGrid] solo sabe representar y conmutar un tablero de un tamaño dado.
 */
object GridSwitchStages {

    /**
     * Nº de etapas que se juegan en CADA tamaño antes de crecer al siguiente
     * (salvo la 1ª, ver [TUTORIAL_STAGE]): 3 etapas en 3×3, 3 en 4×4, 3 en 5×5 y,
     * agotada la rampa, el tope de [GRID_SWITCH_MAX_SIZE] en bucle. Antes cada
     * etapa crecía el tablero (curva 3→4→5→6 en solo 4 etapas); repartir 3 etapas
     * por tamaño deja tiempo a que el jugador aprenda cada escala antes de que
     * crezca (pedido del usuario: "progresión más lenta").
     */
    private const val LEVELS_PER_SIZE: Int = 3

    /**
     * Primera etapa del juego: un ÚNICO toque de desorden sobre el 3×3 más
     * pequeño. Pedido del usuario ("primer nivel estilo tutorial, solución en un
     * solo click"): con un solo toque de scramble, la solución es literalmente
     * tocar la misma celda que lo generó (ver KDoc de [GridSwitchGenerator]), así
     * que sirve de introducción a la mecánica sin plantear un puzzle real todavía.
     */
    private const val TUTORIAL_STAGE: Int = 1

    /**
     * Índice de tamaño máximo alcanzable (`GRID_SWITCH_MIN_SIZE + índice`, topado
     * en [GRID_SWITCH_MAX_SIZE]). Usado tanto por [gridSizeForStage] como por
     * [scrambleTouchesForStage] para saber cuándo el tablero ya no crece más.
     */
    private const val MAX_SIZE_INDEX: Int = GRID_SWITCH_MAX_SIZE - GRID_SWITCH_MIN_SIZE

    /**
     * Toques de scramble adicionales por cada etapa una vez el 6×6 agotó su propia
     * rampa (ver [scrambleTouchesForStage]). Mismo criterio que la curva original:
     * en un tablero de 36 celdas, 6 toques extra por etapa es un incremento
     * perceptible pero gradual.
     */
    private const val EXTRA_TOUCHES_PER_STAGE: Int = 6

    /**
     * Lado de la cuadrícula para la etapa [stage] (1-based): [LEVELS_PER_SIZE]
     * etapas en 3×3, otras tantas en 4×4, otras tantas en 5×5 y, de ahí en
     * adelante, fijo en [GRID_SWITCH_MAX_SIZE] (pedido del usuario: "y así hasta
     * el tope").
     */
    fun gridSizeForStage(stage: Int): Int = GRID_SWITCH_MIN_SIZE + sizeIndexForStage(stage)

    /**
     * Nº de toques aleatorios que aplica [GridSwitchGenerator] para desordenar el
     * tablero de la etapa [stage].
     *
     * La 1ª etapa es un caso especial: 1 solo toque, el tutorial (ver
     * [TUTORIAL_STAGE]). El resto sigue una RAMPA dentro de cada tamaño: la
     * primera etapa de un tamaño nuevo desordena poco (`size` toques, un
     * calentamiento suave tras el salto de tablero) y la última de ese tamaño
     * llega al scramble "completo" (`size²` toques, aprox. un toque por celda —
     * el criterio de la curva original). Agotada esa rampa en el tamaño tope, la
     * dificultad sigue subiendo indefinidamente con [EXTRA_TOUCHES_PER_STAGE] por
     * etapa (mismo mecanismo que la curva original, solo que desplazado a partir
     * de la etapa en que el 6×6 ya llegó a `size²`).
     */
    fun scrambleTouchesForStage(stage: Int): Int {
        if (stage <= TUTORIAL_STAGE) return 1
        val size = gridSizeForStage(stage)
        val sizeIndex = sizeIndexForStage(stage)
        // Primera etapa del tamaño actual: 1-based, así que sizeIndex=0 arranca en
        // la etapa 1 (el tutorial) aunque este cálculo no se use para ella.
        val tierStartStage = sizeIndex * LEVELS_PER_SIZE + 1
        val posInTier = stage - tierStartStage // 0-based; solo puede ≥ LEVELS_PER_SIZE en el tamaño tope
        return if (posInTier < LEVELS_PER_SIZE) {
            rampTouches(size, posInTier)
        } else {
            val extraStages = posInTier - (LEVELS_PER_SIZE - 1)
            size * size + extraStages * EXTRA_TOUCHES_PER_STAGE
        }
    }

    /**
     * Índice de tamaño (0 = [GRID_SWITCH_MIN_SIZE]) de la etapa [stage]: agrupa
     * cada [LEVELS_PER_SIZE] etapas en un mismo tamaño y se queda fijo en
     * [MAX_SIZE_INDEX] de ahí en adelante — así TODAS las etapas del tablero tope
     * (10, 11, 12, 13…) caen en el mismo "tier", que es justo lo que necesita
     * [scrambleTouchesForStage] para seguir sumando dificultad sin volver a
     * arrancar la rampa desde `size` en cada bloque de [LEVELS_PER_SIZE].
     */
    private fun sizeIndexForStage(stage: Int): Int =
        ((stage - 1) / LEVELS_PER_SIZE).coerceAtMost(MAX_SIZE_INDEX)

    /**
     * Rampa lineal de `size` (la etapa más suave de este tamaño, [pos] = 0) a
     * `size²` (scramble completo, [pos] = [LEVELS_PER_SIZE] - 1).
     */
    private fun rampTouches(size: Int, pos: Int): Int {
        val min = size
        val max = size * size
        return min + (max - min) * pos / (LEVELS_PER_SIZE - 1)
    }
}
