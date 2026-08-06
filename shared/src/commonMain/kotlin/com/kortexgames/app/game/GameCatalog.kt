package com.kortexgames.app.game

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.kortexgames.app.core.theme.CategoryPalette

/**
 * IDs estables de los juegos de ejemplo. Deben coincidir con las filas sembradas
 * en la tabla `games` de Supabase (migración `0005_seed_catalog.sql`) para que la
 * sincronización remota (FK a `games`) funcione. Son UUID fijos y deterministas.
 */
object GameIds {
    /** Memoria de secuencias (categoría "memory"). */
    const val SEQUENCE_MEMORY = "11111111-1111-4111-8111-111111111111"


    /** Ordena las Pociones / Water Sort (categoría "logic"). */
    const val WATER_SORT = "33333333-3333-4333-8333-333333333333"

    /** Burbujas de Cálculo (categoría "calculation" / Cálculo Mental). */
    const val BUBBLE_MATH = "44444444-4444-4444-8444-444444444444"

    /** Flujo de Energía (categoría "spatial" / Visión Espacial). */
    const val ENERGY_FLOW = "55555555-5555-4555-8555-555555555555"

    /** Atracción Geométrica / Polarity Collision (categoría "spatial"). */
    const val POLARITY_COLLISION = "66666666-6666-4666-8666-666666666666"

    /** Crucigrama Neón (categoría "language" / Lenguaje y Vocabulario). */
    const val CRUCIGRAMA_NEON = "77777777-7777-4777-8777-777777777777"

    /** Palabras Conectadas / Word Connect (categoría "language" / Lenguaje y Vocabulario). */
    const val WORD_CONNECT = "88888888-8888-4888-8888-888888888888"

    /** Tornillos Neón / Neon Screws & Bolts (categoría "spatial" / Visión Espacial). */
    const val NEON_SCREWS = "99999999-9999-4999-8999-999999999999"

    /** Tetris Neón / Block Puzzle 8×8 (categoría "spatial" / Visión Espacial). */
    const val NEON_BLOCK_GRID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    /** Neon Lexicon / Sopa de Letras Neón (categoría "language" / Lenguaje y Vocabulario). */
    const val NEON_LEXICON = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    /** Neon Starport Escape / Rush Hour espacial (categoría "logic" / Pensamiento Lógico). */
    const val STARPORT_ESCAPE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    /** Neon Circuit Flow / Flow Free de circuitos (categoría "problem_solving" / Resolución de Problemas). */
    const val NEON_CIRCUIT = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    /** Hypergate / escudo de polaridad (categorías "reflexes" + "flexibility"). */
    const val HYPERGATE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

    /** Neon Pulse / entrenador visomotor de nodos que se contraen (categoría "reflexes"). */
    const val NEON_PULSE = "ffffffff-ffff-4fff-8fff-ffffffffffff"

    /**
     * Neon Grid 2048 / fusión de potencias de dos (categoría "calculation").
     *
     * Rompe el patrón de "nibble repetido" de los ids anteriores porque ya no
     * quedaban libres (1..f están tomados, incluido el `2222…` del Reflex Tap
     * retirado en la migración 0020, que NO se puede reciclar: sus filas de
     * `user_progress` siguen apuntando ahí). Se usa un UUID v4 válido y
     * mnemotécnico (`2048…`), igual de determinista.
     */
    const val NEON_2048 = "20482048-2048-4048-8048-204820482048"

    /**
     * Neon Sudoku Matrix / Sudoku 9x9 con panel holográfico neón (categoría
     * "logic" / Pensamiento Lógico). UUID v4 aleatorio (ya no quedan nibbles
     * libres para un mnemotécnico como en [NEON_2048]).
     *
     * Reservado desde la FASE 2 (ViewModel) del juego; se añade su [GameInfo] al
     * catálogo cuando la pantalla Compose (FASE 3) esté lista para publicarse.
     */
    const val NEON_SUDOKU_MATRIX = "9c40ca31-4a69-4d6b-a903-355098c129ee"

    /**
     * Neon Defuser / Buscaminas con panel neón de desactivación (categoría
     * "attention" / Atención y Concentración). UUID v4 aleatorio.
     *
     * El juego nació como malla hexagonal ("Hexa Mine") y se migró a celdas
     * cuadradas con vecindad de 8. Se conserva **el mismo UUID** a propósito: ya
     * hay filas de resultados y de progreso apuntando a él, y cambiarlo las
     * huérfanaría sin ganar nada (el identificador no describe la geometría).
     */
    const val NEON_DEFUSER = "7b3f1e02-9d4c-4a8e-b1c6-2f5a9d0e4c37"

