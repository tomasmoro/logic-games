package com.example.kortexgames.game.wordconnect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.LevelStripState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val WheelSize = 300.dp
private val NodeSize = 66.dp

/**
 * Pantalla de **Palabras Conectadas**.
 *
 * Layout (de arriba abajo): HUD, ranuras de palabras que se revelan con brillo neón,
 * la "pizarra" con la palabra que se está formando y —anclada abajo— la **rueda de
 * letras** que el jugador arrastra para unir letras (mecánica de la imagen de
 * referencia). Todo el estado de juego vive en el motor; aquí solo se pinta y anima.
 */
@Composable
fun WordConnectScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: WordConnectViewModel = viewModel {
        WordConnectViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game
    val accent = CategoryPalette.Language

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Palabras Conectadas",
            description = "Une las letras de la rueda arrastrando el dedo para formar palabras. " +
                "Cada palabra que descubras se ilumina arriba.",
            accent = accent,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            startLabel = "Empezar",
            onStart = { vm.onIntent(WordConnectIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
        )
        return
    }

    LifecycleResumeEffect(Unit) {
        vm.onIntent(WordConnectIntent.Resume)
        onPauseOrDispose { vm.onIntent(WordConnectIntent.Pause) }
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WordConnectHud(
                level = game.level,
                score = game.score,
                solved = game.correctWords,
                total = game.slots.size,
                combo = game.combo,
                accent = accent,
            )

            // Ranuras de palabras: ocupan el espacio libre y hacen scroll si no caben.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                game.slots.forEach { slot ->
                    WordSlotRow(slot = slot, accent = accent)
                    Spacer(Modifier.height(8.dp))
                }
            }

            CurrentWordBoard(
                word = game.currentWord,
                accent = accent,
                feedbackTick = game.feedbackTick,
                outcome = game.lastOutcome,
            )

            Spacer(Modifier.height(16.dp))

            LetterWheel(
                letters = game.letters,
                selection = game.selection,
                accent = accent,
                onBegin = { vm.onIntent(WordConnectIntent.BeginTrace) },
                onExtend = { vm.onIntent(WordConnectIntent.ExtendTrace(it)) },
                onEnd = { vm.onIntent(WordConnectIntent.EndTrace) },
            )

            Spacer(Modifier.height(8.dp))
        }

        FeedbackFlash(eventId = game.feedbackTick, result = game.lastOutcome)

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(WordConnectIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(WordConnectIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(WordConnectIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(WordConnectIntent.Pause) },
            onResume = { vm.onIntent(WordConnectIntent.Resume) },
            onExit = onExit,
            gameTitle = "Palabras Conectadas",
            helpText = "Une las letras de la rueda arrastrando el dedo para formar palabras y descubre todas las del panel.",
            accent = accent,
        )
    }
}

