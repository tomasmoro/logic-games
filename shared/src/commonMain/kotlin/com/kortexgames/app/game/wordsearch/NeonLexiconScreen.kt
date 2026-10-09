package com.kortexgames.app.game.wordsearch

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
import androidx.compose.ui.graphics.drawscope.inset
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
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.ArcadeBrickBackground
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.UPCOMING_LEVEL_TEASERS
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gameintro_levels_cleared_notice
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.sp
import com.kortexgames.app.ui.components.NeonProgressBar
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.gameboard_hud_level
import kortexgames.shared.generated.resources.wordsearch_hud_words
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import org.jetbrains.compose.resources.stringResource

/** Lado máximo de una celda; en rejillas anchas manda el ancho disponible. */
private val MaxCell = 44.dp

/** Marco de la placa alrededor de la rejilla de letras. */
private val PlatePadding = 8.dp

/**
 * Colores de las palabras encontradas, por orden en la lista. Cada palabra se queda con el suyo
 * (tubo en la rejilla + ficha en la lista), así dos palabras que se cruzan se distinguen y el
 * jugador localiza de un vistazo en la rejilla la que acaba de tachar. Subconjunto de la paleta
 * neón (§9.2) con tonos bien separados; el acento del juego (magenta) queda para el láser activo.
 */
private val WordColors = listOf(
    LogicColors.NeonCyan,
    LogicColors.NeonGreen,
    LogicColors.Amber,
    LogicColors.Violet,
    LogicColors.Coral,
    LogicColors.Blue,
)

/** Lo que tarda el tubo de una palabra encontrada en recorrerla de punta a punta (s). */
private const val TUBE_SWEEP_SEC = 0.32f

/** Lo que tarda el tubo en bajar de pleno brillo a reposo tras encenderse (s). */
private const val TUBE_SETTLE_SEC = 0.9f

/** Retraso del rebote entre una letra de la palabra y la siguiente (ms): la ola sigue al tubo. */
private const val LETTER_WAVE_STEP_MS = 45L

/** Retraso de la entrada en cascada entre una diagonal de la rejilla y la siguiente (ms). */
private const val ENTRY_STEP_MS = 18L

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
            graph.adManager,
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
        // Catálogo finito: la selección nunca apunta más allá del último nivel (si
        // ya se superaron todos, "Empezar" rejuega el último — no hay "siguiente").
        var selectedLevel by remember(state.maxUnlocked) {
            mutableStateOf((state.maxUnlocked + 1).coerceAtMost(NeonLexiconGenerator.levelCount))
        }
        GameIntroScreen(
            help = GameHelpContent.neonLexicon,
            title = "Sopa de Letras Neón",
            motif = GameMotif.WORD_SEARCH,
            description = "Desliza el dedo sobre las letras para trazar cada palabra escondida: horizontal, vertical o en diagonal. Encuéntralas todas para superar el nivel.",
            accent = accent,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
                playableLevels = NeonLexiconGenerator.levelCount,
                // Un par de casillas más, con candado y "Pronto", para adelantar que
                // habrá más niveles: así el carril no se corta en seco en el último.
                maxLevel = NeonLexiconGenerator.levelCount + UPCOMING_LEVEL_TEASERS,
            ),
            completionNotice = if (state.allLevelsCompleted) {
                stringResource(Res.string.gameintro_levels_cleared_notice)
            } else {
                null
            },
            startLabel = "Empezar",
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_LEXICON)
                vm.onIntent(NeonLexiconIntent.PlayLevel(selectedLevel))
            },
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
                // Catálogo finito: si esta era la última, "Siguiente nivel" pasa a
                // "Ver niveles" y lleva a la antesala con el cartel de completado.
                hasNextLevel = state.currentLevel < NeonLexiconGenerator.levelCount,
                accent = accent,
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
            help = GameHelpContent.neonLexicon,
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

/**
 * HUD superior: nivel y progreso de palabras encontradas.
 *
 * Mismo esqueleto que el de Crucigrama y los tableros por niveles (píldora + barra que se llena).
 * El título del juego se quitó de aquí: ya está en la antesala y en el menú de pausa. Deja libre
 * la esquina superior derecha, donde vive el botón de pausa (antes el contador quedaba debajo).
 */
