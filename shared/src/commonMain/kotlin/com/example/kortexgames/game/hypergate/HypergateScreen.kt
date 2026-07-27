package com.example.kortexgames.game.hypergate

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.example.kortexgames.ui.components.softGlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * # HypergateScreen — renderizado radial (Fase 3)
 *
 * Pantalla del minijuego **Hypergate**. Es, por diseño, solo dos cosas (§3 del spec): un
 * **gestor de toques** (tap en cualquier parte → conmuta el escudo) y un **Canvas** que traduce
 * el estado polar del motor a píxeles. Sin botones genéricos ni emojis.
 *
 * ## Traducción polar → cartesiana (el corazón del render)
 * El motor mantiene cada proyectil como `(angleRad, distancePx)` respecto al centro (ver
 * [Projectile]); aquí se proyecta a pantalla una sola vez por frame y proyectil:
 * `x = centro.x + cos(angleRad) * distancePx`, `y = centro.y + sin(angleRad) * distancePx`.
 * Concentrar toda la trigonometría en el render (y no en la física) es lo que justifica el
 * modelo polar: el tick es O(n) sin `cos/sin`, y el coste trigonométrico se paga aquí, donde de
 * todos modos hay que tocar la GPU.
 *
 * ## Feedback táctil del escudo
 * Al conmutar, la escala del anillo hace un **micro-rebote con `spring`** ([shieldScale]) y el
 * color **transiciona** entre Verde Neón (A) y Cian Eléctrico (B) con `animateColorAsState`. El
 * anillo se dibuja como `Stroke` dentro de una capa con [Modifier.softGlow] del color activo,
 * tal como pide el spec, para el halo neón "que respira".
 */
@Composable
fun HypergateScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: HypergateViewModel = viewModel {
        HypergateViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de física.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            title = "Hypergate",
            motif = GameMotif.HYPERGATE,
            description = "Toca en cualquier parte para alternar la polaridad del escudo. Haz que su color coincida con cada proyectil justo antes del impacto: iguala para absorber, falla y chocarás.",
            accent = CategoryPalette.Reflexes,
            onStart = { vm.onIntent(HypergateIntent.Start) },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Halo de baja amplitud que "respira" (un único bucle ambiental, §9.4).
    val glowPulse by rememberInfiniteTransition(label = "hypergateGlow").animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hypergateGlowAlpha",
    )

    // Color del escudo según su polaridad, con crossfade suave al conmutar.
    val shieldColor by animateColorAsState(
        targetValue = if (game.shield == ShieldState.A) LogicColors.NeonGreen else LogicColors.NeonCyan,
        animationSpec = tween(durationMillis = 180),
        label = "hypergateShieldColor",
    )

    // Micro-rebote táctil: cada cambio de polaridad hunde la escala y la deja volver con spring.
    val shieldScale = remember { Animatable(1f) }
    LaunchedEffect(game.shield) {
        shieldScale.snapTo(SHIELD_BOUNCE_MIN)
        shieldScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos -> vm.onIntent(HypergateIntent.Tick(frameNanos)) }
        }
    }

    // Reporta el tamaño real al motor para situar spawns y colisiones.
    LaunchedEffect(viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            vm.onIntent(
                HypergateIntent.UpdateViewport(
                    widthPx = viewportSize.width.toFloat(),
                    heightPx = viewportSize.height.toFloat(),
                ),
            )
        }
    }

    val density = LocalDensity.current
    val shieldDiameterDp = with(density) { (game.shieldRadiusPx * 2f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                // Tap-anywhere: la posición del toque es irrelevante, solo conmuta el escudo.
                detectTapGestures { vm.onIntent(HypergateIntent.ToggleShield) }
            },
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        // Capa de proyectiles: polar → cartesiano, dibujados como estelas neón con glow.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            for (p in game.projectiles) {
                val head = Offset(
                    x = center.x + cos(p.angleRad) * p.distancePx,
                    y = center.y + sin(p.angleRad) * p.distancePx,
                )
                drawNeonProjectile(
                    head = head,
                    angleRad = p.angleRad,
                    color = projectileColor(p.required),
                    glowPulse = glowPulse,
                )
            }
        }

        // Escudo central: anillo con softGlow del color activo + micro-rebote de escala.
        if (game.shieldRadiusPx > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(shieldDiameterDp)
                    .graphicsLayer {
                        scaleX = shieldScale.value
                        scaleY = shieldScale.value
                    }
                    .softGlow(color = shieldColor, shape = CircleShape),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawShieldRing(color = shieldColor, glowPulse = glowPulse)
                }
            }
        }

        // HUD superior: marcador, absorbidos, choques y tiempo restante.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Hypergate",
                style = MaterialTheme.typography.headlineSmall,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Toca para igualar la polaridad del escudo al proyectil",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                HypergateHudPill(label = "Puntos", value = game.score.toString())
                HypergateHudPill(label = "Absorbidos", value = game.absorbed.toString(), modifier = Modifier.padding(start = 8.dp))
                HypergateHudPill(label = "Choques", value = game.crashed.toString(), modifier = Modifier.padding(start = 8.dp))
                HypergateHudPill(label = "Tiempo", value = "${(game.remainingMs / 1000).coerceAtLeast(0)}s", modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(HypergateIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HypergateIntent.Pause) },
            onResume = { vm.onIntent(HypergateIntent.Resume) },
            onExit = onExit,
            gameTitle = "Hypergate",
            helpText = "Toca en cualquier parte para alternar la polaridad del escudo. Haz que su color coincida con cada proyectil justo antes del impacto: iguala para absorber, falla y chocarás.",
            accent = CategoryPalette.Reflexes,
        )
    }
}

