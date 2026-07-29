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
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.kortexgames.game.GameMotif
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameExitGuard
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.game.GameHelpContent
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.ResumeState
import com.example.kortexgames.ui.components.ReviveAdOverlay
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.alphaIf
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.drawNeonSparks
import com.example.kortexgames.ui.components.drawNeonTile
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --- Constantes de interacción/dibujo -----------------------------------------

/** Elevación de la pieza arrastrada sobre el dedo, para que el pulgar no la tape. */
private val DRAG_LIFT = 56.dp

/** Lado del mini-bloque con el que se dibujan las piezas en la mano. */
private val HAND_CELL = 13.dp

/**
 * Duración total de la limpieza (reloj único). Algo más larga que un fade simple
 * porque incluye el "reparto" de la onda escalonada ([CLEAR_STAGGER_SPAN]): el
 * fade real de cada celda ocupa la fracción restante, ~55 % de este tiempo.
 */
private const val CLEAR_ANIM_MS = 460

/**
 * Fracción del reloj de limpieza dedicada a escalonar el arranque por distancia
 * al epicentro: la celda más lejana empieza a romperse cuando el reloj llega
 * aquí. El resto (1 − esto) es lo que dura el fade+shrink de cada celda. Subirlo
 * hace la onda más marcada; bajarlo la acerca a una limpieza simultánea.
 */
