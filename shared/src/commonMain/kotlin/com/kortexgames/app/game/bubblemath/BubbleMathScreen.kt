package com.kortexgames.app.game.bubblemath

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.kortexgames.app.ui.components.CitySkylineBackground
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonBubble
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kortexgames.app.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.bubble_cloud_missing_numbers
import kortexgames.shared.generated.resources.bubble_cloud_missing_symbols
import kortexgames.shared.generated.resources.bubble_cloud_result_failed
import kortexgames.shared.generated.resources.bubble_cloud_result_life
import kortexgames.shared.generated.resources.bubble_cloud_result_points
import kortexgames.shared.generated.resources.bubble_cloud_reward_life
import kortexgames.shared.generated.resources.bubble_cloud_reward_points
import kortexgames.shared.generated.resources.bubble_cloud_seconds
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Paleta de colores de las burbujas. Se asigna por id de burbuja (no por si es la
 * correcta): el color es puramente decorativo para variar la escena; **revelar la
 * respuesta con el color rompería el juego** (el reto es calcular, no mirar).
 */
private val BubbleColors = listOf(
    LogicColors.Blue,
    LogicColors.Violet,
    LogicColors.NeonCyan,
    LogicColors.Coral,
    LogicColors.Magenta,
    LogicColors.Amber,
)

/** Diámetro de una burbuja. Fijo para que el cálculo de posición sea simple. */
private val BubbleSize = 74.dp

/** 2π: círculo completo en radianes, para repartir las chispas en todas direcciones. */
private const val TAU = 6.2831855f

/** π: media vuelta. Da el impulso de ida y vuelta de un latido con un solo `sin`. */
private const val PI_F = 3.1415927f

/** Alto de la banda inferior donde vive el objetivo (el "suelo"). */
private val FloorBand = 104.dp

/**
 * Pantalla de "Burbujas de Cálculo". Caen burbujas con operaciones y, en la base,
 * se muestra el número objetivo; el jugador debe explotar la burbuja cuyo resultado
 * coincide antes de que toque el suelo.
 *
 * La física la lleva el motor; aquí solo se **mapea** la posición fraccional de cada
 * burbuja a píxeles (vía [BoxWithConstraints]) y se añade el "juice" visual (destello
 * de acierto/fallo, latido del combo). Se pausa/reanuda con el ciclo de vida para que
 * al volver de segundo plano las burbujas no aparezcan ya en el suelo.
 */
