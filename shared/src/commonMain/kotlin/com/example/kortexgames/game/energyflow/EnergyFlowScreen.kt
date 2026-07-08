package com.example.kortexgames.game.energyflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.CitySkylineBackground
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick

/** Color de las tuberías **energizadas** (energía cian que fluye desde la batería). */
private val PipePowered = LogicColors.NeonCyan

/** Color de las tuberías apagadas (aún sin energía): tenue para no competir con el neón. */
private val PipeIdle = LogicColors.OnDarkMuted.copy(alpha = 0.40f)

/** Color de la batería (fuente): verde neón, siempre encendida. */
private val SourceColor = LogicColors.NeonGreen

/** Color de la bombilla encendida (destino alimentado). */
private val TargetLit = LogicColors.Amber

/**
 * Pantalla de "Flujo de Energía". Observa el estado del ViewModel y pinta la rejilla
 * de tuberías; un toque en una pieza la gira 90° ([EnergyFlowIntent.RotateTile]) y el
 * motor recalcula qué celdas quedan **energizadas** desde la batería. Al cerrar el
 * circuito, superpone [GameOverOverlay].
 *
 * Cada pieza se dibuja en su propio [Canvas] (rendimiento holgado incluso en 8×8) y
 * anima el giro con `graphicsLayer { rotationZ }` sobre física de resorte (§9.4): la
 * geometría se pinta en su orientación resuelta y la rotación la aporta la capa
 * gráfica, de modo que el giro es fluido y siempre en sentido horario.
 *
 * El tablero se envuelve en `key(boardGen)`: al **reiniciar** o **volver a jugar** se
 * fuerza una recomposición limpia para que las piezas "salten" al nuevo barajado en
 * vez de desenroscarse con una animación larga hacia atrás.
 */
