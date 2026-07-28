package com.example.kortexgames.game.neonsudoku

import kotlinx.serialization.Serializable

/**
 * # Neon Sudoku Matrix — modelos de dominio (FASE 1)
 *
 * Minijuego de la categoría **Pensamiento Lógico**: Sudoku clásico 9x9 con panel
 * holográfico neón. El jugador rellena celdas vacías respetando la regla clásica
 * (cada fila, columna y bloque 3x3 contiene los dígitos 1-9 sin repetir).
 *
 * Este archivo contiene únicamente **dominio puro**: sin dependencias de Compose
 * ni de ninguna API de plataforma, para que viva con naturalidad en `commonMain`
 * y sea testeable de forma aislada. La generación aleatoria de tableros con
 * solución única queda fuera de alcance: [Board.fromTemplate] asume que el string
 * de 81 caracteres ya viene resuelto/validado desde el repositorio de plantillas.
 * El motor de juego (input, notas, detección de choques) se implementa en FASE 2.
 *
 * ## Sistema de coordenadas
 * El tablero se indexa por [CellPosition] (`row`, `col`) en `0..8`, fila arriba a
 * abajo y columna izquierda a derecha — NO por índice plano — para que el resto
 * del dominio (selección, resaltado de fila/columna/bloque) razone en términos
 * humanos de Sudoku en lugar de aritmética de índices repetida en cada sitio.
 */

/**
 * Coordenada de una celda dentro del tablero 9x9.
 *
 * @property row fila, `0..8` (de arriba a abajo).
 * @property col columna, `0..8` (de izquierda a derecha).
 */
@Serializable
data class CellPosition(val row: Int, val col: Int) {
    init {
        require(row in 0 until NeonSudokuConfig.BOARD_SIZE) { "row fuera de rango: $row" }
        require(col in 0 until NeonSudokuConfig.BOARD_SIZE) { "col fuera de rango: $col" }
    }

    /**
     * Índice del bloque 3x3 al que pertenece la celda, `0..8` (izquierda a
     * derecha, arriba a abajo). Es la clave que usa el motor de validación
     * (FASE 2) para agrupar las celdas de un mismo cuadrante sin recorrer todo
     * el tablero.
     */
    val blockIndex: Int
        get() = (row / NeonSudokuConfig.BLOCK_SIZE) * NeonSudokuConfig.BLOCK_SIZE +
            (col / NeonSudokuConfig.BLOCK_SIZE)
}

/**
 * Una celda del tablero.
 *
 * Es un `data class` inmutable: el motor (FASE 2) no muta celdas in situ, sino
 * que reemplaza la celda afectada dentro de un nuevo [Board] (patrón de estado
 * inmutable de MVI, igual que el resto de juegos del proyecto).
 *
 * @property position coordenada estable de la celda dentro del tablero.
 * @property value dígito `1..9` escrito (pista o jugador); `null` si está vacía.
 * @property isFixed `true` si es una pista inicial de la plantilla: inmutable,
 *   el jugador no puede seleccionarla como destino de escritura/borrado.
 * @property notes marcas de lápiz (`1..9`, sin duplicados por ser [Set]) que el
 *   jugador anota como hipótesis. Solo tiene sentido visual cuando [value] es
 *   `null`; se conserva aunque [value] no sea null por simplicidad de estado
 *   (el motor las descarta al escribir un valor definitivo, FASE 2).
 * @property hasConflict `true` cuando [value] no coincide con el dígito de la
 *   solución del puzzle en esta posición ([SudokuPuzzle.solution]). Pese al
 *   nombre (heredado de una primera versión que detectaba duplicados por fila/
 *   columna/bloque), la corrección de una celda es hoy una propiedad **propia**:
 *   se compara solo contra la solución, nunca contra las demás celdas escritas.
 *   Esto es deliberado (ver KDoc de [NeonSudokuViewModel]): con la regla clásica
 *   de duplicados, un dígito erróneo podía no marcarse hasta que el jugador
 *   completaba correctamente el resto del grupo, obligándolo a deshacer partidas
 *   enteras para encontrar el fallo real. Comparar contra la solución da
 *   feedback inmediato en la propia celda, sin re-resolver el tablero en el
 *   cliente (la solución ya viene calculada del banco offline).
 */
@Serializable
data class SudokuCell(
    val position: CellPosition,
    val value: Int? = null,
    val isFixed: Boolean = false,
    val notes: Set<Int> = emptySet(),
    val hasConflict: Boolean = false,
) {
    /** `true` si la celda no tiene ningún dígito escrito todavía. */
    val isEmpty: Boolean get() = value == null

    /** `true` si hay notas de lápiz visibles (solo tiene sentido si [isEmpty]). */
    val hasNotes: Boolean get() = value == null && notes.isNotEmpty()
}