@Composable
private fun LexiconHud(level: Int, found: Int, total: Int, accent: Color) {
    val progress by animateFloatAsState(
        targetValue = if (total <= 0) 0f else found.toFloat() / total,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lexiconProgress",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 62.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.gameboard_hud_level, level.toString()).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.wordsearch_hud_words, found.toString(), total.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Bold,
            )
            NeonProgressBar(progress = progress, color = accent, modifier = Modifier.fillMaxWidth())
        }
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
        // La placa asoma [PlatePadding] alrededor de la rejilla; se descuenta antes de repartir.
        val byWidth = (maxWidth - PlatePadding * 2) / grid.cols
        val byHeight = if (maxHeight.value.isFinite()) (maxHeight - PlatePadding * 2) / grid.rows else MaxCell
        val cell = minOf(MaxCell, byWidth, byHeight)
        val cellPx = with(density) { cell.toPx() }

        val selectedCells = remember(selection) { selection?.cells?.toSet().orEmpty() }
        // Celda → (color de su palabra, puesto de la letra dentro de ella). En un cruce gana la
        // palabra posterior de la lista. El puesto ordena la ola de rebotes a lo largo del trazo.
        val solvedCells = remember(words) {
            val map = HashMap<Coordinate, Pair<Color, Int>>()
            words.forEachIndexed { index, entry ->
                if (!entry.found) return@forEachIndexed
                val color = WordColors[index % WordColors.size]
                entry.word.cells.forEachIndexed { order, coord -> map[coord] = color to order }
            }
            map
        }

        // Reloj del trazo de las palabras encontradas. Solo corre mientras alguna se está
        // encendiendo: el resto de la partida la rejilla es estática y no debe redibujarse.
        val foundAt = remember(grid) { mutableStateMapOf<String, Float>() }
        var animating by remember { mutableStateOf(false) }
        val clock = rememberBoardClock(running = animating)
        val seeded = remember(grid) { booleanArrayOf(false) }
        LaunchedEffect(words) {
            var fresh = false
            words.forEach { entry ->
                if (!entry.found || entry.text in foundAt) return@forEach
                // Las que ya venían encontradas (partida retomada) nacen asentadas, sin trazo.
                foundAt[entry.text] = if (seeded[0]) clock.peek() else Float.NEGATIVE_INFINITY
                fresh = fresh || seeded[0]
            }
            seeded[0] = true
            if (fresh) {
                animating = true
                delay(((TUBE_SWEEP_SEC + TUBE_SETTLE_SEC) * 1000).toLong() + 100L)
                animating = false
            }
        }

        // Última celda emitida, para deduplicar UpdateDrag (ver KDoc).
        var lastCell by remember { mutableStateOf<Coordinate?>(null) }

        Box(
            modifier = Modifier
                .size(cell * grid.cols, cell * grid.rows)
                // Placa compartida del kit de tableros, asomando por fuera de la rejilla. Sustituye
                // al recuadro gris por celda: una sola superficie limpia donde lo único que
                // brilla son los tubos de las palabras.
                .drawBehind {
                    val pad = PlatePadding.toPx()
                    inset(-pad, -pad, -pad, -pad) { drawNeonBoardPlate(accent = accent, corner = 18.dp) }
                }
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
                // Tubos de las palabras ya encontradas: cada una en su color. El tubo recorre la
                // palabra de la primera letra a la última al encontrarla y luego baja a reposo.
                val now = if (animating) clock.seconds else Float.MAX_VALUE
                words.forEachIndexed { index, entry ->
                    val stamp = foundAt[entry.text] ?: return@forEachIndexed
                    val age = now - stamp
                    val sweep = (age / TUBE_SWEEP_SEC).coerceIn(0f, 1f)
                    val from = cellCenter(entry.word.start, cellPx)
                    val end = cellCenter(entry.word.end, cellPx)
                    drawWordTube(
                        from = from,
                        to = Offset(from.x + (end.x - from.x) * sweep, from.y + (end.y - from.y) * sweep),
                        thickness = cellPx * 0.74f,
                        color = WordColors[index % WordColors.size],
                        glow = 1f - ((age - TUBE_SWEEP_SEC) / TUBE_SETTLE_SEC).coerceIn(0f, 1f),
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
                                solved = solvedCells[coord],
                                selected = coord in selectedCells,
                                entryDelayMs = (r + c) * ENTRY_STEP_MS,
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
                    WordSparkBurst(
                        word = entry.word,
                        found = entry.found,
                        cellPx = cellPx,
                        accent = WordColors[words.indexOf(entry).coerceAtLeast(0) % WordColors.size],
                    )
                }
            }
        }
    }
}

/**
 * Celda-letra: solo el glifo, sin recuadro propio (el fondo es la placa del tablero).
 *
 * Tres animaciones, todas con resorte (§9.4):
 *  - **entrada**: aparece en cascada diagonal al montarse el nivel;
 *  - **selección**: crece un poco mientras el láser pasa por ella, así el trazo se siente bajo
 *    el dedo;
 *  - **acierto**: salta al resolverse su palabra, con un retraso según su puesto en ella — la ola
 *    recorre la palabra detrás del tubo — y queda encendida con un halo del color de la palabra.
 *
 * @param solved color de la palabra que la resolvió y puesto de la letra dentro de ella, o `null`.
 * @param entryDelayMs retraso de su entrada en la cascada inicial.
 */
