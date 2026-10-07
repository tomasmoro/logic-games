package com.kortexgames.app.game.hypergate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.NeonProgressBar
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.drawEdgeFlash
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.hypergate_hint
import kortexgames.shared.generated.resources.hypergate_hud_absorbed
import kortexgames.shared.generated.resources.hypergate_hud_crashed
import kortexgames.shared.generated.resources.hypergate_hud_time
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

/**
 * # HypergateScreen — renderizado radial (Fase 3)
 *
 * Pantalla del minijuego **Hypergate**. Es, por diseño, solo dos cosas (§3 del spec): un
 * **gestor de toques** (tap en cualquier parte → conmuta el escudo) y un **Canvas** que traduce
 * el estado polar del motor a píxeles. Sin botones genéricos ni emojis.
 *
 * ## Traducción polar → cartesiana (el corazón del render)
 * El motor mantiene cada proyectil como `(angleRad, distancePx)` respecto al centro (ver
 * [Projectile]); aquí se proyecta a pantalla una sola vez por frame y proyectil:
 * `x = centro.x + cos(angleRad) * distancePx`, `y = centro.y + sin(angleRad) * distancePx`.
 * Concentrar toda la trigonometría en el render (y no en la física) es lo que justifica el
 * modelo polar: el tick es O(n) sin `cos/sin`, y el coste trigonométrico se paga aquí, donde de
 * todos modos hay que tocar la GPU.
 *
 * ## Escena y feedback
 * Todo se pinta en **un solo Canvas** con las funciones de `HypergateArt`: el túnel de velocidad
 * del fondo, la marca de dónde impactará el cometa más cercano, los cometas, el portal y los
 * impactos. Un único reloj ([rememberBoardClock]) mueve las animaciones y se lee solo al dibujar.
 *
 * El portal responde a cada toque (rebote + onda) y cada impacto tiene su efecto en el punto del
 * anillo donde ocurrió, con su "+N"; un choque sacude la escena y la tiñe de rojo por los bordes.
 * Los aciertos seguidos forman una **racha** que el núcleo del portal enseña y que aviva su giro:
 * es solo visual (la puntuación la lleva el motor), pero da al jugador algo que proteger.
 */
