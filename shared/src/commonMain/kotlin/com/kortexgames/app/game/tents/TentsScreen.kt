package com.kortexgames.app.game.tents

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.kortexgames.app.ui.components.BoardClock
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawBoardSocket
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonGrass
import com.kortexgames.app.ui.components.drawNeonPine
import com.kortexgames.app.ui.components.drawNeonTent
import com.kortexgames.app.ui.components.drawNeonWire
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.tents_board_generating
import kortexgames.shared.generated.resources.tents_counter_column_description
import kortexgames.shared.generated.resources.tents_counter_row_description
import kortexgames.shared.generated.resources.tents_gameover_headline
import kortexgames.shared.generated.resources.tents_hud_progress
import kortexgames.shared.generated.resources.tents_intro_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// --- Constantes de composición y de animación ---------------------------------

/** Nombre del juego: contenido de catálogo (§10), no texto de UI traducible. */
private const val GAME_TITLE = "Neon Trees & Tents"

/**
 * Grosor de la franja de contadores como fracción de la casilla. Más estrecha que una casilla
 * entera: los números no necesitan tanto sitio y ese espacio vale más repartido en el tablero.
 */
private const val HEADER_FRACTION = 0.72f

/** Tamaño del número de un contador como fracción de la casilla: legible también en 8×8. */
private const val COUNTER_FONT_FRACTION = 0.38f

/** Lado de la baldosa respecto a la casilla: el hueco entre baldosas hace de rejilla. */
private const val SOCKET_FRACTION = 0.90f

/** "Nunca": marca de tiempo de algo que no ha ocurrido. Lejos en el pasado = animación acabada. */
private const val NEVER = -1_000f

/** Duración del brote de una marca recién puesta (micro-feedback, §9.4). */
private const val POP_SEC = 0.30f

/** Duración del estallido de chispas al plantar una tienda. */
private const val BURST_SEC = 0.45f

/** Duración del barrido de luz que recorre una fila/columna al cuadrar su contador. */
private const val SWEEP_SEC = 0.55f

/** Desfase entre los estallidos de victoria de una tienda y la siguiente. */
private const val VICTORY_STAGGER_SEC = 0.07f

/** Luciérnagas del ambiente. Pocas a propósito: es atmósfera, no un segundo tablero que mirar. */
private const val FIREFLIES = 16

/** Acento de la categoría (Pensamiento Lógico): cromo del HUD, la placa y la antesala. */
private val Accent = CategoryPalette.Logic

