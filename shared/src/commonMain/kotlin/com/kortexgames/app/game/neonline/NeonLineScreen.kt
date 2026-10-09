package com.kortexgames.app.game.neonline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timeline
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.kortexgames.app.ui.components.BoardClock
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawBoardSocket
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonNode
import com.kortexgames.app.ui.components.drawNeonWire
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.rememberBoardClock
import kotlin.math.abs
import kotlin.math.sin

// --- Constantes de composición del tablero ------------------------------------

/** Grosor de la línea como fracción del tamaño de celda. */
private const val LINE_WIDTH_FRACTION = 0.24f

/** Lado del bloque-obstáculo como fracción de la celda (deja aire alrededor). */
private const val OBSTACLE_SIZE_FRACTION = 0.74f

/** Lado de la baldosa de una celda libre, como fracción de la celda (el resto es junta). */
private const val SOCKET_SIZE_FRACTION = 0.88f

/** Duración del "pop" con que se enciende una celda al entrar la línea. */
private const val CELL_POP_SEC = 0.20f

/** Radio base de la punta luminosa, como fracción de la celda. */
private const val HEAD_RADIUS_FRACTION = 0.19f

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
                    completionSweep.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
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

    // Reloj de animación del tablero (corriente del cable, giro de la punta, entradas). Se
    // congela en pausa.
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)
    // Instante en que se montó ESTE tablero: dispara la entrada en cascada de las celdas. Cambia
    // con el nivel y con sus bloques (nivel nuevo), no al reiniciar el mismo.
    val boardBorn = remember(state.currentLevel, game.obstacles) { clock.peek() }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            NeonBoardHud(
                level = state.currentLevel,
                progress = if (game.playableCount > 0) game.path.size.toFloat() / game.playableCount else 0f,
                progressLabel = "${game.path.size}/${game.playableCount}",
                accent = CategoryPalette.ProblemSolving,
                // La barra va en el color del cable: llenar la barra ES trazar la línea.
                progressColor = LineAccent,
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
                    clock = clock,
                    boardBorn = boardBorn,
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
                accent = CategoryPalette.ProblemSolving,
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

/**
 * El tablero: un único [Canvas] con placa, celdas, bloques, línea y punta, más una capa de
 * gestos encima. `BoxWithConstraints` fija la equivalencia celda↔px que comparten el
 * dibujo y la traducción de gestos, que es lo que hace que ambos no puedan
 * desalinearse.
 *
 * El dibujo se apoya en el **kit de tablero neón** (`ui/components/NeonBoardKit.kt`): placa,
 * zócalos de celda, cable, nodo y chispas son piezas compartidas, pensadas para llevarse igual
 * a Conectores y a Flujo de Energía. Aquí solo vive lo propio de este juego: los bloques-chip,
 * el orden de las capas y sus tiempos.
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
 *
 * @param clock reloj de animación de la pantalla.
 * @param boardBorn instante (del [clock]) en que se montó este tablero; arranca la cascada.
 */
@Composable
private fun NeonLineBoard(
    game: NeonLineGameState,
    onIntent: (NeonLineIntent) -> Unit,
    rejectedCell: GridPosition?,
    rejectAmount: Float,
    sweep: Float,
    clock: BoardClock,
    boardBorn: Float,
    modifier: Modifier = Modifier,
) {
    val size = game.gridSize

    // Instante en que se encendió cada celda del trazo, para su "pop". No es estado observable:
    // lo rellena y lo poda el propio dibujo, que ya se repinta en cada frame.
    val litAt = remember { mutableMapOf<GridPosition, Float>() }

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
            val time = clock.seconds
            // Celdas que dejaron de estar en el trazo (retroceso o reinicio): se olvidan.
            if (litAt.size > game.path.size) litAt.keys.retainAll(game.visited)

            // El marco se enciende al resolver: sube con el barrido final y se queda.
            val solvedGlow = if (game.isSolved) (if (sweep > 0f) sweep else 1f) else 0f
            drawNeonBoardPlate(accent = CategoryPalette.ProblemSolving, lit = solvedGlow, litColor = LineAccent)
            drawCells(game, cellPx, time, time - boardBorn, litAt)
            game.path.firstOrNull()?.let { drawOrigin(it, cellPx) }
            drawLine(game, cellPx, sweep, time)
            game.head?.let { drawHead(it, cellPx, time, solved = game.isSolved) }
            if (rejectedCell != null && rejectAmount > 0f) {
                drawRejectFlash(rejectedCell, cellPx, rejectAmount)
            }
            // Remate del circuito completo: chispas en la punta cuando el barrido llega a ella.
            game.head?.takeIf { game.isSolved }?.let { head ->
                drawSparkBurst(
                    center = cellCenter(head, cellPx),
                    color = LineAccent,
                    reach = cellPx * 1.5f,
                    progress = (sweep - SWEEP_BURST_START) / (1f - SWEEP_BURST_START),
                    seed = game.path.size,
                )
            }
        }
    }
}

