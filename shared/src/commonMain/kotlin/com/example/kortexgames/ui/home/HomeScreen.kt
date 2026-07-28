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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameCategory
import com.example.kortexgames.game.GameInfo
import com.example.kortexgames.game.daily.DailyGoalState
import com.example.kortexgames.game.daily.DailyMissionGame
import com.example.kortexgames.game.daily.calculateStreakDays
import com.example.kortexgames.ui.components.CategoryMotifSurface
import com.example.kortexgames.ui.components.CircularProgressRing
import com.example.kortexgames.ui.components.GameMotifIcon
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.dashedBorder
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

    // Los intersticiales los gobierna el AdManager y los presenta un colector único en
    // App.kt (se muestran en un breakpoint, no en Home): esta pantalla ya no los toca.

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    onPlay = onQuickPlay,
                    onOpenGame = { game ->
                        Routes.gameRoute(game.id)?.let(onOpenGame) ?: onSeeGames()
                    },
                    onClaim = {
                        scope.launch {
                            if (graph.dailyGoalManager.claimReward()) {
                                snackbar.showSnackbar("¡Recompensa diaria reclamada!")
                            }
                        }
                    },
                )
            }

            // Juego estrella: el más jugado. Los siguientes más jugados (excluido el
            // estrella) alimentan "Otros juegos que te gustan" en la misma tarjeta.
            val rankedGames = remember(history) { rankedPlayedGames(history) }
            val starGame = rankedGames.firstOrNull()
            if (starGame != null) {
                StarGameCard(
                    star = starGame,
                    others = rankedGames.drop(1).take(3),
                    onPlay = { Routes.gameRoute(starGame.game.id)?.let(onOpenGame) },
                    onPlayOther = { other -> Routes.gameRoute(other.game.id)?.let(onOpenGame) },
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
 * Tarjeta del objetivo diario con [CircularProgressRing] que se llena al entrar, la
 * fila **"Tu misión de hoy"** con los juegos del día ([DailyMissionRow]) y el botón
 * circular de jugar ([PlayNowFab]) anclado a la esquina superior derecha del anillo,
 * **sobresaliendo** de su borde para destacar la acción (acento escaso).
 *
 * @param goal estado del objetivo (progreso + misión del día).
 * @param onPlay abre una partida rápida (botón circular).
 * @param onOpenGame abre un juego concreto de la misión al tocar su celda.
 * @param onClaim reclama la recompensa cuando la misión está completa.
 */
@Composable
private fun DailyGoalCard(
    goal: DailyGoalState,
    onPlay: () -> Unit,
    onOpenGame: (GameInfo) -> Unit,
    onClaim: () -> Unit,
) {
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

        // El botón "jugar ahora" se ancla a la esquina superior derecha del anillo,
        // sobresaliendo de su borde: el CTA nace visualmente del propio progreso.
        Box(contentAlignment = Alignment.Center) {
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
            PlayNowFab(
                onClick = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 16.dp, y = 16.dp),
            )
        }

        // "Tu misión de hoy": los juegos del día con su estado (hecho/pendiente).
        if (goal.mission.isNotEmpty()) {
            DailyMissionRow(mission = goal.mission, onOpenGame = onOpenGame)
        }
    }
}

/**
 * Botón circular de **jugar ahora**: el acento más llamativo de la Home. Va anclado
 * a la esquina de la tarjeta de entrenamiento y combina [softGlow] (brillo verde
 * continuo), [pulse] (latido) y [bounceClick] (rebote al tocar). Es el ÚNICO
 * elemento con animación en bucle de la pantalla (CLAUDE.md §9.4).
 */
@Composable
private fun PlayNowFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .pulse()
            .softGlow(color = LogicColors.NeonGreen, shape = CircleShape, maxElevation = 24.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(LogicGradients.play))
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = KortexIcons.Play, tint = LogicColors.BackgroundDark, size = 30.dp, glow = false)
    }
}

/**
 * Fila **"Tu misión de hoy"**: los juegos del día en celdas cuadradas iguales. Cada
 * celda muestra su estado: **hecha** (relleno tenue + check en el color de la
 * categoría) o **pendiente** (borde punteado + icono de la categoría). Tocar una
 * celda abre ese juego para completarlo.
 */
