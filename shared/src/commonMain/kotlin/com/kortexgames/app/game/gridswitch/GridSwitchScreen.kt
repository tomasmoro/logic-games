package com.kortexgames.app.game.gridswitch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.orthogonalNeighbors
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.grid_switch_gameover_headline
import kortexgames.shared.generated.resources.grid_switch_hud_moves
import kortexgames.shared.generated.resources.grid_switch_hud_stage
import kortexgames.shared.generated.resources.grid_switch_intro_description
import kortexgames.shared.generated.resources.grid_switch_restart_description
import kortexgames.shared.generated.resources.grid_switch_subtitle
import org.jetbrains.compose.resources.stringResource

/**
 * Color de acento de las luces encendidas. Cian eléctrico ("foco", §9.2) y no el
 * verde neón: aquí lo encendido es justo lo que el jugador tiene que resolver,
 * no un logro — el verde de acción se reserva a la celebración de etapa
 * completada ([com.kortexgames.app.ui.components.GameOverOverlay]).
 */
private val LitAccent = LogicColors.NeonCyan

/**
 * Duración del "pop" de conmutación: escala + destello breve sobre las celdas
 * afectadas por el último toque (spring/tween corto, §9.4 micro-feedback).
 */
private const val TOGGLE_FLASH_MS = 220

/**
 * Pantalla de "Neon Grid Switch".
 *
 * Estructura análoga a los demás juegos LEVELED del catálogo (antesala → tablero
 * a pantalla completa → [GameOverOverlay]), pero SIN carril de niveles: las
 * etapas se suceden en orden estricto desde la 1ª (ver KDoc de
 * [GridSwitchContract]), así que la antesala es la de un juego sin selector
 * (como Burbujas de Cálculo o Neon Legion) — [GameIntroScreen] con `levels =
 * null` y un único CTA "Comenzar".
 *
 * El tablero es un único [Canvas] de lado adaptable: `BoxWithConstraints` fija
 * el tamaño de celda a partir del ancho disponible y el lado de la cuadrícula
 * ([GridSwitchUiState.gridSize], 3..6), y cada celda se dibuja como tubo de
 * neón hueco/encendido con [drawNeonTile] (§9.7) — la MISMA función que usan
 * las teclas de Memoria y las celdas de Crucigrama, para que el lenguaje visual
 * de "neón" sea uno solo en toda la app.
 *
 * La UI solo traduce px→celda; TODA la regla (qué se conmuta, conteo de
 * movimientos, victoria) la decide el motor vía [GridSwitchIntent.ToggleCell]
 * (mismo reparto de responsabilidades que Línea Neón).
 */