    /**
     * Neon Hyper-Cube / cubo mágico 3×3 holográfico (categoría "spatial" / Visión Espacial).
     * UUID v4 aleatorio (ya no quedan nibbles libres para un mnemotécnico como en [NEON_2048]).
     *
     * Reservado desde la FASE 2 (motor + ViewModel) del juego; se añade su [GameInfo] al catálogo
     * cuando la pantalla Compose (FASE 3) esté lista para publicarse.
     */
    const val HYPER_CUBE = "3d5c8a17-6b24-4e9f-9a80-1c7e5b2d4f63"

    /**
     * Quantum Merge / esferas de energía que caen y se fusionan (categoría "spatial" / Visión
     * Espacial). UUID v4 aleatorio, como los dos anteriores.
     *
     * El prefijo se eligió a conciencia para que **no se parezca a ningún otro id del catálogo**:
     * dos UUID que solo difieren en un par de dígitos transpuestos son un accidente esperando a
     * ocurrir (basta copiar el constante equivocado para que las partidas de un juego se guarden
     * en la tabla de otro, y la FK no lo detectaría porque ambos existen).
     */
    const val QUANTUM_MERGE = "c17b40de-92a5-4f38-8b61-0d7e5a3c9142"
}

/**
 * Categorías cognitivas del catálogo (§1 de CLAUDE.md). Cada una lleva su color
 * representativo (de [CategoryPalette]) y un **icono vectorial Material** (nunca
 * emoji) para dar identidad visual inmediata a las tarjetas.
 *
 * @property displayName etiqueta visible en español.
 * @property tagline descripción corta de la habilidad que entrena (para tarjetas).
 * @property icon icono representativo (variante Rounded).
 * @property accent color de marca de la categoría (tarjetas, chips, halo neón).
 */
enum class GameCategory(
    val displayName: String,
    val tagline: String,
    val icon: ImageVector,
    val accent: Color,
) {
    MEMORY("Memoria", "Mejora tu recuerdo", Icons.Rounded.Psychology, CategoryPalette.Memory),
    LOGIC("Pensamiento Lógico", "Resolución de problemas", Icons.Rounded.Extension, CategoryPalette.Logic),
    PROBLEM_SOLVING("Resolución de Problemas", "Encuentra la solución", Icons.Rounded.Lightbulb, CategoryPalette.ProblemSolving),
    REFLEXES("Reflejos", "Pensamiento rápido", Icons.Rounded.Timer, CategoryPalette.Reflexes),
    MENTAL_SPEED("Velocidad Mental", "Procesa más rápido", Icons.Rounded.Speed, CategoryPalette.MentalSpeed),
    ATTENTION("Atención y Concentración", "Mantén el foco", Icons.Rounded.CenterFocusStrong, CategoryPalette.Attention),
    SPATIAL("Visión Espacial", "Orienta y rota", Icons.Rounded.Explore, CategoryPalette.SpatialVision),
    MENTAL_MATH("Cálculo Mental", "Números al vuelo", Icons.Rounded.Functions, CategoryPalette.MentalMath),
    LANGUAGE("Lenguaje y Vocabulario", "Amplía tu léxico", Icons.Rounded.Translate, CategoryPalette.Language),
    FLEXIBILITY("Flexibilidad Cognitiva", "Cambia de enfoque", Icons.Rounded.Shuffle, CategoryPalette.CognitiveFlexibility),
    PATTERNS("Reconocimiento de Patrones", "Detecta la regla", Icons.Rounded.ViewModule, CategoryPalette.PatternRecognition),
}

/**
 * Motivo visual **propio de un juego** para el fondo de su tarjeta de catálogo.
 *
 * Existe porque varios juegos comparten [GameCategory] (p. ej. Crucigrama, Sopa de
 * Letras y Palabras Conectadas son todos "Lenguaje y Vocabulario") y, si el fondo
 * dependiera solo de la categoría, sus tarjetas serían idénticas y no comunicarían
 * de qué juego se trata. Cada motivo dibuja una miniatura reconocible de la
 * mecánica del juego (una rejilla de crucigrama, una sopa de letras con palabras
 * marcadas, la rueda de letras…), teñida con el acento de la categoría para no
 * romper la identidad de color.
 *
 * Es un `enum` puro (sin dependencias de dibujo) a propósito: el mapeo motivo →
 * Canvas vive en la capa de UI ([com.kortexgames.app.ui.components.CategoryTexture]),
 * de modo que el catálogo de dominio no arrastre lógica de Compose.
 */
