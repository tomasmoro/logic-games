package com.kortexgames.app.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.components.KortexIcons

/**
 * Rutas de navegación de la app. Se usan strings estables (en vez de rutas
 * tipadas) por simplicidad y para no acoplar la UI a la serialización.
 *
 * Dos familias:
 *  - **Pestañas** (Home/Games/Profile): raíz con [AnimatedBottomBar] visible.
 *  - **Juegos** (Memory/Water Sort): pantalla completa, SIN barra inferior, para
 *    máxima inmersión durante la partida.
 */
object Routes {
    const val HOME = "home"
    const val GAMES = "games"
    const val PROFILE = "profile"

    /**
     * Login **a demanda**: un invitado pulsa "Iniciar sesión" en Home/Perfil.
     * Muestra la flecha de volver y NO la opción de invitado (ya pasó la puerta).
     */
    const val AUTH = "auth"

    /**
     * Login como **puerta de primera apertura** (onboarding): obligatorio decidir,
     * ofrece "continuar sin cuenta" con aviso de riesgos y no muestra "volver".
     * Es una ruta aparte (en vez de un argumento) para no depender de la API de
     * argumentos, que difiere entre plataformas en Compose Multiplatform.
     */
    const val AUTH_ONBOARDING = "auth/onboarding"

    /** Ajustes de cuenta (nombre de usuario, borrar cuenta…), abierta desde Perfil. */
    const val SETTINGS = "settings"

    const val MEMORY = "game/memory"
    const val WATER_SORT = "game/watersort"
    const val BUBBLE_MATH = "game/bubblemath"
    const val ENERGY_FLOW = "game/energyflow"
    const val POLARITY_COLLISION = "game/polarity-collision"
    const val CRUCIGRAMA_NEON = "game/crucigrama-neon"
    const val WORD_CONNECT = "game/word-connect"
    const val NEON_SCREWS = "game/neon-screws"
    const val NEON_BLOCK_GRID = "game/neon-block-grid"
    const val NEON_LEXICON = "game/neon-lexicon"
    const val STARPORT_ESCAPE = "game/starport-escape"
    const val NEON_CIRCUIT = "game/neon-circuit"
    const val HYPERGATE = "game/hypergate"
    const val NEON_PULSE = "game/neon-pulse"
    const val NEON_2048 = "game/neon-2048"
    const val NEON_SUDOKU = "game/neon-sudoku"
    const val NEON_DEFUSER = "game/neon-defuser"
    const val HYPER_CUBE = "game/hyper-cube"
    const val QUANTUM_MERGE = "game/quantum-merge"

    /**
     * Ruta de juego para un [GameIds] concreto, o null si el juego aún no es
     * jugable (placeholder del catálogo). Mantiene el mapeo id→ruta en un solo
     * sitio para que el catálogo no conozca las rutas de navegación.
     */
    fun gameRoute(gameId: String?): String? = when (gameId) {
        GameIds.SEQUENCE_MEMORY -> MEMORY
        GameIds.WATER_SORT -> WATER_SORT
        GameIds.BUBBLE_MATH -> BUBBLE_MATH
        GameIds.ENERGY_FLOW -> ENERGY_FLOW
        GameIds.POLARITY_COLLISION -> POLARITY_COLLISION
        GameIds.CRUCIGRAMA_NEON -> CRUCIGRAMA_NEON
        GameIds.WORD_CONNECT -> WORD_CONNECT
        GameIds.NEON_SCREWS -> NEON_SCREWS
        GameIds.NEON_BLOCK_GRID -> NEON_BLOCK_GRID
        GameIds.NEON_LEXICON -> NEON_LEXICON
        GameIds.STARPORT_ESCAPE -> STARPORT_ESCAPE
        GameIds.NEON_CIRCUIT -> NEON_CIRCUIT
        GameIds.HYPERGATE -> HYPERGATE
        GameIds.NEON_PULSE -> NEON_PULSE
        GameIds.NEON_2048 -> NEON_2048
        GameIds.NEON_SUDOKU_MATRIX -> NEON_SUDOKU
        GameIds.NEON_DEFUSER -> NEON_DEFUSER
        GameIds.HYPER_CUBE -> HYPER_CUBE
        GameIds.QUANTUM_MERGE -> QUANTUM_MERGE
        else -> null
    }

    /**
     * ¿La ruta es una **pantalla de juego** a pantalla completa? Fuente única de esta
     * pregunta: todas las rutas de juego comparten el prefijo `"game/"`, así que un
     * juego nuevo queda cubierto automáticamente sin tocar esta función ni las que la
     * usan (ocultar la barra inferior, contar tiempo/breakpoints de anuncios, etc.).
     * Evita listas de rutas mantenidas a mano que se desincronizan al sumar juegos.
     */
    fun isGameRoute(route: String?): Boolean = route?.startsWith("game/") == true

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
