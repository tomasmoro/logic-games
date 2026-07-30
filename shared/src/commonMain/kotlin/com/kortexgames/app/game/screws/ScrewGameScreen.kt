package com.kortexgames.app.game.screws

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// --- Constantes de dibujo (en unidades de tablero; se escalan a px) ----------

/** Radio visual del agujero del tablero. */
private const val HOLE_RADIUS = 24f

/** Radio de la cabeza hexagonal del tornillo (mayor que el agujero: lo tapa). */
private const val SCREW_RADIUS = 34f

/** Radio de acierto del toque sobre un tornillo (generoso: dedo, no puntero). */
private const val SCREW_TAP_RADIUS = 60f

/** Radio de acierto del toque sobre un agujero. */
private const val HOLE_TAP_RADIUS = 55f

/** Elevación del tornillo seleccionado ("desatornillado", flota sobre su agujero). */
private const val SELECTED_LIFT = 26f

/** Duración de la caída de una placa liberada. */
private const val FALL_DURATION_MS = 700

/**
 * Paleta neón de los bordes de placa, indexada por [Plate.colorIndex]. La lógica
 * pura no conoce `Color` (mismo patrón que las pociones de Water Sort); todos
 * los tonos salen de [LogicColors] — nunca hardcodeados sueltos (CLAUDE.md §9.2).
 */
private val PlateAccents: List<Color> = listOf(
    LogicColors.Violet,
    LogicColors.Blue,
    LogicColors.Magenta,
    LogicColors.Amber,
    LogicColors.NeonGreen,
    LogicColors.Coral,
)

/**
 * Pantalla de "Tornillos Neón".
 *
 * Estructura idéntica a los demás juegos LEVELED: antesala con selector de
 * niveles ([GameIntroScreen]) → tablero a pantalla completa → [GameOverOverlay].
 *
 * El tablero es un único `Canvas` que dibuja en **unidades virtuales** de
 * [BOARD_WIDTH] escaladas a px con un solo factor (el mismo que traduce los
 * toques de vuelta a unidades). Las animaciones son **reactivas al estado**:
 * cada placa lleva sus [Animatable] (rotación/centro con `spring` poco
 * amortiguado = balanceo de péndulo; caída con `tween` acelerado) que persiguen
 * la pose objetivo que publica el motor.
 *
 * **Feedback sensorial**: esta pantalla es el único consumidor de los Effects
 * one-shot del ViewModel — los traduce a llamadas al `AudioAndHapticManager`
 * (decisión de arquitectura de este juego; ver KDoc de [ScrewGameViewModel]).
 */
