package com.kortexgames.app.game.energyflow

import org.jetbrains.compose.resources.stringResource
import kortexgames.shared.generated.resources.energyflow_hud_rotations_one
import kortexgames.shared.generated.resources.energyflow_hud_rotations
import kortexgames.shared.generated.resources.Res
import com.kortexgames.app.ui.components.rememberBoardClock
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.drawNeonWire
import com.kortexgames.app.ui.components.drawNeonNode
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.BoardClock
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
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
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.CitySkylineBackground
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonTile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Color de las tuberías **energizadas** (energía cian que fluye desde la batería). */
private val PipePowered = LogicColors.NeonCyan

/**
 * Filamento de una tubería apagada: claro y bien visible. La tubería sin energía es lo que el
 * jugador estudia para decidir qué girar, así que tiene que leerse sin esfuerzo; la energizada
 * se distingue por su color y su halo, no porque la apagada esté a oscuras.
 */
private val PipeIdle = lerp(LogicColors.OnDark, CategoryPalette.SpatialVision, 0.30f)

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
        EnergyFlowViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Fase de intro: antesala del juego con icono, descripción y carril de niveles.
    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        // Comparativa mundial del nivel resaltado, ANTES de jugarlo: se relanza en
        // cada cambio de nivel del carril (y en la entrada a la antesala, con el
        // nivel por defecto). Ver EnergyFlowViewModel.refreshRankingPreview.
        LaunchedEffect(selectedLevel) { vm.onIntent(EnergyFlowIntent.PreviewLevel(selectedLevel)) }
        GameIntroScreen(
            help = GameHelpContent.energyFlow,
            title = "Flujo de Energía",
            description = "Gira las piezas para llevar la energía de la batería a la bombilla.",
            accent = CategoryPalette.SpatialVision,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
                bestTimes = state.levelTimes,
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.ENERGY_FLOW)
                vm.onIntent(EnergyFlowIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
            motif = GameMotif.ENERGY_PIPES,
            background = {
                CitySkylineBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.SpatialVision)
            },
            // Ranking mundial del nivel elegido, justo encima del CTA (mismo panel que
            // el diálogo de fin de nivel; mismo mecanismo que Water Sort): pedido
            // explícito para que la antesala también responda "¿cómo me va ahí?".
            configContent = {
                val preview = state.rankingPreview
                when {
                    state.rankingPreviewLoading -> WorldRankingLoading()
                    preview != null -> WorldRankingPreviewPanel(ranking = preview)
                    else -> RankingPreviewUnavailable(difficultyLabel = "Nivel $selectedLevel")
                }
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
    // Reloj del tablero (kit compartido): entrada en cascada de las piezas y giro del aro de la
    // batería. `boardBorn` se renueva con cada tablero nuevo (nivel o reinicio).
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)
    val boardBorn = remember(boardGen, game.round) { clock.peek() }

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
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // HUD común de los juegos de tablero (el mismo de Línea Neón y Conectores). La
            // barra muestra cuánta placa tiene energía; el reinicio vive aquí y ya no abajo.
            val tileCount = game.grid.tiles.size
            NeonBoardHud(
                level = game.round,
                progress = if (tileCount > 0) game.powered.size.toFloat() / tileCount else 0f,
                progressLabel = if (game.rotations == 1) {
                    stringResource(Res.string.energyflow_hud_rotations_one)
                } else {
                    stringResource(Res.string.energyflow_hud_rotations, game.rotations.toString())
                },
                accent = CategoryPalette.SpatialVision,
                progressColor = PipePowered,
                onRestart = {
                    if (!game.solved) {
                        boardGen++
                        vm.onIntent(EnergyFlowIntent.Restart)
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            // Tablero cuadrado centrado, ocupa el espacio disponible.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
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
                                            row = row,
                                            col = col,
                                            clock = clock,
                                            boardBorn = boardBorn,
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
                accent = CategoryPalette.SpatialVision,
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
            help = GameHelpContent.energyFlow,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/**
 * Una pieza del tablero. Dibuja sus tuberías en la orientación **resuelta** y delega
 * el giro a `graphicsLayer { rotationZ }`, animado con resorte para que el giro se
 * sienta táctil (§9.4). La fuente/destino añaden su nodo.
 *
 * ## La pieza apagada es sobria; el neón es la energía
 * Antes TODAS las piezas llevaban su tubo de neón cian encendido, energizadas o no: 16 marcos
 * brillantes en un 4×4 (64 en un 8×8), con las tuberías —lo que hay que leer— como rectángulos
 * grises debajo. Ahora la pieza sin energía es una baldosa oscura de borde tenue, y el tubo de
 * neón ([drawNeonTile], §9.7) se reserva para la que SÍ tiene energía: el camino encendido se
 * ve de un vistazo porque es lo único que brilla (§9.1: el neón vale porque es escaso).
 *
 * @param sparkTick giro (`rotationSeq`) en el que esta celda **acaba de energizarse**,
 *        o `0` si no aplica. Dispara un estallido de chispas ([drawSparkBurst], del kit de
 *        tablero) para reforzar el "engancha" del circuito al cerrarse un tramo nuevo.
 * @param row fila de la pieza y [col] su columna: ordenan la entrada en cascada.
 * @param clock reloj del tablero; [boardBorn] es el instante en que se montó este tablero.
 */
@Composable
private fun EnergyTileView(
    tile: Tile,
    powered: Boolean,
    pulse: Float,
    sparkTick: Long,
    row: Int,
    col: Int,
    clock: BoardClock,
    boardBorn: Float,
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
    // Encendido/apagado suave de la pieza al ganar o perder energía.
    val energy by animateFloatAsState(
        targetValue = if (powered) 1f else 0f,
        animationSpec = tween(durationMillis = if (powered) 160 else 260),
        label = "tileEnergy",
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

    Box(
        modifier = modifier
            // Entrada en cascada por diagonales al montar el tablero (kit compartido).
            .graphicsLayer {
                val appear = boardCascade(row, col, clock.seconds - boardBorn)
                scaleX = appear
                scaleY = appear
                alpha = appear.coerceIn(0f, 1f)
            }
            .bounceClick(onClick = onRotate),
        contentAlignment = Alignment.Center,
    ) {
        // Capa 1 — fondo de la celda ESTÁTICO: no rota, así sus esquinas cuadradas
        // nunca barren sobre las celdas vecinas al girar la pieza.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val margin = 3.dp.toPx()
            val corner = CornerRadius(12.dp.toPx())
            val topLeft = Offset(margin, margin)
            val tileSize = Size(size.width - margin * 2f, size.height - margin * 2f)
            // Base opaca: la cuadrícula debe tapar la ciudad del fondo (pedido del usuario).
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(lerp(LogicColors.SurfaceDark, LogicColors.SurfaceVariantDark, 0.55f), LogicColors.SurfaceDark),
                ),
                topLeft = topLeft,
                size = tileSize,
                cornerRadius = corner,
            )
            // La batería y la bombilla llevan siempre un borde de su color, aunque tenue:
            // son los dos extremos del circuito y hay que localizarlos sin buscar.
            val restEdge = if (tile.kind == TileKind.PIPE) {
                lerp(LogicColors.SurfaceVariantDark, CategoryPalette.SpatialVision, 0.45f)
            } else {
                tileColor.copy(alpha = 0.55f)
            }
            drawRoundRect(restEdge, topLeft, tileSize, corner, style = Stroke(1.5.dp.toPx()))
            // Con energía: el tubo de neón compartido, respirando. En las tuberías va fino y
            // a media intensidad —el protagonista es el cable encendido, y un marco a pleno
            // brillo del mismo cian lo emborronaba—; batería y bombilla sí lo llevan entero.
            if (energy > 0f) {
                val isPipe = tile.kind == TileKind.PIPE
                drawNeonTile(
                    baseColor = tileColor,
                    activeAmt = (if (isPipe) 0.22f + 0.16f * pulse else 0.55f + 0.45f * pulse) * energy,
                    cornerRadius = 12.dp,
                    sparks = false,
                    baseMargin = 3.dp,
                    strokeScale = if (isPipe) 0.6f else 1f,
                    alpha = energy,
                )
            }
            drawSparkBurst(center, tileColor, reach = size.minDimension * 0.75f, progress = spark.value, seed = row * 31 + col)
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
            drawPipes(tile.connectors, energy, pulse)
            when (tile.kind) {
                TileKind.SOURCE -> drawNeonNode(
                    center = center,
                    radius = size.width * 0.17f,
                    color = SourceColor,
                    pulse = pulse,
                    time = clock.seconds,
                    active = true,
                )
                TileKind.TARGET -> drawTarget(lit = energy, pulse = pulse)
                TileKind.PIPE -> Unit
            }
        }
    }
}

/**
 * Dibuja las tuberías de la pieza: un tramo del centro a cada lado con [connectors].
 *
 * - **Apagada:** una ranura oscura con un filamento claro dentro ([PipeIdle]) — un tubo de neón
 *   sin corriente. Es nítida a propósito: es lo que el jugador lee para decidir qué girar.
 * - **Con energía:** el cable de neón del kit de tablero ([drawNeonWire]: halo ancho → intermedio
 *   → nítido → núcleo blanco), el mismo que traza Línea Neón.
 *
 * Los tramos de la pieza se unen en UN solo `Path` que pasa por el centro, de modo que el halo
 * no se duplica (y no se ve más brillante) en el cruce.
 *
 * @param energy 0 = apagada, 1 = energizada; los valores intermedios funden una en otra.
 */
private fun DrawScope.drawPipes(connectors: Set<Direction>, energy: Float, pulse: Float) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val pipeWidth = w * 0.15f

    val pipes = Path()
    for (dir in connectors) {
        // Las tuberías llegan hasta el borde para "tocar" las de la celda vecina.
        val end = when (dir) {
            Direction.NORTH -> Offset(center.x, 0f)
            Direction.EAST -> Offset(w, center.y)
            Direction.SOUTH -> Offset(center.x, h)
            Direction.WEST -> Offset(0f, center.y)
        }
        pipes.moveTo(end.x, end.y)
        pipes.lineTo(center.x, center.y)
    }

    if (energy < 1f) {
        val idle = 1f - energy
        drawPath(
            pipes,
            LogicColors.BackgroundDark.copy(alpha = 0.90f * idle),
            style = Stroke(pipeWidth * 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            pipes,
            PipeIdle.copy(alpha = idle),
            style = Stroke(pipeWidth * 0.62f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(PipeIdle.copy(alpha = idle), pipeWidth * 0.58f, center)
    }
    if (energy > 0f) {
        drawNeonWire(
            path = pipes,
            color = PipePowered.copy(alpha = energy),
            strokeWidth = pipeWidth,
            glow = (0.80f + 0.35f * pulse) * energy,
            core = 0.55f * energy,
        )
        drawCircle(Color.White.copy(alpha = 0.85f * energy), pipeWidth * 0.34f, center)
    }
}

/**
 * La **bombilla** (destino). Apagada es un borne gris con el centro hueco, que invita a
 * completarlo; al recibir energía se enciende en ámbar con halo y rayos — el premio del nivel.
 *
 * @param lit 0 = apagada, 1 = encendida.
 */
private fun DrawScope.drawTarget(lit: Float, pulse: Float) {
    val radius = size.width * 0.19f
    if (lit > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(TargetLit.copy(alpha = (0.55f + 0.25f * pulse) * lit), Color.Transparent),
                center = center,
                radius = radius * 3.0f,
            ),
            radius = radius * 3.0f,
            center = center,
        )
        // Rayos cortos alrededor: lo que la hace leerse como bombilla y no como otro nodo.
        val rays = 8
        for (i in 0 until rays) {
            val a = i * (2f * PI.toFloat() / rays)
            val inner = radius * 1.45f
            val outer = radius * (1.80f + 0.20f * pulse)
            drawLine(
                color = TargetLit.copy(alpha = 0.9f * lit),
                start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                strokeWidth = radius * 0.16f,
                cap = StrokeCap.Round,
            )
        }
    }
    val body = lerp(LogicColors.OnDarkMuted, TargetLit, lit)
    drawCircle(body, radius, center)
    drawCircle(lerp(LogicColors.BackgroundDark, Color.White, lit), radius * 0.52f, center)
}
