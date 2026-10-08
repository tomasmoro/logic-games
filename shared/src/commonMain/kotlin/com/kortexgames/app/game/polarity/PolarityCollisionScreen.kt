package com.kortexgames.app.game.polarity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.SpaceBackdrop
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.polarity_banner_shower_subtitle
import kortexgames.shared.generated.resources.polarity_banner_shower_title
import kortexgames.shared.generated.resources.polarity_banner_wave_caught
import kortexgames.shared.generated.resources.polarity_banner_wave_colors
import kortexgames.shared.generated.resources.polarity_banner_wave_life
import kortexgames.shared.generated.resources.polarity_banner_wave_reward
import kortexgames.shared.generated.resources.polarity_banner_wave_title
import kortexgames.shared.generated.resources.polarity_hint
import kortexgames.shared.generated.resources.polarity_hud_caught
import kortexgames.shared.generated.resources.polarity_hud_score
import kortexgames.shared.generated.resources.polarity_hud_seconds
import kortexgames.shared.generated.resources.polarity_hud_shower_in
import kortexgames.shared.generated.resources.polarity_hud_shower_now
import kortexgames.shared.generated.resources.polarity_hud_wave
import kortexgames.shared.generated.resources.polarity_intro_description
import kortexgames.shared.generated.resources.polarity_life
import kortexgames.shared.generated.resources.polarity_life_lost
import kortexgames.shared.generated.resources.polarity_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.kortexgames.app.ui.components.drawEdgeFlash
import com.kortexgames.app.ui.components.rememberBoardClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Paleta de sectores del disco, en el orden en que se van estrenando: la partida
 * arranca con los [PolarityConfig.INITIAL_COLOR_COUNT] primeros y cada lluvia de
 * meteoros añade el siguiente hasta [PolarityConfig.MAX_COLOR_COUNT].
 *
 * El orden no es casual: los tres primeros (cian, verde, ámbar) son los que más se
 * distinguen entre sí a velocidad de juego, así que el jugador nuevo nunca falla por
 * no poder separar dos colores. Los parecidos entran después, cuando ya domina el
 * gesto y distinguirlos ES parte de la dificultad.
 */
internal val SectorPalette = listOf(
    LogicColors.NeonCyan,
    LogicColors.NeonGreen,
    LogicColors.Amber,
    LogicColors.Violet,
    LogicColors.Magenta,
)

/**
 * Pantalla de Atracción Geométrica.
 *
 * Implementa un `Canvas` full-screen y controla el círculo central con drag libre:
 * cada movimiento calcula `atan2` respecto al centro para convertir gesto en rotación.
 *
 * La partida es infinita: no hay reloj de fin, solo vidas. El HUD refleja eso —
 * corazones y oleada en curso en vez de cuenta atrás de partida— y la única cuenta
 * atrás visible es la del siguiente evento (la lluvia de meteoros).
 */
