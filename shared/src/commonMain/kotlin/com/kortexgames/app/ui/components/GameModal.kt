package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlinx.coroutines.delay

/*
 * # Piezas comunes de los diálogos modales de juego
 *
 * El menú de pausa ([GamePauseControls]) y el cartel de fin de partida
 * ([GameOverOverlay]) son la misma "tarjeta sobre scrim" con distinto contenido.
 * Aquí vive lo que comparten —scrim, superficie de la tarjeta y entrada escalonada
 * del contenido— para que un ajuste de aspecto se haga en un solo sitio y los dos
 * diálogos (y los 24 juegos que los usan) no se desincronicen.
 */

/** Radio de esquina de las tarjetas modales de juego. */
internal val ModalCardCorner = 28.dp

/** Alcance del resplandor exterior de la tarjeta modal. */
private val HaloReach = 26.dp

/** Forma de las tarjetas modales de juego (pausa, fin de partida). */
internal val ModalCardShape = RoundedCornerShape(ModalCardCorner)

/**
 * Scrim (velo oscuro) de un diálogo modal de juego.
 *
 * Se **dibuja desbordando** los límites del composable en vez de usar `background`:
 * la pantalla de juego vive dentro del área segura, así que un `background` normal
 * dejaba sin oscurecer la franja de la barra de estado y la del indicador de inicio
 * — dos bandas más claras que delataban el borde del modal. Pintando un rectángulo
 * mucho mayor que la pantalla, el velo llega hasta el borde físico.
 *
 * @param alpha opacidad del velo (0 = sin velo; anímala para el fundido de entrada).
 */
internal fun Modifier.modalScrim(alpha: Float): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    val bleed = 400.dp.toPx()
    drawRect(
        color = Color.Black.copy(alpha = alpha),
        topLeft = Offset(-bleed, -bleed),
        size = Size(size.width + bleed * 2f, size.height + bleed * 2f),
    )
}

/**
 * Superficie de una tarjeta modal de juego: resplandor neón exterior del color del
 * juego, fondo con un baño de luz de acento que cae desde arriba y borde en degradado.
 *
 * Antes la tarjeta era un rectángulo de `SurfaceDark` plano con un borde fino: se
 * leía como un formulario, no como un panel de juego. La luz de acento arriba da
 * volumen (como si el propio marco iluminara el panel) y el halo la despega del
 * scrim, sin romper la regla de "superficie oscura, acento escaso" (§9.1): el
 * acento se queda en los bordes y en el primer tercio.
 *
 * El halo se dibuja por FUERA del contorno (capas infladas, misma receta que
 * [breathingNeonHalo]) y es estático: el único bucle del diálogo es el del CTA (§9.4).
 *
 * @param accent color de acento de la categoría del juego.
 * @param corner radio de esquina; debe coincidir con el de [shape].
 */
internal fun Modifier.modalCard(
    accent: Color,
    shape: RoundedCornerShape = ModalCardShape,
    corner: Dp = ModalCardCorner,
): Modifier = this
    .drawBehind {
        val radius = corner.toPx()
        // Muchas capas finas de alfa bajo que se solapan: la suma decae suave hacia
        // fuera. Con solo tres capas gruesas se veían los escalones como anillos.
        val layers = 8
        for (i in 1..layers) {
            val w = HaloReach.toPx() * i / layers
            drawRoundRect(
                color = accent.copy(alpha = 0.034f),
                topLeft = Offset(-w / 2f, -w / 2f),
                size = Size(size.width + w, size.height + w),
                cornerRadius = CornerRadius(radius + w / 2f),
                style = Stroke(width = w),
            )
        }
    }
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0f to lerp(LogicColors.SurfaceDark, accent, 0.14f),
            0.42f to LogicColors.SurfaceDark,
            1f to lerp(LogicColors.SurfaceDark, LogicColors.BackgroundDark, 0.40f),
        ),
    )
    .drawBehind {
        // Foco de luz en la esquina superior: rompe la simetría del degradado vertical.
        drawRect(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.18f, 0f),
                radius = size.width * 0.85f,
            ),
        )
    }
    .border(
        BorderStroke(1.5.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.80f), accent.copy(alpha = 0.14f)))),
        shape,
    )

/** Retardo antes de que entre el primer bloque (deja asentarse el rebote de la tarjeta). */
private const val REVEAL_LEAD_MS = 90L

/** Separación entre la entrada de un bloque y la del siguiente. */
private const val REVEAL_STEP_MS = 55L

/**
 * Entrada escalonada de un bloque de contenido dentro de un diálogo o pantalla de
 * juego: sube unos dp y funde, retrasado según su [index]. Hace que el ojo recorra
 * el contenido en orden (cabecera → opciones → CTA) en vez de recibirlo de golpe.
 *
 * A diferencia de [StaggeredReveal] (pensado para la Home, que se reproduce una sola
 * vez por instancia), este se **repite cada vez** que [visible] pasa a true: un
 * diálogo se abre muchas veces por sesión y cada apertura merece su entrada.
 *
 * Es corto a propósito (§9.4): con 5-6 bloques todo está en su sitio en ~0,4 s, y
 * los bloques son pulsables desde el primer frame — la animación no bloquea nada.
 *
 * @param index posición del bloque (0 = el primero en entrar).
 * @param visible dispara la entrada al pasar a true; a false, vuelve al estado inicial.
 */
@Composable
internal fun ModalReveal(
    index: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(REVEAL_LEAD_MS + index * REVEAL_STEP_MS)
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 280f))
        } else {
            progress.snapTo(0f)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 18.dp.toPx()
            // Sin capa offscreen: recortaría halos y el latido del CTA durante el fundido.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        content()
    }
}