enum class GameMotif {
    /** Sopa de letras: cuadrícula de letras con algunas palabras resaltadas/tachadas. */
    WORD_SEARCH,

    /** Crucigrama: rejilla entrelazada de casillas abiertas, bloques y letras. */
    CROSSWORD,

    /** Palabras conectadas: rueda de letras unidas por un trazo. */
    WORD_WHEEL,

    /** Ordena las pociones / water sort: frasquitos de poción con líquido. */
    POTIONS,

    /** Tetris / block puzzle: piezas de tetrominó (formas de 4 bloques). */
    TETROMINO,

    /** Sudoku: cuadrícula 9×9 con separadores de bloque y algunos números. */
    SUDOKU_GRID,

    /** Neon 2048: recuadros con potencias de dos (2, 4, 8…) del juego. */
    NUMBER_TILES,

    /** Neon Defuser / buscaminas: rejilla con números, una bandera y una mina. */
    MINESWEEPER,

    /** Neon Circuit Flow / flow free: nodos unidos por tuberías redondeadas en rejilla. */
    CIRCUIT_FLOW,

    /** Neon Hyper-Cube: cubo 3×3 en isométrica con sus tres caras visibles subdivididas. */
    HYPER_CUBE,

    /** Hypergate: portal circular con cometas que se precipitan hacia él. */
    HYPERGATE,

    /** Pulso Neon: varios pulsos concéntricos espaciados y de tamaños escalonados. */
    NEON_PULSE,

    /** Memoria de Secuencias: rejilla 3×3 de pads con uno iluminado (estilo Simon). */
    SEQUENCE_GRID,

    /** Burbujas de Cálculo: burbujas con operaciones y un objetivo numérico. */
    MATH_BUBBLES,

    /** Flujo de Energía: tablero de tuberías que conecta dos nodos-objetivo iluminados. */
    ENERGY_PIPES,

    /** Atracción Geométrica: círculo de 4 sectores con partículas que llegan de fuera. */
    POLARITY_SECTORS,

    /** Quantum Merge: esferas de luz de tamaños crecientes apiladas dentro del reactor. */
    QUANTUM_SPHERES,
}

/**
 * Metadatos de un minijuego para pintar el catálogo. Es contenido de UI (no un
 * modelo de dominio): describe cómo se muestra y si ya es jugable. El icono y el
 * color se toman de su [category].
 *
 * @property id UUID estable ([GameIds]); null en placeholders "próximamente".
 * @property playable false = tarjeta visible pero deshabilitada (roadmap).
 * @property published false = juego terminado pero que **no publicamos** todavía:
 *           su código sigue en el repo pero se oculta por completo del catálogo y
 *           del dado aleatorio (ver [GameCatalog.games]). No confundir con
 *           [playable] (roadmap "Próximamente", que sí se muestra atenuado).
 * @property motif motivo visual propio del juego ([GameMotif]): pinta el fondo de su
 *           tarjeta del catálogo y, dibujado centrado, hace de arte "héroe" en la
 *           intro y de miniatura en la Home (**fuente única de identidad visual**).
 *           null = usa el fondo genérico de la categoría y su icono como respaldo.
 */
data class GameInfo(
    val id: String?,
    val title: String,
    val category: GameCategory,
    val playable: Boolean,
    val published: Boolean = true,
    val motif: GameMotif? = null,
)

/**
 * Catálogo de juegos que alimenta la pantalla de lista. Hoy son jugables nueve
 * juegos (Memoria, Reflejos, Pociones, Burbujas de Cálculo, Flujo de Energía,
 * Atracción Geométrica, Crucigrama Neón, Palabras Conectadas y Tornillos Neón);
 * el resto son placeholders del roadmap para dar volumen y comunicar la visión
 * sin engañar (aparecen como "Próximamente").
 */
