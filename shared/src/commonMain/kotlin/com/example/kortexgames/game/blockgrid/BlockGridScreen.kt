package com.example.kortexgames.game.blockgrid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.alphaIf
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.drawNeonTile
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// --- Constantes de interacción/dibujo -----------------------------------------

/** Elevación de la pieza arrastrada sobre el dedo, para que el pulgar no la tape. */
private val DRAG_LIFT = 56.dp

/** Lado del mini-bloque con el que se dibujan las piezas en la mano. */
private val HAND_CELL = 13.dp

/** Duración del fade+shrink de las líneas al romperse. */
private const val CLEAR_ANIM_MS = 340

/**
 * Mapa acento semántico → token de [LogicColors]. Vive en la UI (el dominio no
 * conoce `Color`, ver [BlockAccent]); nunca colores sueltos (CLAUDE.md §9.2).
 */
private fun BlockAccent.color(): Color = when (this) {
    BlockAccent.CYAN -> LogicColors.NeonCyan
    BlockAccent.GREEN -> LogicColors.NeonGreen
    BlockAccent.VIOLET -> LogicColors.Violet
    BlockAccent.MAGENTA -> LogicColors.Magenta
    BlockAccent.CORAL -> LogicColors.Coral
    BlockAccent.BLUE -> LogicColors.Blue
    BlockAccent.AMBER -> LogicColors.Amber
}

/**
 * Pantalla de "Tetris Neón".
 *
 * Estructura estándar de juego ENDLESS: antesala ([GameIntroScreen]) mientras
 * está en IDLE → tablero + mano a pantalla completa → [GameOverOverlay].
 *
 * **Geometría del drag & drop**: los gestos nacen en cada pieza de la mano
 * ([detectDragGestures] local a su slot) pero el fantasma y el drop se deciden
 * en celdas del tablero. La traducción usa un único sistema de referencia — las
 * coordenadas *de raíz* de Compose ([boundsInRoot]) — capturando los rects del
 * tablero y de cada slot con [onGloballyPositioned]; así no importa cómo se
 * reorganice el layout entre medias.
 *
 * La **posición del dedo se queda en la UI** (estado local): cambia a 60+ Hz y
 * pasarla por el ViewModel solo generaría churn; al dominio únicamente viaja la
 * celda de anclaje candidata ([BlockGridIntent.DragMoved]) y solo cuando cambia.
 */
