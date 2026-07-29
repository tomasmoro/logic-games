package com.kortexgames.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Duración de la "tirada" del dado antes de navegar (ms). Suspense breve. */
private const val ROLL_DURATION_MS = 520

/**
 * Botón flotante de **juego aleatorio**: un dado neón que "invita" con animación
 * en bucle (balanceo suave + latido + brillo). Al pulsarlo hace una **tirada
 * visible** (gira dos vueltas con rebote y da un pequeño "pop" de escala) y, al
 * terminar, dispara [onClick] para abrir un juego al azar.
 *
 * La navegación se retrasa [ROLL_DURATION_MS] ms a propósito para que se vea rodar
 * el dado (suspense). Se ignoran toques mientras rueda para no encadenar tiradas.
 *
 * Usa un degradado morado→magenta (distinto del verde de PLAY NOW) para leerse
 * como "sorpresa/azar" sin competir con la acción principal.
 *
 * @param onClick acción al terminar la tirada (elegir y abrir juego aleatorio).
 * @param onRoll se invoca al INICIAR la tirada; buen sitio para sonido + háptica
 *   (efecto "lanzar el dado"). No se dispara si ya está rodando.
 * @param onLand se invoca cuando el dado "aterriza" (fin del giro), justo antes de
 *   [onClick]; buen sitio para un toque háptico suave que remate la sensación.
 * @param size diámetro del botón.
 */
@Composable
fun RandomGameFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRoll: () -> Unit = {},
    onLand: () -> Unit = {},
    size: Dp = 62.dp,
) {
    val scope = rememberCoroutineScope()
    var rolling by remember { mutableStateOf(false) }

    // Balanceo continuo de baja amplitud cuando está en reposo: el dado "tiembla"
    // invitando a tocarlo. Se apaga durante la tirada para que el giro se vea limpio.
    val infinite = rememberInfiniteTransition(label = "diceIdle")
    val wobble by infinite.animateFloat(
        initialValue = -9f,
        targetValue = 9f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "diceWobble",
    )

    // "Tirada": cada pulsación suma 720° (dos vueltas). Un tween con desaceleración
    // completa el giro justo dentro de la ventana de suspense y "aterriza" suave.
    var rollTarget by remember { mutableStateOf(0f) }
    val roll by animateFloatAsState(
        targetValue = rollTarget,
        animationSpec = tween(durationMillis = ROLL_DURATION_MS, easing = FastOutSlowInEasing),
        label = "diceRoll",
    )

    // Pequeño "pop" de escala mientras rueda: da sensación de lanzamiento.
    val pop by animateFloatAsState(
        targetValue = if (rolling) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dicePop",
    )

    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .pulse(maxScale = 1.05f, durationMillis = 1300)
            .scale(pop)
            .softGlow(color = LogicColors.Violet, shape = shape, maxElevation = 22.dp)
            .clip(shape)
            .background(Brush.linearGradient(LogicGradients.primary))
            .bounceClick {
                if (!rolling) {
                    rolling = true
                    rollTarget += 720f // dos vueltas completas
                    onRoll() // sonido + háptica del "lanzamiento"
                    scope.launch {
                        delay(ROLL_DURATION_MS.toLong()) // deja ver rodar el dado
                        onLand() // toque suave al "aterrizar"
                        onClick()
                        rolling = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KortexIcons.Dice,
            contentDescription = "Jugar un juego al azar",
            tint = LogicColors.OnDark,
            modifier = Modifier
                .size(size * 0.5f)
                .rotate((if (rolling) 0f else wobble) + roll),
        )
    }
}
