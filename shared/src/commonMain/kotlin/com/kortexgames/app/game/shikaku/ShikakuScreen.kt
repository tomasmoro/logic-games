package com.kortexgames.app.game.shikaku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.shikaku_badge_area
import kortexgames.shared.generated.resources.shikaku_badge_multiple
import kortexgames.shared.generated.resources.shikaku_badge_out_of_mask
import kortexgames.shared.generated.resources.shikaku_badge_target
import kortexgames.shared.generated.resources.shikaku_board_generating
import kortexgames.shared.generated.resources.shikaku_hud_covered
import kortexgames.shared.generated.resources.shikaku_hud_level
import kortexgames.shared.generated.resources.shikaku_hud_restart_description
import kortexgames.shared.generated.resources.shikaku_gameover_headline
import kortexgames.shared.generated.resources.shikaku_intro_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

// --- Constantes de composición del tablero ------------------------------------

/** Nombre del juego: contenido de catálogo (§10), no texto de UI traducible. */
private const val GAME_TITLE = "Neon Shikaku Matrix"

/** Tamaño de la pista como fracción de la celda: legible en 12×12 sin tocar los bordes. */
private const val CLUE_FONT_FRACTION = 0.44f

/** Encendido en reposo del tubo de un rectángulo sellado (0 = apagado, 1 = núcleo blanco). */
private const val SEALED_GLOW = 0.45f

/** Opacidad mínima del parpadeo de error: "sutil" = nunca llega a apagarse del todo. */
private const val ERROR_BLINK_MIN = 0.45f

/** Único punto donde un tinte de dominio se vuelve un color de `LogicColors`. */
private fun ShikakuTint.toColor(): Color = when (this) {
    ShikakuTint.CYAN -> LogicColors.NeonCyan
    ShikakuTint.MAGENTA -> LogicColors.Magenta
    ShikakuTint.GREEN -> LogicColors.NeonGreen
}

/**
 * Color de la selección en curso. Tres lecturas, no dos: verde "esto encaja", rojo "esto es
 * imposible" (pisa un agujero o encierra dos pistas) y ámbar/cian para lo que simplemente aún no
 * está terminado. Pintar de rojo un arrastre a medio camino castigaría el gesto antes de acabarlo.
 */
private fun ShikakuRectValidity.toSelectionColor(): Color = when (this) {
    ShikakuRectValidity.VALID -> LogicColors.NeonGreen
    ShikakuRectValidity.WRONG_AREA -> LogicColors.Amber
    ShikakuRectValidity.NO_NUMBER -> LogicColors.NeonCyan
    ShikakuRectValidity.MULTIPLE_NUMBERS, ShikakuRectValidity.OUT_OF_MASK -> LogicColors.Error
}

/**
 * Pantalla de "Neon Shikaku Matrix".
 *
 * Estructura idéntica a los demás juegos por niveles: antesala con selector ([GameIntroScreen]) →
 * tablero → [GameOverOverlay], con [GamePauseControls] encima.
 *
 * La pantalla no decide nada: convierte el dedo en celdas, emite [ShikakuIntent] y pinta el
 * [ShikakuUiState]. Los [ShikakuEffect] se vuelven sonido y vibración en un único `collect`.
 *
 * Todo el tablero es **un solo [Canvas]** (celdas, rectángulos, selección y números): los
 * rectángulos abarcan varias celdas y cambian en cada paso del arrastre, así que un composable por
 * celda obligaría a recomponer decenas de nodos por gesto; aquí un cambio de selección solo
 * invalida el dibujo.
 *
 * @param graph grafo de dependencias (repositorios, audio/háptica, anuncios).
 * @param onExit vuelve a la pantalla anterior.
 */
