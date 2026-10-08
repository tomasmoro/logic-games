package com.kortexgames.app.game.neonpulse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.NeonProgressBar
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.drawEdgeFlash
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.neonpulse_banner_armored
import kortexgames.shared.generated.resources.neonpulse_banner_bomb
import kortexgames.shared.generated.resources.neonpulse_banner_move
import kortexgames.shared.generated.resources.neonpulse_banner_trap
import kortexgames.shared.generated.resources.neonpulse_fast
import kortexgames.shared.generated.resources.neonpulse_frenzy_call
import kortexgames.shared.generated.resources.neonpulse_frenzy_label
import kortexgames.shared.generated.resources.neonpulse_gameover_headline
import kortexgames.shared.generated.resources.neonpulse_hud_multiplier
import kortexgames.shared.generated.resources.neonpulse_hud_wave
import kortexgames.shared.generated.resources.neonpulse_intro_description
import kortexgames.shared.generated.resources.neonpulse_life
import kortexgames.shared.generated.resources.neonpulse_life_lost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * # Neon Pulse — pantalla
 *
 * Lienzo libre con los nodos del motor y un HUD superpuesto. La pantalla **no decide nada**:
 * resuelve el hit-testing del toque (qué nodo hay bajo el dedo) y pinta el estado; puntuación,
 * combo, frenesí, bombas y blindados son cosa del [NeonPulseViewModel].
 *
 * ## Que se sienta agresivo
 * El juego trata de ir rápido y arriesgar, y la pantalla lo empuja:
 *  - el fondo **late** desde el centro, más deprisa con cada horda, y en frenesí la pantalla
 *    entera se tiñe por los bordes;
 *  - cada nodo **avisa** cuando se le acaba el tiempo (tiembla y su anillo vira a blanco);
 *  - cada acierto suelta sus puntos en un texto que flota (con "¡RÁPIDO!" si lo fue), la bomba
 *    barre el lienzo con una onda y perder una vida sacude la pantalla.
 *
 * ## Forma además de color
 * Cada tipo de nodo tiene **silueta propia** (punto, aspa, placas de blindaje, púas, corazón):
 * coral y rojo están muy cerca, y con el lienzo lleno hay que decidir en milisegundos.
 *
 * Las animaciones de ambiente salen de un único reloj ([rememberBoardClock]) leído solo al
 * dibujar; las de eventos puntuales (estallidos) son [Burst] con su propio `Animatable`, porque
 * nacen de efectos one-shot y mueren solas.
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
    // Sacudida de la pantalla (bomba o vida perdida): 0→1, con la amplitud de quien la pidió.
    val shake = remember { Animatable(1f) }
    var shakeAmplitude by remember { mutableFloatStateOf(0f) }
    // Cartel "¡FRENESÍ!" al dispararse.
    var frenzyCall by remember { mutableIntStateOf(0) }

    fun launchBurst(burst: Burst, millis: Int) {
        bursts += burst
        scope.launch {
            // Expansión rápida y retirada: no se acumulan objetos.
            burst.progress.animateTo(1f, tween(millis, easing = FastOutSlowInEasing))
            bursts.remove(burst)
        }
    }
    fun launchShake(amplitude: Float) {
        shakeAmplitude = amplitude
        scope.launch {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
        }
    }

    // Traducción de efectos one-shot → audio/háptica/animación. Un único colector.
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when (effect) {
                is NeonPulseEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonPulseEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
                // Horda superada: golpe de audio + háptica de logro. El cartel con el
                // número lo pinta la propia pantalla a partir del estado.
                NeonPulseEffect.WaveCleared -> {
                    graph.audio.playSound(SoundEffect.LEVEL_UP)
                    graph.audio.hapticFeedback(HapticFeedback.SUCCESS)
                }
                is NeonPulseEffect.ShowComboAnim -> launchBurst(
                    Burst(effect.x, effect.y, effect.type, BurstKind.HIT, effect.points, effect.fast),
                    BURST_MS,
                )
                is NeonPulseEffect.ArmorCracked -> launchBurst(
                    Burst(effect.x, effect.y, NodeType.ARMORED, BurstKind.CRACK),
                    CRACK_MS,
                )
                is NeonPulseEffect.BombBlast -> {
                    launchBurst(Burst(effect.x, effect.y, NodeType.BOMB, BurstKind.BLAST), BLAST_MS)
                    launchShake(BLAST_SHAKE_DP)
                }
                NeonPulseEffect.FrenzyStarted -> {
                    frenzyCall++
                    graph.audio.hapticFeedback(HapticFeedback.SUCCESS)
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
            description = stringResource(Res.string.neonpulse_intro_description),
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

    // Reloj de ambiente. Solo se congela en pausa: al acabar la partida sigue, para que los
    // últimos estallidos y el destello de la vida perdida terminen bajo el diálogo.
    val clock = rememberBoardClock(running = state.status != GameStatus.PAUSED)

    // Vida perdida: sacudida + destello rojo de borde. El motor solo publica cuántas quedan.
    var lifeLostAt by remember { mutableFloatStateOf(-10f) }
    var lastLives by remember { mutableIntStateOf(state.lives) }
    LaunchedEffect(state.lives) {
        val before = lastLives
        lastLives = state.lives
        if (state.lives < before) {
            lifeLostAt = clock.peek()
            launchShake(LIFE_SHAKE_DP)
        }
    }

    // Estado más reciente para el hit-testing dentro de pointerInput sin recrear el
    // modificador en cada frame (pointerInput(Unit) se instala una sola vez).
    val nodes by rememberUpdatedState(state.activeNodes)
    val measurer = rememberTextMeasurer()
    val pointsStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
    val fastStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    val fastLabel = stringResource(Res.string.neonpulse_fast)
    val inFrenzy = state.frenzyMs > 0L

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
                .graphicsLayer {
                    val p = shake.value
                    if (p < 1f) {
                        translationX = sin(p * SHAKE_CYCLES * 2f * PI.toFloat()) * shakeAmplitude.dp.toPx() * (1f - p)
                    }
                }
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
            val now = clock.seconds
            val minDim = min(size.width, size.height)

            // El pulso de fondo se acelera con la horda y se desboca en frenesí.
            drawPulseField(
                time = now,
                tempo = (0.55f + 0.05f * state.wave).coerceAtMost(1.6f) * if (inFrenzy) 1.8f else 1f,
                tint = if (inFrenzy) LogicColors.Magenta else CategoryPalette.Reflexes,
                intensity = if (inFrenzy) 1f else 0.45f,
            )
            if (inFrenzy) drawEdgeFlash(LogicColors.Magenta, 0.30f + 0.12f * sin(now * 9f))

            state.activeNodes.forEach { node -> drawNode(node, minDim, now) }
            bursts.forEach { burst -> drawBurst(burst, minDim, measurer, pointsStyle, fastStyle, fastLabel) }

            val lostAge = now - lifeLostAt
            if (lostAge in 0f..EDGE_FLASH_SEC) drawEdgeFlash(LogicColors.Error, 1f - lostAge / EDGE_FLASH_SEC)
        }

        // HUD superior: puntuación y combo, vidas (corazones neón) y horda en curso.
        NeonPulseHud(
            score = state.score,
            lives = state.lives,
            wave = state.wave,
            waveProgress = if (state.waveNodesTotal == 0) {
                0f
            } else {
                state.waveNodesResolved.toFloat() / state.waveNodesTotal
            },
            combo = state.combo,
            multiplier = state.multiplier,
            frenzyMs = state.frenzyMs,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )

        // "¡FRENESÍ!": un golpe de texto al dispararse. `key` lo relanza si se recarga.
        FrenzyCall(trigger = frenzyCall, modifier = Modifier.align(Alignment.Center))

        // Cartel entre hordas: anuncia la oleada que entra y, en las hordas clave,
        // el cambio de reglas permanentes (bombas, trampas, blindados, movimiento) para que
        // la subida de dificultad se entienda en vez de sufrirse a ciegas. El corazón de
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
                headline = stringResource(Res.string.neonpulse_gameover_headline, state.wave.toString()),
                onPlayAgain = { vm.onIntent(NeonPulseIntent.PlayAgain) },
                onExit = onExit,
                accent = CategoryPalette.Reflexes,
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
 * **Pulso de fondo**: anillos que salen del centro de la pantalla y se pierden hacia los bordes,
 * como el latido de un monitor. Es el "pulso" del nombre del juego, y su ritmo es el de la
 * partida: la pantalla sube [tempo] con cada horda y lo dispara en frenesí.
 *
 * @param tempo latidos por segundo, aproximadamente.
 * @param intensity 0..1, presencia de los anillos (baja en juego normal: es ambiente).
 */
private fun DrawScope.drawPulseField(time: Float, tempo: Float, tint: Color, intensity: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val reach = maxOf(size.width, size.height) * 0.72f
    for (i in 0 until PULSE_RINGS) {
        val phase = (time * tempo * 0.45f + i / PULSE_RINGS.toFloat()) % 1f
        // Sale rápido y frena (onda que se disipa).
        val ease = 1f - (1f - phase) * (1f - phase)
        drawCircle(
            color = tint.copy(alpha = 0.22f * intensity * (1f - phase)),
            radius = reach * ease,
            center = center,
            style = Stroke(width = (1f + 3f * (1f - phase)).dp.toPx()),
        )
    }
}

/**
 * Dibuja un nodo: cristal oscuro con tubo de neón, la **silueta de su tipo** dentro y, alrededor,
 * su anillo de tiempo.
 *
 * - Entra con un rebote (sale de su edad, sin animación aparte).
 * - En el último tercio de su vida **avisa**: tiembla y el anillo de tiempo engorda y vira a
 *   blanco. La bomba no avisa: perderla no cuesta nada.
 *
 * @param minDim lado menor del lienzo en px: el radio del nodo es normalizado respecto a él.
 * @param time segundos del reloj de la pantalla (giro de las púas de la bomba, temblor).
 */
private fun DrawScope.drawNode(node: Node, minDim: Float, time: Float) {
    val color = node.type.accent()
    val ageMs = (node.totalLifeMs - node.remainingMs).toFloat()
    val pop = EaseOutBack.transform((ageMs / NODE_POP_MS).coerceIn(0f, 1f))
    // Urgencia 0..1 en el último tramo de vida; la bomba y el corazón no apremian.
    val punishes = node.type == NodeType.NORMAL || node.type == NodeType.ARMORED
    val urgency = if (punishes) ((URGENT_FROM - node.lifeFraction) / URGENT_FROM).coerceIn(0f, 1f) else 0f
    val jitter = if (urgency > 0f) sin(time * 85f + node.id) * minDim * 0.006f * urgency else 0f

    val center = Offset(node.x * size.width + jitter, node.y * size.height)
    val core = node.radius * minDim * pop
    if (core <= 0f) return
    val stroke = core * TUBE_STROKE_FRACTION

    // Cristal: oscuro, apenas teñido. El color lo pone el tubo.
    drawCircle(lerp(LogicColors.BackgroundDark, color, 0.16f), core, center)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.10f), color.copy(alpha = 0.40f)),
            center = center,
            radius = core,
        ),
        radius = core,
        center = center,
    )

    when (node.type) {
        // El corazón se dibuja como silueta de tubo neón dentro del mismo globo:
        // la forma (no solo el color) lo separa a simple vista de un objetivo.
        NodeType.HEART -> drawNeonHeart(center, core * HEART_SHAPE_FRACTION, color, stroke)
        else -> {
            // La trampa lleva el tubo a trazos: "esto no se toca" antes de mirar el color.
            val effect = if (node.type == NodeType.TRAP) {
                PathEffect.dashPathEffect(floatArrayOf(core * 0.42f, core * 0.30f), time * core * 0.9f)
            } else {
                null
            }
            drawCircle(color.copy(alpha = 0.24f), core, center, style = Stroke(stroke * 4.5f))
            drawCircle(color.copy(alpha = 0.50f), core, center, style = Stroke(stroke * 2.1f))
            drawCircle(color, core, center, style = Stroke(stroke, pathEffect = effect))
            drawCircle(Color.White.copy(alpha = 0.55f), core, center, style = Stroke(stroke * 0.4f, pathEffect = effect))
        }
    }

    // Silueta del tipo.
    when (node.type) {
        NodeType.NORMAL -> {
            drawCircle(color, core * 0.30f, center)
            drawCircle(Color.White.copy(alpha = 0.85f), core * 0.13f, center)
        }
        NodeType.TRAP -> {
            val arm = core * 0.38f
            val width = stroke * 1.1f
            drawLine(color, Offset(center.x - arm, center.y - arm), Offset(center.x + arm, center.y + arm), width, StrokeCap.Round)
            drawLine(color, Offset(center.x + arm, center.y - arm), Offset(center.x - arm, center.y + arm), width, StrokeCap.Round)
        }
        NodeType.ARMORED -> {
            if (node.hitsLeft > 1) {
                // Blindaje intacto: seis placas alrededor del tubo.
                val plate = core * 1.30f
                for (i in 0 until 6) {
                    drawArc(
                        color = color,
                        startAngle = i * 60f + 8f,
                        sweepAngle = 44f,
                        useCenter = false,
                        topLeft = Offset(center.x - plate, center.y - plate),
                        size = Size(plate * 2f, plate * 2f),
                        style = Stroke(width = stroke * 1.5f, cap = StrokeCap.Butt),
                    )
                }
            } else {
                // Blindaje roto: tres grietas donde estaban las placas.
                for (i in 0 until 3) {
                    val angle = i * 2.1f + 0.4f
                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = Offset(center.x + cos(angle) * core * 0.45f, center.y + sin(angle) * core * 0.45f),
                        end = Offset(center.x + cos(angle + 0.35f) * core * 0.95f, center.y + sin(angle + 0.35f) * core * 0.95f),
                        strokeWidth = stroke * 0.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // Un punto por toque que le queda.
            val gap = core * 0.34f
            val first = center.x - gap * (node.hitsLeft - 1) / 2f
            for (i in 0 until node.hitsLeft) drawCircle(color, core * 0.15f, Offset(first + gap * i, center.y))
        }
        NodeType.BOMB -> {
            // Púas que giran alrededor de un núcleo que late.
            for (i in 0 until 8) {
                val angle = time * 2.4f + i * (PI.toFloat() / 4f)
                drawLine(
                    color = color,
                    start = Offset(center.x + cos(angle) * core * 0.34f, center.y + sin(angle) * core * 0.34f),
                    end = Offset(center.x + cos(angle) * core * 0.66f, center.y + sin(angle) * core * 0.66f),
                    strokeWidth = stroke * 0.8f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(lerp(color, Color.White, 0.5f + 0.4f * sin(time * 10f)), core * 0.24f, center)
        }
        NodeType.HEART -> Unit
    }

    // Anillo de tiempo: parte grande (fracción 1) y se cierra hacia el tubo
    // (fracción 0). Cuando lo toca, el motor ya lo habrá expirado.
    val ringRadius = core + core * RING_SPAN * node.lifeFraction
    drawCircle(
        color = lerp(color, Color.White, 0.75f * urgency).copy(alpha = 0.85f),
        radius = ringRadius,
        center = center,
        style = Stroke(width = RING_STROKE_DP.dp.toPx() * (1f + 0.9f * urgency)),
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


/** Color de acento por tipo de nodo (siempre desde la paleta del tema, §9.2). */
private fun NodeType.accent(): Color = when (this) {
    NodeType.NORMAL -> LogicColors.Coral
    NodeType.TRAP -> LogicColors.Error
    NodeType.HEART -> LogicColors.NeonGreen
    NodeType.ARMORED -> LogicColors.Violet
    NodeType.BOMB -> LogicColors.Amber
}

/**
 * Dibuja un estallido según su [BurstKind]:
 *  - `HIT`: chispas radiales + onda, y los puntos ganados flotando hacia arriba (con el rótulo de
 *    "rápido" debajo si lo fue). Es lo que convierte cada toque en una recompensa visible;
 *  - `CRACK`: un chispazo corto y blanco, el del blindaje al romperse (aún no hay premio);
 *  - `BLAST`: la onda de la bomba, que barre el lienzo entero con un fogonazo inicial.
 */
private fun DrawScope.drawBurst(
    burst: Burst,
    minDim: Float,
    measurer: TextMeasurer,
    pointsStyle: TextStyle,
    fastStyle: TextStyle,
    fastLabel: String,
) {
    val p = burst.progress.value
    val center = Offset(burst.x * size.width, burst.y * size.height)
    val base = NeonPulseConfig.NODE_RADIUS * minDim
    val color = burst.type.accent()
    val alpha = 1f - p

    if (burst.kind == BurstKind.BLAST) {
        val reach = hypot(size.width, size.height)
        val ease = 1f - (1f - p) * (1f - p)
        if (p < 0.25f) drawRect(Color.White.copy(alpha = 0.35f * (1f - p / 0.25f)))
        drawCircle(color.copy(alpha = 0.30f * alpha), reach * ease, center, style = Stroke(base * 2.4f * alpha + 1f))
        drawCircle(Color.White.copy(alpha = 0.85f * alpha), reach * ease, center, style = Stroke(base * 0.35f * alpha + 1f))
        drawCircle(color.copy(alpha = 0.6f * alpha), reach * ease * 0.72f, center, style = Stroke(base * 0.5f * alpha + 1f))
        return
    }

    val small = burst.kind == BurstKind.CRACK
    val sparkColor = if (small) Color.White else color
    burst.sparkAngles.forEachIndexed { i, angleDeg ->
        val angle = angleDeg * (PI.toFloat() / 180f)
        val dir = Offset(cos(angle), sin(angle))
        val travel = base * (0.9f + SPARK_TRAVEL * p) * burst.sparkLengths[i] * if (small) 0.55f else 1f
        val start = center + dir * (base * 0.5f + travel * 0.4f)
        val end = center + dir * (base * 0.5f + travel)
        drawLine(
            color = sparkColor.copy(alpha = alpha * 0.95f),
            start = start,
            end = end,
            strokeWidth = SPARK_WIDTH_DP.dp.toPx() * (1f - p * 0.5f),
            cap = StrokeCap.Round,
        )
    }
    if (small) return

    // Onda expansiva que acompaña a las chispas (retenida del diseño previo).
    drawCircle(
        color = color.copy(alpha = alpha * 0.7f),
        radius = base * (1f + BURST_GROWTH * p),
        center = center,
        style = Stroke(width = RING_STROKE_DP.dp.toPx()),
    )

    // Puntos flotantes.
    if (burst.points > 0) {
        val rise = base * (1.2f + 1.6f * p)
        val layout = measurer.measure("+${burst.points}", pointsStyle)
        drawText(
            textLayoutResult = layout,
            color = (if (burst.fast) LogicColors.Amber else LogicColors.OnDark).copy(alpha = 1f - p * p),
            topLeft = Offset(center.x - layout.size.width / 2f, center.y - rise - layout.size.height),
        )
        if (burst.fast) {
            val tag = measurer.measure(fastLabel, fastStyle)
            drawText(
                textLayoutResult = tag,
                color = LogicColors.Amber.copy(alpha = 1f - p * p),
                topLeft = Offset(center.x - tag.size.width / 2f, center.y - rise),
            )
        }
    }
}

/** Qué clase de estallido es un [Burst]; decide cómo se dibuja. */
private enum class BurstKind { HIT, CRACK, BLAST }

/**
 * Estallido en curso: posición normalizada, tipo del nodo que lo causó (su color) y avance.
 *
 * @property points puntos a mostrar flotando (0 = sin texto).
 * @property fast si fue un acierto rápido: se destaca en ámbar con su rótulo.
 */
private class Burst(
    val x: Float,
    val y: Float,
    val type: NodeType,
    val kind: BurstKind,
    val points: Int = 0,
    val fast: Boolean = false,
) {
    val progress: Animatable<Float, *> = Animatable(0f)

    // Ángulos y alcances fijos por estallido: sorteados una vez, no en cada frame.
    val sparkAngles: List<Float> = List(SPARK_COUNT) { i ->
        (360f / SPARK_COUNT) * i + Random.nextFloat() * 18f - 9f
    }
    val sparkLengths: List<Float> = List(SPARK_COUNT) { 0.75f + Random.nextFloat() * 0.5f }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * HUD superior.
 *
 * - **Puntuación** arriba a la izquierda, que late al sumar, con el **multiplicador de combo**
 *   debajo: una insignia con su barrita de avance hacia el siguiente escalón. El combo ya
 *   existía en el motor pero no se enseñaba, así que el jugador no sabía que encadenar aciertos
 *   multiplicaba los puntos ni cuándo lo perdía.
 * - **Vidas**, horda y su progreso, centrados.
 * - **Frenesí**: mientras dura, una barra magenta que se vacía bajo el bloque central.
 *
 * La esquina superior derecha queda libre para el botón de pausa.
 */
@Composable
private fun NeonPulseHud(
    score: Int,
    lives: Int,
    wave: Int,
    waveProgress: Float,
    combo: Int,
    multiplier: Int,
    frenzyMs: Long,
    modifier: Modifier = Modifier,
) {
    val scorePop = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(score) }
    LaunchedEffect(score) {
        val grew = score > lastScore
        lastScore = score
        if (!grew) return@LaunchedEffect
        scorePop.snapTo(1.22f)
        scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    scaleX = scorePop.value
                    scaleY = scorePop.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
            ComboBadge(combo = combo, multiplier = multiplier, frenzy = frenzyMs > 0L)
        }
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
                text = stringResource(Res.string.neonpulse_hud_wave, wave.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
            )
            val animated by animateFloatAsState(
                targetValue = waveProgress.coerceIn(0f, 1f),
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "waveProgress",
            )
            NeonProgressBar(
                progress = animated,
                color = LogicColors.Coral,
                modifier = Modifier.padding(top = 4.dp).width(WAVE_BAR_WIDTH_DP.dp),
            )
            AnimatedVisibility(visible = frenzyMs > 0L, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.neonpulse_frenzy_label,
                            NeonPulseConfig.FRENZY_MULTIPLIER.toString(),
                        ),
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
                        color = LogicColors.Magenta,
                        fontWeight = FontWeight.Black,
                    )
                    NeonProgressBar(
                        progress = frenzyMs.toFloat() / NeonPulseConfig.FRENZY_MS,
                        color = LogicColors.Magenta,
                        modifier = Modifier.padding(top = 2.dp).width(FRENZY_BAR_WIDTH_DP.dp),
                    )
                }
            }
        }
    }
}