@Composable
fun BubbleMathScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: BubbleMathViewModel = viewModel {
        BubbleMathViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.bubbleMath,
            title = "Burbujas de Cálculo",
            description = "Revienta las burbujas con el resultado correcto antes de que toquen el suelo; encadena aciertos para subir el combo.",
            accent = CategoryPalette.MentalMath,
            motif = GameMotif.MATH_BUBBLES,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.BUBBLE_MATH)
                vm.onIntent(BubbleMathIntent.Start)
            },
            onExit = onExit,
            background = {
                CitySkylineBackground(
                    modifier = Modifier.fillMaxSize(),
                    accent = LogicColors.Violet,
                    intensity = 0.6f,
                )
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

    // Pausa la caída al ir a segundo plano y la reanuda al volver: si el bucle
    // siguiera corriendo, al regresar las burbujas estarían "pegadas" al suelo.
    LifecycleResumeEffect(Unit) {
        vm.onIntent(BubbleMathIntent.Resume)
        onPauseOrDispose { vm.onIntent(BubbleMathIntent.Pause) }
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Skyline de ciudad nocturna, muy sutil, detrás de todo el juego.
        CitySkylineBackground(
            modifier = Modifier.fillMaxSize(),
            accent = LogicColors.Violet,
            intensity = 0.6f,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            GameHud(round = game.round, score = game.score, combo = game.combo)

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
            ) {
                val fieldW = maxWidth
                val fieldH = maxHeight
                // Línea de suelo: por encima de la banda del objetivo. y=1 (fracción del
                // motor) se mapea a esta altura.
                val floorLine = fieldH - FloorBand

                // Cada burbuja: se posiciona por su centro y se hace pulsable. `key`
                // ata el estado de composición (animación de entrada) a la identidad de
                // la burbuja, para que al explotar un distractor las demás no "hereden"
                // el estado por su posición en la lista.
                game.bubbles.forEach { bubble ->
                    key(bubble.id) {
                        FallingBubble(
                            bubble = bubble,
                            fieldWidth = fieldW,
                            floorLine = floorLine,
                            onTap = { vm.onIntent(BubbleMathIntent.TapBubble(bubble.id)) },
                        )
                    }
                }

                // Objetivo en la base + línea de suelo.
                TargetBase(
                    target = game.target,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Chispas del estallido de la última burbuja tocada (encima de las burbujas):
                // muchas al acertar, pocas y rojas al fallar.
                BubbleBurstLayer(
                    burst = game.lastBurst,
                    eventId = game.eventId,
                    floorLine = floorLine,
                )

                // Destello de feedback a pantalla completa (verde acierto / rojo fallo).
                FeedbackFlash(eventId = game.eventId, result = game.lastResult)

                // Nube de ecuación: reto relámpago para recuperar una vida. Ocupa el
                // campo entero porque las fichas van abajo, justo donde vive el
                // objetivo; el motor la hace entrar con el tablero ya limpio, entre
                // una ronda y la siguiente.
                game.cloud?.let { cloud ->
                    EquationCloudOverlay(
                        cloud = cloud,
                        livesFull = game.lives >= BubbleMathState.MAX_LIVES,
                        onToken = { vm.onIntent(BubbleMathIntent.TapCloudToken(it)) },
                        onBlank = { vm.onIntent(BubbleMathIntent.TapCloudBlank(it)) },
                    )
                }
            }
        }

        // Vidas como corazones, ancladas arriba y centradas: el estado más crítico
        // del jugador (cuánto le queda) va en el punto de mayor foco de la pantalla.
        //
        // Recuperar una vida (nube de ecuación resuelta, o revivir por anuncio) es de
        // las cosas más celebrables del juego, así que el corazón que vuelve se marca
        // aquí para que se anuncie a lo grande y no aparezca sin más. Se recuerda el
        // valor anterior porque el estado solo trae "cuántas vidas hay", no "acabas de
        // ganar una".
        var previousLives by remember { mutableStateOf(game.lives) }
        var celebratedHeart by remember { mutableStateOf(-1) }
        LaunchedEffect(game.lives) {
            val gained = game.lives > previousLives
            previousLives = game.lives
            if (!gained) return@LaunchedEffect
            celebratedHeart = game.lives - 1
            delay(LifeGainDurationMs.toLong())
            celebratedHeart = -1
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(BubbleMathState.MAX_LIVES) { i ->
                LifeHeart(alive = i < game.lives, celebrate = i == celebratedHeart)
            }
        }
    }

    if (state.status == GameStatus.FINISHED && state.gameOver != null) {
        GameOverOverlay(
            info = state.gameOver!!,
            audio = graph.audio,
            onPlayAgain = { vm.onIntent(BubbleMathIntent.PlayAgain) },
            onExit = onExit,
        )
    }

    // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
    // Se oculta durante la oferta de revivir: no tiene sentido pausar mientras se
    // decide (y evita solapar el menú de pausa con el overlay de segunda oportunidad).
    if (game.phase != BubblePhase.REVIVE_OFFER) {
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(BubbleMathIntent.Pause) },
            onResume = { vm.onIntent(BubbleMathIntent.Resume) },
            onExit = onExit,
            gameTitle = "Burbujas de Cálculo",
            help = GameHelpContent.bubbleMath,
            accent = CategoryPalette.MentalMath,
        )
    }

    // Segunda oportunidad: al quedarse sin vidas (una vez por partida) se ofrece
    // revivir con una vida extra viendo un anuncio. Componente reutilizable común.
    // Se emite EL ÚLTIMO para quedar por encima del botón de pausa (el juego sigue en
    // RUNNING mientras se decide) y bloquear el tablero con su propio scrim.
    if (game.phase == BubblePhase.REVIVE_OFFER) {
        ReviveAdOverlay(
            adManager = graph.adManager,
            onRevive = { vm.onIntent(BubbleMathIntent.Revive) },
            onDecline = { vm.onIntent(BubbleMathIntent.DeclineRevive) },
            rewardLabel = "una vida extra",
            accent = CategoryPalette.MentalMath,
            audio = graph.audio,
        )
    }
}

/**
 * Barra superior con la ronda, el marcador y el combo (cuando ≥2, con latido). Las
 * vidas se muestran aparte, centradas arriba de toda la pantalla (ver
 * [BubbleMathScreen]), así que esta barra deja hueco a la derecha para no chocar
 * con ellas.
 */
