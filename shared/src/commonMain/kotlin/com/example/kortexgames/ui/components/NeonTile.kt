package com.example.kortexgames.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors

/**
 * Dibuja un tile/celda como **tubo de neón hueco**: apila varios trazos del mismo
 * contorno con anchura decreciente y alfa creciente para simular resplandor sin coste
 * de blur —halo ancho translúcido → halo intermedio → trazo nítido → núcleo blanco— y,
 * al encenderse, rellena el "cristal" con un tinte radial suave.
 *
 * Es la **fuente única** del lenguaje de "borde neón" de la app (mismo truco que
 * [NeonFrame]): lo usan el juego de Memoria y el Crucigrama para que todos los tableros
 * compartan idéntica estética. Centralizarlo evita que cada juego derive su propio
 * borde y se desincronicen.
 *
 * @param baseColor color neón del tile.
 * @param activeAmt 0 = apagado (contorno tenue, hueco), 1 = encendido (color pleno + relleno).
 * @param cornerRadius radio de esquina del contorno (debe coincidir con el `clip` del tile).
 * @param sparks si true, dibuja tres chispas sobre el borde superior mientras enciende.
 */
fun DrawScope.drawNeonTile(
    baseColor: Color,
    activeAmt: Float,
    cornerRadius: Dp = 22.dp,
    sparks: Boolean = true,
) {
    val corner = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())

    // Borde: en reposo un neón atenuado (tubo "en espera"); encendido, color pleno.
    val idle = lerp(baseColor, LogicColors.SurfaceVariantDark, 0.38f)
    val edge = lerp(idle, baseColor, activeAmt)

    val stroke = (2.4.dp + 1.8.dp * activeAmt).toPx()
    // Margen para que los halos quepan dentro de los límites del tile (crece al encender).
    val margin = (7.dp + 2.dp * activeAmt).toPx()
    val topLeft = Offset(margin, margin)
    val rectSize = Size(size.width - margin * 2f, size.height - margin * 2f)

    // Relleno interior: hueco en reposo; al encenderse se llena con un tinte radial.
    if (activeAmt > 0f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    baseColor.copy(alpha = 0.30f * activeAmt),
                    baseColor.copy(alpha = 0.04f * activeAmt),
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension / 1.3f,
            ),
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = corner,
        )
    }

    // Halo exterior ancho y translúcido (respira con el encendido).
    drawRoundRect(
        color = edge.copy(alpha = 0.30f * (0.35f + 0.65f * activeAmt)),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * 4.5f),
    )
    // Halo intermedio: da cuerpo al resplandor.
    drawRoundRect(
        color = edge.copy(alpha = 0.55f * (0.45f + 0.55f * activeAmt)),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * 2.1f),
    )
    // Trazo nítido del "tubo" neón.
    drawRoundRect(
        color = edge,
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    // Núcleo blanco interior del tubo al encender (el look "prendido" del neón real).
    if (activeAmt > 0f) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f * activeAmt),
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = corner,
            style = Stroke(width = stroke * 0.42f),
        )
    }

    if (sparks && activeAmt > 0.05f) {
        drawTileSparks(edge, topLeft.y, size.width / 2f, activeAmt)
    }
}

/**
 * Tres chispas sobre el borde superior del tile: una vertical al centro y dos
 * diagonales a los lados, con alfa proporcional a [amt] para que aparezcan y se
 * apaguen junto con el encendido.
 */
private fun DrawScope.drawTileSparks(color: Color, topY: Float, cx: Float, amt: Float) {
    val gap = 3.dp.toPx()
    val len = 6.dp.toPx()
    val spread = 8.dp.toPx()
    val w = 2.dp.toPx()
    val c = color.copy(alpha = 0.9f * amt)
    val baseY = topY - gap

    // Central: vertical hacia arriba.
    drawLine(c, Offset(cx, baseY), Offset(cx, baseY - len), w, StrokeCap.Round)
    // Izquierda: diagonal hacia arriba-afuera.
    drawLine(
        c,
        Offset(cx - spread, baseY),
        Offset(cx - spread - len * 0.7f, baseY - len * 0.7f),
        w,
        StrokeCap.Round,
    )
    // Derecha: diagonal hacia arriba-afuera.
    drawLine(
        c,
        Offset(cx + spread, baseY),
        Offset(cx + spread + len * 0.7f, baseY - len * 0.7f),
        w,
        StrokeCap.Round,
    )
}