private const val CLEAR_STAGGER_SPAN = 0.5f

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
        BlockGridViewModel(graph.progressRepository, graph.savedGameStateRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la corrida en curso antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // --- Estado local de UI (declarado ANTES del colector de efectos, que lo lee
    // en sus closures; en Kotlin un lambda no puede referenciar variables aún no
    // declaradas). Los rects en coordenadas de raíz: el del contenedor (para
    // posicionar overlays) y el de la rejilla (para traducir dedo → celda).
    var containerRect by remember { mutableStateOf(Rect.Zero) }
    var boardRect by remember { mutableStateOf(Rect.Zero) }

    // Posición del dedo (raíz) durante el arrastre. Solo-UI, ver KDoc de la clase.
    var fingerRoot by remember { mutableStateOf(Offset.Zero) }

    // Centro (raíz) del hueco de cada pieza de la mano, por id: destino del "vuelo
    // de vuelta" cuando un drop se rechaza o se cancela el gesto.
    val slotCenters = remember { mutableStateMapOf<Int, Offset>() }

    // Celebración de combo: nonce (fuerza recomponer aunque el combo se repita),
    // líneas y si toca soltar guirnaldas (combo 5+ o vaciado total del tablero).
    var combo by remember { mutableStateOf<ComboCelebration?>(null) }

    // Pieza que vuela de vuelta a la mano (drop rechazado/cancelado), o null en
    // reposo. Dirigido por el efecto AnimatePieceReturn, no por el estado.
    var returnFlight by remember { mutableStateOf<ReturnFlight?>(null) }

    // Centro (en celdas) de la última pieza colocada: origen de la onda de
    // limpieza escalonada (las celdas más lejanas se rompen algo más tarde).
    var lastPlacedCenter by remember { mutableStateOf<Offset?>(null) }

    // Único punto donde los Effects se vuelven sonido/vibración/celebración.
    LaunchedEffect(Unit) {
        var comboNonce = 0
        var returnNonce = 0
        vm.effect.collect { effect ->
            when (effect) {
                is BlockGridEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is BlockGridEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                is BlockGridEffect.ShowComboAnim ->
                    combo = ComboCelebration(++comboNonce, effect.lines, effect.showGarlands)
                is BlockGridEffect.AnimatePieceReturn -> {
                    // La pieza sigue en la mano (nunca se colocó); se anima su
                    // regreso desde donde se soltó hasta el centro de su hueco.
                    val piece = vm.state.value.hand.firstOrNull { it.id == effect.pieceId }
                    val target = slotCenters[effect.pieceId]
                    if (piece != null && target != null &&
                        boardRect != Rect.Zero && containerRect != Rect.Zero
                    ) {
                        returnFlight = ReturnFlight(
                            nonce = ++returnNonce,
                            piece = piece,
                            startFinger = fingerRoot - containerRect.topLeft,
                            targetCenter = target - containerRect.topLeft,
                        )
                    }
                }
            }
        }
    }

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.blockGrid,
            title = "Tetris Neón",
            motif = GameMotif.TETROMINO,
            description = "Arrastra las piezas al tablero y completa filas o columnas para romperlas. La partida termina cuando ninguna pieza cabe.",
            accent = CategoryPalette.SpatialVision,
            onStart = { vm.onIntent(BlockGridIntent.StartGame) },
            resume = state.savedScore?.let { score ->
                ResumeState(
                    onResume = { vm.onIntent(BlockGridIntent.ResumeSaved) },
                    detail = "$score pts en curso",
                )
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

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
                    clearOrigin = lastPlacedCenter,
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
                            // Oculta el slot mientras la pieza se arrastra O mientras
                            // vuela de vuelta: así el "vuelo de vuelta" aterriza sobre
                            // un hueco vacío y no sobre una copia ya visible.
                            hidden = state.drag?.pieceId == piece.id || returnFlight?.piece?.id == piece.id,
                            modifier = Modifier.weight(1f),
                            onCenter = { center -> slotCenters[piece.id] = center },
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
                                    // Centro (en celdas) del bounding box: origen de la
                                    // onda de limpieza si esta jugada rompe líneas.
                                    lastPlacedCenter = Offset(
                                        cell.col + piece.shape.width / 2f,
                                        cell.row + piece.shape.height / 2f,
                                    )
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

        // Vuelo de vuelta de una pieza rechazada/cancelada: overlay one-shot que
        // la lleva desde donde se soltó hasta su hueco, encogiéndose al tamaño de
        // la mano. Se rekeya con el nonce para reiniciar si vuelve a rechazarse.
        returnFlight?.let { rf ->
            if (boardRect != Rect.Zero) {
                key(rf.nonce) {
                    ReturningPieceOverlay(
                        piece = rf.piece,
                        boardCellPx = boardRect.width / BOARD_SIZE,
                        startFinger = rf.startFinger,
                        targetCenter = rf.targetCenter,
                        onDone = { returnFlight = null },
                    )
                }
            }
        }

        // Burst "¡COMBO!": one-shot dirigido por el efecto, no por el estado.
        // Los fuegos artificiales duran más que el rótulo de texto: son ellos
        // quienes deciden cuándo se limpia `combo` (ver KDoc de FireworksCelebration).
        combo?.let { c ->
            key(c.nonce) {
                FireworksCelebration(lines = c.lines, showGarlands = c.showGarlands, onDone = { combo = null })
                ComboBurst(lines = c.lines, onDone = {})
            }
        }

        // Segunda oportunidad: al llenarse el tablero, ofrece limpiarlo viendo un
        // anuncio antes del game-over. El scrim del overlay bloquea el tablero mientras
        // se decide; al aceptar se limpia y sigue la corrida, al rechazar cae al
        // game-over normal. Icono de "refrescar" (no un corazón): el trato es limpiar
        // el tablero, no una vida.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(BlockGridIntent.Revive) },
                onDecline = { vm.onIntent(BlockGridIntent.DeclineRevive) },
                rewardLabel = "el tablero limpio",
                body = "Mira un anuncio y limpia el tablero para seguir jugando.",
                icon = KortexIcons.Refresh,
                accent = CategoryPalette.SpatialVision,
                audio = graph.audio,
            )
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
            onExit = exitWithSave,
            gameTitle = "Tetris Neón",
            help = GameHelpContent.blockGrid,
            accent = CategoryPalette.SpatialVision,
            exitKeepsProgress = true,
        )

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la corrida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(BlockGridIntent.Resume) },
            onConfirmExit = exitWithSave,
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
 * La limpieza es **fade + shrink dirigido por estado, en onda**: cuando el
 * tablero trae celdas [BoardCell.Clearing], un [Animatable] va de 0→1 y al
 * llegar notifica [onClearFinished] para que el dominio las vacíe (dos tiempos,
 * ver el motor). Sobre ese reloj único, cada celda arranca su propio fade+shrink
 * con una **demora proporcional a su distancia a [clearOrigin]** (la pieza recién
 * colocada): la ruptura se propaga como una onda desde donde el jugador soltó, en
 * vez de que todas las celdas se apaguen a la vez — más "juice" sin temporizadores
 * por celda (todo se recalcula por frame a partir del reloj + la distancia). Se
 * rekeya con el set de celdas: si un combo nuevo cae en plena animación, el ciclo
 * arranca de cero para el conjunto ampliado.
 *
 * @param previewAccent acento de la pieza que se arrastra, usado para teñir el
 *        glow de [PlacementPreview.clearingLines]; null en reposo (no hay nada
 *        que iluminar).
 * @param clearOrigin centro (en celdas) de la última pieza colocada, epicentro de
 *        la onda de limpieza; si es null (no debería con líneas rotas) la onda
 *        emana del centro del tablero.
 */
