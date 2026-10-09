package com.kortexgames.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale

/*
 * # Glifos del "bosque neón"
 *
 * El pino, la tienda y la mata de pasto de Neon Trees & Tents, dibujados como tubos de luz. Viven
 * en `ui/components` (y no dentro del juego) porque los pintan dos sitios que deben verse
 * idénticos: el tablero y la miniatura del catálogo/antesala (`CategoryTexture`). Con el dibujo
 * duplicado, retocar la silueta en uno dejaría al otro desfasado.
 *
 * No son contornos de tile, así que no pueden llamar a [drawNeonTile]; replican su MISMA receta
 * de capas (§9.7): halo ancho → halo intermedio → trazo nítido → núcleo blanco, con las mismas
 * proporciones de grosor (×4,5 / ×2,1 / ×1 / ×0,42), para que el neón de estas figuras sea el
 * mismo que el del resto de tableros.
 *
 * Son funciones puras: la animación (balanceo, encendido, escala de entrada) llega por parámetro
 * desde quien lleva el reloj; aquí no hay estado.
 */

/**
 * Traza [path] con la receta de capas del neón de la app.
 *
 * @param stroke grosor del trazo nítido; los halos se derivan de él.
 * @param glow 0..1: cuánto "encendido" tiene el tubo (intensidad de halos y núcleo blanco).
 */
private fun DrawScope.drawNeonPath(path: Path, color: Color, stroke: Float, glow: Float, alpha: Float) {
    fun layer(c: Color, width: Float) =
        drawPath(path, c, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))

    layer(color.copy(alpha = 0.30f * (0.35f + 0.65f * glow) * alpha), stroke * 4.5f)
    layer(color.copy(alpha = 0.55f * (0.45f + 0.55f * glow) * alpha), stroke * 2.1f)
    layer(color.copy(alpha = alpha), stroke)
    if (glow > 0f) layer(Color.White.copy(alpha = 0.55f * glow * alpha), stroke * 0.42f)
}

/**
 * Pino de neón: tres pisos de copa escalonados sobre un tronco corto.
 *
 * La silueta es un único contorno cerrado (no tres triángulos sueltos) para que el halo no se
 * duplique donde los pisos se montarían, que es lo que hace que un dibujo de neón parezca sucio.
 *
 * @param center centro de la casilla.
 * @param side lado de la casilla; el pino ocupa ~70 % para dejar respirar la rejilla.
 * @param glow 0..1, encendido. En reposo conviene bajo: hay muchos árboles por tablero y con
 *   todos a tope competirían con las tiendas. Por encima de 0,6 se le encienden además unas
 *   lucecitas en las puntas — la señal de "este árbol ya tiene su tienda".
 * @param sway inclinación en grados alrededor de la BASE del tronco (el árbol se mece, no gira
 *   sobre su centro).
 * @param scale escala alrededor de la base: el árbol "brota" del suelo al aparecer.
 */
fun DrawScope.drawNeonPine(
    center: Offset,
    side: Float,
    color: Color,
    glow: Float = 0.35f,
    alpha: Float = 1f,
    sway: Float = 0f,
    scale: Float = 1f,
) {
    if (alpha <= 0f || scale <= 0f) return
    val h = side * 0.70f
    val top = center.y - h / 2f
    val trunkTop = top + h * 0.80f
    val bottom = top + h
    val cx = center.x
    // Semianchos de cada piso (abajo) y de la muesca donde arranca el siguiente.
    val w1 = side * 0.15f
    val w2 = side * 0.22f
    val w3 = side * 0.29f
    val notch = side * 0.08f
    val y1 = top + h * 0.30f
    val y2 = top + h * 0.56f
    val trunk = side * 0.045f
    val base = Offset(cx, bottom)

    // Aura: da el "resplandor" del árbol encendido sin engordar el trazo.
    if (glow > 0f) {
        val reach = side * 0.62f
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.26f * glow * alpha), Color.Transparent), center, reach),
            radius = reach,
            center = center,
        )
    }

    rotate(sway, pivot = base) {
        scale(scale, scale, pivot = base) {
            val crown = Path().apply {
                moveTo(cx, top)
                lineTo(cx + w1, y1); lineTo(cx + notch, y1)
                lineTo(cx + w2, y2); lineTo(cx + notch, y2)
                lineTo(cx + w3, trunkTop)
                lineTo(cx - w3, trunkTop)
                lineTo(cx - notch, y2); lineTo(cx - w2, y2)
                lineTo(cx - notch, y1); lineTo(cx - w1, y1)
                close()
            }
            drawPath(
                crown,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = (0.30f + 0.25f * glow) * alpha), color.copy(alpha = 0.10f * alpha)),
                    startY = top,
                    endY = trunkTop,
                ),
            )
            val stroke = side * 0.045f
            drawNeonPath(crown, color, stroke, glow, alpha)
            drawLine(
                color = color.copy(alpha = 0.85f * alpha),
                start = Offset(cx, trunkTop + stroke),
                end = base,
                strokeWidth = trunk * 2f,
                cap = StrokeCap.Round,
            )
            // Lucecitas en las puntas: solo con el árbol bien encendido.
            val lights = ((glow - 0.6f) / 0.4f).coerceIn(0f, 1f) * alpha
            if (lights > 0f) {
                val dot = side * 0.032f
                // Función local y no una lista de puntas: esto corre por árbol y por frame.
                fun light(x: Float, y: Float) {
                    val tip = Offset(x, y)
                    drawCircle(color.copy(alpha = 0.45f * lights), dot * 2.4f, tip)
                    drawCircle(Color.White.copy(alpha = 0.95f * lights), dot, tip)
                }
                light(cx, top)
                light(cx + w1, y1); light(cx - w1, y1)
                light(cx + w2, y2); light(cx - w2, y2)
                light(cx + w3, trunkTop); light(cx - w3, trunkTop)
            }
        }
    }
}