/** Pseudoaleatorio determinista 0..1: mismo [n] → mismo valor en cada frame (sin estado). */
private fun hash01(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/**
 * Pantalla de "Neon Trees & Tents".
 *
 * Estructura idéntica a los demás juegos por niveles: antesala con selector ([GameIntroScreen]) →
 * tablero → [GameOverOverlay], con [GamePauseControls] encima.
 *
 * La pantalla no decide nada: convierte el dedo en casillas, emite [TentsIntent] y pinta el
 * [TentsUiState]. Los [TentsEffect] se vuelven sonido y vibración en un único `collect`.
 *
 * **Dirección de arte: un claro de bosque de noche.** Luciérnagas a la deriva de fondo, pinos que
 * se mecen y se encienden cuando reciben su tienda, tiendas con una lámpara que titila dentro y
 * una cuerda de luz que ata cada una a su árbol. Todo lo que se mueve en bucle es lento y de poca
 * amplitud (§9.4); lo rápido —brotes, chispas, barridos— solo ocurre como respuesta a una jugada.
 *
 * @param graph grafo de dependencias (repositorios, audio/háptica, anuncios).
 * @param onExit vuelve a la pantalla anterior.
 */
@Composable
fun TentsScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: TentsViewModel = viewModel {
        TentsViewModel(graph.progressRepository, graph.playerProgressRepository, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is TentsEffect.PlaySound -> graph.audio.playSound(effect.sound.effect)
                is TentsEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic.feedback)
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.tents,
            title = GAME_TITLE,
            motif = GameMotif.TENTS_FOREST,
            description = stringResource(Res.string.tents_intro_description),
            accent = Accent,
            icon = Icons.Rounded.Park,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_TENTS)
                vm.onIntent(TentsIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
        )
        return
    }

    // Un único reloj para toda la pantalla: ambiente, tablero y contadores laten sincronizados.
    // En pausa se congela — el bosque se queda quieto bajo el menú en vez de seguir distrayendo.
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        ForestAmbience(clock = clock, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            val placed = state.rowCounts.sum()
            val total = state.rowTargets.sum()
            NeonBoardHud(
                level = state.level,
                // Puede pasar de 1 si el jugador planta tiendas de más: el HUD lo recorta, y el
                // exceso ya lo delatan los contadores en rojo.
                progress = if (total > 0) placed.toFloat() / total else 0f,
                progressLabel = stringResource(Res.string.tents_hud_progress, placed.toString(), total.toString()),
                accent = Accent,
                onRestart = { vm.onIntent(TentsIntent.RestartLevel) },
                progressColor = LogicColors.NeonCyan,
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isGenerating || state.gridSize == 0) {
                    Text(
                        text = stringResource(Res.string.tents_board_generating),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LogicColors.OnDarkMuted,
                    )
                } else {
                    // Casilla cuadrada: la mayor que quepa contando la franja de contadores.
                    val cell = minOf(maxWidth, maxHeight) / (state.gridSize + HEADER_FRACTION)
                    TentsField(state = state, cell = cell, clock = clock, onIntent = vm::onIntent)
                }
            }
        }

        val gameOver = state.gameOver
        if (state.status == GameStatus.FINISHED && gameOver != null) {
            GameOverOverlay(
                info = gameOver,
                audio = graph.audio,
                headline = stringResource(Res.string.tents_gameover_headline),
                onPlayAgain = { vm.onIntent(TentsIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(TentsIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(TentsIntent.ChooseLevel) },
                accent = Accent,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(TentsIntent.Pause) },
            onResume = { vm.onIntent(TentsIntent.Resume) },
            onExit = onExit,
            gameTitle = GAME_TITLE,
            help = GameHelpContent.tents,
            accent = Accent,
        )
    }
}

/**
 * Ambiente de fondo: dos auroras muy tenues que respiran y un puñado de luciérnagas a la deriva.
 *
 * Es lo que convierte "una rejilla sobre fondo liso" en un lugar. Va por DETRÁS de todo y con
 * opacidades bajas: la regla de proporción del sistema de diseño (superficie oscura, acento
 * escaso, §9.1) sigue mandando, y nada de esto debe poder confundirse con una pieza del juego.
 *
 * Cada luciérnaga es una función pura del reloj y de su índice (posición, ritmo y parpadeo salen
 * de [hash01]): no hay lista de partículas que actualizar ni estado que guardar.
 */
@Composable
private fun ForestAmbience(clock: BoardClock, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val t = clock.seconds
        val w = size.width
        val h = size.height

        fun aurora(color: Color, at: Offset, phase: Float) {
            val reach = w * 0.85f
            val breath = 0.085f + 0.03f * sin(t * 0.45f + phase)
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = breath), Color.Transparent), at, reach),
                radius = reach,
                center = at,
            )
        }
        aurora(Accent, Offset(w * 0.12f, h * 0.22f), 0f)
        aurora(LogicColors.NeonGreen, Offset(w * 0.92f, h * 0.86f), 2.1f)

        for (i in 0 until FIREFLIES) {
            // Sube despacio y reaparece por abajo; el vaivén lateral evita la línea recta.
            val rise = 0.010f + 0.018f * hash01(i * 7 + 3)
            val y = (((hash01(i * 3 + 2) - t * rise) % 1f) + 1f) % 1f * h
            val x = (hash01(i * 3 + 1) + 0.05f * sin(t * (0.25f + 0.4f * hash01(i * 5)) + i)) * w
            val twinkle = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * (1.1f + 1.4f * hash01(i * 11)) + i * 2.1f))
            val color = if (i % 3 == 0) LogicColors.Amber else LogicColors.NeonGreen
            val radius = (1.1f + 1.5f * hash01(i * 13)).dp.toPx()
            val at = Offset(x, y)
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.30f * twinkle), Color.Transparent), at, radius * 6f),
                radius = radius * 6f,
                center = at,
            )
            drawCircle(color.copy(alpha = 0.75f * twinkle), radius, at)
        }
    }
}