@Composable
fun BlockGridScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: BlockGridViewModel = viewModel {
        BlockGridViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Celebración de combo: par (nonce, líneas). El nonce fuerza a recomponer el
    // burst aunque caigan dos combos iguales seguidos.
    var combo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Único punto donde los Effects se vuelven sonido/vibración/celebración.
    LaunchedEffect(Unit) {
        var comboNonce = 0
        vm.effect.collect { effect ->
            when (effect) {
                is BlockGridEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is BlockGridEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                is BlockGridEffect.ShowComboAnim -> combo = ++comboNonce to effect.lines
            }
        }
    }

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            title = "Tetris Neón",
            description = "Arrastra las piezas al tablero y completa filas o columnas para romperlas. La partida termina cuando ninguna pieza cabe.",
            accent = CategoryPalette.SpatialVision,
            onStart = { vm.onIntent(BlockGridIntent.StartGame) },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    // Rects en coordenadas de raíz: el del contenedor (para posicionar el
    // overlay de arrastre) y el de la rejilla (para traducir dedo → celda).
    var containerRect by remember { mutableStateOf(Rect.Zero) }
    var boardRect by remember { mutableStateOf(Rect.Zero) }

    // Posición del dedo (raíz) durante el arrastre. Solo-UI, ver KDoc de la clase.
    var fingerRoot by remember { mutableStateOf(Offset.Zero) }

    val currentState by rememberUpdatedState(state)

    /**
     * Celda de anclaje candidata para [piece] con el dedo en [finger]: el origen
     * visual de la pieza (centrada en X sobre el dedo, elevada [DRAG_LIFT]) se
     * redondea a la celda más cercana. Devuelve la esquina sup. izq. del
     * bounding box aunque caiga fuera: el motor decide validez.
     */
    fun originCellFor(piece: Polyomino, finger: Offset, liftPx: Float): GridPos? {
        if (boardRect == Rect.Zero) return null
        val cellPx = boardRect.width / BOARD_SIZE
        val bboxTopLeft = finger - Offset(
            piece.shape.width * cellPx / 2f,
            piece.shape.height * cellPx + liftPx,
        )
        val rel = bboxTopLeft - boardRect.topLeft
        return GridPos(
            row = (rel.y / cellPx).roundToInt(),
            col = (rel.x / cellPx).roundToInt(),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .onGloballyPositioned { containerRect = it.boundsInRoot() },
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            BlockGridHud(
                score = state.score,
                lines = state.linesCleared,
                onRestart = { vm.onIntent(BlockGridIntent.StartGame) },
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                BoardCanvas(
                    board = state.board,
                    preview = state.drag?.preview,
                    previewAccent = state.drag?.let { d -> state.hand.firstOrNull { it.id == d.pieceId } }
                        ?.accent?.color(),
                    onClearFinished = { vm.onIntent(BlockGridIntent.LineClearFinished) },
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .aspectRatio(1f)
                        .background(LogicColors.SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                        .padding(10.dp)
                        .onGloballyPositioned { boardRect = it.boundsInRoot() },
                )
            }

            // Mano: 3 slots de ancho fijo; la pieza en vuelo se oculta de su slot
            // (sigue en el estado: si el drop falla reaparece sin más lógica).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.hand.forEach { piece ->
                    key(piece.id) {
                        HandSlot(
                            piece = piece,
                            hidden = state.drag?.pieceId == piece.id,
                            modifier = Modifier.weight(1f),
                            onDragStart = { finger ->
                                fingerRoot = finger
                                vm.onIntent(BlockGridIntent.DragStarted(piece.id))
                            },
                            onDragMove = { finger, liftPx ->
                                fingerRoot = finger
                                // Solo molesta al ViewModel si la celda candidata
                                // cambió (el dedo se mueve a 60+ Hz, la celda no).
                                originCellFor(piece, finger, liftPx)?.let { cell ->
                                    val prev = currentState.drag?.preview
                                    if (prev == null || currentState.drag?.pieceId != piece.id ||
                                        cellChanged(piece, prev, cell)
                                    ) {
                                        vm.onIntent(BlockGridIntent.DragMoved(piece.id, cell.row, cell.col))
                                    }
                                }
                            },
                            onDragEnd = { finger, liftPx ->
                                val cell = originCellFor(piece, finger, liftPx)
                                if (cell != null) {
                                    vm.onIntent(BlockGridIntent.DropPiece(piece.id, cell.row, cell.col))
                                } else {
                                    vm.onIntent(BlockGridIntent.DragCancelled)
                                }
                            },
                            onDragCancel = { vm.onIntent(BlockGridIntent.DragCancelled) },
                        )
                    }
                }
            }
        }

        // Pieza en vuelo: se dibuja como overlay del contenedor, a tamaño de
        // celda REAL del tablero (anticipa exactamente cómo quedará) y elevada
        // sobre el dedo. Entra con resorte (crece al levantarla, §9.4).
        state.drag?.let { drag ->
            val piece = state.hand.firstOrNull { it.id == drag.pieceId }
            if (piece != null && boardRect != Rect.Zero) {
                DraggedPieceOverlay(
                    piece = piece,
                    cellPx = boardRect.width / BOARD_SIZE,
                    position = fingerRoot - containerRect.topLeft,
                )
            }
        }

        // Burst "¡COMBO!": one-shot dirigido por el efecto, no por el estado.
        combo?.let { (nonce, lines) ->
            key(nonce) {
                ComboBurst(lines = lines, onDone = { combo = null })
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Fin de la partida!",
                onPlayAgain = { vm.onIntent(BlockGridIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(BlockGridIntent.Pause) },
            onResume = { vm.onIntent(BlockGridIntent.Resume) },
            onExit = onExit,
            gameTitle = "Tetris Neón",
            helpText = "Arrastra las piezas al tablero y completa filas o columnas para romperlas. La partida termina cuando ninguna pieza cabe.",
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/** ¿La celda candidata difiere de la del fantasma vigente? (dedupe de DragMoved). */
private fun cellChanged(piece: Polyomino, preview: PlacementPreview, cell: GridPos): Boolean {
    // El fantasma no guarda su origen; se reconstruye: el origen es el mínimo
    // (fila, columna) de las celdas previsualizadas menos el offset mínimo
    // visible de la forma — comparar contra las celdas absolutas es más simple
    // y elimina ambigüedades con piezas parcialmente fuera.
    return piece.shape.absoluteCells(cell).filter { it.isInsideBoard }.toSet() != preview.cells
}

// --- HUD ----------------------------------------------------------------------

/** HUD superior: título, puntaje, líneas y reinicio (mismo lenguaje que Tornillos). */
@Composable
private fun BlockGridHud(
    score: Int,
    lines: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Tetris Neón",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPill(label = "Puntos", value = score.toString())
            HudPill(label = "Líneas", value = lines.toString())
            Box(
                modifier = Modifier
                    .bounceClick(onClick = onRestart)
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = KortexIcons.Refresh,
                    contentDescription = "Reiniciar partida",
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

// --- Tablero -------------------------------------------------------------------

/**
 * La rejilla 8×8: un único `Canvas` que pinta celdas vacías, bloques asentados
 * (con halo neón), el fantasma del arrastre, el glow de líneas a punto de
 * romperse y la animación de limpieza con sus chispas.
 *
 * La limpieza es **fade + shrink dirigido por estado**: cuando el tablero trae
 * celdas [BoardCell.Clearing], un [Animatable] va de 0→1 y al llegar notifica
 * [onClearFinished] para que el dominio las vacíe (dos tiempos, ver el motor).
 * Se rekeya con el set de celdas: si un combo nuevo cae en plena animación, el
 * ciclo arranca de cero para el conjunto ampliado — un único reloj, sin estados
 * a medias por celda.
 *
 * @param previewAccent acento de la pieza que se arrastra, usado para teñir el
 *        glow de [PlacementPreview.clearingLines]; null en reposo (no hay nada
 *        que iluminar).
 */
@Composable
private fun BoardCanvas(
    board: BoardGrid,
    preview: PlacementPreview?,
    previewAccent: Color?,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearingCells = remember(board) {
        buildSet {
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
                if (board.cells[r][c] is BoardCell.Clearing) add(GridPos(r, c))
            }
        }
    }
    val clearProgress = remember { Animatable(0f) }
    val onClearFinishedCurrent by rememberUpdatedState(onClearFinished)
    LaunchedEffect(clearingCells) {
        if (clearingCells.isNotEmpty()) {
            clearProgress.snapTo(0f)
            clearProgress.animateTo(1f, tween(CLEAR_ANIM_MS, easing = FastOutSlowInEasing))
            onClearFinishedCurrent()
        }
    }

    // Latido suave del glow de anticipo (§9.4): respira mientras el jugador
    // sostiene una jugada que rompería línea, para que se note sin marear.
    val glowPulse by rememberInfiniteTransition(label = "lineGlowPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "lineGlowPulse",
    )

    Canvas(modifier = modifier) {
        val cellPx = size.width / BOARD_SIZE
        val previewCells = preview?.cells.orEmpty()
        val clearingLines = preview?.clearingLines?.takeIf { preview.isValid } ?: FullLines(emptySet(), emptySet())

        for (r in 0 until BOARD_SIZE) {
            for (c in 0 until BOARD_SIZE) {
                val topLeft = Offset(c * cellPx, r * cellPx)
                when (val cell = board.cells[r][c]) {
                    BoardCell.Empty -> {
                        drawEmptyCell(topLeft, cellPx)
                        if (GridPos(r, c) in previewCells) {
                            drawGhostCell(topLeft, cellPx, valid = preview?.isValid == true)
                        }
                    }

                    is BoardCell.Filled ->
                        drawBlock(topLeft, cellPx, cell.accent.color())

                    is BoardCell.Clearing -> {
                        val p = clearProgress.value
                        // El hueco se ve debajo mientras el bloque se encoge.
                        drawEmptyCell(topLeft, cellPx)
                        drawBlock(
                            topLeft = topLeft,
                            cellPx = cellPx,
                            accent = cell.accent.color(),
                            alpha = 1f - p,
                            scale = 1f - 0.55f * p,
                        )
                        // Chispas al romperse (mismo lenguaje que Crucigrama Neón):
                        // esquirlas que salen del bloque mientras se desvanece.
                        drawClearSparks(topLeft, cellPx, cell.accent.color(), p)
                    }
                }
            }
        }

        // Anticipo: antes de soltar, ilumina la fila/columna entera que se
        // completaría con la pieza en el hueco actual.
        if (clearingLines.isNotEmpty() && previewAccent != null) {
            drawLineGlowPreview(clearingLines, cellPx, previewAccent, glowPulse)
        }
    }
}

// --- Mano y pieza en vuelo -------------------------------------------------------

/**
 * Slot de la mano: dibuja la pieza en miniatura y es la fuente de sus gestos.
 * El detector se registra por `piece.id`: si el slot pasa a alojar otra pieza
 * (reposición de mano), el gesto anterior muere con la pieza que lo originó.
 *
 * Los callbacks entregan la posición del dedo **en coordenadas de raíz**
 * (origen del slot + posición local) y el lift en px, ya resueltos aquí para
 * que la pantalla no repita conversiones.
 */
@Composable
private fun HandSlot(
    piece: Polyomino,
    hidden: Boolean,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset, Float) -> Unit,
    onDragEnd: (Offset, Float) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var slotOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { slotOrigin = it.boundsInRoot().topLeft }
            .pointerInput(piece.id) {
                val liftPx = DRAG_LIFT.toPx()
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = { down ->
                        finger = slotOrigin + down
                        onDragStart(finger)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        finger += dragAmount
                        onDragMove(finger, liftPx)
                    },
                    onDragEnd = { onDragEnd(finger, liftPx) },
                    onDragCancel = onDragCancel,
                )
            }
            .alphaIf(hidden, 0f),
        contentAlignment = Alignment.Center,
    ) {
        PolyominoBlocks(
            piece = piece,
            cell = HAND_CELL,
        )
    }
}

/** Miniatura de una pieza: sus bloques a escala [cell], con acento neón suave. */
@Composable
private fun PolyominoBlocks(
    piece: Polyomino,
    cell: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(cell * piece.shape.width, cell * piece.shape.height)) {
        val cellPx = cell.toPx()
        piece.shape.cells.forEach { offset ->
            drawBlock(
                topLeft = Offset(offset.dCol * cellPx, offset.dRow * cellPx),
                cellPx = cellPx,
                accent = piece.accent.color(),
            )
        }
    }
}

/**
 * La pieza mientras vuela: overlay a tamaño de celda real, centrada en X sobre
 * el dedo y elevada [DRAG_LIFT] (el pulgar no debe taparla). Aparece creciendo
 * desde la escala de la mano con un `spring` con rebote — el "resorte táctil"
 * de levantar una ficha física (§9.4: spring para todo lo que se toca).
 */
@Composable
private fun DraggedPieceOverlay(
    piece: Polyomino,
    cellPx: Float,
    position: Offset,
) {
    val appear = remember { Animatable(0.55f) }
    LaunchedEffect(piece.id) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftPx = with(density) { DRAG_LIFT.toPx() }
    val bboxW = piece.shape.width * cellPx
    val bboxH = piece.shape.height * cellPx
    val topLeft = position - Offset(bboxW / 2f, bboxH + liftPx)

    Canvas(
        modifier = Modifier
            .graphicsLayer {
                translationX = topLeft.x
                translationY = topLeft.y
                scaleX = appear.value
                scaleY = appear.value
            }
            .size(
                with(density) { bboxW.toDp() },
                with(density) { bboxH.toDp() },
            ),
    ) {
        piece.shape.cells.forEach { offset ->
            drawBlock(
                topLeft = Offset(offset.dCol * cellPx, offset.dRow * cellPx),
                cellPx = cellPx,
                accent = piece.accent.color(),
                glowBoost = true,
            )
        }
    }
}

// --- Celebración de combo ---------------------------------------------------------

/**
 * Rótulo one-shot al romper líneas: entra con resorte, respira un instante y se
 * desvanece; [onDone] lo retira. No usa emojis ni bloquea la interacción (§9.4).
 */
@Composable
private fun ComboBurst(lines: Int, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0.4f) }
    val alpha = remember { Animatable(1f) }
    val onDoneCurrent by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(650)
        alpha.animateTo(0f, tween(220))
        onDoneCurrent()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (lines == 1) "¡LÍNEA!" else "¡COMBO x$lines!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = if (lines == 1) LogicColors.NeonGreen else LogicColors.Amber,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        )
    }
}

