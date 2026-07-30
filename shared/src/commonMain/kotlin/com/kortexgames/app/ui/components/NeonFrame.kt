package com.kortexgames.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors

/**
 * "Bezel" de consola arcade: envuelve [content] en un panel de **fondo sólido**
 * con un **borde neón que gira** de forma continua alrededor del marco, más un halo
 * suave que respira.
 *
 * Dos motivos de diseño:
 *  1. El fondo sólido ([panelColor]) tapa cualquier textura de detrás, de modo que
 *     los huecos entre botones muestran el panel y no el muro del fondo — da
 *     sensación de "tablero" compacto en vez de botones sueltos.
 *  2. El borde animado es el **único bucle llamativo** de la pantalla (CLAUDE.md
 *     §9.4/§9.5): lento y de baja amplitud, guía la vista al área de juego sin
 *     competir con nada más.
 *
 * La rotación se consigue muestreando una rueda de colores neón en posiciones fijas
 * de un `sweepGradient` y desplazando el punto de muestreo con la fase animada: las
 * posiciones quedan siempre ordenadas (Compose lo exige) y solo cambian los colores,
 * lo que produce la ilusión de giro sin discontinuidades.
 *
 * @param colors ciclo de colores neón que recorren el borde (se trata como bucle:
 *   el último enlaza con el primero).
 * @param cornerRadius radio de las esquinas del panel y del borde.
 * @param borderWidth grosor del trazo neón nítido.
 * @param contentPadding aire entre el borde y el contenido.
 * @param panelColor color de relleno del panel (opaco a propósito).
 * @param periodMillis duración de una vuelta completa del borde (lento por diseño).
 */
@Composable
fun NeonFrame(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        LogicColors.NeonCyan,
        LogicColors.Violet,
        LogicColors.Magenta,
        LogicColors.NeonGreen,
    ),
    cornerRadius: Dp = 28.dp,
    borderWidth: Dp = 2.5.dp,
    contentPadding: Dp = 16.dp,
    panelColor: Color = LogicColors.SurfaceDark,
    periodMillis: Int = 6000,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "neonFrame")
    // Fase de rotación del borde: una vuelta completa por período, en bucle lineal.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    // Latido del halo: sube y baja suave para que el neón "respire".
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            // Se dibuja el panel + contenido primero y el neón ENCIMA: si se pintara
            // detrás, el `background` opaco taparía el borde y el halo (se veían
            // apagados). `drawWithContent` opera fuera del `clip`, así el halo puede
            // sangrar por fuera del panel.
            .drawWithContent {
                drawContent()

                val cornerPx = cornerRadius.toPx()
                val stroke = borderWidth.toPx()
                val corner = CornerRadius(cornerPx, cornerPx)

                // Muestreo de la rueda neón en posiciones fijas + desplazamiento de
                // fase → rotación continua sin romper el orden de los stops.
                val steps = 48
                val stops = Array(steps + 1) { i ->
                    val pos = i / steps.toFloat()
                    pos to wheelColor((pos + phase) % 1f, colors)
                }
                val brush = Brush.sweepGradient(
                    colorStops = stops,
                    center = Offset(size.width / 2f, size.height / 2f),
                )

                // Halo exterior ancho y translúcido (sangra hacia afuera).
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = corner,
                    style = Stroke(width = stroke * 5f),
                    alpha = 0.42f * glow,
                )
                // Halo intermedio para dar cuerpo al resplandor.
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = corner,
                    style = Stroke(width = stroke * 2.4f),
                    alpha = 0.66f * glow,
                )
                // Borde nítido, inset media línea para que quede pegado al panel.
                val inset = stroke / 2f
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = corner,
                    style = Stroke(width = stroke),
                )
            }
            .clip(shape)
            .background(panelColor)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Color de la "rueda" neón en la posición [t] (0..1, cíclica). Interpola linealmente
 * entre colores adyacentes de [colors], tratando la lista como un bucle cerrado para
 * que el borde no tenga costura al dar la vuelta.
 */
private fun wheelColor(t: Float, colors: List<Color>): Color {
    val n = colors.size
    val scaled = ((t % 1f) + 1f) % 1f * n
    val i = scaled.toInt() % n
    val frac = scaled - scaled.toInt()
    return lerp(colors[i], colors[(i + 1) % n], frac)
}