@Composable
fun ShikakuScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: ShikakuViewModel = viewModel {
        ShikakuViewModel(graph.progressRepository, graph.playerProgressRepository, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is ShikakuEffect.PlaySound -> graph.audio.playSound(effect.sound.effect)
                is ShikakuEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic.feedback)
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.shikaku,
            title = GAME_TITLE,
            description = stringResource(Res.string.shikaku_intro_description),
            accent = CategoryPalette.SpatialVision,
            icon = Icons.Rounded.Dashboard,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_SHIKAKU)
                vm.onIntent(ShikakuIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ShikakuHud(
                level = state.level,
                validation = state.validation,
                onRestart = { vm.onIntent(ShikakuIntent.RestartLevel) },
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isGenerating || state.mask.columns == 0) {
                    Text(
                        text = stringResource(Res.string.shikaku_board_generating),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LogicColors.OnDarkMuted,
                    )
                } else {
                    // Celda cuadrada: la mayor que quepa en ambos ejes. La caja del tablero mide
                    // exactamente columnas × filas celdas, así px→celda es una división sin márgenes.
                    val cell = minOf(maxWidth / state.mask.columns, maxHeight / state.mask.rows)
                    ShikakuBoard(
                        state = state,
                        onIntent = vm::onIntent,
                        modifier = Modifier.size(cell * state.mask.columns, cell * state.mask.rows),
                    )
                }
            }
        }

        val gameOver = state.gameOver
        if (state.status == GameStatus.FINISHED && gameOver != null) {
            GameOverOverlay(
                info = gameOver,
                audio = graph.audio,
                headline = stringResource(Res.string.shikaku_gameover_headline),
                onPlayAgain = { vm.onIntent(ShikakuIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(ShikakuIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(ShikakuIntent.ChooseLevel) },
                accent = CategoryPalette.SpatialVision,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(ShikakuIntent.Pause) },
            onResume = { vm.onIntent(ShikakuIntent.Resume) },
            onExit = onExit,
            gameTitle = GAME_TITLE,
            help = GameHelpContent.shikaku,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/** HUD superior: título y, debajo, las píldoras de nivel y cobertura junto al reinicio. */
@Composable
private fun ShikakuHud(
    level: Int,
    validation: ShikakuBoardValidation,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = GAME_TITLE,
            style = MaterialTheme.typography.titleLarge,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPill(stringResource(Res.string.shikaku_hud_level), level.toString())
            HudPill(
                stringResource(Res.string.shikaku_hud_covered),
                "${validation.coveredCells}/${validation.totalCells}",
            )
            HudIconButton(KortexIcons.Refresh, stringResource(Res.string.shikaku_hud_restart_description), onRestart)
        }
    }
}

@Composable
private fun HudIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .bounceClick(onClick = onClick)
            .background(LogicColors.SurfaceDark, shape = MaterialTheme.shapes.medium)
            .padding(8.dp),
    ) {
        NeonIcon(icon, tint = LogicColors.OnDarkMuted, size = 22.dp, glow = false, contentDescription = description)
    }
}

@Composable
private fun HudPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(LogicColors.SurfaceDark, shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark)
    }
}

/**
 * El tablero: dibujo + captura del gesto + badge flotante.
 *
 * **Gesto.** Se usa `awaitEachGesture` a mano en vez de `detectDragGestures` porque este último
 * espera a superar el *touch slop* antes de avisar: los primeros milímetros del arrastre se
 * perderían y el ancla podría caer en la celda vecina. Aquí el ancla es la celda EXACTA del
 * primer contacto y cada cambio de celda se emite en el mismo frame. Solo se emite al cambiar de
 * celda (no por píxel), que es el contrato de [ShikakuIntent.UpdateDrag].
 *
 * **Borde neón.** Los marcos reutilizan [drawNeonTile] (§9.7) pasándole la caja de cada
 * rectángulo. El "glow" de los rectángulos sale del halo de ese tubo y no de `Modifier.softGlow`:
 * ese modificador es una sombra de elevación sobre un composable, y aquí los rectángulos son
 * trazos dentro de un único Canvas translúcido, donde una sombra se vería como una mancha bajo el
 * relleno en vez de como un contorno luminoso.
 */
