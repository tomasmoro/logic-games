package com.example.kortexgames.game.crucigrama

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.drawNeonTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val CellSize = 60.dp
private val BankLetterSize = 60.dp

/**
 * Colores neón asignados por palabra. Cada slot recibe un color estable según su
 * orden, de modo que las palabras entrelazadas se distingan visualmente en los
 * cruces: sin esto, leer una fila/columna cruzada produce "palabras" falsas como
 * `AMORA`. El set es un subconjunto de la paleta neón (§9.2) con tonos bien
 * separados en el círculo cromático.
 */
private val WordColors = listOf(
    LogicColors.NeonGreen,
    LogicColors.NeonCyan,
    LogicColors.Violet,
    LogicColors.Coral,
    LogicColors.Blue,
    LogicColors.Amber,
)

/**
 * Pantalla del Crucigrama Neón.
 *
 * UX (pedidos del usuario):
 *  - teclado de letras **centrado** con estética de tecla de juego,
 *  - **Pista** en la esquina superior derecha (no en una barra inferior),
 *  - **sin** "Reiniciar" ni "Deshacer": la corrección se hace con la tecla de
 *    **Borrar** integrada en el teclado, y la escritura es libre (nunca se bloquea),
 *  - cada palabra se ilumina con **su color** para separar los cruces,
 *  - encendido tipo **cartel de neón** (parpadeo → prende) con chispas al resolver.
 */
@Composable
fun CrucigramaNeonScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: CrucigramaNeonViewModel = viewModel {
        CrucigramaNeonViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Crucigrama Neón",
            description = "Escribe palabras con el teclado inferior. Si una palabra es correcta, se coloca sola en su lugar dentro del crucigrama.",
            accent = CategoryPalette.Language,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            startLabel = "Empezar",
            onStart = { vm.onIntent(CrucigramaNeonIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
        )
        return
    }

    LifecycleResumeEffect(Unit) {
        vm.onIntent(CrucigramaNeonIntent.Resume)
        onPauseOrDispose { vm.onIntent(CrucigramaNeonIntent.Pause) }
    }

    var showHintAd by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            CrosswordHud(
                level = game.level,
                score = game.score,
                solved = game.correctWords,
                total = game.slots.size,
                combo = game.combo,
                onHint = { showHintAd = true },
            )

            // La rejilla ocupa el espacio libre y queda centrada; así el elemento de
            // escritura y el teclado bajan hacia la zona cómoda del pulgar.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CrosswordGrid(
                    rows = game.rows,
                    cols = game.cols,
                    cells = game.cells,
                    slots = game.slots,
                )
            }

            CurrentWord(input = game.inputBuffer, hint = state.revealedHint)

            Spacer(Modifier.height(14.dp))

            LetterBank(
                letters = game.letters,
                accent = CategoryPalette.Language,
                onTapLetter = { vm.onIntent(CrucigramaNeonIntent.TapLetter(it)) },
                onDelete = { vm.onIntent(CrucigramaNeonIntent.Backspace) },
            )
        }

        // Panel lateral semioculto con las palabras extra (bonus) del nivel.
        ExtrasPanel(words = game.extraWords, found = game.extraFound, foundTick = game.extraTick)

        FeedbackFlash(eventId = game.feedbackTick, result = game.lastOutcome)

        if (showHintAd) {
            HintAdOverlay(
                onFinished = {
                    showHintAd = false
                    vm.onIntent(CrucigramaNeonIntent.HintAdWatched)
                },
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(CrucigramaNeonIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(CrucigramaNeonIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(CrucigramaNeonIntent.ChooseLevel) },
            )
        }
    }
}

@Composable
private fun CrosswordHud(
    level: Int,
    score: Int,
    solved: Int,
    total: Int,
    combo: Int,
    onHint: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text("Nivel $level", style = MaterialTheme.typography.labelLarge, color = CategoryPalette.Language, fontWeight = FontWeight.Bold)
            Text("$score", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark, fontWeight = FontWeight.Black)
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Pista anclada arriba a la derecha (pedido del usuario).
            HintButton(onClick = onHint)
            val comboScale by animateFloatAsState(
                targetValue = if (combo >= 2) 1f else 0.8f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "comboScale",
            )
            Text("$solved/$total", style = MaterialTheme.typography.titleMedium, color = LogicColors.OnDark, fontWeight = FontWeight.Bold)
            Text(
                if (combo >= 2) "x$combo" else "",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.NeonGreen,
                fontWeight = FontWeight.Black,
                modifier = Modifier.alpha(comboScale),
            )
        }
    }
}

