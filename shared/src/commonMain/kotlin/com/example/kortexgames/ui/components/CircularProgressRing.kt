package com.example.kortexgames.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients

/**
 * Anillo de progreso circular que **se llena gradualmente al entrar** en pantalla
 * (no aparece "de golpe"): refuerza la sensación de logro del objetivo diario.
 *
 * El truco de la animación de entrada: se anima desde 0f hasta [progress] usando
 * un estado interno que arranca en 0 y se actualiza en un [LaunchedEffect]. Así
 * la barra siempre "crece" al componerse, aunque el progreso ya venga alto.
 *
 * @param progress objetivo 0f..1f (se recorta a ese rango).
 * @param size diámetro total del anillo.
 * @param strokeWidth grosor del trazo.
 * @param trackColor color del carril de fondo (progreso restante).
 * @param progressColors degradado del arco de progreso (barrido).
 * @param content contenido centrado dentro del anillo (p. ej. "3/5").
 */
@Composable
fun CircularProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    strokeWidth: Dp = 14.dp,
    trackColor: Color = LogicColors.SurfaceVariantDark,
    progressColors: List<Color> = LogicGradients.success,
    content: @Composable () -> Unit = {},
) {
    // Estado que arranca en 0 para forzar el "llenado" en la primera composición.
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(progress) { target = progress.coerceIn(0f, 1f) }

    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "ringFill",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)

            // Carril completo (fondo).
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Arco de progreso (empieza arriba, -90°, y avanza en sentido horario).
            if (animated > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(progressColors),
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}
