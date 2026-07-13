package com.example.kortexgames.game.neoncircuit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.rounded.Hub
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
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.bounceClick

// --- Constantes de composición de la placa ------------------------------------

/** Grosor del cable como fracción del tamaño de celda. */
private const val WIRE_WIDTH_FRACTION = 0.26f

/** Radio del nodo como fracción del tamaño de celda. */
private const val NODE_RADIUS_FRACTION = 0.30f

/** Nº de pasadas de resplandor (glow) alrededor de cables y nodos. */
private const val GLOW_LAYERS = 3

/** Cuánto engorda el cable al palpitar tras conectar un par (fracción extra). */
private const val PULSE_WIDTH_GAIN = 0.35f

/**
 * Traduce una **identidad de canal** ([WireColor], dominio puro) al token real de
 * `LogicColors` con el que se pinta. Este `when` es, a propósito, el ÚNICO sitio
 * del juego donde un canal se vuelve un color concreto: el dominio nunca conoce
 * `Color` (ver KDoc de [WireColor]).
 */
private fun WireColor.toAccent(): Color = when (this) {
    WireColor.CYAN -> LogicColors.NeonCyan
    WireColor.MAGENTA -> LogicColors.Magenta
    WireColor.GREEN -> LogicColors.NeonGreen
    WireColor.AMBER -> LogicColors.Amber
    WireColor.VIOLET -> LogicColors.Violet
    WireColor.BLUE -> LogicColors.Blue
    WireColor.CORAL -> LogicColors.Coral
}

/**
 * Pantalla de "Neon Circuit Flow".
 *
 * Estructura idéntica a los demás juegos LEVELED: antesala con selector de
 * niveles ([GameIntroScreen]) → placa a pantalla completa → [GameOverOverlay].
 *
 * A diferencia de Starport (una capa de composables por pieza), aquí los cables
 * son **trazos continuos** que no encajan en celdas: se pintan en un único
 * [Canvas] recorriendo un [Path] por los centros de celda, con `StrokeCap.Round`
 * y `StrokeJoin.Round` para que los codos salgan suaves (§9.4). El resplandor
 * neón se consigue con varias pasadas del mismo trazo a alpha decreciente
 * (glow barato, sin shaders de plataforma).
 *
 * La UI solo traduce px→celda y reporta la celda bajo el dedo; TODA la lógica
 * (adyacencia, cortes, victoria) la decide el motor vía intents. Ver el `when`
 * de casos en `NeonCircuitEngine.kt`.
 */