/** Color neón de un proyectil según la polaridad que lo absorbe (coherente con el escudo). */
private fun projectileColor(required: ShieldState): Color =
    if (required == ShieldState.A) LogicColors.NeonGreen else LogicColors.NeonCyan

/**
 * Píldora del HUD (etiqueta + valor), mismo lenguaje visual que el resto de juegos.
 * `modifier` como primer parámetro opcional según la convención de componentes (§4).
 */
@Composable
private fun HypergateHudPill(label: String, value: String, modifier: Modifier = Modifier) {
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
 * Dibuja el anillo del escudo dentro de su propia capa (que ya lleva el [Modifier.softGlow]).
 * El Canvas ocupa el diámetro exacto del escudo, así que el radio de dibujo es la mitad del lado.
 *
 * @param glowPulse factor 0.72..1 del latido ambiental, modula la opacidad del halo interno.
 */
private fun DrawScope.drawShieldRing(color: Color, glowPulse: Float) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val radius = size.minDimension * 0.5f

    // Halo radial interno (además del softGlow de la capa) para el brillo neón "encendido".
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.22f * glowPulse), Color.Transparent),
            center = center,
            radius = radius * 1.9f,
        ),
        radius = radius * 1.4f,
        center = center,
    )
    // Anillo (Stroke) del color activo.
    drawCircle(
        color = color.copy(alpha = 0.9f),
        radius = radius * 0.94f,
        center = center,
        style = Stroke(width = radius * 0.09f),
    )
    // Núcleo oscuro para que el anillo respire sobre el fondo y no se "rellene" visualmente.
    drawCircle(
        color = LogicColors.BackgroundDark.copy(alpha = 0.85f),
        radius = radius * 0.72f,
        center = center,
    )
}

/**
 * Dibuja un proyectil como una **estela corta** con `StrokeCap.Round` apuntando hacia el centro,
 * más un glow radial y un núcleo brillante. La cola se sitúa "detrás" (hacia afuera) usando el
 * mismo ángulo polar: `cola = head + (cos, sin) * TRAIL_LEN`, dando sensación de movimiento sin
 * necesidad de guardar posiciones previas.
 */
private fun DrawScope.drawNeonProjectile(
    head: Offset,
    angleRad: Float,
    color: Color,
    glowPulse: Float,
) {
    val tail = Offset(
        x = head.x + cos(angleRad) * PROJECTILE_TRAIL_PX,
        y = head.y + sin(angleRad) * PROJECTILE_TRAIL_PX,
    )
    // Glow radial alrededor de la cabeza (mismo lenguaje que los asteroides de Polarity).
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.4f * glowPulse), Color.Transparent),
            center = head,
            radius = PROJECTILE_HEAD_PX * 2.6f,
        ),
        radius = PROJECTILE_HEAD_PX * 1.9f,
        center = head,
    )
    // Estela.
    drawLine(
        color = color,
        start = tail,
        end = head,
        strokeWidth = PROJECTILE_HEAD_PX * 0.9f,
        cap = StrokeCap.Round,
    )
    // Núcleo brillante en la punta.
    drawCircle(color = color, radius = PROJECTILE_HEAD_PX * 0.55f, center = head)
    drawCircle(color = Color.White.copy(alpha = 0.28f), radius = PROJECTILE_HEAD_PX * 0.2f, center = head)
}

/** Escala mínima del micro-rebote del escudo al conmutar (hunde y vuelve con spring a 1). */
private const val SHIELD_BOUNCE_MIN = 0.86f

/** Longitud (px) de la estela del proyectil, hacia afuera desde su cabeza. */
private const val PROJECTILE_TRAIL_PX = 34f

/** Radio base (px) de la cabeza del proyectil; el glow y el núcleo se derivan de él. */
private const val PROJECTILE_HEAD_PX = 13f
