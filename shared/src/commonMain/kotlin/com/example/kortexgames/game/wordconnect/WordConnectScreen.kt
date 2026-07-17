package com.example.kortexgames.game.wordconnect

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.example.kortexgames.ui.components.drawNeonBubble
import com.example.kortexgames.ui.components.drawNeonTile
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tamaño del anillo y de cada ficha en función de la cantidad de letras del nivel.
 * Con pocas letras (4) el círculo queda compacto; con más letras crece lo justo para
 * que los nodos no se amontonen. Siempre queda más pequeño que el tamaño fijo anterior
 * y proporcional a [letterCount] (pedido del usuario).
 */
private fun wheelSizeFor(letterCount: Int): Dp {
    val n = letterCount.coerceIn(3, 9)
    return 186.dp + 11.dp * (n - 3)
}

private fun nodeSizeFor(letterCount: Int): Dp = when {
    letterCount <= 4 -> 56.dp
    letterCount <= 6 -> 50.dp
    else -> 44.dp
}

/**
 * Fondo de **pared de ladrillos** para la pantalla (pedido del usuario), en tonos del
 * tema en vez de un marrón que rompería la identidad "azul noche" (§9.1): ladrillos
 * alternos en [LogicColors.SurfaceDark]/[LogicColors.SurfaceVariantDark] con junta en
 * [LogicColors.BackgroundDark], hiladas a soga con traba (offset de media pieza por
 * fila). Un viñeteado radial oscurece los bordes para que la rejilla nunca compita con
 * las ranuras de palabras ni la rueda de letras (§9.1: superficie oscura, acento escaso).
 */