/**
 * Insignia del multiplicador de combo, con una barrita que marca cuánto falta para el siguiente
 * escalón. Late en cada acierto, se "calienta" (coral → ámbar → magenta en frenesí) y se apaga
 * de golpe al romperse la racha: el jugador ve lo que acaba de perder.
 */
@Composable
private fun ComboBadge(combo: Int, multiplier: Int, frenzy: Boolean) {
    val beat = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        if (combo <= 0) return@LaunchedEffect
        beat.snapTo(1.25f)
        beat.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val color = when {
        frenzy -> LogicColors.Magenta
        multiplier >= 3 -> LogicColors.Amber
        multiplier >= 2 -> LogicColors.Coral
        else -> LogicColors.OnDarkMuted
    }
    val step = NeonPulseConfig.COMBO_STEP
    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = beat.value
                scaleY = beat.value
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .border(1.5.dp, color.copy(alpha = if (multiplier > 1 || frenzy) 0.85f else 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(Res.string.neonpulse_hud_multiplier, multiplier.toString()),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Black,
        )
        NeonProgressBar(
            progress = (combo % step).toFloat() / step,
            color = color,
            modifier = Modifier.width(COMBO_BAR_WIDTH_DP.dp),
        )
    }
}

/**
 * Golpe de texto "¡FRENESÍ!" en el centro al dispararse (o recargarse) el frenesí: entra grande,
 * se asienta y se desvanece solo. No bloquea nada: es un grito, no un cartel.
 *
 * @param trigger contador que sube en cada disparo; 0 = nunca.
 */