@Composable
private fun ShikakuBoard(
    state: ShikakuUiState,
    onIntent: (ShikakuIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mask = state.mask
    val currentOnIntent by rememberUpdatedState(onIntent)

    // Posición del dedo en píxeles del tablero: estado LOCAL de la UI. Solo sirve para colocar
    // el badge; el dominio trabaja en celdas y no necesita saberla.
    var finger by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    // Parpadeo de los rectángulos inválidos. Se lee solo dentro del bloque de dibujo, así que
    // anima invalidando el Canvas sin recomponer nada.
    val blink by rememberInfiniteTransition(label = "shikakuError").animateFloat(
        initialValue = 1f,
        targetValue = ERROR_BLINK_MIN,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shikakuErrorBlink",
    )

    // Destello del rectángulo recién sellado: el tubo se enciende a tope y se asienta con resorte.
    val newestId = state.rectangles.lastOrNull()?.id
    val sealFlash = remember { Animatable(0f) }
    LaunchedEffect(newestId) {
        if (newestId != null) {
            sealFlash.snapTo(1f)
            sealFlash.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val clueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
    val cellPx = if (mask.columns > 0) boardSize.width.toFloat() / mask.columns else 0f
    // Los números se miden una vez por nivel y tamaño, no en cada frame de dibujo.
    val clueLayouts = remember(state.clues, cellPx) {
        val fontSize = with(density) { (cellPx * CLUE_FONT_FRACTION).toSp() }
        state.clues.map { measurer.measure(it.number.toString(), clueStyle.copy(fontSize = fontSize)) }
    }

    Box(modifier = modifier.onSizeChanged { boardSize = it }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // La clave es la máscara: al cambiar de nivel se reinicia el detector con la
                // nueva rejilla y se descarta cualquier gesto a medias del tablero anterior.
                .pointerInput(mask) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val cellSize = size.width.toFloat() / mask.columns
                        fun cellOf(position: Offset) =
                            floor(position.x / cellSize).toInt() to floor(position.y / cellSize).toInt()

                        var last = cellOf(down.position)
                        finger = down.position
                        currentOnIntent(ShikakuIntent.StartDrag(last.first, last.second))
                        down.consume()

                        var released = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    currentOnIntent(ShikakuIntent.ReleaseDrag)
                                    break
                                }
                                finger = change.position
                                val cell = cellOf(change.position)
                                if (cell != last) {
                                    last = cell
                                    currentOnIntent(ShikakuIntent.UpdateDrag(cell.first, cell.second))
                                }
                                change.consume()
                            }
                        } finally {
                            // Gesto interrumpido (cancelación, puntero perdido): no se sella nada.
                            if (!released) currentOnIntent(ShikakuIntent.CancelDrag)
                        }
                    }
                },
        ) {
            val cell = size.width / mask.columns
            val gap = 1.dp.toPx()
            val cellCorner = CornerRadius(cell * 0.14f)
            val inner = Size(cell - 2 * gap, cell - 2 * gap)

            // 1) Celdas jugables. Las inhabilitadas no se pintan: se funden con el fondo.
            for (y in 0 until mask.rows) {
                for (x in 0 until mask.columns) {
                    if (!mask.isEnabled(x, y)) continue
                    val topLeft = Offset(x * cell + gap, y * cell + gap)
                    drawRoundRect(LogicColors.SurfaceDark, topLeft, inner, cellCorner)
                    drawRoundRect(LogicColors.SurfaceVariantDark, topLeft, inner, cellCorner, style = Stroke(1.dp.toPx()))
                }
            }

            // Relleno translúcido + tubo neón de un rectángulo en coordenadas de celda.
            fun frame(bounds: ShikakuRect, color: Color, glow: Float, alpha: Float) {
                val topLeft = Offset(bounds.left * cell, bounds.top * cell)
                val rectSize = Size(bounds.width * cell, bounds.height * cell)
                drawRoundRect(
                    color = color.copy(alpha = 0.14f * alpha),
                    topLeft = topLeft + Offset(gap, gap),
                    size = Size(rectSize.width - 2 * gap, rectSize.height - 2 * gap),
                    cornerRadius = cellCorner,
                )
                drawNeonTile(
                    baseColor = color,
                    activeAmt = glow.coerceIn(0f, 1f),
                    cornerRadius = 10.dp,
                    sparks = false,
                    baseMargin = 3.dp,
                    strokeScale = 0.7f,
                    rectTopLeft = topLeft,
                    rectSize = rectSize,
                    alpha = alpha,
                )
            }

            // 2) Rectángulos sellados.
            for (rectangle in state.rectangles) {
                val valid = rectangle.validity == ShikakuRectValidity.VALID
                val flash = if (rectangle.id == newestId) sealFlash.value else 0f
                frame(
                    bounds = rectangle.bounds,
                    color = if (valid) rectangle.tint.toColor() else LogicColors.Error,
                    glow = SEALED_GLOW + (1f - SEALED_GLOW) * flash,
                    alpha = if (valid) 1f else blink,
                )
            }

            // 3) Selección en curso, por encima de lo sellado (lo va a reemplazar al soltar).
            state.selection?.let { frame(it.bounds, it.validity.toSelectionColor(), glow = 0.9f, alpha = 1f) }

            // 4) Números, al final para que ningún relleno los tape. Una pista ya resuelta toma
            //    el color de su rectángulo: de un vistazo se ve cuáles quedan por atender.
            state.clues.forEachIndexed { i, clue ->
                val layout = clueLayouts.getOrNull(i) ?: return@forEachIndexed
                val owner = ShikakuRules.rectangleAt(state.rectangles, clue.x, clue.y)
                drawText(
                    textLayoutResult = layout,
                    color = if (owner?.validity == ShikakuRectValidity.VALID) owner.tint.toColor() else LogicColors.OnDark,
                    topLeft = Offset(
                        clue.x * cell + (cell - layout.size.width) / 2f,
                        clue.y * cell + (cell - layout.size.height) / 2f,
                    ),
                )
            }
        }

        DragBadge(selection = state.selection, finger = finger, boardSize = boardSize)
    }
}

