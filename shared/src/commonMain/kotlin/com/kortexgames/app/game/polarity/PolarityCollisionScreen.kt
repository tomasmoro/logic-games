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
private val SectorPalette = listOf(
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
    val glowPulse by rememberInfiniteTransition(label = "polarityGlow").animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "polarityGlowAlpha",
    )

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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val hexRadius = min(size.width, size.height) * 0.18f

            drawRingSectors(center = center, radius = hexRadius, rotationRad = game.rotationRad, colors = sectorPalette)
            drawRingFrame(center = center, radius = hexRadius, glowPulse = glowPulse)

            for (particle in game.particles) {
                val color = sectorPalette[particle.colorIndex % sectorPalette.size]
                // Los meteoros de la lluvia llevan estela: se distinguen de un vistazo
                // de los asteroides "de verdad", que sí cuestan vida.
                if (particle.meteor) {
                    drawMeteorTrail(particle = particle, color = color)
                }
                drawNeonAsteroid(
                    center = Offset(particle.x, particle.y),
                    radius = particle.radius,
                    color = color,
                    glowPulse = glowPulse,
                    glowBoost = if (particle.magnetic) 1.18f else 1f,
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
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(Res.string.polarity_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            // Vidas: el dato crítico de una partida infinita (antes lo era el reloj), así
            // que van justo bajo el título y por encima del resto de métricas.
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(PolarityConfig.MAX_LIVES) { i ->
                    PolarityHeart(alive = i < game.lives)
                }
            }

            // Cuatro métricas en una fila: píldoras compactas (padding corto y 6 dp de
            // separación) para que quepan sin recortarse en pantallas de 360 dp.
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HudPill(label = stringResource(Res.string.polarity_hud_score), value = game.score.toString())
                HudPill(
                    label = stringResource(Res.string.polarity_hud_caught),
                    value = game.caught.toString(),
                )
                HudPill(
                    label = stringResource(Res.string.polarity_hud_wave),
                    value = game.wave.toString(),
                )
                // Cuenta atrás del EVENTO, no de la partida: cuánto falta para la lluvia
                // (o cuánto queda de ella). En los carteles no se muestra: ahí la propia
                // pantalla ya está diciendo qué pasa.
                if (!game.isInterlude) {
                    val seconds = (game.phaseRemainingMs / 1000).coerceAtLeast(0)
                    HudPill(
                        label = stringResource(
                            if (game.phase == PolarityPhase.SHOWER) {
                                Res.string.polarity_hud_shower_now
                            } else {
                                Res.string.polarity_hud_shower_in
                            },
                        ),
                        value = stringResource(Res.string.polarity_hud_seconds, seconds.toString()),
                        accent = if (game.phase == PolarityPhase.SHOWER) LogicColors.Magenta else null,
                    )
                }
            }
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
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
 * Píldora de una métrica del HUD.
 *
 * @param accent color opcional del valor; se usa para teñir la cuenta atrás mientras
 *   la lluvia está activa (estado excepcional que conviene que salte a la vista).
 */
@Composable
private fun HudPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Column(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = accent ?: LogicColors.OnDark,
        )
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
 * Disco de sectores con lenguaje de "tubo de neón" (misma familia visual que
 * [com.kortexgames.app.ui.components.drawNeonTile]): relleno de cristal con
 * degradado radial (brillante al centro, se apaga hacia el borde) en vez de color
 * plano —lo que antes leía como una pelota de playa— y un aro de neón por sector
 * (halo ancho → halo medio → trazo nítido) que remata el borde exterior. Las
 * costuras entre sectores son una fibra de luz blanca fina, no un corte gris.
 *
 * El número de sectores lo marca [colors] (3..5, crece con cada lluvia de meteoros),
 * así que todo se calcula a partir de `colors.size`: el reparto angular tiene que
 * coincidir EXACTAMENTE con el hit-test del motor
 * ([PolarityCollisionEngine], `isColorMatch`), que también divide 2π entre el número
 * de sectores empezando en `rotationRad`.
 */
private fun DrawScope.drawRingSectors(
    center: Offset,
    radius: Float,
    rotationRad: Float,
    colors: List<Color>,
) {
    val count = colors.size
    val sectorAngle = 360f / count
    val startAngleDeg = rotationRad * 180f / PI.toFloat()
    val diameter = radius * 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(diameter, diameter)

    // Relleno "cristal": degradado radial por sector, no color plano. Da volumen y
    // rompe la lectura de "pelota de playa" de un pie chart de colores sólidos.
    repeat(count) { index ->
        val base = colors[index]
        drawArc(
            brush = Brush.radialGradient(
                colors = listOf(
                    lerp(base, Color.White, 0.16f),
                    base,
                    lerp(base, LogicColors.BackgroundDark, 0.5f),
                ),
                center = center,
                radius = radius * 1.08f,
            ),
            startAngle = startAngleDeg + sectorAngle * index,
            sweepAngle = sectorAngle,
            useCenter = true,
            topLeft = topLeft,
            size = arcSize,
        )
    }

    // Reflejo especular arriba-izquierda: sugiere superficie de cristal/vidrio en vez
    // de plástico mate, sin tapar el color de los sectores.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            center = center - Offset(radius * 0.32f, radius * 0.34f),
            radius = radius * 0.75f,
        ),
        radius = radius,
        center = center,
    )

    // Aro de neón por sector: mismo apilado halo-ancho → halo-medio → nítido que
    // [com.kortexgames.app.ui.components.drawNeonTile], pero siguiendo el arco.
    val rimInsetDeg = 3f
    repeat(count) { index ->
        val base = colors[index]
        val start = startAngleDeg + sectorAngle * index + rimInsetDeg
        val sweep = sectorAngle - rimInsetDeg * 2f
        drawArc(
            color = base.copy(alpha = 0.30f),
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = radius * 0.14f, cap = StrokeCap.Round),
        )
        drawArc(
            color = base.copy(alpha = 0.62f),
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = radius * 0.065f, cap = StrokeCap.Round),
        )
        drawArc(
            color = lerp(base, Color.White, 0.35f),
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = radius * 0.024f, cap = StrokeCap.Round),
        )
    }

    // Costuras entre sectores: fibra de luz blanca (halo + núcleo), no un corte gris.
    val sectorAngleRad = (2.0 * PI / count).toFloat()
    repeat(count) { index ->
        val angle = rotationRad + sectorAngleRad * index
        val outer = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        drawLine(
            color = Color.White.copy(alpha = 0.20f),
            start = center, end = outer,
            strokeWidth = radius * 0.05f, cap = StrokeCap.Round,
        )
        drawLine(
            color = LogicColors.BackgroundDark.copy(alpha = 0.55f),
            start = center, end = outer,
            strokeWidth = radius * 0.022f, cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.55f),
            start = center, end = outer,
            strokeWidth = radius * 0.008f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawRingFrame(center: Offset, radius: Float, glowPulse: Float) {
    // Halo radial tipo NeonIcon: mismo lenguaje visual que HomeScreen y las tarjetas.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                LogicColors.NeonCyan.copy(alpha = 0.18f * glowPulse),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 1.85f,
        ),
        radius = radius * 1.42f,
        center = center,
    )
    drawCircle(
        color = LogicColors.NeonCyan.copy(alpha = 0.34f * glowPulse),
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.026f),
    )
    // Núcleo del eje: mismo apilado halo → nítido que los tiles, para que el centro
    // se sienta "encendido" en vez de un simple recorte oscuro.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                LogicColors.NeonCyan.copy(alpha = 0.30f * glowPulse),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 0.42f,
        ),
        radius = radius * 0.30f,
        center = center,
    )
    drawCircle(
        color = LogicColors.BackgroundDark.copy(alpha = 0.92f),
        radius = radius * 0.20f,
        center = center,
    )
    drawCircle(
        color = LogicColors.NeonCyan.copy(alpha = 0.55f * glowPulse),
        radius = radius * 0.20f,
        center = center,
        style = Stroke(width = radius * 0.012f),
    )
}

