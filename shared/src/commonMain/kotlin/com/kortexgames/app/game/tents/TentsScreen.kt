package com.kortexgames.app.game.tents

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Park
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
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonPine
import com.kortexgames.app.ui.components.drawNeonTent
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.tents_board_generating
import kortexgames.shared.generated.resources.tents_counter_column_description
import kortexgames.shared.generated.resources.tents_counter_row_description
import kortexgames.shared.generated.resources.tents_gameover_headline
import kortexgames.shared.generated.resources.tents_hud_progress
import kortexgames.shared.generated.resources.tents_intro_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor

// --- Constantes de composición del tablero ------------------------------------

/** Nombre del juego: contenido de catálogo (§10), no texto de UI traducible. */
private const val GAME_TITLE = "Neon Trees & Tents"

/**
 * Grosor de la franja de contadores como fracción de la casilla. Más estrecha que una casilla
 * entera: los números no necesitan tanto sitio y ese espacio vale más repartido en el tablero.
 */
private const val HEADER_FRACTION = 0.7f

/** Tamaño del número de un contador como fracción de la casilla: legible también en 8×8. */
private const val COUNTER_FONT_FRACTION = 0.40f

/** Opacidad mínima del parpadeo de error: "suave" = nunca llega a apagarse del todo. */
private const val ERROR_BLINK_MIN = 0.45f

/** Escala desde la que "brota" una marca recién puesta antes de asentarse con resorte. */
private const val POP_FROM = 0.55f

/** Acento de la categoría (Pensamiento Lógico): cromo del HUD, la placa y la antesala. */
private val Accent = CategoryPalette.Logic

/**
 * Pantalla de "Neon Trees & Tents".
 *
 * Estructura idéntica a los demás juegos por niveles: antesala con selector ([GameIntroScreen]) →
 * tablero → [GameOverOverlay], con [GamePauseControls] encima.
 *
 * La pantalla no decide nada: convierte el dedo en casillas, emite [TentsIntent] y pinta el
 * [TentsUiState]. Los [TentsEffect] se vuelven sonido y vibración en un único `collect`.
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

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
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
                    TentsField(state = state, cell = cell, onIntent = vm::onIntent)
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
 * Tablero con sus dos franjas de contadores: columnas arriba, filas a la izquierda.
 *
 * Los contadores son composables (uno por fila/columna) y no parte del Canvas porque cada uno
 * anima su propio color: así al plantar una tienda solo se recomponen los dos que cambian. El
 * parpadeo de error se comparte desde aquí para que todos los avisos latan a la vez — con una
 * transición por elemento irían desfasados y la pantalla parecería estropeada.
 */
@Composable
private fun TentsField(state: TentsUiState, cell: Dp, onIntent: (TentsIntent) -> Unit) {
    val blink by rememberInfiniteTransition(label = "tentsError").animateFloat(
        initialValue = 1f,
        targetValue = ERROR_BLINK_MIN,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tentsErrorBlink",
    )
    val header = cell * HEADER_FRACTION
    val n = state.gridSize

    Column {
        Row {
            Spacer(Modifier.width(header))
            for (x in 0 until n) {
                TentsCounter(
                    target = state.columnTargets[x],
                    status = state.columnStatus(x),
                    blink = { blink },
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
                        status = state.rowStatus(y),
                        blink = { blink },
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
                isSolved = state.isSolved,
                blink = { blink },
                onIntent = onIntent,
                modifier = Modifier.size(cell * n),
            )
        }
    }
}

/**
 * Contador de borde: el número de tiendas que pide una fila o columna.
 *
 * Tres lecturas (ver [TentsLineStatus]): atenuado mientras falta, verde al clavarlo —con un
 * pequeño rebote, es el "bien hecho" del juego— y rojo parpadeante al pasarse.
 *
 * @param blink opacidad del parpadeo de error, como lambda para leerla en la fase de dibujo
 *   (`graphicsLayer`) y no recomponer el texto en cada frame de la animación.
 * @param description lectura de accesibilidad: el número solo no dice ni a qué línea pertenece
 *   ni cuántas tiendas lleva ya.
 */
@Composable
private fun TentsCounter(
    target: Int,
    status: TentsLineStatus,
    blink: () -> Float,
    cell: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = when (status) {
            TentsLineStatus.UNDER -> LogicColors.OnDarkMuted
            TentsLineStatus.EXACT -> LogicColors.NeonGreen
            TentsLineStatus.OVER -> LogicColors.Error
        },
        animationSpec = tween(180),
        label = "tentsCounterColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (status == TentsLineStatus.EXACT) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tentsCounterScale",
    )
    val fontSize = with(LocalDensity.current) { (cell * COUNTER_FONT_FRACTION).toSp() }
    Box(modifier = modifier.clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(
            text = target.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize),
            fontWeight = FontWeight.Black,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (status == TentsLineStatus.OVER) blink() else 1f
            },
        )
    }
}

