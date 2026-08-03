package com.kortexgames.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.game.daily.DailyGoalState
import com.kortexgames.app.game.daily.DailyMissionGame
import com.kortexgames.app.game.daily.TrainingDay
import com.kortexgames.app.game.daily.TrainingDayStatus
import com.kortexgames.app.game.daily.calculateStreakDays
import com.kortexgames.app.game.daily.weeklyTrainingDays
import com.kortexgames.app.ui.components.CategoryMotifSurface
import com.kortexgames.app.ui.components.GameMotifIcon
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.alphaIf
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.dashedBorder
import com.kortexgames.app.ui.components.pulse
import com.kortexgames.app.ui.components.softGlow
import com.kortexgames.app.ui.navigation.Routes
import kotlin.math.roundToInt

/**
 * Pantalla de Inicio: el **centro de motivación**. Saludo con avatar, la tarjeta de
 * entrenamiento ([TrainingCard]: racha + semana + progreso de hoy + misión, con el CTA
 * de jugar), el juego estrella del jugador y una fila de categorías con iconos neón.
 *
 * @param onQuickPlay abre una partida rápida (quick play).
 * @param onSeeGames lleva al catálogo completo.
 * @param onOpenGame abre un juego concreto por su ruta de navegación.
 * @param onOpenAuth abre el login (mostrado como CTA solo a invitados).
 *   juego estrella: ahí es donde vive el histórico completo del jugador).
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
    // La tira de la semana se recalcula solo cuando cambia el historial: recorre todas
    // las partidas para agrupar por día y no debe rehacerse en cada recomposición.
    val week = remember(history) { weeklyTrainingDays(history) }

    // Sesión reactiva: la fuente de verdad para el saludo y el CTA de login.
    val session by graph.authRepository.sessionState.collectAsStateWithLifecycle()
    val isGuest = session !is AuthState.Authenticated

    // Nombre amigable: el modo invitado no tiene nombre → saludo genérico cálido.
    val playerName = if (isGuest) "Jugador" else "Campeón"

    // Los intersticiales los gobierna el AdManager y los presenta un colector único en
    // App.kt (se muestran en un breakpoint, no en Home): esta pantalla ya no los toca.

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            HomeHeader(name = playerName)

            // Invitado: banner que invita a iniciar sesión para guardar en la nube.
            if (isGuest) {
                SignInBanner(onClick = onOpenAuth)
            }

            // Tarjeta de entrenamiento: racha + semana + progreso de hoy + misión. Se
            // muestra siempre (también con el objetivo cumplido, donde cambia a estado
            // de celebración) para que la racha no desaparezca de la Home al completarlo.
            TrainingCard(
                goal = dailyGoal,
                streakDays = streak,
                week = week,
                onPlay = onQuickPlay,
                onOpenGame = { game ->
                    Routes.gameRoute(game.id)?.let(onOpenGame) ?: onSeeGames()
                },
            )

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

/**
 * Cabecera: avatar con anillo neón + saludo.
 *
 * La racha vive ahora en [TrainingCard] (que la muestra con la tira de la semana): no
 * se repite aquí para no cantar dos veces el mismo dato en la misma pantalla.
 */
@Composable
private fun HomeHeader(name: String) {
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
    }
}

/**
 * Color de identidad del bloque de entrenamiento: el naranja de la racha
 * ([LogicColors.StreakOrange]). Se usa para el fondo, el emblema, los días
 * cumplidos y la barra de hoy, de modo que toda la tarjeta se lea como **una sola
 * cosa** ("tu entrenamiento") frente al verde, que en esta app significa siempre
 * *acción* y aquí queda reservado al botón de jugar.
 */
private val TrainingAccent = LogicColors.StreakOrange