private fun DrawScope.drawBrickWall() {
    drawRect(LogicColors.BackgroundDark)

    val brickW = 84.dp.toPx()
    val brickH = 32.dp.toPx()
    val mortar = 3.dp.toPx()
    val corner = CornerRadius(2.dp.toPx())

    val rows = (size.height / brickH).toInt() + 2
    val cols = (size.width / brickW).toInt() + 3
    for (row in -1..rows) {
        val rowOffset = if (row % 2 == 0) 0f else -brickW / 2f
        val y = row * brickH
        for (col in -1..cols) {
            val x = rowOffset + col * brickW
            // Ligera variación de tono por ladrillo (patrón determinista, sin random
            // por frame) para que la pared no se vea como una textura repetida plana.
            val shade = ((row * 31 + col * 17) % 5) / 5f
            val brickColor = lerp(LogicColors.SurfaceVariantDark, LogicColors.SurfaceDark, shade)
            drawRoundRect(
                color = brickColor,
                topLeft = Offset(x + mortar / 2f, y + mortar / 2f),
                size = Size(brickW - mortar, brickH - mortar),
                cornerRadius = corner,
            )
        }
    }

    // Viñeteado: mantiene el centro (donde vive la UI) más oscuro que los bordes.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, LogicColors.BackgroundDark.copy(alpha = 0.6f)),
            center = Offset(size.width / 2f, size.height * 0.42f),
            radius = size.maxDimension * 0.8f,
        ),
    )
}

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

    Box(modifier = Modifier.fillMaxSize().drawBehind { drawBrickWall() }) {
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
 * Una fila de casillas para una palabra objetivo, con la **misma estética de tubo
 * neón que el Crucigrama** ([drawNeonTile]): oculta y hueca mientras no se resuelve;
 * al acertarla, un parpadeo irregular la "engancha" encendida (igual que
 * [com.example.kortexgames.game.crucigrama.CrucigramaNeonScreen]'s `GridCell`), con
 * una respiración sutil continua y una ráfaga de chispas que salen del centro.
 */
@Composable
private fun WordSlotRow(slot: WordSlotState, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        slot.answer.forEachIndexed { i, letter ->
            WordSlotCell(letter = letter, solved = slot.solved, solvedAtTick = slot.solvedAtTick, accent = accent, seed = i)
        }
    }
}

@Composable
private fun WordSlotCell(letter: Char, solved: Boolean, solvedAtTick: Long?, accent: Color, seed: Int) {
    val shape = RoundedCornerShape(12.dp)
    val ignition = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }
    LaunchedEffect(solvedAtTick) {
        if (solvedAtTick == null) {
            ignition.snapTo(if (solved) 1f else 0f)
            spark.snapTo(0f)
            return@LaunchedEffect
        }
        // Chispas en paralelo al parpadeo de encendido.
        launch {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(560, easing = LinearEasing))
        }
        // Parpadeo tipo tubo de neón que titila y "engancha" (mismo lenguaje que el
        // Crucigrama): apaga y prende de forma irregular hasta quedar encendido.
        ignition.snapTo(0f)
        ignition.animateTo(0.85f, tween(55))
        ignition.animateTo(0.08f, tween(45))
        ignition.animateTo(0.7f, tween(35))
        ignition.animateTo(0.05f, tween(60))
        ignition.animateTo(1f, tween(150))
    }

    // Respiración lenta y de baja amplitud una vez encendida (§9.4: bucles sutiles).
    val breath by rememberInfiniteTransition(label = "slotBreath").animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "slotBreathValue",
    )
    val activeAmt = if (solved) (ignition.value * (0.6f + 0.4f * breath)).coerceIn(0f, 1f) else 0f
    val tileColor = if (solved) accent else LogicColors.SurfaceVariantDark

    Box(
        modifier = Modifier
            .size(42.dp)
            .drawBehind {
                drawNeonTile(tileColor, activeAmt, cornerRadius = 12.dp, sparks = false, baseMargin = 7.dp, strokeScale = 0.6f)

                // Chispas propias de la casilla: partículas radiales desde el centro
                // que se apagan según avanza [spark] (idéntico patrón al Crucigrama).
                val sp = spark.value
                if (sp > 0f && sp < 1f) {
                    val count = 6
                    val angleSeed = seed * 1.7f
                    val dist = size.minDimension * (0.3f + 0.75f * sp)
                    val fade = 1f - sp
                    val dot = (2.2f * (1f - sp * 0.4f)).dp.toPx()
                    for (i in 0 until count) {
                        val ang = angleSeed + i * (2f * PI.toFloat() / count)
                        drawCircle(
                            color = accent.copy(alpha = fade),
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
            text = if (solved) letter.toString() else "",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
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
    // El anillo y las fichas se dimensionan según la cantidad de letras del nivel
    // (más pequeño y proporcional que el tamaño fijo anterior).
    val wheelSize = remember(letters.size) { wheelSizeFor(letters.size) }
    val nodeSize = remember(letters.size) { nodeSizeFor(letters.size) }
    val wheelPx = with(density) { wheelSize.toPx() }
    val nodePx = with(density) { nodeSize.toPx() }
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
            .size(wheelSize)
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
                .size(wheelSize)
                .clip(CircleShape)
                .background(LogicColors.SurfaceDark.copy(alpha = 0.55f))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.25f)), CircleShape),
        )

        // Línea neón que conecta el trazo. Se dibuja bajo los nodos.
        Canvas(modifier = Modifier.size(wheelSize)) {
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
                nodeSize = nodeSize,
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

/**
 * Ficha de letra con la estética de **globo de neón** ([drawNeonBubble], la misma
 * fuente de bordes neón que usan las burbujas de Cálculo Mental): un aro de tubo
 * encendido con relleno de cristal, en vez del degradado/borde ad-hoc anterior. Al
 * seleccionarse, un resorte la agranda y el halo se intensifica ([glow]) para dar la
 * sensación táctil "con peso" (§9.4); un relleno adicional refuerza el look "prendido".
 */
@Composable
private fun WheelNode(
    letter: Char,
    selected: Boolean,
    accent: Color,
    nodeSize: Dp,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "nodeScale",
    )
    val glow by animateFloatAsState(
        targetValue = if (selected) 1.7f else 1f,
        animationSpec = spring(),
        label = "nodeGlow",
    )
    Box(
        modifier = modifier
            .size(nodeSize)
            .scale(scale)
            .drawBehind {
                drawNeonBubble(accent, glow = glow)
                // Relleno extra al seleccionar: refuerza el "encendido" del globo.
                if (selected) {
                    drawCircle(accent.copy(alpha = 0.4f), radius = size.minDimension / 2f - 6.dp.toPx())
                }
            }
            .clip(CircleShape),
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
