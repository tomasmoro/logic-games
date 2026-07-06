package com.example.kortexgames.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.theme.LogicGamesTheme
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.memory.SequenceMemoryScreen
import com.example.kortexgames.game.reflex.ReflexTapScreen
import com.example.kortexgames.game.watersort.WaterSortScreen
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
 * Estructura: un [Scaffold] con [AnimatedBottomBar] (visible solo en las pestañas
 * raíz) y un [NavHost] con transiciones **sutiles** de opacidad + escala
 * (CLAUDE.md §9.4 — nada de deslizamientos largos que mareen).
 *
 * Integra el [AppGraph.adManager]: entrar a una ruta de juego marca "juego
 * activo" (corre el contador de anuncios); cualquier otra ruta lo pausa.
 */
@Composable
fun App(graph: AppGraph) {
    LogicGamesTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        // El AdManager solo cuenta tiempo dentro de un juego.
        LaunchedEffect(currentRoute) {
            if (currentRoute == Routes.MEMORY || currentRoute == Routes.REFLEX ||
                currentRoute == Routes.WATER_SORT
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
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding),
                // Transición por defecto: fade + escala corta (no invasiva).
                enterTransition = { fadeIn(tween(250)) + scaleIn(initialScale = 0.96f, animationSpec = tween(250)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(250)) },
                popExitTransition = { fadeOut(tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        graph = graph,
                        onQuickPlay = { navController.navigate(Routes.REFLEX) },
                        onSeeGames = { navController.navigateToTab(Routes.GAMES) },
                        onOpenGame = { route -> navController.navigate(route) },
                    )
                }
                composable(Routes.GAMES) {
                    GameListScreen(
                        graph = graph,
                        onOpenGame = { route -> navController.navigate(route) },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(graph = graph)
                }
                composable(Routes.MEMORY) {
                    SequenceMemoryScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.REFLEX) {
                    ReflexTapScreen(graph) { navController.popBackStack() }
                }
                composable(Routes.WATER_SORT) {
                    WaterSortScreen(graph) { navController.popBackStack() }
                }
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
