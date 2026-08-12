package com.kortexgames.app.game.neonpulse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
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
        "Partida infinita por hordas: cada una es más rápida y solo te frenan tus vidas."

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
            // Horda superada: golpe de audio + háptica de logro. El cartel con el
            // número lo pinta la propia pantalla a partir del estado.
            NeonPulseEffect.WaveCleared -> {
                graph.audio.playSound(SoundEffect.LEVEL_UP)
                graph.audio.hapticFeedback(HapticFeedback.SUCCESS)
            }
            is NeonPulseEffect.ShowComboAnim -> {
                val burst = Burst(effect.x, effect.y, effect.type, Animatable(0f))
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
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_PULSE)
                vm.onIntent(NeonPulseIntent.Start)
            },
            onExit = onExit,
            background = {
                SpaceBackdrop(modifier = Modifier.fillMaxSize())
            },
            // Comparativa mundial del jugador, ANTES de jugar (mismo panel que el
            // diálogo de fin de partida): pedido explícito para que la antesala
            // también responda "¿cómo me va?". Sin selector de dificultad —el juego
            // rankea en una tabla única—, así que solo hay que resolver el panel.
            configContent = {
                val preview = state.rankingPreview
                when {
                    state.rankingPreviewLoading -> WorldRankingLoading()
                    preview != null -> WorldRankingPreviewPanel(ranking = preview)
                    else -> RankingPreviewUnavailable(difficultyLabel = null)
                }
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

        // HUD superior: puntuación, vidas (corazones neón) y horda en curso.
        NeonPulseHud(
            score = state.score,
            lives = state.lives,
            wave = state.wave,
            waveProgress = if (state.waveNodesTotal == 0) {
                0f
            } else {
                state.waveNodesResolved.toFloat() / state.waveNodesTotal
            },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )

        // Cartel entre hordas: anuncia la oleada que entra y, en las hordas clave,
        // el cambio de reglas permanentes (movimiento, trampas) para que la subida
        // de dificultad se entienda en vez de sufrirse a ciegas. El corazón de
        // rescate deliberadamente NO se anuncia aquí (ver KDoc de NeonPulseWaveBanner).
        NeonPulseWaveBanner(
            wave = state.wave,
            visible = state.waveBannerMs > 0L && state.status != GameStatus.FINISHED,
            modifier = Modifier.align(Alignment.Center),
        )

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                // En una partida infinita el titular útil no es "fin de partida"
                // sino hasta dónde llegaste: la horda es la marca a batir.
                headline = "Horda ${state.wave} alcanzada",
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

        // Segunda oportunidad: al agotar las vidas (una vez por partida) se ofrece
        // revivir con una vida extra viendo un anuncio. Componente reutilizable común
        // (mismo trato que Neon 2048 / Burbujas de Cálculo). Se emite EL ÚLTIMO para
        // quedar por encima del botón de pausa —el estado sigue en RUNNING mientras
        // se decide— y bloquear el lienzo con su propio scrim.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(NeonPulseIntent.Revive) },
                onDecline = { vm.onIntent(NeonPulseIntent.DeclineRevive) },
                rewardLabel = "una vida extra",
                accent = CategoryPalette.Reflexes,
                audio = graph.audio,
            )
        }
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
    val color = node.type.accent()
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

    if (node.type == NodeType.HEART) {
        // El corazón se dibuja como silueta de tubo neón dentro del mismo globo:
        // la forma (no solo el color) lo separa a simple vista de un objetivo, que
        // es lo crítico cuando el lienzo va lleno y hay que decidir en milisegundos.
        drawNeonHeart(center, core * HEART_SHAPE_FRACTION, color, stroke)
    } else {
        // Halo exterior ancho y translúcido (identidad neón del diseño).
        drawCircle(color.copy(alpha = 0.26f), core, center, style = Stroke(stroke * 4.5f))
        // Halo intermedio: da cuerpo al resplandor.
        drawCircle(color.copy(alpha = 0.50f), core, center, style = Stroke(stroke * 2.1f))
        // Aro nítido del "tubo" neón.
        drawCircle(color, core, center, style = Stroke(stroke))
        // Núcleo blanco interior del tubo (look "prendido" del neón real).
        drawCircle(Color.White.copy(alpha = 0.55f), core, center, style = Stroke(stroke * 0.4f))
    }

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
 * Silueta de corazón pintada con el **mismo apilado de capas** que el resto del
 * neón del proyecto (halo ancho → halo intermedio → trazo nítido → núcleo blanco,
 * CLAUDE.md §9.7). No usa `drawNeonTile` porque ese helper dibuja contornos
 * redondeados de tile, no un `Path` arbitrario; sí replica sus proporciones para
 * que el brillo case con el de los nodos.
 *
 * @param center centro geométrico del corazón, en px.
 * @param r radio de referencia (la figura se inscribe en un cuadrado de lado `2r`).
 * @param color acento del corazón (verde neón = vida).
 * @param stroke grosor base del tubo, en px.
 */