@Composable
fun GridSwitchScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: GridSwitchViewModel = viewModel {
        GridSwitchViewModel(graph.progressRepository, graph.audio, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Celdas que afectó el ÚLTIMO toque (ella + vecinas existentes) y cuánto les
    // queda de "pop": vive en la UI (no en el motor) porque es una animación
    // puntual de feedback táctil, no una regla de juego.
    var flashedCells by remember { mutableStateOf<Set<GridPosition>>(emptySet()) }
    val flashAmount = remember { Animatable(0f) }
    // Token que se incrementa en CADA toque, incluso si repite las mismas celdas
    // del anterior (p. ej. tocar dos veces seguidas la misma celda): un `Set`
    // idéntico no dispararía de nuevo el `LaunchedEffect` de abajo si se usara
    // `flashedCells` como key (los Set comparan por contenido), así que el
    // re-disparo se ata a este contador y no al valor del set.
    var tapToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is GridSwitchEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is GridSwitchEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
            }
        }
    }

    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.gridSwitch,
            title = "Neon Grid Switch",
            description = stringResource(Res.string.grid_switch_intro_description),
            accent = CategoryPalette.PatternRecognition,
            motif = GameMotif.LIGHTS_GRID,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta
                // terminar la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_GRID_SWITCH)
                vm.onIntent(GridSwitchIntent.StartGame)
            },
            onExit = onExit,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GridSwitchHud(
                stage = state.stageLevel,
                moves = state.moveCount,
                onRestart = { vm.onIntent(GridSwitchIntent.RestartStage) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                GridSwitchBoard(
                    board = state.board,
                    flashedCells = flashedCells,
                    flashAmount = flashAmount.value,
                    onCellTapped = { cell ->
                        // La flash es puramente visual: se dispara con el toque, tanto si
                        // el motor termina aceptándolo como si lo ignora (partida no
                        // RUNNING o etapa ya resuelta) — ese caso es raro y el destello de
                        // más no es perceptible como error.
                        flashedCells = (cell.orthogonalNeighbors(state.gridSize) + cell).toSet()
                        tapToken++
                        vm.onIntent(GridSwitchIntent.ToggleCell(cell))
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = stringResource(Res.string.grid_switch_gameover_headline),
                onPlayAgain = { vm.onIntent(GridSwitchIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(GridSwitchIntent.NextStage) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(GridSwitchIntent.Pause) },
            onResume = { vm.onIntent(GridSwitchIntent.Resume) },
            onExit = onExit,
            gameTitle = "Neon Grid Switch",
            help = GameHelpContent.gridSwitch,
            accent = CategoryPalette.PatternRecognition,
        )
    }

    // Dispara el "pop" de conmutación en cada toque (ver KDoc de [tapToken]).
    LaunchedEffect(tapToken) {
        if (tapToken > 0) {
            flashAmount.snapTo(1f)
            flashAmount.animateTo(0f, tween(durationMillis = TOGGLE_FLASH_MS))
        }
    }
}

/** HUD superior: título, píldoras de etapa/movimientos y reinicio. */
@Composable
private fun GridSwitchHud(
    stage: Int,
    moves: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Neon Grid Switch",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(Res.string.grid_switch_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPill(label = stringResource(Res.string.grid_switch_hud_stage), value = stage.toString())
            HudPill(label = stringResource(Res.string.grid_switch_hud_moves), value = moves.toString())
            Box(
                modifier = Modifier
                    .bounceClick(onClick = onRestart)
                    .background(
                        LogicColors.SurfaceDark.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.medium,
                    )
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = KortexIcons.Refresh,
                    contentDescription = stringResource(Res.string.grid_switch_restart_description),
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
 * El tablero: un único [Canvas] con fondo, cuadrícula de tubos de neón y una capa
 * de gestos encima. `BoxWithConstraints` fija la equivalencia celda↔px que
 * comparten el dibujo y la traducción del toque —igual criterio que el tablero de
 * Línea Neón—, para que ambos no puedan desalinearse aunque el lado de la
 * cuadrícula cambie de una etapa a otra (3×3 hasta 6×6).
 *
 * **Gesto (`detectTapGestures`):** cada toque se traduce a la celda bajo el dedo y
 * se reporta tal cual ([onCellTapped]); la UI NUNCA decide qué se conmuta ni
 * valida nada — eso es 100% del motor (ver [GridSwitchEngine.onCellToggled]).
 */
@Composable
private fun GridSwitchBoard(
    board: LightGrid,
    flashedCells: Set<GridPosition>,
    flashAmount: Float,
    onCellTapped: (GridPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = board.size

    BoxWithConstraints(modifier = modifier) {
        val cellDp: Dp = maxWidth / size
        val cellPx: Float = with(LocalDensity.current) { cellDp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(size, cellPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            val cell = GridPosition(
                                row = (offset.y / cellPx).toInt().coerceIn(0, size - 1),
                                col = (offset.x / cellPx).toInt().coerceIn(0, size - 1),
                            )
                            onCellTapped(cell)
                        },
                    )
                },
        ) {
            drawBoardBackdrop()
            for (row in 0 until size) {
                for (col in 0 until size) {
                    val pos = GridPosition(row, col)
                    val lit = board.cellAt(pos)
                    val flashed = pos in flashedCells
                    drawNeonTile(
                        baseColor = LitAccent,
                        activeAmt = if (lit) 1f else 0f,
                        cornerRadius = (cellPx * 0.22f).toDp(),
                        sparks = false,
                        baseMargin = (cellPx * 0.07f).toDp(),
                        strokeScale = 0.85f,
                        rectTopLeft = Offset(col * cellPx, row * cellPx),
                        rectSize = Size(cellPx, cellPx),
                        // Pop de conmutación: brillo de presión independiente del
                        // encendido, así una celda que se APAGA con el toque también
                        // destella (si solo dependiera de activeAmt no se vería nada).
                        pressAmt = if (flashed) flashAmount else 0f,
                        scale = if (flashed) 1f + 0.12f * flashAmount else 1f,
                    )
                }
            }
        }
    }
}

/**
 * Fondo del tablero: superficie oscura redondeada con un marco frío apagado.
 * Deliberadamente discreto (no un borde de neón encendido, §9.7): encima van
 * hasta 36 tubos de neón propios, y un bezel intenso competiría con ellos.
 */
private fun DrawScope.drawBoardBackdrop() {
    val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())
    drawRoundRect(
        color = LogicColors.SurfaceDark,
        size = size,
        cornerRadius = corner,
    )
}