@Composable
private fun FrenzyCall(trigger: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FRENZY_CALL_MS, easing = LinearEasing))
    }
    val p = progress.value
    if (p >= 1f) return
    Text(
        text = stringResource(Res.string.neonpulse_frenzy_call),
        style = MaterialTheme.typography.displayLarge,
        color = LogicColors.Magenta,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        modifier = modifier.graphicsLayer {
            val scale = 1.6f - 0.6f * EaseOutBack.transform((p * 4f).coerceAtMost(1f))
            scaleX = scale
            scaleY = scale
            alpha = if (p < 0.6f) 1f else 1f - (p - 0.6f) / 0.4f
        },
    )
}

/**
 * Cartel de transición entre hordas: el número de la que entra y, solo en las hordas
 * que estrenan una regla, una línea que la explica.
 *
 * El corazón de rescate **no** se anuncia: solo cae si al jugador le falta una vida, y
 * prometerlo aquí sería mentir a quien llega con las vidas completas.
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
                text = stringResource(Res.string.neonpulse_hud_wave, wave.toString()),
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.OnDark,
            )
            val note = waveNote(wave)
            if (note != null) {
                Text(
                    text = stringResource(note.first),
                    style = MaterialTheme.typography.titleMedium,
                    color = note.second,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
                )
            }
        }
    }
}

/**
 * Aviso de la regla que estrena la horda [wave] (texto y color del tipo de nodo que llega), o
 * `null` si esa horda no cambia nada. Los umbrales salen de [NeonPulseConfig], no de literales.
 */
