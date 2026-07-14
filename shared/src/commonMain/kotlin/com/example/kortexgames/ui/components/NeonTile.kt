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
 * @param baseMargin margen base entre el borde del tile y el tubo (más pequeño = tubo
 *   más grande dentro del recuadro). Crece un poco al encender para dejar sitio al halo.
 * @param strokeScale factor sobre el grosor del tubo (y sus halos). <1 adelgaza el
 *   neón: útil cuando la celda es pequeña (rejillas densas) y un tubo grueso tapaba la
 *   letra. 1 = grosor estándar.
 * @param rectTopLeft esquina superior izquierda del recuadro del tile dentro del
 *   `DrawScope` actual. Por defecto [Offset.Zero], que es lo correcto cuando el tile
 *   es dueño de todo su `Canvas` (Memoria, Crucigrama). Los tableros que pintan
 *   muchas celdas en un único `Canvas` compartido (p. ej. Tetris Neón) pasan aquí la
 *   posición de cada celda para reutilizar el mismo dibujo sin un `Canvas` por celda.
 * @param rectSize tamaño del recuadro del tile; por defecto el tamaño completo del
 *   `DrawScope` (mismo razonamiento que [rectTopLeft]).
 * @param alpha multiplicador de opacidad global del tile (0..1); sirve para el
 *   fundido de piezas que se desvanecen (limpieza de líneas).
 * @param scale factor de escala del tubo alrededor de su propio centro (1 = tamaño
 *   normal); sirve para el encogimiento de piezas que se desvanecen.
 */
fun DrawScope.drawNeonTile(
    baseColor: Color,
    activeAmt: Float,
    cornerRadius: Dp = 22.dp,
    sparks: Boolean = true,
    baseMargin: Dp = 7.dp,
    strokeScale: Float = 1f,
    rectTopLeft: Offset = Offset.Zero,
    rectSize: Size = size,
    alpha: Float = 1f,
    scale: Float = 1f,
) {
    if (alpha <= 0f) return
    val corner = CornerRadius(cornerRadius.toPx() * scale, cornerRadius.toPx() * scale)

    // Borde: en reposo un neón atenuado (tubo "en espera"); encendido, color pleno.
    val idle = lerp(baseColor, LogicColors.SurfaceVariantDark, 0.38f)
    val edge = lerp(idle, baseColor, activeAmt)

    val stroke = (2.4.dp + 1.8.dp * activeAmt).toPx() * strokeScale
    // Margen para que los halos quepan dentro de los límites del tile (crece al encender).
    val margin = (baseMargin + 2.dp * activeAmt).toPx()
    val fullSize = Size(rectSize.width - margin * 2f, rectSize.height - margin * 2f)
    // El encogimiento ([scale]) se hace alrededor del centro del recuadro, no de su
    // esquina, para que el tubo se contraiga "in situ" en vez de desplazarse.
    val center = rectTopLeft + Offset(rectSize.width / 2f, rectSize.height / 2f)
    val shrunkSize = Size(fullSize.width * scale, fullSize.height * scale)
    val topLeft = center - Offset(shrunkSize.width / 2f, shrunkSize.height / 2f)

    // Relleno interior: hueco en reposo; al encenderse se llena con un tinte radial.
    if (activeAmt > 0f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    baseColor.copy(alpha = 0.30f * activeAmt * alpha),
                    baseColor.copy(alpha = 0.04f * activeAmt * alpha),
                ),
                center = center,
                radius = rectSize.minDimension / 1.3f,
            ),
            topLeft = topLeft,
            size = shrunkSize,
            cornerRadius = corner,
        )
    }

    // Halo exterior ancho y translúcido (respira con el encendido).
    drawRoundRect(
        color = edge.copy(alpha = 0.30f * (0.35f + 0.65f * activeAmt) * alpha),
        topLeft = topLeft,
        size = shrunkSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * 4.5f),
    )
    // Halo intermedio: da cuerpo al resplandor.
    drawRoundRect(
        color = edge.copy(alpha = 0.55f * (0.45f + 0.55f * activeAmt) * alpha),
        topLeft = topLeft,
        size = shrunkSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * 2.1f),
    )
    // Trazo nítido del "tubo" neón.
    drawRoundRect(
        color = edge.copy(alpha = alpha),
        topLeft = topLeft,
        size = shrunkSize,
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    // Núcleo blanco interior del tubo al encender (el look "prendido" del neón real).
    if (activeAmt > 0f) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f * activeAmt * alpha),
            topLeft = topLeft,
            size = shrunkSize,
            cornerRadius = corner,
            style = Stroke(width = stroke * 0.42f),
        )
    }

    if (sparks && activeAmt > 0.05f) {
        drawTileSparks(edge.copy(alpha = edge.alpha * alpha), topLeft.y, center.x, activeAmt)
    }
}

/**
 * Dibuja una burbuja como **globo de neón**: el mismo lenguaje visual que
 * [drawNeonTile] (halo ancho translúcido → halo intermedio → aro nítido → núcleo
 * blanco) pero circular, para que las burbujas de "Burbujas de Cálculo" compartan la
 * estética de tubo neón de las teclas de Memoria. Sobre esos aros hay un relleno
 * radial tenue que "enciende el cristal" sin tapar el texto del centro, y un pequeño
 * reflejo especular arriba que le da volumen de globo.
 *
 * A diferencia de las teclas, una burbuja está siempre "encendida" (es un objeto vivo
 * en caída), por lo que no interpola apagado→encendido; [glow] solo modula la
 * intensidad del halo por si se quiere un latido externo (no abusar: §9.4 pide no
 * pulsar muchos elementos a la vez).
 *
 * @param baseColor color neón de la burbuja (identidad + halo).
 * @param glow multiplicador del halo exterior; 1 = pleno.
 */
fun DrawScope.drawNeonBubble(baseColor: Color, glow: Float = 1f) {
    val center = Offset(size.width / 2f, size.height / 2f)
    // Margen para que el halo ancho quepa dentro de los límites del composable.
    val margin = 6.dp.toPx()
    val radius = size.minDimension / 2f - margin
    val stroke = 3.dp.toPx()

    // Relleno "cristal": tinte radial suave, más brillante en la zona alta (como si
    // la luz entrara por arriba); se apaga hacia el borde para no comerse el texto.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                baseColor.copy(alpha = 0.34f),
                baseColor.copy(alpha = 0.12f),
                baseColor.copy(alpha = 0.03f),
            ),
            center = Offset(size.width / 2f, size.height * 0.38f),
            radius = radius * 1.25f,
        ),
        radius = radius,
        center = center,
    )
    // Halo exterior ancho y translúcido (respira con [glow]).
    drawCircle(baseColor.copy(alpha = 0.26f * glow), radius, center, style = Stroke(stroke * 4.5f))
    // Halo intermedio: da cuerpo al resplandor.
    drawCircle(baseColor.copy(alpha = 0.50f * glow), radius, center, style = Stroke(stroke * 2.1f))
    // Aro nítido del "tubo" neón.
    drawCircle(baseColor, radius, center, style = Stroke(stroke))
    // Núcleo blanco interior del tubo (el look "prendido" del neón real).
    drawCircle(Color.White.copy(alpha = 0.55f), radius, center, style = Stroke(stroke * 0.4f))

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
