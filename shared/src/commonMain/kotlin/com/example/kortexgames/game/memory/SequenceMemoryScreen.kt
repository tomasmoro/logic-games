package com.example.kortexgames.game.memory

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameOverOverlay

/**
 * Pantalla de Memoria de Secuencias. Observa el estado del ViewModel y pinta la
 * rejilla 3x3; durante SHOWING los tiles se iluminan solos (input ignorado) y en
 * INPUT el jugador los toca. Al terminar, superpone [GameOverOverlay].
 */
@Composable
fun SequenceMemoryScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: SequenceMemoryViewModel = viewModel {
        SequenceMemoryViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    Box(Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Memoria de Secuencias", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark)
            Spacer(Modifier.height(8.dp))
            Text(
                "Nivel ${game.level}   ·   ${game.score} pts",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.Electric,
            )
            Text(
                when (game.phase) {
                    MemoryPhase.SHOWING -> "Observa la secuencia…"
                    MemoryPhase.INPUT -> "¡Tu turno! Repítela"
                    MemoryPhase.ROUND_OK -> "¡Bien! Siguiente ronda"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
            )
            Spacer(Modifier.height(24.dp))

            // Rejilla 3x3
            val inputEnabled = game.phase == MemoryPhase.INPUT
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (rowIdx in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (colIdx in 0 until 3) {
                            val tileIndex = rowIdx * 3 + colIdx
                            MemoryTile(
                                lit = game.litTile == tileIndex,
                                enabled = inputEnabled,
                                onClick = { vm.onIntent(SequenceMemoryIntent.TapTile(tileIndex)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                onPlayAgain = { vm.onIntent(SequenceMemoryIntent.PlayAgain) },
                onExit = onExit,
            )
        }
    }
}

/** Un tile de la rejilla; anima su color cuando se ilumina. */
@Composable
private fun MemoryTile(
    lit: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = if (lit) LogicColors.Electric else LogicColors.SurfaceVariantDark
    val color by animateColorAsState(targetValue = target, label = "tileColor")
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    )
}
