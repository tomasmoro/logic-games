package com.kortexgames.app.game.shikaku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
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
import com.kortexgames.app.ui.components.BoardClock
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonBoardHud
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawBoardSocket
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonTile
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.shikaku_badge_area
import kortexgames.shared.generated.resources.shikaku_badge_multiple
import kortexgames.shared.generated.resources.shikaku_badge_out_of_mask
import kortexgames.shared.generated.resources.shikaku_badge_target
import kortexgames.shared.generated.resources.shikaku_board_generating
import kortexgames.shared.generated.resources.shikaku_gameover_headline
import kortexgames.shared.generated.resources.shikaku_intro_description
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

// --- Constantes de composición del tablero ------------------------------------

/** Nombre del juego: contenido de catálogo (§10), no texto de UI traducible. */
private const val GAME_TITLE = "Neon Shikaku Matrix"

/** Tamaño de la pista como fracción de la celda: legible en 12×12 sin tocar los bordes. */
internal const val CLUE_FONT_FRACTION = 0.44f

/** Encendido en reposo del tubo de un rectángulo sellado (0 = apagado, 1 = núcleo blanco). */
internal const val SEALED_GLOW = 0.45f

/** Opacidad mínima del parpadeo de error: "sutil" = nunca llega a apagarse del todo. */
private const val ERROR_BLINK_MIN = 0.45f

/** Periodo del parpadeo de un rectángulo inválido (s). */
private const val ERROR_BLINK_SEC = 1.4f

/** Marco de la placa alrededor de la rejilla. */
private val PlatePadding = 8.dp

/** Separación entre una celda y el borde de su casilla. */
private const val CELL_GAP_DP = 1.5f

// --- Encendido de un rectángulo correcto ---------------------------------------

/** Retraso del encendido entre una celda y la siguiente, por paso de distancia a la pista (s). */
internal const val LIGHT_STEP_SEC = 0.045f

/** Lo que tarda una celda en encenderse con su rebote (s). */
internal const val LIGHT_POP_SEC = 0.30f

/** Lo que tarda el tubo del rectángulo en bajar de pleno al reposo tras sellarse (s). */
internal const val SEAL_FLASH_SEC = 0.75f

/** Duración del "pop" con que la pista se convierte en ficha (s). */
private const val CHIP_POP_SEC = 0.38f

/** Duración de la ráfaga de chispas de la pista resuelta (s). */
internal const val SEAL_SPARKS_SEC = 0.55f

/** Radio de la ficha de una pista, en lados de celda. */
private const val CHIP_RADIUS = 0.34f

// --- Brillo de ambiente sobre lo resuelto --------------------------------------

/** Cada cuánto cruza el tablero el brillo (s). Lento: ambiente, no reclamo (§9.4). */
private const val SHEEN_PERIOD_SEC = 4.5f

/** Lo que tarda el brillo en cruzar de esquina a esquina (s). */
private const val SHEEN_TRAVEL_SEC = 1.1f

/** Anchura de la banda de brillo, en fracción de la diagonal del tablero. */
private const val SHEEN_WIDTH = 0.14f

/** Opacidad máxima del brillo sobre una celda. */
private const val SHEEN_ALPHA = 0.16f

// --- Celebración de nivel resuelto ----------------------------------------------

/** Retraso de la onda final entre una celda y la siguiente, por paso de distancia al centro (s). */
private const val SOLVE_WAVE_STEP_SEC = 0.03f

/** Duración del destello de cada celda en la onda final (s). */
private const val SOLVE_WAVE_CELL_SEC = 0.45f

/** Opacidad del destello blanco de cada celda en la onda final. Contenido: a más, las celdas
 *  se lavan a un rosa/celeste pálido y se pierde el color de cada rectángulo. */
private const val SOLVE_WAVE_ALPHA = 0.30f

/** Baño extra del color del rectángulo sobre una celda encendida. */
private const val SOLVED_TINT_ALPHA = 0.14f

/** Desfase entre las ráfagas de chispas de pistas consecutivas en la celebración (s). */
private const val SOLVE_SPARK_STAGGER_SEC = 0.05f

