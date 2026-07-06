package com.example.kortexgames.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.ui.components.KortexIcons

/**
 * Rutas de navegación de la app. Se usan strings estables (en vez de rutas
 * tipadas) por simplicidad y para no acoplar la UI a la serialización.
 *
 * Dos familias:
 *  - **Pestañas** (Home/Games/Profile): raíz con [AnimatedBottomBar] visible.
 *  - **Juegos** (Memory/Reflex): pantalla completa, SIN barra inferior, para
 *    máxima inmersión durante la partida.
 */
object Routes {
    const val HOME = "home"
    const val GAMES = "games"
    const val PROFILE = "profile"

    const val MEMORY = "game/memory"
    const val REFLEX = "game/reflex"
    const val WATER_SORT = "game/watersort"

    /**
     * Ruta de juego para un [GameIds] concreto, o null si el juego aún no es
     * jugable (placeholder del catálogo). Mantiene el mapeo id→ruta en un solo
     * sitio para que el catálogo no conozca las rutas de navegación.
     */
    fun gameRoute(gameId: String?): String? = when (gameId) {
        GameIds.SEQUENCE_MEMORY -> MEMORY
        GameIds.REFLEX_TAP -> REFLEX
        GameIds.WATER_SORT -> WATER_SORT
        else -> null
    }

    /**
     * Ruta de un juego jugable elegido **al azar** (para el botón de "juego
     * aleatorio"). Solo considera juegos jugables con ruta; null si no hay ninguno.
     */
    fun randomGameRoute(): String? =
        GameCatalog.games
            .filter { it.playable }
            .mapNotNull { gameRoute(it.id) }
            .randomOrNull()
}

/**
 * Pestañas de la barra de navegación inferior. Cada una lleva su [route], la
 * etiqueta visible y un **icono vectorial** (nunca emoji).
 */
enum class TopLevelTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", KortexIcons.Home),
    GAMES(Routes.GAMES, "Games", KortexIcons.Games),
    PROFILE(Routes.PROFILE, "Profile", KortexIcons.Profile);

    companion object {
        /** ¿La ruta actual corresponde a una pestaña raíz (⇒ mostrar la barra)? */
        fun isTopLevel(route: String?): Boolean = entries.any { it.route == route }
    }
}
