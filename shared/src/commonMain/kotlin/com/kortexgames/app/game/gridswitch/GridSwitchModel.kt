package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighbors
import kotlinx.serialization.Serializable

/**
 * # Modelos de dominio de "Neon Grid Switch" (Reconocimiento de Patrones)
 *
 * Variante de Lights Out con progresión de tamaño de matriz: cada etapa crece la
 * cuadrícula (3×3 → 4×4 → 5×5 → 6×6) y, a partir de la 6×6, sigue subiendo la
 * dificultad aumentando cuánto se desordena el tablero (ver [GridSwitchStages]).
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
     * Etapa (1-based) a partir de la cual el tablero deja de crecer y queda fijo
     * en [GRID_SWITCH_MAX_SIZE]. Coincide con "Nivel 4+" del diseño: desde aquí la
     * dificultad ya no viene de un tablero más grande, sino de más toques de
     * scramble (ver [scrambleTouchesForStage]).
     */
    const val SIZE_CAP_STAGE: Int = 4

    /**
     * Toques de scramble adicionales por cada etapa por encima de [SIZE_CAP_STAGE].
     * En un tablero 6×6 (36 celdas) 6 toques extra por etapa es un incremento
     * perceptible pero gradual: la etapa 8 (4 etapas después del tope) parte de un
     * tablero notablemente más revuelto que la 4 sin que un único salto de
     * dificultad se sienta injusto.
     */
    private const val EXTRA_TOUCHES_PER_STAGE: Int = 6

    /**
     * Lado de la cuadrícula para la etapa [stage] (1-based): 3×3 → 4×4 → 5×5 y, de
     * ahí en adelante, fijo en [GRID_SWITCH_MAX_SIZE].
     */
    fun gridSizeForStage(stage: Int): Int = when {
        stage <= 1 -> GRID_SWITCH_MIN_SIZE
        stage == 2 -> 4
        stage == 3 -> 5
        else -> GRID_SWITCH_MAX_SIZE
    }

    /**
     * Nº de toques aleatorios que aplica [GridSwitchGenerator] para desordenar el
     * tablero de la etapa [stage].
     *
     * Base = `size²` (aprox. un toque por celda: suficiente para que el patrón de
     * luces resultante no se parezca al tablero resuelto, sin exigir decenas de
     * jugadas para deshacerlo). Por encima de [SIZE_CAP_STAGE], donde el tamaño ya
     * no crece, se suma [EXTRA_TOUCHES_PER_STAGE] por cada etapa extra para que la
     * dificultad siga subiendo (mandato del diseño: "Nivel 4+ ... con mayor número
     * de iteraciones de desorden").
     */
    fun scrambleTouchesForStage(stage: Int): Int {
        val size = gridSizeForStage(stage)
        val baseTouches = size * size
        val extraStages = (stage - SIZE_CAP_STAGE).coerceAtLeast(0)
        return baseTouches + extraStages * EXTRA_TOUCHES_PER_STAGE
    }
}
