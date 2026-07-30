package com.kortexgames.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.max

/** Un punto del gráfico: [x] normalmente tiempo/índice, [y] el valor (ej. % efectividad). */
data class ChartPoint(val x: Float, val y: Float)

/**
 * Gráfico de líneas genérico y reutilizable (efectividad, tiempo de finalización...).
 * Dibuja: relleno degradado bajo la curva, la línea, y puntos. Anima el trazado
 * de izquierda a derecha en la primera composición.
 *
 * Es agnóstico de los datos: pásale cualquier lista de [ChartPoint].
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = LogicColors.Electric,
    fillColors: List<Color> = listOf(LogicColors.Electric, Color.Transparent),
    strokeWidth: Float = 6f,
    animate: Boolean = true,
) {
    if (points.size < 2) return

    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = if (animate) 900 else 0, easing = LinearEasing),
        label = "chartReveal",
    )

    // Rango de datos (memorizado para no recalcular en cada frame de animación).
    val bounds = remember(points) {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        // Evita división por cero si todos los valores Y son iguales.
        Bounds(minX, max(maxX, minX + 1f), minY, max(maxY, minY + 1f))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val visibleCount = max(2, (points.size * progress).toInt())
        val visible = points.take(visibleCount)
        drawLineChart(visible, bounds, lineColor, fillColors, strokeWidth)
    }
}

private data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

private fun DrawScope.drawLineChart(
    points: List<ChartPoint>,
    bounds: Bounds,
    lineColor: Color,
    fillColors: List<Color>,
    strokeWidth: Float,
) {
    val w = size.width
    val h = size.height
    val padY = strokeWidth * 2

    fun px(x: Float) = ((x - bounds.minX) / (bounds.maxX - bounds.minX)) * w
    fun py(y: Float) = h - padY - ((y - bounds.minY) / (bounds.maxY - bounds.minY)) * (h - padY * 2)

    val screen = points.map { Offset(px(it.x), py(it.y)) }

    // Relleno bajo la curva
    val fill = Path().apply {
        moveTo(screen.first().x, h)
        screen.forEach { lineTo(it.x, it.y) }
        lineTo(screen.last().x, h)
        close()
    }
    drawPath(fill, brush = Brush.verticalGradient(fillColors, startY = 0f, endY = h), alpha = 0.35f)

    // Línea
    val line = Path().apply {
        moveTo(screen.first().x, screen.first().y)
        for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
    }
    drawPath(
        path = line,
        color = lineColor,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
    )

    // Puntos
    screen.forEach { drawCircle(color = lineColor, radius = strokeWidth * 0.9f, center = it) }
}