private fun DrawScope.drawNeonHeart(center: Offset, r: Float, color: Color, stroke: Float) {
    val path = heartPath(center, r)
    drawPath(path, color.copy(alpha = 0.26f), style = Stroke(stroke * 4.5f, cap = StrokeCap.Round))
    drawPath(path, color.copy(alpha = 0.50f), style = Stroke(stroke * 2.1f, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(stroke * 0.4f, cap = StrokeCap.Round))
}

/**
 * Construye el contorno del corazón con dos cúbicas simétricas: de la punta
 * inferior sube por el lóbulo izquierdo hasta el valle central y baja de vuelta
 * por el derecho. Se genera a demanda (es una figura barata) en vez de cachearse:
 * como mucho hay un corazón en pantalla cada cinco hordas.
 */
private fun heartPath(center: Offset, r: Float): Path {
    val w = r * 2f
    val h = r * 2f
    val cx = center.x
    val cy = center.y
    return Path().apply {
        moveTo(cx, cy + h * 0.36f)
        cubicTo(
            cx - w * 0.62f, cy + h * 0.04f,
            cx - w * 0.48f, cy - h * 0.46f,
            cx, cy - h * 0.12f,
        )
        cubicTo(
            cx + w * 0.48f, cy - h * 0.46f,
            cx + w * 0.62f, cy + h * 0.04f,
            cx, cy + h * 0.36f,
        )
        close()
    }
}

/**
 * Color de acento de cada tipo de nodo. Centralizado aquí para que el nodo, su
 * anillo de tiempo y su explosión siempre hablen del mismo color (CLAUDE.md §9.2:
 * el color es semántico, no decorativo).
 */
private fun NodeType.accent(): Color = when (this) {
    NodeType.NORMAL -> LogicColors.Coral
    NodeType.TRAP -> LogicColors.Error
    NodeType.HEART -> LogicColors.NeonGreen
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
    val color = burst.type.accent()
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
 * la retira de la lista. [type] tiñe las chispas con el color del nodo impactado
 * (coral en un objetivo, verde en un corazón). Los ángulos/longitudes de chispa se
 * fijan una sola vez al crear el burst (no en cada frame) para que cada chispa
 * vuele en línea recta.
 */
private class Burst(
    val x: Float,
    val y: Float,
    val type: NodeType,
    val progress: Animatable<Float, *>,
) {
    val sparkAngles: List<Float> = List(SPARK_COUNT) { i ->
        (360f / SPARK_COUNT) * i + Random.nextFloat() * 18f - 9f
    }
    val sparkLengths: List<Float> = List(SPARK_COUNT) { 0.75f + Random.nextFloat() * 0.5f }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * Cabecera de juego: puntuación a la izquierda y, centradas, las vidas (corazones
 * neón) con la horda en curso y su progreso justo debajo. Los corazones "gastados"
 * se pintan como contorno sin brillo para comunicar la pérdida de un vistazo.
 *
 * Sustituye a la cuenta atrás del diseño anterior: la partida es infinita, así que
 * la pregunta "¿cuánto me queda?" ya no la responde un reloj sino las vidas, y la
 * de "¿cuánto llevo?" la responde el número de horda.
 *
 * Se dibujan siempre [NeonPulseConfig.MAX_LIVES] huecos —no solo las vidas
 * iniciales— para que el corazón de rescate tenga un sitio visible al que llegar y
 * el HUD no cambie de anchura al recogerlo.
 *
 * @param waveProgress fracción `[0f..1f]` de nodos ya resueltos de la horda.
 */
@Composable
private fun NeonPulseHud(
    score: Int,
    lives: Int,
    wave: Int,
    waveProgress: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Puntuación arriba a la izquierda: no compite con las vidas/horda, que
        // quedan centradas para leerse de un vistazo sin mover la vista.
        Text(
            text = "$score",
            style = MaterialTheme.typography.headlineMedium,
            color = LogicColors.OnDark,
            modifier = Modifier.align(Alignment.TopStart),
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(NeonPulseConfig.MAX_LIVES) { i ->
                    NeonPulseHeart(alive = i < lives)
                }
            }
            Text(
                text = "HORDA $wave",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
            )
            NeonPulseWaveProgress(progress = waveProgress)
        }
    }
}

/**
 * Barra fina de progreso de la horda. Se anima con `spring` (CLAUDE.md §9.4: la
 * física de resorte para lo que responde al jugador) para que cada nodo resuelto
 * empuje la barra en vez de saltarla, y se mantiene discreta —2 dp y baja opacidad—
 * porque es información de apoyo, no el foco del lienzo.
 */
@Composable
private fun NeonPulseWaveProgress(progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "waveProgress",
    )
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(WAVE_BAR_WIDTH_DP.dp)
            .height(WAVE_BAR_HEIGHT_DP.dp)
            .clip(CircleShape)
            .background(LogicColors.SurfaceVariantDark),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(CircleShape)
                .background(LogicColors.Coral),
        )
    }
}