@Composable
private fun GameHud(round: Int, score: Int, combo: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Ronda ${round.coerceAtLeast(1)}",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
            )
            Text(
                "$score",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
            )
        }

        // Combo: solo aparece a partir de x2 para que sea una recompensa notable.
        val comboScale by animateFloatAsState(
            targetValue = if (combo >= 2) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "comboScale",
        )
        if (comboScale > 0f) {
            Text(
                "x$combo",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.NeonGreen,
                fontWeight = FontWeight.Black,
                modifier = Modifier.scale(comboScale),
            )
        }
    }
}

/** Slot fijo de cada corazón (incluye el espacio del halo) para que la posición
 *  no cambie según el estado: así al perder una vida los demás no se mueven. */
private val HeartSlot = 40.dp

/** Tamaño del glifo del corazón dentro de su slot. */
private val HeartGlyph = 22.dp

/** Duración de la celebración de vida recuperada (ms). */
private const val LifeGainDurationMs = 950

/**
 * Un corazón de vida con **posición estable** y transición animada. El contorno
 * (vida perdida) está siempre presente y ocupa el mismo hueco; encima, el corazón
 * relleno + su halo se **desvanecen dando un pequeño "estallido"** (escala hacia
 * arriba mientras baja la opacidad) al perder la vida, en lugar de desaparecer de
 * golpe. Feedback visual inmediato, CLAUDE.md §9.4.
 *
 * @param celebrate true justo cuando ESTE corazón es el que se acaba de recuperar:
 *   dispara una vez la animación de premio (latido + onda expansiva + "+1"). Perder
 *   una vida se nota solo, pero recuperarla es raro y hay que **anunciarlo**.
 */
@Composable
private fun LifeHeart(alive: Boolean, celebrate: Boolean = false) {
    // Opacidad y escala del corazón relleno: al morir se apaga (0) y crece (1.4)
    // → efecto de "reventar". Al revivir (reintentar) vuelve con rebote.
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

    // Progreso 0→1 de la celebración. En reposo vale 0 (recién compuesto) o 1 (ya
    // terminada) y en ambos extremos no se dibuja nada: solo "vive" mientras corre.
    val gain = remember { Animatable(0f) }
    LaunchedEffect(celebrate) {
        if (!celebrate) return@LaunchedEffect
        gain.snapTo(0f)
        gain.animateTo(1f, tween(durationMillis = LifeGainDurationMs))
    }
    val gainAmt = gain.value
    val celebrating = gainAmt > 0f && gainAmt < 1f

    // Latido del corazón premiado: un golpe fuerte que se asienta (sin(π·t) da el
    // impulso de ida y vuelta en un solo valor, sin encadenar animaciones).
    val gainPulse = if (celebrating) 1f + 0.55f * sin(gainAmt * PI_F) * (1f - gainAmt) else 1f

    Box(modifier = Modifier.size(HeartSlot), contentAlignment = Alignment.Center) {
        // Onda expansiva + fogonazo del premio, por debajo del glifo.
        if (celebrating) LifeGainBurst(progress = gainAmt)

        // Contorno base: marca el hueco de la vida (siempre visible, no se mueve).
        Icon(
            imageVector = KortexIcons.HeartOutline,
            contentDescription = null,
            tint = LogicColors.OnDarkMuted,
            modifier = Modifier.size(HeartGlyph),
        )
        // Halo del corazón vivo, atado a su opacidad.
        if (fillAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(HeartSlot)
                    .alpha(fillAlpha)
                    .background(
                        Brush.radialGradient(
                            listOf(LogicColors.Error.copy(alpha = 0.38f), Color.Transparent),
                        ),
                    ),
            )
            // Corazón relleno encima del contorno.
            Icon(
                imageVector = KortexIcons.Heart,
                contentDescription = if (alive) "Vida" else null,
                tint = LogicColors.Error,
                modifier = Modifier
                    .size(HeartGlyph)
                    .scale(fillScale * gainPulse)
                    .alpha(fillAlpha),
            )
        }
        // "+1" que sube y se desvanece: dice en un golpe de vista QUÉ se ha ganado.
        if (celebrating) LifeGainLabel(progress = gainAmt)
    }
}