@Composable
private fun LetterCellView(
    letter: Char,
    size: androidx.compose.ui.unit.Dp,
    solved: Pair<Color, Int>?,
    selected: Boolean,
    entryDelayMs: Long,
) {
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(entryDelayMs)
        entry.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }

    // Rebote one-shot cuando la celda pasa a resuelta (o cambia de palabra en un cruce).
    val pop = remember { Animatable(1f) }
    // Sin animar en la primera composición: una partida retomada no debe saltar entera.
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(solved?.first) {
        val isFirst = first[0]
        first[0] = false
        if (solved == null || isFirst) return@LaunchedEffect
        delay(solved.second * LETTER_WAVE_STEP_MS)
        pop.snapTo(1.45f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val lift by animateFloatAsState(
        targetValue = if (selected) 1.22f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "letterLift",
    )

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.titleMedium.copy(
                // Halo del color de la palabra: la letra resuelta se lee como rótulo encendido.
                shadow = solved?.let { Shadow(color = it.first.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 12f) },
            ),
            // Seleccionadas y resueltas en blanco puro: dentro del tubo es el único tono con
            // contraste fiable. El resto, en el texto normal del tema — con la placa lisa de
            // fondo ya no hace falta atenuarlas para que el tablero respire.
            color = if (solved != null || selected) Color.White else LogicColors.OnDark.copy(alpha = 0.78f),
            fontWeight = if (solved != null || selected) FontWeight.Black else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val scale = entry.value * pop.value * lift
                scaleX = scale
                scaleY = scale
                alpha = entry.value.coerceIn(0f, 1f)
            },
        )
    }
}

/**
 * Lista de palabras a encontrar, como fichas. La hallada toma **el color de su tubo** en la
 * rejilla (borde, texto y un baño de fondo), se tacha y entra con un rebote: lista y tablero
 * hablan el mismo idioma y el jugador ve cuál acaba de caer.
 */
@Composable
private fun WordList(words: List<WordEntry>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Se reparte en filas de 3 para no depender de FlowRow (experimental).
        words.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                row.forEachIndexed { i, entry ->
                    key(entry.text) {
                        WordChip(entry = entry, color = WordColors[(rowIndex * 3 + i) % WordColors.size])
                    }
                }
            }
        }
    }
}

/** Ficha de una palabra de la lista; ver [WordList]. */
@Composable
private fun WordChip(entry: WordEntry, color: Color) {
    val lit by animateFloatAsState(
        targetValue = if (entry.found) 1f else 0f,
        animationSpec = tween(220),
        label = "wordChipLit",
    )
    val pop = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(entry.found) {
        val isFirst = first[0]
        first[0] = false
        if (!entry.found || isFirst) return@LaunchedEffect
        pop.snapTo(1.28f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    Text(
        text = entry.text,
        style = MaterialTheme.typography.labelLarge,
        color = lerp(LogicColors.OnDark.copy(alpha = 0.85f), color, lit),
        fontWeight = if (entry.found) FontWeight.Black else FontWeight.SemiBold,
        textDecoration = if (entry.found) TextDecoration.LineThrough else null,
        modifier = Modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .background(lerp(LogicColors.SurfaceDark, color, 0.16f * lit).copy(alpha = 0.85f), CircleShape)
            .border(1.dp, lerp(LogicColors.SurfaceVariantDark, color, 0.75f * lit), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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

/**
 * **Tubo de neón hueco** de una palabra encontrada: el contorno de la cápsula encendido en su
 * color y el interior de cristal oscuro apenas teñido, para que las letras se lean dentro.
 *
 * Antes la palabra hallada quedaba como una cápsula rellena a media opacidad, igual que el láser
 * pero más apagada: con varias cruzándose el tablero se volvía una mancha del mismo magenta. Un
 * tubo por palabra, hueco y de su color, mantiene limpia la rejilla y deja al láser activo
 * ([drawCapsule], macizo) como único elemento "lleno".
 *
 * El contorno sale de dos trazos con cabos redondos: uno del color a todo el grosor y encima
 * otro algo más fino con el cristal, que deja visible solo el borde. Mismas capas que el resto
 * del neón de la app (halo ancho → intermedio → trazo nítido → núcleo blanco, §9.7).
 *
 * @param glow 0..1: brillo extra de recién encendido; en 0 queda en su reposo.
 */
private fun DrawScope.drawWordTube(from: Offset, to: Offset, thickness: Float, color: Color, glow: Float) {
    val cap = StrokeCap.Round
    val stroke = 2.2.dp.toPx()
    val boost = glow.coerceIn(0f, 1f)
    drawLine(color.copy(alpha = 0.10f + 0.14f * boost), from, to, strokeWidth = thickness + stroke * 5f, cap = cap)
    drawLine(color.copy(alpha = 0.26f + 0.22f * boost), from, to, strokeWidth = thickness + stroke * 2f, cap = cap)
    drawLine(color, from, to, strokeWidth = thickness, cap = cap)
    drawLine(
        lerp(color, Color.White, 0.55f + 0.35f * boost).copy(alpha = 0.55f + 0.4f * boost),
        from,
        to,
        strokeWidth = thickness - stroke * 0.6f,
        cap = cap,
    )
    drawLine(
        lerp(LogicColors.BackgroundDark, color, 0.20f + 0.25f * boost),
        from,
        to,
        strokeWidth = (thickness - stroke * 2f).coerceAtLeast(0f),
        cap = cap,
    )
}