/** Centro en px de una celda del tablero. */
private fun cellCenter(pos: GridPosition, cellPx: Float): Offset =
    Offset((pos.col + 0.5f) * cellPx, (pos.row + 0.5f) * cellPx)

/**
 * Celdas del tablero: zócalos que se encienden al pasar la línea, y bloques inertes.
 *
 * Las **celdas libres** son baldosas hundidas ([drawBoardSocket]). Vacías llevan un punto de
 * contacto —sin él un tablero vacío se lee como una superficie uniforme y no se percibe cuántas
 * faltan—; al entrar la línea se **llenan de luz con un pequeño rebote**, y verlas encenderse una
 * a una es parte de la recompensa. Mientras no hay trazo, los puntos respiran despacio: es la
 * invitación a empezar por cualquier celda.
 *
 * Los **obstáculos** son microchips soldados a la placa: bloque con bisel, patillas en los cuatro
 * lados y una muesca de orientación. Nada de neón ni de halo — son lo único inerte de la
 * pantalla, y esa frialdad es justo lo que los hace legibles al instante como "por aquí no".
 *
 * Al montar el nivel todo entra **en cascada** por diagonales ([boardCascade]).
 *
 * @param sinceBorn segundos desde que se montó el tablero.
 * @param litAt instante en que se encendió cada celda del trazo (lo rellena esta función).
 */
private fun DrawScope.drawCells(
    game: NeonLineGameState,
    cellPx: Float,
    time: Float,
    sinceBorn: Float,
    litAt: MutableMap<GridPosition, Float>,
) {
    val idle = game.path.isEmpty()
    val padBreath = if (idle) 0.65f + 0.35f * sin(time * 2.4f) else 0.75f

    for (row in 0 until game.gridSize) {
        for (col in 0 until game.gridSize) {
            val cell = GridPosition(row, col)
            val center = cellCenter(cell, cellPx)
            val appear = boardCascade(row, col, sinceBorn)
            if (appear <= 0f) continue
            when (game.cellState(cell)) {
                NeonLineCellState.OBSTACLE -> drawChip(center, cellPx * OBSTACLE_SIZE_FRACTION * appear, cellPx)
                NeonLineCellState.EMPTY -> drawBoardSocket(
                    center = center,
                    side = cellPx * SOCKET_SIZE_FRACTION * appear,
                    fill = 0f,
                    color = LineAccent,
                    padAlpha = padBreath,
                )
                NeonLineCellState.VISITED -> {
                    val age = (time - litAt.getOrPut(cell) { time }) / CELL_POP_SEC
                    drawBoardSocket(
                        center = center,
                        side = cellPx * SOCKET_SIZE_FRACTION * appear,
                        fill = if (age >= 1f) 1f else EaseOutBack.transform(age.coerceAtLeast(0f)),
                        color = LineAccent,
                    )
                }
            }
        }
    }
}