@Composable
private fun BoardCanvas(
    board: BoardGrid,
    preview: PlacementPreview?,
    previewAccent: Color?,
    clearOrigin: Offset?,
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
    // Demora (fracción del reloj) de cada celda según su distancia al epicentro,
    // normalizada al alcance máximo del conjunto → la celda más lejana arranca en
    // STAGGER_SPAN y aún así termina justo al cerrar el reloj (ver progreso local).
    val clearDelays = remember(clearingCells, clearOrigin) {
        if (clearingCells.isEmpty()) {
            emptyMap()
        } else {
            val origin = clearOrigin ?: Offset(BOARD_SIZE / 2f, BOARD_SIZE / 2f)
            val distances = clearingCells.associateWith { pos ->
                val dc = pos.col + 0.5f - origin.x
                val dr = pos.row + 0.5f - origin.y
                sqrt(dc * dc + dr * dr)
            }
            val maxDist = distances.values.maxOrNull()?.takeIf { it > 0f } ?: 1f
            distances.mapValues { (_, d) -> d / maxDist * CLEAR_STAGGER_SPAN }
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
                        // Progreso LOCAL de esta celda: descuenta su demora y
                        // reescala el resto del reloj a 0..1, de modo que las
                        // celdas cercanas al epicentro rompen antes que las lejanas.
                        val delay = clearDelays[GridPos(r, c)] ?: 0f
                        val p = ((clearProgress.value - delay) / (1f - CLEAR_STAGGER_SPAN))
                            .coerceIn(0f, 1f)
                        // El hueco se ve debajo mientras el bloque se encoge.
                        drawEmptyCell(topLeft, cellPx)
                        drawBlock(
                            topLeft = topLeft,
                            cellPx = cellPx,
                            accent = cell.accent.color(),
                            alpha = 1f - p,
                            scale = 1f - 0.55f * p,
                        )
                        // Chispas al romperse (ráfaga neón compartida de la app):
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
 * que la pantalla no repita conversiones. [onCenter] reporta el centro del slot
 * (raíz) para que el "vuelo de vuelta" de una pieza rechazada sepa a dónde ir.
 */
@Composable
private fun HandSlot(
    piece: Polyomino,
    hidden: Boolean,
    onCenter: (Offset) -> Unit,
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
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                slotOrigin = bounds.topLeft
                onCenter(bounds.center)
            }
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

// --- Vuelo de vuelta de una pieza rechazada -----------------------------------------

/** Duración del "vuelo de vuelta" de una pieza rechazada/cancelada a su hueco. */
private const val RETURN_ANIM_MS = 260

/**
 * Pieza que vuela de vuelta a la mano tras un drop rechazado o un gesto
 * cancelado. Guarda el [nonce] (rekey del overlay para reiniciar si vuelve a
 * rechazarse en pleno vuelo), la [piece] a dibujar y los dos extremos del
 * trayecto en **coordenadas del contenedor**: [startFinger] (posición del dedo
 * al soltar) y [targetCenter] (centro del hueco destino en la mano).
 */
private data class ReturnFlight(
    val nonce: Int,
    val piece: Polyomino,
    val startFinger: Offset,
    val targetCenter: Offset,
)

/**
 * Overlay one-shot del "vuelo de vuelta": lleva la pieza desde donde se soltó
 * hasta su hueco de la mano mientras se encoge del tamaño de celda del tablero
 * al de la mano ([HAND_CELL]). Da continuidad al gesto rechazado —la ficha
 * "rebota" a su sitio— en vez de desaparecer de golpe (§9.4: lo táctil se anima).
 *
 * Un único reloj ([progress], 0→1 con `FastOutSlowIn`) interpola a la vez la
 * posición del centro y la escala; al aterrizar coincide en tamaño y lugar con
 * la miniatura en reposo del slot (que se revela justo cuando [onDone] retira el
 * overlay), por lo que el relevo es imperceptible. El centro de partida se
 * calcula igual que en [DraggedPieceOverlay] (la pieza va centrada en X sobre el
 * dedo y elevada [DRAG_LIFT]) para que el vuelo arranque exactamente donde
 * estaba la pieza arrastrada.
 */
@Composable
private fun ReturningPieceOverlay(
    piece: Polyomino,
    boardCellPx: Float,
    startFinger: Offset,
    targetCenter: Offset,
    onDone: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val onDoneCurrent by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(RETURN_ANIM_MS, easing = FastOutSlowInEasing))
        onDoneCurrent()
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftPx = with(density) { DRAG_LIFT.toPx() }
    val handCellPx = with(density) { HAND_CELL.toPx() }
    val bboxW = piece.shape.width * boardCellPx
    val bboxH = piece.shape.height * boardCellPx

    // Centro de la pieza en el instante de soltar (mismo encuadre que el overlay
    // de arrastre) y factor de escala hasta el tamaño de la miniatura de la mano.
    val startCenter = startFinger - Offset(0f, bboxH / 2f + liftPx)
    val endScale = handCellPx / boardCellPx

    val e = progress.value
    val cx = startCenter.x + (targetCenter.x - startCenter.x) * e
    val cy = startCenter.y + (targetCenter.y - startCenter.y) * e
    val scale = 1f + (endScale - 1f) * e

    Canvas(
        modifier = Modifier
            .graphicsLayer {
                // transformOrigin por defecto = centro del layer: escalar mantiene
                // el centro visual en (cx, cy), que es lo que interpolamos.
                translationX = cx - bboxW / 2f
                translationY = cy - bboxH / 2f
                scaleX = scale
                scaleY = scale
            }
            .size(
                with(density) { bboxW.toDp() },
                with(density) { bboxH.toDp() },
            ),
    ) {
        piece.shape.cells.forEach { offset ->
            drawBlock(
                topLeft = Offset(offset.dCol * boardCellPx, offset.dRow * boardCellPx),
                cellPx = boardCellPx,
                accent = piece.accent.color(),
            )
        }
    }
}

// --- Celebración de combo ---------------------------------------------------------

/**
 * Celebración de combo pendiente de mostrar. [nonce] fuerza a recomponer el
 * burst aunque dos combos seguidos tengan el mismo [lines] (mismo esquema que
 * el resto de celebraciones one-shot de la app). [showGarlands] llega ya
 * decidido por el ViewModel ([BlockGridViewModel.GARLAND_COMBO_THRESHOLD] +
 * vaciado total): la UI solo pinta, no decide cuándo un hito es "grande".
 */
private data class ComboCelebration(val nonce: Int, val lines: Int, val showGarlands: Boolean)

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

// --- Fuegos artificiales y guirnaldas (celebración de combo) -------------------------

/** Duración total de la celebración: fuegos + guirnaldas cayendo. */
private const val FIREWORKS_DURATION_MS = 1500

/** Partículas por explosión de un fuego artificial individual. */
private const val FIREWORK_PARTICLE_COUNT = 14

/** Máximo de fuegos simultáneos: a partir de aquí más líneas no suman claridad, solo ruido. */
private const val MAX_FIREWORKS = 6

/** Guirnaldas cayendo por celebración: fijo, decoran cualquier limpieza (no escala con el combo). */
private const val GARLAND_COUNT = 16

/** Semilla del orden barajado de arranque de las guirnaldas (ver su generación). */
private const val GARLAND_ORDER_SEED = 41

/** Demora máxima (fracción del reloj) del arranque de la guirnalda que cae más tarde. */
private const val GARLAND_MAX_DELAY_FRAC = 0.55f

/** Fracción del reloj que tarda CADA guirnalda en cruzar toda la pantalla, caiga cuando caiga. */
private const val GARLAND_FALL_FRAC = 0.45f

/** Paleta neón compartida por fuegos y guirnaldas: los mismos acentos que ya usan los bloques. */
private val CELEBRATION_COLORS = listOf(
    LogicColors.NeonGreen, LogicColors.NeonCyan, LogicColors.Violet,
    LogicColors.Magenta, LogicColors.Coral, LogicColors.Amber,
)

/** Un fuego artificial: origen (fracción del canvas), color y demora antes de estallar. */
private data class FireworkSpec(val originFrac: Offset, val color: Color, val delayFrac: Float)

/**
 * Una guirnalda cayendo en espiral: columna base (fracción X), color, demora,
 * largo y los parámetros de su corkscrew ([spiralTurns] vueltas completas
 * durante toda la caída, [spiralRadiusFrac] qué tan ancho gira respecto al
 * canvas).
 */
private data class GarlandSpec(
    val xFrac: Float,
    val color: Color,
    val delayFrac: Float,
    val spiralPhase: Float,
    val spiralTurns: Float,
    val spiralRadiusFrac: Float,
    val lengthFrac: Float,
)

/**
 * Celebración de combo: **N fuegos artificiales** (uno por línea rota, tope
 * [MAX_FIREWORKS]) que estallan escalonados en la mitad superior del tablero.
 * Si [showGarlands] es true (combo de 5+ líneas o vaciado total del tablero,
 * ver [BlockGridViewModel]) se suma un puñado fijo de **guirnaldas neón**
 * cayendo desde arriba con balanceo — reservadas a esos hitos grandes para que
 * no pierdan impacto apareciendo en cualquier línea suelta.
 *
 * Un único reloj ([progress], 0→1 lineal) maneja ambos efectos: cada fuego y
 * cada guirnalda tiene su propia `delayFrac` para escalonar el arranque, pero
 * no hay temporizadores independientes por partícula — se recalculan todas cada
 * frame en función de `progress` (mismo patrón que [drawClearSparks]: nada de
 * estado por partícula, todo determinista a partir de una semilla + el tiempo).
 * [onDone] llega al terminar el reloj: dura más que [ComboBurst] a propósito,
 * es quien decide cuándo se retira la celebración entera del árbol.
 */
@Composable
private fun FireworksCelebration(
    lines: Int,
    showGarlands: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val onDoneCurrent by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(FIREWORKS_DURATION_MS, easing = LinearEasing))
        onDoneCurrent()
    }

    val fireworkCount = lines.coerceIn(1, MAX_FIREWORKS)
    val fireworks = remember(lines) {
        List(fireworkCount) { i ->
            val rnd = Random(lines * 97 + i * 131 + 11)
            FireworkSpec(
                originFrac = Offset(0.16f + rnd.nextFloat() * 0.68f, 0.14f + rnd.nextFloat() * 0.38f),
                color = CELEBRATION_COLORS[rnd.nextInt(CELEBRATION_COLORS.size)],
                // Escalonados en el primer 55% del reloj: si hay varios estallan
                // en cadena en vez de todos a la vez (más "fuegos artificiales").
                delayFrac = if (fireworkCount <= 1) 0f else i / (fireworkCount - 1).toFloat() * 0.55f,
            )
        }
    }
    val garlands = if (!showGarlands) {
        emptyList()
    } else {
        remember(lines) {
            // Orden barajado (semilla fija) para asignar la demora: si se usara
            // el índice tal cual, las guirnaldas de un extremo del tablero
            // caerían siempre juntas y las del otro también. Desacoplar el
            // "cuándo cae" de "dónde cae" separa la cortina en el tiempo sin
            // dejar un patrón visible.
            val order = (0 until GARLAND_COUNT).shuffled(Random(GARLAND_ORDER_SEED))
            List(GARLAND_COUNT) { i ->
                val rnd = Random(i * 271 + 17)
                GarlandSpec(
                    xFrac = rnd.nextFloat(),
                    color = CELEBRATION_COLORS[rnd.nextInt(CELEBRATION_COLORS.size)],
                    // Rampa por orden barajado + jitter: reparte los arranques a
                    // lo largo de casi toda la celebración en vez de agruparlos
                    // en la primera fracción del reloj (separación vertical real,
                    // no solo variación de fase de balanceo).
                    delayFrac = order[i] / (GARLAND_COUNT - 1).toFloat() * GARLAND_MAX_DELAY_FRAC +
                        rnd.nextFloat() * 0.06f,
                    spiralPhase = rnd.nextFloat() * TAU,
                    spiralTurns = 1.3f + rnd.nextFloat() * 1.4f,
                    spiralRadiusFrac = 0.028f + rnd.nextFloat() * 0.03f,
                    lengthFrac = 0.05f + rnd.nextFloat() * 0.035f,
                )
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        garlands.forEach { drawGarland(it, progress.value, size) }
        fireworks.forEach { drawFirework(it, progress.value, size) }
    }
}

/**
 * Guirnalda cayendo: un breve trazo neón (halo + núcleo, mismo lenguaje de capas
 * que [drawBlock]) que desciende en **espiral** (corkscrew) en vez de un simple
 * balanceo lateral — gira [GarlandSpec.spiralTurns] vueltas completas alrededor
 * de su columna mientras cae. El grosor del trazo respira con el giro
 * (`cos(spiralAngle)`): fino quie cuando gira "de canto" hacia la cámara,
 * fino cuando gira "de canto", grueso cuando queda "de cara" — como una
 * cinta real rotando sobre su eje.
 *
 * La duración de caída ([GARLAND_FALL_FRAC]) es **fija** para todas, sea cual
 * sea su demora de arranque: así una guirnalda que empieza tarde no se ve
 * "acelerada" para alcanzar a las demás, solo aparece más tarde y más abajo en
 * el tiempo — es lo que separa la cortina verticalmente en vez de amontonarla
 * al principio del reloj.
 */
private fun DrawScope.drawGarland(g: GarlandSpec, globalT: Float, canvasSize: Size) {
    if (globalT < g.delayFrac) return
    val t = ((globalT - g.delayFrac) / GARLAND_FALL_FRAC).coerceIn(0f, 1f)
    val len = canvasSize.height * g.lengthFrac
    val travel = canvasSize.height + len * 2f
    val y = -len + t * travel

    val spiralAngle = t * TAU * g.spiralTurns + g.spiralPhase
    val radius = canvasSize.width * g.spiralRadiusFrac
    val x = g.xFrac * canvasSize.width + cos(spiralAngle) * radius
    // Ligero desplazamiento en X entre extremos del trazo, siguiendo la
    // tangente del giro: da la torsión visual de una cinta enroscándose, no
    // solo su centro moviéndose en espiral.
    val tilt = -sin(spiralAngle) * radius * 0.9f

    val fadeIn = (t / 0.08f).coerceIn(0f, 1f)
    val fadeOut = ((1f - t) / 0.15f).coerceIn(0f, 1f)
    val alpha = minOf(fadeIn, fadeOut)
    if (alpha <= 0f) return

    val twist = 0.35f + 0.65f * kotlin.math.abs(cos(spiralAngle))
    val hot = lerp(g.color, Color.White, 0.3f)
    val top = Offset(x - tilt / 2f, y - len / 2f)
    val bottom = Offset(x + tilt / 2f, y + len / 2f)
    drawLine(
        g.color.copy(alpha = alpha * 0.35f), top, bottom,
        strokeWidth = 7.dp.toPx() * twist, cap = StrokeCap.Round,
    )
    drawLine(
        hot.copy(alpha = alpha), top, bottom,
        strokeWidth = 2.5.dp.toPx() * twist, cap = StrokeCap.Round,
    )
}

/**
 * Un fuego artificial: destello blanco al estallar + partículas que salen
 * radialmente del centro, frenan (ease-out) y caen un poco por gravedad mientras
 * se desvanecen. El ángulo/velocidad de cada partícula sale de un [Random]
 * sembrado con el centro del estallido: mismo fuego siempre se ve igual, pero
 * cada uno (posición distinta) se ve único.
 */
private fun DrawScope.drawFirework(f: FireworkSpec, globalT: Float, canvasSize: Size) {
    if (globalT < f.delayFrac) return
    val t = ((globalT - f.delayFrac) / (1f - f.delayFrac)).coerceIn(0f, 1f)
    val center = Offset(f.originFrac.x * canvasSize.width, f.originFrac.y * canvasSize.height)
    val ease = 1f - (1f - t) * (1f - t)
    val maxRadius = canvasSize.width * 0.16f
    val alpha = 1f - t

    // Destello inicial del estallido: un breve fogonazo blanco en el centro.
    if (t < 0.18f) {
        val flashAlpha = 1f - t / 0.18f
        drawCircle(Color.White.copy(alpha = flashAlpha * 0.8f), radius = maxRadius * (0.22f + 0.2f * t), center = center)
    }

    val rnd = Random((center.x * 13f + center.y * 7f + f.delayFrac * 1000f).toInt())
    val hot = lerp(f.color, Color.White, 0.4f)
    repeat(FIREWORK_PARTICLE_COUNT) { i ->
        val angle = (i / FIREWORK_PARTICLE_COUNT.toFloat()) * TAU + (rnd.nextFloat() - 0.5f) * 0.35f
        val speed = 0.75f + rnd.nextFloat() * 0.5f
        val dist = ease * maxRadius * speed
        val fall = t * t * canvasSize.height * 0.05f
        val pos = center + Offset(cos(angle) * dist, sin(angle) * dist + fall)
        drawCircle(f.color.copy(alpha = alpha * 0.35f), radius = 5.dp.toPx(), center = pos)
        drawCircle(hot.copy(alpha = alpha), radius = 2.dp.toPx(), center = pos)
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

/** 2π: círculo completo, para repartir partículas en toda dirección. */
private const val TAU = 6.2831855f

/**
 * Alcance de las chispas de ruptura, en fracción del lado de la celda. La
 * estela que dibuja [drawNeonSparks] es proporcional a este alcance, así que
 * tocar este valor reescala la ráfaga entera de forma coherente.
 */
private const val CLEAR_SPARK_REACH = 0.9f

/**
 * Chispas de ruptura de un bloque [BoardCell.Clearing]: esquirlas que nacen en
 * el centro del bloque y salen disparadas hacia afuera mientras se desvanecen,
 * sincronizadas con [progress] (0=recién roto..1=ya desvanecido del todo).
 *
 * Delega en [drawNeonSparks], la fuente única del efecto (CLAUDE.md §9.7); aquí
 * solo se traduce la geometría de la celda (esquina + lado) a los parámetros
 * del componente.
 *
 * La semilla sale de la posición de la celda (determinista, sin `remember`
 * porque esto corre dentro de un `Canvas`): cada bloque siempre chispea igual,
 * pero bloques distintos no se ven clonados.
 */
private fun DrawScope.drawClearSparks(topLeft: Offset, cellPx: Float, accent: Color, progress: Float) {
    drawNeonSparks(
        center = topLeft + Offset(cellPx / 2f, cellPx / 2f),
        reach = cellPx * CLEAR_SPARK_REACH,
        accent = accent,
        progress = progress,
        seed = (topLeft.x * 131f + topLeft.y * 977f).toInt(),
    )
}
