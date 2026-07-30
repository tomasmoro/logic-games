package com.kortexgames.app.game.defuser

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/**
 * # Neon Defuser — modelos de dominio (FASE 1)
 *
 * Minijuego de la categoría **Atención y Concentración**: Buscaminas clásico sobre
 * un panel rectangular de celdas cuadradas con estética de consola de
 * desactivación. El jugador revela celdas seguras y marca con un "escudo" las que
 * cree minadas, deduciendo a partir de los números de peligro.
 *
 * Este archivo contiene únicamente **dominio puro**: sin dependencias de Compose
 * ni de ninguna API de plataforma, para que viva con naturalidad en `commonMain`
 * y sea testeable de forma aislada. La colocación de minas (garantizando que el
 * primer toque nunca sea mina), el conteo de vecinos y el *flood fill* de la
 * cascada se implementan en el motor de **FASE 2**; el renderizado del panel en
 * `Canvas`, en **FASE 3**.
 *
 * ## Sistema de coordenadas y vecindad
 * El panel se indexa por [CellPosition] `(row, col)` —fila de arriba abajo,
 * columna de izquierda a derecha— en lugar de por índice plano, para que el resto
 * del dominio razone en términos humanos de tablero en vez de repetir aritmética
 * de índices en cada sitio.
 *
 * Cada celda tiene **hasta 8 vecinas**: las 4 ortogonales más las 4 diagonales
 * ([CellPosition.NEIGHBOR_OFFSETS]). Las celdas de borde y esquina tienen menos,
 * y eso lo resuelve el propio tablero al filtrar por límites ([MineBoard.neighborsOf]),
 * no cada llamador. Ese 8 es el número que hace que la escala de peligro llegue
 * hasta [DefuserConfig.MAX_ADJACENT].
 */

/**
 * Coordenada de una celda dentro del panel.
 *
 * Es `@Serializable` para poder persistir la partida en curso (guardado de
 * partida) sin exponer detalles de plataforma.
 *
 * @property row fila, de arriba abajo (`0` = primera fila).
 * @property col columna, de izquierda a derecha (`0` = primera columna).
 */
@Serializable
data class CellPosition(val row: Int, val col: Int) {

    /**
     * Las 8 celdas contiguas en el plano infinito, **sin filtrar** por los límites
     * del panel. El motor (FASE 2) las intersecta con las celdas reales vía
     * [MineBoard.neighborsOf]; el filtrado se hace allí para que esta función
     * quede puramente geométrica y reutilizable (conteo, cascada, tests).
     */
    fun neighbors(): List<CellPosition> =
        NEIGHBOR_OFFSETS.map { (dRow, dCol) -> CellPosition(row + dRow, col + dCol) }

    /**
     * Distancia de **Chebyshev** (número de "anillos" de separación) hasta [other]:
     * el máximo de las diferencias en fila y columna. Es la métrica correcta aquí
     * porque el movimiento incluye diagonales —exactamente la misma vecindad de 8
     * que usa el juego—, así que un "anillo" de distancia 1 es justo el conjunto de
     * las 8 vecinas.
     *
     * La usa la UI (FASE 3) para **escalonar la cascada**: las celdas más lejanas
     * del toque se revelan un poco más tarde, creando la onda expansiva de luz.
     * Vive en el dominio porque es geometría pura, no presentación.
     */
    fun distanceTo(other: CellPosition): Int =
        max(abs(row - other.row), abs(col - other.col))

    companion object {
        /**
         * Los 8 desplazamientos a las celdas contiguas: 4 ortogonales + 4
         * diagonales. Se excluye `(0, 0)` porque una celda no es vecina de sí misma.
         * El motor (FASE 2) suma estos offsets para contar minas adyacentes y
         * propagar la cascada.
         */
        val NEIGHBOR_OFFSETS: List<Pair<Int, Int>> = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -1, /* celda */ 0 to 1,
            1 to -1, 1 to 0, 1 to 1,
        )
    }
}

/**
 * Estado de interacción visible de una celda (lo que el jugador ve).
 *
 * Es ortogonal al **contenido** de la celda ([MineCell.hasMine] /
 * [MineCell.adjacentMines]): una celda `HIDDEN` puede ocultar una mina o un
 * número; el jugador no lo sabe hasta revelarla. Se modela como `enum` (dominio
 * cerrado) para que la UI use un `when` exhaustivo y no queden estados "colados"
 * como strings sueltos.
 */