@Composable
fun EnergyFlowScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: EnergyFlowViewModel = viewModel {
        EnergyFlowViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Fase de intro: antesala del juego con icono, descripción y carril de niveles.
    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Flujo de Energía",
            description = "Gira las piezas para llevar la energía de la batería a la bombilla.",
            accent = CategoryPalette.SpatialVision,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = { vm.onIntent(EnergyFlowIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            background = {
                CitySkylineBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.SpatialVision)
            },
        )
        return
    }

    val game = state.game

    // Generación del tablero: sube al reiniciar/reempezar para recomponer desde cero.
    var boardGen by remember { mutableStateOf(0) }

    // Latido lento y de baja amplitud del halo de energía (ambiente, §9.4).
    val pulse by rememberInfiniteTransition(label = "energyPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "energyPulseAlpha",
    )

    Box(Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Skyline de ciudad nocturna en el índigo de "Visión Espacial", muy sutil.
        CitySkylineBackground(
            modifier = Modifier.fillMaxSize(),
            accent = CategoryPalette.SpatialVision,
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Flujo de Energía",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${game.rotations} giros",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.Electric,
            )
            Text(
                "Nivel ${game.round}",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.NeonGreen,
            )
            Text(
                "Gira las piezas para llevar la energía de la batería a la bombilla",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // Tablero cuadrado centrado, ocupa el espacio disponible.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val grid = game.grid
                if (grid.cols > 0) {
                    val side = min(maxWidth, maxHeight)
                    val cell = side / grid.cols
                    Column {
                        for (row in 0 until grid.rows) {
                            Row {
                                for (col in 0 until grid.cols) {
                                    val index = row * grid.cols + col
                                    // key(boardGen): recomposición limpia al reiniciar.
                                    androidx.compose.runtime.key(boardGen, index) {
                                        EnergyTileView(
                                            tile = grid.tiles[index],
                                            powered = index in game.powered,
                                            pulse = pulse,
                                            onRotate = { vm.onIntent(EnergyFlowIntent.RotateTile(index)) },
                                            modifier = Modifier.size(cell),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Reiniciar el mismo nivel (vuelve al barajado inicial).
            RestartButton(
                enabled = !game.solved,
                onClick = {
                    boardGen++
                    vm.onIntent(EnergyFlowIntent.Restart)
                },
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = {
                    boardGen++
                    vm.onIntent(EnergyFlowIntent.PlayAgain)
                },
                onExit = onExit,
                onNextLevel = {
                    boardGen++
                    vm.onIntent(EnergyFlowIntent.NextLevel)
                },
                onChooseLevel = { vm.onIntent(EnergyFlowIntent.ChooseLevel) },
            )
        }
    }
}

/**
 * Una pieza del tablero. Dibuja sus tuberías en la orientación **resuelta** y delega
 * el giro a `graphicsLayer { rotationZ }`, animado con resorte para que el giro se
 * sienta táctil (§9.4). Las tuberías se pintan con el color de energía si la pieza
 * está [powered], o apagadas en caso contrario; la fuente/destino añaden su nodo.
 */
@Composable
private fun EnergyTileView(
    tile: Tile,
    powered: Boolean,
    pulse: Float,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ángulo objetivo = nº de giros × 90°. Como el modelo solo suma giros, la
    // animación de resorte siempre gira en sentido horario (nunca desenrosca).
    val angle by animateFloatAsState(
        targetValue = tile.rotation * 90f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tileRotation",
    )

    Box(modifier = modifier.bounceClick(onClick = onRotate), contentAlignment = Alignment.Center) {
        // Capa 1 — fondo de la celda ESTÁTICO: no rota, así sus esquinas cuadradas
        // nunca barren sobre las celdas vecinas al girar la pieza.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCell()
        }
        // Capa 2 — tuberías + nodo, que SÍ rotan. Sus extremos viven en el círculo
        // inscrito de la celda (radio = medio lado), luego se mantienen dentro de sus
        // propios límites durante el giro y no se solapan con las celdas contiguas.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle },
        ) {
            drawPipes(tile.connectors, powered, pulse)
            when (tile.kind) {
                TileKind.SOURCE -> drawNode(SourceColor, lit = true, pulse = pulse)
                TileKind.TARGET -> drawNode(TargetLit, lit = powered, pulse = pulse)
                TileKind.PIPE -> Unit
            }
        }
    }
}

/** Fondo de la celda: superficie redondeada con borde tenue para leer la rejilla. */
private fun DrawScope.drawCell() {
    val inset = 1.5.dp.toPx()
    val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    drawRoundRect(
        color = LogicColors.SurfaceDark,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = LogicColors.SurfaceVariantDark,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = corner,
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * Dibuja las tuberías de la pieza: un tramo del centro a cada lado con [connectors],
 * más un buje central. Si está [powered], añade un halo neón (más ancho y translúcido,
 * pulsando con [pulse]) tras el núcleo brillante; si no, un trazo tenue.
 */
private fun DrawScope.drawPipes(connectors: Set<Direction>, powered: Boolean, pulse: Float) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val pipeWidth = w * 0.24f
    val color = if (powered) PipePowered else PipeIdle

    for (dir in connectors) {
        // Las tuberías llegan hasta el borde para "tocar" las de la celda vecina.
        val end = when (dir) {
            Direction.NORTH -> Offset(center.x, 0f)
            Direction.EAST -> Offset(w, center.y)
            Direction.SOUTH -> Offset(center.x, h)
            Direction.WEST -> Offset(0f, center.y)
        }
        if (powered) {
            drawLine(color.copy(alpha = 0.22f * pulse), center, end, strokeWidth = pipeWidth * 1.9f, cap = StrokeCap.Round)
        }
        drawLine(color, center, end, strokeWidth = pipeWidth, cap = StrokeCap.Round)
    }

    if (powered) {
        drawCircle(color.copy(alpha = 0.22f * pulse), radius = pipeWidth * 1.1f, center = center)
    }
    drawCircle(color, radius = pipeWidth * 0.62f, center = center)
}

/**
 * Dibuja el nodo de fuente/destino: un disco de [color] con un anillo interior oscuro
 * (aspecto de "borne"). Cuando está [lit] añade un halo pulsante; apagado (destino sin
 * energía) se pinta atenuado para invitar a completarlo.
 */
private fun DrawScope.drawNode(color: Color, lit: Boolean, pulse: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.width * 0.22f
    val drawColor = if (lit) color else LogicColors.OnDarkMuted

    if (lit) {
        drawCircle(color.copy(alpha = 0.35f * pulse), radius = radius * 2.1f, center = center)
    }
    drawCircle(drawColor, radius = radius, center = center)
    drawCircle(LogicColors.BackgroundDark, radius = radius * 0.5f, center = center)
    drawCircle(drawColor, radius = radius * 0.28f, center = center)
}

/**
 * Botón de "Reiniciar" con icono neón. Envuelve el icono en un contenedor de tamaño
 * fijo para que activar/desactivar el halo no cambie su footprint (mismo patrón que
 * en "Ordena las Pociones").
 */
@Composable
private fun RestartButton(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) LogicColors.Amber else LogicColors.OnDarkMuted
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .bounceClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            NeonIcon(icon = KortexIcons.Refresh, tint = tint, glow = enabled, size = 28.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text("Reiniciar", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