/**
 * Cuánto se retiene el diálogo de resultado tras resolver (ms): el tiempo de ver encenderse el
 * último rectángulo y la onda final. Sin esta espera el diálogo taparía el tablero en el mismo
 * instante de soltar el dedo y la jugada que gana el nivel sería la única que no se ve.
 */
private const val SOLVE_HOLD_MS = 1_150L

/** Único punto donde un tinte de dominio se vuelve un color de `LogicColors`. */
internal fun ShikakuTint.toColor(): Color = when (this) {
    ShikakuTint.CYAN -> LogicColors.NeonCyan
    ShikakuTint.MAGENTA -> LogicColors.Magenta
    ShikakuTint.GREEN -> LogicColors.NeonGreen
}

/**
 * Color de la selección en curso. Tres lecturas, no dos: verde "esto encaja", rojo "esto es
 * imposible" (pisa un agujero o encierra dos pistas) y ámbar/cian para lo que simplemente aún no
 * está terminado. Pintar de rojo un arrastre a medio camino castigaría el gesto antes de acabarlo.
 */
internal fun ShikakuRectValidity.toSelectionColor(): Color = when (this) {
    ShikakuRectValidity.VALID -> LogicColors.NeonGreen
    ShikakuRectValidity.WRONG_AREA -> LogicColors.Amber
    ShikakuRectValidity.NO_NUMBER -> LogicColors.NeonCyan
    ShikakuRectValidity.MULTIPLE_NUMBERS, ShikakuRectValidity.OUT_OF_MASK -> LogicColors.Error
}

/**
 * Pantalla de "Neon Shikaku Matrix".
 *
 * Estructura idéntica a los demás juegos por niveles: antesala con selector ([GameIntroScreen]) →
 * tablero → [GameOverOverlay], con [GamePauseControls] encima.
 *
 * La pantalla no decide nada: convierte el dedo en celdas, emite [ShikakuIntent] y pinta el
 * [ShikakuUiState]. Los [ShikakuEffect] se vuelven sonido y vibración en un único `collect`.
 *
 * Todo el tablero es **un solo [Canvas]** (celdas, rectángulos, selección y números): los
 * rectángulos abarcan varias celdas y cambian en cada paso del arrastre, así que un composable por
 * celda obligaría a recomponer decenas de nodos por gesto; aquí un cambio de selección solo
 * invalida el dibujo.
 *
 * @param graph grafo de dependencias (repositorios, audio/háptica, anuncios).
 * @param onExit vuelve a la pantalla anterior.
 */