/**
 * Un bloque-obstáculo con aspecto de microchip.
 *
 * @param side lado del cuerpo del chip (ya con la escala de entrada aplicada).
 * @param cellPx lado de la celda, para dimensionar patillas y bisel.
 */
private fun DrawScope.drawChip(center: Offset, side: Float, cellPx: Float) {
    if (side <= 0f) return
    val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
    val corner = CornerRadius(cellPx * 0.09f)
    val pinLength = cellPx * 0.07f
    val pinWidth = cellPx * 0.055f
    val pinColor = lerp(LogicColors.SurfaceVariantDark, LogicColors.OnDarkMuted, 0.35f)

    // Patillas: tres por lado, asomando por debajo del cuerpo.
    for (k in -1..1) {
        val along = k * side * 0.27f
        drawRect(pinColor, Offset(topLeft.x - pinLength, center.y + along - pinWidth / 2f), Size(pinLength, pinWidth))
        drawRect(pinColor, Offset(topLeft.x + side, center.y + along - pinWidth / 2f), Size(pinLength, pinWidth))
        drawRect(pinColor, Offset(center.x + along - pinWidth / 2f, topLeft.y - pinLength), Size(pinWidth, pinLength))
        drawRect(pinColor, Offset(center.x + along - pinWidth / 2f, topLeft.y + side), Size(pinWidth, pinLength))
    }
    // Cuerpo con volumen (más claro arriba) y bisel interior.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(lerp(LogicColors.SurfaceVariantDark, LogicColors.OnDarkMuted, 0.16f), LogicColors.SurfaceVariantDark),
            startY = topLeft.y,
            endY = topLeft.y + side,
        ),
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = corner,
    )
    val inset = side * 0.14f
    drawRoundRect(
        color = LogicColors.BackgroundDark.copy(alpha = 0.45f),
        topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
        size = Size(side - inset * 2f, side - inset * 2f),
        cornerRadius = CornerRadius(cellPx * 0.05f),
        style = Stroke(width = 1.2.dp.toPx()),
    )
    drawRoundRect(LogicColors.BackgroundDark.copy(alpha = 0.60f), topLeft, Size(side, side), corner, style = Stroke(1.5.dp.toPx()))
    // Muesca de orientación en una esquina.
    drawCircle(LogicColors.OnDarkMuted.copy(alpha = 0.55f), side * 0.055f, Offset(topLeft.x + side * 0.24f, topLeft.y + side * 0.24f))
}

/**
 * Marca del **origen** del trazo: un aro bajo el arranque de la línea. Sin ella, con el tablero
 * medio lleno no se distingue qué extremo es el principio — y saberlo importa para decidir si
 * conviene deshacer hasta ahí.
 */
private fun DrawScope.drawOrigin(cell: GridPosition, cellPx: Float) {
    val center = cellCenter(cell, cellPx)
    drawCircle(LineAccent.copy(alpha = 0.30f), cellPx * 0.36f, center, style = Stroke(cellPx * 0.07f))
    drawCircle(LineAccent, cellPx * 0.36f, center, style = Stroke(1.5.dp.toPx()))
}

/**
 * La línea de luz: un [Path] por los centros de las celdas visitadas, pintado con el cable de
 * neón del kit ([drawNeonWire]) y con su **corriente** fluyendo hacia la punta.
 * `StrokeCap.Round` + `StrokeJoin.Round` hacen que puntas y codos salgan suaves.
 *
 * Un trazo de una sola celda no dibuja línea: lo representa la propia punta.
 *
 * @param sweep 0..1 del barrido de celebración. Recorre la línea iluminando un tramo
 *   móvil ([SWEEP_HALF_WIDTH] a cada lado): es la lectura de "la corriente por fin
 *   circula", y por eso viaja de la primera celda a la última en vez de encender todo
 *   de golpe. A 0 (o ya terminado) no dibuja nada.
 */