/**
 * Tablero de Sudoku 9x9 (81 celdas), inmutable.
 *
 * Se almacena como lista plana indexada por `row * 9 + col` en lugar de una
 * matriz `Array<Array<>>` para que el tipo sea un `data class` con `equals`/
 * `copy` estructural gratis (los arrays de Kotlin comparan por referencia) y así
 * encaje sin fricción en el patrón de estado inmutable de MVI.
 *
 * Las funciones [cellsInRow]/[cellsInColumn]/[cellsInBlock] son las primitivas
 * de agrupación que el validador de FASE 2 usará para detectar choques: sobre
 * cada grupo de 9 celdas basta construir un mapa `valor -> nº de apariciones`
 * (`O(9)` por grupo, `O(1)` amortizado por consulta de valor) en vez de comparar
 * la celda contra las otras 80 una a una (`O(N^2)` sobre todo el tablero). El
 * detalle de esa construcción vive en el KDoc del motor (FASE 2).
 */
data class Board(private val cells: List<SudokuCell>) {

    init {
        require(cells.size == NeonSudokuConfig.CELL_COUNT) {
            "Board requiere ${NeonSudokuConfig.CELL_COUNT} celdas, recibidas: ${cells.size}"
        }
    }

    /** Todas las celdas en orden plano `row * 9 + col`. Accesor público de solo
     *  lectura (el campo del constructor es privado) para poder **serializar** el
     *  tablero en el guardado de partida (FASE 4) sin exponer la lista mutable. */
    val allCells: List<SudokuCell> get() = cells

    /** Celda en la coordenada `(row, col)`. */
    fun cellAt(row: Int, col: Int): SudokuCell = cells[row * NeonSudokuConfig.BOARD_SIZE + col]

    /** Celda en [position]. */
    fun cellAt(position: CellPosition): SudokuCell = cellAt(position.row, position.col)

    /** Las 9 celdas de la fila [row], en orden de columna. */
    fun cellsInRow(row: Int): List<SudokuCell> =
        (0 until NeonSudokuConfig.BOARD_SIZE).map { col -> cellAt(row, col) }

    /** Las 9 celdas de la columna [col], en orden de fila. */
    fun cellsInColumn(col: Int): List<SudokuCell> =
        (0 until NeonSudokuConfig.BOARD_SIZE).map { row -> cellAt(row, col) }

    /** Las 9 celdas del bloque 3x3 [blockIndex] (ver [CellPosition.blockIndex]). */
    fun cellsInBlock(blockIndex: Int): List<SudokuCell> {
        val blockRow = (blockIndex / NeonSudokuConfig.BLOCK_SIZE) * NeonSudokuConfig.BLOCK_SIZE
        val blockCol = (blockIndex % NeonSudokuConfig.BLOCK_SIZE) * NeonSudokuConfig.BLOCK_SIZE
        return (0 until NeonSudokuConfig.BLOCK_SIZE).flatMap { dRow ->
            (0 until NeonSudokuConfig.BLOCK_SIZE).map { dCol ->
                cellAt(blockRow + dRow, blockCol + dCol)
            }
        }
    }

    /**
     * Todas las celdas rellenas (fijas o de jugador) que contienen [value].
     * Es la fuente del resaltado "todas las celdas con este número" (CLAUDE.md
     * requisito de UX): la UI (FASE 3) la usa directamente, sin duplicar el
     * criterio de coincidencia en la capa de presentación.
     */
    fun cellsWithValue(value: Int): List<SudokuCell> = cells.filter { it.value == value }

    /** `true` cuando las 81 celdas tienen un valor (fin de partida potencial;
     *  la validez matemática la confirma el motor de FASE 2 vía [hasAnyConflict]). */
    val isComplete: Boolean get() = cells.none { it.isEmpty }

    /** Nº de celdas con un dígito escrito (pistas incluidas). Alimenta el
     *  indicador de progreso del HUD (FASE 3). */
    val filledCount: Int get() = cells.count { !it.isEmpty }

    /** `true` si alguna celda escrita no coincide con la solución (ver [SudokuCell.hasConflict]). */
    val hasAnyConflict: Boolean get() = cells.any { it.hasConflict }

    /** Nuevo [Board] con la celda en [position] reemplazada por [cell]. Preserva
     *  la inmutabilidad: el motor (FASE 2) nunca muta [cells] in situ. */
    fun replacing(position: CellPosition, cell: SudokuCell): Board {
        val index = position.row * NeonSudokuConfig.BOARD_SIZE + position.col
        return Board(cells.toMutableList().apply { this[index] = cell })
    }