@Composable
fun ShikakuScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: ShikakuViewModel = viewModel {
        ShikakuViewModel(graph.progressRepository, graph.playerProgressRepository, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is ShikakuEffect.PlaySound -> graph.audio.playSound(effect.sound.effect)
                is ShikakuEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic.feedback)
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.shikaku,
            tutorial = ShikakuTutorial.tutorial,
            title = GAME_TITLE,
            motif = GameMotif.SHIKAKU_RECTS,
            description = stringResource(Res.string.shikaku_intro_description),
            accent = CategoryPalette.SpatialVision,
            icon = Icons.Rounded.Dashboard,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_SHIKAKU)
                vm.onIntent(ShikakuIntent.PlayLevel(selectedLevel))
            },
            onExit = onExit,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // HUD compartido de los tableros por niveles (el mismo de Línea Neón y Conectores):
            // nivel, barra de cobertura y reinicio. El título ya está en la antesala y la pausa.
            NeonBoardHud(
                level = state.level,
                progress = if (state.validation.totalCells == 0) 0f else {
                    state.validation.coveredCells.toFloat() / state.validation.totalCells
                },
                progressLabel = "${state.validation.coveredCells}/${state.validation.totalCells}",
                accent = CategoryPalette.SpatialVision,
                onRestart = { vm.onIntent(ShikakuIntent.RestartLevel) },
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isGenerating || state.mask.columns == 0) {
                    Text(
                        text = stringResource(Res.string.shikaku_board_generating),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LogicColors.OnDarkMuted,
                    )
                } else {
                    // Celda cuadrada: la mayor que quepa en ambos ejes. La caja del tablero mide
                    // exactamente columnas × filas celdas, así px→celda es una división sin márgenes.
                    val cell = minOf(maxWidth / state.mask.columns, maxHeight / state.mask.rows)
                    ShikakuBoard(
                        state = state,
                        onIntent = vm::onIntent,
                        modifier = Modifier.size(cell * state.mask.columns, cell * state.mask.rows),
                    )
                }
            }
        }

        val gameOver = state.gameOver
        // El resultado espera a que termine la celebración del tablero (ver SOLVE_HOLD_MS).
        var resultReady by remember { mutableStateOf(false) }
        LaunchedEffect(gameOver != null) {
            resultReady = false
            if (gameOver != null) {
                delay(SOLVE_HOLD_MS)
                resultReady = true
            }
        }
        if (state.status == GameStatus.FINISHED && gameOver != null && resultReady) {
            GameOverOverlay(
                info = gameOver,
                audio = graph.audio,
                headline = stringResource(Res.string.shikaku_gameover_headline),
                onPlayAgain = { vm.onIntent(ShikakuIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(ShikakuIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(ShikakuIntent.ChooseLevel) },
                accent = CategoryPalette.SpatialVision,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(ShikakuIntent.Pause) },
            onResume = { vm.onIntent(ShikakuIntent.Resume) },
            onExit = onExit,
            gameTitle = GAME_TITLE,
            help = GameHelpContent.shikaku,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/**
 * El tablero: dibujo + captura del gesto + badge flotante.
 *
 * **Gesto.** Se usa `awaitEachGesture` a mano en vez de `detectDragGestures` porque este último
 * espera a superar el *touch slop* antes de avisar: los primeros milímetros del arrastre se
 * perderían y el ancla podría caer en la celda vecina. Aquí el ancla es la celda EXACTA del
 * primer contacto y cada cambio de celda se emite en el mismo frame. Solo se emite al cambiar de
 * celda (no por píxel), que es el contrato de [ShikakuIntent.UpdateDrag].
 *
 * **Animación.** Un único [BoardClock] mueve todo y se lee solo dentro del dibujo, así que animar
 * no recompone nada. De él cuelgan cuatro momentos:
 *  - la **entrada** del nivel (las celdas aparecen en cascada);
 *  - el **encendido** de un rectángulo correcto: sus celdas se iluminan una a una en onda desde
 *    la pista, la pista se vuelve ficha con un rebote y suelta chispas (ver [drawSolvedCell] y
 *    [drawClue]). Es la recompensa de cada acierto, así que es lo más vistoso del tablero;
 *  - el **brillo** de ambiente que cruza de vez en cuando lo ya resuelto, para que el tablero
 *    siga vivo mientras el jugador piensa;
 *  - la **celebración** al completar el nivel (onda desde el centro, chispas en cada pista y
 *    marco encendido).
 *
 * **Borde neón.** Los marcos reutilizan [drawNeonTile] (§9.7) pasándole la caja de cada
 * rectángulo, y la placa y las celdas salen del kit de tableros ([drawNeonBoardPlate],
 * [drawBoardSocket]), los mismos de Línea Neón.
 */
@Composable
private fun ShikakuBoard(
    state: ShikakuUiState,
    onIntent: (ShikakuIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mask = state.mask
    val currentOnIntent by rememberUpdatedState(onIntent)

    // Posición del dedo en píxeles del tablero: estado LOCAL de la UI. Solo sirve para colocar
    // el badge; el dominio trabaja en celdas y no necesita saberla.
    var finger by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    val clock = rememberBoardClock()
    // "Nivel" para la animación = máscara + pistas: reiniciar el mismo nivel conserva ambas y
    // por eso no repite la entrada (solo se vacían los rectángulos).
    val levelAt = remember(mask, state.clues) { clock.peek() }

    // Rectángulo → instante en que quedó CORRECTO. Se deduce comparando con lo ya anotado en
    // vez de pedirle un efecto al ViewModel: es adorno puro y así el contrato MVI no cambia.
    val validAt = remember(mask, state.clues) { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(state.rectangles) {
        val valid = state.rectangles.filter { it.validity == ShikakuRectValidity.VALID }
        val ids = valid.map { it.id }.toSet()
        validAt.keys.filter { it !in ids }.forEach { validAt.remove(it) }
        valid.forEach { if (it.id !in validAt) validAt[it.id] = clock.peek() }
    }

    // Instante en que se completó el nivel (-1 = aún no).
    var solvedAt by remember(mask, state.clues) { mutableFloatStateOf(-1f) }
    LaunchedEffect(state.validation.isSolved) {
        solvedAt = if (state.validation.isSolved) clock.peek() else -1f
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val clueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
    val cellPx = if (mask.columns > 0) boardSize.width.toFloat() / mask.columns else 0f
    // Los números se miden una vez por nivel y tamaño, no en cada frame de dibujo.
    val clueLayouts = remember(state.clues, cellPx) {
        val fontSize = with(density) { (cellPx * CLUE_FONT_FRACTION).toSp() }
        state.clues.map { measurer.measure(it.number.toString(), clueStyle.copy(fontSize = fontSize)) }
    }
    // Qué rectángulo CORRECTO ocupa cada celda y qué pista encierra: se resuelve una vez por
    // jugada (no por frame) y deja el dibujo en una lectura de tabla por celda.
    val solved = remember(state.rectangles, state.clues, mask) {
        SolvedLookup.of(mask, state.rectangles, state.clues)
    }

    Box(modifier = modifier.onSizeChanged { boardSize = it }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // La clave es la máscara: al cambiar de nivel se reinicia el detector con la
                // nueva rejilla y se descarta cualquier gesto a medias del tablero anterior.
                .pointerInput(mask) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val cellSize = size.width.toFloat() / mask.columns
                        fun cellOf(position: Offset) =
                            floor(position.x / cellSize).toInt() to floor(position.y / cellSize).toInt()

                        var last = cellOf(down.position)
                        finger = down.position
                        currentOnIntent(ShikakuIntent.StartDrag(last.first, last.second))
                        down.consume()

                        var released = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    currentOnIntent(ShikakuIntent.ReleaseDrag)
                                    break
                                }
                                finger = change.position
                                val cell = cellOf(change.position)
                                if (cell != last) {
                                    last = cell
                                    currentOnIntent(ShikakuIntent.UpdateDrag(cell.first, cell.second))
                                }
                                change.consume()
                            }
                        } finally {
                            // Gesto interrumpido (cancelación, puntero perdido): no se sella nada.
                            if (!released) currentOnIntent(ShikakuIntent.CancelDrag)
                        }
                    }
                },
        ) {
            val now = clock.seconds
            val cell = size.width / mask.columns
            val gap = CELL_GAP_DP.dp.toPx()
            val tile = cell - 2 * gap
            val sinceSolved = if (solvedAt >= 0f) now - solvedAt else -1f
            val accent = CategoryPalette.SpatialVision

            // 0) Placa. Asoma PlatePadding por fuera de la rejilla (inset negativo); al
            //    completar el nivel su marco se enciende en verde y se apaga solo.
            val platePad = PlatePadding.toPx()
            inset(-platePad, -platePad, -platePad, -platePad) {
                drawNeonBoardPlate(
                    accent = accent,
                    lit = if (sinceSolved < 0f) 0f else (1f - sinceSolved / 1.4f).coerceIn(0f, 1f),
                    litColor = LogicColors.NeonGreen,
                    corner = 18.dp,
                )
            }

            // Fase del brillo de ambiente: -1 si ahora mismo no está cruzando.
            val sheenCycle = now % SHEEN_PERIOD_SEC
            val sheenHead = if (sheenCycle < SHEEN_TRAVEL_SEC && sinceSolved < 0f) {
                -SHEEN_WIDTH + (sheenCycle / SHEEN_TRAVEL_SEC) * (1f + 2f * SHEEN_WIDTH)
            } else {
                -1f
            }
            val diagonal = (mask.columns + mask.rows).toFloat()
            val midX = (mask.columns - 1) / 2f
            val midY = (mask.rows - 1) / 2f

            // 1) Celdas jugables. Las inhabilitadas no se pintan: son placa desnuda.
            for (y in 0 until mask.rows) {
                for (x in 0 until mask.columns) {
                    if (!mask.isEnabled(x, y)) continue
                    val entry = boardCascade(y, x, now - levelAt)
                    if (entry <= 0f) continue
                    val center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                    val owner = solved.ownerAt(x, y)
                    val stamp = owner?.let { validAt[it.id] }
                    if (owner == null || stamp == null) {
                        drawBoardSocket(center, tile * entry, fill = 0f, color = accent, padAlpha = 0f)
                        continue
                    }
                    // Encendido en onda desde la pista del rectángulo.
                    val local = (now - stamp - solved.distanceAt(x, y) * LIGHT_STEP_SEC) / LIGHT_POP_SEC
                    val sheen = if (sheenHead < 0f || local < 1f) 0f else {
                        val d = abs((x + y + 1) / diagonal - sheenHead)
                        (1f - d / SHEEN_WIDTH).coerceAtLeast(0f)
                    }
                    val wave = if (sinceSolved < 0f) 0f else {
                        val p = (sinceSolved - (abs(x - midX) + abs(y - midY)) * SOLVE_WAVE_STEP_SEC) / SOLVE_WAVE_CELL_SEC
                        if (p <= 0f || p >= 1f) 0f else sin(p * PI.toFloat())
                    }
                    drawSolvedCell(center, tile * entry, owner.tint.toColor(), local, sheen, wave)
                }
            }

            // Relleno translúcido + tubo neón de un rectángulo en coordenadas de celda.
            fun frame(bounds: ShikakuRect, color: Color, glow: Float, alpha: Float, fill: Float) {
                val topLeft = Offset(bounds.left * cell, bounds.top * cell)
                val rectSize = Size(bounds.width * cell, bounds.height * cell)
                if (fill > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = fill * alpha),
                        topLeft = topLeft + Offset(gap, gap),
                        size = Size(rectSize.width - 2 * gap, rectSize.height - 2 * gap),
                        cornerRadius = CornerRadius(cell * 0.18f),
                    )
                }
                drawNeonTile(
                    baseColor = color,
                    activeAmt = glow.coerceIn(0f, 1f),
                    cornerRadius = 10.dp,
                    sparks = false,
                    baseMargin = 3.dp,
                    strokeScale = 0.7f,
                    rectTopLeft = topLeft,
                    rectSize = rectSize,
                    alpha = alpha,
                )
            }

            // 2) Rectángulos sellados. El correcto NO lleva relleno propio: ya lo dan sus celdas
            //    encendidas; el tubo nace a pleno y baja al reposo. El inválido parpadea en rojo.
            val blink = ERROR_BLINK_MIN + (1f - ERROR_BLINK_MIN) *
                (0.5f + 0.5f * sin(now * 2f * PI.toFloat() / ERROR_BLINK_SEC))
            for (rectangle in state.rectangles) {
                val stamp = validAt[rectangle.id]
                if (rectangle.validity == ShikakuRectValidity.VALID && stamp != null) {
                    val flash = (1f - (now - stamp) / SEAL_FLASH_SEC).coerceIn(0f, 1f)
                    frame(
                        bounds = rectangle.bounds,
                        color = rectangle.tint.toColor(),
                        glow = SEALED_GLOW + (1f - SEALED_GLOW) * flash * flash,
                        alpha = 1f,
                        fill = 0f,
                    )
                } else if (rectangle.validity != ShikakuRectValidity.VALID) {
                    frame(rectangle.bounds, LogicColors.Error, glow = SEALED_GLOW, alpha = blink, fill = 0.14f)
                }
            }

            // 3) Selección en curso, por encima de lo sellado (lo va a reemplazar al soltar).
            //    Cuando encaja, late: el tablero avisa de que ya se puede soltar.
            val selection = state.selection
            if (selection != null) {
                val ready = selection.validity == ShikakuRectValidity.VALID
                val pulse = if (ready) 0.5f + 0.5f * sin(now * 9f) else 0f
                frame(
                    bounds = selection.bounds,
                    color = selection.validity.toSelectionColor(),
                    glow = if (ready) 0.78f + 0.22f * pulse else 0.7f,
                    alpha = 1f,
                    fill = 0.16f + 0.08f * pulse,
                )
            }

            // 4) Chispas de los rectángulos recién resueltos y de la celebración final. Van
            //    bajo los números para no tapar la cifra en el momento en que cambia.
            state.rectangles.forEach { rectangle ->
                val stamp = validAt[rectangle.id] ?: return@forEach
                val clue = solved.clueOf(rectangle.id) ?: return@forEach
                drawSparkBurst(
                    center = Offset((clue.x + 0.5f) * cell, (clue.y + 0.5f) * cell),
                    color = rectangle.tint.toColor(),
                    reach = cell * 1.15f,
                    progress = (now - stamp) / SEAL_SPARKS_SEC,
                    seed = rectangle.id,
                )
            }
            if (sinceSolved >= 0f) {
                state.clues.forEachIndexed { i, clue ->
                    drawSparkBurst(
                        center = Offset((clue.x + 0.5f) * cell, (clue.y + 0.5f) * cell),
                        color = solved.ownerAt(clue.x, clue.y)?.tint?.toColor() ?: accent,
                        reach = cell * 1.3f,
                        progress = (sinceSolved - 0.15f - i * SOLVE_SPARK_STAGGER_SEC) / SEAL_SPARKS_SEC,
                        seed = 97 + i,
                    )
                }
            }

            // 5) Pistas, al final para que nada las tape.
            state.clues.forEachIndexed { i, clue ->
                val layout = clueLayouts.getOrNull(i) ?: return@forEachIndexed
                val entry = boardCascade(clue.y, clue.x, now - levelAt)
                if (entry <= 0f) return@forEachIndexed
                val owner = solved.ownerAt(clue.x, clue.y)
                val stamp = owner?.let { validAt[it.id] }
                drawClue(
                    layout = layout,
                    center = Offset((clue.x + 0.5f) * cell, (clue.y + 0.5f) * cell),
                    cell = cell,
                    entry = entry,
                    solvedColor = if (stamp != null) owner.tint.toColor() else null,
                    sinceSolved = if (stamp != null) now - stamp else 0f,
                    // La pista que la selección está a punto de resolver se adelanta y crece.
                    armed = selection != null && selection.validity == ShikakuRectValidity.VALID &&
                        selection.bounds.contains(clue.x, clue.y),
                )
            }
        }

        DragBadge(selection = state.selection, finger = finger, boardSize = boardSize)
    }
}

