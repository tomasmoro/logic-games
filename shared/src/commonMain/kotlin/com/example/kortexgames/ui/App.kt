package com.example.kortexgames.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGamesTheme
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.bubblemath.BubbleMathScreen
import com.example.kortexgames.game.crucigrama.CrucigramaNeonScreen
import com.example.kortexgames.game.energyflow.EnergyFlowScreen
import com.example.kortexgames.game.memory.SequenceMemoryScreen
import com.example.kortexgames.game.hypergate.HypergateScreen
import com.example.kortexgames.game.blockgrid.BlockGridScreen
import com.example.kortexgames.game.wordsearch.NeonLexiconScreen
import com.example.kortexgames.game.screws.ScrewGameScreen
import com.example.kortexgames.game.neoncircuit.NeonCircuitScreen
import com.example.kortexgames.game.neonpulse.NeonPulseScreen
import com.example.kortexgames.game.polarity.PolarityCollisionScreen
import com.example.kortexgames.game.starport.StarportScreen
import com.example.kortexgames.game.watersort.WaterSortScreen
import com.example.kortexgames.game.wordconnect.WordConnectScreen
import com.example.kortexgames.ui.auth.AuthScreen
import com.example.kortexgames.ui.components.RandomGameFab
import com.example.kortexgames.ui.games.GameListScreen
import com.example.kortexgames.ui.home.HomeScreen
import com.example.kortexgames.ui.navigation.AnimatedBottomBar
import com.example.kortexgames.ui.navigation.Routes
import com.example.kortexgames.ui.navigation.TopLevelTab
import com.example.kortexgames.ui.profile.ProfileScreen

/**
 * Raíz de la app Compose Multiplatform, compartida por Android e iOS.
 *
 * Antes de montar la navegación resuelve la **puerta de onboarding**
 * ([AppGraph.onboardingGate]): mientras el flag no se ha leído de DataStore
 * (`null`) muestra un splash; luego decide si el destino inicial es el login
 * (primera apertura) o el Home.
 */
@Composable
fun App(graph: AppGraph) {
    LogicGamesTheme {
        // null = aún leyendo DataStore → splash (evita parpadear la bienvenida a
        // quien ya la pasó). false = primera apertura → login. true = ya decidió.
        val hasDecided by graph.onboardingGate.hasDecided.collectAsStateWithLifecycle()
        when (hasDecided) {
            null -> SplashScreen()
            else -> MainNavigation(graph = graph, startAtAuth = hasDecided == false)
        }
    }
}

/** Splash mínimo mientras se resuelve la puerta de onboarding. */
@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = LogicColors.NeonCyan, modifier = Modifier.size(36.dp))
    }
}

/**
 * Navegación principal: un [Scaffold] con [AnimatedBottomBar] (visible solo en las
 * pestañas raíz) y un [NavHost] con transiciones **sutiles** de opacidad + escala
 * (CLAUDE.md §9.4 — nada de deslizamientos largos que mareen).
 *
 * Integra el [AppGraph.adManager]: entrar a una ruta de juego marca "juego
 * activo" (corre el contador de anuncios); cualquier otra ruta lo pausa.
 *
 * @param startAtAuth true si es la primera apertura (arranca en el login onboarding).
 */
