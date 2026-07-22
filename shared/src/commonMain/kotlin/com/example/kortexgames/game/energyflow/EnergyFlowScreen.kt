package com.example.kortexgames.game.energyflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.energyflow_intro
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
                bestTimes = state.levelTimes,
            ),
            onStart = { vm.onIntent(EnergyFlowIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            heroImage = Res.drawable.energyflow_intro,
            background = {
                CitySkylineBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.SpatialVision)
            },
        )
        return
    }

    val game = state.game

    // Generación del tablero: sube al reiniciar/reempezar para recomponer desde cero.
    var boardGen by remember { mutableStateOf(0) }

    // Rastrea qué celdas acaban de energizarse para lanzarles una ráfaga de chispas
    // (mismo lenguaje que el crucigrama): compara el set de energizadas de este giro
    // contra el del giro anterior y guarda, por celda, el giro en el que "prendió".
    // Se reinicia con `boardGen` para no arrastrar chispas de una partida anterior.
    var previousPowered by remember(boardGen) { mutableStateOf<Set<Int>>(emptySet()) }
    val sparkTicks = remember(boardGen) { mutableStateMapOf<Int, Long>() }
    LaunchedEffect(boardGen, game.powered) {
        val newlyPowered = game.powered - previousPowered
        for (i in newlyPowered) sparkTicks[i] = game.rotationSeq
        previousPowered = game.powered
    }

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
                                            sparkTick = sparkTicks[index] ?: 0L,
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
                audio = graph.audio,
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

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(EnergyFlowIntent.Pause) },
            onResume = { vm.onIntent(EnergyFlowIntent.Resume) },
            onExit = onExit,
            gameTitle = "Flujo de Energía",
            helpText = "Gira las piezas para llevar la energía de la batería a la bombilla.",
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/**
 * Una pieza del tablero. Dibuja sus tuberías en la orientación **resuelta** y delega
 * el giro a `graphicsLayer { rotationZ }`, animado con resorte para que el giro se
 * sienta táctil (§9.4). Las tuberías se pintan con el color de energía si la pieza
 * está [powered], o apagadas en caso contrario; la fuente/destino añaden su nodo.
 *
 * @param sparkTick giro (`rotationSeq`) en el que esta celda **acaba de energizarse**,
 *        o `0` si no aplica. Dispara una ráfaga de chispas (mismo lenguaje visual que
 *        el crucigrama, vía [drawTileConnectSparks]) para reforzar el "engancha" del
 *        circuito al cerrarse un tramo nuevo.
 */
@Composable
private fun EnergyTileView(
    tile: Tile,
    powered: Boolean,
    pulse: Float,
    sparkTick: Long,
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

    val spark = remember { Animatable(0f) }
    LaunchedEffect(sparkTick) {
        if (sparkTick <= 0L) return@LaunchedEffect
        spark.snapTo(0f)
        spark.animateTo(1f, tween(560, easing = LinearEasing))
    }

    val tileColor = when (tile.kind) {
        TileKind.SOURCE -> SourceColor
        TileKind.TARGET -> TargetLit
        TileKind.PIPE -> PipePowered
    }

    Box(modifier = modifier.bounceClick(onClick = onRotate), contentAlignment = Alignment.Center) {
        // Capa 1 — fondo de la celda ESTÁTICO: no rota, así sus esquinas cuadradas
        // nunca barren sobre las celdas vecinas al girar la pieza. Reutiliza el tubo de
        // neón de [drawNeonTile] (mismo lenguaje visual que Memoria y Crucigrama, §9.2)
        // en vez de un panel plano, y encima las chispas de conexión.
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Base opaca de la celda: sin esto el "tubo" de [drawNeonTile] es hueco
            // (solo contorno + halo) y el skyline de fondo se colaba por dentro de
            // cada pieza (pedido del usuario: la cuadrícula debe tapar la ciudad).
            drawRoundRect(color = LogicColors.SurfaceDark, cornerRadius = CornerRadius(10.dp.toPx()))
            val activeAmt = if (powered) (0.55f + 0.45f * pulse) else 0f
            drawNeonTile(tileColor, activeAmt, cornerRadius = 10.dp, sparks = false, baseMargin = 4.dp)
            val sp = spark.value
            if (sp > 0f && sp < 1f) drawTileConnectSparks(tileColor, sp)
        }
        // Capa 2 — tuberías + nodo, que SÍ rotan. `clip = true` en el graphicsLayer
        // recorta el dibujo a los límites propios de la celda en TODO ángulo de giro:
        // sin esto, el grosor del trazo (con extremos redondeados) sobresalía de la
        // celda hacia las vecinas justo en las orientaciones cardinales (pedido del
        // usuario: "los conectores no deberían sobresalir de los contenedores").
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle; clip = true },
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

/**
 * Ráfaga de chispas radiales que salen del centro de la celda al energizarse, con
 * alfa decreciente conforme avanza [amt] (0→1). Mismo patrón que las chispas de
 * celda del crucigrama, adaptado al color de energía de la pieza.
 */
private fun DrawScope.drawTileConnectSparks(color: Color, amt: Float) {
    val count = 7
    val dist = size.minDimension * (0.3f + 0.8f * amt)
    val fade = 1f - amt
    val dot = 2.6.dp.toPx() * (1f - amt * 0.4f)
    for (i in 0 until count) {
        val ang = i * (2f * PI.toFloat() / count)
        drawCircle(
            color = color.copy(alpha = fade),
            radius = dot,
            center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
        )
    }
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