/**
 * Tabla "celda → rectángulo correcto que la ocupa", con la pista de cada uno y la distancia de
 * cada celda a ella. Se construye una vez por jugada para que el dibujo, que corre por frame, no
 * tenga que buscar en la lista de rectángulos celda a celda.
 */
private class SolvedLookup(
    private val columns: Int,
    private val owners: Array<ShikakuRectangle?>,
    private val distances: IntArray,
    private val clues: Map<Int, ShikakuCell>,
) {
    /** Rectángulo correcto que cubre la celda, o `null` si está libre o su rectángulo es inválido. */
    fun ownerAt(x: Int, y: Int): ShikakuRectangle? = owners.getOrNull(y * columns + x)

    /** Pasos (en celdas) desde la celda hasta la pista de su rectángulo: ordena la onda de encendido. */
    fun distanceAt(x: Int, y: Int): Int = distances.getOrElse(y * columns + x) { 0 }

    /** Pista que encierra el rectángulo [id], si es correcto. */
    fun clueOf(id: Int): ShikakuCell? = clues[id]

    companion object {
        fun of(mask: BoardMask, rectangles: List<ShikakuRectangle>, clues: List<ShikakuCell>): SolvedLookup {
            val owners = arrayOfNulls<ShikakuRectangle>(mask.columns * mask.rows)
            val distances = IntArray(mask.columns * mask.rows)
            val clueById = HashMap<Int, ShikakuCell>()
            for (rectangle in rectangles) {
                if (rectangle.validity != ShikakuRectValidity.VALID) continue
                val bounds = rectangle.bounds
                // Un rectángulo correcto encierra exactamente una pista (es parte de ser válido).
                val clue = clues.firstOrNull { it in bounds } ?: continue
                clueById[rectangle.id] = clue
                for (y in bounds.top until bounds.bottom) {
                    for (x in bounds.left until bounds.right) {
                        if (x !in 0 until mask.columns || y !in 0 until mask.rows) continue
                        owners[y * mask.columns + x] = rectangle
                        distances[y * mask.columns + x] = abs(x - clue.x) + abs(y - clue.y)
                    }
                }
            }
            return SolvedLookup(mask.columns, owners, distances, clueById)
        }
    }
}