/** Píldora neón de "Pista" para la esquina superior derecha. */
@Composable
private fun HintButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(LogicColors.SurfaceDark.copy(alpha = 0.9f))
            .border(BorderStroke(1.dp, CategoryPalette.Language.copy(alpha = 0.6f)), shape)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NeonIcon(icon = KortexIcons.Help, tint = CategoryPalette.Language, size = 18.dp, glow = true)
        Text("Pista", style = MaterialTheme.typography.labelLarge, color = CategoryPalette.Language, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Palabra que se está escribiendo. Sin recuadro (pedido del usuario): solo el texto,
 * grande y centrado, con las letras ~25% más grandes que antes para dar protagonismo.
 */
@Composable
private fun CurrentWord(
    input: String,
    hint: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (input.isBlank()) "_ _ _ _" else input.toCharArray().joinToString(" "),
            // displaySmall (~36sp) escalado ~25% -> 45sp para agrandar las letras.
            fontSize = 45.sp,
            style = MaterialTheme.typography.displaySmall,
            color = if (input.isBlank()) LogicColors.OnDarkMuted else LogicColors.OnDark,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = hint ?: "Palabra actual",
            style = MaterialTheme.typography.labelLarge,
            color = CategoryPalette.Language,
        )
    }
}

@Composable
private fun CrosswordGrid(
    rows: Int,
    cols: Int,
    cells: List<CrucigramaNeonCellState>,
    slots: List<CrucigramaNeonSlotState>,
) {
    val byCoord = remember(cells) { cells.associateBy { it.row to it.col } }

    // Color y momento de encendido por celda. El color es el de la palabra que la
    // resolvió; en un cruce (celda compartida por 2 palabras) gana la resuelta más
    // tarde, para que el último encendido "reclame" la intersección.
    val slotColor = remember(slots) {
        slots.mapIndexed { i, slot -> slot.number to WordColors[i % WordColors.size] }.toMap()
    }
    val cellVisual = remember(cells, slots) {
        val solved = slots.filter { it.solved }
        cells.associate { cell ->
            val owner = solved
                .filter { it.number in cell.slotNumbers }
                .maxByOrNull { it.solvedAtTick ?: 0L }
            cell.index to (slotColor[owner?.number] to owner?.solvedAtTick)
        }
    }

    // Tamaño de celda adaptativo: crece hasta [CellSize] pero se encoge para que la
    // rejilla completa quepa (los niveles avanzados tienen más columnas/filas).
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val gap = 4.dp
        val byWidth = (maxWidth - gap * (cols - 1)) / cols
        val byHeight = if (maxHeight.value.isFinite()) (maxHeight - gap * (rows - 1)) / rows else CellSize
        val cell = minOf(CellSize, byWidth, byHeight)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(cols) { col ->
                        val cellState = byCoord[row to col]
                        if (cellState == null) {
                            Spacer(Modifier.size(cell))
                        } else {
                            val (wordColor, tick) = cellVisual[cellState.index] ?: (null to null)
                            GridCell(
                                cell = cellState,
                                wordColor = wordColor ?: CategoryPalette.Language,
                                solvedTick = tick,
                                cellSize = cell,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Celda del crucigrama con estética de juego y encendido de neón real.
 *
 * Al resolverse la palabra que la contiene, la celda se enciende como un tubo de
 * neón: una secuencia de [ignition] con **parpadeos irregulares** que "engancha" y
 * queda encendida, más una **respiración** sutil continua ([breath]) y una ráfaga
 * de **chispas** ([spark]) que salen del centro. El resplandor se dibuja como un
 * halo radial difuminado en [drawBehind] (no una sombra), evitando el artefacto de
 * "recuadro interno" que producía el glow anterior.
 */
@Composable
private fun GridCell(
    cell: CrucigramaNeonCellState,
    wordColor: Color,
    solvedTick: Long?,
    cellSize: androidx.compose.ui.unit.Dp,
) {
    val solved = cell.fixed
    val shape = RoundedCornerShape(12.dp)

    val ignition = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }
    LaunchedEffect(solvedTick) {
        if (solvedTick == null || solvedTick == 0L) {
            ignition.snapTo(if (solved) 1f else 0f)
            spark.snapTo(0f)
            return@LaunchedEffect
        }
        // Chispas en paralelo al encendido.
        launch {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(560, easing = LinearEasing))
        }
        // Parpadeo tipo tubo de neón que titila y "engancha".
        ignition.snapTo(0f)
        ignition.animateTo(0.85f, tween(55))
        ignition.animateTo(0.08f, tween(45))
        ignition.animateTo(0.7f, tween(35))
        ignition.animateTo(0.05f, tween(60))
        ignition.animateTo(1f, tween(150))
    }

    // Respiración lenta y de baja amplitud una vez encendida (§9.4).
    val breath by rememberInfiniteTransition(label = "cellBreath").animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "cellBreathValue",
    )
    // "Encendido" del tubo neón: 0 = apagado (celda vacía en espera), 1 = pleno.
    val activeAmt = if (solved) (ignition.value * (0.7f + 0.3f * breath)).coerceIn(0f, 1f) else 0f
    // Base neutra para las celdas vacías (tubo tenue "en espera"); color de la palabra
    // al resolverse.
    val tileColor = if (solved) wordColor else LogicColors.SurfaceVariantDark

    Box(
        modifier = Modifier
            .size(cellSize)
            .drawBehind {
                // Borde neón tipo tubo (misma estética que Memoria vía [drawNeonTile]).
                // baseMargin reducido ~20% (7 -> 5.6dp) para que el tubo llene más la celda.
                drawNeonTile(tileColor, activeAmt, cornerRadius = 12.dp, sparks = false, baseMargin = 5.6.dp)

                // Chispas propias del crucigrama: partículas radiales que salen del
                // centro y se apagan al encender la palabra.
                val sp = spark.value
                if (sp > 0f && sp < 1f) {
                    val count = 7
                    val seed = cell.index * 1.7f
                    val dist = size.minDimension * (0.3f + 0.8f * sp)
                    val fade = 1f - sp
                    val dot = (2.6f * (1f - sp * 0.4f)).dp.toPx()
                    for (i in 0 until count) {
                        val ang = seed + i * (2f * PI.toFloat() / count)
                        drawCircle(
                            color = wordColor.copy(alpha = fade),
                            radius = dot,
                            center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
                        )
                    }
                }
            }
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cell.entry?.toString() ?: "",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HintAdOverlay(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        // Placeholder de anuncio: en producción se reemplaza por intersticial real.
        delay(1300)
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(LogicColors.SurfaceDark)
                .border(BorderStroke(1.dp, CategoryPalette.Language.copy(alpha = 0.6f)), RoundedCornerShape(20.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Anuncio", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark, fontWeight = FontWeight.Black)
            Text(
                "Viendo anuncio para desbloquear una pista...",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Teclado inferior de letras, **centrado**, con estética de tecla de juego, más la
 * tecla de **Borrar** integrada (única acción disponible; ya no hay Reiniciar ni
 * Deshacer). La escritura es libre: el jugador teclea y corrige con Borrar.
 */
@Composable
private fun LetterBank(
    letters: List<Char>,
    accent: Color,
    onTapLetter: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LETRAS", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        val rows = remember(letters) { letters.chunked(6) }
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { letter ->
                    LetterKey(letter = letter, accent = accent, onClick = { onTapLetter(letter) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            DeleteKey(onClick = onDelete)
        }
    }
}

/**
 * Tecla de letra con la **misma estética neón del crucigrama**: un tubo de neón
 * encendido ([drawNeonTile]) con la letra dentro. Rebota con [bounceClick] al pulsar.
 */
@Composable
private fun LetterKey(letter: Char, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(BankLetterSize)
            .drawBehind { drawNeonTile(accent, activeAmt = 0.85f, cornerRadius = 16.dp, sparks = false, baseMargin = 5.dp) }
            .clip(shape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
    }
}

/** Tecla de **Borrar** (backspace): mismo tubo neón (en cian) que las letras. */
@Composable
private fun DeleteKey(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val tint = LogicColors.NeonCyan
    Row(
        modifier = Modifier
            .height(BankLetterSize)
            .drawBehind { drawNeonTile(tint, activeAmt = 0.7f, cornerRadius = 16.dp, sparks = false, baseMargin = 5.dp) }
            .clip(shape)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NeonIcon(icon = KortexIcons.Backspace, tint = tint, size = 24.dp, glow = false)
        Text("Borrar", style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Panel lateral **semioculto** con las palabras extra (bonus). Colapsado por defecto:
 * solo una pestaña con el contador en el borde derecho; al tocarla se despliega la
 * lista. Las encontradas se revelan en ámbar; las pendientes se enmascaran con puntos.
 *
 * Al descubrir una extra ([foundTick] cambia), el panel se **abre solo 3 s** y luego se
 * oculta, y salta una **ráfaga de chispas** ámbar sobre la pestaña para que el jugador
 * entienda que encontró una palabra extra. Ámbar = recompensa (§9.2).
 */
@Composable
private fun BoxScope.ExtrasPanel(words: List<String>, found: Set<String>, foundTick: Long) {
    if (words.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) } // abierto manualmente por el usuario
    val accent = LogicColors.Amber
    val edgeShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    val spark = remember { Animatable(0f) }

    // Al encontrar una extra: chispas + auto-abrir 3 s (salvo que el usuario lo haya fijado).
    LaunchedEffect(foundTick) {
        if (foundTick == 0L) return@LaunchedEffect
        launch {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(650, easing = LinearEasing))
        }
        open = true
        delay(3000)
        if (!pinned) open = false
    }

    Row(
        modifier = Modifier.align(Alignment.CenterEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (open) {
            Column(
                modifier = Modifier
                    .widthIn(max = 150.dp)
                    .clip(edgeShape)
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.96f))
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.55f)), edgeShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("EXTRAS", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
                words.forEach { w ->
                    val f = w in found
                    Text(
                        text = if (f) w else "•".repeat(w.length),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (f) accent else LogicColors.OnDarkMuted,
                        fontWeight = if (f) FontWeight.Black else FontWeight.Normal,
                        letterSpacing = if (f) 1.sp else 3.sp,
                    )
                }
            }
        }
        // Pestaña siempre visible (semioculta, pegada al borde), con las chispas detrás.
        Column(
            modifier = Modifier
                .drawBehind {
                    val sp = spark.value
                    if (sp > 0f && sp < 1f) {
                        val count = 9
                        val dist = size.minDimension * (0.4f + 1.1f * sp)
                        val fade = 1f - sp
                        val dot = (3f * (1f - sp * 0.4f)).dp.toPx()
                        for (i in 0 until count) {
                            val ang = i * (2f * PI.toFloat() / count)
                            drawCircle(
                                color = accent.copy(alpha = fade),
                                radius = dot,
                                center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
                            )
                        }
                    }
                }
                .clip(edgeShape)
                .background(accent.copy(alpha = 0.16f))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.5f)), edgeShape)
                .bounceClick {
                    open = !open
                    pinned = open
                }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NeonIcon(icon = KortexIcons.Star, tint = accent, size = 18.dp, glow = true)
            Text("${found.size}/${words.size}", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FeedbackFlash(eventId: Long, result: CrucigramaNeonOutcome?) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(eventId) {
        if (eventId == 0L || result == null) return@LaunchedEffect
        alpha.snapTo(0.27f)
        alpha.animateTo(0f, tween(380))
    }
    if (alpha.value <= 0f || result == null) return
    val color = if (result == CrucigramaNeonOutcome.CORRECT) LogicColors.Success else LogicColors.Error
    Box(modifier = Modifier.fillMaxSize().background(color.copy(alpha = alpha.value)))
}