/**
 * Tarjeta de **entrenamiento** de la Home: reúne en un solo bloque la racha
 * (icono + días seguidos + tira de la semana) y el entrenamiento de hoy (progreso
 * sobre la meta + los juegos de la misión).
 *
 * ## Decisiones visuales
 *
 * - **Solo fondo, sin borde.** Toma el mismo lenguaje que las tarjetas del catálogo
 *   ([com.kortexgames.app.ui.games.GameListScreen]): degradado vertical del acento
 *   hacia [LogicColors.SurfaceDark] y una sombra corta que la separa del fondo. Nada
 *   de trazos ni halos en el contorno: la tarjeta ya es el elemento más grande de la
 *   pantalla y no necesita marco para destacar.
 * - **Fondo liso, sin motivo.** El contenido ya es denso (número grande, siete
 *   puntos, barra y tres celdas de juego), así que cualquier dibujo detrás compite
 *   con él: aquí el degradado solo tiñe, no ilustra.
 * - **Un único elemento en bucle**, el botón de jugar (CLAUDE.md §9.4): ni el fondo
 *   ni el borde laten.
 *
 * Se muestra siempre, también con el objetivo cumplido: en ese caso el botón de
 * jugar cede su sitio a un sello de completado y la barra pasa al verde de acierto,
 * pero la racha y la semana siguen visibles (son el gancho de retención).
 *
 * @param goal estado del objetivo (progreso + misión del día).
 * @param streakDays días consecutivos entrenando ([calculateStreakDays]).
 * @param week los 7 días de la semana en curso ([weeklyTrainingDays]).
 * @param onPlay abre una partida rápida (botón circular).
 * @param onOpenGame abre un juego concreto de la misión al tocar su celda.
 */
@Composable
private fun TrainingCard(
    goal: DailyGoalState,
    streakDays: Int,
    week: List<TrainingDay>,
    onPlay: () -> Unit,
    onOpenGame: (GameInfo) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // clip = false: la sombra se dibuja FUERA de la silueta, como en las
            // tarjetas del catálogo; recortarla la eliminaría por completo.
            .shadow(elevation = 8.dp, shape = shape, clip = false)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(TrainingAccent.copy(alpha = 0.21f), LogicColors.SurfaceDark),
                ),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StreakHeader(streakDays = streakDays, isComplete = goal.isComplete, onPlay = onPlay)
        WeekStrip(week = week)
        // Separador tenue: el escalón entre "tu racha" (arriba) y "hoy" (abajo).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LogicColors.OnDarkMuted.copy(alpha = 0.18f)),
        )
        TodayProgress(goal = goal)
        if (goal.mission.isNotEmpty()) {
            MissionPanel(mission = goal.mission, onOpenGame = onOpenGame)
        }
    }
}

/**
 * Cabecera de [TrainingCard]: rótulo, llama de la racha, número grande de días y, a
 * la derecha, el botón de jugar (o el sello de completado si ya se cumplió la meta).
 *
 * El rótulo va en su **propia fila a todo el ancho** y no junto al número: en
 * pantallas de 360dp no cabe "RACHA DE ENTRENAMIENTO" en la columna que queda entre
 * la llama y el botón, y acababa recortado con puntos suspensivos.
 */
@Composable
private fun StreakHeader(streakDays: Int, isComplete: Boolean, onPlay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "RACHA DE ENTRENAMIENTO",
            style = MaterialTheme.typography.labelMedium,
            color = TrainingAccent,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Emblema de la racha: la llama sobre una placa tenue de su propio color,
            // el mismo patrón de "icono + placa" que abre las filas del catálogo.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TrainingAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = KortexIcons.Streak, tint = TrainingAccent, size = 28.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text("$streakDays", style = MaterialTheme.typography.displayLarge, color = LogicColors.OnDark)
            Spacer(Modifier.width(8.dp))
            Text(
                if (streakDays == 1) "día seguido" else "días seguidos",
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            if (isComplete) CompletedBadge() else PlayNowFab(onClick = onPlay)
        }
    }
}

/**
 * Sello de **objetivo cumplido**: sustituye al [PlayNowFab] cuando ya no hay nada que
 * pedirle al jugador hoy. Es un estado, no una acción: ni late ni es pulsable, para
 * que el único foco animado de la pantalla siga siendo el CTA (CLAUDE.md §9.4).
 */
@Composable
private fun CompletedBadge() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(LogicColors.Success.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.Check,
            tint = LogicColors.Success,
            size = 30.dp,
            contentDescription = "Entrenamiento de hoy completado",
        )
    }
}

/**
 * Tira de los 7 días de la semana (lunes→domingo): inicial arriba y punto de estado
 * abajo. Es el "calendario de la racha" y lo que hace visible el hueco que el jugador
 * no quiere dejar mañana.
 *
 * Cada día ocupa el mismo `weight`, así la fila reparte el ancho sola en cualquier
 * pantalla sin que haya que calcular tamaños de punto ni separaciones.
 */
@Composable
private fun WeekStrip(week: List<TrainingDay>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        week.forEach { day ->
            DayDot(day = day, modifier = Modifier.weight(1f))
        }
    }
}

