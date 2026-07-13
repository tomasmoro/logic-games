package com.example.kortexgames.game.reflex

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay

/**
 * Pantalla de Reflejos. Toda la superficie es táctil; su color y texto dependen
 * de la fase del motor. Un toque cualquiera se traduce en [ReflexTapIntent.Tap].
 */
@Composable
fun ReflexTapScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: ReflexTapViewModel = viewModel {
        ReflexTapViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            title = "Reflejos de Toque Rápido",
            description = "Toca en cuanto la pantalla se ponga verde. Cuanto más rápido reacciones, mejor tu marca.",
            accent = CategoryPalette.Reflexes,
            onStart = { vm.onIntent(ReflexTapIntent.Start) },
            onExit = onExit,
        )
        return
    }

    // Color de la zona según la fase (feedback visual inmediato).
    val targetColor = when (game.phase) {
        ReflexPhase.READY -> LogicColors.Success
        ReflexPhase.TOO_SOON -> LogicColors.Error
        ReflexPhase.ROUND_RESULT -> LogicColors.Violet
        else -> LogicColors.SurfaceDark // WAITING / IDLE / FINISHED
    }
    val bg by animateColorAsState(targetColor, label = "reflexBg")
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                vm.onIntent(ReflexTapIntent.Tap)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "Ronda ${game.round.coerceAtLeast(1)}/${game.totalRounds}",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.OnDark,
            )
            Text(
                text = when (game.phase) {
                    ReflexPhase.WAITING -> "Espera…"
                    ReflexPhase.READY -> "¡TOCA YA!"
                    ReflexPhase.TOO_SOON -> "¡Muy pronto! 😬"
                    ReflexPhase.ROUND_RESULT -> "${game.lastReactionMs} ms"
                    else -> ""
                },
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.OnDark,
                textAlign = TextAlign.Center,
            )
            val best = game.bestMs
            if (best != null) {
                Text(
                    "Mejor: $best ms   ·   ${game.score} pts",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDark.copy(alpha = 0.85f),
                )
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(ReflexTapIntent.PlayAgain) },
                onExit = onExit,
            )
        }
    }
}