/**
 * Memoria de animación del tablero: **cuándo** pasó cada cosa, en segundos del [BoardClock].
 *
 * El dominio solo sabe cómo está el tablero ahora; para animar hace falta saber qué acaba de
 * cambiar. En vez de un `Animatable` por casilla (hasta 64, más filas y columnas), se anota el
 * instante de cada cambio y el dibujo deriva la animación de `ahora − instante`. Así cualquier
 * cambio se anima igual venga de donde venga —un toque, el arrastre o un reinicio que vacía
 * medio tablero— sin que el gesto tenga que avisar a nadie.
 *
 * No es estado de Compose a propósito: nadie debe recomponerse cuando se anota un instante, y el
 * Canvas ya se redibuja en cada frame porque lee el reloj.
 */
private class BoardMemory(size: Int, val createdAt: Float) {
    private var board: TentsBoard? = null
    private var rows: List<TentsLineStatus> = emptyList()
    private var columns: List<TentsLineStatus> = emptyList()

    /** Instante del último cambio de cada casilla. */
    val changedAt = FloatArray(size * size) { NEVER }

    /** Instante en que cada fila / columna cuadró su contador por última vez. */
    val rowExactAt = FloatArray(size) { NEVER }
    val columnExactAt = FloatArray(size) { NEVER }

    /** Instante de la victoria. */
    var solvedAt = NEVER
        private set

    /** Encendido suavizado (0..1) de cada árbol; lo va acercando a su objetivo el propio dibujo. */
    val treeGlow = FloatArray(size * size)

    /** Instante del frame anterior, para que el suavizado de [treeGlow] no dependa de los FPS. */
    var lastFrameAt = createdAt

    /** Compara [state] con lo último visto y anota qué ha cambiado en el instante [now]. */
    fun observe(state: TentsUiState, now: Float) {
        val next = state.board
        val previous = board
        if (previous === next) return
        if (previous != null && previous.size == next.size) {
            for (i in next.cells.indices) {
                if (previous.cells[i].type != next.cells[i].type) changedAt[i] = now
            }
        }
        val nextRows = List(next.size) { state.rowStatus(it) }
        val nextColumns = List(next.size) { state.columnStatus(it) }
        // Con `previous == null` es el primer vistazo: los contadores que nacen cuadrados (los
        // ceros) no deben lanzar su barrido al abrir el nivel.
        if (previous != null) {
            for (i in 0 until next.size) {
                if (nextRows[i] == TentsLineStatus.EXACT && rows.getOrNull(i) != TentsLineStatus.EXACT) rowExactAt[i] = now
                if (nextColumns[i] == TentsLineStatus.EXACT && columns.getOrNull(i) != TentsLineStatus.EXACT) columnExactAt[i] = now
            }
        }
        rows = nextRows
        columns = nextColumns
        solvedAt = if (!state.isSolved) NEVER else if (solvedAt == NEVER) now else solvedAt
        board = next
    }
}

/**
 * Tablero con sus dos franjas de contadores: columnas arriba, filas a la izquierda.
 *
 * Los contadores son composables (uno por fila/columna) y no parte del Canvas porque cada uno
 * anima su propio color y su propio rebote: al plantar una tienda solo se recomponen los dos que
 * cambian.
 */
