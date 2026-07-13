package com.example.kortexgames.game.blockgrid

/**
 * # Modelos de dominio de "Neon Block Grid" (Visión Espacial)
 *
 * Mecánica clásica de Block Puzzle: tablero 8×8, mano de 3 poliominós que se
 * arrastran al tablero, y limpieza simultánea de filas/columnas completas.
 *
 * Decisiones clave:
 *  - **Dominio 100% puro**: nada de Compose ni colores concretos. El acento
 *    visual de cada pieza se modela con [BlockAccent] (semántico) y la UI lo
 *    mapea a `LogicColors`/`CategoryPalette` en la capa de presentación.
 *  - **Inmutabilidad**: cada jugada produce un tablero nuevo ([BoardGrid] es
 *    una `data class` sobre listas inmutables), igual que el resto de motores
 *    del proyecto. Copiar 64 celdas por jugada es despreciable frente a la
 *    seguridad que da para MVI (snapshots comparables, sin aliasing).
 *  - **Sin rotación**: como en el Block Puzzle clásico, las piezas caen con
 *    orientación fija; la variedad se logra incluyendo cada rotación como
 *    forma independiente en [PolyominoShape].
 */

/** Lado del tablero cuadrado. 8×8 es el estándar del género. */
const val BOARD_SIZE: Int = 8

/** Piezas simultáneas en la mano del jugador. */
const val HAND_SIZE: Int = 3

/**
 * Coordenada de celda del tablero: fila y columna en `0 until BOARD_SIZE`.
 *
 * Se usa (row, col) y no (x, y) para evitar la ambigüedad clásica de qué eje
 * es cuál: `row` crece hacia abajo y `col` hacia la derecha, como se dibuja.
 */
data class GridPos(val row: Int, val col: Int) {

    /** ¿La posición cae dentro del tablero? */
    val isInsideBoard: Boolean
        get() = row in 0 until BOARD_SIZE && col in 0 until BOARD_SIZE
}

/**
 * Desplazamiento relativo de un bloque respecto al origen (celda superior
 * izquierda) de su pieza. Separado de [GridPos] a propósito: un offset no es
 * una celda del tablero y mezclarlos invita a errores de traducción.
 */
data class GridOffset(val dRow: Int, val dCol: Int)

/**
 * Acento neón de un bloque, en términos semánticos de la marca.
 *
 * El dominio NO conoce hex ni `Color`: la UI traduce cada valor a su token de
 * `LogicColors` (CYAN → `NeonCyan`, GREEN → `NeonGreen`, VIOLET → `Violet`,
 * MAGENTA → `Magenta`, CORAL → `Coral`, BLUE → `Blue`, AMBER → `Amber`).
 * Así el tema puede evolucionar sin tocar lógica ni datos guardados.
 */
enum class BlockAccent {
    CYAN,
    GREEN,
    VIOLET,
    MAGENTA,
    CORAL,
    BLUE,
    AMBER,
}

/**
 * Catálogo cerrado de formas de poliominó.
 *
 * Cada forma es el conjunto de offsets de sus bloques respecto al origen,
 * normalizados para que `dRow >= 0`, `dCol >= 0` y exista al menos un bloque
 * en la fila 0 y otro en la columna 0 (así el origen coincide con la esquina
 * del *bounding box* y la matemática de anclaje es trivial).
 *
 * Las rotaciones se listan como formas independientes (no hay rotación en
 * partida), lo que además permite ponderar la dificultad: el generador puede
 * elegir con qué frecuencia salen las piezas grandes.
 */
enum class PolyominoShape(val cells: Set<GridOffset>) {

    /** Monominó 1×1: la pieza "salvavidas" que siempre cabe en cualquier hueco. */
    DOT(setOf(GridOffset(0, 0))),

    // --- Líneas horizontales ---
    LINE2_H(lineH(2)),
    LINE3_H(lineH(3)),
    LINE4_H(lineH(4)),
    LINE5_H(lineH(5)),