@Composable
fun ScrewGameScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: ScrewGameViewModel = viewModel {
        ScrewGameViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto donde los Effects se vuelven sonido/vibración.
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is ScrewGameEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is ScrewGameEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                // La celebración visual la dirige el estado (gameOver → overlay
                // con fuegos artificiales); el efecto queda para ganchos futuros.
                ScrewGameEffect.ShowLevelComplete -> Unit
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.screws,
            title = "Tornillos Neón",
            description = "Desatornilla y recoloca los pernos para soltar todas las placas. Las placas tapan agujeros… y cuelgan si les queda un solo tornillo.",
            accent = CategoryPalette.SpatialVision,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = { vm.onIntent(ScrewGameIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    val game = state.game

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            ScrewHud(
                level = state.currentLevel,
                moves = game.moves,
                cleared = game.platesCleared,
                total = game.totalPlates,
                onRestart = { vm.onIntent(ScrewGameIntent.Restart) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ScrewBoard(
                    game = game,
                    onIntent = vm::onIntent,
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .aspectRatio(BOARD_WIDTH / game.boardHeight),
                )
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(ScrewGameIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(ScrewGameIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(ScrewGameIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(ScrewGameIntent.Pause) },
            onResume = { vm.onIntent(ScrewGameIntent.Resume) },
            onExit = onExit,
            gameTitle = "Tornillos Neón",
            help = GameHelpContent.screws,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/** HUD superior: título, píldoras de progreso y botón de reiniciar nivel. */
@Composable
private fun ScrewHud(
    level: Int,
    moves: Int,
    cleared: Int,
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Tornillos Neón",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPill(label = "Nivel", value = level.toString())
            HudPill(label = "Movimientos", value = moves.toString())
            HudPill(label = "Placas", value = "$cleared/$total")
            Box(
                modifier = Modifier
                    .bounceClick(onClick = onRestart)
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = KortexIcons.Refresh,
                    contentDescription = "Reiniciar nivel",
                    tint = LogicColors.OnDarkMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
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

/**
 * Animatables de una placa. Persiguen la pose objetivo del motor: así la
 * animación es **dirigida por estado** (reactiva), nunca imperativa (§9.4).
 */
private class PlateAnimState(initial: Plate) {
    /** Rotación en radianes (mismo dominio que el modelo). */
    val rotation = Animatable(initial.rotation)
    val centerX = Animatable(initial.center.x)
    val centerY = Animatable(initial.center.y)

    /** Progreso 0→1 de la caída (solo cuando el motor marca FALLING). */
    val fall = Animatable(0f)
}

/**
 * Enlaza los [Animatable] de [plate] con su pose objetivo:
 *
 *  - **Rotación**: `spring` MUY poco amortiguado — el rebote del muelle ES el
 *    balanceo del péndulo al quedar colgando; no hace falta simularlo.
 *  - **Centro**: `spring` medio (acompaña la rotación alrededor del pivote).
 *  - **Caída**: `tween` con easing acelerado (gravedad); al completarse notifica
 *    al dominio ([ScrewGameIntent.PlateFallFinished]) para retirar la placa.
 */
@Composable
private fun PlateAnimator(plate: Plate, anim: PlateAnimState, onFallen: (Int) -> Unit) {
    LaunchedEffect(plate.rotation) {
        launch {
            anim.rotation.animateTo(
                targetValue = plate.rotation,
                animationSpec = spring(dampingRatio = 0.28f, stiffness = 140f),
            )
        }
    }
    LaunchedEffect(plate.center) {
        launch {
            anim.centerX.animateTo(plate.center.x, spring(stiffness = Spring.StiffnessMediumLow))
        }
        launch {
            anim.centerY.animateTo(plate.center.y, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    val onFallenCurrent by rememberUpdatedState(onFallen)
    LaunchedEffect(plate.status) {
        if (plate.status == PlateStatus.FALLING) {
            anim.fall.animateTo(1f, tween(FALL_DURATION_MS, easing = FastOutLinearInEasing))
            onFallenCurrent(plate.id)
        }
    }
}

/**
 * El tablero: un `Canvas` que dibuja agujeros → placas (por z) → tornillos, y
 * traduce los toques a intents. El **hit-testing vive aquí** (no en el motor)
 * porque es una preocupación de presentación: radios de acierto pensados para
 * el dedo, en las mismas unidades virtuales del dibujo.
 */
@Composable
private fun ScrewBoard(
    game: ScrewGameState,
    onIntent: (ScrewGameIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Animatables por placa. Mapa simple (no snapshot): la invalidación del
    // dibujo la disparan los propios Animatable al leerse sus `value`.
    val anims = remember { mutableMapOf<Int, PlateAnimState>() }
    game.plates.forEach { plate ->
        key(plate.id) {
            PlateAnimator(
                plate = plate,
                anim = anims.getOrPut(plate.id) { PlateAnimState(plate) },
                onFallen = { onIntent(ScrewGameIntent.PlateFallFinished(it)) },
            )
        }
    }
    // Limpieza: al retirar una placa del estado, su Animatable ya no se usa.
    LaunchedEffect(game.plates.size) {
        val alive = game.plates.mapTo(HashSet()) { it.id }
        anims.keys.retainAll(alive)
    }

    // Respiración del glow (lenta, baja amplitud — §9.4) compartida por
    // tornillos y agujeros disponibles: un solo reloj, cero competencia visual.
    val glowPulse by rememberInfiniteTransition(label = "screwGlow").animateFloat(
        initialValue = 0.70f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "screwGlowAlpha",
    )

    // El detector se registra una vez; lee el estado vigente vía
    // rememberUpdatedState para no reiniciar el gesto en cada jugada.
    val currentGame by rememberUpdatedState(game)
    val currentIntent by rememberUpdatedState(onIntent)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { tap ->
                val g = currentGame
                val scale = size.width / BOARD_WIDTH
                val p = Vec2(tap.x / scale, tap.y / scale)
                val holesById = g.holes.associateBy { it.id }

                // Prioridad tornillo > agujero: los tornillos siempre están
                // encima y son el objeto principal de la interacción.
                val screw = g.screws
                    .minByOrNull { holesById.getValue(it.holeId).position.distanceTo(p) }
                    ?.takeIf { holesById.getValue(it.holeId).position.distanceTo(p) <= SCREW_TAP_RADIUS }
                if (screw != null) {
                    currentIntent(ScrewGameIntent.SelectScrew(screw.id))
                    return@detectTapGestures
                }
                val hole = g.holes
                    .minByOrNull { it.position.distanceTo(p) }
                    ?.takeIf { it.position.distanceTo(p) <= HOLE_TAP_RADIUS }
                if (hole != null && g.selectedScrewId != null) {
                    currentIntent(ScrewGameIntent.PlaceScrew(hole.id))
                } else {
                    currentIntent(ScrewGameIntent.CancelSelection)
                }
            }
        },
    ) {
        val scale = size.width / BOARD_WIDTH
        fun Vec2.px() = Offset(x * scale, y * scale)

        drawBoardPanel()
        val hasSelection = game.selectedScrewId != null
        game.holes.forEach { hole ->
            drawHole(
                center = hole.position.px(),
                scale = scale,
                // Solo se "encienden" los destinos válidos, y solo cuando hay
                // un tornillo en la mano: guía sin ruido permanente.
                highlighted = hasSelection && game.isHoleAvailable(hole.id),
                glowPulse = glowPulse,
            )
        }

        game.plates.sortedBy { it.zIndex }.forEach { plate ->
            val anim = anims[plate.id] ?: return@forEach
            drawPlate(plate = plate, anim = anim, scale = scale)
        }

        val holesById = game.holes.associateBy { it.id }
        game.screws.forEach { screw ->
            drawScrew(
                center = holesById.getValue(screw.holeId).position.px(),
                scale = scale,
                selected = screw.id == game.selectedScrewId,
                glowPulse = glowPulse,
            )
        }
    }
}

// --- Primitivas de dibujo ----------------------------------------------------

/** Panel de fondo del tablero: superficie oscura con borde sutil (§9.6). */
private fun DrawScope.drawBoardPanel() {
    val corner = CornerRadius(24.dp.toPx())
    drawRoundRect(color = LogicColors.SurfaceDark.copy(alpha = 0.92f), cornerRadius = corner)
    drawRoundRect(
        color = LogicColors.SurfaceVariantDark,
        cornerRadius = corner,
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

/**
 * Agujero del tablero: anillo elevado + pozo oscuro. Si [highlighted], halo cian
 * que respira (destino válido para el tornillo seleccionado).
 */
private fun DrawScope.drawHole(center: Offset, scale: Float, highlighted: Boolean, glowPulse: Float) {
    if (highlighted) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LogicColors.NeonCyan.copy(alpha = 0.30f * glowPulse), Color.Transparent),
                center = center,
                radius = HOLE_RADIUS * 2.6f * scale,
            ),
            radius = HOLE_RADIUS * 2.2f * scale,
            center = center,
        )
        drawCircle(
            color = LogicColors.NeonCyan.copy(alpha = 0.75f * glowPulse),
            radius = HOLE_RADIUS * 1.25f * scale,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
    drawCircle(color = LogicColors.SurfaceVariantDark, radius = HOLE_RADIUS * 1.1f * scale, center = center)
    drawCircle(color = LogicColors.BackgroundDark, radius = HOLE_RADIUS * 0.85f * scale, center = center)
}

/**
 * Placa: cuerpo sobrio ([LogicColors.SurfaceVariantDark]) con **borde iluminado**
 * de su color de acento y agujeros pasantes. Se dibuja en la pose animada
 * (centro/rotación de sus [Animatable]); si cae, se desplaza hacia abajo con
 * giro residual y se desvanece.
 */
private fun DrawScope.drawPlate(plate: Plate, anim: PlateAnimState, scale: Float) {
    val accent = PlateAccents[plate.colorIndex % PlateAccents.size]
    val fall = anim.fall.value
    val alpha = 1f - fall * fall // se desvanece al final de la caída, no al inicio
    if (alpha <= 0f) return

    val center = Offset(anim.centerX.value * scale, anim.centerY.value * scale)
    val fallDy = fall * size.height * 1.1f
    val rotationDeg = (anim.rotation.value * 180f / PI.toFloat()) +
        fall * 24f * (if (plate.id % 2 == 0) 1f else -1f) // deriva al caer

    translate(left = 0f, top = fallDy) {
        rotate(degrees = rotationDeg, pivot = center) {
            val half = Offset(plate.halfSize.x * scale, plate.halfSize.y * scale)
            val topLeft = center - half
            val rectSize = Size(half.x * 2f, half.y * 2f)
            val corner = CornerRadius(20.dp.toPx())

            drawRoundRect(
                color = LogicColors.SurfaceVariantDark.copy(alpha = alpha),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = corner,
            )
            // Borde neón doble: trazo exterior difuso (halo) + línea nítida.
            drawRoundRect(
                color = accent.copy(alpha = 0.28f * alpha),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = corner,
                style = Stroke(width = 6.dp.toPx()),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.85f * alpha),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = corner,
                style = Stroke(width = 2.dp.toPx()),
            )
            // Agujeros pasantes: se ve el "fondo" a través de la placa. Se
            // dibujan en coordenadas locales SIN rotar (la rotación del
            // transform exterior ya los lleva a su sitio).
            plate.holeOffsets.forEach { offset ->
                val holeCenter = center + Offset(offset.x * scale, offset.y * scale)
                drawCircle(
                    color = LogicColors.BackgroundDark.copy(alpha = alpha),
                    radius = HOLE_RADIUS * 0.95f * scale,
                    center = holeCenter,
                )
                drawCircle(
                    color = accent.copy(alpha = 0.5f * alpha),
                    radius = HOLE_RADIUS * 0.95f * scale,
                    center = holeCenter,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Tornillo: cabeza hexagonal con halo neón (mismo lenguaje que `NeonIcon`) y
 * ranura en cruz. El seleccionado se **eleva** sobre su agujero, crece y su halo
 * respira más fuerte — lectura inmediata de "desatornillado, en la mano".
 */
private fun DrawScope.drawScrew(center: Offset, scale: Float, selected: Boolean, glowPulse: Float) {
    val lift = if (selected) SELECTED_LIFT * scale else 0f
    val c = center - Offset(0f, lift)
    val r = SCREW_RADIUS * scale * (if (selected) 1.22f else 1f)
    val accent = LogicColors.NeonCyan

    // Sombra proyectada sobre el agujero cuando flota (refuerza la elevación).
    if (selected) {
        drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r * 0.8f, center = center)
    }

    val glowAlpha = (if (selected) 0.55f else 0.30f) * glowPulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
            center = c,
            radius = r * 2.4f,
        ),
        radius = r * 1.9f,
        center = c,
    )

    // Cabeza hexagonal: relleno metálico oscuro + arista neón.
    val hex = Path().apply {
        for (i in 0 until 6) {
            val angle = (PI / 3.0 * i - PI / 6.0).toFloat()
            val x = c.x + r * cos(angle)
            val y = c.y + r * sin(angle)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(path = hex, color = LogicColors.SurfaceVariantDark)
    drawPath(path = hex, color = accent.copy(alpha = if (selected) 1f else 0.8f), style = Stroke(width = 2.dp.toPx()))

    // Ranura en cruz (Phillips): dos trazos cortos y redondeados.
    val slot = r * 0.52f
    val slotStroke = r * 0.24f
    drawLine(
        color = LogicColors.BackgroundDark.copy(alpha = 0.9f),
        start = Offset(c.x - slot, c.y),
        end = Offset(c.x + slot, c.y),
        strokeWidth = slotStroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = LogicColors.BackgroundDark.copy(alpha = 0.9f),
        start = Offset(c.x, c.y - slot),
        end = Offset(c.x, c.y + slot),
        strokeWidth = slotStroke,
        cap = StrokeCap.Round,
    )
}