@Composable
private fun TentsField(state: TentsUiState, cell: Dp, clock: BoardClock, onIntent: (TentsIntent) -> Unit) {
    val n = state.gridSize
    val header = cell * HEADER_FRACTION
    // `peek()` y no `seconds`: aquí solo se anota "cuándo"; leer el reloj observable en la
    // composición la repetiría en cada frame.
    val memory = remember(state.level, n) { BoardMemory(n, clock.peek()) }
    memory.observe(state, clock.peek())

    // Parpadeo de error compartido: todos los avisos laten a la vez. Con una animación por
    // elemento irían desfasados y la pantalla parecería estropeada.
    val blink = { 0.72f + 0.28f * cos(clock.seconds * 4.5f) }

    Column {
        Row {
            Spacer(Modifier.width(header))
            for (x in 0 until n) {
                TentsCounter(
                    target = state.columnTargets[x],
                    current = state.columnCounts[x],
                    blink = blink,
                    cell = cell,
                    description = stringResource(
                        Res.string.tents_counter_column_description,
                        (x + 1).toString(), state.columnCounts[x].toString(), state.columnTargets[x].toString(),
                    ),
                    modifier = Modifier.size(width = cell, height = header),
                )
            }
        }
        Row {
            Column {
                for (y in 0 until n) {
                    TentsCounter(
                        target = state.rowTargets[y],
                        current = state.rowCounts[y],
                        blink = blink,
                        cell = cell,
                        description = stringResource(
                            Res.string.tents_counter_row_description,
                            (y + 1).toString(), state.rowCounts[y].toString(), state.rowTargets[y].toString(),
                        ),
                        modifier = Modifier.size(width = header, height = cell),
                    )
                }
            }
            TentsBoardCanvas(
                board = state.board,
                level = state.level,
                clock = clock,
                memory = memory,
                blink = blink,
                onIntent = onIntent,
                modifier = Modifier.size(cell * n),
            )
        }
    }
}

/**
 * Contador de borde: una ficha con el número de tiendas que pide una fila o columna.
 *
 * Tres lecturas (ver [TentsLineStatus]):
 *  - **falta** → ficha apagada, número atenuado;
 *  - **exacto** → se enciende en verde con un rebote de resorte: es el "bien hecho" del juego;
 *  - **excedido** → rojo, con una sacudida al pasarse y un parpadeo suave mientras siga así.
 *
 * @param current tiendas plantadas ahora. Además de decidir el estado, relanza la sacudida si el
 *   jugador sigue añadiendo tiendas a una línea que ya estaba excedida.
 * @param blink opacidad del parpadeo de error, como lambda para leerla en `graphicsLayer` y no
 *   recomponer el texto en cada frame.
 * @param description lectura de accesibilidad: el número solo no dice ni a qué línea pertenece
 *   ni cuántas tiendas lleva ya.
 */
@Composable
private fun TentsCounter(
    target: Int,
    current: Int,
    blink: () -> Float,
    cell: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val status = TentsLineStatus.of(current, target)
    val tint by animateColorAsState(
        targetValue = when (status) {
            TentsLineStatus.UNDER -> LogicColors.OnDarkMuted
            TentsLineStatus.EXACT -> LogicColors.NeonGreen
            TentsLineStatus.OVER -> LogicColors.Error
        },
        animationSpec = tween(180),
        label = "tentsCounterTint",
    )
    // Cuánto "encendida" está la ficha: relleno y borde solo aparecen fuera de UNDER.
    val lit by animateFloatAsState(
        targetValue = if (status == TentsLineStatus.UNDER) 0f else 1f,
        animationSpec = tween(220),
        label = "tentsCounterLit",
    )
    val bump = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(status, current) {
        when (status) {
            TentsLineStatus.EXACT -> {
                bump.snapTo(1.32f)
                bump.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            }
            // Resorte muy poco amortiguado desde un extremo = sacudida que se apaga sola.
            TentsLineStatus.OVER -> {
                shake.snapTo(1f)
                shake.animateTo(0f, spring(dampingRatio = 0.16f, stiffness = Spring.StiffnessMedium))
            }
            TentsLineStatus.UNDER -> Unit
        }
    }

    val density = LocalDensity.current
    val fontSize = with(density) { (cell * COUNTER_FONT_FRACTION).toSp() }
    val shakeReach = with(density) { 5.dp.toPx() }
    val shape = RoundedCornerShape(cell * 0.24f)
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .padding(cell * 0.07f)
            .graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
                translationX = shake.value * shakeReach
                alpha = if (status == TentsLineStatus.OVER) blink() else 1f
            }
            .background(LogicColors.SurfaceDark.copy(alpha = 0.55f), shape)
            .background(tint.copy(alpha = 0.18f * lit), shape)
            .border(1.2.dp, tint.copy(alpha = 0.75f * lit), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = target.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize),
            fontWeight = FontWeight.Black,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * La rejilla: dibujo + captura del gesto, en **un solo [Canvas]** que se redibuja con el reloj.
 *
 * **Gesto.** `awaitEachGesture` a mano (como en Shikaku) porque hacen falta dos cosas que
 * `detectTapGestures` no da juntas: respuesta en el mismo frame del contacto, sin esperar a que
 * se levante el dedo, y continuar el toque como arrastre. El primer contacto cicla la casilla; si
 * con eso quedó en pasto, seguir arrastrando **siembra pasto** en las casillas vacías por las que
 * pasa el dedo. Es el "modo rápido" del juego sin botón de modo: descartar una fila entera es la
 * jugada más repetida. El arrastre solo pisa casillas vacías a propósito — nunca borra una tienda
 * ni un pasto ya puesto, así que un dedo que se desvía no destruye trabajo ni cuesta puntos.
 *
 * **Capas**, de atrás hacia delante: placa → baldosas → barridos de fila/columna → cuerdas de
 * luz árbol↔tienda → pasto, árboles y tiendas → ondas y chispas. El orden importa: las chispas
 * van las últimas para que ningún relleno las tape, y las cuerdas por debajo de las figuras para
 * que parezcan salir de ellas.
 *
 * **Neón.** Las figuras usan los glifos compartidos ([drawNeonPine], [drawNeonTent]), que
 * replican la receta de capas de `drawNeonTile` (§9.7), y el resto sale del kit de tablero
 * ([drawNeonBoardPlate], [drawBoardSocket], [drawNeonWire], [drawSparkBurst]). El resplandor es
 * de esos halos y no de `Modifier.softGlow`: ese modificador es una sombra de elevación sobre un
 * composable, y aquí todo son trazos dentro de un único Canvas.
 *
 * @param level clave del detector de gestos: al cambiar de nivel se descarta cualquier gesto a
 *   medias del tablero anterior.
 * @param memory instantes de los últimos cambios; de ahí salen todas las animaciones de reacción.
 * @param blink opacidad del parpadeo de error; se lee solo al dibujar.
 */