/**
 * Tienda de neón: pirámide translúcida con la abertura marcada.
 *
 * @param center centro de la casilla.
 * @param side lado de la casilla.
 * @param glow 0..1, encendido del tubo (sube en el destello al plantarla).
 * @param scale escala alrededor de [center]; es el "pop" de entrada con resorte.
 * @param ember 0..1: luz cálida que sale por la abertura, como una lámpara dentro. Quien anima
 *   la hace titilar; con 0 la tienda está "apagada" (miniaturas, tiendas en error).
 * @param emberColor color de esa luz.
 */
fun DrawScope.drawNeonTent(
    center: Offset,
    side: Float,
    color: Color,
    glow: Float = 0.6f,
    alpha: Float = 1f,
    scale: Float = 1f,
    ember: Float = 0f,
    emberColor: Color = color,
) {
    if (alpha <= 0f || scale <= 0f) return
    scale(scale, scale, pivot = center) {
        val h = side * 0.54f
        val half = side * 0.31f
        val top = center.y - h / 2f
        val bottom = center.y + h / 2f
        val body = Path().apply {
            moveTo(center.x, top)
            lineTo(center.x + half, bottom)
            lineTo(center.x - half, bottom)
            close()
        }
        drawPath(
            body,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.42f * alpha), color.copy(alpha = 0.12f * alpha)),
                startY = top,
                endY = bottom,
            ),
        )
        // Abertura: una V invertida corta desde la base. Es lo que hace que el triángulo se lea
        // como tienda y no como una señal de advertencia.
        val door = Path().apply {
            moveTo(center.x - half * 0.30f, bottom)
            lineTo(center.x, bottom - h * 0.42f)
            lineTo(center.x + half * 0.30f, bottom)
        }
        val lamp = ember.coerceIn(0f, 1f) * alpha
        if (lamp > 0f) {
            val hearth = Offset(center.x, bottom - h * 0.10f)
            val reach = side * 0.34f
            drawCircle(
                brush = Brush.radialGradient(listOf(emberColor.copy(alpha = 0.55f * lamp), Color.Transparent), hearth, reach),
                radius = reach,
                center = hearth,
            )
            drawPath(Path().apply { addPath(door); close() }, emberColor.copy(alpha = 0.85f * lamp))
        }
        val stroke = side * 0.05f
        drawNeonPath(body, color, stroke, glow, alpha)
        drawPath(
            door,
            color.copy(alpha = 0.9f * alpha),
            style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Mata de pasto: tres briznas curvas que nacen del mismo punto.
 *
 * Deliberadamente SIN halo de neón: marca una casilla descartada y tiene que quedarse en segundo
 * plano frente a árboles y tiendas.
 *
 * @param sway desplazamiento horizontal de las puntas, como fracción de [side] (la brisa).
 * @param scale escala alrededor de la base: la mata brota al marcar la casilla.
 */
fun DrawScope.drawNeonGrass(
    center: Offset,
    side: Float,
    color: Color,
    alpha: Float = 1f,
    sway: Float = 0f,
    scale: Float = 1f,
) {
    if (alpha <= 0f || scale <= 0f) return
    val base = Offset(center.x, center.y + side * 0.13f)
    scale(scale, scale, pivot = base) {
        // Las tres briznas van en UN solo path: puede haber decenas de matas en pantalla y esto
        // se redibuja en cada frame.
        val blades = Path()

        /** Añade una brizna; [lean] y [height] en fracciones de la casilla. */
        fun blade(lean: Float, height: Float) {
            blades.moveTo(base.x + lean * side * 0.25f, base.y)
            blades.quadraticTo(
                base.x + lean * side * 0.35f, base.y - height * side * 0.6f,
                base.x + (lean + sway) * side, base.y - height * side,
            )
        }
        blade(-0.13f, 0.19f)
        blade(0f, 0.28f)
        blade(0.13f, 0.21f)
        drawPath(blades, color.copy(alpha = alpha), style = Stroke(width = side * 0.036f, cap = StrokeCap.Round))
    }
}
