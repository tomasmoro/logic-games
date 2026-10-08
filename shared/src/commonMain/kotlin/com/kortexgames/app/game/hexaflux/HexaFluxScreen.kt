package com.kortexgames.app.game.hexaflux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hexagon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.BoardClock
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.modalCard
import com.kortexgames.app.ui.components.modalScrim
import com.kortexgames.app.ui.components.rememberBoardClock
import com.kortexgames.app.ui.components.softGlow
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.hexaflux_board_description
import kortexgames.shared.generated.resources.hexaflux_combo
import kortexgames.shared.generated.resources.hexaflux_gameover_headline
import kortexgames.shared.generated.resources.hexaflux_hud_clear
import kortexgames.shared.generated.resources.hexaflux_hud_score
import kortexgames.shared.generated.resources.hexaflux_hud_survive
import kortexgames.shared.generated.resources.hexaflux_intro_description
import kortexgames.shared.generated.resources.hexaflux_lost_blocked
import kortexgames.shared.generated.resources.hexaflux_lost_choose_level
import kortexgames.shared.generated.resources.hexaflux_lost_no_moves
import kortexgames.shared.generated.resources.hexaflux_lost_retry
import kortexgames.shared.generated.resources.hexaflux_lost_title
import kortexgames.shared.generated.resources.hexaflux_moves_left
import kortexgames.shared.generated.resources.hexaflux_objective_clear
import kortexgames.shared.generated.resources.hexaflux_objective_score
import kortexgames.shared.generated.resources.hexaflux_objective_survive
import kortexgames.shared.generated.resources.hexaflux_tray_hint
import kortexgames.shared.generated.resources.hexaflux_tray_piece_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// --- Constantes de composición y de animación ---------------------------------

/** Nombre del juego: contenido de catálogo (§10), no texto de UI traducible. */
private const val GAME_TITLE = "Neon Hexa Flux"

/** Acento de la categoría (Reconocimiento de Patrones): cromo del HUD, celdas y antesala. */
private val Accent = CategoryPalette.PatternRecognition

private const val SQRT_3 = 1.7320508f

/** Radio de la celda dibujada respecto al de la malla: el hueco entre celdas hace de rejilla. */
private const val SOCKET_FRACTION = 0.93f

/** Radio de una ficha respecto al de la malla: deja ver el borde de su celda alrededor. */
private const val TILE_FRACTION = 0.84f

/** Duración del estallido de chispas de una fusión o de un gimmick roto (revelado corto, §9.4). */
private const val BURST_MS = 450

/** Jugadas restantes a partir de las cuales el contador avisa en rojo. */
private const val LOW_MOVES = 3

/** Coseno y seno de los seis vértices de un hexágono *pointy-top* (a `30° + 60°·i`). */
private val HEX_COS = FloatArray(6) { cos(PI / 3.0 * it + PI / 6.0).toFloat() }
private val HEX_SIN = FloatArray(6) { sin(PI / 3.0 * it + PI / 6.0).toFloat() }

/** Colores de las parejas de portales; una pareja comparte color para que se lean como una. */
private val PortalColors = listOf(LogicColors.Violet, LogicColors.Amber, LogicColors.Magenta, LogicColors.NeonCyan)

/**
 * Color neón de cada tier. La escalera va de frío a cálido y termina en violeta (cian → verde →
 * magenta → ámbar → violeta) para que "más energía" se lea sin mirar los puntos de la ficha.
 */
private val FluxTier.color: Color
    get() = when (this) {
        FluxTier.SPARK -> LogicColors.NeonCyan
        FluxTier.PULSE -> LogicColors.NeonGreen
        FluxTier.SURGE -> LogicColors.Magenta
        FluxTier.NOVA -> LogicColors.Amber
        FluxTier.CORE -> LogicColors.Violet
    }

/**
 * Encaje del tablero en el lienzo: el paso de coordenadas axiales a píxeles y su inversa.
 *
 * Calcula la caja que envuelve los centros de todas las celdas en unidades de radio
 * ([HexCoord.unitX]/[HexCoord.unitY]), le suma el medio hexágono que sobresale por cada lado
 * (`√3/2` en horizontal, `1` en vertical) y elige el mayor radio que la hace caber. Así una
 * máscara ancha como la mariposa y una compacta se ajustan solas, sin tabla de tamaños por nivel.
 *
 * @param maxRadius tope del radio: en un tablero de 18 celdas sobre una tablet, sin él las
 *   fichas saldrían enormes y la bandeja, que usa otro tamaño, parecería de otro juego.
 */
