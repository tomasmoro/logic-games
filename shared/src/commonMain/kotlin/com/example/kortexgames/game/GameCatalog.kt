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

/**
 * IDs estables de los juegos de ejemplo. Deben coincidir con las filas sembradas
 * en la tabla `games` de Supabase (migración `0005_seed_catalog.sql`) para que la
 * sincronización remota (FK a `games`) funcione. Son UUID fijos y deterministas.
 */
object GameIds {
    /** Memoria de secuencias (categoría "memory"). */
    const val SEQUENCE_MEMORY = "11111111-1111-4111-8111-111111111111"

    /** Reflejos de toque rápido (categoría "reflexes"). */
    const val REFLEX_TAP = "22222222-2222-4222-8222-222222222222"

    /** Ordena las Pociones / Water Sort (categoría "logic"). */
    const val WATER_SORT = "33333333-3333-4333-8333-333333333333"
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
 */
data class GameInfo(
    val id: String?,
    val title: String,
    val category: GameCategory,
    val playable: Boolean,
)

/**
 * Catálogo de juegos que alimenta la pantalla de lista. Hoy solo dos juegos son
 * jugables (FASE 4); el resto son placeholders del roadmap para dar volumen y
 * comunicar la visión sin engañar (aparecen como "Próximamente").
 */
object GameCatalog {
    val games: List<GameInfo> = listOf(
        GameInfo(GameIds.SEQUENCE_MEMORY, "Memoria de Secuencias", GameCategory.MEMORY, playable = true),
        GameInfo(GameIds.REFLEX_TAP, "Reflejos de Toque Rápido", GameCategory.REFLEXES, playable = true),
        GameInfo(GameIds.WATER_SORT, "Ordena las Pociones", GameCategory.LOGIC, playable = true),
        GameInfo(null, "Parejas Relámpago", GameCategory.MEMORY, playable = false),
        GameInfo(null, "Cadena Lógica", GameCategory.LOGIC, playable = false),
        GameInfo(null, "Sumas Veloces", GameCategory.MENTAL_MATH, playable = false),
        GameInfo(null, "Encuentra el Intruso", GameCategory.ATTENTION, playable = false),
        GameInfo(null, "Rotación Espacial", GameCategory.SPATIAL, playable = false),
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
