package com.kortexgames.app.game.shikaku

/**
 * # Neon Shikaku Matrix — modelos de dominio (Fase 1)
 *
 * Tipos puros (sin Compose ni plataforma) que describen un tablero de Shikaku sobre una
 * **máscara irregular**: qué celdas existen ([BoardMask]), dónde están las pistas
 * ([ShikakuCell]), la geometría de un rectángulo ([ShikakuRect]) y los rectángulos que traza el
 * jugador ([ShikakuRectangle]).
 *
 * Decisiones que condicionan el resto del juego:
 *
 *  1. **Todo en coordenadas de celda, nunca en píxeles.** `x` crece hacia la derecha e `y` hacia
 *     abajo, con origen en la esquina superior izquierda de la caja contenedora de la máscara. La
 *     pantalla convierte el gesto a celda en el borde de la UI, así el dominio (y sus tests) no
 *     saben cuánto mide nada en pantalla.
 *  2. **La máscara es parte del nivel, no un adorno del render.** Una celda inhabilitada no es
 *     "una celda vacía": no existe, no se cubre y ningún rectángulo puede pisarla. Por eso la
 *     consulta [BoardMask.isEnabled] responde `false` también fuera de rango — quien valida un
 *     rectángulo no necesita distinguir "fuera del tablero" de "agujero".
 *  3. **Geometría separada de identidad.** [ShikakuRect] es un valor (dos rectángulos iguales son
 *     el mismo); [ShikakuRectangle] añade el `id` estable que necesitan `RemoveRectangle` y las
 *     animaciones de la lista en Compose.
 */

/**
 * Celda con pista: la casilla ([x], [y]) muestra el número [number] y exige que el rectángulo que
 * la contenga mida exactamente [number] celdas.
 *
 * Solo se modelan las celdas CON número: las demás no llevan ningún dato propio (su única
 * propiedad, estar habilitadas o no, ya vive en [BoardMask]), así que una lista dispersa de
 * pistas es más barata y más directa de recorrer que una matriz densa de celdas casi todas vacías.
 */
data class ShikakuCell(val x: Int, val y: Int, val number: Int)

/**
 * Rectángulo alineado a la rejilla, en coordenadas de celda.
 *
 * @property left columna de la celda superior izquierda.
 * @property top fila de la celda superior izquierda.
 * @property width ancho en celdas (≥ 1).
 * @property height alto en celdas (≥ 1).
 */
data class ShikakuRect(val left: Int, val top: Int, val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "Un rectángulo necesita al menos una celda: ${width}x$height" }
    }

    /** Primera columna FUERA del rectángulo (límite exclusivo, simplifica los rangos `until`). */
    val right: Int get() = left + width

    /** Primera fila FUERA del rectángulo (límite exclusivo). */
    val bottom: Int get() = top + height

    /** Número de celdas: el valor que debe igualar a la pista contenida. */
    val area: Int get() = width * height

    /** `true` si la celda ([x], [y]) cae dentro del rectángulo. */
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    /** `true` si la pista [cell] cae dentro del rectángulo. */
    operator fun contains(cell: ShikakuCell): Boolean = contains(cell.x, cell.y)

    /** `true` si comparte al menos una celda con [other] (tocarse por el borde NO es solaparse). */
    fun overlaps(other: ShikakuRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        /**
         * Rectángulo mínimo que abarca dos celdas cualesquiera, sin importar en qué orden se den.
         *
         * Es la operación del gesto de arrastre: el jugador puede tirar desde el ancla hacia
         * cualquiera de las cuatro diagonales, así que normalizar aquí evita que cada consumidor
         * (ViewModel, vista previa, badge) repita los `min`/`max`.
         */
        fun spanning(ax: Int, ay: Int, bx: Int, by: Int): ShikakuRect {
            val left = minOf(ax, bx)
            val top = minOf(ay, by)
            return ShikakuRect(left, top, maxOf(ax, bx) - left + 1, maxOf(ay, by) - top + 1)
        }
    }
}

/**
 * Máscara del tablero: qué celdas de una rejilla [columns] × [rows] son jugables.
 *
 * Es lo que permite tableros en L, en cruz, con agujero central o de borde asimétrico: la rejilla
 * sigue siendo rectangular (su caja contenedora) y la máscara "recorta" la figura.
 *
 * Es `data class` sobre una `List<Boolean>` inmutable (y no un `BooleanArray`) a propósito: vive
 * dentro del `UiState`, que debe comparar por valor para que `StateFlow` descarte emisiones
 * idénticas. Un array compararía por referencia y rompería esa garantía.
 *
 * @property columns ancho de la caja contenedora, en celdas.
 * @property rows alto de la caja contenedora, en celdas.
 */