private fun DrawScope.drawLine(game: NeonLineGameState, cellPx: Float, sweep: Float, time: Float) {
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
    // Resuelto, el cable brilla más: el circuito está cerrado y la corriente circula.
    drawNeonWire(
        path = line,
        color = LineAccent,
        strokeWidth = stroke,
        glow = if (game.isSolved) 1.35f else 1f,
        core = if (game.isSolved) 0.55f else 0.30f,
        flowPhase = time,
    )

    // Barrido de celebración: se ilumina celda a celda según su distancia al frente
    // del barrido. Se dibuja por celdas (y no como un degradado sobre el Path) porque
    // un Path arbitrario no tiene una parametrización de longitud barata en Compose,
    // y el índice de celda ya es esa parametrización, gratis.
    if (sweep > 0f && sweep < 1f) {
        val front = sweep / SWEEP_BURST_START
        cells.forEachIndexed { index, cell ->
            val position = index.toFloat() / (cells.size - 1)
            val distance = abs(position - front)
            if (distance < SWEEP_HALF_WIDTH) {
                val amount = 1f - distance / SWEEP_HALF_WIDTH
                val center = cellCenter(cell, cellPx)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.90f * amount), LineAccent.copy(alpha = 0.35f * amount), Color.Transparent),
                        center = center,
                        radius = cellPx * 0.62f,
                    ),
                    radius = cellPx * 0.62f,
                    center = center,
                )
            }
        }
    }
}

/**
 * La punta de la línea: el nodo de luz del kit ([drawNeonNode]) con su aro giratorio, que
 * **late** suavemente. Responde a la pregunta que el jugador se hace sin parar mientras
 * arrastra: "¿dónde estoy?". Al resolver el nivel deja de destacar sobre el resto (ya no hay
 * nada que guiar): se dibuja quieta y sin aro.
 */
private fun DrawScope.drawHead(head: GridPosition, cellPx: Float, time: Float, solved: Boolean) {
    drawNeonNode(
        center = cellCenter(head, cellPx),
        radius = cellPx * HEAD_RADIUS_FRACTION,
        color = LineAccent,
        pulse = if (solved) 0f else 0.5f + 0.5f * sin(time * 5.2f),
        time = time,
        active = !solved,
    )
}

/**
 * Destello rojo sobre una celda a la que la línea no puede entrar: la baldosa se tiñe y un aspa
 * la tacha. Es feedback de *por qué* no avanzó: sin él, un movimiento ilegal se percibe como que
 * el juego "no responde". [amount] va de 1 (recién rechazado) a 0.
 */
private fun DrawScope.drawRejectFlash(cell: GridPosition, cellPx: Float, amount: Float) {
    val center = cellCenter(cell, cellPx)
    val side = cellPx * SOCKET_SIZE_FRACTION
    val corner = CornerRadius(side * 0.22f)
    val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
    drawRoundRect(LogicColors.Error.copy(alpha = 0.45f * amount), topLeft, Size(side, side), corner)
    drawRoundRect(LogicColors.Error.copy(alpha = amount), topLeft, Size(side, side), corner, style = Stroke(2.dp.toPx()))
    val arm = side * 0.20f
    val stroke = 3.dp.toPx()
    val cross = Color.White.copy(alpha = 0.9f * amount)
    drawLine(cross, Offset(center.x - arm, center.y - arm), Offset(center.x + arm, center.y + arm), stroke, StrokeCap.Round)
    drawLine(cross, Offset(center.x + arm, center.y - arm), Offset(center.x - arm, center.y + arm), stroke, StrokeCap.Round)
}

/** Mitad del ancho (en fracción de la línea) del tramo que ilumina el barrido final. */
private const val SWEEP_HALF_WIDTH = 0.16f

/**
 * Fracción de la animación final en la que el barrido ya llegó a la punta: el resto es para el
 * estallido de chispas que lo remata.
 */
private const val SWEEP_BURST_START = 0.62f
