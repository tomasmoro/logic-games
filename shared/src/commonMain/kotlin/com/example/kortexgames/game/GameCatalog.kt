package com.example.kortexgames.game

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
import com.example.kortexgames.core.theme.CategoryPalette
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.bubblemath_intro
import kortexgames.shared.generated.resources.crucigrama_intro
import kortexgames.shared.generated.resources.energyflow_intro
import kortexgames.shared.generated.resources.memory_intro
import kortexgames.shared.generated.resources.potionsorting_intro
import org.jetbrains.compose.resources.DrawableResource

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
 * Metadatos de un minijuego para pintar el catálogo. Es contenido de UI (no un
 * modelo de dominio): describe cómo se muestra y si ya es jugable. El icono y el
 * color se toman de su [category].
 *
 * @property id UUID estable ([GameIds]); null en placeholders "próximamente".
 * @property playable false = tarjeta visible pero deshabilitada (roadmap).
 * @property heroImage arte "héroe" del juego (el mismo de su pantalla de intro);
 *           **fuente única de verdad** reutilizada por la intro y por las tarjetas de
 *           la Home. null = aún sin arte → se cae al icono de la categoría.
 */
data class GameInfo(
    val id: String?,
    val title: String,
    val category: GameCategory,
    val playable: Boolean,
    val heroImage: DrawableResource? = null,
)

/**
 * Catálogo de juegos que alimenta la pantalla de lista. Hoy son jugables nueve
 * juegos (Memoria, Reflejos, Pociones, Burbujas de Cálculo, Flujo de Energía,
 * Atracción Geométrica, Crucigrama Neón, Palabras Conectadas y Tornillos Neón);
 * el resto son placeholders del roadmap para dar volumen y comunicar la visión
 * sin engañar (aparecen como "Próximamente").
 */
object GameCatalog {
    val games: List<GameInfo> = listOf(
        GameInfo(GameIds.SEQUENCE_MEMORY, "Memoria de Secuencias", GameCategory.MEMORY, playable = true, heroImage = Res.drawable.memory_intro),
        GameInfo(GameIds.WATER_SORT, "Ordena las Pociones", GameCategory.LOGIC, playable = true, heroImage = Res.drawable.potionsorting_intro),
        GameInfo(GameIds.BUBBLE_MATH, "Burbujas de Cálculo", GameCategory.MENTAL_MATH, playable = true, heroImage = Res.drawable.bubblemath_intro),
        GameInfo(GameIds.ENERGY_FLOW, "Flujo de Energía", GameCategory.SPATIAL, playable = true, heroImage = Res.drawable.energyflow_intro),
        GameInfo(GameIds.POLARITY_COLLISION, "Atracción Geométrica", GameCategory.SPATIAL, playable = true),
        GameInfo(GameIds.CRUCIGRAMA_NEON, "Crucigrama Neón", GameCategory.LANGUAGE, playable = true, heroImage = Res.drawable.crucigrama_intro),
        GameInfo(GameIds.WORD_CONNECT, "Palabras Conectadas", GameCategory.LANGUAGE, playable = true),
        GameInfo(GameIds.NEON_SCREWS, "Tornillos Neón", GameCategory.SPATIAL, playable = true),
        GameInfo(GameIds.NEON_BLOCK_GRID, "Tetris Neón", GameCategory.SPATIAL, playable = true),
        GameInfo(GameIds.NEON_LEXICON, "Sopa de Letras Neón", GameCategory.LANGUAGE, playable = true),
        GameInfo(GameIds.STARPORT_ESCAPE, "Neon Starport Escape", GameCategory.LOGIC, playable = true),
        GameInfo(GameIds.NEON_CIRCUIT, "Neon Circuit Flow", GameCategory.PROBLEM_SOLVING, playable = true),
        GameInfo(GameIds.HYPERGATE, "Hypergate", GameCategory.REFLEXES, playable = true),
        GameInfo(GameIds.NEON_PULSE, "Neon Pulse", GameCategory.REFLEXES, playable = true),
        GameInfo(null, "Parejas Relámpago", GameCategory.MEMORY, playable = false),
        GameInfo(null, "Cadena Lógica", GameCategory.LOGIC, playable = false),
        GameInfo(null, "Encuentra el Intruso", GameCategory.ATTENTION, playable = false),
        GameInfo(null, "Anagramas", GameCategory.LANGUAGE, playable = false),
        GameInfo(null, "Cambio de Regla", GameCategory.FLEXIBILITY, playable = false),
        GameInfo(null, "Patrón Oculto", GameCategory.PATTERNS, playable = false),
    )

    /** Categorías destacadas en la Home (fila horizontal, como el mockup). */
    val featuredCategories: List<GameCategory> = listOf(
        GameCategory.MEMORY, GameCategory.REFLEXES, GameCategory.LOGIC,
        GameCategory.MENTAL_MATH, GameCategory.ATTENTION,
    )
}