@Composable
fun HypergateScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: HypergateViewModel = viewModel {
        HypergateViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de física.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.hypergate,
            title = "Hypergate",
            motif = GameMotif.HYPERGATE,
            description = "Toca en cualquier parte para alternar la polaridad del escudo. Haz que su color coincida con cada proyectil justo antes del impacto: iguala para absorber, falla y chocarás.",
            accent = CategoryPalette.Reflexes,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.HYPERGATE)
                vm.onIntent(HypergateIntent.Start)
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
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

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Reloj único de las animaciones (túnel, coronas del portal, impactos). Se congela fuera de
    // RUNNING: en pausa los cometas no avanzan, así que tampoco debe moverse el fondo.
    val clock = rememberBoardClock(running = state.status == GameStatus.RUNNING)

    // Color del escudo según su polaridad, con crossfade suave al conmutar.
    val shieldColor by animateColorAsState(
        targetValue = game.shield.toNeon(),
        animationSpec = tween(durationMillis = 140),
        label = "hypergateShieldColor",
    )

    // Instante del último cambio de polaridad: de su "edad" salen el rebote y la onda del portal.
    var toggleAt by remember { mutableFloatStateOf(-10f) }
    LaunchedEffect(game.shield) { toggleAt = clock.peek() }

    // --- Impactos ---------------------------------------------------------------------------
    // El motor no dice DÓNDE impactó cada proyectil (solo emite sonido/vibración y actualiza los
    // contadores), así que la pantalla lo deduce comparando la lista de proyectiles con la del
    // frame anterior: el que desaparece estando junto al anillo acaba de impactar, y si fue
    // absorbido o chocó se sabe por qué contador subió. Es adorno puro y así el motor no cambia.
    val impacts = remember { mutableStateListOf<GateImpact>() }
    val previous = remember { arrayOfNulls<HypergateState>(1) }
    var streak by remember { mutableIntStateOf(0) }
    var crashAt by remember { mutableFloatStateOf(-10f) }
    SideEffect {
        val before = previous[0]
        previous[0] = game
        if (before == null) return@SideEffect
        val now = clock.peek()
        impacts.removeAll { now - it.at > IMPACT_LIFE_SEC + 0.5f }
        val absorbed = game.absorbed - before.absorbed
        val crashed = game.crashed - before.crashed
        if (absorbed < 0 || crashed < 0) {
            // Partida nueva: fuera lo de la anterior.
            impacts.clear()
            streak = 0
            return@SideEffect
        }
        if (absorbed == 0 && crashed == 0) return@SideEffect
        val alive = game.projectiles.mapTo(HashSet()) { it.id }
        val gone = before.projectiles.filter { it.id !in alive }
        // Reparto de la puntuación del frame entre lo que impactó, para el "+N" flotante.
        var delta = game.score - before.score
        gone.forEachIndexed { i, p ->
            // Si en el mismo frame hubo de los dos tipos, cada uno se decide por su polaridad.
            val success = if (crashed == 0) true else if (absorbed == 0) false else p.required == before.shield
            impacts += GateImpact(
                id = p.id,
                angleRad = p.angleRad,
                required = p.required,
                success = success,
                at = now,
                scoreDelta = if (i == 0) delta else 0,
            )
            delta = 0
        }
        if (crashed > 0) {
            streak = 0
            crashAt = now
        } else {
            streak += absorbed
        }
    }

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos -> vm.onIntent(HypergateIntent.Tick(frameNanos)) }
        }
    }

    // Reporta el tamaño real al motor para situar spawns y colisiones.
    LaunchedEffect(viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            vm.onIntent(
                HypergateIntent.UpdateViewport(
                    widthPx = viewportSize.width.toFloat(),
                    heightPx = viewportSize.height.toFloat(),
                ),
            )
        }
    }

    val measurer = rememberTextMeasurer()
    val streakStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
    val deltaStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
    // La racha se mide una vez por valor, no en cada frame.
    val streakLayout = remember(streak) {
        if (streak >= STREAK_SHOWN_FROM) measurer.measure("x$streak", streakStyle) else null
    }
    val timeFraction = (game.remainingMs.toFloat() / ROUND_DURATION_MS).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                // Tap-anywhere: la posición del toque es irrelevante, solo conmuta el escudo.
                detectTapGestures { vm.onIntent(HypergateIntent.ToggleShield) }
            },
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        // Toda la escena en un Canvas: túnel → guía del próximo impacto → cometas → portal →
        // impactos → textos. El portal va en el mismo Canvas que los cometas (antes era una
        // capa aparte) para que impactos y anillo compartan centro y radio exactos.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Sacudida del choque: el golpe se siente en toda la escena.
                    val age = clock.seconds - crashAt
                    if (age in 0f..SHAKE_SEC) {
                        translationX = sin(age * 70f) * SHAKE_AMPLITUDE.toPx() * (1f - age / SHAKE_SEC)
                    }
                },
        ) {
            val now = clock.seconds
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val radius = game.shieldRadiusPx

            // El túnel acelera según se agota la ronda: la urgencia del final se ve en el fondo.
            drawWarpField(center, now, speed = 1f + 1.2f * (1f - timeFraction), tint = shieldColor)

            // Marca de impacto del cometa más cercano: el que hay que resolver ahora.
            val nearest = game.projectiles.minByOrNull { it.distancePx }
            if (nearest != null && radius > 0f) {
                val travel = (nearest.distancePx - radius).coerceAtLeast(0f)
                drawImpactMarker(
                    center = center,
                    radius = radius,
                    head = Offset(
                        center.x + cos(nearest.angleRad) * nearest.distancePx,
                        center.y + sin(nearest.angleRad) * nearest.distancePx,
                    ),
                    angleRad = nearest.angleRad,
                    color = nearest.required.toNeon(),
                    urgency = 1f - travel / (radius * MARKER_RANGE),
                )
            }

            // Cometas: polar → cartesiano.
            for (p in game.projectiles) {
                drawComet(
                    head = Offset(
                        x = center.x + cos(p.angleRad) * p.distancePx,
                        y = center.y + sin(p.angleRad) * p.distancePx,
                    ),
                    angleRad = p.angleRad,
                    required = p.required,
                    speedFactor = p.speedPx / REFERENCE_SPEED_PX,
                )
            }

            drawGate(
                center = center,
                radius = radius,
                shield = game.shield,
                color = shieldColor,
                time = now,
                toggleAge = now - toggleAt,
                crashAge = now - crashAt,
                streak = streak,
                showGlyph = streakLayout == null,
            )
            // Con racha, el núcleo enseña el multiplicador en vez del glifo.
            streakLayout?.let { layout ->
                drawText(
                    textLayoutResult = layout,
                    color = shieldColor,
                    topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
                )
            }

            impacts.forEach { impact ->
                val age = now - impact.at
                drawGateImpact(
                    center = center,
                    radius = radius,
                    angleRad = impact.angleRad,
                    color = impact.required.toNeon(),
                    success = impact.success,
                    age = age,
                    seed = impact.id.toInt(),
                )
                // "+N" (o la penalización) que sale del punto de impacto y se aleja apagándose.
                val p = age / FLOAT_TEXT_SEC
                if (impact.scoreDelta != 0 && p in 0f..1f) {
                    val layout = measurer.measure(
                        text = if (impact.scoreDelta > 0) "+${impact.scoreDelta}" else "${impact.scoreDelta}",
                        style = deltaStyle,
                    )
                    val distance = radius * (1.75f + 0.5f * p)
                    drawText(
                        textLayoutResult = layout,
                        color = (if (impact.success) impact.required.toNeon() else LogicColors.Error).copy(alpha = 1f - p * p),
                        topLeft = Offset(
                            center.x + cos(impact.angleRad) * distance - layout.size.width / 2f,
                            center.y + sin(impact.angleRad) * distance - layout.size.height / 2f,
                        ),
                    )
                }
            }

            // Destello rojo por los bordes al chocar.
            val crashAge = now - crashAt
            if (crashAge in 0f..EDGE_FLASH_SEC) drawEdgeFlash(LogicColors.Error, 1f - crashAge / EDGE_FLASH_SEC)
        }

        HypergateHud(
            score = game.score,
            absorbed = game.absorbed,
            crashed = game.crashed,
            remainingMs = game.remainingMs,
            timeFraction = timeFraction,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Cómo se juega, solo hasta el primer impacto: enseña sin estorbar y se va en cuanto el
        // jugador ya ha visto de qué va.
        AnimatedVisibility(
            visible = game.absorbed + game.crashed == 0 && state.status == GameStatus.RUNNING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = stringResource(Res.string.hypergate_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.80f), RoundedCornerShape(16.dp))
                    .border(1.dp, CategoryPalette.Reflexes.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(HypergateIntent.PlayAgain) },
                onExit = onExit,
                accent = CategoryPalette.Reflexes,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HypergateIntent.Pause) },
            onResume = { vm.onIntent(HypergateIntent.Resume) },
            onExit = onExit,
            gameTitle = "Hypergate",
            help = GameHelpContent.hypergate,
            accent = CategoryPalette.Reflexes,
        )
    }
}