enum class MineCellState {
    /** Oculta: aún no revelada ni marcada. Estado inicial de todas las celdas. */
    HIDDEN,

    /** Revelada: muestra su número de minas adyacentes (o el hueco si es 0). */
    REVEALED,

    /** Marcada con un "escudo" (bandera): el jugador cree que oculta una mina. */
    FLAGGED,
}

/**
 * Una celda del panel, inmutable.
 *
 * Como en el resto de juegos del proyecto, el motor (FASE 2) **no muta** celdas
 * in situ: reemplaza la celda afectada dentro de un nuevo [MineBoard] (patrón de
 * estado inmutable de MVI).
 *
 * @property position coordenada estable dentro del panel.
 * @property hasMine `true` si oculta una mina. Se rellena al colocar las minas en
 *   el primer toque (FASE 2); en el panel recién creado siempre es `false`.
 * @property adjacentMines número de minas en las celdas vecinas (`0..8`). Solo es
 *   significativo cuando [hasMine] es `false`; el motor lo calcula al generar el
 *   campo. `0` desencadena la cascada al revelar.
 * @property state estado de interacción visible (ver [MineCellState]).
 * @property isDetonated `true` únicamente en la mina que el jugador pisó y que
 *   provocó el Game Over (o que provocó la oferta de revivir). Distingue "la
 *   explosión" (halo expansivo rojo, FASE 3) del resto de minas que se revelan al
 *   perder. Solo puede ser `true` si [hasMine] lo es, y solo mientras la mina
 *   sigue sin desactivar: [onRevive][com.kortexgames.app.game.defuser.DefuserViewModel]
 *   lo vuelve a `false` a la vez que marca [isDefused].
 * @property isDefused `true` en la mina que el jugador pisó y que quedó
 *   **neutralizada** al aceptar revivir (ver "vida extra" en [DefuserIntent.Revive]).
 *   Se representa con [MineCellState.FLAGGED] (se ve como un escudo permanente) en
 *   vez de un estado propio, para heredar gratis el dibujo y el HUD de banderas;
 *   este flag existe solo para que [onToggleFlag][com.kortexgames.app.game.defuser.DefuserViewModel]
 *   la trate como un escudo que el jugador YA NO puede quitar (si se pudiera
 *   desmarcar, un toque posterior la volvería a detonar). Solo puede ser `true` si
 *   [hasMine] lo es.
 */
@Serializable
data class MineCell(
    val position: CellPosition,
    val hasMine: Boolean = false,
    val adjacentMines: Int = 0,
    val state: MineCellState = MineCellState.HIDDEN,
    val isDetonated: Boolean = false,
    val isDefused: Boolean = false,
) {
    /** `true` si la celda está oculta (candidata a revelar o marcar). */
    val isHidden: Boolean get() = state == MineCellState.HIDDEN

    /** `true` si está revelada y no oculta mina (una celda "segura" descubierta). */
    val isRevealedSafe: Boolean get() = state == MineCellState.REVEALED && !hasMine

    /** `true` si está revelada, es segura y no tiene minas alrededor: es el
     *  disparador de la cascada (sus vecinas también son seguras de revelar). */
    val isEmptyRevealed: Boolean get() = isRevealedSafe && adjacentMines == 0

    /** `true` si el jugador la marcó con escudo/bandera. */
    val isFlagged: Boolean get() = state == MineCellState.FLAGGED
}

/**
 * El panel completo: una rejilla inmutable de [columns] × [rows] celdas.
 *
 * ## Por qué lista plana + índice
 * Las celdas se guardan en una `List` plana indexada por `row * columns + col`
 * (serializable directa y con `equals`/`copy` estructural gratis, lo que encaja en
 * el estado inmutable de MVI; los `Array` de Kotlin comparan por referencia). El
 * acceso por coordenada es aritmética `O(1)`, sin necesidad de un mapa auxiliar.
 *
 * La generación de minas y el conteo de adyacencias se hacen en el motor de
 * FASE 2 sobre un [blank]; este modelo solo ofrece las primitivas topológicas
 * ([neighborsOf], [replacing], recuentos).
 *
 * @property columns ancho del panel en celdas.
 * @property rows alto del panel en celdas.
 * @property cells las `columns * rows` celdas en orden plano `row * columns + col`.
 */
