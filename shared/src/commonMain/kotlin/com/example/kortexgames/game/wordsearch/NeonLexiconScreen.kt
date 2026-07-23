package com.example.kortexgames.game.wordsearch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.ArcadeBrickBackground
import com.example.kortexgames.ui.components.GameExitGuard
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.ResumeState
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Lado máximo de una celda; en rejillas anchas manda el ancho disponible. */
private val MaxCell = 44.dp

/**
 * Pantalla de "Neon Lexicon" (Sopa de Letras Neón).
 *
 * Estructura estándar de juego LEVELED: antesala ([GameIntroScreen] con carril de
 * niveles) → tablero → [GameOverOverlay]. El acento de categoría es **magenta**
 * (Lenguaje) sobre el azul-noche del fondo (§9.2).
 *
 * El detalle protagonista es el **láser** de selección: un `Canvas` por encima de
 * la cuadrícula de letras dibuja una cápsula de luz (línea gruesa con cabos
 * redondeados + halos + degradado) que une la letra inicial con la letra bajo el
 * dedo, en tiempo real. El feedback "cremallera" (tick + háptica por cada letra
 * cruzada) llega como Effects del ViewModel y se reproduce aquí.
 */
@Composable
fun NeonLexiconScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: NeonLexiconViewModel = viewModel {
        NeonLexiconViewModel(
            graph.progressRepository,
            graph.playerProgressRepository,
            graph.savedGameStateRepository,
            graph.audio,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val accent = CategoryPalette.Language

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la partida en curso antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // Único punto donde los Effects se vuelven sonido/vibración (patrón blockgrid).
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is NeonLexiconEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonLexiconEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
            }
        }
    }

    // Antesala: selección de nivel.
    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Sopa de Letras Neón",
            description = "Desliza el dedo sobre las letras para trazar cada palabra escondida: horizontal, vertical o en diagonal. Encuéntralas todas para superar el nivel.",
            accent = accent,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            startLabel = "Empezar",
            onStart = { vm.onIntent(NeonLexiconIntent.PlayLevel(selectedLevel)) },
            resume = state.savedLevel?.let { level ->
                ResumeState(
                    onResume = { vm.onIntent(NeonLexiconIntent.ResumeSaved) },
                    detail = "Nivel $level en curso",
                )
            },
            onExit = onExit,
            background = {
                ArcadeBrickBackground(modifier = Modifier.fillMaxSize(), accent = accent)
            },
        )
        return
    }

    // Pausa el cronómetro del motor cuando la pantalla no está en primer plano.
    LifecycleResumeEffect(Unit) {
        vm.onIntent(NeonLexiconIntent.Resume)
        onPauseOrDispose { vm.onIntent(NeonLexiconIntent.Pause) }
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Textura ambiental de muro arcade "neo-retro" (magenta Lenguaje), muy sutil.
        ArcadeBrickBackground(
            modifier = Modifier.fillMaxSize(),
            accent = accent,
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LexiconHud(
                level = state.currentLevel,
                found = state.words.count { it.found },
                total = state.words.size,
                accent = accent,
            )

            // La rejilla ocupa el espacio libre y queda centrada.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                WordGridBoard(
                    grid = state.grid,
                    selection = state.selection,
                    words = state.words,
                    accent = accent,
                    onStartDrag = { r, c -> vm.onIntent(NeonLexiconIntent.StartDrag(r, c)) },
                    onUpdateDrag = { r, c -> vm.onIntent(NeonLexiconIntent.UpdateDrag(r, c)) },
                    onEndDrag = { vm.onIntent(NeonLexiconIntent.EndDrag) },
                    onCancelDrag = { vm.onIntent(NeonLexiconIntent.CancelDrag) },
                )
            }

            WordList(words = state.words, accent = accent)
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(NeonLexiconIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(NeonLexiconIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(NeonLexiconIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(NeonLexiconIntent.Pause) },
            onResume = { vm.onIntent(NeonLexiconIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Sopa de Letras Neón",
            helpText = "Desliza el dedo sobre las letras para trazar cada palabra escondida: horizontal, vertical o en diagonal. Encuéntralas todas para superar el nivel.",
            accent = accent,
            exitKeepsProgress = true,
        )

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la partida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(NeonLexiconIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = accent,
        )
    }
}

/** HUD superior: nivel y progreso de palabras encontradas. */
@Composable
private fun LexiconHud(level: Int, found: Int, total: Int, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Nivel $level",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Sopa de Letras",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            "$found/$total",
            style = MaterialTheme.typography.headlineMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * El tablero: cuadrícula de letras (capa de `Text`) con el **láser** de selección
 * y las cápsulas de palabras resueltas dibujados encima en un `Canvas`.
 *
 * ## Geometría del gesto (píxel → celda)
 * El detector [detectDragGestures] entrega la posición del dedo en píxeles
 * **relativos a este Box**, que mide exactamente `cols·cellPx × rows·cellPx`. Por
 * eso la conversión es directa: `col = floor(x/cellPx)`, `row = floor(y/cellPx)`.
 * Se descartan posiciones fuera de la rejilla (dedo por encima/al lado). El
 * ViewModel recibe solo celdas (no píxeles) y ademas se **deduplica**: solo se
 * emite `UpdateDrag` cuando el dedo entra en una celda distinta, evitando cientos
 * de intents redundantes (el dedo se mueve a 60+ Hz, la celda no).
 */
@Composable
private fun WordGridBoard(
    grid: WordSearchGrid,
    selection: Selection?,
    words: List<WordEntry>,
    accent: Color,
    onStartDrag: (Int, Int) -> Unit,
    onUpdateDrag: (Int, Int) -> Unit,
    onEndDrag: () -> Unit,
    onCancelDrag: () -> Unit,
) {
    if (grid.rows == 0 || grid.cols == 0) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Lado de celda: cabe a lo ancho y (si la altura es finita) a lo alto,
        // sin pasar de [MaxCell] para que las rejillas pequeñas no se agiganten.
        val byWidth = maxWidth / grid.cols
        val byHeight = if (maxHeight.value.isFinite()) maxHeight / grid.rows else MaxCell
        val cell = minOf(MaxCell, byWidth, byHeight)
        val cellPx = with(density) { cell.toPx() }

        val foundWords = remember(words) { words.filter { it.found }.map { it.word } }
        val selectedCells = remember(selection) { selection?.cells?.toSet().orEmpty() }
        val solvedCells = remember(foundWords) { foundWords.flatMap { it.cells }.toSet() }

        // Última celda emitida, para deduplicar UpdateDrag (ver KDoc).
        var lastCell by remember { mutableStateOf<Coordinate?>(null) }

        Box(
            modifier = Modifier
                .size(cell * grid.cols, cell * grid.rows)
                .pointerInput(grid) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            cellAt(offset, cellPx, grid)?.let {
                                lastCell = it
                                onStartDrag(it.row, it.col)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val c = cellAt(change.position, cellPx, grid)
                            if (c != null && c != lastCell) {
                                lastCell = c
                                onUpdateDrag(c.row, c.col)
                            }
                        },
                        onDragEnd = {
                            lastCell = null
                            onEndDrag()
                        },
                        onDragCancel = {
                            lastCell = null
                            onCancelDrag()
                        },
                    )
                },
        ) {
            // Capa 0: láser + cápsulas resueltas, por DEBAJO de las letras. Así el
            // tubo de neón sigue brillando (asoma por las juntas y por la
            // translucidez del fondo de cada celda) sin tapar nunca el glifo: la
            // letra manda siempre en legibilidad, el neón es el "ambiente" detrás.
            Canvas(modifier = Modifier.matchParentSize()) {
                // Cápsulas de palabras ya encontradas: tenues y permanentes.
                foundWords.forEach { word ->
                    drawCapsule(
                        from = cellCenter(word.start, cellPx),
                        to = cellCenter(word.end, cellPx),
                        thickness = cellPx * 0.72f,
                        accent = accent,
                        intensity = 0.42f,
                    )
                }
                // Láser activo: la cápsula brillante bajo el dedo.
                selection?.let { sel ->
                    drawCapsule(
                        from = cellCenter(sel.start, cellPx),
                        to = cellCenter(sel.current, cellPx),
                        thickness = cellPx * 0.78f,
                        accent = accent,
                        intensity = 1f,
                    )
                }
            }

            // Capa 1: letras, siempre encima del tubo de neón para que se lean bien.
            Column {
                for (r in 0 until grid.rows) {
                    Row {
                        for (c in 0 until grid.cols) {
                            val coord = Coordinate(r, c)
                            LetterCellView(
                                letter = grid.letters[r][c],
                                size = cell,
                                solved = coord in solvedCells,
                                selected = coord in selectedCells,
                                accent = accent,
                            )
                        }
                    }
                }
            }

            // Capa 2: chispas de acierto. Breves y por encima de todo (mismo
            // lenguaje de "reward" que Burbujas de Cálculo), una ráfaga por
            // palabra recién encontrada a lo largo de su trazo.
            words.forEach { entry ->
                key(entry.text) {
                    WordSparkBurst(word = entry.word, found = entry.found, cellPx = cellPx, accent = accent)
                }
            }
        }
    }
}