/**
 * Onda expansiva de una vida recuperada: un anillo que crece desde el corazón y un
 * fogonazo cálido que lo envuelve, ambos desvaneciéndose. Sigue la receta de neón de
 * la app (halo → anillo nítido) y se apaga con `1 − progreso`, así que nunca queda
 * nada dibujado al terminar.
 */
@Composable
private fun LifeGainBurst(progress: Float) {
    // Desaceleración (ease-out): sale rápido y frena, como el estallido de las burbujas.
    val ease = 1f - (1f - progress) * (1f - progress)
    val fade = 1f - progress
    Canvas(modifier = Modifier.size(HeartSlot)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (HeartGlyph / 2).toPx() + ease * (HeartSlot / 2).toPx()
        // Fogonazo interior que se apaga rápido (cuadrático) para no ensuciar el HUD.
        drawCircle(
            color = LogicColors.Error.copy(alpha = 0.40f * fade * fade),
            radius = radius * 0.9f,
            center = center,
        )
        // Halo ancho + anillo nítido del frente de onda.
        drawCircle(
            color = LogicColors.Error.copy(alpha = 0.30f * fade),
            radius = radius,
            center = center,
            style = Stroke(width = 5.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f * fade),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** El "+1" del premio: asciende sobre el corazón mientras se desvanece. */
@Composable
private fun LifeGainLabel(progress: Float) {
    // Frenada al subir (ease-out) y desvanecido en el último tercio: el texto se lee
    // entero antes de empezar a irse.
    val rise = 1f - (1f - progress) * (1f - progress)
    val fade = ((1f - progress) / 0.35f).coerceIn(0f, 1f)
    Text(
        text = "+1",
        style = MaterialTheme.typography.titleMedium,
        color = LogicColors.Error,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .offset(y = (-14 - 18 * rise).dp)
            .alpha(fade)
            .scale(0.8f + 0.4f * rise),
    )
}

/**
 * Una burbuja en caída. Mapea la posición fraccional del motor a un desplazamiento
 * en Dp y aplica una entrada con rebote (crece de 0.6→1 al aparecer). El color es
 * decorativo (por id), nunca indica si es la correcta.
 */
@Composable
private fun FallingBubble(
    bubble: Bubble,
    fieldWidth: Dp,
    floorLine: Dp,
    onTap: () -> Unit,
) {
    // Posición del centro → esquina superior izquierda (restando el radio).
    val xDp = (fieldWidth * bubble.x - BubbleSize / 2)
        .coerceIn(0.dp, fieldWidth - BubbleSize)
    val yDp = floorLine * bubble.y - BubbleSize / 2

    // Aparición con resorte: se anima una sola vez al entrar en composición.
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(bubble.id) {
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    val color = BubbleColors[bubble.id % BubbleColors.size]
    Box(
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .size(BubbleSize)
            .scale(appear.value)
            // Globo de neón hueco: misma estética de "tubo neón" que las teclas de
            // Memoria (halo + aro + núcleo blanco), centralizada en [drawNeonBubble].
            .drawBehind { drawNeonBubble(color) }
            .clip(CircleShape)
            .bounceClick(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            bubble.expr.text,
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Base del juego: la línea de suelo y el número objetivo destacado. Es el "cesto"
 * al que apuntan las burbujas: si el objetivo cruza esta línea, se pierde una vida.
 */
@Composable
private fun TargetBase(target: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Línea de suelo: neón ROJO con glow. Es una frontera de peligro ("no crucen
        // más allá"): el resplandor rojo la carga de significado semántico —cuanto más
        // se acerca una burbuja, más evidente el límite— sin robar el foco del objetivo.
        DangerFloorLine()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "OBJETIVO",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
            )
            Text(
                "$target",
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.NeonGreen,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

/**
 * Línea de suelo de **peligro**: un tubo de neón rojo con halo que late suavemente.
 * Comunica el límite que las burbujas no deben cruzar. El resplandor se dibuja apilando
 * trazos horizontales (halo ancho translúcido → intermedio → línea nítida → núcleo
 * blanco), el mismo truco "sin blur" del resto de neón de la app; el latido lento y de
 * baja amplitud sigue §9.4 (bucles ambientales suaves, sin robar atención).
 */
@Composable
private fun DangerFloorLine() {
    // Latido lento del halo (respira entre 0.6 y 1): marca "zona viva" de peligro.
    val transition = rememberInfiniteTransition(label = "dangerGlow")
    val glow by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val red = LogicColors.Error
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(18.dp),
    ) {
        val cy = size.height / 2f
        val w = 3.dp.toPx()
        // Se desvanece en los extremos para que el tubo "flote" y no choque con los bordes.
        fun line(alpha: Float, width: Float) = drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    red.copy(alpha = alpha),
                    red.copy(alpha = alpha),
                    Color.Transparent,
                ),
            ),
            start = Offset(0f, cy),
            end = Offset(size.width, cy),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
        line(0.22f * glow, w * 5f)   // Halo ancho translúcido (respira con glow).
        line(0.45f * glow, w * 2.4f) // Halo intermedio.
        line(0.95f, w)               // Línea nítida del tubo.
        // Núcleo blanco-rojizo interior: el look de neón "encendido".
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
            ),
            start = Offset(0f, cy),
            end = Offset(size.width, cy),
            strokeWidth = w * 0.4f,
            cap = StrokeCap.Round,
        )
    }
}

/** Nº de chispas de un estallido según el resultado: el acierto libera muchas más. */
private const val BurstSparksSuccess = 16
private const val BurstSparksFail = 6

/**
 * Semilla de una chispa: dirección y alcance fijos durante toda su animación (se generan
 * una vez por evento para que las chispas no "salten" entre recomposiciones).
 *
 * @property angle ángulo de salida en radianes.
 * @property reach fracción del alcance máximo (0.7..1) para que no vuelen todas igual.
 * @property length longitud de la estela, como múltiplo de una unidad base.
 */
private data class SparkSeed(val angle: Float, val reach: Float, val length: Float)

/**
 * Capa de **chispas** que estalla en el sitio exacto donde explotó la última burbuja
 * tocada (dato [BubbleBurst] del motor). Se dispara **una sola vez** por [eventId] y
 * anima un `progress` 0→1 con el que las estelas salen disparadas hacia afuera mientras
 * se desvanecen, más un anillo de choque que se expande. Refuerza el feedback de acierto
 * (muchas chispas, en el color de la burbuja) frente al de fallo (pocas, en rojo error).
 *
 * @param floorLine altura (Dp) a la que el motor mapea `y = 1` (el suelo); se usa para
 *   convertir la posición fraccional del estallido a píxeles, igual que las burbujas.
 */
@Composable
private fun BubbleBurstLayer(burst: BubbleBurst?, eventId: Int, floorLine: Dp) {
    if (burst == null) return

    val progress = remember { Animatable(0f) }
    // Chispas fijas por evento: dirección/alcance deterministas mientras dura la animación.
    val sparks = remember(eventId) {
        val count = if (burst.success) BurstSparksSuccess else BurstSparksFail
        val rnd = Random(eventId)
        List(count) {
            SparkSeed(
                angle = rnd.nextFloat() * TAU,
                reach = 0.7f + rnd.nextFloat() * 0.3f,
                length = 0.7f + rnd.nextFloat() * 0.6f,
            )
        }
    }

    LaunchedEffect(eventId) {
        if (eventId == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 520))
    }
    val p = progress.value
    if (p <= 0f || p >= 1f) return

    val color = if (burst.success) BubbleColors[burst.colorId % BubbleColors.size] else LogicColors.Error
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * burst.x
        val cy = (floorLine.toPx() * burst.y).coerceIn(0f, size.height)

        // Desaceleración: salen rápido y frenan (ease-out), como esquirlas reales.
        val ease = 1f - (1f - p) * (1f - p)
        val alpha = 1f - p
        val maxDist = (if (burst.success) BubbleSize * 1.6f else BubbleSize * 0.9f).toPx()
        val unit = 10.dp.toPx()

        // Anillo de choque que se expande desde el borde de la burbuja.
        drawCircle(
            color = color.copy(alpha = 0.4f * alpha),
            radius = (BubbleSize / 2).toPx() + ease * maxDist * 0.5f,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )

        sparks.forEach { s ->
            val dist = ease * maxDist * s.reach
            val dx = cos(s.angle)
            val dy = sin(s.angle)
            val head = Offset(cx + dx * dist, cy + dy * dist)
            val tail = Offset(cx + dx * (dist - unit * s.length), cy + dy * (dist - unit * s.length))
            // Estela de la chispa (color del resultado).
            drawLine(
                color = color.copy(alpha = alpha),
                start = tail,
                end = head,
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Cabeza blanca incandescente.
            drawCircle(
                color = Color.White.copy(alpha = 0.9f * alpha),
                radius = 1.6.dp.toPx(),
                center = head,
            )
        }
    }
}

/**
 * Destello a pantalla completa como feedback inmediato: verde al acertar, rojo al
 * fallar o dejar escapar el objetivo. Se dispara **una sola vez** por evento gracias
 * a [eventId] (no en cada recomposición): sube la opacidad de golpe y la desvanece.
 */
@Composable
private fun FeedbackFlash(eventId: Int, result: TapResult?) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(eventId) {
        if (eventId == 0 || result == null) return@LaunchedEffect
        alpha.snapTo(0.35f)
        alpha.animateTo(0f, tween(durationMillis = 420))
    }
    if (alpha.value <= 0f) return
    val color = if (result == TapResult.CORRECT) LogicColors.Success else LogicColors.Error
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color.copy(alpha = alpha.value)),
    )
}

