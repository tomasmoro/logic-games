package com.example.kortexgames.game.neonpulse

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameMotif
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.game.GameHelpContent
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.SpaceBackdrop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Texto y descripción de la antesala; reutilizado también como ayuda en pausa. */
private const val NEON_PULSE_HELP =
    "Toca los nodos coral antes de que su anillo se cierre. ¡No toques los rojos! " +
        "Dura 30 s y cada vez aparecen más rápido."

/**
 * # Neon Pulse — Motor gráfico (Compose, FASE 3)
 *
 * Pantalla del entrenador visomotor. Todo el lienzo de juego se pinta en **un solo
 * [Canvas]** (alto rendimiento: cero recomposición por nodo, cero `@Composable`
 * anidados) y el toque se resuelve por geometría en [pointerInput] —no hay botones
 * de Compose por nodo—.
 *
 * El bucle de juego lo conduce el [NeonPulseViewModel] (FASE 2); esta pantalla solo
 * **observa el estado** y **traduce los efectos** one-shot a sonido/háptica y a la
 * animación de explosión.
 *
 * @param graph grafo de DI (repos, audio, settings).
 * @param onExit callback para volver al catálogo de juegos.
 */
@Composable
fun NeonPulseScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: NeonPulseViewModel = viewModel {
        NeonPulseViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Explosiones activas: cada acierto añade una que crece y se desvanece. Se
    // gestionan en la UI (no en el estado del juego) porque son puro adorno visual
    // y su ciclo de vida es independiente de la simulación.
    val bursts = remember { mutableStateListOf<Burst>() }
    val scope = rememberCoroutineScope()

    // Traducción de efectos one-shot → audio/háptica/animación. Un único colector.
    LaunchedEffectCollect(vm) { effect ->
        when (effect) {
            is NeonPulseEffect.PlaySound -> graph.audio.playSound(effect.sound)
            is NeonPulseEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
            is NeonPulseEffect.ShowComboAnim -> {
                val burst = Burst(effect.x, effect.y, Animatable(0f))
                bursts += burst
                scope.launch {
                    // Expansión rápida (450 ms) y retirada: no se acumulan objetos.
                    burst.progress.animateTo(1f, tween(BURST_MS, easing = FastOutSlowInEasing))
                    bursts.remove(burst)
                }
            }
        }
    }

    // Antesala (intro) mientras el juego está en IDLE, igual que el resto de juegos.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.neonPulse,
            title = "Neon Pulse",
            motif = GameMotif.NEON_PULSE,
            description = NEON_PULSE_HELP,
            accent = CategoryPalette.Reflexes,
            onStart = { vm.onIntent(NeonPulseIntent.Start) },
            onExit = onExit,
            background = {
                SpaceBackdrop(modifier = Modifier.fillMaxSize())
            },
        )
        return
    }

    // Estado más reciente para el hit-testing dentro de pointerInput sin recrear el
    // modificador en cada frame (pointerInput(Unit) se instala una sola vez).
    val nodes by rememberUpdatedState(state.activeNodes)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        // Fondo espacial ambiental (mismo componente que Polarity Collision): da
        // atmósfera de "campo de energía" sin competir con los nodos jugables.
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Hit-testing de baja latencia: en cuanto hay un tap, buscamos
                    // (de arriba hacia abajo, por eso `lastOrNull`) el nodo cuyo
                    // círculo contiene el punto. Coincidencia → TapNode(id); vacío
                    // → TapMiss. La conversión normalizado→px usa el tamaño real.
                    detectTapGestures { pos ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val minDim = min(w, h)
                        val hit = nodes.lastOrNull { node ->
                            val cx = node.x * w
                            val cy = node.y * h
                            // Radio de acierto algo mayor que el visual (TOUCH_SLOP)
                            // para que el juego perdone toques al borde del nodo.
                            val hitR = node.radius * minDim * TOUCH_SLOP
                            hypot(pos.x - cx, pos.y - cy) <= hitR
                        }
                        if (hit != null) {
                            vm.onIntent(NeonPulseIntent.TapNode(hit.id))
                        } else {
                            vm.onIntent(NeonPulseIntent.TapMiss)
                        }
                    }
                },
        ) {
            val minDim = min(size.width, size.height)
            state.activeNodes.forEach { node -> drawNode(node, minDim) }
            bursts.forEach { burst -> drawBurst(burst, minDim) }
        }

        // HUD superior: puntuación, tiempo restante y vidas (corazones neón).
        NeonPulseHud(
            score = state.score,
            remainingMs = state.remainingMs,
            lives = state.lives,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(NeonPulseIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú común (Reanudar / audio / ayuda / Salir).
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(NeonPulseIntent.Pause) },
            onResume = { vm.onIntent(NeonPulseIntent.Resume) },
            onExit = onExit,
            gameTitle = "Neon Pulse",
            help = GameHelpContent.neonPulse,
            accent = CategoryPalette.Reflexes,
        )
    }
}

