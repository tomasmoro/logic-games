package com.example.kortexgames.game.bubblemath

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.bounceClick

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

    // Pausa la caída al ir a segundo plano y la reanuda al volver: si el bucle
    // siguiera corriendo, al regresar las burbujas estarían "pegadas" al suelo.
    LifecycleResumeEffect(Unit) {
        vm.onIntent(BubbleMathIntent.Resume)
        onPauseOrDispose { vm.onIntent(BubbleMathIntent.Pause) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        GameHud(round = game.round, score = game.score, lives = game.lives, combo = game.combo)

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

            // Destello de feedback a pantalla completa (verde acierto / rojo fallo).
            FeedbackFlash(eventId = game.eventId, result = game.lastResult)
        }
    }

    if (state.status == GameStatus.FINISHED && state.gameOver != null) {
        GameOverOverlay(
            info = state.gameOver!!,
            onPlayAgain = { vm.onIntent(BubbleMathIntent.PlayAgain) },
            onExit = onExit,
        )
    }
}

/**
 * Barra superior con la ronda, el marcador, el combo (cuando ≥2, con latido) y las
 * vidas restantes como corazones (llenos = disponibles, vacíos = perdidos).
 */
@Composable
private fun GameHud(round: Int, score: Int, lives: Int, combo: Int) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(BubbleMathState.MAX_LIVES) { i ->
                LifeHeart(alive = i < lives)
            }
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
            .clip(CircleShape)
            // Degradado radial: da volumen de "burbuja" (brillo arriba, sombra abajo).
            .background(
                Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0.55f)),
                ),
            )
            .border(BorderStroke(1.5.dp, color.copy(alpha = 0.9f)), CircleShape)
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
        // Línea de suelo neón, tenue (referencia visual sin robar protagonismo).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, LogicColors.NeonCyan.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        )
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