@Composable
fun NeonCircuitScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: NeonCircuitViewModel = viewModel {
        NeonCircuitViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Palpitación del cable recién conectado: un Animatable que salta a 1 y vuelve
    // a 0 con un spring rebotón (los "latidos" de §9.4). Se guarda qué canal
    // palpita para engordar solo ESE trazo.
    val pulse = remember { Animatable(0f) }
    var pulseColor by remember { mutableStateOf<WireColor?>(null) }

    // Único punto donde los Effects se vuelven sonido/vibración/animación.
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is NeonCircuitEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonCircuitEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                is NeonCircuitEffect.PulsePath -> {
                    pulseColor = effect.color
                    pulse.snapTo(1f)
                    // spring poco amortiguado = un par de latidos antes de calmarse.
                    pulse.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 380f))
                }
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Neon Circuit Flow",
            description = "La placa está desconectada. Arrastra desde cada nodo y tiende un cable de luz hasta su gemelo del mismo color, sin que dos cables se crucen. Conéctalos todos para energizar el circuito.",
            accent = CategoryPalette.ProblemSolving,
            icon = Icons.Rounded.Hub,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = { vm.onIntent(NeonCircuitIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    val game = state.game

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            CircuitHud(
                level = state.currentLevel,
                connected = game.connectedCount,
                total = game.pairCount,
                onRestart = { vm.onIntent(NeonCircuitIntent.Restart) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircuitBoard(
                    game = game,
                    onIntent = vm::onIntent,
                    pulseColor = pulseColor,
                    pulse = pulse.value,
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
                headline = "¡Circuito energizado!",
                onPlayAgain = { vm.onIntent(NeonCircuitIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(NeonCircuitIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(NeonCircuitIntent.ChooseLevel) },
            )
        }
    }
}

/** HUD superior: título, píldoras de nivel/conexiones y reinicio. */
@Composable
private fun CircuitHud(
    level: Int,
    connected: Int,
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Neon Circuit Flow",
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
            HudPill(label = "Conectados", value = "$connected/$total")
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
 * La placa: un único [Canvas] que dibuja fondo, cables y nodos, más una capa de
 * gestos encima. `BoxWithConstraints` fija la equivalencia celda↔px que comparten
 * dibujo y traducción de gestos.
 *
 * **Gesto (`detectDragGestures`):** al posar el dedo se mira si hay un nodo bajo la
 * celda inicial y, si lo hay, se arranca su trazo ([NeonCircuitIntent.StartPath]);
 * cada movimiento reporta la celda cruda bajo el dedo
 * ([NeonCircuitIntent.ExtendPath]) y el motor decide qué hacer con ella; al
 * levantar se cierra el trazo ([NeonCircuitIntent.EndPath]). La UI nunca valida
 * adyacencia ni cruces.
 */
@Composable
private fun CircuitBoard(
    game: NeonCircuitGameState,
    onIntent: (NeonCircuitIntent) -> Unit,
    pulseColor: WireColor?,
    pulse: Float,
    modifier: Modifier = Modifier,
) {
    val size = game.gridSize
    BoxWithConstraints(modifier = modifier) {
        val cellDp: Dp = maxWidth / size
        val cellPx: Float = with(LocalDensity.current) { cellDp.toPx() }

        // Convierte un punto (px) a celda, acotado al tablero: aunque el dedo se
        // salga por un borde, se mapea a la celda de borde más cercana.
        fun offsetToCell(o: Offset): GridPosition = GridPosition(
            row = (o.y / cellPx).toInt().coerceIn(0, size - 1),
            col = (o.x / cellPx).toInt().coerceIn(0, size - 1),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(size, cellPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cell = offsetToCell(offset)
                            // Solo se arranca trazo si el dedo posó sobre un nodo.
                            game.nodeAt(cell)?.let { node ->
                                onIntent(NeonCircuitIntent.StartPath(node.color, cell))
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onIntent(NeonCircuitIntent.ExtendPath(offsetToCell(change.position)))
                        },
                        onDragEnd = { onIntent(NeonCircuitIntent.EndPath) },
                        onDragCancel = { onIntent(NeonCircuitIntent.EndPath) },
                    )
                },
        ) {
            drawBoardBackdrop(size, cellPx)

            // Cables debajo, nodos encima (así los terminales tapan el arranque
            // del trazo y se leen como el "conector" del que sale el cable).
            game.paths.values.forEach { path ->
                val boost = if (path.color == pulseColor) 1f + PULSE_WIDTH_GAIN * pulse else 1f
                drawWire(path, cellPx, boost)
            }
            game.nodes.forEach { node ->
                drawNode(node, cellPx, connected = game.isColorConnected(node.color))
            }
        }
    }
}

/** Centro en px de una celda del tablero. */
private fun cellCenter(pos: GridPosition, cellPx: Float): Offset =
    Offset((pos.col + 0.5f) * cellPx, (pos.row + 0.5f) * cellPx)

/**
 * Fondo de la placa: superficie oscura redondeada y rejilla muy sutil, para que
 * el jugador perciba la cuadrícula sin que compita con los cables (§9.1).
 */
private fun DrawScope.drawBoardBackdrop(gridSize: Int, cellPx: Float) {
    val corner = CornerRadius(24.dp.toPx())
    drawRoundRect(color = LogicColors.SurfaceDark.copy(alpha = 0.62f), cornerRadius = corner)

    val gridColor = LogicColors.SurfaceVariantDark.copy(alpha = 0.45f)
    for (i in 1 until gridSize) {
        drawLine(gridColor, Offset(i * cellPx, 0f), Offset(i * cellPx, size.height), strokeWidth = 1.5f)
        drawLine(gridColor, Offset(0f, i * cellPx), Offset(size.width, i * cellPx), strokeWidth = 1.5f)
    }
    // Marco frío apagado (detalle, sin robar foco a los cables).
    drawRoundRect(
        color = LogicColors.NeonCyan.copy(alpha = 0.22f),
        cornerRadius = corner,
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * Dibuja un cable como trazo continuo con codos redondeados y halo neón.
 *
 * El halo son [GLOW_LAYERS] pasadas del MISMO [Path], cada una más ancha y más
 * translúcida, pintadas de fuera hacia dentro; encima va el trazo sólido. Con
 * `StrokeCap.Round` + `StrokeJoin.Round` tanto las puntas como los giros quedan
 * suaves. Un cable de una sola celda (solo el nodo de arranque) no traza línea:
 * lo representa el propio nodo.
 *
 * @param widthBoost multiplicador del grosor para la palpitación al conectar.
 */
private fun DrawScope.drawWire(path: WirePath, cellPx: Float, widthBoost: Float) {
    if (path.cells.size < 2) return
    val accent = path.color.toAccent()
    val baseWidth = cellPx * WIRE_WIDTH_FRACTION * widthBoost

    val line = Path().apply {
        val first = cellCenter(path.cells.first(), cellPx)
        moveTo(first.x, first.y)
        for (i in 1 until path.cells.size) {
            val c = cellCenter(path.cells[i], cellPx)
            lineTo(c.x, c.y)
        }
    }

    for (layer in GLOW_LAYERS downTo 1) {
        drawPath(
            path = line,
            color = accent.copy(alpha = 0.12f * (GLOW_LAYERS + 1 - layer)),
            style = Stroke(
                width = baseWidth + layer * (cellPx * 0.10f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
    drawPath(
        path = line,
        color = accent,
        style = Stroke(width = baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Dibuja un nodo: círculo de luz con halo (mismas pasadas concéntricas que el
 * cable) y un punto oscuro central que le da profundidad de "conector". Cuando su
 * canal ya está conectado, se le añade un anillo blanco tenue como sello de
 * "cerrado" (feedback de estado, no decorativo).
 */
private fun DrawScope.drawNode(node: Node, cellPx: Float, connected: Boolean) {
    val accent = node.color.toAccent()
    val center = cellCenter(node.position, cellPx)
    val radius = cellPx * NODE_RADIUS_FRACTION

    for (layer in GLOW_LAYERS downTo 1) {
        drawCircle(
            color = accent.copy(alpha = 0.10f * (GLOW_LAYERS + 1 - layer)),
            radius = radius + layer * (cellPx * 0.08f),
            center = center,
        )
    }
    drawCircle(color = accent, radius = radius, center = center)
    drawCircle(
        color = LogicColors.BackgroundDark.copy(alpha = 0.40f),
        radius = radius * 0.42f,
        center = center,
    )
    if (connected) {
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = radius * 1.15f,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