    // --- Líneas verticales ---
    LINE2_V(lineV(2)),
    LINE3_V(lineV(3)),
    LINE4_V(lineV(4)),
    LINE5_V(lineV(5)),

    // --- Cuadrados ---
    SQUARE_2(square(2)),
    SQUARE_3(square(3)),

    // --- Tromino L (esquina de 3 bloques), 4 rotaciones ---
    CORNER_NW(setOf(GridOffset(0, 0), GridOffset(0, 1), GridOffset(1, 0))),
    CORNER_NE(setOf(GridOffset(0, 0), GridOffset(0, 1), GridOffset(1, 1))),
    CORNER_SW(setOf(GridOffset(0, 0), GridOffset(1, 0), GridOffset(1, 1))),
    CORNER_SE(setOf(GridOffset(0, 1), GridOffset(1, 0), GridOffset(1, 1))),

    // --- Tetromino L (3 alto × 2 ancho), 4 rotaciones ---
    L_UP(setOf(GridOffset(0, 0), GridOffset(1, 0), GridOffset(2, 0), GridOffset(2, 1))),
    L_RIGHT(setOf(GridOffset(0, 0), GridOffset(0, 1), GridOffset(0, 2), GridOffset(1, 0))),
    L_DOWN(setOf(GridOffset(0, 0), GridOffset(0, 1), GridOffset(1, 1), GridOffset(2, 1))),
    L_LEFT(setOf(GridOffset(0, 2), GridOffset(1, 0), GridOffset(1, 1), GridOffset(1, 2))),

    // --- Tetromino T, 4 rotaciones ---
    T_UP(setOf(GridOffset(0, 1), GridOffset(1, 0), GridOffset(1, 1), GridOffset(1, 2))),
    T_RIGHT(setOf(GridOffset(0, 0), GridOffset(1, 0), GridOffset(1, 1), GridOffset(2, 0))),
    T_DOWN(setOf(GridOffset(0, 0), GridOffset(0, 1), GridOffset(0, 2), GridOffset(1, 1))),
    T_LEFT(setOf(GridOffset(0, 1), GridOffset(1, 0), GridOffset(1, 1), GridOffset(2, 1))),
    ;

    /** Bloques de la pieza (tamaño = puntos base al colocarla). */
    val blockCount: Int get() = cells.size

    /** Ancho del *bounding box* en columnas (para centrar la pieza en la mano). */
    val width: Int get() = cells.maxOf { it.dCol } + 1

    /** Alto del *bounding box* en filas. */
    val height: Int get() = cells.maxOf { it.dRow } + 1

    /**
     * Celdas absolutas del tablero que ocuparía la pieza anclada en [origin]
     * (origen = esquina superior izquierda del bounding box). No valida
     * límites ni colisiones: eso es responsabilidad del tablero/motor.
     */
    fun absoluteCells(origin: GridPos): Set<GridPos> =
        cells.mapTo(mutableSetOf()) { GridPos(origin.row + it.dRow, origin.col + it.dCol) }
}

// Constructores de formas a nivel de archivo (no en el companion del enum: las
// entradas se inicializan ANTES que el companion y no pueden invocarlo).

/** Línea horizontal de [n] bloques. */
private fun lineH(n: Int): Set<GridOffset> = (0 until n).mapTo(mutableSetOf()) { GridOffset(0, it) }

/** Línea vertical de [n] bloques. */
private fun lineV(n: Int): Set<GridOffset> = (0 until n).mapTo(mutableSetOf()) { GridOffset(it, 0) }

/** Cuadrado macizo de [side]×[side]. */
private fun square(side: Int): Set<GridOffset> = buildSet {
    for (r in 0 until side) for (c in 0 until side) add(GridOffset(r, c))
}

/**
 * Pieza concreta en la mano del jugador.
 *
 * @property id identificador único dentro de la partida. Necesario porque la
 *           mano puede contener dos piezas de la misma forma y la UI/gestos
 *           deben distinguirlas (el drag arrastra un `id`, no una forma).
 * @property shape forma geométrica de la pieza.
 * @property accent acento neón con el que se pinta (fijo desde que se genera,
 *           para que la pieza no "cambie de color" al colocarse).
 */
