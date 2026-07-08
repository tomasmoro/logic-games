package com.example.kortexgames.game.polarity

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.SpaceBackdrop
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pantalla de Atracción Geométrica.
 *
 * Implementa un `Canvas` full-screen y controla el círculo central con drag libre:
 * cada movimiento calcula `atan2` respecto al centro para convertir gesto en rotación.
 */
@Composable
fun PolarityCollisionScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: PolarityCollisionViewModel = viewModel {
        PolarityCollisionViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro (evita,
    // de paso, que el bucle de física corra bajo la intro).
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            title = "Atracción Geométrica",
            description = "Rota el círculo para capturar las piezas de tu color y evita las contrarias antes de que se acabe el tiempo.",
            accent = CategoryPalette.SpatialVision,
            onStart = { vm.onIntent(PolarityCollisionIntent.Start) },
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val hexRadius = min(size.width, size.height) * 0.18f
            val sectorPalette = listOf(
                LogicColors.NeonCyan,
                LogicColors.NeonGreen,
                LogicColors.Amber,
                LogicColors.Violet,
            )

            drawRingSectors(center = center, radius = hexRadius, rotationRad = game.rotationRad, colors = sectorPalette)
            drawRingFrame(center = center, radius = hexRadius, glowPulse = glowPulse)

            for (particle in game.particles) {
                val color = sectorPalette[particle.colorIndex % sectorPalette.size]
                drawNeonAsteroid(
                    center = Offset(particle.x, particle.y),
                    radius = particle.radius,
                    color = color,
                    glowPulse = glowPulse,
                    glowBoost = if (particle.magnetic) 1.18f else 1f,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Atracción Geométrica",
                style = MaterialTheme.typography.headlineSmall,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Rota el círculo desde cualquier zona para capturar por color",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                HudPill(label = "Puntos", value = game.score.toString())
                HudPill(label = "Aciertos", value = game.caught.toString(), modifier = Modifier.padding(start = 8.dp))
                HudPill(label = "Fallos", value = game.missed.toString(), modifier = Modifier.padding(start = 8.dp))
                HudPill(label = "Tiempo", value = "${(game.remainingMs / 1000).coerceAtLeast(0)}s", modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                onPlayAgain = { vm.onIntent(PolarityCollisionIntent.PlayAgain) },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun HudPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark)
    }
}

private fun DrawScope.drawRingSectors(
    center: Offset,
    radius: Float,
    rotationRad: Float,
    colors: List<Color>,
) {
    val sectorAngle = 90f
    val startAngleDeg = rotationRad * 180f / PI.toFloat()
    val diameter = radius * 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(diameter, diameter)
    repeat(4) { index ->
        drawArc(
            color = colors[index % colors.size],
            startAngle = startAngleDeg + sectorAngle * index,
            sweepAngle = sectorAngle,
            useCenter = true,
            topLeft = topLeft,
            size = arcSize,
        )
    }

    // Separadores finos: ayudan a leer los cuatro colores sin meter un aro gris.
    repeat(4) { index ->
        val angle = rotationRad + (PI.toFloat() / 2f) * index
        drawLine(
            color = LogicColors.BackgroundDark.copy(alpha = 0.62f),
            start = center,
            end = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius),
            strokeWidth = radius * 0.035f,
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
    drawCircle(
        color = LogicColors.BackgroundDark.copy(alpha = 0.88f),
        radius = radius * 0.22f,
        center = center,
    )
}

/**
 * Asteroide con glow canónico: halo radial como [com.example.kortexgames.ui.components.NeonIcon]
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