    companion object {
        /** Tablero vacío (81 celdas sin pistas), útil como estado inicial antes
         *  de que el ViewModel (FASE 2) cargue una plantilla real. */
        fun empty(): Board = Board(
            (0 until NeonSudokuConfig.BOARD_SIZE).flatMap { row ->
                (0 until NeonSudokuConfig.BOARD_SIZE).map { col ->
                    SudokuCell(position = CellPosition(row, col))
                }
            },
        )

        /**
         * Construye un [Board] a partir de una plantilla de 81 caracteres (fila a
         * fila, izquierda a derecha): dígitos `1`-`9` son pistas fijas, `0` o `.`
         * son celdas vacías.
         *
         * @throws IllegalArgumentException si [template] no mide 81 caracteres o
         *   contiene un carácter fuera de `[0-9.]`.
         */
        fun fromTemplate(template: String): Board {
            require(template.length == NeonSudokuConfig.CELL_COUNT) {
                "La plantilla debe tener ${NeonSudokuConfig.CELL_COUNT} caracteres, tiene: ${template.length}"
            }
            val cells = template.mapIndexed { index, char ->
                val row = index / NeonSudokuConfig.BOARD_SIZE
                val col = index % NeonSudokuConfig.BOARD_SIZE
                val position = CellPosition(row, col)
                when (char) {
                    '0', '.' -> SudokuCell(position = position)
                    in '1'..'9' -> SudokuCell(position = position, value = char.digitToInt(), isFixed = true)
                    else -> throw IllegalArgumentException("Carácter de plantilla inválido: '$char' en índice $index")
                }
            }
            return Board(cells)
        }
    }
}

/**
 * Constantes de diseño y balance de Neon Sudoku Matrix.
 *
 * Se centralizan aquí (una sola fuente de verdad) para que la geometría del
 * tablero no quede repetida como literales mágicos en el modelo, el motor
 * (FASE 2) y el `Canvas` (FASE 3).
 */
object NeonSudokuConfig {
    /** Lado del tablero: 9x9 celdas. */
    const val BOARD_SIZE = 9

    /** Lado de un bloque: 3x3 celdas. */
    const val BLOCK_SIZE = 3

    /** Total de celdas del tablero (81). */
    const val CELL_COUNT = BOARD_SIZE * BOARD_SIZE

    /** Dígito mínimo válido. */
    const val MIN_DIGIT = 1

    /** Dígito máximo válido. */
    const val MAX_DIGIT = 9

    /** Puntuación base de una partida completada sin penalizaciones. */
    const val BASE_SCORE = 1_000

    /** Puntos que resta cada choque cometido (ver [SudokuCell.hasConflict]). */
    const val ERROR_SCORE_PENALTY = 25

    /** Puntos que resta cada segundo transcurrido: recompensa terminar rápido
     *  sin convertir el Sudoku en un juego contrarreloj (la partida NUNCA
     *  termina por agotar tiempo, solo penaliza el puntaje final). */
    const val TIME_SCORE_PENALTY_PER_SEC = 1

    /** Número de choques permitidos antes de perder la partida (regla "3 strikes":
     *  al cometer el 3.º error el jugador pierde, salvo que reviva viendo un
     *  anuncio). Se centraliza aquí para poder ajustar la severidad del juego. */
    const val MAX_ERRORS = 3

    /** Choques que "devuelve" un revivir con anuncio: tras revivir, el contador se
     *  reajusta para dejar exactamente estos errores de margen antes de volver a
     *  perder. `1` = una segunda oportunidad ajustada (mismo espíritu que la vida
     *  extra de "Burbujas de Cálculo"), no un reinicio del contador. */
    const val REVIVE_ERROR_GRANT = 1
}

/**
 * Niveles de dificultad de Neon Sudoku Matrix (taxonomía pura).
 *
 * ## Cómo se controla la dificultad
 * Los puzzles se generan y **ratean offline** ([SudokuPuzzleRepository], banco en
 * `tools/sudoku/generate_bank.py`). La calibración empírica mostró que, con
 * generación aleatoria, la dificultad "por técnica lógica" es casi bimodal —o el
 * puzzle cae con *singles*, o exige técnicas avanzadas—, así que el rateo combina
 * dos ejes:
 *  - FACIL/MEDIO/DIFICIL se diferencian por **número de pistas** (más pistas ⇒
 *    más fácil), la palanca que el jugador percibe, y son resolubles con lógica
 *    humana sin adivinar.
 *  - EXPERTO se reserva para el **salto real de técnica**: puzzles que exigen
 *    razonamiento por encima de pares/triples/X-Wing.
 *
 * Antes las plantillas vivían embebidas aquí (una `List<String>` por nivel); se
 * sacaron al repositorio para poder ampliar el banco (y servirlo desde la nube)
 * sin tocar el dominio. El `enum` se queda solo como el conjunto cerrado de tiers
 * que recorre el selector de la antesala (mismo patrón que el tamaño de tablero
 * de Neon Grid 2048) y que mapea a `difficultyLevel` en el resultado.
 *
 * @property displayName etiqueta visible en el selector.
 */
enum class SudokuDifficulty(val displayName: String) {
    FACIL("Fácil"),
    MEDIO("Medio"),
    DIFICIL("Difícil"),
    EXPERTO("Experto"),
}