// ---------------------------------------------------------------------------
// Dibujo en Canvas
// ---------------------------------------------------------------------------

/**
 * Pinta un [Node] como **globo de tubo neón hueco** —el mismo lenguaje visual que
 * `drawNeonBubble`/`drawNeonTile` (Memoria, Crucigrama): relleno de cristal tenue,
 * halo ancho translúcido, halo intermedio, aro nítido y núcleo blanco— más el
 * **anillo de tiempo** que se contrae por encima. No reutiliza `drawNeonBubble`
 * directamente porque ese helper asume que ocupa todo el `DrawScope`; aquí el nodo
 * es un punto dentro de un lienzo compartido, así que la geometría se calcula a mano
 * con el mismo apilado de trazos.
 *
 * Todo se dibuja con primitivas de [DrawScope] (sin capas Compose) para mantener el
 * coste por frame mínimo. El color sale del tema (coral = normal, [LogicColors.Error]
 * = trampa); nunca se hardcodea (CLAUDE.md §9.2).
 *
 * @param minDim menor dimensión del lienzo en px; base para escalar radios de forma
 *   uniforme (así los nodos no se deforman por la relación de aspecto).
 */
private fun DrawScope.drawNode(node: Node, minDim: Float) {
    val center = Offset(node.x * size.width, node.y * size.height)
    val core = node.radius * minDim
    val color = if (node.type == NodeType.TRAP) LogicColors.Error else LogicColors.Coral
    val stroke = core * TUBE_STROKE_FRACTION

    // Relleno "cristal": tinte radial suave, más brillante arriba, igual que las
    // burbujas de Cálculo Mental.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.34f),
                color.copy(alpha = 0.12f),
                color.copy(alpha = 0.03f),
            ),
            center = Offset(center.x, center.y - core * 0.12f),
            radius = core * 1.25f,
        ),
        radius = core,
        center = center,
    )
    // Halo exterior ancho y translúcido (identidad neón del diseño).
    drawCircle(color.copy(alpha = 0.26f), core, center, style = Stroke(stroke * 4.5f))
    // Halo intermedio: da cuerpo al resplandor.
    drawCircle(color.copy(alpha = 0.50f), core, center, style = Stroke(stroke * 2.1f))
    // Aro nítido del "tubo" neón.
    drawCircle(color, core, center, style = Stroke(stroke))
    // Núcleo blanco interior del tubo (look "prendido" del neón real).
    drawCircle(Color.White.copy(alpha = 0.55f), core, center, style = Stroke(stroke * 0.4f))

    // Anillo de tiempo: parte grande (fracción 1) y se cierra hacia el tubo
    // (fracción 0). Cuando lo toca, el motor ya lo habrá expirado.
    val ringRadius = core + core * RING_SPAN * node.lifeFraction
    drawCircle(
        color = color.copy(alpha = 0.85f),
        radius = ringRadius,
        center = center,
        style = Stroke(width = RING_STROKE_DP.dp.toPx()),
    )
}

/**
 * Pinta una explosión de acierto: **chispas** que salen disparadas radialmente desde
 * el nodo (mismo lenguaje que `drawTileSparks` de Memoria, pero en círculo completo
 * para celebrar el acierto) más un anillo de onda expansiva que las acompaña. Todo
 * se desvanece según [Burst.progress] (`0` recién nacida → `1` disuelta).
 */
private fun DrawScope.drawBurst(burst: Burst, minDim: Float) {
    val p = burst.progress.value
    val center = Offset(burst.x * size.width, burst.y * size.height)
    val base = NeonPulseConfig.NODE_RADIUS * minDim
    val color = LogicColors.Coral
    val alpha = 1f - p

    burst.sparkAngles.forEachIndexed { i, angleDeg ->
        val angle = angleDeg * (PI.toFloat() / 180f)
        val dir = Offset(cos(angle), sin(angle))
        val travel = base * (0.9f + SPARK_TRAVEL * p) * burst.sparkLengths[i]
        val start = center + dir * (base * 0.5f + travel * 0.4f)
        val end = center + dir * (base * 0.5f + travel)
        drawLine(
            color = color.copy(alpha = alpha * 0.95f),
            start = start,
            end = end,
            strokeWidth = SPARK_WIDTH_DP.dp.toPx() * (1f - p * 0.5f),
            cap = StrokeCap.Round,
        )
    }

    // Onda expansiva que acompaña a las chispas (retenida del diseño previo).
    drawCircle(
        color = color.copy(alpha = alpha * 0.7f),
        radius = base * (1f + BURST_GROWTH * p),
        center = center,
        style = Stroke(width = RING_STROKE_DP.dp.toPx()),
    )
}