private class HexLayout(coords: Collection<HexCoord>, width: Float, height: Float, maxRadius: Float) {
    /** Radio (centro a vértice) de una celda, en píxeles. */
    val radius: Float
    private val originX: Float
    private val originY: Float

    init {
        val minX = coords.minOf { it.unitX() }
        val maxX = coords.maxOf { it.unitX() }
        val minY = coords.minOf { it.unitY() }
        val maxY = coords.maxOf { it.unitY() }
        radius = minOf(width / (maxX - minX + SQRT_3), height / (maxY - minY + 2f), maxRadius)
        originX = width / 2f - (minX + maxX) / 2f * radius
        originY = height / 2f - (minY + maxY) / 2f * radius
    }

    /** Centro en píxeles de la celda [coord]. */
    fun centerOf(coord: HexCoord): Offset =
        Offset(originX + coord.unitX() * radius, originY + coord.unitY() * radius)

    /** Celda bajo el punto [point] (puede caer fuera de la máscara: quien llama lo comprueba). */
    fun cellAt(point: Offset): HexCoord =
        HexCoord.fromUnit((point.x - originX) / radius, (point.y - originY) / radius)
}

/**
 * Pieza que el jugador está arrastrando.
 *
 * @property index posición de la pieza en la bandeja.
 * @property position punto del dedo en coordenadas de la raíz de la ventana, que es el único
 *   sistema común a la bandeja (donde nace el gesto) y al tablero (donde termina).
 */
private data class PieceDrag(val index: Int, val position: Offset)

/**
 * Pantalla de "Neon Hexa Flux".
 *
 * Estructura idéntica a los demás juegos por niveles: antesala con selector ([GameIntroScreen]) →
 * tablero → [GameOverOverlay], con [GamePauseControls] encima. La única pieza propia es el
 * cartel de derrota ([LostOverlay]): este juego puede perderse, y una derrota no guarda
 * resultado, así que no hay [com.kortexgames.app.game.GameOverInfo] que enseñar.
 *
 * La pantalla no decide nada: convierte el dedo en celdas axiales, emite [HexaFluxIntent] y pinta
 * el [HexaFluxUiState]. Los [HexaFluxEffect] se vuelven sonido y vibración en un único `collect`.
 *
 * **Dos formas de colocar, a propósito.** Arrastrar la pieza desde la bandeja es lo natural y
 * enseña dónde va a caer antes de soltar; tocar pieza y luego celda es más preciso en tableros
 * densos y no tapa el tablero con la mano. Ambas terminan en el mismo intent.
 *
 * @param graph grafo de dependencias (repositorios, audio/háptica, anuncios).
 * @param onExit vuelve a la pantalla anterior.
 */