/** Diámetro del punto de un día de la semana en [WeekStrip]. */
private val DayDotSize = 32.dp

/**
 * Punto de un día concreto de la semana. Los cuatro estados de [TrainingDayStatus] se
 * distinguen por relleno Y por forma (no solo por color), para que se lean también
 * con poca luz o con dificultades de percepción del color:
 *  - cumplido → disco lleno del acento con un check oscuro dentro,
 *  - hoy → contorno **punteado** (el mismo lenguaje de "hueco por completar" que usan
 *    las celdas pendientes de la misión) con el centro marcado,
 *  - fallado / futuro → disco apagado; el futuro, además, más tenue.
 */
@Composable
private fun DayDot(day: TrainingDay, modifier: Modifier = Modifier) {
    val statusText = when (day.status) {
        TrainingDayStatus.DONE -> "entrenado"
        TrainingDayStatus.TODAY -> "hoy, pendiente"
        TrainingDayStatus.MISSED -> "sin entrenar"
        TrainingDayStatus.PENDING -> "por llegar"
    }
    Column(
        modifier = modifier.semantics { contentDescription = "${day.name}: $statusText" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            day.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (day.status == TrainingDayStatus.DONE) TrainingAccent else LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .size(DayDotSize)
                .then(
                    // El `clip` va DENTRO de cada rama, no antes del `when`: el borde
                    // punteado se dibuja centrado en el contorno, así que recortarlo a
                    // la silueta se comería la mitad exterior del trazo.
                    when (day.status) {
                        TrainingDayStatus.DONE -> Modifier
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(LogicColors.Amber, TrainingAccent)))
                        // cornerRadius = radio del punto: el trazo punteado recorre el
                        // círculo completo en vez de un rectángulo redondeado.
                        TrainingDayStatus.TODAY -> Modifier.dashedBorder(
                            color = TrainingAccent,
                            cornerRadius = DayDotSize / 2,
                            dashLength = 4.dp,
                        )
                        TrainingDayStatus.MISSED -> Modifier
                            .clip(CircleShape)
                            .background(LogicColors.SurfaceVariantDark)
                        TrainingDayStatus.PENDING -> Modifier
                            .clip(CircleShape)
                            .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.5f))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (day.status) {
                TrainingDayStatus.DONE -> NeonIcon(
                    icon = KortexIcons.CheckMark,
                    tint = LogicColors.BackgroundDark,
                    size = 18.dp,
                    glow = false,
                )
                // El resto son "huecos": un punto central pequeño, encendido hoy y
                // apagado los demás días.
                else -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (day.status == TrainingDayStatus.TODAY) {
                                TrainingAccent
                            } else {
                                LogicColors.OnDarkMuted.copy(alpha = 0.5f)
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Bloque "ENTRENAMIENTO DE HOY": contador `hechos/meta` y barra de progreso. La barra
 * se llena con una animación corta al entrar en la pantalla (y cada vez que sube el
 * progreso), que es el "premio" inmediato tras terminar una partida.
 */
@Composable
private fun TodayProgress(goal: DailyGoalState) {
    val progress by animateFloatAsState(
        targetValue = goal.progress,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "todayProgress",
    )
    val barColors = if (goal.isComplete) LogicGradients.success else LogicGradients.reward

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ENTRENAMIENTO DE HOY",
                style = MaterialTheme.typography.labelMedium,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // El rótulo se queda con todo el hueco libre (el contador se mide
                // primero): así el contador queda anclado al borde derecho.
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${goal.completed}/${goal.target}",
                style = MaterialTheme.typography.labelLarge,
                color = if (goal.isComplete) LogicColors.Success else LogicColors.Amber,
                fontWeight = FontWeight.Black,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(LogicColors.SurfaceVariantDark),
        ) {
            // Solo se dibuja el relleno si hay algo que mostrar: `fillMaxWidth(0f)`
            // pintaría una franja de ancho cero con su propio recorte, gasto inútil.
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(barColors)),
                )
            }
        }
        if (goal.isComplete) {
            Text(
                "¡Completado! Vuelve mañana para mantener la racha.",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.Success,
            )
        }
    }
}