data class BoardMask(
    val columns: Int,
    val rows: Int,
    private val enabled: List<Boolean>,
) {

    init {
        require(columns >= 0 && rows >= 0) { "Dimensiones negativas: ${columns}x$rows" }
        require(enabled.size == columns * rows) {
            "La máscara trae ${enabled.size} celdas y la rejilla ${columns}x$rows necesita ${columns * rows}"
        }
    }

    /** Cuántas celdas jugables tiene la figura: el total que hay que cubrir para ganar. */
    val enabledCount: Int = enabled.count { it }

    /**
     * `true` si ([x], [y]) es una celda jugable. Fuera de la rejilla devuelve `false` en lugar de
     * lanzar: para las reglas, salirse del tablero y caer en un agujero son el mismo error.
     */
    fun isEnabled(x: Int, y: Int): Boolean =
        x in 0 until columns && y in 0 until rows && enabled[y * columns + x]

    /**
     * `true` si TODAS las celdas de [rect] son jugables, es decir, si el rectángulo cabe en la
     * figura sin pisar agujeros ni salirse. Es la condición geométrica previa a cualquier regla
     * de Shikaku.
     */
    fun covers(rect: ShikakuRect): Boolean {
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                if (!isEnabled(x, y)) return false
            }
        }
        return true
    }

    companion object {
        /** Máscara sin celdas: valor inicial del estado antes de que se genere el primer nivel. */
        val EMPTY: BoardMask = BoardMask(0, 0, emptyList())

        /** Tablero rectangular clásico: todas las celdas habilitadas. */
        fun full(columns: Int, rows: Int): BoardMask = of(columns, rows) { _, _ -> true }

        /** Construye la máscara preguntando celda a celda a [isEnabled] (recorrido por filas). */
        fun of(columns: Int, rows: Int, isEnabled: (x: Int, y: Int) -> Boolean): BoardMask =
            BoardMask(columns, rows, List(columns * rows) { isEnabled(it % columns, it / columns) })
    }
}

/**
 * Familias de figura que sabe construir el generador. Ordenadas de menor a mayor exigencia
 * espacial: cuanto más se aleja la figura del rectángulo, menos sirven las "plantillas" mentales
 * del jugador (filas y columnas completas) y más hay que razonar celda a celda.
 */
enum class ShikakuMaskShape {
    /** Rejilla completa, sin recortes. */
    RECTANGLE,

    /** Rejilla con las cuatro esquinas mordidas (cada una con su propio tamaño). */
    CUT_CORNERS,

    /** Forma en L: falta un cuadrante entero. */
    L_SHAPE,

    /** Cruz: las cuatro esquinas recortadas con el mismo tamaño. */
    CROSS,

    /** Rosca: agujero rectangular interior que no toca el borde. */
    CENTER_HOLE,

    /** Borde asimétrico con varios mordiscos aleatorios y, a veces, un agujero interior. */
    IRREGULAR,
}

/**
 * Por qué un rectángulo cumple o incumple las reglas. Se distingue el MOTIVO (y no un simple
 * booleano) porque la UI reacciona distinto: el badge de arrastre puede decir "falta área" frente
 * a "aquí no hay número", y el parpadeo de error solo aplica a rectángulos ya colocados.
 */
enum class ShikakuRectValidity {
    /** Contiene exactamente una pista y su área coincide con ella. */
    VALID,

    /** Contiene exactamente una pista, pero su área no es la que pide. */
    WRONG_AREA,

    /** No contiene ninguna pista. */
    NO_NUMBER,

    /** Contiene dos o más pistas: habría que partirlo. */
    MULTIPLE_NUMBERS,

    /** Pisa celdas inhabilitadas o se sale de la figura: ni siquiera es un rectángulo jugable. */
    OUT_OF_MASK,
}

/**
 * Acento neón de un rectángulo del jugador. Es un enum de dominio (y no un `Color`) para que este
 * archivo no dependa de Compose; la pantalla lo traduce a `LogicColors` en un único `when`.
 */
enum class ShikakuTint { CYAN, MAGENTA, GREEN }

/**
 * Rectángulo trazado por el jugador.
 *
 * @property id identificador estable dentro de la partida. No se reutiliza al borrar: así
 *   `RemoveRectangle(id)` nunca apunta por accidente a un rectángulo creado después, y Compose
 *   puede usarlo como `key` para animar entradas y salidas.
 * @property bounds geometría en celdas.
 * @property tint acento neón con el que se pinta; se asigna al crearlo para que no cambie de
 *   color cuando se borran o reordenan los vecinos.
 * @property validity veredicto de reglas vigente. Vive en el modelo (no se recalcula en la UI)
 *   porque es el ViewModel quien conoce las pistas y decide el parpadeo de error.
 */
data class ShikakuRectangle(
    val id: Int,
    val bounds: ShikakuRect,
    val tint: ShikakuTint,
    val validity: ShikakuRectValidity,
)

/**
 * Nivel listo para jugar, tal como lo entrega [ShikakuLevelGenerator].
 *
 * @property level número de nivel (1-based) del que salió.
 * @property mask figura del tablero.
 * @property clues pistas visibles; hay exactamente una por rectángulo de [solution].
 * @property solution partición con la que se construyó el nivel. **No es el criterio de
 *   victoria**: la partida se gana cumpliendo las reglas, coincida o no con esta lista (ver
 *   [hasUniqueSolution]). Se conserva para pistas/ayudas y para los tests.
 * @property hasUniqueSolution `true` si el generador DEMOSTRÓ que [solution] es la única
 *   partición válida. `false` significa "no demostrado" (se agotó el presupuesto de búsqueda),
 *   no necesariamente que haya varias; el nivel sigue siendo resoluble en cualquier caso.
 */
data class ShikakuLevel(
    val level: Int,
    val mask: BoardMask,
    val clues: List<ShikakuCell>,
    val solution: List<ShikakuRect>,
    val hasUniqueSolution: Boolean,
)