@Composable
fun HexaFluxScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: HexaFluxViewModel = viewModel {
        HexaFluxViewModel(graph.progressRepository, graph.playerProgressRepository, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is HexaFluxEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is HexaFluxEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableIntStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.hexaFlux,
            title = GAME_TITLE,
            motif = GameMotif.HEXA_FLUX,
            description = stringResource(Res.string.hexaflux_intro_description),
            accent = Accent,
            icon = Icons.Rounded.Hexagon,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_HEXA_FLUX)
                vm.onIntent(HexaFluxIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
        )
        return
    }

    // En pausa el reloj se congela: bombas y portales se quedan quietos bajo el menú.
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)
    val density = LocalDensity.current
    val maxRadius = with(density) { 44.dp.toPx() }
    // La pieza arrastrada flota por encima del dedo: si fuera justo debajo, la mano taparía
    // tanto la pieza como la celda de destino.
    val dragLift = with(density) { Offset(0f, 64.dp.toPx()) }

    var selected by remember(state.level) { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<PieceDrag?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    val layout = remember(state.levelConfig, boardSize, maxRadius) {
        if (boardSize == IntSize.Zero) {
            null
        } else {
            HexLayout(state.boardState.keys, boardSize.width.toFloat(), boardSize.height.toFloat(), maxRadius)
        }
    }
    val playing = state.status == GameStatus.RUNNING

    /** Celda del tablero bajo un punto dado en coordenadas de raíz, o null si cae fuera. */
    fun anchorAt(rootPoint: Offset): HexCoord? =
        layout?.cellAt(rootPoint - boardOrigin)?.takeIf { it in state.boardState }

    val hover = drag?.let { anchorAt(it.position - dragLift) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val progress = state.progress
            val hudLabel = when (state.levelConfig.winCondition) {
                is WinCondition.TargetScore -> Res.string.hexaflux_hud_score
                WinCondition.ClearBoard -> Res.string.hexaflux_hud_clear
                is WinCondition.Survive -> Res.string.hexaflux_hud_survive
            }
            NeonBoardHud(
                level = state.level,
                progress = progress.fraction,
                progressLabel = stringResource(hudLabel, progress.current.toString(), progress.goal.toString()),
                accent = Accent,
                onRestart = { vm.onIntent(HexaFluxIntent.RestartLevel) },
                progressColor = LogicColors.NeonCyan,
            )
            ObjectiveRow(state = state, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                HexBoard(
                    state = state,
                    layout = layout,
                    clock = clock,
                    ghost = drag?.let { d -> hover?.let { anchor -> state.nextPieces.getOrNull(d.index)?.to(anchor) } },
                    enabled = playing,
                    onCellTap = { coord ->
                        state.nextPieces.getOrNull(selected)?.let { piece ->
                            vm.onIntent(HexaFluxIntent.PlacePiece(coord.q, coord.r, piece))
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            boardOrigin = it.positionInRoot()
                            boardSize = it.size
                        },
                )
                ComboBadge(combo = state.combo, modifier = Modifier.align(Alignment.TopCenter))
            }

            PieceTray(
                pieces = state.nextPieces,
                selected = selected,
                draggingIndex = drag?.index,
                enabled = playing,
                onTap = { index ->
                    // Primer toque elige; un toque sobre la ya elegida la gira. Así girar nunca
                    // ocurre por accidente al cambiar de pieza.
                    if (index == selected) vm.onIntent(HexaFluxIntent.RotatePiece(index)) else selected = index
                },
                onDragStart = { index, at ->
                    selected = index
                    drag = PieceDrag(index, at)
                },
                onDragBy = { amount -> drag = drag?.let { it.copy(position = it.position + amount) } },
                onDragEnd = { dropped ->
                    val current = drag
                    drag = null
                    // Soltar fuera del tablero cancela en silencio: no es una jugada fallida,
                    // es el jugador arrepintiéndose.
                    if (dropped && current != null) {
                        val anchor = anchorAt(current.position - dragLift)
                        val piece = state.nextPieces.getOrNull(current.index)
                        if (anchor != null && piece != null) {
                            vm.onIntent(HexaFluxIntent.PlacePiece(anchor.q, anchor.r, piece))
                        }
                    }
                },
            )
            Text(
                text = stringResource(Res.string.hexaflux_tray_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            )
        }

        // Pieza en vuelo, por encima de todo lo demás: sale de la bandeja y cruza el tablero.
        val flying = drag
        val flyingPiece = flying?.let { state.nextPieces.getOrNull(it.index) }
        if (flying != null && flyingPiece != null && layout != null) {
            val path = remember { Path() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val at = flying.position - rootOrigin - dragLift
                for (tile in flyingPiece.tiles) {
                    val center = at + Offset(tile.offset.unitX(), tile.offset.unitY()) * layout.radius
                    drawFluxTile(path, center, layout.radius, tile.tier, alpha = 0.92f)
                }
            }
        }

        val gameOver = state.gameOver
        if (state.isLevelCleared && gameOver != null) {
            GameOverOverlay(
                info = gameOver,
                audio = graph.audio,
                headline = stringResource(Res.string.hexaflux_gameover_headline),
                onPlayAgain = { vm.onIntent(HexaFluxIntent.PlayLevel(state.level)) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(HexaFluxIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(HexaFluxIntent.ChooseLevel) },
                accent = Accent,
            )
        }
        if (state.isGameOver) {
            LostOverlay(
                reason = stringResource(
                    if (state.movesLeft <= 0) Res.string.hexaflux_lost_no_moves else Res.string.hexaflux_lost_blocked,
                ),
                onRetry = { vm.onIntent(HexaFluxIntent.RestartLevel) },
                onChooseLevel = { vm.onIntent(HexaFluxIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HexaFluxIntent.Pause) },
            onResume = { vm.onIntent(HexaFluxIntent.Resume) },
            onExit = onExit,
            gameTitle = GAME_TITLE,
            help = GameHelpContent.hexaFlux,
            accent = Accent,
        )
    }
}

/**
 * Franja bajo el HUD: qué pide el nivel y cuántas jugadas quedan.
 *
 * El objetivo cambia de un nivel a otro (rota cada 3), así que tiene que estar siempre a la
 * vista: sin él, un jugador que viene de un nivel de puntuación seguiría acumulando puntos en
 * uno de limpieza sin entender por qué no avanza.
 */
@Composable
private fun ObjectiveRow(state: HexaFluxUiState, modifier: Modifier = Modifier) {
    val objective = when (val win = state.levelConfig.winCondition) {
        is WinCondition.TargetScore -> stringResource(Res.string.hexaflux_objective_score, win.target.toString())
        WinCondition.ClearBoard -> stringResource(Res.string.hexaflux_objective_clear)
        is WinCondition.Survive -> stringResource(Res.string.hexaflux_objective_survive, win.turns.toString())
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = objective,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.hexaflux_moves_left, state.movesLeft.toString()),
            style = MaterialTheme.typography.labelLarge,
            color = if (state.movesLeft <= LOW_MOVES) LogicColors.Error else LogicColors.OnDark,
            modifier = Modifier
                .background(LogicColors.SurfaceVariantDark, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Cartel "COMBO ×N" sobre el tablero. Solo aparece con dos o más fusiones encadenadas: una
 * fusión suelta es lo normal y celebrarla le quitaría valor a la cadena.
 *
 * Es el único elemento de la pantalla con [softGlow]: el halo que respira está reservado a lo
 * que se quiere destacar (§9.4, "bucles con propósito") y aquí eso es la reacción en cadena.
 */
@Composable
private fun ComboBadge(combo: Int, modifier: Modifier = Modifier) {
    // Se recuerda el último combo "de verdad" para que el número no cambie a 0 o 1 mientras el
    // cartel se desvanece.
    var shown by remember { mutableIntStateOf(combo) }
    if (combo > 1) shown = combo
    val shape = RoundedCornerShape(12.dp)
    AnimatedVisibility(
        visible = combo > 1,
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
        exit = fadeOut(tween(250)),
        modifier = modifier.padding(top = 4.dp),
    ) {
        Text(
            text = stringResource(Res.string.hexaflux_combo, shown.toString()),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = LogicColors.Amber,
            modifier = Modifier
                .softGlow(color = LogicColors.Amber, shape = shape, minElevation = 4.dp, maxElevation = 16.dp)
                .background(LogicColors.SurfaceDark, shape)
                .border(1.5.dp, LogicColors.Amber.copy(alpha = 0.7f), shape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * El tablero hexagonal, dibujado entero en un `Canvas`.
 *
 * Capas, de abajo arriba: celda vacía → gimmick → ficha → portal (anillo, por encima de la ficha
 * que lo ocupe) → chispas → sombra de la pieza arrastrada.
 *
 * **Animación.** Las celdas que cambiaron en la última jugada traen su [CellAnim] en el estado;
 * dos `Animatable` compartidos las mueven todas a la vez: `pop` es un resorte con rebote (lo
 * táctil usa `spring`, §9.4) que escala fichas colocadas, fusionadas y aparecidas, y `burst` es
 * el avance lineal de las chispas. Compartirlos es correcto porque todo lo que cambia en una
 * jugada cambia en el mismo instante, y evita un `Animatable` por celda.
 *
 * @param layout encaje del tablero, o null hasta que se conoce el tamaño del lienzo.
 * @param ghost pieza arrastrada y celda donde caería su ancla, o null si no hay arrastre sobre
 *   el tablero.
 * @param enabled false bloquea los toques (pausa, nivel terminado).
 */
@Composable
private fun HexBoard(
    state: HexaFluxUiState,
    layout: HexLayout?,
    clock: BoardClock,
    ghost: Pair<HexPiece, HexCoord>?,
    enabled: Boolean,
    onCellTap: (HexCoord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = state.boardState
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(board) {
        pop.snapTo(0f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(board) {
        burst.snapTo(0f)
        burst.animateTo(1f, tween(BURST_MS))
    }

    val path = remember { Path() }
    val measurer = rememberTextMeasurer()
    val currentBoard by rememberUpdatedState(board)
    val currentTap by rememberUpdatedState(onCellTap)
    val description = stringResource(Res.string.hexaflux_board_description, board.values.count { it.isFree }.toString())

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(layout, enabled) {
                if (layout == null || !enabled) return@pointerInput
                detectTapGestures { point ->
                    val coord = layout.cellAt(point)
                    if (coord in currentBoard) currentTap(coord)
                }
            },
    ) {
        if (layout == null) return@Canvas
        val radius = layout.radius
        val time = clock.seconds
        val popped = pop.value
        val sparks = burst.value

        for (cell in board.values) {
            val center = layout.centerOf(cell.coord)
            path.setHex(center, radius * SOCKET_FRACTION)
            drawPath(path, LogicColors.SurfaceVariantDark.copy(alpha = 0.55f))
            drawPath(path, Accent.copy(alpha = 0.18f), style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))

            // El resorte pasa de 1 en el rebote; por abajo se recorta para no dibujar al revés.
            val scale = when (cell.anim) {
                CellAnim.NONE, CellAnim.SHATTERED -> 1f
                CellAnim.PLACED -> 0.7f + 0.3f * popped
                CellAnim.MERGED -> 0.35f + 0.65f * popped
                CellAnim.TELEPORTED, CellAnim.SPAWNED -> popped
            }.coerceAtLeast(0f)

            when (val gimmick = cell.gimmick) {
                is Gimmick.Ice -> drawIce(path, center, radius, gimmick.layers, time)
                Gimmick.Stone -> drawStone(path, center, radius * scale)
                is Gimmick.TurnBomb -> drawBomb(center, radius, gimmick.turnsLeft, time, measurer)
                Gimmick.Corrupt -> drawCorrupt(path, center, radius * scale, time)
                is Gimmick.Portal, null -> Unit
            }

            cell.tier?.let { tier ->
                val lit = if (cell.anim == CellAnim.MERGED) 1f - sparks else 0f
                drawFluxTile(path, center, radius * scale, tier, lit = lit)
            }
            (cell.gimmick as? Gimmick.Portal)?.let { drawPortal(center, radius, it.pairId, time) }

            val seed = cell.coord.q * 31 + cell.coord.r
            when (cell.anim) {
                CellAnim.MERGED -> cell.tier?.let { drawSparkBurst(center, it.color, radius * 1.7f, sparks, seed) }
                CellAnim.SHATTERED -> drawSparkBurst(center, LogicColors.NeonCyan, radius * 1.5f, sparks, seed)
                else -> Unit
            }
        }

        // Sombra de la pieza arrastrada: del color de cada ficha si la jugada es legal y en rojo
        // si no, para que el veredicto se vea antes de soltar.
        ghost?.let { (piece, anchor) ->
            val legal = HexaFluxEngine.canPlace(board, piece, anchor)
            for (tile in piece.tiles) {
                val at = anchor + tile.offset
                if (at !in board) continue
                val color = if (legal) tile.tier.color else LogicColors.Error
                path.setHex(layout.centerOf(at), radius * TILE_FRACTION)
                drawPath(path, color.copy(alpha = 0.26f))
                drawPath(path, color.copy(alpha = 0.90f), style = Stroke(radius * 0.07f, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * Bandeja con las piezas disponibles.
 *
 * @param selected pieza elegida para el modo "tocar celda".
 * @param draggingIndex pieza en pleno arrastre (se atenúa en la bandeja: está "en la mano").
 * @param onDragStart índice y punto de arranque en coordenadas de raíz.
 * @param onDragEnd `true` si el dedo se levantó (soltar), `false` si el gesto se canceló.
 */
@Composable
private fun PieceTray(
    pieces: List<HexPiece>,
    selected: Int,
    draggingIndex: Int?,
    enabled: Boolean,
    onTap: (Int) -> Unit,
    onDragStart: (Int, Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        pieces.forEachIndexed { index, piece ->
            TraySlot(
                piece = piece,
                index = index,
                selected = index == selected,
                dragging = index == draggingIndex,
                enabled = enabled,
                onTap = { onTap(index) },
                onDragStart = { onDragStart(index, it) },
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
            )
        }
    }
}

/**
 * Un hueco de la bandeja con su pieza.
 *
 * El giro se anima "hacia atrás": el estado ya trae la pieza girada 60°, así que el dibujo
 * arranca en −60° y vuelve a 0° con un resorte. El resultado es el mismo que animar el ángulo,
 * pero sin guardar en la UI una rotación que podría desincronizarse de la del estado.
 */
@Composable
private fun TraySlot(
    piece: HexPiece,
    index: Int,
    selected: Boolean,
    dragging: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
) {
    val spin = remember(piece.id) { Animatable(0f) }
    var seen by remember(piece.id) { mutableStateOf(piece) }
    LaunchedEffect(piece) {
        if (piece != seen) {
            seen = piece
            spin.snapTo(-60f)
            spin.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val highlight by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "traySlotHighlight",
    )

    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentTap by rememberUpdatedState(onTap)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragBy by rememberUpdatedState(onDragBy)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(20.dp)
    val path = remember { Path() }
    val description = stringResource(
        Res.string.hexaflux_tray_piece_description, (index + 1).toString(), piece.tiles.size.toString(),
    )

    Canvas(
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer {
                val grow = 1f + 0.06f * highlight
                scaleX = grow
                scaleY = grow
                alpha = if (dragging) 0.3f else 1f
            }
            .background(lerp(LogicColors.SurfaceDark, LogicColors.SurfaceVariantDark, highlight), shape)
            .border(1.5.dp, lerp(LogicColors.SurfaceVariantDark, Accent, highlight), shape)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { contentDescription = description }
            .pointerInput(piece.id, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { currentTap() }
            }
            .pointerInput(piece.id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { currentDragStart(origin + it) },
                    onDrag = { change, amount ->
                        change.consume()
                        currentDragBy(amount)
                    },
                    onDragEnd = { currentDragEnd(true) },
                    onDragCancel = { currentDragEnd(false) },
                )
            },
    ) {
        // Radio fijo que deja caber la pieza más larga (tres en línea) en cualquier giro.
        val radius = size.minDimension * 0.17f
        // Se centra por el centroide y no por el ancla: una pieza en codo anclada en un extremo
        // quedaría descolgada hacia un lado del hueco.
        val cx = piece.tiles.map { it.offset.unitX() }.average().toFloat()
        val cy = piece.tiles.map { it.offset.unitY() }.average().toFloat()
        rotate(spin.value) {
            for (tile in piece.tiles) {
                val at = this.center + Offset(tile.offset.unitX() - cx, tile.offset.unitY() - cy) * radius
                drawFluxTile(path, at, radius, tile.tier)
            }
        }
    }
}

/**
 * Cartel de nivel perdido.
 *
 * No reutiliza [GameOverOverlay] porque ese muestra un resultado guardado (puntaje, ranking,
 * récord) y una derrota no guarda nada. Sí comparte su lenguaje visual —velo y tarjeta de
 * [modalScrim]/[modalCard]— para que se lea como parte de la misma familia de modales.
 *
 * @param reason frase que explica por qué se perdió (sin jugadas / sin hueco).
 */
@Composable
private fun LostOverlay(reason: String, onRetry: () -> Unit, onChooseLevel: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lostOverlayReveal",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim(0.72f * reveal.coerceIn(0f, 1f))
            // Se traga los toques: el tablero de debajo ya no admite jugadas, pero sin esto un
            // toque "atravesaría" el velo hasta el botón de reiniciar del HUD.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp)
                .graphicsLayer {
                    val grow = 0.9f + 0.1f * reveal
                    scaleX = grow
                    scaleY = grow
                    alpha = reveal.coerceIn(0f, 1f)
                }
                .modalCard(LogicColors.Error)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.hexaflux_lost_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                textAlign = TextAlign.Center,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )
            AnimatedGameButton(
                text = stringResource(Res.string.hexaflux_lost_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(Res.string.hexaflux_lost_choose_level),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                modifier = Modifier.bounceClick(onClick = onChooseLevel).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// --- Primitivas de dibujo -------------------------------------------------------

/** Reescribe este [Path] como un hexágono *pointy-top* de [radius] centrado en [center]. */
private fun Path.setHex(center: Offset, radius: Float): Path {
    reset()
    for (i in 0 until 6) {
        val x = center.x + radius * HEX_COS[i]
        val y = center.y + radius * HEX_SIN[i]
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
    return this
}

/**
 * Contorno de neón sobre un hexágono ya cargado en [path].
 *
 * [com.kortexgames.app.ui.components.drawNeonTile] solo sabe de rectángulos redondeados, así
 * que aquí se replica su MISMA proporción de capas —halo ancho → halo intermedio → trazo nítido
 * → núcleo blanco al encender— sobre un `Path` arbitrario, como pide la §9.7 del CLAUDE.md para
 * los trazos que no son un contorno de tile. Así las fichas hexagonales comparten "tubo de luz"
 * con las teclas y celdas del resto de juegos.
 *
 * @param lit encendido 0..1; añade el núcleo blanco (p. ej. en la ficha recién fusionada).
 */
private fun DrawScope.drawNeonHex(path: Path, color: Color, radius: Float, lit: Float = 0f, alpha: Float = 1f) {
    drawPath(path, color.copy(alpha = 0.10f * alpha), style = Stroke(radius * 0.40f, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.22f * alpha), style = Stroke(radius * 0.20f, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.95f * alpha), style = Stroke(radius * 0.08f, join = StrokeJoin.Round))
    if (lit > 0f) {
        drawPath(path, Color.White.copy(alpha = 0.85f * lit * alpha), style = Stroke(radius * 0.035f, join = StrokeJoin.Round))
    }
}

/**
 * Una ficha de energía: hexágono con degradado del color de su tier, borde de neón y tantos
 * puntos como su nivel. Los puntos existen por accesibilidad: sin ellos el tier se distinguiría
 * solo por el color, y cian/verde o magenta/ámbar se confunden con ciertos daltonismos.
 *
 * @param radius radio de la **malla** (no de la ficha): la ficha se encoge a [TILE_FRACTION].
 */
private fun DrawScope.drawFluxTile(
    path: Path,
    center: Offset,
    radius: Float,
    tier: FluxTier,
    lit: Float = 0f,
    alpha: Float = 1f,
) {
    val r = radius * TILE_FRACTION
    if (r <= 0f) return
    val color = tier.color
    path.setHex(center, r)
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(lerp(color, Color.White, 0.35f), color, lerp(color, LogicColors.BackgroundDark, 0.45f)),
            center = center - Offset(r * 0.25f, r * 0.30f),
            radius = r * 1.35f,
        ),
        alpha = alpha,
    )
    drawNeonHex(path, color, r, lit, alpha)

    val pips = tier.ordinal + 1
    val gap = r * 0.24f
    val start = center.x - gap * (pips - 1) / 2f
    for (i in 0 until pips) {
        drawCircle(LogicColors.BackgroundDark.copy(alpha = 0.60f * alpha), r * 0.075f, Offset(start + gap * i, center.y))
    }
}

/**
 * Hielo: placa translúcida con vetas de brillo en diagonal que se deslizan muy despacio. Una
 * segunda capa se anuncia con un hexágono interior, para que "le faltan dos golpes" se vea.
 */
private fun DrawScope.drawIce(path: Path, center: Offset, radius: Float, layers: Int, time: Float) {
    val r = radius * TILE_FRACTION
    path.setHex(center, r)
    drawPath(path, LogicColors.NeonCyan.copy(alpha = 0.20f))
    clipPath(path) {
        val drift = (time * 0.08f) % 1f
        for (i in -2..2) {
            val x = center.x + (i + drift) * r * 0.7f
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(x - r * 0.6f, center.y + r),
                end = Offset(x + r * 0.6f, center.y - r),
                strokeWidth = r * 0.10f,
            )
        }
    }
    drawPath(path, Color.White.copy(alpha = 0.60f), style = Stroke(r * 0.06f, join = StrokeJoin.Round))
    if (layers > 1) {
        path.setHex(center, r * 0.62f)
        drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(r * 0.06f, join = StrokeJoin.Round))
    }
}

/** Metal: placa gris industrial con bisel y tres remaches. Sin brillo ni animación: es inerte. */
private fun DrawScope.drawStone(path: Path, center: Offset, radius: Float) {
    val r = radius * TILE_FRACTION
    if (r <= 0f) return
    path.setHex(center, r)
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                lerp(LogicColors.SurfaceVariantDark, LogicColors.OnDarkMuted, 0.38f),
                lerp(LogicColors.SurfaceDark, LogicColors.BackgroundDark, 0.30f),
            ),
            start = center - Offset(r, r),
            end = center + Offset(r, r),
        ),
    )
    drawPath(path, LogicColors.OnDarkMuted.copy(alpha = 0.45f), style = Stroke(r * 0.07f, join = StrokeJoin.Round))
    for (i in 0 until 6 step 2) {
        val at = center + Offset(HEX_COS[i], HEX_SIN[i]) * (r * 0.58f)
        drawCircle(LogicColors.BackgroundDark.copy(alpha = 0.65f), r * 0.09f, at)
    }
}

/**
 * Bomba de turno: núcleo rojo con su cuenta atrás. El parpadeo se acelera a medida que se agota
 * la mecha, de modo que la urgencia se percibe con el rabillo del ojo sin leer el número.
 */
private fun DrawScope.drawBomb(center: Offset, radius: Float, turnsLeft: Int, time: Float, measurer: TextMeasurer) {
    val blink = 0.5f + 0.5f * sin(time * (3f + 9f / turnsLeft.coerceAtLeast(1)))
    val color = LogicColors.Error
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.40f * blink), Color.Transparent), center, radius * 1.15f),
        radius = radius * 1.15f,
        center = center,
    )
    val core = radius * 0.52f
    drawCircle(lerp(LogicColors.BackgroundDark, color, 0.30f + 0.35f * blink), core, center)
    drawCircle(color, core, center, style = Stroke(radius * 0.08f))
    val text = measurer.measure(
        text = turnsLeft.toString(),
        style = TextStyle(color = LogicColors.OnDark, fontSize = (radius * 0.62f).toSp(), fontWeight = FontWeight.Black),
    )
    drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
}

/** Ficha corrupta: hexágono oscuro con borde rojo y un aspa que titila, como una señal averiada. */
private fun DrawScope.drawCorrupt(path: Path, center: Offset, radius: Float, time: Float) {
    val r = radius * TILE_FRACTION * 0.94f
    if (r <= 0f) return
    val color = LogicColors.Error
    path.setHex(center, r)
    drawPath(path, lerp(LogicColors.BackgroundDark, color, 0.26f))
    drawNeonHex(path, color, r, alpha = 0.80f)
    val flicker = 0.65f + 0.35f * sin(time * 5.3f + center.x)
    val arm = r * 0.34f
    drawLine(color.copy(alpha = flicker), center - Offset(arm, arm), center + Offset(arm, arm), r * 0.11f, StrokeCap.Round)
    drawLine(color.copy(alpha = flicker), center + Offset(-arm, arm), center + Offset(arm, -arm), r * 0.11f, StrokeCap.Round)
}

/**
 * Portal: tres arcos que giran despacio sobre un resplandor del color de su pareja. Se dibuja
 * por encima de la ficha que lo ocupe para que el portal siga viéndose aunque esté tapado.
 */
private fun DrawScope.drawPortal(center: Offset, radius: Float, pairId: Int, time: Float) {
    val color = PortalColors[pairId % PortalColors.size]
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), center, radius * 0.9f),
        radius = radius * 0.9f,
        center = center,
    )
    val ring = radius * 0.55f
    rotate(degrees = time * 40f, pivot = center) {
        for (i in 0 until 3) {
            drawArc(
                color = color,
                startAngle = i * 120f,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = center - Offset(ring, ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(radius * 0.09f, cap = StrokeCap.Round),
            )
        }
    }
}