@Composable
fun PolarityCollisionScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: PolarityCollisionViewModel = viewModel {
        PolarityCollisionViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game
    val title = stringResource(Res.string.polarity_title)

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro (evita,
    // de paso, que el bucle de física corra bajo la intro).
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.polarity,
            tutorial = PolarityTutorial.tutorial,
            title = title,
            motif = GameMotif.POLARITY_SECTORS,
            description = stringResource(Res.string.polarity_intro_description),
            accent = CategoryPalette.SpatialVision,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.POLARITY_COLLISION)
                vm.onIntent(PolarityCollisionIntent.Start)
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDragAngle by remember { mutableStateOf<Float?>(null) }
    // Reloj único de las animaciones de la escena (campo de gravedad, corona del disco, arcos
    // de las magnéticas). Solo se congela en PAUSA. Al terminar la partida sigue corriendo a
    // propósito: la última vida se pierde en el mismo instante en que acaba, y con el reloj
    // parado el disco se quedaba teñido de rojo, congelado, detrás del diálogo de resultado.
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)

    // Instantes de la última vida perdida y ganada: de su "edad" salen la sacudida, el tinte
    // rojo del disco y los destellos de borde. El motor solo publica cuántas vidas hay.
    var lifeLostAt by remember { mutableFloatStateOf(-10f) }
    var lifeGainedAt by remember { mutableFloatStateOf(-10f) }
    var lastLives by remember { mutableIntStateOf(game.lives) }
    LaunchedEffect(game.lives) {
        val before = lastLives
        lastLives = game.lives
        when {
            game.lives < before -> lifeLostAt = clock.peek()
            // Partida nueva (las vidas vuelven al máximo con el marcador a cero) no es un premio.
            game.lives > before && game.score > 0 -> lifeGainedAt = clock.peek()
        }
    }

    // Bucle de juego robusto: sincroniza física al reloj de render del frame.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                vm.onIntent(PolarityCollisionIntent.Frame(frameNanos))
            }
        }
    }

    LaunchedEffect(viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            vm.onIntent(
                PolarityCollisionIntent.UpdateViewport(
                    widthPx = viewportSize.width.toFloat(),
                    heightPx = viewportSize.height.toFloat(),
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .onSizeChanged { viewportSize = it }
            .pointerInput(viewportSize) {
                // El gesto vive en toda la pantalla: no hace falta "tocar" el círculo.
                detectDragGestures(
                    onDragStart = { point ->
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        lastDragAngle = atan2(point.y - center.y, point.x - center.x)
                    },
                    onDragCancel = { lastDragAngle = null },
                    onDragEnd = { lastDragAngle = null },
                    onDrag = { change, _ ->
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        val current = atan2(change.position.y - center.y, change.position.x - center.x)
                        val previous = lastDragAngle ?: current
                        // Multiplicador de sensibilidad para que el giro responda más rápido.
                        val delta = normalizeAngle(current - previous) * ROTATION_SPEED_MULTIPLIER
                        vm.onIntent(PolarityCollisionIntent.RotateBy(delta))
                        lastDragAngle = current
                        change.consume()
                    },
                )
            },
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        // Colores activos: se recalculan solo cuando una lluvia añade sector, no en
        // cada frame de dibujo.
        val sectorPalette = remember(game.colorCount) { SectorPalette.take(game.colorCount) }

        val inShower = game.phase == PolarityPhase.SHOWER
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Sacudida al perder una vida: el golpe se siente en toda la escena.
                    val age = clock.seconds - lifeLostAt
                    if (age in 0f..SHAKE_SEC) {
                        translationX = sin(age * 70f) * SHAKE_AMPLITUDE.toPx() * (1f - age / SHAKE_SEC)
                    }
                },
        ) {
            val now = clock.seconds
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val hexRadius = min(size.width, size.height) * 0.18f

            // Fondo: todo cae hacia el disco. En la lluvia de meteoros acelera y vira a magenta.
            drawGravityField(
                center = center,
                discRadius = hexRadius,
                time = now,
                speed = if (inShower) 2.4f else 1f,
                tint = if (inShower) LogicColors.Magenta else CategoryPalette.SpatialVision,
            )

            // Marca de entrada de la partícula más cercana: la que hay que resolver ahora.
            val nearest = game.particles.minByOrNull { hypot(it.x - center.x, it.y - center.y) }
            if (nearest != null) {
                val distance = hypot(nearest.x - center.x, nearest.y - center.y)
                drawApproachMarker(
                    center = center,
                    discRadius = hexRadius,
                    angleRad = atan2(nearest.y - center.y, nearest.x - center.x),
                    color = sectorPalette[nearest.colorIndex % sectorPalette.size],
                    urgency = 1f - (distance - hexRadius) / (hexRadius * MARKER_RANGE),
                )
            }

            // Destello del sector que acaba de capturar: sale de los impactos del motor.
            val sectorFlash = FloatArray(sectorPalette.size)
            for (impact in game.impacts) {
                if (!impact.success) continue
                val index = impact.colorIndex % sectorPalette.size
                val amount = 1f - impact.ageMs.toFloat() / IMPACT_LIFETIME_MS.toFloat()
                if (amount > sectorFlash[index]) sectorFlash[index] = amount
            }
            val lostAge = now - lifeLostAt
            drawPolarityDisc(
                center = center,
                radius = hexRadius,
                rotationRad = game.rotationRad,
                colors = sectorPalette,
                time = now,
                sectorFlash = sectorFlash,
                hurt = if (lostAge in 0f..HURT_SEC) 1f - lostAge / HURT_SEC else 0f,
            )

            for (particle in game.particles) {
                drawNeonOrb(
                    particle = particle,
                    color = sectorPalette[particle.colorIndex % sectorPalette.size],
                    time = now,
                )
            }

            // Chispas de colisión: verdes/color de sector en acierto, rojas en fallo, para
            // que el jugador lea el resultado del impacto sin mirar el HUD.
            for (impact in game.impacts) {
                drawImpactBurst(
                    impact = impact,
                    sectorColor = sectorPalette[impact.colorIndex % sectorPalette.size],
                )
            }

            // Destellos de borde: rojo al perder una vida, verde al ganarla.
            if (lostAge in 0f..EDGE_FLASH_SEC) drawEdgeFlash(LogicColors.Error, 1f - lostAge / EDGE_FLASH_SEC)
            val gainAge = now - lifeGainedAt
            if (gainAge in 0f..EDGE_FLASH_SEC) drawEdgeFlash(LogicColors.Success, 1f - gainAge / EDGE_FLASH_SEC)
        }

        PolarityHud(game = game, modifier = Modifier.align(Alignment.TopCenter))

        // Cómo se juega, solo hasta la primera captura: enseña sin estorbar y se va sola.
        AnimatedVisibility(
            visible = game.caught == 0 && state.status == GameStatus.RUNNING && !game.isInterlude,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = stringResource(Res.string.polarity_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.80f), RoundedCornerShape(16.dp))
                    .border(1.dp, CategoryPalette.SpatialVision.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Carteles de cambio de fase, bajo el disco: es la única zona ancha y vacía de
        // la pantalla (arriba está el HUD y en el centro el disco), así que el aviso no
        // tapa nada de lo que el jugador necesita mirar al volver a jugar.
        PhaseBanner(
            game = game,
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(0, (viewportSize.height * BANNER_CENTER_OFFSET_FRACTION).toInt()) },
        )

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(PolarityCollisionIntent.PlayAgain) },
                onExit = onExit,
                accent = CategoryPalette.SpatialVision,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(PolarityCollisionIntent.Pause) },
            onResume = { vm.onIntent(PolarityCollisionIntent.Resume) },
            onExit = onExit,
            gameTitle = title,
            help = GameHelpContent.polarity,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/**
 * Cartel de cambio de fase: anuncia la lluvia de meteoros y, al terminarla, la
 * recompensa (vida + color nuevo) junto a los meteoros cazados.
 *
 * Los premios se cantan según [PolarityCollisionState.rewardedLife] /
 * [PolarityCollisionState.rewardedColor] y no "por defecto": una vez al tope,
 * prometer "+1 vida" sería mentir al jugador.
 */