/**
 * Animación efímera de acierto. [x]/[y] en espacio normalizado `[0f..1f]`;
 * [progress] avanza de 0 (recién nacida) a 1 (desvanecida), momento en que la UI
 * la retira de la lista. Los ángulos/longitudes de chispa se fijan una sola vez al
 * crear el burst (no en cada frame) para que cada chispa vuele en línea recta.
 */
private class Burst(val x: Float, val y: Float, val progress: Animatable<Float, *>) {
    val sparkAngles: List<Float> = List(SPARK_COUNT) { i ->
        (360f / SPARK_COUNT) * i + Random.nextFloat() * 18f - 9f
    }
    val sparkLengths: List<Float> = List(SPARK_COUNT) { 0.75f + Random.nextFloat() * 0.5f }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * Cabecera de juego: puntuación a la izquierda, tiempo al centro y vidas
 * (corazones neón) a la derecha. Los corazones "gastados" se pintan como contorno
 * sin brillo para comunicar la pérdida de un vistazo.
 */
@Composable
private fun NeonPulseHud(
    score: Int,
    remainingMs: Long,
    lives: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Puntuación arriba a la izquierda: no compite con las vidas/tiempo, que
        // quedan centrados para leerse de un vistazo sin mover la vista.
        Text(
            text = "$score",
            style = MaterialTheme.typography.headlineMedium,
            color = LogicColors.OnDark,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Vidas centradas arriba y, justo debajo, el tiempo restante: agrupa las dos
        // señales de "cuánto me queda" (vida/tiempo) en el eje central del HUD.
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(NeonPulseConfig.INITIAL_LIVES) { i ->
                    NeonPulseHeart(alive = i < lives)
                }
            }
            Text(
                text = "${(remainingMs / 1000).coerceAtLeast(0)}s",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.OnDarkMuted,
            )
        }
    }
}

/**
 * Un corazón del HUD de vidas. Antes el icono/tinte/halo cambiaban de golpe al
 * perder una vida (salto brusco entre `Favorite` y `FavoriteBorder`); aquí se
 * cruza con [Crossfade] y se añade un "pop" de escala (spring) al apagarse, para
 * que la pérdida se lea como una animación y no como un parpadeo.
 */
@Composable
private fun NeonPulseHeart(alive: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(alive) {
        if (!alive) {
            scale.snapTo(1.35f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            )
        }
    }
    Crossfade(targetState = alive, animationSpec = tween(180), modifier = Modifier.scale(scale.value)) { isAlive ->
        NeonIcon(
            icon = if (isAlive) KortexIcons.Heart else KortexIcons.HeartOutline,
            tint = if (isAlive) LogicColors.Coral else LogicColors.OnDarkMuted,
            size = 22.dp,
            glow = isAlive,
            contentDescription = if (isAlive) "Vida" else "Vida perdida",
        )
    }
}

/**
 * Colector del `Flow` de efectos del ViewModel en un único [androidx.compose.runtime.LaunchedEffect].
 * Se extrae a helper para mantener el cuerpo de la pantalla legible.
 */
@Composable
private fun LaunchedEffectCollect(
    vm: NeonPulseViewModel,
    onEffect: suspend (NeonPulseEffect) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(vm) {
        vm.effect.collect { onEffect(it) }
    }
}

// --- Constantes de render (no de balance; el balance vive en NeonPulseConfig) ----

/** Multiplicador del radio de acierto sobre el visual: perdona toques al borde. */
private const val TOUCH_SLOP = 1.35f

/** Cuánto se extiende el anillo sobre el núcleo cuando el nodo está recién nacido. */
private const val RING_SPAN = 1.8f

/** Grosor de anillos y explosiones (dp). */
private const val RING_STROKE_DP = 3f

/** Duración de la animación de explosión (ms). */
private const val BURST_MS = 450

/** Cuánto crece la onda expansiva respecto al radio base del nodo. */
private const val BURST_GROWTH = 2.5f

/** Grosor del tubo neón del nodo, como fracción de su radio de núcleo. */
private const val TUBE_STROKE_FRACTION = 0.22f

/** Número de chispas que dispara cada acierto. */
private const val SPARK_COUNT = 8

/** Cuánto se alargan las chispas a medida que avanza la explosión. */
private const val SPARK_TRAVEL = 2.2f

/** Grosor de cada chispa (dp). */
private const val SPARK_WIDTH_DP = 2.5f