/**
 * Botón circular de **jugar ahora**: el acento más llamativo de la Home. Vive en la
 * cabecera de [TrainingCard] y combina [softGlow] (brillo verde continuo), [pulse]
 * (latido) y [bounceClick] (rebote al tocar). Es el ÚNICO elemento con animación en
 * bucle de la pantalla (CLAUDE.md §9.4).
 */
@Composable
private fun PlayNowFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .pulse()
            .softGlow(color = LogicColors.NeonGreen, shape = CircleShape, maxElevation = 24.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(LogicGradients.play))
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.Play,
            tint = LogicColors.BackgroundDark,
            size = 28.dp,
            glow = false,
            contentDescription = "Jugar ahora",
        )
    }
}

/**
 * Panel de la **misión de hoy**: los juegos del día sobre una placa oscura que los
 * separa del resto de la tarjeta. La placa no es decorativa: rebaja el tinte naranja
 * del degradado justo detrás de las celdas, para que cada juego se lea con el color
 * de SU categoría y no sobre un fondo cálido que se lo desvirtúa.
 */
@Composable
private fun MissionPanel(mission: List<DailyMissionGame>, onOpenGame: (GameInfo) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(LogicColors.BackgroundDark.copy(alpha = 0.55f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        mission.forEach { item ->
            MissionGameCell(
                item = item,
                onClick = { onOpenGame(item.game) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Fracción del ancho disponible que ocupa el recuadro de un juego de la misión: el
 * arte manda menos que las cifras del entrenamiento, así que la celda se queda un
 * 15% por debajo de su columna en vez de llenarla.
 */
private const val MissionCellScale = 0.85f

/**
 * Celda de un juego de la misión: recuadro con el **arte del propio juego** y su
 * título debajo. Toma el color de identidad de su categoría ([GameCategory.accent])
 * para el marco, el arte y el título, coherente con el resto del catálogo.
 *
 * El icono es siempre el del juego (su [GameInfo.motif], o el de la categoría como
 * respaldo) también cuando ya está hecho: sustituirlo por un check haría que las tres
 * celdas se volvieran indistinguibles al completarlas. El estado lo cuenta el marco
 * (sólido vs punteado) y una insignia de check en la esquina.
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
                // 85% del ancho de su columna: el recuadro encoge pero la fila sigue
                // repartiéndose a partes iguales, así los tres quedan centrados bajo
                // sus títulos y con el mismo aire entre ellos.
                .fillMaxWidth(MissionCellScale)
                .aspectRatio(1f)
                .clip(shape)
                .then(
                    // Hecho → relleno + marco sólido en el acento; pendiente → marco
                    // punteado que se lee como "hueco por completar".
                    if (item.isDone) {
                        Modifier
                            .background(accent.copy(alpha = 0.18f))
                            .border(2.dp, accent, shape)
                    } else {
                        Modifier.dashedBorder(color = accent.copy(alpha = 0.55f), cornerRadius = 18.dp)
                    },
                )
                .bounceClick(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val motif = item.game.motif
            if (motif != null) {
                // El motivo fija sus propias opacidades a partir del color, así que
                // "apagar" el pendiente se hace con alpha de capa, no tiñendo el accent.
                GameMotifIcon(
                    motif = motif,
                    accent = accent,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .alphaIf(!item.isDone, 0.7f),
                )
            } else {
                NeonIcon(
                    icon = item.game.category.icon,
                    tint = if (item.isDone) accent else accent.copy(alpha = 0.7f),
                    size = 28.dp,
                    glow = item.isDone,
                )
            }
            if (item.isDone) {
                // Insignia sobre el arte: confirma "hecho" sin taparlo. El disco oscuro
                // detrás la despega del motivo, que puede ser claro.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(LogicColors.BackgroundDark),
                    contentAlignment = Alignment.Center,
                ) {
                    NeonIcon(
                        icon = KortexIcons.CheckMark,
                        tint = accent,
                        size = 14.dp,
                        glow = false,
                        contentDescription = "Completado",
                    )
                }
            }
        }
        Text(
            item.game.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.isDone) accent else LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            // Alto fijo de 2 líneas: las tres celdas quedan alineadas aunque los
            // títulos tengan distinto largo.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
 * Tarjeta **"Tu juego estrella"**: rellena la Home reforzando el dominio del jugador
 * y reenganchándolo. Toma el color de identidad de la categoría del juego
 * ([GameCategory.accent]) para el fondo, el CTA y el ranking de abajo.
 *
 * ## Solo fondo, sin borde
 *
 * Mismo criterio que [TrainingCard]: degradado vertical del acento hacia
 * [LogicColors.SurfaceDark] + una sombra corta, sin trazo ni halo pulsante en el
 * contorno. Antes llevaba un borde que latía en opacidad; se retira para que las
 * tarjetas grandes de la Home compartan un único lenguaje de fondo.
 *
 * @param star datos del juego destacado (primer puesto de [rankedPlayedGames]).
 * @param others siguientes juegos más jugados (hasta 3), listados como ranking
 *   debajo del CTA con una barra que muestra su peso frente al juego estrella.
 * @param onPlay abre de nuevo el juego estrella.
 * @param onPlayOther abre uno de los otros juegos del ranking.
 * @param onViewStats abre las estadísticas completas del jugador (Perfil).
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // clip = false: la sombra se dibuja FUERA de la silueta, igual que en
            // [TrainingCard] y en las filas del catálogo.
            .shadow(elevation = 8.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), LogicColors.SurfaceDark)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Insignia "#1 más jugado": chip con borde fino en el acento, más discreta
        // que el rótulo anterior pero igual de clara sobre el fondo ya teñido.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                "#1 MÁS JUGADO",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Black,
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
                    style = MaterialTheme.typography.headlineMedium,
                    color = LogicColors.OnDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${star.playCount} partidas · mejor ${star.bestScore}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // CTA "seguir entrenando" + botón cuadrado de estadísticas: la acción principal
        // reclama la mayor parte del ancho, el secundario queda a su lado con el mismo
        // acento pero solo de contorno, para no competir en peso visual con el CTA.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.7f))))
                    .bounceClick(onClick = onPlay)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeonIcon(icon = KortexIcons.Play, tint = LogicColors.BackgroundDark, size = 18.dp, glow = false)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Seguir entrenando",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = LogicColors.BackgroundDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // "Otros juegos que te gustan": ranking de los siguientes más jugados, dentro
        // de la misma tarjeta para invitar a variar sin salir del contexto "tus favoritos".
        if (others.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LogicColors.OnDarkMuted.copy(alpha = 0.18f)),
            )
            OtherGamesRanking(star = star, others = others, onPlay = onPlayOther)
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

/**
 * Ranking de "otros juegos que te gustan": una fila numerada por juego (2, 3, 4…)
 * con su icono de categoría, título y una **barra de peso** proporcional a su nº de
 * partidas frente al del juego estrella (el `100%` del ranking). La barra es lo que
 * convierte una simple lista en algo que se lee de un vistazo: "cuánto juegas esto
 * comparado con tu favorito", sin necesidad de comparar números.
 */
@Composable
private fun OtherGamesRanking(star: StarGame, others: List<StarGame>, onPlay: (StarGame) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Otros juegos que te gustan",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
        )
        others.forEachIndexed { index, other ->
            OtherGameRankRow(
                rank = index + 2, // el #1 ya lo ocupa el juego estrella de arriba.
                entry = other,
                maxPlayCount = star.playCount,
                onClick = { onPlay(other) },
            )
        }
    }
}

/**
 * Una fila del ranking: nº de puesto, icono de categoría en círculo, título y barra
 * de peso, con el nº de partidas a la derecha. Todo teñido con el acento de LA
 * CATEGORÍA DE ESE JUEGO (no el del juego estrella), para que el ranking siga
 * identificando cada juego por su propio color aunque conviva en una sola tarjeta.
 *
 * @param rank puesto en el ranking (2, 3, 4…: el 1 es el juego estrella de arriba).
 * @param maxPlayCount partidas del juego estrella; denominador de la barra de peso.
 */
@Composable
private fun OtherGameRankRow(
    rank: Int,
    entry: StarGame,
    maxPlayCount: Int,
    onClick: () -> Unit,
) {
    val accent = entry.game.category.accent
    // Mínimo 6% de barra visible: con 0 partidas relativas la barra desaparecería y
    // el juego se leería como "sin datos" en vez de "el que menos se juega".
    val weight = (entry.playCount.toFloat() / maxOf(maxPlayCount, 1).toFloat()).coerceIn(0.06f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bounceClick(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(18.dp),
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.5.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(icon = entry.game.category.icon, tint = accent, size = 20.dp, glow = false)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.game.title,
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(LogicColors.SurfaceVariantDark),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(weight)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.55f)))),
                )
            }
        }
        Text(
            "${entry.playCount}",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
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