/**
 * La rejilla: dibujo + captura del gesto, en **un solo [Canvas]**.
 *
 * **Gesto.** `awaitEachGesture` a mano (como en Shikaku) porque hacen falta dos cosas que
 * `detectTapGestures` no da juntas: respuesta en el mismo frame del contacto, sin esperar a que
 * se levante el dedo, y continuar el toque como arrastre. El primer contacto cicla la casilla; si
 * con eso quedó en pasto, seguir arrastrando **siembra pasto** en las casillas vacías por las que
 * pasa el dedo. Es el "modo rápido" del juego sin botón de modo: descartar una fila entera es la
 * jugada más repetida. El arrastre solo pisa casillas vacías a propósito — nunca borra una tienda
 * ni un pasto ya puesto, así que un dedo que se desvía no destruye trabajo ni cuesta puntos.
 *
 * **Neón.** Árboles y tiendas usan [drawNeonPine] / [drawNeonTent], que replican la receta de
 * capas de `drawNeonTile` (§9.7). El resplandor sale de esos halos y no de `Modifier.softGlow`:
 * ese modificador es una sombra de elevación sobre un composable, y aquí las figuras son trazos
 * dentro de un único Canvas. La placa es la compartida [drawNeonBoardPlate], discreta, que solo
 * se enciende al resolver.
 *
 * @param level clave del detector de gestos: al cambiar de nivel se descarta cualquier gesto a
 *   medias del tablero anterior.
 * @param blink opacidad del parpadeo de error; se lee solo al dibujar.
 */
@Composable
private fun TentsBoardCanvas(
    board: TentsBoard,
    level: Int,
    isSolved: Boolean,
    blink: () -> Float,
    onIntent: (TentsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = board.size
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentBoard by rememberUpdatedState(board)

    // Última casilla tocada y su "pop" de entrada. Es estado LOCAL de la UI: al dominio le da
    // igual cuál fue la última, solo sirve para animar la marca recién puesta.
    var poppedCell by remember { mutableIntStateOf(-1) }
    var popTick by remember { mutableIntStateOf(0) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(popTick) {
        if (popTick > 0) {
            pop.snapTo(POP_FROM)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    // Celebración: la placa se enciende y las tiendas viran a verde al resolver.
    val solved by animateFloatAsState(
        targetValue = if (isSolved) 1f else 0f,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "tentsSolved",
    )

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
                    poppedCell = last.second * n + last.first
                    popTick++
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
                            poppedCell = cell.second * n + cell.first
                            popTick++
                            currentOnIntent(TentsIntent.SetCellState(cell.first, cell.second, TentsCellType.GRASS))
                        }
                    }
                    change.consume()
                }
            }
        },
    ) {
        val cell = size.width / n
        val blinkAlpha = blink()

        // 1) Placa y rejilla. Solo las líneas interiores: el marco ya lo pone la placa.
        drawNeonBoardPlate(accent = Accent, lit = solved, litColor = LogicColors.NeonGreen)
        val line = 1.dp.toPx()
        val inset = cell * 0.12f
        for (i in 1 until n) {
            val at = i * cell
            drawLine(LogicColors.SurfaceVariantDark, Offset(at, inset), Offset(at, size.height - inset), line)
            drawLine(LogicColors.SurfaceVariantDark, Offset(inset, at), Offset(size.width - inset, at), line)
        }

        // 2) Contenido de cada casilla.
        board.cells.forEachIndexed { index, item ->
            val center = Offset((item.x + 0.5f) * cell, (item.y + 0.5f) * cell)
            val scale = if (index == poppedCell) pop.value else 1f
            when (item.type) {
                TentsCellType.EMPTY -> Unit

                TentsCellType.TREE -> drawNeonPine(center, cell, LogicColors.NeonGreen)

                // Punto atenuado: tiene que leerse como "descartado" sin competir con nada.
                TentsCellType.GRASS -> drawCircle(
                    color = LogicColors.OnDarkMuted.copy(alpha = 0.7f),
                    radius = cell * 0.07f * scale,
                    center = center,
                )

                TentsCellType.TENT -> drawNeonTent(
                    center = center,
                    side = cell,
                    color = if (item.hasConflict) {
                        LogicColors.Error
                    } else {
                        lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, solved)
                    },
                    // El destello de entrada sale del mismo resorte que la escala: cuanto más
                    // lejos está de su tamaño final, más encendido el tubo.
                    glow = (0.6f + 0.4f * solved + abs(1f - scale)).coerceIn(0f, 1f),
                    alpha = if (item.hasConflict) blinkAlpha else 1f,
                    scale = scale,
                )
            }
        }
    }
}