object GameCatalog {
    /**
     * Todos los juegos definidos, incluidos los que aún **no publicamos**
     * ([GameInfo.published] = false). Es privado a propósito: la UI y el dado
     * aleatorio deben consumir [games], que ya filtra los no publicados en un
     * único punto para que nunca se cuelen en dos sitios distintos.
     */
    private val allGames: List<GameInfo> = listOf(
        GameInfo(GameIds.WATER_SORT, "Ordena las Pociones", GameCategory.LOGIC, playable = true, motif = GameMotif.POTIONS),
        GameInfo(GameIds.NEON_2048, "2048", GameCategory.MENTAL_MATH, playable = true, motif = GameMotif.NUMBER_TILES),
        GameInfo(GameIds.NEON_DEFUSER, "Buscaminas", GameCategory.ATTENTION, playable = true, motif = GameMotif.MINESWEEPER),
        GameInfo(GameIds.ENERGY_FLOW, "Flujo de Energía", GameCategory.SPATIAL, playable = true, motif = GameMotif.ENERGY_PIPES),
        GameInfo(GameIds.BUBBLE_MATH, "Burbujas de Cálculo", GameCategory.MENTAL_MATH, playable = true, motif = GameMotif.MATH_BUBBLES),
        GameInfo(GameIds.HYPER_CUBE, "Hyper Cubo", GameCategory.SPATIAL, playable = true, motif = GameMotif.HYPER_CUBE),
        GameInfo(GameIds.NEON_BLOCK_GRID, "Tetris Neón", GameCategory.LOGIC, playable = true, motif = GameMotif.TETROMINO),
        GameInfo(GameIds.CRUCIGRAMA_NEON, "Crucigrama Neón", GameCategory.LANGUAGE, playable = true, motif = GameMotif.CROSSWORD),
        GameInfo(GameIds.NEON_SUDOKU_MATRIX, "Neon Sudoku", GameCategory.MENTAL_MATH, playable = true, motif = GameMotif.SUDOKU_GRID),
        GameInfo(GameIds.NEON_PULSE, "Pulso Neon", GameCategory.REFLEXES, playable = true, motif = GameMotif.NEON_PULSE),
        GameInfo(GameIds.POLARITY_COLLISION, "Atracción Geométrica", GameCategory.SPATIAL, playable = true, motif = GameMotif.POLARITY_SECTORS),
        GameInfo(GameIds.WORD_CONNECT, "Palabras Conectadas", GameCategory.LANGUAGE, playable = true, published = false, motif = GameMotif.WORD_WHEEL),
        GameInfo(GameIds.NEON_SCREWS, "Tornillos Neón", GameCategory.SPATIAL, playable = true, published = false),
        GameInfo(GameIds.SEQUENCE_MEMORY, "Memoria de Secuencias", GameCategory.MEMORY, playable = true, motif = GameMotif.SEQUENCE_GRID),
        GameInfo(GameIds.NEON_LEXICON, "Sopa de Letras Neón", GameCategory.LANGUAGE, playable = true, motif = GameMotif.WORD_SEARCH),
        GameInfo(GameIds.STARPORT_ESCAPE, "Neon Starport Escape", GameCategory.LOGIC, playable = true, published = false),
        GameInfo(GameIds.NEON_CIRCUIT, "Conectores", GameCategory.PROBLEM_SOLVING, playable = true, motif = GameMotif.CIRCUIT_FLOW),
        GameInfo(GameIds.HYPERGATE, "Hypergate", GameCategory.REFLEXES, playable = true, motif = GameMotif.HYPERGATE),
        GameInfo(GameIds.QUANTUM_MERGE, "Quantum Merge", GameCategory.SPATIAL, playable = true, motif = GameMotif.QUANTUM_SPHERES),
        GameInfo(null, "Parejas Relámpago", GameCategory.MEMORY, playable = false),
        GameInfo(null, "Cadena Lógica", GameCategory.LOGIC, playable = false),
        GameInfo(null, "Encuentra el Intruso", GameCategory.ATTENTION, playable = false),
        GameInfo(null, "Anagramas", GameCategory.LANGUAGE, playable = false),
        GameInfo(null, "Cambio de Regla", GameCategory.FLEXIBILITY, playable = false),
        GameInfo(null, "Patrón Oculto", GameCategory.PATTERNS, playable = false),
    )

    /**
     * Catálogo visible para la UI y el dado aleatorio: [allGames] sin los juegos
     * marcados como no publicados ([GameInfo.published] = false). Filtrar aquí, en
     * un solo sitio, garantiza que un juego oculto no reaparezca por accidente en
     * la lista, la Home o la ruta aleatoria.
     */
    val games: List<GameInfo> = allGames.filter { it.published }

    /** Categorías destacadas en la Home (fila horizontal, como el mockup). */
    val featuredCategories: List<GameCategory> = listOf(
        GameCategory.MEMORY, GameCategory.REFLEXES, GameCategory.LOGIC,
        GameCategory.MENTAL_MATH, GameCategory.ATTENTION,
    )
}
