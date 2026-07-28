package com.example.kortexgames.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Click sin ripple (el feedback lo da la animación de escala del propio botón).
 */
fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/** Aplica alpha solo si [condition] es cierta. */
fun Modifier.alphaIf(condition: Boolean, value: Float): Modifier =
    if (condition) this.alpha(value) else this

/**
 * Borde **punteado** redondeado, dibujado con [drawBehind] (Compose no ofrece un
 * `border` con guiones). Se usa para estados "por rellenar" —marcos vacíos que
 * invitan a completarse—, como las celdas de la misión diaria aún no jugadas.
 *
 * @param color color de los guiones.
 * @param cornerRadius radio de las esquinas (a juego con el `clip` de la celda).
 * @param strokeWidth grosor del trazo.
 * @param dashLength longitud de cada guion y del hueco entre guiones.
 */
fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp = 18.dp,
    strokeWidth: Dp = 1.5.dp,
    dashLength: Dp = 6.dp,
): Modifier = drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), dashLength.toPx()),
        ),
    )
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = stroke,
    )
}

/**
 * **Interacción táctil por defecto de la app** (CLAUDE.md §9.4). Al presionar,
 * reduce la escala del componente (95 % por defecto) y al soltar rebota con
 * física de resorte: se siente "con peso" y da feedback inmediato sin ripple.
 *
 * Gestiona su propio [MutableInteractionSource] por defecto; úsalo en cualquier
 * elemento pulsable (tarjetas, íconos, botones custom). Para botones con degradado
 * ya existe [AnimatedGameButton], que aplica este mismo principio.
 *
 * @param pressedScale escala mientras está presionado (0f..1f).
 * @param interactionSource opcional: pásalo cuando el propio composable necesite
 *   leer `pressed` también (p. ej. subir el brillo de un [drawNeonTile] con
 *   [collectPressGlow] mientras se mantiene pulsado). Si se omite, se crea uno
 *   interno como antes — no rompe a los llamadores existentes.
 * @param onClick acción; buen sitio para disparar SoundEffect.TAP + háptica.
 */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Spring de baja rigidez + bounce medio: la vuelta a 1f "rebota" suavemente.
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "bounceScale",
    )
    this
        .scale(scale)
        .clickableNoRipple(interaction, enabled = enabled, onClick = onClick)
}

/**
 * Progreso de brillo por presión (0 = reposo, 1 = presionado a fondo), listo para
 * pasar como `pressAmt` a [drawNeonTile]. Es la **fuente única** de la curva de
 * animación de este feedback: cualquier tile neón clicable la reutiliza en vez de
 * reimplementar su propio manejo de `collectIsPressedAsState`.
 *
 * Sube a 1 con [Animatable.snapTo] (instantáneo, sin interpolar) en vez de un
 * `spring`/`tween`: muchos tiles (p. ej. las letras del banco en Crucigrama) se
 * **retiran de la composición en el mismo click** que los presiona —el jugador
 * consume la letra al tocarla—, así que si el encendido tardase varios frames en
 * subir, el tile podía desaparecer antes de que llegara a verse. Solo el apagado
 * ([fadeOutMillis]) se anima, para los tiles que sí permanecen en pantalla.
 *
 * Requiere el mismo [InteractionSource] que se le pasa a [bounceClick] (o al
 * `clickable` del tile) para que ambos reaccionen al mismo toque.
 */
@Composable
fun InteractionSource.collectPressGlow(fadeOutMillis: Int = 180): State<Float> {
    val pressed by collectIsPressedAsState()
    val glow = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            glow.snapTo(1f)
        } else {
            glow.animateTo(0f, animationSpec = tween(fadeOutMillis))
        }
    }
    return remember { derivedStateOf { glow.value } }
}

/**
 * Latido suave y continuo (escala oscila entre [minScale] y [maxScale]). Sirve
 * para llamar la atención hacia UNA sola acción principal (p. ej. "JUGAR AHORA").
 *
 * Es de baja amplitud a propósito: invita sin marear. **No lo pongas en varios
 * elementos a la vez** (competirían por la atención — CLAUDE.md §9.4).
 */
fun Modifier.pulse(
    minScale: Float = 1f,
    maxScale: Float = 1.04f,
    durationMillis: Int = 1100,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    this.scale(scale)
}

/**
 * Halo/sombra de color que "respira" (elevación animada). Refuerza el CTA
 * principal dándole un brillo sutil y continuo sin recurrir a imágenes.
 *
 * Nota: el color de sombra (ambient/spot) se respeta en Android; en algunos
 * backends puede degradar a sombra neutra, lo cual sigue siendo aceptable.
 *
 * @param color color del brillo (idealmente el acento del propio botón).
 * @param maxElevation elevación máxima del ciclo de respiración.
 */
fun Modifier.softGlow(
    color: Color,
    shape: Shape = RoundedCornerShape(20.dp),
    minElevation: Dp = 6.dp,
    maxElevation: Dp = 22.dp,
    durationMillis: Int = 1400,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "glow")
    val elevation by transition.animateFloat(
        initialValue = minElevation.value,
        targetValue = maxElevation.value,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowElevation",
    )
    this.shadow(
        elevation = elevation.dp,
        shape = shape,
        clip = false,
        ambientColor = color,
        spotColor = color,
    )
}