private fun waveNote(wave: Int): Pair<StringResource, Color>? = when (wave) {
    NeonPulseConfig.BOMB_UNLOCK_WAVE -> Res.string.neonpulse_banner_bomb to NodeType.BOMB.accent()
    NeonPulseConfig.TRAP_UNLOCK_WAVE -> Res.string.neonpulse_banner_trap to NodeType.TRAP.accent()
    NeonPulseConfig.ARMORED_UNLOCK_WAVE -> Res.string.neonpulse_banner_armored to NodeType.ARMORED.accent()
    NeonPulseConfig.MOVE_UNLOCK_WAVE -> Res.string.neonpulse_banner_move to LogicColors.Coral
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
            contentDescription = if (alive) null else stringResource(Res.string.neonpulse_life_lost),
        )
        // Corazón relleno + halo, atados a la misma opacidad, por encima del contorno.
        if (fillAlpha > 0f) {
            NeonIcon(
                icon = KortexIcons.Heart,
                tint = LogicColors.Coral,
                size = HeartGlyph,
                glow = true,
                contentDescription = if (alive) stringResource(Res.string.neonpulse_life) else null,
                modifier = Modifier.scale(fillScale).alpha(fillAlpha),
            )
        }
    }
}


// --- Constantes de render (no de balance; el balance vive en NeonPulseConfig) ----