/**
 * Un impacto reciente en el portal, deducido por la pantalla (ver el `SideEffect` de
 * [HypergateScreen]).
 *
 * @property id el del proyectil; sirve de semilla para que cada impacto chispee distinto.
 * @property angleRad ángulo polar por el que llegó: sitúa el efecto sobre el anillo.
 * @property required polaridad del proyectil (su color).
 * @property success absorbido (`true`) o choque.
 * @property at instante del reloj de la pantalla en que ocurrió.
 * @property scoreDelta puntos que sumó o restó, para el texto flotante; 0 = sin texto.
 */
private data class GateImpact(
    val id: Long,
    val angleRad: Float,
    val required: ShieldState,
    val success: Boolean,
    val at: Float,
    val scoreDelta: Int,
)

/**
 * HUD superior: puntuación, cronómetro y contadores.
 *
 * La **puntuación** va grande y late al sumar. El **tiempo** es una barra que se vacía más los
 * segundos en una píldora; en los últimos segundos ambos pasan a rojo y la píldora late, porque
 * en una ronda de 30 s "se acaba" es la información que cambia cómo juegas. Absorbidos y choques
 * son fichas pequeñas con icono. El título y la instrucción que antes ocupaban la cabecera se
 * quitaron: el título está en la antesala y la instrucción es una pista que desaparece sola.
 *
 * Deja libre la esquina superior derecha (el botón de pausa vive ahí).
 */
