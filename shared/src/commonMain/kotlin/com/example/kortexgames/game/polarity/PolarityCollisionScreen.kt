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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import com.example.kortexgames.game.GameMotif
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
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
            motif = GameMotif.POLARITY_SECTORS,
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
            gameTitle = "Atracción Geométrica",
            helpText = "Rota el círculo para capturar las piezas de tu color y evita las contrarias antes de que se acabe el tiempo.",
            accent = CategoryPalette.SpatialVision,
        )
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

/**
 * Disco de 4 sectores con lenguaje de "tubo de neón" (misma familia visual que
 * [com.example.kortexgames.ui.components.drawNeonTile]): relleno de cristal con
 * degradado radial (brillante al centro, se apaga hacia el borde) en vez de color
 * plano —lo que antes leía como una pelota de playa— y un aro de neón por sector
 * (halo ancho → halo medio → trazo nítido) que remata el borde exterior. Las
 * costuras entre sectores son una fibra de luz blanca fina, no un corte gris.
 */
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

    // Relleno "cristal": degradado radial por sector, no color plano. Da volumen y
    // rompe la lectura de "pelota de playa" de un pie chart de colores sólidos.
    repeat(4) { index ->
        val base = colors[index % colors.size]
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
    // [com.example.kortexgames.ui.components.drawNeonTile], pero siguiendo el arco.
    val rimInsetDeg = 3f
    repeat(4) { index ->
        val base = colors[index % colors.size]
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
    repeat(4) { index ->
        val angle = rotationRad + (PI.toFloat() / 2f) * index
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
 * Estallido de chispas de un impacto: núcleo blanco que destella, onda expansiva
 * semántica (verde [LogicColors.Success] en acierto, roja [LogicColors.Error] en
 * fallo, igual que el resto de la app) y rayos radiales en el color del sector para
 * que se lea a la vez QUÉ color impactó y SI fue correcto. Todo se desvanece según
 * [PolarityImpact.ageMs] sobre [IMPACT_LIFETIME_MS], sin animación propia: el motor
 * hace avanzar la edad frame a frame, así que solo interpolamos aquí.
 */
private fun DrawScope.drawImpactBurst(impact: PolarityImpact, sectorColor: Color) {
    val progress = (impact.ageMs.toFloat() / IMPACT_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
    val fade = 1f - progress
    if (fade <= 0f) return
    val center = Offset(impact.x, impact.y)
    val semanticColor = if (impact.success) LogicColors.Success else LogicColors.Error

    // Flash: pop blanco breve en el instante del impacto.
    drawCircle(
        color = Color.White.copy(alpha = 0.85f * fade * fade),
        radius = 5.dp.toPx() + 4.dp.toPx() * progress,
        center = center,
    )

    // Onda expansiva semántica: crece y se apaga; comunica acierto/fallo de un vistazo.
    drawCircle(
        color = semanticColor.copy(alpha = 0.6f * fade),
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
            color = sectorColor.copy(alpha = 0.9f * fade),
            start = center + dir * 3.dp.toPx(),
            end = center + dir * len,
            strokeWidth = 2.dp.toPx() * fade + 0.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
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