@Composable
private fun TentsBoardCanvas(
    board: TentsBoard,
    level: Int,
    clock: BoardClock,
    memory: BoardMemory,
    blink: () -> Float,
    onIntent: (TentsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = board.size
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentBoard by rememberUpdatedState(board)

    // Parejas árbol → tienda del tablero actual. Se recalculan por jugada, no por frame.
    val pairs = remember(board) { TentsMatching.pairs(board) }
    val tentOrder = remember(board) { board.cells.indices.filter { board.cells[it].type == TentsCellType.TENT } }

    Canvas(
        modifier = modifier.pointerInput(n, level) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cellSize = size.width.toFloat() / n
                fun cellOf(position: Offset) =
                    floor(position.x / cellSize).toInt() to floor(position.y / cellSize).toInt()

                var last = cellOf(down.position)
                val startType = currentBoard.typeAt(last.first, last.second)
                down.consume()
                // Sobre un árbol no hay jugada, pero el gesto se sigue consumiendo hasta soltar
                // para que no "resbale" a la casilla vecina como si fuera un toque nuevo.
                if (startType != null && startType != TentsCellType.TREE) {
                    currentOnIntent(TentsIntent.CycleCell(last.first, last.second))
                }
                // Vacío → Pasto es el único arranque que se prolonga al arrastrar.
                val sowing = startType == TentsCellType.EMPTY

                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val cell = cellOf(change.position)
                    if (cell != last) {
                        last = cell
                        if (sowing && currentBoard.typeAt(cell.first, cell.second) == TentsCellType.EMPTY) {
                            currentOnIntent(TentsIntent.SetCellState(cell.first, cell.second, TentsCellType.GRASS))
                        }
                    }
                    change.consume()
                }
            }
        },
    ) {
        val now = clock.seconds
        val dt = (now - memory.lastFrameAt).coerceIn(0f, 0.1f)
        memory.lastFrameAt = now
        val cell = size.width / n
        val entered = now - memory.createdAt
        val blinkAlpha = blink()
        val solved = ((now - memory.solvedAt) / 0.45f).coerceIn(0f, 1f)
        val plateCorner = 24.dp

        fun centerOf(index: Int) = Offset((index % n + 0.5f) * cell, (index / n + 0.5f) * cell)

        /** Escala de brote 0,4→1 (con rebote) de la marca de la casilla [index]. */
        fun popOf(index: Int): Float {
            val p = ((now - memory.changedAt[index]) / POP_SEC).coerceIn(0f, 1f)
            return 0.4f + 0.6f * EaseOutBack.transform(p)
        }

        // 1) Placa: discreta mientras se juega, se enciende en verde al resolver.
        drawNeonBoardPlate(accent = Accent, lit = solved, litColor = LogicColors.NeonGreen, corner = plateCorner)

        // 2) Baldosas, con entrada en cascada por diagonales. La de una tienda se llena de luz
        //    y la de un árbol queda teñida: el tablero se lee como piezas, no como una rejilla.
        board.cells.forEachIndexed { index, item ->
            val cascade = boardCascade(item.y, item.x, entered)
            val (fill, color) = when (item.type) {
                TentsCellType.TENT -> popOf(index) to if (item.hasConflict) {
                    LogicColors.Error
                } else {
                    lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, solved)
                }
                TentsCellType.TREE -> (0.30f + 0.45f * memory.treeGlow[index]) to LogicColors.NeonGreen
                TentsCellType.EMPTY, TentsCellType.GRASS -> 0f to LogicColors.NeonCyan
            }
            drawBoardSocket(centerOf(index), cell * SOCKET_FRACTION * cascade, fill, color, padAlpha = 0f)
        }

        // 3) Barrido de luz por la fila/columna que acaba de cuadrar su contador: lleva la vista
        //    del tablero al número que se ha encendido. Recortado a la placa para no desbordarla.
        val plate = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(plateCorner.toPx())))
        }
        clipPath(plate) {
            for (i in 0 until n) {
                drawLineSweep(i, cell, horizontal = true, progress = (now - memory.rowExactAt[i]) / SWEEP_SEC)
                drawLineSweep(i, cell, horizontal = false, progress = (now - memory.columnExactAt[i]) / SWEEP_SEC)
            }
        }

        // 4) Cuerdas de luz: cada tienda queda atada al árbol que le corresponde. Hace visible
        //    la regla "una tienda por árbol", la única que no se ve mirando casillas sueltas.
        pairs.forEach { (tree, tent) ->
            val from = centerOf(tree)
            val to = centerOf(tent)
            val grow = ((now - memory.changedAt[tent]) / POP_SEC).coerceIn(0f, 1f)
            // Solo el tramo entre las dos figuras: de un 30 % a un 70 % del camino.
            val start = lerpOffset(from, to, 0.30f)
            val end = lerpOffset(from, to, 0.30f + 0.40f * grow)
            val rope = Path().apply { moveTo(start.x, start.y); lineTo(end.x, end.y) }
            drawNeonWire(
                path = rope,
                color = lerp(LogicColors.NeonGreen, LogicColors.NeonCyan, 0.5f * (1f - solved)),
                strokeWidth = cell * 0.045f,
                glow = 0.9f,
                core = 0.25f,
                flowPhase = now,
            )
        }

        // 5) Contenido de cada casilla.
        board.cells.forEachIndexed { index, item ->
            val center = centerOf(index)
            val cascade = boardCascade(item.y, item.x, entered)
            when (item.type) {
                TentsCellType.EMPTY -> Unit

                TentsCellType.TREE -> {
                    // El encendido persigue a su objetivo con suavizado exponencial: el árbol
                    // no "salta" de apagado a encendido cuando recibe (o pierde) su tienda.
                    val target = if (index in pairs) 1f else 0f
                    val glow = memory.treeGlow[index] + (target - memory.treeGlow[index]) * (dt * 9f).coerceAtMost(1f)
                    memory.treeGlow[index] = glow
                    // En reposo respira muy despacio; cada árbol con su propia fase.
                    val breath = 0.06f * sin(now * 1.3f + hash01(index) * 6.28f)
                    drawNeonPine(
                        center = center,
                        side = cell,
                        color = LogicColors.NeonGreen,
                        glow = (0.28f + breath + 0.72f * glow).coerceIn(0f, 1f),
                        sway = 2.4f * sin(now * 1.05f + hash01(index * 5 + 1) * 6.28f),
                        scale = cascade,
                    )
                }

                TentsCellType.GRASS -> drawNeonGrass(
                    center = center,
                    side = cell,
                    color = lerp(LogicColors.OnDarkMuted, LogicColors.NeonGreen, 0.22f),
                    alpha = 0.78f,
                    sway = 0.035f * sin(now * 1.6f + hash01(index * 3) * 6.28f),
                    scale = popOf(index) * cascade,
                )

                TentsCellType.TENT -> {
                    val pop = popOf(index)
                    val settle = ((now - memory.changedAt[index]) / 0.5f).coerceIn(0f, 1f)
                    // Una tienda mal puesta se sacude al caer, además de parpadear en rojo.
                    val wobble = if (item.hasConflict) 7f * (1f - settle) * sin(now * 38f) else 0f
                    rotate(wobble, pivot = center) {
                        drawNeonTent(
                            center = center,
                            side = cell,
                            color = if (item.hasConflict) {
                                LogicColors.Error
                            } else {
                                lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, solved)
                            },
                            // Destello de entrada: nace a tope y se asienta mientras brota.
                            glow = (0.55f + 0.45f * (1f - settle) + 0.45f * solved).coerceIn(0f, 1f),
                            alpha = if (item.hasConflict) blinkAlpha else 1f,
                            scale = pop * cascade,
                            // La lámpara titila con dos ondas de periodos distintos para que no
                            // se note el bucle; una tienda en error se queda a oscuras.
                            ember = if (item.hasConflict) {
                                0f
                            } else {
                                0.72f + 0.16f * sin(now * 5.3f + index) + 0.10f * sin(now * 11.7f + index * 2.3f)
                            },
                            emberColor = LogicColors.Amber,
                        )
                    }
                }
            }
        }

        // 6) Reacciones, por encima de todo: onda del toque y chispas.
        board.cells.forEachIndexed { index, item ->
            val since = now - memory.changedAt[index]
            if (since < 0f || since > BURST_SEC) return@forEachIndexed
            val center = centerOf(index)
            val ripple = since / POP_SEC
            if (ripple < 1f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f * (1f - ripple)),
                    radius = cell * (0.18f + 0.34f * ripple),
                    center = center,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            if (item.type == TentsCellType.TENT) {
                drawSparkBurst(
                    center = center,
                    color = if (item.hasConflict) LogicColors.Error else LogicColors.NeonCyan,
                    reach = cell * 0.72f,
                    progress = since / BURST_SEC,
                    seed = index,
                )
            }
        }
        // Victoria: las tiendas estallan una tras otra, en orden de lectura.
        if (memory.solvedAt != NEVER) {
            tentOrder.forEachIndexed { order, index ->
                drawSparkBurst(
                    center = centerOf(index),
                    color = LogicColors.NeonGreen,
                    reach = cell * 0.95f,
                    progress = (now - memory.solvedAt - order * VICTORY_STAGGER_SEC) / 0.6f,
                    seed = index + 97,
                )
            }
        }
    }
}

