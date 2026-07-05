package com.example.kortexgames.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.ads.AdEvent
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.daily.DailyGoalState
import com.example.kortexgames.ui.components.AnimatedGameButton
import com.example.kortexgames.ui.components.ChartPoint
import com.example.kortexgames.ui.components.LineChart
import com.example.kortexgames.ui.settings.SettingsIntent
import com.example.kortexgames.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Menú principal. Integra FASE 3 (MVI settings, LineChart, AdManager) y FASE 4
 * (tarjeta de Daily Goal + acceso a los dos juegos).
 */
@Composable
fun HomeScreen(
    graph: AppGraph,
    onOpenMemory: () -> Unit,
    onOpenReflex: () -> Unit,
) {
    val settingsVm: SettingsViewModel = viewModel {
        SettingsViewModel(graph.settingsRepository, graph.audio)
    }
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    val dailyGoal by graph.dailyGoalManager.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val effectiveness = remember {
        listOf(52f, 61f, 58f, 70f, 74f, 82f, 88f)
            .mapIndexed { i, v -> ChartPoint(i.toFloat(), v) }
    }

    // Anuncio cada 3 min de juego activo (si no es premium).
    LaunchedEffect(Unit) {
        graph.adManager.adEvents.collect { event ->
            if (event is AdEvent.ShowInterstitial) snackbar.showSnackbar("🎬 Mostrar anuncio intersticial")
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Logic Games", style = MaterialTheme.typography.displayLarge)

            DailyGoalCard(
                goal = dailyGoal,
                onClaim = {
                    scope.launch {
                        if (graph.dailyGoalManager.claimReward()) {
                            snackbar.showSnackbar("🎁 ¡Recompensa diaria reclamada!")
                        }
                    }
                },
            )

            Text("Tu efectividad esta semana", style = MaterialTheme.typography.titleLarge)
            LineChart(points = effectiveness)

            Text("Juegos", style = MaterialTheme.typography.titleLarge)
            AnimatedGameButton(
                text = "🧠  Memoria de Secuencias",
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.primary,
                onClick = onOpenMemory,
            )
            AnimatedGameButton(
                text = "⚡  Reflejos de Toque Rápido",
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.energy,
                onClick = onOpenReflex,
            )

            Spacer(Modifier.height(8.dp))
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)
            SettingToggle("Efectos de sonido", settings.settings.isSfxEnabled) {
                settingsVm.onIntent(SettingsIntent.ToggleSfx)
            }
            SettingToggle("Música", settings.settings.isMusicEnabled) {
                settingsVm.onIntent(SettingsIntent.ToggleMusic)
            }
            SettingToggle("Vibración", settings.settings.isHapticsEnabled) {
                settingsVm.onIntent(SettingsIntent.ToggleHaptics)
            }
        }
    }
}

/** Tarjeta del objetivo diario: progreso X/5 y botón de recompensa al cumplirlo. */
@Composable
private fun DailyGoalCard(goal: DailyGoalState, onClaim: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("🎯 Objetivo diario", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
        Text(
            if (goal.isComplete) "¡Completado! ${goal.completed}/${goal.target}"
            else "${goal.completed}/${goal.target} ejercicios · faltan ${goal.remaining}",
            style = MaterialTheme.typography.bodyLarge,
            color = LogicColors.OnDarkMuted,
        )
        LinearProgressIndicator(
            progress = { goal.progress },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)),
            color = LogicColors.Success,
            trackColor = LogicColors.SurfaceVariantDark,
        )
        if (goal.canClaim) {
            AnimatedGameButton(
                text = "🎁 Reclamar recompensa",
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.reward,
                onClick = onClaim,
            )
        } else if (goal.rewardClaimed) {
            Text("✅ Recompensa de hoy reclamada", style = MaterialTheme.typography.labelLarge, color = LogicColors.Success)
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