@Composable
private fun PhaseBanner(game: PolarityCollisionState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = game.isInterlude,
        enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.85f),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val isShower = game.phase == PolarityPhase.SHOWER_INTRO
        // Panel de cristal tras el texto: el cartel cae sobre el campo de gravedad y las
        // partículas, y sin fondo propio se leía mal. El borde canta el tipo de aviso
        // (magenta = empieza la lluvia, verde = recompensa).
        val tone = if (isShower) LogicColors.Magenta else LogicColors.NeonGreen
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .background(LogicColors.SurfaceDark.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
                .border(1.5.dp, tone.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = if (isShower) {
                    stringResource(Res.string.polarity_banner_shower_title)
                } else {
                    stringResource(Res.string.polarity_banner_wave_title, game.wave.toString())
                },
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            val subtitle = if (isShower) {
                stringResource(Res.string.polarity_banner_shower_subtitle)
            } else {
                stringResource(Res.string.polarity_banner_wave_caught, game.showerCaught.toString())
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            val reward = when {
                isShower -> null
                game.rewardedLife && game.rewardedColor ->
                    stringResource(Res.string.polarity_banner_wave_reward, game.colorCount.toString())
                game.rewardedLife -> stringResource(Res.string.polarity_banner_wave_life)
                game.rewardedColor -> stringResource(Res.string.polarity_banner_wave_colors, game.colorCount.toString())
                else -> null
            }
            if (reward != null) {
                Text(
                    text = reward,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.NeonGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * HUD superior.
 *
 * - **Puntuación** grande a la izquierda, que late al sumar.
 * - **Vidas** debajo: en una partida infinita son el dato crítico (antes lo era el reloj).
 * - **Oleada** y **aciertos** como fichas con icono.
 * - La **cuenta atrás del evento** (cuánto falta para la lluvia, o cuánto queda de ella) en una
 *   píldora que durante la lluvia pasa a magenta. No es la de la partida, que no tiene fin; en
 *   los carteles se oculta porque ahí la propia pantalla ya dice qué pasa.
 *
 * El título y la instrucción que ocupaban la cabecera se quitaron: el título está en la antesala
 * y la instrucción es una pista que desaparece sola. Deja libre la esquina superior derecha (el
 * botón de pausa vive ahí).
 */
@Composable
private fun PolarityHud(game: PolarityCollisionState, modifier: Modifier = Modifier) {
    val accent = CategoryPalette.SpatialVision
    val scorePop = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(game.score) }
    LaunchedEffect(game.score) {
        val grew = game.score > lastScore
        lastScore = game.score
        if (!grew) return@LaunchedEffect
        scorePop.snapTo(1.2f)
        scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 76.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = game.score.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f).graphicsLayer {
                    scaleX = scorePop.value
                    scaleY = scorePop.value
                    // Crece desde la izquierda: el número está alineado a ese lado.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
            HudChip(KortexIcons.TrendingUp, accent, game.wave.toString(), stringResource(Res.string.polarity_hud_wave))
            HudChip(KortexIcons.Star, LogicColors.Amber, game.caught.toString(), stringResource(Res.string.polarity_hud_caught))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(PolarityConfig.MAX_LIVES) { i -> PolarityHeart(alive = i < game.lives) }
            }
            if (!game.isInterlude) {
                val shower = game.phase == PolarityPhase.SHOWER
                val tone = if (shower) LogicColors.Magenta else LogicColors.OnDarkMuted
                val seconds = (game.phaseRemainingMs / 1000).coerceAtLeast(0)
                Row(
                    modifier = Modifier
                        .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                        .border(1.dp, tone.copy(alpha = if (shower) 0.85f else 0.35f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (shower) Res.string.polarity_hud_shower_now else Res.string.polarity_hud_shower_in,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = tone,
                    )
                    Text(
                        text = stringResource(Res.string.polarity_hud_seconds, seconds.toString()),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (shower) LogicColors.Magenta else LogicColors.OnDark,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

/** Ficha de contador del HUD: icono + cifra. El icono dice qué es sin gastar una etiqueta. */
@Composable
private fun HudChip(icon: ImageVector, tint: Color, value: String, description: String) {
    Row(
        modifier = Modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, LogicColors.SurfaceVariantDark, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 14.dp, glow = false, contentDescription = description)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark, fontWeight = FontWeight.Black)
    }
}

/** Tamaño del glifo del corazón dentro de su slot. */
private val HeartGlyph = 18.dp

/** Slot fijo de cada corazón (incluye el hueco del halo, que `NeonIcon` dibuja a
 *  `size * 1.9`) para que la posición NO cambie según el estado: si el `Row` midiera
 *  cada corazón por su contenido, el vivo (con halo) desalinearía a los apagados.
 *  Mismo patrón que `NeonPulseScreen.NeonPulseHeart`. */
private val HeartSlot = HeartGlyph * 1.9f

/**
 * Un corazón del HUD de vidas, con **posición estable**. El contorno (vida perdida)
 * está siempre presente y ocupa el mismo hueco; encima, el corazón relleno + su halo
 * se desvanecen dando un pequeño "estallido" (escala hacia arriba mientras baja la
 * opacidad) al perder la vida, en vez de desaparecer de golpe. Se pintan siempre
 * [PolarityConfig.MAX_LIVES] huecos para que la vida que regala cada lluvia tenga un
 * sitio visible al que llegar y el HUD no cambie de ancho al recibirla.
 */
@Composable
private fun PolarityHeart(alive: Boolean) {
    val fillAlpha by animateFloatAsState(
        targetValue = if (alive) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "heartAlpha",
    )
    val fillScale by animateFloatAsState(
        targetValue = if (alive) 1f else 1.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "heartScale",
    )

    Box(modifier = Modifier.size(HeartSlot), contentAlignment = Alignment.Center) {
        NeonIcon(
            icon = KortexIcons.HeartOutline,
            tint = LogicColors.OnDarkMuted,
            size = HeartGlyph,
            glow = false,
            contentDescription = if (alive) null else stringResource(Res.string.polarity_life_lost),
        )
        if (fillAlpha > 0f) {
            NeonIcon(
                icon = KortexIcons.Heart,
                tint = LogicColors.Coral,
                size = HeartGlyph,
                glow = true,
                contentDescription = if (alive) stringResource(Res.string.polarity_life) else null,
                modifier = Modifier.scale(fillScale).alpha(fillAlpha),
            )
        }
    }
}

/**
 * Estallido de chispas de un impacto: núcleo blanco que destella, onda expansiva
 * semántica (verde [LogicColors.Success] en acierto, roja [LogicColors.Error] en
 * fallo, igual que el resto de la app) y rayos radiales en el color del sector para
 * que se lea a la vez QUÉ color impactó y SI fue correcto. Todo se desvanece según
 * [PolarityImpact.ageMs] sobre [IMPACT_LIFETIME_MS], sin animación propia: el motor
 * hace avanzar la edad frame a frame, así que solo interpolamos aquí.
 *
 * Caso aparte: los meteoros perdidos durante la lluvia ([PolarityImpact.harmless]) se
 * pintan apagados y en gris, nunca en rojo — no ha pasado nada malo, y teñir de error
 * lo que no castiga enseñaría a temer la fase de regalo.
 */
internal fun DrawScope.drawImpactBurst(impact: PolarityImpact, sectorColor: Color) {
    val progress = (impact.ageMs.toFloat() / IMPACT_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
    val fade = 1f - progress
    if (fade <= 0f) return
    val center = Offset(impact.x, impact.y)
    val semanticColor = when {
        impact.success -> LogicColors.Success
        impact.harmless -> LogicColors.OnDarkMuted
        else -> LogicColors.Error
    }
    // Los impactos inofensivos son ruido de fondo de la lluvia: mismo dibujo, mucha
    // menos presencia, para no competir con las capturas buenas.
    val intensity = if (impact.harmless) 0.4f else 1f

    // Flash: pop blanco breve en el instante del impacto.
    drawCircle(
        color = Color.White.copy(alpha = 0.85f * fade * fade * intensity),
        radius = 5.dp.toPx() + 4.dp.toPx() * progress,
        center = center,
    )

    // Onda expansiva semántica: crece y se apaga; comunica acierto/fallo de un vistazo.
    drawCircle(
        color = semanticColor.copy(alpha = 0.6f * fade * intensity),
        radius = 6.dp.toPx() + 26.dp.toPx() * progress,
        center = center,
        style = Stroke(width = 2.5.dp.toPx() * fade + 0.6.dp.toPx()),
    )

    // Rayos radiales en el color del sector impactado: en fallo son irregulares
    // (longitud desigual por rayo, semilla estable en [impact.id]) para leer "caos";
    // en acierto son simétricos, lectura limpia.
    val rayCount = if (impact.success) 8 else 10
    val baseLen = 8.dp.toPx() + 24.dp.toPx() * progress
    repeat(rayCount) { i ->
        val jitter = if (impact.success) 1f else 0.55f + (((impact.id * 31 + i * 17) % 9) / 9f) * 0.9f
        val angle = (2.0 * PI / rayCount * i + impact.id * 0.7).toFloat()
        val dir = Offset(cos(angle), sin(angle))
        val len = baseLen * jitter
        drawLine(
            color = sectorColor.copy(alpha = 0.9f * fade * intensity),
            start = center + dir * 3.dp.toPx(),
            end = center + dir * len,
            strokeWidth = 2.dp.toPx() * fade + 0.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun normalizeAngle(angleRad: Float): Float {
    val twoPi = (2.0 * PI).toFloat()
    var angle = angleRad
    while (angle <= -PI.toFloat()) angle += twoPi
    while (angle > PI.toFloat()) angle -= twoPi
    return angle
}

private const val ROTATION_SPEED_MULTIPLIER = 3.0f

/** Sacudida de la escena al perder una vida: duración y amplitud inicial. */
private const val SHAKE_SEC = 0.32f
private val SHAKE_AMPLITUDE = 8.dp

/** Cuánto dura el tinte rojo del disco tras un fallo (s). */
private const val HURT_SEC = 0.45f

/** Duración de los destellos de borde (s). */
private const val EDGE_FLASH_SEC = 0.45f

/** Distancia (en radios del disco) desde la que la marca de entrada empieza a avivarse. */
private const val MARKER_RANGE = 4.5f

/**
 * Desplazamiento vertical del cartel de fase respecto al centro, en fracción de la
 * altura de pantalla: lo baja lo justo para caer bajo el disco (radio 0.18 del lado
 * menor) sin pisar el borde inferior.
 */
private const val BANNER_CENTER_OFFSET_FRACTION = 0.26f
