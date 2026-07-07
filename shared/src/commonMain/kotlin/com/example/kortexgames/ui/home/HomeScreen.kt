package com.example.kortexgames.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
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
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameCategory
import com.example.kortexgames.game.GameInfo
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
 * @param onOpenAuth abre el login (mostrado como CTA solo a invitados).
 */
@Composable
fun HomeScreen(
    graph: AppGraph,
    onQuickPlay: () -> Unit,
    onSeeGames: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenAuth: () -> Unit,
) {
    val dailyGoal by graph.dailyGoalManager.state.collectAsStateWithLifecycle()
    val history by graph.progressRepository.observeHistory(null)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val streak = calculateStreakDays(history)

    // Sesión reactiva: la fuente de verdad para el saludo y el CTA de login.
    val session by graph.authRepository.sessionState.collectAsStateWithLifecycle()
    val isGuest = session !is AuthState.Authenticated

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Nombre amigable: el modo invitado no tiene nombre → saludo genérico cálido.
    val playerName = if (isGuest) "Jugador" else "Campeón"

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

            // Invitado: banner que invita a iniciar sesión para guardar en la nube.
            if (isGuest) {
                SignInBanner(onClick = onOpenAuth)
            }

            // Entrenamiento terminado → cartel de celebración con brillo neón; en
            // caso contrario, el CTA que invita a completarlo. Nunca ambos: un solo
            // foco de acción por pantalla (CLAUDE.md §9.1 "acento escaso").
            if (dailyGoal.isComplete) {
                TrainingCompleteCard()
            } else {
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
            }

            // Juego estrella: el más jugado con mejor precisión media. Se calcula a partir
            val starGame = remember(history) { findStarGame(history) }
            if (starGame != null) {
                StarGameCard(
                    star = starGame,
                    onPlay = { Routes.gameRoute(starGame.game.id)?.let(onOpenGame) },
                )
            }
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

/**
 * Banner para invitados: recuerda que sin cuenta el progreso no se guarda en la
 * nube y ofrece iniciar sesión de un toque. Estética cian (foco) con [bounceClick].
 * Solo se muestra a invitados, por lo que no compite con el CTA de "jugar".
 */
@Composable
private fun SignInBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(LogicGradients.energy),
                shape = RoundedCornerShape(20.dp),
            )
            .bounceClick(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = Icons.Rounded.CloudSync, tint = LogicColors.NeonCyan, size = 26.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Guarda tu progreso",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
            )
            Text(
                "Inicia sesión para sincronizar en la nube",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Entrar",
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.NeonCyan,
            fontWeight = FontWeight.Bold,
        )
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

/**
 * Cartel de **entrenamiento completado**: sustituye al [PlayNowButton] cuando el
 * objetivo diario está cumplido. Celebra el logro con estética neón sin caer en el
 * ruido: un halo verde que respira ([softGlow]), un borde que late en opacidad y un
 * icono de check con latido ([pulse]). Reutiliza el mismo lenguaje del CTA para que
 * el usuario perciba el cambio como "el botón se convirtió en recompensa".
 *
 * No es interactivo: es un estado de confirmación, no una acción.
 */
@Composable
private fun TrainingCompleteCard() {
    val shape = RoundedCornerShape(30.dp)

    // Latido de opacidad del borde: da vida al neón sin mover el layout. Sincroniza
    // en espíritu con el brillo de [softGlow] para leerse como un único "respiro".
    val transition = rememberInfiniteTransition(label = "trainingComplete")
    val borderAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "borderAlpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .softGlow(color = LogicColors.Success, shape = shape, maxElevation = 24.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        LogicColors.SurfaceDark,
                        LogicColors.SurfaceVariantDark,
                    ),
                ),
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        LogicColors.NeonGreen.copy(alpha = borderAlpha),
                        LogicColors.NeonCyan.copy(alpha = borderAlpha),
                    ),
                ),
                shape = shape,
            )
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(
            icon = KortexIcons.Check,
            tint = LogicColors.Success,
            size = 34.dp,
            modifier = Modifier.pulse(maxScale = 1.08f),
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "¡Entrenamiento completado!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = LogicColors.OnDark,
            )
            Text(
                "Gran trabajo hoy. ¡Vuelve mañana para mantener la racha!",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
    }
}

