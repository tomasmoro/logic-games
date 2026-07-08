package com.example.kortexgames.game.crucigrama

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.kortexgames.ui.components.softGlow
import kotlinx.coroutines.delay

private val CellSize = 52.dp
private val BankLetterSize = 58.dp

/**
 * Pantalla del Crucigrama Neón.
 *
 * Cambios de UX pedidos por el usuario:
 *  - sin marco/fondo decorativo en el tablero,
 *  - rejilla más grande,
 *  - sin numeración pequeña,
 *  - pistas ocultas y desbloqueables solo tras anuncio,
 *  - colocación automática al escribir una palabra correcta.
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
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CrosswordHud(
                level = game.level,
                score = game.score,
                solved = game.correctWords,
                total = game.slots.size,
                combo = game.combo,
            )

            CrosswordGrid(
                rows = game.rows,
                cols = game.cols,
                cells = game.cells,
                slots = game.slots,
            )

            InputStrip(
                input = game.inputBuffer,
                hint = state.revealedHint,
            )

            LetterBank(
                letters = game.letters,
                accent = CategoryPalette.Language,
                onTapLetter = { vm.onIntent(CrucigramaNeonIntent.TapLetter(it)) },
            )

            ActionRow(
                onHint = { showHintAd = true },
                onClear = { vm.onIntent(CrucigramaNeonIntent.ClearWord) },
                onBackspace = { vm.onIntent(CrucigramaNeonIntent.Backspace) },
            )
        }

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
private fun CrosswordHud(level: Int, score: Int, solved: Int, total: Int, combo: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Nivel $level", style = MaterialTheme.typography.labelLarge, color = CategoryPalette.Language, fontWeight = FontWeight.Bold)
            Text("$score", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark, fontWeight = FontWeight.Black)
        }
        Column(horizontalAlignment = Alignment.End) {
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

@Composable
private fun InputStrip(
    input: String,
    hint: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            CategoryPalette.Language.copy(alpha = 0.18f),
                            LogicColors.SurfaceDark,
                        ),
                    ),
                )
                .border(
                    BorderStroke(1.4.dp, CategoryPalette.Language.copy(alpha = 0.65f)),
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = if (input.isBlank()) "_ _ _ _" else input.toCharArray().joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                color = if (input.isBlank()) LogicColors.OnDarkMuted else LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.6.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "Palabra actual",
            style = MaterialTheme.typography.labelLarge,
            color = CategoryPalette.Language,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = CategoryPalette.Language,
            )
        }
    }
}

@Composable
private fun ActionRow(
    onHint: () -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolButton(icon = KortexIcons.Help, label = "Pista", tint = CategoryPalette.Language, onClick = onHint)
        ToolButton(icon = KortexIcons.Refresh, label = "Reiniciar", tint = LogicColors.Amber, onClick = onClear)
        ToolButton(icon = KortexIcons.Undo, label = "Deshacer", tint = LogicColors.NeonCyan, onClick = onBackspace)
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
    val solvedByCell = remember(slots) {
        val solvedNumbers = slots.filter { it.solved }.map { it.number }.toSet()
        cells.associate { cell ->
            val solvedTick = slots
                .filter { it.number in solvedNumbers && cell.slotNumbers.contains(it.number) }
                .maxOfOrNull { it.solvedAtTick ?: 0L }
            cell.index to solvedTick
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(cols) { col ->
                    val cell = byCoord[row to col]
                    if (cell == null) {
                        Spacer(Modifier.size(CellSize))
                    } else {
                        GridCell(
                            cell = cell,
                            solved = cell.fixed,
                            solvedTick = solvedByCell[cell.index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    cell: CrucigramaNeonCellState,
    solved: Boolean,
    solvedTick: Long?,
) {
    val shape = RoundedCornerShape(12.dp)
    val flicker = remember { Animatable(0f) }
    LaunchedEffect(solvedTick) {
        if (solvedTick == null || solvedTick == 0L) {
            flicker.snapTo(0f)
            return@LaunchedEffect
        }
        flicker.snapTo(0f)
        flicker.animateTo(1f, tween(90))
        flicker.animateTo(0.35f, tween(55))
        flicker.animateTo(1f, tween(120))
    }
    val glowAlpha = if (solved) 0.3f + flicker.value * 0.7f else 0f

    Box(
        modifier = Modifier
            .size(CellSize)
            .then(
                if (solved) Modifier.softGlow(CategoryPalette.Language, shape = shape, minElevation = 2.dp, maxElevation = 10.dp)
                else Modifier,
            )
            .clip(shape)
            .background(
                when {
                    solved -> CategoryPalette.Language.copy(alpha = 0.2f * glowAlpha.coerceAtLeast(0.5f))
                    else -> LogicColors.SurfaceDark
                },
            )
            .border(
                BorderStroke(
                    if (solved) 1.5.dp else 1.dp,
                    if (solved) CategoryPalette.Language.copy(alpha = glowAlpha.coerceAtLeast(0.7f)) else LogicColors.SurfaceVariantDark,
                ),
                shape,
            ),
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

@Composable
private fun LetterBank(letters: List<Char>, accent: Color, onTapLetter: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LETRAS", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        val rows = remember(letters) { letters.chunked(6) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { letter ->
                        LetterTile(letter = letter, accent = accent, onClick = { onTapLetter(letter) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterTile(letter: Char, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .size(BankLetterSize)
            .softGlow(color = accent, shape = shape, minElevation = 4.dp, maxElevation = 12.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f), LogicColors.SurfaceDark)))
            .border(BorderStroke(1.2.dp, accent.copy(alpha = 0.7f)), shape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), style = MaterialTheme.typography.headlineSmall, color = LogicColors.BackgroundDark, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LogicColors.SurfaceDark.copy(alpha = 0.92f))
                .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), RoundedCornerShape(16.dp))
                .bounceClick(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(icon = icon, tint = tint, size = 24.dp, glow = false)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
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