@Composable
private fun MainNavigation(graph: AppGraph, startAtAuth: Boolean) {
        val navController = rememberNavController()
        // El destino inicial se fija una sola vez: si el usuario resuelve la puerta
        // durante la sesión, el flag cambia pero no queremos recrear el NavHost.
        val startDestination = remember { if (startAtAuth) Routes.AUTH_ONBOARDING else Routes.HOME }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        // El AdManager solo cuenta tiempo dentro de un juego.
         LaunchedEffect(currentRoute) {
            if (currentRoute == Routes.MEMORY ||
                currentRoute == Routes.WATER_SORT || currentRoute == Routes.BUBBLE_MATH ||
                currentRoute == Routes.ENERGY_FLOW || currentRoute == Routes.POLARITY_COLLISION ||
                currentRoute == Routes.CRUCIGRAMA_NEON || currentRoute == Routes.WORD_CONNECT ||
                currentRoute == Routes.NEON_SCREWS || currentRoute == Routes.NEON_BLOCK_GRID ||
                currentRoute == Routes.NEON_LEXICON || currentRoute == Routes.STARPORT_ESCAPE ||
                currentRoute == Routes.NEON_CIRCUIT || currentRoute == Routes.HYPERGATE ||
                currentRoute == Routes.NEON_PULSE
            ) {
                graph.adManager.onEnterGameplay()
            } else {
                graph.adManager.onEnterMenuOrPause()
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                // Dado flotante de "juego al azar": solo en las pestañas raíz,
                // aparece/desaparece con un escalado suave.
                AnimatedVisibility(
                    visible = TopLevelTab.isTopLevel(currentRoute),
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f),
                ) {
                    RandomGameFab(
                        onClick = { Routes.randomGameRoute()?.let { navController.navigate(it) } },
                        // "Lanzar el dado": sonido de dado + háptica fuerte (respeta ajustes).
                        onRoll = {
                            graph.audio.playSound(SoundEffect.DICE_ROLL)
                            graph.audio.hapticFeedback(HapticFeedback.HEAVY)
                        },
                        // "Aterrizaje": toque háptico suave al terminar el giro.
                        onLand = { graph.audio.hapticFeedback(HapticFeedback.LIGHT) },
                    )
                }
            },
            bottomBar = {
                // La barra entra/sale deslizando desde abajo al alternar entre
                // pestañas raíz y pantallas de juego (inmersión total en la partida).
                AnimatedVisibility(
                    visible = TopLevelTab.isTopLevel(currentRoute),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    AnimatedBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { tab -> navController.navigateToTab(tab.route) },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding),
                // Transición por defecto: fade + escala corta (no invasiva).
                enterTransition = { fadeIn(tween(250)) + scaleIn(initialScale = 0.96f, animationSpec = tween(250)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(250)) },
                popExitTransition = { fadeOut(tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)) },
            ) {
                // Puerta de primera apertura: al resolver salta a Home y se saca del
                // backstack para que "atrás" no vuelva a la bienvenida.
                composable(Routes.AUTH_ONBOARDING) {
                    AuthScreen(
                        graph = graph,
                        isOnboarding = true,
                        onFinished = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.AUTH_ONBOARDING) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                // Login a demanda desde Home/Perfil: al terminar, vuelve atrás.
                composable(Routes.AUTH) {
                    AuthScreen(
                        graph = graph,
                        isOnboarding = false,
                        onFinished = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        graph = graph,
                        onQuickPlay = { navController.navigate(Routes.MEMORY) },
                        onSeeGames = { navController.navigateToTab(Routes.GAMES) },
                        onOpenGame = { route -> navController.navigate(route) },
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                    )
                }
                composable(Routes.GAMES) {
                    GameListScreen(
                        graph = graph,
                        onOpenGame = { route -> navController.navigate(route) },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        graph = graph,
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                    )
                }
                composable(Routes.MEMORY) {
                    SequenceMemoryScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.WATER_SORT) {
                    WaterSortScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.BUBBLE_MATH) {
                    BubbleMathScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.ENERGY_FLOW) {
                    EnergyFlowScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.POLARITY_COLLISION) {
                    PolarityCollisionScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.CRUCIGRAMA_NEON) {
                    CrucigramaNeonScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.WORD_CONNECT) {
                    WordConnectScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.NEON_SCREWS) {
                    ScrewGameScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.NEON_BLOCK_GRID) {
                    BlockGridScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.NEON_LEXICON) {
                    NeonLexiconScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.STARPORT_ESCAPE) {
                    StarportScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.NEON_CIRCUIT) {
                    NeonCircuitScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.HYPERGATE) {
                    HypergateScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.NEON_PULSE) {
                    NeonPulseScreen(graph) { navController.popBackStack() }
                }
            }
        }
}

/**
 * Navega a una pestaña raíz con el patrón estándar de bottom-nav: un único
 * destino en cima de pila y **preservando el estado** de cada pestaña (scroll,
 * formularios) al volver a ella.
 */
private fun NavController.navigateToTab(route: String) {
    val startDestinationId = graph.findStartDestination().id
    navigate(route) {
        // Vuelve al inicio del grafo guardando el estado para no apilar pestañas.
        popUpTo(startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