/**
 * Una celda de un rectángulo **correcto**: la baldosa del kit ([drawBoardSocket]) llenándose de
 * la luz del rectángulo, con tres capas de animación encima.
 *
 * @param side lado de la baldosa en píxeles (ya con la escala de entrada del nivel).
 * @param local avance del encendido de ESTA celda: ≤0 aún no le ha llegado la onda, 0..1 se está
 *   encendiendo (con rebote y un destello blanco que se apaga), ≥1 encendida.
 * @param sheen 0..1: cuánto la toca ahora el brillo de ambiente.
 * @param wave 0..1: destello de la onda de nivel completado.
 */
internal fun DrawScope.drawSolvedCell(
    center: Offset,
    side: Float,
    color: Color,
    local: Float,
    sheen: Float,
    wave: Float,
) {
    val fill = when {
        local <= 0f -> 0f
        local >= 1f -> 1f
        else -> EaseOutBack.transform(local)
    }
    // La onda final "levanta" un poco la celda además de iluminarla.
    drawBoardSocket(center, side * (1f + 0.06f * wave), fill = fill, color = color, padAlpha = 0f)

    val overlay = when {
        local in 0f..1f -> 0.55f * (1f - local)
        else -> 0f
    } + SHEEN_ALPHA * sheen + SOLVE_WAVE_ALPHA * wave
    val lit = side * fill.coerceAtMost(1.08f) * (1f + 0.06f * wave)
    // Baño extra del color sobre la baldosa del kit: aquí la celda encendida ES la recompensa
    // (en Línea Neón encima va un cable brillante; en Shikaku no hay nada más), así que se
    // sube un punto su luz para que lo resuelto destaque sobre lo pendiente.
    if (fill > 0f) {
        drawRoundRect(
            color = color.copy(alpha = SOLVED_TINT_ALPHA * fill.coerceAtMost(1f)),
            topLeft = Offset(center.x - lit / 2f, center.y - lit / 2f),
            size = Size(lit, lit),
            cornerRadius = CornerRadius(side * 0.22f),
        )
    }
    if (overlay > 0f) {
        drawRoundRect(
            color = Color.White.copy(alpha = overlay.coerceAtMost(0.8f)),
            topLeft = Offset(center.x - lit / 2f, center.y - lit / 2f),
            size = Size(lit, lit),
            cornerRadius = CornerRadius(side * 0.22f),
        )
    }
}