/**
 * Badge flotante con el cálculo en vivo ("3 × 2 = 6") junto al dedo.
 *
 * Se coloca **encima** del dedo, no debajo: es el único sitio que la mano no tapa. Horizontalmente
 * se recorta al tablero para que no se salga de pantalla en las columnas extremas. Sigue al dedo
 * con un resorte rígido: lo bastante rápido para no quedarse atrás, lo bastante suave para no
 * temblar con cada píxel.
 */
@Composable
private fun DragBadge(selection: ShikakuSelection?, finger: Offset, boardSize: IntSize) {
    // Se recuerda la última selección para que el badge conserve su texto mientras se desvanece.
    var shown by remember { mutableStateOf(selection) }
    if (selection != null) shown = selection
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }

    val lift = with(LocalDensity.current) { 36.dp.roundToPx() }
    val target = IntOffset(
        x = (finger.x.toInt() - badgeSize.width / 2).coerceIn(0, (boardSize.width - badgeSize.width).coerceAtLeast(0)),
        y = finger.y.toInt() - badgeSize.height - lift,
    )
    val position by animateIntOffsetAsState(target, spring(stiffness = Spring.StiffnessHigh), label = "shikakuBadge")

    AnimatedVisibility(
        visible = selection != null,
        modifier = Modifier.offset { position }.onSizeChanged { badgeSize = it },
        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.6f)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val color = current.validity.toSelectionColor()
        val bounds = current.bounds
        val hint = when (current.validity) {
            ShikakuRectValidity.WRONG_AREA ->
                stringResource(Res.string.shikaku_badge_target, current.targetNumber.toString())
            ShikakuRectValidity.MULTIPLE_NUMBERS -> stringResource(Res.string.shikaku_badge_multiple)
            ShikakuRectValidity.OUT_OF_MASK -> stringResource(Res.string.shikaku_badge_out_of_mask)
            ShikakuRectValidity.VALID, ShikakuRectValidity.NO_NUMBER -> null
        }
        Column(
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                .border(1.5.dp, color.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    Res.string.shikaku_badge_area,
                    bounds.width.toString(), bounds.height.toString(), bounds.area.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
            }
        }
    }
}