/** Factor de tolerancia del toque: el radio de acierto es un 35% mayor que el del
 *  nodo dibujado, para que el juego perdone toques al borde. */
private const val TOUCH_SLOP = 1.35f

/** Tamaño inicial del anillo de tiempo, en radios del núcleo por encima de él. */
private const val RING_SPAN = 1.8f

/** Grosor del anillo de tiempo y de la onda del estallido. */
private const val RING_STROKE_DP = 3f

/** Duración de un estallido de acierto (ms): deja leer los puntos flotantes. */
private const val BURST_MS = 620

/** Duración del chispazo de blindaje roto (ms). */
private const val CRACK_MS = 260

/** Duración de la onda de la bomba (ms). */
private const val BLAST_MS = 520

/** Cuánto crece la onda del estallido respecto al radio del nodo. */
private const val BURST_GROWTH = 2.5f

/** Grosor del trazo del tubo neón del nodo, como fracción de su radio. */
private const val TUBE_STROKE_FRACTION = 0.22f

/** Chispas por estallido, su recorrido (en radios) y su grosor. */
private const val SPARK_COUNT = 8
private const val SPARK_TRAVEL = 2.2f
private const val SPARK_WIDTH_DP = 2.5f

/** Tamaño de la silueta del corazón como fracción del radio del nodo. */
private const val HEART_SHAPE_FRACTION = 0.85f

/** Lo que tarda un nodo en "saltar" a su tamaño al aparecer (ms). */
private const val NODE_POP_MS = 170f

/** Fracción de vida por debajo de la cual un nodo empieza a avisar. */
private const val URGENT_FROM = 0.34f

/** Anillos del pulso de fondo. */
private const val PULSE_RINGS = 4

/** Sacudida de pantalla: duración, oscilaciones y amplitud (dp) según el motivo. */
private const val SHAKE_MS = 320
private const val SHAKE_CYCLES = 3f
private const val LIFE_SHAKE_DP = 9f
private const val BLAST_SHAKE_DP = 6f

/** Duración del destello rojo de borde al perder una vida (s). */
private const val EDGE_FLASH_SEC = 0.45f

/** Vida del grito de "¡FRENESÍ!" (ms). */
private const val FRENZY_CALL_MS = 900

/** Anchos de las barras del HUD. */
private const val WAVE_BAR_WIDTH_DP = 96f
private const val FRENZY_BAR_WIDTH_DP = 120f
private const val COMBO_BAR_WIDTH_DP = 44f