// ---------------------------------------------------------------------------
// Nube de ecuación
// ---------------------------------------------------------------------------

/**
 * Color de neón de la nube. Cian eléctrico a propósito: el objetivo del juego ya es
 * verde y el suelo de peligro rojo, así que la nube necesitaba un tercer color para
 * leerse de inmediato como "esto es otra cosa, algo nuevo que ha entrado".
 */
private val CloudAccent = LogicColors.NeonCyan

/** Alto de la nube (el panel con la ecuación). */
private val CloudHeight = 168.dp

/** Tamaño de un hueco de la ecuación. */
private val SlotWidth = 48.dp
private val SlotHeight = 54.dp

/** Tamaño de una ficha de la bandeja inferior (mayor: es lo que se pulsa). */
private val TokenWidth = 62.dp
private val TokenHeight = 58.dp

/**
 * Nube de ecuación: el reto relámpago que entra entre dos rondas y devuelve una
 * vida. Cubre el campo entero —que el motor deja vacío antes de abrirla, nunca a
 * mitad de una caída— con la ecuación incompleta arriba, dentro de una nube de neón,
 * y las fichas abajo, al alcance del pulgar y justo donde estaba el objetivo.
 *
 * @param livesFull si el jugador ya tiene todas las vidas; cambia la promesa de la
 *   recompensa (puntos en vez de vida) para no prometer algo que no va a recibir.
 */
