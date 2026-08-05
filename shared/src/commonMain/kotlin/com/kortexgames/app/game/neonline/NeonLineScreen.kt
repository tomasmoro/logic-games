package com.kortexgames.app.game.neonline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timeline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import kotlin.math.abs

// --- Constantes de composición del tablero ------------------------------------

/** Grosor de la línea como fracción del tamaño de celda. */
private const val LINE_WIDTH_FRACTION = 0.30f

/** Lado del bloque-obstáculo como fracción de la celda (deja aire alrededor). */
private const val OBSTACLE_SIZE_FRACTION = 0.78f

/** Radio del punto que marca una celda vacía, como fracción de la celda. */
private const val EMPTY_DOT_FRACTION = 0.07f

/** Radio base de la punta luminosa, como fracción de la celda. */
private const val HEAD_RADIUS_FRACTION = 0.22f

/** Cuánto crece la punta en el pico del latido (fracción extra). */
private const val HEAD_PULSE_GAIN = 0.28f

/**
 * Color del circuito. Es el cian de "foco" del sistema de diseño (§9.2) y no el
 * ámbar de la categoría (`CategoryPalette.ProblemSolving`) a propósito: el ámbar se
 * reserva aquí para el cromo del juego —HUD, marco, intro—, de modo que la línea que
 * el jugador dibuja sea el ÚNICO elemento cian de la pantalla y se lea al instante
 * como "lo que estoy haciendo".
 */
private val LineAccent: Color = LogicColors.NeonCyan

/**
 * Pantalla de "Línea Neón".
 *
 * Estructura idéntica a los demás juegos LEVELED: antesala con selector de niveles
 * ([GameIntroScreen]) → tablero a pantalla completa → [GameOverOverlay].
 *
 * Igual que en Conectores, la línea es un **trazo continuo** que no encaja en celdas:
 * se pinta en un único [Canvas] recorriendo un [Path] por los centros de celda, con
 * `StrokeCap.Round` y `StrokeJoin.Round` para que los codos salgan suaves (§9.4). El
 * resplandor neón se consigue con varias pasadas del mismo trazo a alpha decreciente
 * (halo ancho → intermedio → nítido → núcleo, la receta única del proyecto para
 * bordes de neón, §9.7), sin shaders de plataforma.
 *
 * La UI solo traduce px→celda y reporta la celda bajo el dedo; TODA la lógica
 * (adyacencia, obstáculos, retroceso, victoria) la decide el motor vía intents. Ver
 * el `when` de casos en `NeonLineEngine.kt`.
 */