/**
 * Una pista del tablero.
 *
 * Sin resolver es un número claro dentro de un aro fino: los aros marcan de un vistazo dónde
 * están las "anclas" del nivel. Al resolverse se convierte en una **ficha** maciza del color de
 * su rectángulo, con el número en oscuro, y entra con un rebote — así se distingue lo resuelto de
 * lo pendiente por forma y no solo por color.
 *
 * @param entry escala de la entrada en cascada del nivel.
 * @param solvedColor color del rectángulo que la resuelve, o `null` si sigue pendiente.
 * @param sinceSolved segundos desde que se resolvió (solo si [solvedColor] no es `null`).
 * @param armed la selección en curso la resolvería al soltar: se pinta en verde y crece.
 */
internal fun DrawScope.drawClue(
    layout: TextLayoutResult,
    center: Offset,
    cell: Float,
    entry: Float,
    solvedColor: Color?,
    sinceSolved: Float,
    armed: Boolean,
) {
    val radius = cell * CHIP_RADIUS
    val pop = if (solvedColor == null) 1f else {
        val p = (sinceSolved / CHIP_POP_SEC).coerceIn(0f, 1f)
        0.55f + 0.45f * EaseOutBack.transform(p)
    }
    val size = entry * pop * if (armed) 1.14f else 1f
    scale(size, size, pivot = center) {
        if (solvedColor != null) {
            // Halo corto + disco con degradado: la ficha "emite" sobre las celdas encendidas.
            drawCircle(solvedColor.copy(alpha = 0.30f), radius * 1.28f, center)
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(lerp(solvedColor, Color.White, 0.45f), solvedColor),
                    startY = center.y - radius,
                    endY = center.y + radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(Color.White.copy(alpha = 0.55f), radius, center, style = Stroke(1.2.dp.toPx()))
        } else {
            val ring = if (armed) LogicColors.NeonGreen else LogicColors.OnDarkMuted.copy(alpha = 0.45f)
            drawCircle(LogicColors.BackgroundDark.copy(alpha = 0.55f), radius, center)
            drawCircle(ring, radius, center, style = Stroke(1.5.dp.toPx()))
        }
        drawText(
            textLayoutResult = layout,
            color = when {
                solvedColor != null -> LogicColors.BackgroundDark
                armed -> LogicColors.NeonGreen
                else -> LogicColors.OnDark
            },
            topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
        )
    }
}