@Serializable
data class MineBoard(
    val columns: Int,
    val rows: Int,
    val cells: List<MineCell>,
) {
    init {
        require(cells.size == columns * rows) {
            "MineBoard ${columns}x$rows requiere ${columns * rows} celdas, recibidas: ${cells.size}"
        }
    }

    /** `true` si [position] cae dentro de los límites del panel. */
    fun contains(position: CellPosition): Boolean =
        position.row in 0 until rows && position.col in 0 until columns

    /** Celda en [position], o `null` si cae fuera del panel. */
    fun cellAt(position: CellPosition): MineCell? =
        if (contains(position)) cells[position.row * columns + position.col] else null

    /**
     * Las celdas vecinas **reales** de [position]: las 8 contiguas
     * ([CellPosition.neighbors]) intersectadas con las que existen en el panel. Las
     * que caen fuera del borde simplemente no aparecen (borde = 5 vecinas, esquina
     * = 3), sin que el llamador tenga que comprobar límites.
     */
    fun neighborsOf(position: CellPosition): List<MineCell> =
        position.neighbors().mapNotNull { cellAt(it) }

    /** Nuevo [MineBoard] con la celda de `cell.position` reemplazada por [cell].
     *  Preserva la inmutabilidad: el motor (FASE 2) nunca muta [cells] in situ. */
    fun replacing(cell: MineCell): MineBoard =
        copy(cells = cells.map { if (it.position == cell.position) cell else it })

    /** Nuevo [MineBoard] aplicando [transform] a cada celda. Azúcar para las
     *  operaciones masivas del motor (colocar minas, calcular adyacencias, revelar
     *  todas las minas al perder) sin repetir el `map` en cada sitio. */
    fun mapCells(transform: (MineCell) -> MineCell): MineBoard =
        copy(cells = cells.map(transform))

    /** Total de minas ocultas en el panel. */
    val mineCount: Int get() = cells.count { it.hasMine }

    /** Número de escudos/banderas colocados por el jugador. */
    val flagCount: Int get() = cells.count { it.isFlagged }

    /** Número de celdas ya reveladas. Lo usa el motor (FASE 2) para saber cuántas
     *  celdas nuevas abrió una cascada (diferencia antes/después) y así elegir el
     *  sonido: revelado simple vs. onda de cascada. */
    val revealedCount: Int get() = cells.count { it.state == MineCellState.REVEALED }

    /** Celdas seguras (sin mina) que aún siguen sin revelar. Cuando llega a 0, el
     *  jugador ha despejado todo el campo: condición de victoria (motor FASE 2). */
    val safeHiddenCount: Int
        get() = cells.count { !it.hasMine && it.state != MineCellState.REVEALED }

    /** `true` si no queda ninguna celda segura por revelar: victoria. */
    val isCleared: Boolean get() = safeHiddenCount == 0

    companion object {
        /**
         * Crea un panel **vacío** (todas las celdas ocultas, sin minas todavía) con
         * las dimensiones de la [difficulty] dada. Las minas y las adyacencias las
         * coloca el motor en el primer toque (FASE 2), lo que permite garantizar que
         * ese primer toque nunca sea mina.
         */
        fun blank(difficulty: MineDifficulty): MineBoard = MineBoard(
            columns = difficulty.columns,
            rows = difficulty.rows,
            cells = buildList {
                for (row in 0 until difficulty.rows) {
                    for (col in 0 until difficulty.columns) {
                        add(MineCell(position = CellPosition(row, col)))
                    }
                }
            },
        )
    }
}

/**
 * Fase de una partida de Neon Defuser (el resultado *dentro* del juego).
 *
 * Es distinto de [com.kortexgames.app.game.GameStatus], que modela el ciclo de
 * vida de navegación (IDLE en la antesala, RUNNING jugando, PAUSED, FINISHED).
 * [MinePhase] responde a "¿cómo va la partida?" y gobierna qué overlay se muestra
 * al terminar (victoria vs. explosión). Se separan igual que en Neon Sudoku, que
 * combina `GameStatus` con su propio estado de resultado.
 */
enum class MinePhase {
    /** Partida en curso: se aceptan toques y marcados. */
    PLAYING,

    /** El jugador despejó todas las celdas seguras: victoria. */
    WON,

    /** El jugador reveló una mina: derrota (Game Over). */
    LOST,
}