/**
 * Cartel de entrada de horda. Aparece con fundido + escala (spring) durante
 * [NeonPulseConfig.WAVE_BANNER_MS] y es el respiro entre oleadas: comunica el
 * número de horda y, cuando esa horda estrena una **regla nueva**, la avisa en una
 * línea (para que la subida de dificultad se entienda, no se sufra a ciegas).
 *
 * El corazón de rescate deliberadamente NO se anuncia aquí aunque también sea una
 * regla que puede estrenar la horda: es un premio de aparición condicional (solo si
 * falta vida) y anticiparlo lo convertiría en una promesa —"en esta horda te curas
 * seguro"— que el motor no siempre cumple, además de restarle sorpresa al hallazgo.
 * Las reglas permanentes (trampas, movimiento) sí se avisan porque cambian cómo se
 * juega TODAS las hordas siguientes.
 */
@Composable
private fun NeonPulseWaveBanner(
    wave: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.8f),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "HORDA $wave",
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.OnDark,
            )
            val note = waveNote(wave)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.Coral,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Aviso de la regla permanente que estrena la horda [wave], o `null` si no estrena
 * ninguna. Solo se anuncia la horda exacta del desbloqueo: repetirlo cada oleada
 * convertiría el cartel en ruido y dejaría de leerse.
 */
private fun waveNote(wave: Int): String? = when (wave) {
    NeonPulseConfig.TRAP_UNLOCK_WAVE -> "¡Cuidado con los nodos rojos!"
    NeonPulseConfig.MOVE_UNLOCK_WAVE -> "¡Los pulsos empiezan a moverse!"
    else -> null
}

/** Tamaño del glifo del corazón dentro de su slot. */
private val HeartGlyph = 22.dp

/** Slot fijo de cada corazón (incluye el hueco del halo) para que la posición NO
 *  cambie según el estado: `NeonIcon` con `glow = true` mide `size * 1.9` (el halo),
 *  bastante más que `size` sin halo, así que dejar que el `Row` mida cada corazón
 *  por su propio contenido desalineaba verticalmente el corazón vivo (caja más alta
 *  por el halo) respecto a los apagados (caja del tamaño del glifo). El slot iguala
 *  ese `1.9×` para que el halo quepa justo sin desbordar hacia el corazón vecino.
 *  Mismo patrón que `BubbleMathScreen.LifeHeart`. */
private val HeartSlot = HeartGlyph * 1.9f

/**
 * Un corazón del HUD de vidas, con **posición estable**. El contorno (vida
 * perdida) está siempre presente y ocupa el mismo hueco; encima, el corazón
 * relleno + su halo se **desvanecen dando un pequeño "estallido"** (escala hacia
 * arriba mientras baja la opacidad) al perder la vida, en vez de cambiar de golpe
 * o desaparecer de golpe.
 */
@Composable
private fun NeonPulseHeart(alive: Boolean) {
    val fillAlpha by animateFloatAsState(
        targetValue = if (alive) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "heartAlpha",
    )
    val fillScale by animateFloatAsState(
        targetValue = if (alive) 1f else 1.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "heartScale",
    )

    Box(modifier = Modifier.size(HeartSlot), contentAlignment = Alignment.Center) {
        // Contorno base: marca el hueco de la vida, siempre visible y sin halo (no
        // se mueve ni cambia de tamaño con el resto de corazones).
        NeonIcon(
            icon = KortexIcons.HeartOutline,
            tint = LogicColors.OnDarkMuted,
            size = HeartGlyph,
            glow = false,
            contentDescription = if (alive) null else "Vida perdida",
        )
        // Corazón relleno + halo, atados a la misma opacidad, por encima del contorno.
        if (fillAlpha > 0f) {
            NeonIcon(
                icon = KortexIcons.Heart,
                tint = LogicColors.Coral,
                size = HeartGlyph,
                glow = true,
                contentDescription = if (alive) "Vida" else null,
                modifier = Modifier.scale(fillScale).alpha(fillAlpha),
            )
        }
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

/** Tamaño del corazón como fracción del radio del nodo: algo menor que el globo
 *  para que la silueta respire dentro del relleno de cristal. */
private const val HEART_SHAPE_FRACTION = 0.85f

/** Ancho de la barra de progreso de horda (dp). */
private const val WAVE_BAR_WIDTH_DP = 96f

/** Alto de la barra de progreso de horda (dp): fina, es información de apoyo. */
private const val WAVE_BAR_HEIGHT_DP = 3f
