package com.example.kortexgames.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kortexgames.core.ads.AdEvent
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameCategory
import com.example.kortexgames.game.daily.DailyGoalState
import com.example.kortexgames.game.daily.calculateStreakDays
import com.example.kortexgames.ui.components.CategoryMotifSurface
import com.example.kortexgames.ui.components.CircularProgressRing
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.pulse
import com.example.kortexgames.ui.components.softGlow
import com.example.kortexgames.ui.navigation.Routes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pantalla de Inicio: el **centro de motivación** (rediseño estilo mockup neón).
 * Header con avatar + racha, objetivo diario con anillo de progreso animado, CTA
 * "PLAY NOW" con latido y brillo, y una fila de categorías con iconos neón.
 *
 * @param onQuickPlay abre una partida rápida (quick play).
 * @param onSeeGames lleva al catálogo completo.
 * @param onOpenGame abre un juego concreto por su ruta de navegación.
 */
@Composable
fun HomeScreen(
    graph: AppGraph,
    onQuickPlay: () -> Unit,
    onSeeGames: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    val dailyGoal by graph.dailyGoalManager.state.collectAsStateWithLifecycle()
    val history by graph.progressRepository.observeHistory(null)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val streak = calculateStreakDays(history)

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Nombre amigable: el modo invitado no tiene nombre → saludo genérico cálido.
    val playerName = remember(graph.authState) {
        if (graph.authState is AuthState.Authenticated) "Campeón" else "Jugador"
    }

    // Anuncio cada 3 min de juego activo (si no es premium).
    LaunchedEffect(Unit) {
        graph.adManager.adEvents.collect { event ->
            if (event is AdEvent.ShowInterstitial) snackbar.showSnackbar("Mostrar anuncio intersticial")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            HomeHeader(name = playerName, streakDays = streak)

            DailyGoalCard(
                goal = dailyGoal,
                onClaim = {
                    scope.launch {
                        if (graph.dailyGoalManager.claimReward()) {
                            snackbar.showSnackbar("¡Recompensa diaria reclamada!")
                        }
                    }
                },
            )

            PlayNowButton(onClick = onQuickPlay)

            CategoryRow(
                onOpenCategory = { category ->
                    val route = GameCatalog.games
                        .firstOrNull { it.category == category && it.playable }
                        ?.let { Routes.gameRoute(it.id) }
                    if (route != null) onOpenGame(route) else onSeeGames()
                },
            )

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Cabecera: avatar con anillo neón + saludo, y píldora de racha a la derecha. */
@Composable
private fun HomeHeader(name: String, streakDays: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: inicial dentro de un círculo con borde de degradado neón.
        Box(
            modifier = Modifier
                .size(52.dp)
                .border(2.dp, Brush.sweepGradient(LogicGradients.energy), CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(LogicColors.SurfaceVariantDark),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.NeonCyan,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("¡Hola, $name!", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark)
            Text("Bienvenid@ de nuevo", style = MaterialTheme.typography.bodyMedium, color = LogicColors.OnDarkMuted)
        }
        StreakPill(streakDays = streakDays)
    }
}

/** Píldora de racha: llama de fuego neón + "N DÍAS / RACHA". */
@Composable
private fun StreakPill(streakDays: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonIcon(icon = KortexIcons.Streak, tint = LogicColors.StreakOrange, size = 26.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                "$streakDays ${if (streakDays == 1) "DÍA" else "DÍAS"}",
                color = LogicColors.StreakOrange,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
            )
            Text("RACHA", color = LogicColors.OnDarkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Tarjeta del objetivo diario con [CircularProgressRing] que se llena al entrar.
 * Muestra el porcentaje y "X / Y" en el centro y, al cumplirse, el botón de premio.
 */
@Composable
private fun DailyGoalCard(goal: DailyGoalState, onClaim: () -> Unit) {
    val percent = (goal.progress * 100).roundToInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(LogicColors.SurfaceDark)
            .padding(vertical = 24.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OBJETIVO DIARIO", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
            Text("Potencia tu mente hoy", style = MaterialTheme.typography.bodyMedium, color = LogicColors.OnDarkMuted)
        }

        CircularProgressRing(
            progress = goal.progress,
            size = 168.dp,
            strokeWidth = 15.dp,
            progressColors = LogicGradients.ring,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$percent%", style = MaterialTheme.typography.displayLarge, color = LogicColors.OnDark)
                Text(
                    "${goal.completed} / ${goal.target} juegos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }

        Text(
            if (goal.isComplete) "¡Objetivo completado! Mantén viva la racha."
            else "¡Mantén viva la racha!\nCompleta ${goal.remaining} juegos más.",
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )

        if (goal.canClaim) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(LogicGradients.reward))
                    .bounceClick(onClick = onClaim)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text("Reclamar recompensa", color = LogicColors.BackgroundDark, fontWeight = FontWeight.Bold)
            }
        } else if (goal.rewardClaimed) {
            Text("Recompensa de hoy reclamada", style = MaterialTheme.typography.labelLarge, color = LogicColors.Success)
        }
    }
}

/**
 * Botón "PLAY NOW": el elemento más llamativo de la Home. Combina [softGlow]
 * (brillo verde continuo), [pulse] (latido) y [bounceClick] (rebote al tocar). Es
 * el ÚNICO elemento con animación en bucle de la pantalla (CLAUDE.md §9.4).
 */
@Composable
private fun PlayNowButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .pulse()
            .softGlow(color = LogicColors.NeonGreen, shape = shape, maxElevation = 26.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(LogicGradients.play))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Completa tu entrenamiento",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = LogicColors.BackgroundDark,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(LogicColors.BackgroundDark.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = KortexIcons.Play, tint = LogicColors.BackgroundDark, size = 18.dp, glow = false)
            }
        }
    }
}

/** Fila horizontal de categorías destacadas, con tarjetas de icono neón. */
@Composable
private fun CategoryRow(onOpenCategory: (GameCategory) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Categorías", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(GameCatalog.featuredCategories) { category ->
                CategoryCard(category = category, onClick = { onOpenCategory(category) })
            }
        }
    }
}

/**
 * Tarjeta de categoría: icono neón, título, tagline y barra de acento inferior.
 * El fondo es temático por categoría ([CategoryMotifSurface] → [CategoryTexture])
 * y al pulsar hace una animación de expansión que ilumina la tarjeta.
 */
@Composable
private fun CategoryCard(category: GameCategory, onClick: () -> Unit) {
    CategoryMotifSurface(
        category = category,
        shape = RoundedCornerShape(22.dp),
        onClick = onClick,
        modifier = Modifier.width(140.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeonIcon(icon = category.icon, tint = category.accent, size = 30.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    maxLines = 1,
                )
                Text(
                    category.tagline,
                    style = MaterialTheme.typography.labelMedium,
                    color = LogicColors.OnDarkMuted,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(category.accent, category.accent.copy(alpha = 0.3f)))),
            )
        }
    }
}