@Composable
private fun EquationCloudOverlay(
    cloud: EquationCloudUi,
    livesFull: Boolean,
    onToken: (Int) -> Unit,
    onBlank: (Int) -> Unit,
) {
    // Entrada: la nube "baja" del cielo con resorte mientras aparece. Una sola vez.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        )
    }

    // Temblor del panel al fallar una combinación: se dispara UNA vez por intento
    // fallido (mismo patrón de `eventId` que el resto del juego).
    val shake = remember { Animatable(0f) }
    LaunchedEffect(cloud.wrongTick) {
        if (cloud.wrongTick == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 360
                1f at 60
                -1f at 130
                0.6f at 200
                -0.3f at 270
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Oscurece el fondo del campo para que la nube sea lo único que se lee:
            // el tablero ya está vacío, pero el skyline y el objetivo siguen detrás.
            .background(LogicColors.BackgroundDark.copy(alpha = 0.82f * enter.value)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            CloudPanel(
                cloud = cloud,
                livesFull = livesFull,
                enter = enter.value,
                shake = shake.value,
                onBlank = onBlank,
            )
            Spacer(Modifier.weight(1f))
            TokenTray(cloud = cloud, enabled = cloud.outcome == null, onToken = onToken)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * La nube en sí: silueta de neón con la ecuación incompleta, la cuenta atrás y el
 * mensaje de recompensa (o el desenlace, una vez resuelta).
 *
 * @param enter 0..1 de la animación de entrada (opacidad + caída desde arriba).
 * @param shake −1..1 del temblor tras un intento fallido.
 */
@Composable
private fun CloudPanel(
    cloud: EquationCloudUi,
    livesFull: Boolean,
    enter: Float,
    shake: Float,
    onBlank: (Int) -> Unit,
) {
    // Balanceo ambiental lento y de poca amplitud: la nube "flota" (§9.4).
    val transition = rememberInfiniteTransition(label = "cloudFloat")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )

    // Al fallar, todo el tubo de neón se tiñe de rojo mientras dura el temblor.
    val wrong = shake != 0f
    val accent = if (wrong) LogicColors.Error else CloudAccent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CloudHeight)
            .offset(
                x = (shake * 8).dp,
                y = ((1f - enter) * -60f + bob * 4f).dp,
            )
            .alpha(enter)
            .drawBehind { drawCloudShape(accent) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CloudCaption(cloud = cloud, livesFull = livesFull)
            EquationRow(cloud = cloud, wrong = wrong, onBlank = onBlank)
            // La cuenta atrás desaparece en cuanto hay desenlace: ya no hay prisa.
            if (cloud.outcome == null) {
                CloudTimerBar(fraction = cloud.timeFraction, remainingMs = cloud.remainingMs)
            }
        }
    }
}