/**
 * Celda-letra. Al resolverse su palabra "salta" con un `spring` (escala 1.3→1) y
 * se enciende en el acento — el rebote táctil pedido para el acierto (§9.4).
 */
@Composable
private fun LetterCellView(
    letter: Char,
    size: androidx.compose.ui.unit.Dp,
    solved: Boolean,
    selected: Boolean,
    accent: Color,
) {
    // Rebote one-shot cuando la celda pasa a resuelta.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(solved) {
        if (solved) {
            pop.snapTo(1.3f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    val background = when {
        solved -> accent.copy(alpha = 0.14f)
        selected -> accent.copy(alpha = 0.22f)
        else -> LogicColors.SurfaceVariantDark.copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier.size(size).padding(2.dp).clip(RoundedCornerShape(10.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.titleMedium,
            // Letras no seleccionadas atenuadas (OnDarkMuted, §9.2). Seleccionadas
            // Y ya resueltas en blanco puro (no OnDark ni el acento): sobre el tubo
            // de neón brillante que asoma debajo, el blanco puro es el único tono
            // con contraste fiable; el acento sigue vivo en el fondo de la celda.
            color = when {
                solved || selected -> Color.White
                else -> LogicColors.OnDarkMuted
            },
            fontWeight = if (solved || selected) FontWeight.Black else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
        )
    }
}

/** Lista de palabras a encontrar; las halladas se tachan y encienden en el acento. */
@Composable
private fun WordList(words: List<WordEntry>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Se reparte en filas de 3 para no depender de FlowRow (experimental).
        words.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { entry ->
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (entry.found) accent else LogicColors.OnDarkMuted,
                        fontWeight = if (entry.found) FontWeight.Black else FontWeight.SemiBold,
                        textDecoration = if (entry.found) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

/** Centro en píxeles de la celda [coord] (para anclar los extremos del láser). */
private fun cellCenter(coord: Coordinate, cellPx: Float): Offset =
    Offset((coord.col + 0.5f) * cellPx, (coord.row + 0.5f) * cellPx)

/** 2π: círculo completo en radianes, para repartir las chispas en todas direcciones. */
private const val TAU = 6.2831855f

/** Nº de chispas que libera una palabra al completarse. */
private const val WordBurstSparkCount = 18

/**
 * Semilla de una chispa de la ráfaga de acierto: punto de origen a lo largo del
 * trazo de la palabra ([originT], 0=inicio..1=fin) y dirección/alcance de salida,
 * fijos durante toda la animación (se generan una vez por palabra, deterministas
 * por su texto, para que no "salten" entre recomposiciones).
 */
private data class WordSparkSeed(val originT: Float, val angle: Float, val reach: Float, val length: Float)

/**
 * Ráfaga de **chispas** que se dispara una sola vez al completar una palabra
 * (mismo lenguaje visual que el estallido de burbuja de Burbujas de Cálculo):
 * pequeñas esquirlas nacen en puntos aleatorios a lo largo del trazo acertado y
 * salen disparadas hacia afuera mientras se desvanecen. Es el "premio" de acertar,
 * sin invadir la legibilidad de las letras (dura ~500ms y las chispas son finas).
 *
 * Se dispara con `LaunchedEffect(found)`: como cada instancia vive bajo `key(word)`
 * en el llamador, solo corre cuando esa palabra concreta pasa a encontrada.
 */
@Composable
private fun WordSparkBurst(word: TargetWord, found: Boolean, cellPx: Float, accent: Color) {
    val progress = remember(word.text) { Animatable(0f) }
    LaunchedEffect(found) {
        if (!found) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 520))
    }
    val p = progress.value
    if (!found || p <= 0f || p >= 1f) return

    val sparks = remember(word.text) {
        val rnd = Random(word.text.hashCode())
        List(WordBurstSparkCount) {
            WordSparkSeed(
                originT = rnd.nextFloat(),
                angle = rnd.nextFloat() * TAU,
                reach = 0.6f + rnd.nextFloat() * 0.6f,
                length = 0.6f + rnd.nextFloat() * 0.5f,
            )
        }
    }

    val start = cellCenter(word.start, cellPx)
    val end = cellCenter(word.end, cellPx)

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Desaceleración: salen rápido y frenan (ease-out), como esquirlas reales.
        val ease = 1f - (1f - p) * (1f - p)
        val alpha = 1f - p
        val maxDist = cellPx * 0.85f
        val unit = cellPx * 0.16f
        val hot = lerp(accent, Color.White, 0.35f)

        sparks.forEach { s ->
            val origin = Offset(
                x = start.x + (end.x - start.x) * s.originT,
                y = start.y + (end.y - start.y) * s.originT,
            )
            val dist = ease * maxDist * s.reach
            val dx = cos(s.angle)
            val dy = sin(s.angle)
            val head = Offset(origin.x + dx * dist, origin.y + dy * dist)
            val tail = Offset(
                origin.x + dx * (dist - unit * s.length),
                origin.y + dy * (dist - unit * s.length),
            )
            drawLine(
                color = hot.copy(alpha = alpha),
                start = tail,
                end = head,
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius = 1.6.dp.toPx(), center = head)
        }
    }
}

/**
 * Convierte el punto [offset] (px, relativo al tablero) a celda de la rejilla, o
 * null si cae fuera. `floor` (no truncado) para que un dedo por encima/izquierda
 * del tablero dé índices negativos y se descarte, en vez de colapsar a 0.
 */
private fun cellAt(offset: Offset, cellPx: Float, grid: WordSearchGrid): Coordinate? {
    val col = floor(offset.x / cellPx).toInt()
    val row = floor(offset.y / cellPx).toInt()
    val coord = Coordinate(row, col)
    return if (grid.isInside(coord)) coord else null
}

/**
 * Dibuja la **cápsula de luz** (el "láser" de neón) entre dos centros de celda.
 *
 * El efecto de tubo de neón se consigue apilando trazos con [StrokeCap.Round] del
 * más ancho y translúcido (halo exterior) al más fino y brillante (núcleo
 * caliente casi blanco). Cabos redondeados = extremos en forma de píldora sobre
 * la primera y última letra. El [intensity] atenúa todo el conjunto: 1 para la
 * selección activa, ~0.4 para las palabras ya resueltas (presentes pero sin robar
 * el foco). El degradado del núcleo corre a lo largo del trazo para dar sensación
 * de energía dirigida.
 */
private fun DrawScope.drawCapsule(
    from: Offset,
    to: Offset,
    thickness: Float,
    accent: Color,
    intensity: Float,
) {
    val cap = StrokeCap.Round
    // Halos externos: anchos y muy translúcidos, dan el resplandor sobre el fondo.
    drawLine(accent.copy(alpha = 0.14f * intensity), from, to, strokeWidth = thickness * 1.9f, cap = cap)
    drawLine(accent.copy(alpha = 0.26f * intensity), from, to, strokeWidth = thickness * 1.35f, cap = cap)
    // Núcleo con degradado a lo largo del trazo (energía dirigida).
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(lerp(accent, Color.White, 0.35f), accent),
            start = from,
            end = to,
        ),
        start = from,
        end = to,
        strokeWidth = thickness * 0.62f * (0.6f + 0.4f * intensity),
        cap = cap,
        alpha = 0.55f + 0.35f * intensity,
    )
    // Centro caliente casi blanco: la línea fina que "quema" en el eje del láser.
    drawLine(
        lerp(accent, Color.White, 0.8f).copy(alpha = 0.75f * intensity),
        from,
        to,
        strokeWidth = thickness * 0.22f,
        cap = cap,
    )
}
