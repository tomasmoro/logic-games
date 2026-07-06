package com.example.kortexgames.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.game.daily.calculateStreakDays
import com.example.kortexgames.ui.components.ChartPoint
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LineChart
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.pulse
import com.example.kortexgames.ui.settings.SettingsIntent
import com.example.kortexgames.ui.settings.SettingsViewModel

/**
 * Pantalla de Perfil: racha actual (con icono de fuego neón animado), espacios
 * preparados para los gráficos de rendimiento, ajustes de la app y gestión de
 * cuenta. La racha se **deriva del historial local** (fuente de verdad, offline).
 * Sin emojis: todos los iconos son vectoriales.
 */
@Composable
fun ProfileScreen(graph: AppGraph) {
    val settingsVm: SettingsViewModel = viewModel {
        SettingsViewModel(graph.settingsRepository, graph.audio)
    }
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    val history by graph.progressRepository.observeHistory(null)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val streak = calculateStreakDays(history)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Perfil", style = MaterialTheme.typography.headlineLarge, color = LogicColors.OnDark)

            StreakCard(streakDays = streak, totalGames = history.size)

            // --- Estadísticas ----------------------------------------------------
            Text("Estadísticas", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
            SectionCard(title = "Efectividad") {
                LineChart(
                    points = remember(history) { effectivenessPoints(history) },
                    lineColor = LogicColors.NeonCyan,
                )
            }
            SectionCard(title = "Tiempo de finalización") {
                ChartPlaceholder("Próximamente: evolución de tus tiempos")
            }

            // --- Ajustes ---------------------------------------------------------
            Text("Ajustes", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
            SectionCard {
                SettingToggle(Icons.AutoMirrored.Rounded.VolumeUp, "Efectos de sonido", LogicColors.NeonCyan, settings.settings.isSfxEnabled) {
                    settingsVm.onIntent(SettingsIntent.ToggleSfx)
                }
                SettingToggle(Icons.Rounded.MusicNote, "Música", LogicColors.Violet, settings.settings.isMusicEnabled) {
                    settingsVm.onIntent(SettingsIntent.ToggleMusic)
                }
                SettingToggle(Icons.Rounded.Vibration, "Vibración", LogicColors.Coral, settings.settings.isHapticsEnabled) {
                    settingsVm.onIntent(SettingsIntent.ToggleHaptics)
                }
            }

            // --- Cuenta ----------------------------------------------------------
            Text("Cuenta", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
            AccountRow(onClick = { /* TODO FASE auth: navegar a ajustes de cuenta */ })

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Tarjeta de racha. El icono de fuego **late** ([pulse]) para transmitir energía;
 * a mayor racha, más "vivo" se siente el perfil.
 */
@Composable
private fun StreakCard(streakDays: Int, totalGames: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(
            icon = KortexIcons.Streak,
            tint = LogicColors.StreakOrange,
            size = 48.dp,
            modifier = Modifier.pulse(maxScale = 1.12f, durationMillis = 750),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$streakDays ${if (streakDays == 1) "día" else "días"}",
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.OnDark,
            )
            Text(
                if (streakDays == 0) "Empieza tu racha jugando hoy"
                else "Racha actual · ¡no la rompas!",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            Text(
                "$totalGames partidas jugadas en total",
                style = MaterialTheme.typography.labelMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
    }
}

/** Fila de ajuste: icono neón + etiqueta + interruptor. */
@Composable
private fun SettingToggle(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonIcon(icon = icon, tint = tint, size = 22.dp, glow = false)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Fila de cuenta: icono de ajustes + texto + chevron, con rebote al tocar. */
@Composable
private fun AccountRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .bounceClick(onClick = onClick)
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonIcon(icon = KortexIcons.Settings, tint = LogicColors.NeonCyan, size = 24.dp, glow = false)
            Spacer(Modifier.width(14.dp))
            Text("Gestión de cuenta", style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
        }
        Icon(KortexIcons.ChevronRight, contentDescription = null, tint = LogicColors.OnDarkMuted, modifier = Modifier.size(24.dp))
    }
}

/** Tarjeta de sección reutilizable (título opcional + contenido). */
@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LogicColors.OnDark)
        }
        content()
    }
}

/** Marcador visual para un gráfico aún sin datos/implementar. */
@Composable
private fun ChartPlaceholder(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceVariantDark),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeonIcon(icon = Icons.AutoMirrored.Rounded.ShowChart, tint = LogicColors.OnDarkMuted, size = 30.dp, glow = false)
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Serie de efectividad (precisión %) de las últimas partidas para el gráfico.
 * Si aún no hay historial suficiente, devuelve una curva de muestra para no dejar
 * el gráfico vacío (el `LineChart` necesita ≥2 puntos).
 */
private fun effectivenessPoints(history: List<GameProgress>): List<ChartPoint> {
    val recent = history.takeLast(7)
    return if (recent.size >= 2) {
        recent.mapIndexed { i, p -> ChartPoint(i.toFloat(), p.accuracyPercentage.toFloat()) }
    } else {
        listOf(52f, 61f, 58f, 70f, 74f, 82f, 88f).mapIndexed { i, v -> ChartPoint(i.toFloat(), v) }
    }
}