@Composable
fun NeonLineScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: NeonLineViewModel = viewModel {
        NeonLineViewModel(
            graph.progressRepository,
            graph.playerProgressRepository,
            graph.audio,
            graph.adManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Destello rojo de movimiento ilegal: qué celda y cuánto le queda de destello.
    // Vive en la UI (no en el State del motor) porque es una animación puntual; el
    // motor solo dice "esta celda se rechazó" y la pantalla decide cómo se ve.
    var rejectedCell by remember { mutableStateOf<GridPosition?>(null) }
    val rejectFlash = remember { Animatable(0f) }

    // Barrido de luz que recorre la línea al completar el circuito (0→1 = de la
    // primera celda a la última).
    val completionSweep = remember { Animatable(0f) }

    // Único punto donde los Effects se vuelven sonido/vibración/animación.
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is NeonLineEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonLineEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                is NeonLineEffect.RejectMove -> {
                    rejectedCell = effect.cell
                    rejectFlash.snapTo(1f)
                    rejectFlash.animateTo(0f, tween(durationMillis = 320, easing = LinearEasing))
                }
                NeonLineEffect.CircuitCompleted -> {
                    completionSweep.snapTo(0f)
                    completionSweep.animateTo(1f, tween(durationMillis = 620, easing = LinearEasing))
                }
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.neonLine,
            title = "Línea Neón",
            motif = GameMotif.SINGLE_LINE,
            description = "La placa está a oscuras. Traza una sola línea de luz que pase por TODAS las celdas libres sin levantar el dedo, esquivando los bloques y sin cruzarte contigo mismo.",
            accent = CategoryPalette.ProblemSolving,
            icon = Icons.Rounded.Timeline,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta
                // terminar la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_LINE)
                vm.onIntent(NeonLineIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    val game = state.game

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            NeonLineHud(
                level = state.currentLevel,
                filled = game.path.size,
                total = game.playableCount,
                onRestart = { vm.onIntent(NeonLineIntent.RestartLevel) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                NeonLineBoard(
                    game = game,
                    onIntent = vm::onIntent,
                    rejectedCell = rejectedCell,
                    rejectAmount = rejectFlash.value,
                    sweep = completionSweep.value,
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
                headline = "¡Circuito completo!",
                onPlayAgain = { vm.onIntent(NeonLineIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(NeonLineIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(NeonLineIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(NeonLineIntent.Pause) },
            onResume = { vm.onIntent(NeonLineIntent.Resume) },
            onExit = onExit,
            gameTitle = "Línea Neón",
            help = GameHelpContent.neonLine,
            accent = CategoryPalette.ProblemSolving,
        )
    }
}

/** HUD superior: título, píldoras de nivel/celdas y reinicio. */
@Composable
private fun NeonLineHud(
    level: Int,
    filled: Int,
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Línea Neón",
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
            HudPill(label = "Celdas", value = "$filled/$total")
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
 * El tablero: un único [Canvas] con fondo, bloques, línea y punta, más una capa de
 * gestos encima. `BoxWithConstraints` fija la equivalencia celda↔px que comparten el
 * dibujo y la traducción de gestos, que es lo que hace que ambos no puedan
 * desalinearse.
 *
 * **Gesto (`detectDragGestures`):** al posar el dedo se reporta la celda de inicio
 * ([NeonLineIntent.StartPath]); cada movimiento reporta la celda cruda bajo el dedo
 * ([NeonLineIntent.UpdatePath]) y el motor decide qué hacer con ella; al levantar se
 * cierra el trazo ([NeonLineIntent.ReleasePath]). La UI **nunca** valida adyacencia,
 * obstáculos ni retrocesos: si lo hiciera, la regla viviría duplicada en dos sitios y
 * el motor dejaría de ser la única fuente de verdad.
 *
 * `change.consume()` en cada movimiento evita que el gesto escale a contenedores con
 * scroll y corte el arrastre a medio trazo.
 */
@Composable
private fun NeonLineBoard(
    game: NeonLineGameState,
    onIntent: (NeonLineIntent) -> Unit,
    rejectedCell: GridPosition?,
    rejectAmount: Float,
    sweep: Float,
    modifier: Modifier = Modifier,
) {
    val size = game.gridSize

    // Latido lento y continuo de la punta (§9.4: bucle de baja amplitud, y SOLO en
    // este elemento — es la guía de "aquí está tu dedo", si latieran más cosas
    // competirían por la atención).
    val headTransition = rememberInfiniteTransition(label = "headPulse")
    val headPulse by headTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    BoxWithConstraints(modifier = modifier) {
        val cellDp: Dp = maxWidth / size
        val cellPx: Float = with(LocalDensity.current) { cellDp.toPx() }

        // Convierte un punto (px) a celda, acotado al tablero: aunque el dedo se
        // salga por un borde, se mapea a la celda de borde más cercana en vez de
        // producir coordenadas fuera de rango.
        fun offsetToCell(o: Offset): GridPosition = GridPosition(
            row = (o.y / cellPx).toInt().coerceIn(0, size - 1),
            col = (o.x / cellPx).toInt().coerceIn(0, size - 1),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(size, cellPx) {
                    detectDragGestures(
                        onDragStart = { offset -> onIntent(NeonLineIntent.StartPath(offsetToCell(offset))) },
                        onDrag = { change, _ ->
                            change.consume()
                            onIntent(NeonLineIntent.UpdatePath(offsetToCell(change.position)))
                        },
                        onDragEnd = { onIntent(NeonLineIntent.ReleasePath) },
                        onDragCancel = { onIntent(NeonLineIntent.ReleasePath) },
                    )
                },
        ) {
            drawBoardBackdrop(size, cellPx)
            drawCells(game, cellPx)
            drawLine(game, cellPx, sweep)
            game.head?.let { drawHead(it, cellPx, headPulse, solved = game.isSolved) }
            if (rejectedCell != null && rejectAmount > 0f) {
                drawRejectFlash(rejectedCell, cellPx, rejectAmount)
            }
        }
    }
}

/** Centro en px de una celda del tablero. */
private fun cellCenter(pos: GridPosition, cellPx: Float): Offset =
    Offset((pos.col + 0.5f) * cellPx, (pos.row + 0.5f) * cellPx)

/**
 * Fondo del tablero: superficie oscura redondeada, rejilla muy sutil y un marco frío
 * apagado.
 *
 * El marco es deliberadamente **discreto** y no un borde de neón encendido: sobre
 * este tablero van rejilla, bloques y una línea brillante, y un bezel intenso
 * competiría con ellos (§9.7 lo dice explícitamente para tableros con mucho
 * contenido encima).
 */
private fun DrawScope.drawBoardBackdrop(gridSize: Int, cellPx: Float) {
    val corner = CornerRadius(24.dp.toPx())
    drawRoundRect(color = LogicColors.SurfaceDark.copy(alpha = 0.62f), cornerRadius = corner)

    val gridColor = LogicColors.SurfaceVariantDark.copy(alpha = 0.45f)
    for (i in 1 until gridSize) {
        drawLine(gridColor, Offset(i * cellPx, 0f), Offset(i * cellPx, size.height), strokeWidth = 1.5f)
        drawLine(gridColor, Offset(0f, i * cellPx), Offset(size.width, i * cellPx), strokeWidth = 1.5f)
    }
    drawRoundRect(
        color = LineAccent.copy(alpha = 0.22f),
        cornerRadius = corner,
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * Celdas de fondo: bloques inertes y puntos de "queda por llenar".
 *
 * Los **obstáculos** son bloques de [LogicColors.SurfaceVariantDark] con esquinas
 * casi rectas y cuatro patillas laterales: el lenguaje visual del microchip soldado
 * a la placa. Nada de neón ni de halo — son lo único inerte de la pantalla, y esa
 * frialdad es justo lo que los hace legibles al instante como "por aquí no".
 *
 * Las **celdas vacías** llevan un punto minúsculo. Sin él, un tablero vacío se lee
 * como una superficie uniforme y el jugador no percibe cuántas casillas le faltan;
 * con él, la cuadrícula "pide" ser recorrida y ver desaparecer los puntos bajo la
 * línea es parte de la recompensa.
 */
private fun DrawScope.drawCells(game: NeonLineGameState, cellPx: Float) {
    val blockSide = cellPx * OBSTACLE_SIZE_FRACTION
    val pinLength = cellPx * 0.08f
    val pinWidth = cellPx * 0.05f

    for (row in 0 until game.gridSize) {
        for (col in 0 until game.gridSize) {
            val cell = GridPosition(row, col)
            val center = cellCenter(cell, cellPx)
            when (game.cellState(cell)) {
                NeonLineCellState.OBSTACLE -> {
                    val topLeft = Offset(center.x - blockSide / 2, center.y - blockSide / 2)
                    // Patillas del chip: cuatro trazos cortos que asoman por los lados.
                    listOf(
                        Offset(topLeft.x - pinLength, center.y),
                        Offset(topLeft.x + blockSide, center.y),
                    ).forEach { p ->
                        drawRect(
                            color = LogicColors.SurfaceVariantDark,
                            topLeft = Offset(p.x, p.y - pinWidth / 2),
                            size = Size(pinLength, pinWidth),
                        )
                    }
                    drawRoundRect(
                        color = LogicColors.SurfaceVariantDark,
                        topLeft = topLeft,
                        size = Size(blockSide, blockSide),
                        cornerRadius = CornerRadius(cellPx * 0.08f),
                    )
                    drawRoundRect(
                        color = LogicColors.BackgroundDark.copy(alpha = 0.55f),
                        topLeft = topLeft,
                        size = Size(blockSide, blockSide),
                        cornerRadius = CornerRadius(cellPx * 0.08f),
                        style = Stroke(width = 1.5f.dp.toPx()),
                    )
                }
                NeonLineCellState.EMPTY -> drawCircle(
                    color = LogicColors.OnDarkMuted.copy(alpha = 0.35f),
                    radius = cellPx * EMPTY_DOT_FRACTION,
                    center = center,
                )
                // Las celdas del trazo no se pintan aquí: las cubre la propia línea.
                NeonLineCellState.VISITED -> Unit
            }
        }
    }
}

/**
 * La línea de luz: un [Path] por los centros de las celdas visitadas, con la misma
 * receta de "tubo de neón" que el resto del proyecto (halo ancho → halo intermedio →
 * trazo nítido → núcleo blanco, §9.7). `StrokeCap.Round` + `StrokeJoin.Round` hacen
 * que puntas y codos salgan suaves.
 *
 * Un trazo de una sola celda no dibuja línea: lo representa la propia punta.
 *
 * @param sweep 0..1 del barrido de celebración. Recorre la línea iluminando un tramo
 *   móvil ([SWEEP_HALF_WIDTH] a cada lado): es la lectura de "la corriente por fin
 *   circula", y por eso viaja de la primera celda a la última en vez de encender todo
 *   de golpe. A 0 (o ya terminado) no dibuja nada.
 */
private fun DrawScope.drawLine(game: NeonLineGameState, cellPx: Float, sweep: Float) {
    val cells = game.path
    if (cells.size < 2) return
    val stroke = cellPx * LINE_WIDTH_FRACTION

    val line = Path().apply {
        val first = cellCenter(cells.first(), cellPx)
        moveTo(first.x, first.y)
        for (i in 1 until cells.size) {
            val c = cellCenter(cells[i], cellPx)
            lineTo(c.x, c.y)
        }
    }

    // Halo exterior ancho y translúcido.
    drawPath(
        path = line,
        color = LineAccent.copy(alpha = 0.22f),
        style = Stroke(width = stroke * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Halo intermedio: da cuerpo al resplandor.
    drawPath(
        path = line,
        color = LineAccent.copy(alpha = 0.40f),
        style = Stroke(width = stroke * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Trazo nítido del "tubo" neón.
    drawPath(
        path = line,
        color = LineAccent,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Núcleo blanco interior: el look "prendido" del neón real.
    drawPath(
        path = line,
        color = Color.White.copy(alpha = 0.28f),
        style = Stroke(width = stroke * 0.42f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Barrido de celebración: se ilumina celda a celda según su distancia al frente
    // del barrido. Se dibuja por celdas (y no como un degradado sobre el Path) porque
    // un Path arbitrario no tiene una parametrización de longitud barata en Compose,
    // y el índice de celda ya es esa parametrización, gratis.
    if (sweep > 0f && sweep < 1f) {
        cells.forEachIndexed { index, cell ->
            val position = index.toFloat() / (cells.size - 1)
            val distance = abs(position - sweep)
            if (distance < SWEEP_HALF_WIDTH) {
                val amount = 1f - distance / SWEEP_HALF_WIDTH
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f * amount),
                    radius = stroke * (0.5f + 0.35f * amount),
                    center = cellCenter(cell, cellPx),
                )
            }
        }
    }
}

/**
 * La punta de la línea: círculo blanco/cian con halo que **late** suavemente. Es la
 * única animación en bucle de la pantalla (§9.4: el latido guía, y solo debe haber
 * uno) y responde a la pregunta que el jugador se hace sin parar mientras arrastra:
 * "¿dónde estoy?". Al resolver el nivel deja de destacar sobre el resto (ya no hay
 * nada que guiar): se dibuja sin el latido.
 */
private fun DrawScope.drawHead(head: GridPosition, cellPx: Float, pulse: Float, solved: Boolean) {
    val center = cellCenter(head, cellPx)
    val beat = if (solved) 0f else pulse
    val radius = cellPx * HEAD_RADIUS_FRACTION * (1f + HEAD_PULSE_GAIN * beat)

    drawCircle(color = LineAccent.copy(alpha = 0.18f + 0.12f * beat), radius = radius * 2.1f, center = center)
    drawCircle(color = LineAccent.copy(alpha = 0.35f + 0.15f * beat), radius = radius * 1.45f, center = center)
    drawCircle(color = Color.White.copy(alpha = 0.92f), radius = radius * 0.62f, center = center)
}

/**
 * Destello rojo sobre una celda a la que la línea no puede entrar. Es feedback de
 * *por qué* no avanzó: sin él, un movimiento ilegal se percibe como que el juego "no
 * responde". [amount] va de 1 (recién rechazado) a 0.
 */
private fun DrawScope.drawRejectFlash(cell: GridPosition, cellPx: Float, amount: Float) {
    val center = cellCenter(cell, cellPx)
    val side = cellPx * OBSTACLE_SIZE_FRACTION
    drawRoundRect(
        color = LogicColors.Error.copy(alpha = 0.55f * amount),
        topLeft = Offset(center.x - side / 2, center.y - side / 2),
        size = Size(side, side),
        cornerRadius = CornerRadius(cellPx * 0.08f),
    )
}

/** Mitad del ancho (en fracción de la línea) del tramo que ilumina el barrido final. */
private const val SWEEP_HALF_WIDTH = 0.18f
