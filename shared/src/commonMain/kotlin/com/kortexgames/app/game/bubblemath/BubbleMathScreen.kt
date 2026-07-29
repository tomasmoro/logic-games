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
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.CitySkylineBackground
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonBubble
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
            onStart = { vm.onIntent(BubbleMathIntent.Start) },
            onExit = onExit,
            background = {
                CitySkylineBackground(
                    modifier = Modifier.fillMaxSize(),
                    accent = LogicColors.Violet,
                    intensity = 0.6f,
                )
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
            }
        }

        // Vidas como corazones, ancladas arriba y centradas: el estado más crítico
        // del jugador (cuánto le queda) va en el punto de mayor foco de la pantalla.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(BubbleMathState.MAX_LIVES) { i ->
                LifeHeart(alive = i < game.lives)
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

/**
 * Un corazón de vida con **posición estable** y transición animada. El contorno
 * (vida perdida) está siempre presente y ocupa el mismo hueco; encima, el corazón
 * relleno + su halo se **desvanecen dando un pequeño "estallido"** (escala hacia
 * arriba mientras baja la opacidad) al perder la vida, en lugar de desaparecer de
 * golpe. Feedback visual inmediato, CLAUDE.md §9.4.
 */
@Composable
private fun LifeHeart(alive: Boolean) {
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

    Box(modifier = Modifier.size(HeartSlot), contentAlignment = Alignment.Center) {
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
                    .scale(fillScale)
                    .alpha(fillAlpha),
            )
        }
    }
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