/**
 * Estela de un meteoro de la lluvia: un trazo que se apaga hacia atrás en la
 * dirección contraria a su velocidad. Es el distintivo visual de la fase de regalo —
 * con la pantalla llena, la estela dice "esto no te quita vida" sin necesidad de leer
 * el HUD— y además comunica la dirección de entrada de un vistazo.
 */
private fun DrawScope.drawMeteorTrail(particle: PolarityParticle, color: Color) {
    val speed = hypot(particle.vx, particle.vy)
    if (speed <= 1f) return
    val dir = Offset(particle.vx / speed, particle.vy / speed)
    val length = particle.radius * METEOR_TRAIL_RADII
    val tail = Offset(particle.x, particle.y) - dir * length
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, color.copy(alpha = 0.55f)),
            start = tail,
            end = Offset(particle.x, particle.y),
        ),
        start = tail,
        end = Offset(particle.x, particle.y),
        strokeWidth = particle.radius * 0.85f,
        cap = StrokeCap.Round,
    )
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
private fun DrawScope.drawImpactBurst(impact: PolarityImpact, sectorColor: Color) {
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

/**
 * Asteroide con glow canónico: halo radial como [com.kortexgames.app.ui.components.NeonIcon]
 * y núcleo sólido para mantener legibilidad sobre el fondo espacial.
 */
private fun DrawScope.drawNeonAsteroid(
    center: Offset,
    radius: Float,
    color: Color,
    glowPulse: Float,
    glowBoost: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.38f * glowPulse * glowBoost),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 2.15f,
        ),
        radius = radius * 1.55f,
        center = center,
    )
    drawCircle(color = color, radius = radius, center = center)
    drawCircle(
        color = Color.White.copy(alpha = 0.24f),
        radius = radius * 0.26f,
        center = center - Offset(radius * 0.16f, radius * 0.16f),
    )
}

private fun normalizeAngle(angleRad: Float): Float {
    val twoPi = (2.0 * PI).toFloat()
    var angle = angleRad
    while (angle <= -PI.toFloat()) angle += twoPi
    while (angle > PI.toFloat()) angle -= twoPi
    return angle
}

private const val ROTATION_SPEED_MULTIPLIER = 3.0f

/** Longitud de la estela del meteoro, en radios de la propia partícula. */
private const val METEOR_TRAIL_RADII = 4.5f

/**
 * Desplazamiento vertical del cartel de fase respecto al centro, en fracción de la
 * altura de pantalla: lo baja lo justo para caer bajo el disco (radio 0.18 del lado
 * menor) sin pisar el borde inferior.
 */
private const val BANNER_CENTER_OFFSET_FRACTION = 0.26f