/**
 * Texto superior de la nube: qué falta y qué se gana. Al resolverse (o agotarse el
 * tiempo) lo sustituye el desenlace, con su color.
 */
@Composable
private fun CloudCaption(cloud: EquationCloudUi, livesFull: Boolean) {
    val text: String
    val color: Color
    when (cloud.outcome) {
        CloudOutcome.LIFE_GAINED -> {
            text = stringResource(Res.string.bubble_cloud_result_life)
            color = LogicColors.Success
        }

        CloudOutcome.BONUS_POINTS -> {
            text = stringResource(Res.string.bubble_cloud_result_points, cloud.bonusPoints.toString())
            color = LogicColors.Amber
        }

        CloudOutcome.FAILED -> {
            text = stringResource(Res.string.bubble_cloud_result_failed)
            color = LogicColors.OnDarkMuted
        }

        null -> {
            val missing = when (cloud.puzzle.kind) {
                EquationBlankKind.SYMBOL -> stringResource(Res.string.bubble_cloud_missing_symbols)
                EquationBlankKind.NUMBER -> stringResource(Res.string.bubble_cloud_missing_numbers)
            }
            val reward = if (livesFull) {
                stringResource(Res.string.bubble_cloud_reward_points)
            } else {
                stringResource(Res.string.bubble_cloud_reward_life)
            }
            text = "$missing · $reward"
            color = LogicColors.OnDarkMuted
        }
    }

    // El desenlace entra con rebote: es el momento de recompensa del reto.
    val resolved = cloud.outcome != null
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "captionScale",
    )
    Text(
        text = text,
        style = if (resolved) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        color = color,
        fontWeight = if (resolved) FontWeight.Black else FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.scale(if (resolved) scale else 1f),
    )
}

/**
 * La ecuación, pieza a pieza. Los trozos fijos se pintan como texto y los huecos como
 * casillas de neón pulsables (pulsar una llena devuelve su ficha a la bandeja).
 */
@Composable
private fun EquationRow(cloud: EquationCloudUi, wrong: Boolean, onBlank: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cloud.puzzle.slots.forEach { slot ->
            when (slot) {
                is EquationSlot.Fixed -> Text(
                    text = slot.text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.Black,
                )

                is EquationSlot.Blank -> BlankSlot(
                    label = cloud.tokenAt(slot.index)?.label,
                    wrong = wrong,
                    enabled = cloud.outcome == null,
                    onClick = { onBlank(slot.index) },
                )
            }
        }
    }
}

/**
 * Un hueco de la ecuación. Apagado mientras está vacío y **encendido** al recibir su
 * ficha: el mismo lenguaje de "tubo de neón que se enciende" que las teclas de Memoria
 * (CLAUDE.md §9.7, fuente única [drawNeonTile]).
 */
