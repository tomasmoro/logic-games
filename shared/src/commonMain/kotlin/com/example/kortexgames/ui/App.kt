package com.example.kortexgames.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kortexgames.core.theme.LogicGamesTheme
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.memory.SequenceMemoryScreen
import com.example.kortexgames.game.reflex.ReflexTapScreen
import com.example.kortexgames.ui.home.HomeScreen

/** Destinos de navegación (navegación mínima basada en estado, sin librería). */
private enum class Screen { HOME, MEMORY, REFLEX }

/**
 * Raíz de la app Compose Multiplatform, compartida por Android e iOS.
 *
 * Integra el [AppGraph.adManager] con la navegación: entrar a un juego marca
 * "juego activo" (el contador de anuncios corre); volver al menú lo pausa.
 */
@Composable
fun App(graph: AppGraph) {
    LogicGamesTheme {
        var screen by remember { mutableStateOf(Screen.HOME) }

        // El AdManager solo cuenta tiempo dentro de un juego.
        LaunchedEffect(screen) {
            if (screen == Screen.HOME) graph.adManager.onEnterMenuOrPause()
            else graph.adManager.onEnterGameplay()
        }

        when (screen) {
            Screen.HOME -> HomeScreen(
                graph = graph,
                onOpenMemory = { screen = Screen.MEMORY },
                onOpenReflex = { screen = Screen.REFLEX },
            )
            Screen.MEMORY -> SequenceMemoryScreen(graph) { screen = Screen.HOME }
            Screen.REFLEX -> ReflexTapScreen(graph) { screen = Screen.HOME }
        }
    }
}