@Composable
private fun WordConnectHud(
    level: Int,
    score: Int,
    solved: Int,
    total: Int,
    combo: Int,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Nivel $level", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
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

/**
 * Una fila de casillas para una palabra objetivo. Oculta hasta resolverse (casillas
 * vacías); al resolverse revela las letras con un destello neón (flicker) y brillo.
 */
@Composable
private fun WordSlotRow(slot: WordSlotState, accent: Color) {
    val flicker = remember { Animatable(0f) }
    LaunchedEffect(slot.solvedAtTick) {
        if (slot.solvedAtTick == null) {
            flicker.snapTo(0f)
            return@LaunchedEffect
        }
        // Destello de encendido de neón al revelar la palabra.
        flicker.snapTo(0f)
        flicker.animateTo(1f, tween(90))
        flicker.animateTo(0.4f, tween(60))
        flicker.animateTo(1f, tween(130))
    }
    val glow = if (slot.solved) 0.35f + flicker.value * 0.65f else 0f

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        slot.answer.forEachIndexed { i, letter ->
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .then(
                        if (slot.solved) Modifier.shadow(
                            elevation = (10.dp.value * glow).dp,
                            shape = shape,
                            clip = false,
                            ambientColor = accent,
                            spotColor = accent,
                        ) else Modifier,
                    )
                    .clip(shape)
                    .background(
                        if (slot.solved) accent.copy(alpha = 0.22f * glow.coerceAtLeast(0.5f))
                        else LogicColors.SurfaceVariantDark.copy(alpha = 0.6f),
                    )
                    .border(
                        BorderStroke(
                            if (slot.solved) 1.5.dp else 1.dp,
                            if (slot.solved) accent.copy(alpha = glow.coerceAtLeast(0.7f))
                            else LogicColors.SurfaceVariantDark,
                        ),
                        shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (slot.solved) letter.toString() else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * "Pizarra" que muestra la palabra en construcción (lo que el jugador escribe al
 * arrastrar). En un fallo tiembla y se tiñe de rojo; con acierto se limpia sola.
 */
@Composable
private fun CurrentWordBoard(
    word: String,
    accent: Color,
    feedbackTick: Long,
    outcome: WordConnectOutcome?,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(feedbackTick) {
        if (outcome == WordConnectOutcome.WRONG) {
            // Sacudida corta lateral para comunicar el error sin bloquear.
            shake.snapTo(0f)
            repeat(3) {
                shake.animateTo(1f, tween(45))
                shake.animateTo(-1f, tween(45))
            }
            shake.animateTo(0f, tween(45))
        }
    }
    val tint = when (outcome) {
        WordConnectOutcome.WRONG -> LogicColors.Error
        else -> accent
    }
    Box(
        modifier = Modifier
            .height(52.dp)
            .offset { IntOffset((shake.value * 10).roundToInt(), 0) },
        contentAlignment = Alignment.Center,
    ) {
        if (word.isEmpty()) {
            Text(
                "Arrastra para unir letras",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        } else {
            Text(
                text = word,
                style = MaterialTheme.typography.headlineMedium,
                color = tint,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Rueda de letras arrastrable.
 *
 * Un único gesto (dedo abajo → recorrido → dedo arriba) construye el trazo: en cada
 * movimiento se busca el nodo bajo el dedo y se encadena vía [onExtend]. Un [Canvas]
 * dibuja la línea neón que conecta los nodos seleccionados (y un tramo hasta el dedo),
 * replicando el gesto de la imagen de referencia.
 */
@Composable
private fun LetterWheel(
    letters: List<WheelLetter>,
    selection: List<Int>,
    accent: Color,
    onBegin: () -> Unit,
    onExtend: (Int) -> Unit,
    onEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val wheelPx = with(density) { WheelSize.toPx() }
    val nodePx = with(density) { NodeSize.toPx() }
    // Radio del anillo: deja medio nodo + margen respecto al borde de la rueda.
    val ringPx = wheelPx / 2f - nodePx / 2f - with(density) { 6.dp.toPx() }
    val hitRadius = nodePx * 0.62f

    // Centros de cada nodo (px, relativos a la caja de la rueda). El primer nodo
    // arranca arriba (−90°) y el resto se reparte en círculo en sentido horario.
    val centers = remember(letters, wheelPx) {
        val n = letters.size.coerceAtLeast(1)
        letters.indices.map { i ->
            val angle = -PI / 2 + i * 2 * PI / n
            Offset(
                x = wheelPx / 2f + ringPx * cos(angle).toFloat(),
                y = wheelPx / 2f + ringPx * sin(angle).toFloat(),
            )
        }
    }

    // Posición actual del dedo (px) para el tramo de línea "en vuelo"; null si no arrastra.
    var pointer by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = Modifier
            .size(WheelSize)
            .pointerInput(centers) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onBegin()
                    pointer = down.position
                    nodeAt(down.position, centers, hitRadius)?.let(onExtend)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        pointer = change.position
                        nodeAt(change.position, centers, hitRadius)?.let(onExtend)
                        change.consume()
                    }
                    onEnd()
                    pointer = null
                }
            },
    ) {
        // Base circular sutil de la rueda (superficie elevada, no compite por atención).
        Box(
            modifier = Modifier
                .size(WheelSize)
                .clip(CircleShape)
                .background(LogicColors.SurfaceDark.copy(alpha = 0.55f))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.25f)), CircleShape),
        )

        // Línea neón que conecta el trazo. Se dibuja bajo los nodos.
        Canvas(modifier = Modifier.size(WheelSize)) {
            if (selection.isEmpty()) return@Canvas
            val points = selection.map { centers[it] }
            // Halo ancho translúcido + trazo brillante fino encima (efecto neón).
            fun stroke(from: Offset, to: Offset) {
                drawLine(accent.copy(alpha = 0.25f), from, to, strokeWidth = nodePx * 0.34f, cap = StrokeCap.Round)
                drawLine(accent, from, to, strokeWidth = nodePx * 0.16f, cap = StrokeCap.Round)
            }
            for (k in 1 until points.size) stroke(points[k - 1], points[k])
            pointer?.let { stroke(points.last(), it) }
        }

        // Nodos de letras.
        letters.forEachIndexed { i, letter ->
            val selected = i in selection
            WheelNode(
                letter = letter.char,
                selected = selected,
                accent = accent,
                modifier = Modifier.offset {
                    IntOffset(
                        x = (centers[i].x - nodePx / 2f).roundToInt(),
                        y = (centers[i].y - nodePx / 2f).roundToInt(),
                    )
                },
            )
        }
    }
}

@Composable
private fun WheelNode(
    letter: Char,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // Resorte al seleccionar: crece y se ilumina; da la sensación táctil "con peso".
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeScale",
    )
    val glowElevation by animateFloatAsState(
        targetValue = if (selected) 16f else 3f,
        animationSpec = spring(),
        label = "nodeGlow",
    )
    Box(
        modifier = modifier
            .size(NodeSize)
            .scale(scale)
            .shadow(glowElevation.dp, CircleShape, clip = false, ambientColor = accent, spotColor = accent)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    if (selected) listOf(accent, accent.copy(alpha = 0.85f))
                    else listOf(accent.copy(alpha = 0.9f), LogicColors.SurfaceDark),
                ),
            )
            .border(
                BorderStroke(if (selected) 2.dp else 1.2.dp, accent.copy(alpha = if (selected) 1f else 0.6f)),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (selected) LogicColors.BackgroundDark else LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun FeedbackFlash(eventId: Long, result: WordConnectOutcome?) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(eventId) {
        // Solo destellan acierto/error; repetir palabra no genera flash.
        if (eventId == 0L || result == null || result == WordConnectOutcome.REPEAT) return@LaunchedEffect
        alpha.snapTo(0.24f)
        alpha.animateTo(0f, tween(380))
    }
    if (alpha.value <= 0f || result == null) return
    val color = if (result == WordConnectOutcome.CORRECT) LogicColors.Success else LogicColors.Error
    Box(modifier = Modifier.fillMaxSize().background(color.copy(alpha = alpha.value)))
}

/**
 * Índice del nodo cuyo centro está a menos de [hitRadius] px de [position], o null.
 * Es la traducción de la posición del dedo a "sobre qué letra estoy".
 */
private fun nodeAt(position: Offset, centers: List<Offset>, hitRadius: Float): Int? {
    var best = -1
    var bestDist = hitRadius
    centers.forEachIndexed { i, c ->
        val d = hypot(position.x - c.x, position.y - c.y)
        if (d <= bestDist) {
            bestDist = d
            best = i
        }
    }
    return best.takeIf { it >= 0 }
}