/**
 * Datos del "juego estrella": el minijuego jugable en el que el usuario obtiene su
 * mejor rendimiento, con las cifras que lo respaldan. Es un modelo de presentación
 * (solo vive para pintar [StarGameCard]), no de dominio.
 *
 * @property game metadatos del juego (título, categoría → icono/color).
 * @property avgAccuracy precisión media del jugador en ese juego (0–100).
 * @property bestScore mejor puntuación conseguida.
 * @property playCount nº de partidas registradas (usado como criterio de desempate).
 */
private data class StarGame(
    val game: GameInfo,
    val avgAccuracy: Double,
    val bestScore: Int,
    val playCount: Int,
)

/**
 * Elige el "juego estrella" a partir del historial local: aquel **juego jugable del
 * catálogo** con mayor precisión media. Ante empate, gana el más jugado y luego el
 * de mejor score, para que la elección sea estable y refleje dominio real.
 *
 * Se ignoran las filas de juegos no catalogados o aún no jugables (roadmap), que no
 * tendrían pantalla a la que enviar al usuario.
 *
 * @return el juego destacado, o null si el historial no tiene partidas jugables.
 */
private fun findStarGame(history: List<GameProgress>): StarGame? =
    history
        .groupBy { it.gameId }
        .mapNotNull { (gameId, runs) ->
            val info = GameCatalog.games.firstOrNull { it.id == gameId && it.playable }
                ?: return@mapNotNull null
            StarGame(
                game = info,
                avgAccuracy = runs.map { it.accuracyPercentage }.average(),
                bestScore = runs.maxOf { it.score },
                playCount = runs.size,
            )
        }
        .maxWithOrNull(compareBy({ it.avgAccuracy }, { it.playCount }, { it.bestScore }))

/**
 * Tarjeta **"Tu juego estrella"**: rellena la Home cuando el objetivo diario ya está
 * cumplido, reforzando el dominio del jugador y reenganchándolo. Toma el color de
 * identidad de la categoría del juego ([GameCategory.accent]) para el halo del icono,
 * el borde y el CTA, manteniendo la coherencia visual del catálogo.
 *
 * @param star datos del juego destacado (ver [findStarGame]).
 * @param onPlay abre de nuevo ese juego.
 */
@Composable
private fun StarGameCard(star: StarGame, onPlay: () -> Unit) {
    val accent = star.game.category.accent
    val shape = RoundedCornerShape(28.dp)

    // Mismo lenguaje neón que [TrainingCompleteCard]: borde que late en opacidad y
    // halo que respira, pero teñidos con el color de la categoría para mantener la
    // identidad visual del juego destacado.
    val transition = rememberInfiniteTransition(label = "starGame")
    val borderAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "borderAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .softGlow(color = accent, shape = shape, maxElevation = 24.dp)
            .clip(shape)
            .background(LogicColors.SurfaceDark)
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = borderAlpha),
                        accent.copy(alpha = borderAlpha * 0.6f),
                    ),
                ),
                shape = shape,
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Rótulo con estrella ámbar: "destacado" universal, sin depender del color
        // de la categoría (que puede ser frío) para leerse como logro.
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonIcon(icon = KortexIcons.Star, tint = LogicColors.Amber, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "TU JUEGO ESTRELLA",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = LogicColors.OnDarkMuted,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icono de la categoría sobre un chip tenue de su propio color.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = star.game.category.icon, tint = accent, size = 28.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    star.game.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    maxLines = 1,
                )
                Text(
                    "${star.avgAccuracy.roundToInt()}% precisión · mejor ${star.bestScore}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                    maxLines = 1,
                )
            }
        }

        // CTA teñido con el color de la categoría: acción clara "vuelve a jugarlo".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.7f))))
                .bounceClick(onClick = onPlay)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Jugar de nuevo",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LogicColors.BackgroundDark,
            )
            Spacer(Modifier.width(8.dp))
            NeonIcon(icon = KortexIcons.Play, tint = LogicColors.BackgroundDark, size = 18.dp, glow = false)
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