// --- Primitivas de dibujo -----------------------------------------------------------

/** Celda vacía: placa hundida sutil sobre el panel (SurfaceVariant + borde fino). */
private fun DrawScope.drawEmptyCell(topLeft: Offset, cellPx: Float) {
    val inset = cellPx * 0.06f
    val corner = CornerRadius(cellPx * 0.20f)
    val cellSize = Size(cellPx - inset * 2, cellPx - inset * 2)
    drawRoundRect(
        color = LogicColors.SurfaceVariantDark.copy(alpha = 0.45f),
        topLeft = topLeft + Offset(inset, inset),
        size = cellSize,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = LogicColors.SurfaceVariantDark,
        topLeft = topLeft + Offset(inset, inset),
        size = cellSize,
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * Fantasma de colocación: **gris** si el hueco es válido (anticipa dónde caerá
 * la pieza) y rojizo tenue si no cabe — información, no castigo.
 */
private fun DrawScope.drawGhostCell(topLeft: Offset, cellPx: Float, valid: Boolean) {
    val inset = cellPx * 0.06f
    val corner = CornerRadius(cellPx * 0.20f)
    val tint = if (valid) LogicColors.OnDarkMuted else LogicColors.Error
    drawRoundRect(
        color = tint.copy(alpha = if (valid) 0.38f else 0.20f),
        topLeft = topLeft + Offset(inset, inset),
        size = Size(cellPx - inset * 2, cellPx - inset * 2),
        cornerRadius = corner,
    )
}

/**
 * Bloque neón: **tubo hueco**, mismo lenguaje que las celdas de Crucigrama Neón
 * ([drawNeonTile]) — contorno luminoso con halo y núcleo blanco, interior apenas
 * teñido (no relleno sólido). [scale]/[alpha] sirven a la animación de limpieza
 * (encoge alrededor de su centro mientras se funde); [glowBoost] sube el
 * encendido a pleno para la pieza en vuelo (es el foco de atención en ese
 * momento) frente al brillo algo más contenido de un bloque ya asentado.
 */
private fun DrawScope.drawBlock(
    topLeft: Offset,
    cellPx: Float,
    accent: Color,
    alpha: Float = 1f,
    scale: Float = 1f,
    glowBoost: Boolean = false,
) {
    drawNeonTile(
        baseColor = accent,
        activeAmt = if (glowBoost) 1f else 0.78f,
        cornerRadius = (cellPx * 0.22f).toDp(),
        sparks = false,
        baseMargin = (cellPx * 0.07f).toDp(),
        strokeScale = 0.8f,
        rectTopLeft = topLeft,
        rectSize = Size(cellPx, cellPx),
        alpha = alpha,
        scale = scale,
    )
}

/**
 * Anticipo de línea completa: mientras el jugador sostiene una jugada que
 * rompería una fila/columna, cada celda de esa línea se "enciende" con el
 * mismo lenguaje de botón de Memoria de Secuencias al pulsarlo
 * ([drawNeonTile] a pleno encendido) — así el jugador reconoce de un vistazo
 * qué línea va a saltar antes incluso de soltar el dedo. Sin chispas: al
 * encenderse una línea entera (no una única celda), repetir el burst en cada
 * bloque saturaría el tablero. [pulse] (0..1, va y vuelve) le da un latido
 * suave al conjunto para que se note sin marear (§9.4); se calcula el set de
 * celdas una sola vez para no re-encender dos veces la intersección de una
 * fila y una columna que rompen a la vez.
 *
 * El encendido va a **tope** (no atenuado como un bloque asentado normal, ver
 * [drawBlock]) y con más tinte blanco que el acento de la pieza: la línea debe
 * saltar a la vista de inmediato, no competir en brillo con el resto del
 * tablero.
 */
private fun DrawScope.drawLineGlowPreview(lines: FullLines, cellPx: Float, accent: Color, pulse: Float) {
    val hot = lerp(accent, Color.White, 0.45f)
    val activeAmt = 0.5f
    val corner = (cellPx * 0.22f).toDp()
    val margin = (cellPx * 0.045f).toDp()

    val cells = buildSet {
        lines.rows.forEach { r -> for (c in 0 until BOARD_SIZE) add(GridPos(r, c)) }
        lines.cols.forEach { c -> for (r in 0 until BOARD_SIZE) add(GridPos(r, c)) }
    }
    cells.forEach { pos ->
        drawNeonTile(
            baseColor = hot,
            activeAmt = activeAmt,
            cornerRadius = corner,
            sparks = false,
            baseMargin = margin,
            strokeScale = 1.35f + 0.35f * pulse,
            rectTopLeft = Offset(pos.col * cellPx, pos.row * cellPx),
            rectSize = Size(cellPx, cellPx),
        )
    }
}

/** 2π: círculo completo, para repartir las chispas de ruptura en toda dirección. */
private const val TAU = 6.2831855f

/** Nº de chispas que libera cada bloque de una línea rota. */
private const val CLEAR_SPARK_COUNT = 6

/**
 * Chispas de ruptura de un bloque [BoardCell.Clearing] (mismo lenguaje visual
 * que la ráfaga de acierto de Crucigrama Neón): esquirlas que nacen en el
 * centro del bloque y salen disparadas hacia afuera mientras se desvanecen,
 * sincronizadas con [progress] (0=recién roto..1=ya desvanecido del todo).
 *
 * La semilla sale de la posición de la celda (determinista, sin `remember`
 * porque esto corre dentro de un `Canvas`): cada bloque siempre chispea igual,
 * pero bloques distintos no se ven clonados.
 */
private fun DrawScope.drawClearSparks(topLeft: Offset, cellPx: Float, accent: Color, progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val center = topLeft + Offset(cellPx / 2f, cellPx / 2f)
    val rnd = Random((topLeft.x * 131f + topLeft.y * 977f).toInt())
    val hot = lerp(accent, Color.White, 0.35f)
    // Salen rápido y frenan (ease-out), como esquirlas reales.
    val ease = 1f - (1f - progress) * (1f - progress)
    val alpha = 1f - progress
    val maxDist = cellPx * 0.9f
    val unit = cellPx * 0.2f

    repeat(CLEAR_SPARK_COUNT) {
        val angle = rnd.nextFloat() * TAU
        val reach = 0.5f + rnd.nextFloat() * 0.6f
        val length = 0.6f + rnd.nextFloat() * 0.5f
        val dist = ease * maxDist * reach
        val dx = cos(angle)
        val dy = sin(angle)
        val head = center + Offset(dx * dist, dy * dist)
        val tail = center + Offset(dx * (dist - unit * length), dy * (dist - unit * length))
        drawLine(
            color = hot.copy(alpha = alpha),
            start = tail,
            end = head,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius = 1.4.dp.toPx(), center = head)
    }
}