/** Punto a la fracción [t] del segmento [a]→[b]. */
private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * Barrido de luz que recorre la fila o columna [line] de punta a punta: una banda verde con los
 * bordes difuminados que avanza y se apaga.
 *
 * @param progress 0..1; fuera de ese rango no dibuja nada (animación sin empezar o ya acabada).
 */
private fun DrawScope.drawLineSweep(line: Int, cell: Float, horizontal: Boolean, progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val fade = 1f - progress
    val band = cell * 1.8f
    val length = if (horizontal) size.width else size.height
    // La banda entra desde fuera y sale por el otro lado.
    val head = -band + (length + band) * progress
    val colors = listOf(
        Color.Transparent,
        LogicColors.NeonGreen.copy(alpha = 0.42f * fade),
        Color.Transparent,
    )
    val inset = cell * (1f - SOCKET_FRACTION) / 2f
    if (horizontal) {
        drawRect(
            brush = Brush.horizontalGradient(colors, startX = head, endX = head + band),
            topLeft = Offset(head, line * cell + inset),
            size = Size(band, cell - 2 * inset),
        )
    } else {
        drawRect(
            brush = Brush.verticalGradient(colors, startY = head, endY = head + band),
            topLeft = Offset(line * cell + inset, head),
            size = Size(cell - 2 * inset, band),
        )
    }
}