/**
 * Badge flotante con el cálculo en vivo ("3 × 2 = 6") junto al dedo.
 *
 * Se coloca **encima** del dedo, no debajo: es el único sitio que la mano no tapa. Horizontalmente
 * se recorta al tablero para que no se salga de pantalla en las columnas extremas. Sigue al dedo
 * con un resorte rígido: lo bastante rápido para no quedarse atrás, lo bastante suave para no
 * temblar con cada píxel.
 */
@Composable
private fun DragBadge(selection: ShikakuSelection?, finger: Offset, boardSize: IntSize) {
    // Se recuerda la última selección para que el badge conserve su texto mientras se desvanece.
    var shown by remember { mutableStateOf(selection) }
    if (selection != null) shown = selection
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }

    val lift = with(LocalDensity.current) { 36.dp.roundToPx() }
    val target = IntOffset(
        x = (finger.x.toInt() - badgeSize.width / 2).coerceIn(0, (boardSize.width - badgeSize.width).coerceAtLeast(0)),
        y = finger.y.toInt() - badgeSize.height - lift,
    )
    val position by animateIntOffsetAsState(target, spring(stiffness = Spring.StiffnessHigh), label = "shikakuBadge")

    AnimatedVisibility(
        visible = selection != null,
        modifier = Modifier.offset { position }.onSizeChanged { badgeSize = it },
        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.6f)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val color = current.validity.toSelectionColor()
        val bounds = current.bounds
        val hint = when (current.validity) {
            ShikakuRectValidity.WRONG_AREA ->
                stringResource(Res.string.shikaku_badge_target, current.targetNumber.toString())
            ShikakuRectValidity.MULTIPLE_NUMBERS -> stringResource(Res.string.shikaku_badge_multiple)
            ShikakuRectValidity.OUT_OF_MASK -> stringResource(Res.string.shikaku_badge_out_of_mask)
            ShikakuRectValidity.VALID, ShikakuRectValidity.NO_NUMBER -> null
        }
        Column(
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                .border(1.5.dp, color.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    Res.string.shikaku_badge_area,
                    bounds.width.toString(), bounds.height.toString(), bounds.area.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
            }
        }
    }
}