@Composable
private fun BlankSlot(label: String?, wrong: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val activeAmt by animateFloatAsState(
        targetValue = if (label != null) 1f else 0.12f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "blankActive",
    )
    val color = if (wrong) LogicColors.Error else CloudAccent
    Box(
        modifier = Modifier
            .size(width = SlotWidth, height = SlotHeight)
            .drawBehind {
                drawNeonTile(
                    baseColor = color,
                    activeAmt = activeAmt,
                    cornerRadius = 12.dp,
                    baseMargin = 5.dp,
                    sparks = false,
                )
            }
            .bounceClick(enabled = enabled && label != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

/**
 * Bandeja inferior con las fichas a pulsar (los símbolos o los números que faltan,
 * más distractores). Una ficha ya colocada se atenúa en vez de desaparecer: si el
 * hueco de la bandeja se moviera, el jugador perdería la referencia a mitad de reto.
 */
@Composable
private fun TokenTray(cloud: EquationCloudUi, enabled: Boolean, onToken: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cloud.puzzle.options.forEach { token ->
            val used = token.id in cloud.placed
            val alpha by animateFloatAsState(
                targetValue = if (used) 0.22f else 1f,
                animationSpec = tween(durationMillis = 180),
                label = "tokenAlpha",
            )
            Box(
                modifier = Modifier
                    .size(width = TokenWidth, height = TokenHeight)
                    .alpha(alpha)
                    .drawBehind {
                        drawNeonTile(
                            baseColor = CloudAccent,
                            activeAmt = 0.85f,
                            cornerRadius = 16.dp,
                            baseMargin = 6.dp,
                            sparks = false,
                        )
                    }
                    .bounceClick(enabled = enabled && !used) { onToken(token.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = token.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

/**
 * Cuenta atrás del reto: una barra que se vacía más los segundos en texto. Cambia de
 * color al entrar en la zona crítica —cian → ámbar → rojo— para que la urgencia se
 * perciba de reojo, sin tener que leer el número.
 */
@Composable
private fun CloudTimerBar(fraction: Float, remainingMs: Long) {
    val color = when {
        fraction <= 0.2f -> LogicColors.Error
        fraction <= 0.45f -> LogicColors.Amber
        else -> CloudAccent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .height(6.dp)
                .width(150.dp)
                .clip(CircleShape)
                .background(LogicColors.SurfaceVariantDark),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            // Redondeo hacia arriba: mientras quede algo de tiempo debe verse "1 s",
            // nunca un "0 s" con la barra todavía viva.
            text = stringResource(Res.string.bubble_cloud_seconds, ((remainingMs + 999) / 1000).toString()),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Silueta de nube en neón: una base redondeada más tres lóbulos, **unidos en un solo
 * [Path]** para poder trazar el contorno sin que se vean las costuras interiores.
 *
 * El resplandor sigue la receta de neón de la app (CLAUDE.md §9.7): halo ancho → halo
 * intermedio → trazo nítido, sobre un relleno translúcido que despega la nube del
 * fondo. No usa [drawNeonTile] porque este contorno no es un rectángulo de tile.
 */
private fun DrawScope.drawCloudShape(accent: Color) {
    val w = size.width
    val h = size.height
    val base = Path().apply {
        addRoundRect(
            RoundRect(
                left = w * 0.04f,
                top = h * 0.44f,
                right = w * 0.96f,
                bottom = h * 0.92f,
                cornerRadius = CornerRadius(h * 0.24f, h * 0.24f),
            ),
        )
    }

    // Lóbulos superiores (izquierda, centro y derecha): centro y radios, en píxeles.
    val lobes = listOf(
        Offset(w * 0.30f, h * 0.44f) to Offset(w * 0.16f, h * 0.26f),
        Offset(w * 0.52f, h * 0.34f) to Offset(w * 0.20f, h * 0.30f),
        Offset(w * 0.74f, h * 0.46f) to Offset(w * 0.15f, h * 0.24f),
    )

    // La unión se encadena creando un Path nuevo en cada paso: `op` escribe en el
    // receptor, así que reutilizarlo como operando de sí mismo sería frágil.
    var path = base
    lobes.forEach { (center, radii) ->
        val lobe = Path().apply {
            addOval(
                Rect(
                    center.x - radii.x,
                    center.y - radii.y,
                    center.x + radii.x,
                    center.y + radii.y,
                ),
            )
        }
        path = Path().apply { op(path, lobe, PathOperation.Union) }
    }

    // Relleno: azul noche translúcido con un velo del acento, para que el texto se lea.
    drawPath(path, color = LogicColors.SurfaceDark.copy(alpha = 0.94f))
    drawPath(path, color = accent.copy(alpha = 0.10f))

    // Tubo de neón: halo ancho → intermedio → trazo nítido.
    drawPath(path, color = accent.copy(alpha = 0.16f), style = Stroke(width = 10.dp.toPx()))
    drawPath(path, color = accent.copy(alpha = 0.38f), style = Stroke(width = 5.dp.toPx()))
    drawPath(path, color = accent, style = Stroke(width = 2.dp.toPx()))
}