@Composable
private fun HypergateHud(
    score: Int,
    absorbed: Int,
    crashed: Int,
    remainingMs: Long,
    timeFraction: Float,
    modifier: Modifier = Modifier,
) {
    val accent = CategoryPalette.Reflexes
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val urgent = remainingMs <= URGENT_FROM_MS
    val timeColor = if (urgent) LogicColors.Error else accent

    val scorePop = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(score) }
    LaunchedEffect(score) {
        val grew = score > lastScore
        lastScore = score
        if (!grew) return@LaunchedEffect
        scorePop.snapTo(1.2f)
        scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    // Latido de la píldora del tiempo: una vez por segundo, solo en la recta final.
    val timeBeat = remember { Animatable(1f) }
    LaunchedEffect(seconds, urgent) {
        if (!urgent) return@LaunchedEffect
        timeBeat.snapTo(1.25f)
        timeBeat.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 76.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.headlineLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f).graphicsLayer {
                    scaleX = scorePop.value
                    scaleY = scorePop.value
                    // Crece desde la izquierda: el número está alineado a ese lado.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
            HudChip(KortexIcons.Shield, LogicColors.NeonGreen, "$absorbed", stringResource(Res.string.hypergate_hud_absorbed))
            HudChip(KortexIcons.Close, LogicColors.Error, "$crashed", stringResource(Res.string.hypergate_hud_crashed))
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = timeBeat.value
                        scaleY = timeBeat.value
                    }
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                    .border(1.5.dp, timeColor.copy(alpha = 0.7f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeonIcon(
                    icon = KortexIcons.Timer,
                    tint = timeColor,
                    size = 16.dp,
                    glow = false,
                    contentDescription = stringResource(Res.string.hypergate_hud_time),
                )
                Text(
                    text = "$seconds",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (urgent) LogicColors.Error else LogicColors.OnDark,
                    fontWeight = FontWeight.Black,
                    // Ancho mínimo: de "10" a "9" la píldora no debe encoger y mover a sus vecinas.
                    modifier = Modifier.widthIn(min = 18.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        NeonProgressBar(progress = timeFraction, color = timeColor, modifier = Modifier.fillMaxWidth())
    }
}

/** Ficha de contador del HUD: icono + cifra. El icono dice qué es sin gastar una etiqueta. */
@Composable
private fun HudChip(icon: ImageVector, tint: Color, value: String, description: String) {
    Row(
        modifier = Modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, LogicColors.SurfaceVariantDark, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 14.dp, glow = false, contentDescription = description)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark, fontWeight = FontWeight.Black)
    }
}

/** Racha de aciertos a partir de la que el núcleo del portal enseña el multiplicador. */
private const val STREAK_SHOWN_FROM = 2

/** Sacudida de la escena al chocar: duración y amplitud inicial. */
private const val SHAKE_SEC = 0.32f
private val SHAKE_AMPLITUDE = 8.dp

/** Duración del destello rojo de borde al chocar (s). */
private const val EDGE_FLASH_SEC = 0.4f

/** Vida del "+N" flotante de un impacto (s). */
private const val FLOAT_TEXT_SEC = 0.7f

/** Distancia (en radios del portal) desde la que la marca de impacto empieza a avivarse. */
private const val MARKER_RANGE = 5f

/** Velocidad de referencia (px/s) con la que se normaliza la estela de un cometa. */
private const val REFERENCE_SPEED_PX = 320f

/** Milisegundos restantes desde los que el cronómetro pasa a rojo y late. */
private const val URGENT_FROM_MS = 5_000L