/**
 * Constantes de diseño y balance de Neon Defuser.
 *
 * Se centralizan aquí (una sola fuente de verdad) para que la geometría y el
 * balance no queden como literales mágicos repartidos entre modelo, motor
 * (FASE 2) y `Canvas` (FASE 3).
 */
object DefuserConfig {
    /** Mínimo de vecinas con mina que puede reportar una celda. */
    const val MIN_ADJACENT = 0

    /** Máximo de vecinas con mina: 8 (las 4 ortogonales + las 4 diagonales). Es lo
     *  que fija el rango de la escala de color de peligro (1..8). */
    const val MAX_ADJACENT = 8

    /** Celdas que el motor reserva como seguras alrededor del primer toque: la
     *  propia celda + sus 8 vecinas. Se usa para validar que la densidad de minas
     *  de una [MineDifficulty] deje sitio a esa zona franca. */
    const val SAFE_ZONE_CELLS = MAX_ADJACENT + 1

    /** Puntuación base de una partida ganada sin penalizaciones. */
    const val BASE_SCORE = 1_000

    /** Puntos que resta cada segundo transcurrido: premia desactivar rápido sin
     *  convertir el juego en una contrarreloj (no se pierde por tiempo, solo baja
     *  el puntaje final). Mismo criterio que Neon Sudoku. */
    const val TIME_SCORE_PENALTY_PER_SEC = 2

    /**
     * Usos del **escáner de minas** por partida: cuántas celdas puede inspeccionar el
     * jugador a cambio de un anuncio (ver [DefuserIntent.RequestScan]). Se limita para
     * que la ayuda no trivialice la deducción —el corazón del Buscaminas— y para dar
     * un tope claro a la monetización por partida. Un número fijo (no ilimitado) fue
     * decisión de producto.
     */
    const val SCAN_MAX_USES = 3
}

/**
 * Niveles de dificultad de Neon Defuser.
 *
 * En Buscaminas la dificultad la marca sobre todo la **densidad de minas** (minas
 * ÷ celdas), más que el tamaño del panel: a más densidad, menos margen de
 * deducción segura y más celdas que obligan a encadenar varias pistas.
 *
 * Las densidades de referencia del Buscaminas clásico son ~12% (principiante),
 * ~16% (intermedio) y ~21% (experto). Aquí se usan valores **deliberadamente por
 * encima** de esa escala (~16%, ~22%, ~26%, ~24%) porque las partidas resultaban
 * demasiado fáciles: con poca densidad el primer toque abre media pantalla en
 * cascada y el resto se resuelve casi sin pensar. El nivel EXPERTO no sigue la
 * densidad creciente estricta de los anteriores: prioriza un panel más grande
 * (más celdas que recorrer y memorizar) sobre empujar aún más el porcentaje de
 * minas. Los paneles, en cambio, se mantienen estrechos para que quepan cómodos
 * en vertical en un móvil.
 *
 * Se modela como `enum` (dominio cerrado) para que el selector de la antesala se
 * construya recorriendo [entries] y añadir un nivel sea un cambio local aquí.
 *
 * @property displayName etiqueta visible en el selector.
 * @property columns número de columnas del panel.
 * @property rows número de filas.
 * @property mineCount minas a sembrar. `init` valida que quepan dejando libre la
 *   zona franca del primer toque.
 */
enum class MineDifficulty(
    val displayName: String,
    val columns: Int,
    val rows: Int,
    val mineCount: Int,
) {
    FACIL("Fácil", columns = 8, rows = 10, mineCount = 13),        // ~16 %
    MEDIO("Medio", columns = 9, rows = 12, mineCount = 24),        // ~22 %
    DIFICIL("Difícil", columns = 10, rows = 14, mineCount = 37),   // ~26 %
    EXPERTO("Experto", columns = 11, rows = 16, mineCount = 42);   // ~24 %

    /** Total de celdas del panel. */
    val cellCount: Int get() = columns * rows

    init {
        // El motor (FASE 2) reserva el primer toque y sus 8 vecinas como zona
        // segura antes de sembrar minas; exigimos que queden celdas suficientes
        // para colocarlas todas fuera de esa zona, o la generación no terminaría.
        require(mineCount in 1..(cellCount - DefuserConfig.SAFE_ZONE_CELLS)) {
            "mineCount ($mineCount) no cabe en un panel ${columns}x$rows dejando el primer toque seguro"
        }
    }
}