@Composable
private fun DailyMissionRow(mission: List<DailyMissionGame>, onOpenGame: (GameInfo) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Tu misión de hoy",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            mission.forEach { item ->
                MissionGameCell(
                    item = item,
                    onClick = { onOpenGame(item.game) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Celda de un juego de la misión: cuadrado con el estado del día y el título debajo.
 * Toma el color de identidad de la categoría del juego ([GameCategory.accent]) para
 * el marco y el icono, coherente con el resto del catálogo.
 *
 * @param item juego de la misión + si ya se jugó hoy.
 * @param onClick abre ese juego.
 */
@Composable
private fun MissionGameCell(
    item: DailyMissionGame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = item.game.category.accent
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(
                    // Hecho → relleno + marco sólido en el acento; pendiente → marco
                    // punteado que se lee como "hueco por completar".
                    if (item.isDone) {
                        Modifier
                            .background(accent.copy(alpha = 0.14f))
                            .border(2.dp, accent, shape)
                    } else {
                        Modifier.dashedBorder(color = accent.copy(alpha = 0.55f), cornerRadius = 18.dp)
                    },
                )
                .bounceClick(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (item.isDone) {
                NeonIcon(icon = KortexIcons.Check, tint = accent, size = 30.dp)
            } else {
                NeonIcon(
                    icon = item.game.category.icon,
                    tint = accent.copy(alpha = 0.7f),
                    size = 28.dp,
                    glow = false,
                )
            }
        }
        Text(
            item.game.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.isDone) accent else LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
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
 * Ordena los **juegos jugables del catálogo** presentes en el historial local por
 * afinidad: primero el más jugado (nº de partidas) y, ante empate, el de mayor
 * precisión media y luego el de mejor score, para que el ranking sea estable y
 * refleje gusto real. El primero es el "juego estrella"; los siguientes alimentan
 * "Otros juegos que te gustan" en la misma tarjeta.
 *
 * Se ignoran las filas de juegos no catalogados o aún no jugables (roadmap), que no
 * tendrían pantalla a la que enviar al usuario.
 *
 * @return ranking descendente, vacío si el historial no tiene partidas jugables.
 */
private fun rankedPlayedGames(history: List<GameProgress>): List<StarGame> =
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
        .sortedWith(
            compareByDescending<StarGame> { it.playCount }
                .thenByDescending { it.avgAccuracy }
                .thenByDescending { it.bestScore },
        )

/**
 * Tarjeta **"Tu juego estrella"**: rellena la Home cuando el objetivo diario ya está
 * cumplido, reforzando el dominio del jugador y reenganchándolo. Toma el color de
 * identidad de la categoría del juego ([GameCategory.accent]) para el halo del icono,
 * el borde y el CTA, manteniendo la coherencia visual del catálogo.
 *
 * @param star datos del juego destacado (primer puesto de [rankedPlayedGames]).
 * @param others siguientes juegos más jugados (hasta 3), para "Otros juegos que te gustan".
 * @param onPlay abre de nuevo el juego estrella.
 * @param onPlayOther abre uno de los otros juegos favoritos.
 */
@Composable
private fun StarGameCard(
    star: StarGame,
    others: List<StarGame>,
    onPlay: () -> Unit,
    onPlayOther: (StarGame) -> Unit,
) {
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
            // Arte "héroe" del juego (mismo que su pantalla de intro); si aún no tiene,
            // cae al icono de la categoría sobre un chip tenue de su propio color.
            GameThumbnail(game = star.game, accent = accent, size = 56.dp, cornerRadius = 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    star.game.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    maxLines = 1,
                )
                Text(
                    "${star.playCount} partidas · ${star.avgAccuracy.roundToInt()}% precisión",
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

        // "Otros juegos que te gustan": los siguientes más jugados, dentro de la
        // misma tarjeta para invitar a variar sin salir del contexto "tus favoritos".
        if (others.isNotEmpty()) {
            OtherGamesRow(games = others, onPlay = onPlayOther)
        }
    }
}

/**
 * Miniatura cuadrada de un juego para las tarjetas de la Home. Reutiliza el **motivo
 * del juego** ([GameInfo.motif]) —el mismo que pinta su tarjeta del catálogo y su
 * recuadro héroe de la intro, dibujado centrado con [GameMotifIcon]—; si el juego aún
 * no tiene motivo, cae al icono de su categoría sobre un chip tenue del color de
 * acento, manteniendo la coherencia visual del catálogo.
 */
@Composable
private fun GameThumbnail(game: GameInfo, accent: Color, size: Dp, cornerRadius: Dp) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        val motif = game.motif
        if (motif != null) {
            GameMotifIcon(motif = motif, accent = accent, modifier = Modifier.fillMaxSize())
        } else {
            NeonIcon(icon = game.category.icon, tint = accent, size = size * 0.5f)
        }
    }
}

/** Fila de hasta 3 mini-tarjetas con los siguientes juegos más jugados. */
@Composable
private fun OtherGamesRow(games: List<StarGame>, onPlay: (StarGame) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Otros juegos que te gustan",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            games.forEach { other ->
                OtherGameMiniCard(star = other, onClick = { onPlay(other) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Mini-tarjeta de un "otro juego": arte héroe + título + nº de partidas, teñida con el color de su categoría. */
@Composable
private fun OtherGameMiniCard(star: StarGame, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = star.game.category.accent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .bounceClick(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GameThumbnail(game = star.game, accent = accent, size = 44.dp, cornerRadius = 12.dp)
        Text(
            star.game.title,
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDark,
            textAlign = TextAlign.Center,
            maxLines = 2,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${star.playCount}x",
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
        )
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