data class Polyomino(
    val id: Int,
    val shape: PolyominoShape,
    val accent: BlockAccent,
)

/**
 * Contenido de una celda del tablero. Sealed (y no un nullable simple) porque
 * una celda tiene tres estados con semántica distinta para la UI:
 * vacía, ocupada, y **ocupada pero en animación de limpieza** — el dominio ya
 * decidió romper la línea pero la UI aún está animando fade+shrink, así que la
 * celda no debe aceptar piezas ni contar para nuevas líneas.
 */
sealed interface BoardCell {

    /** Celda libre: acepta bloques. */
    data object Empty : BoardCell

    /** Celda ocupada por un bloque asentado del acento indicado. */
    data class Filled(val accent: BlockAccent) : BoardCell

    /**
     * Bloque de una línea recién completada, esperando a que la UI termine su
     * animación de desaparición (ver `LineClearFinished` en el contrato). A
     * efectos de reglas cuenta como **vacía** para colocar piezas: así el
     * jugador puede encadenar jugadas sin esperar a la animación.
     */
    data class Clearing(val accent: BlockAccent) : BoardCell
}

/**
 * Tablero inmutable de [BOARD_SIZE]×[BOARD_SIZE].
 *
 * Solo ofrece consultas geométricas puras (¿cabe?, ¿dónde?); las mutaciones
 * (colocar, detectar y limpiar líneas) viven en el motor (Fase 2) para que el
 * modelo no acumule reglas de juego.
 *
 * @property cells matriz fila-mayor: `cells[row][col]`.
 */
data class BoardGrid(
    val cells: List<List<BoardCell>> = List(BOARD_SIZE) { List(BOARD_SIZE) { BoardCell.Empty } },
) {

    /** Celda en [pos]; [BoardCell.Empty] si la posición cae fuera (consulta segura). */
    fun cellAt(pos: GridPos): BoardCell =
        if (pos.isInsideBoard) cells[pos.row][pos.col] else BoardCell.Empty

    /**
     * ¿[pos] puede recibir un bloque? Dentro del tablero y no [BoardCell.Filled].
     * Las celdas [BoardCell.Clearing] SÍ aceptan: el dominio ya las dio por
     * rotas aunque la UI siga animándolas (ver KDoc de [BoardCell.Clearing]).
     */
    fun isFree(pos: GridPos): Boolean =
        pos.isInsideBoard && cellAt(pos) !is BoardCell.Filled

    /**
     * ¿La pieza [shape] cabe anclada en [origin]? Todas sus celdas absolutas
     * deben estar dentro del tablero y libres.
     */
    fun canPlace(shape: PolyominoShape, origin: GridPos): Boolean =
        shape.absoluteCells(origin).all(::isFree)

    /**
     * ¿Existe *algún* anclaje válido para [shape] en el tablero?
     *
     * Es la primitiva de la detección de Game Over (Fase 2): fuerza bruta
     * sobre los ≤ 64 orígenes posibles × ≤ 9 bloques por pieza × 3 piezas de
     * la mano ≈ 1.700 comprobaciones en el peor caso — trivial para ejecutarse
     * tras cada jugada incluso en el hilo principal, por lo que no merece
     * estructuras incrementales (bitboards, listas de huecos) que complicarían
     * el código sin beneficio medible en un 8×8.
     */
    fun canPlaceAnywhere(shape: PolyominoShape): Boolean {
        // Se poda el rango de orígenes con el bounding box: una pieza de 5 de
        // ancho nunca puede anclarse en la columna 4 o posterior.
        for (row in 0..BOARD_SIZE - shape.height) {
            for (col in 0..BOARD_SIZE - shape.width) {
                if (canPlace(shape, GridPos(row, col))) return true
            }
        }
        return false
    }
}
