package com.example.kortexgames.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kortexgames.game.GameCategory

/**
 * Fondo decorativo **temático por categoría**. Cada categoría tiene su propio
 * motivo, dibujado en neón con el color de acento, para que cada tarjeta se sienta
 * distinta y "viva":
 *
 *  - Memoria → red neuronal ([NeuralCornerTexture]).
 *  - Cálculo Mental → símbolos matemáticos de distintos tamaños.
 *  - Pensamiento Lógico → piezas de rompecabezas.
 *  - Resto → arcos concéntricos que asoman desde la esquina superior derecha.
 *
 * @param boost 0f..1f: sube la intensidad/brillo del motivo (para la animación de
 *   click, que hace "brillar más" el fondo). 0 = reposo.
 */
@Composable
fun CategoryTexture(
    category: GameCategory,
    modifier: Modifier = Modifier,
    boost: Float = 0f,
) {
    // En reposo ~0.5; al pulsar sube hasta ~1.0 (fondo más brillante).
    val intensity = (0.5f + boost * 0.5f).coerceIn(0f, 1f)
    when (category) {
        GameCategory.MEMORY ->
            NeuralCornerTexture(accent = category.accent, modifier = modifier, intensity = intensity)
        GameCategory.MENTAL_MATH ->
            MathSymbolsTexture(accent = category.accent, modifier = modifier, intensity = intensity)
        GameCategory.LOGIC ->
            PuzzleTexture(accent = category.accent, modifier = modifier, intensity = intensity)
        else ->
            ConcentricArcsTexture(accent = category.accent, modifier = modifier, intensity = intensity)
    }
}

/** Un glifo suelto del fondo matemático. */
private data class Glyph(
    val text: String,
    val xf: Float,
    val yf: Float,
    val sizeSp: Float,
    val rot: Float,
    val alpha: Float,
)

private val MATH_GLYPHS = listOf(
    Glyph("+", 0.80f, 0.14f, 30f, -12f, 0.95f),
    Glyph("π", 0.55f, 0.24f, 22f, 8f, 0.75f),
    Glyph("÷", 0.92f, 0.40f, 20f, 10f, 0.70f),
    Glyph("×", 0.70f, 0.52f, 26f, -6f, 0.85f),
    Glyph("√", 0.36f, 0.14f, 24f, -4f, 0.60f),
    Glyph("=", 0.30f, 0.58f, 18f, 6f, 0.55f),
    Glyph("∑", 0.95f, 0.72f, 28f, -8f, 0.80f),
    Glyph("%", 0.62f, 0.82f, 18f, 12f, 0.50f),
    Glyph("−", 0.16f, 0.34f, 22f, 0f, 0.55f),
    Glyph("∞", 0.44f, 0.44f, 20f, -10f, 0.60f),
    Glyph("8", 0.86f, 0.58f, 34f, 6f, 0.70f),
)

/** Fondo de símbolos matemáticos esparcidos a distintos tamaños y giros. */
@Composable
private fun MathSymbolsTexture(accent: Color, modifier: Modifier, intensity: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        MATH_GLYPHS.forEach { g ->
            val layout = measurer.measure(
                text = g.text,
                style = TextStyle(
                    fontSize = g.sizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.copy(alpha = g.alpha * intensity),
                ),
            )
            val cx = g.xf * w
            val cy = g.yf * h
            val topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f)
            rotate(degrees = g.rot, pivot = Offset(cx, cy)) {
                drawText(layout, topLeft = topLeft)
            }
        }
    }
}

/** Posición/tamaño/giro de cada pieza de rompecabezas. */
private data class Piece(val xf: Float, val yf: Float, val sizeF: Float, val rot: Float, val alpha: Float)

private val PUZZLE_PIECES = listOf(
    Piece(0.66f, 0.06f, 0.46f, -12f, 0.95f),
    Piece(0.30f, 0.44f, 0.34f, 14f, 0.60f),
    Piece(0.90f, 0.60f, 0.40f, 8f, 0.75f),
)

/** Fondo de piezas de rompecabezas (contorno neón) a distintos tamaños. */
@Composable
private fun PuzzleTexture(accent: Color, modifier: Modifier, intensity: Float) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val stroke = 2f.dp.toPx()
        PUZZLE_PIECES.forEach { piece ->
            val s = piece.sizeF * minDim
            val left = piece.xf * size.width - s / 2f
            val top = piece.yf * size.height - s / 2f
            rotate(degrees = piece.rot, pivot = Offset(piece.xf * size.width, piece.yf * size.height)) {
                translate(left = left, top = top) {
                    drawPath(
                        path = puzzlePiecePath(s),
                        color = accent.copy(alpha = piece.alpha * intensity),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * Contorno de una pieza de rompecabezas de lado [s] con "pestañas" (knobs) que
 * sobresalen en los bordes superior y derecho — la silueta clásica reconocible.
 */
private fun puzzlePiecePath(s: Float): Path {
    val k = s * 0.22f // salida del knob
    return Path().apply {
        moveTo(0f, 0f)
        // Borde superior con pestaña hacia arriba.
        lineTo(s * 0.35f, 0f)
        cubicTo(s * 0.30f, -k, s * 0.70f, -k, s * 0.65f, 0f)
        lineTo(s, 0f)
        // Borde derecho con pestaña hacia la derecha.
        lineTo(s, s * 0.35f)
        cubicTo(s + k, s * 0.30f, s + k, s * 0.70f, s, s * 0.65f)
        lineTo(s, s)
        // Bordes inferior e izquierdo rectos.
        lineTo(0f, s)
        close()
    }
}

/**
 * Fondo genérico y sobrio: **arcos concéntricos** centrados en la esquina superior
 * derecha. Como el centro está en la esquina, solo asoma un "trozo" (un cuarto de
 * onda) dentro de la tarjeta, tipo radar/eco. Un degradado radial anclado en la
 * esquina hace que se desvanezcan hacia el centro.
 */
@Composable
private fun ConcentricArcsTexture(accent: Color, modifier: Modifier, intensity: Float) {
    Canvas(modifier = modifier) {
        val corner = Offset(size.width, 0f) // esquina superior derecha
        val ref = size.width
        val brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.7f * intensity), accent.copy(alpha = 0f)),
            center = corner,
            radius = ref * 1.3f,
        )
        val stroke = 2f.dp.toPx()
        // Anillos de radio creciente: solo se ve la parte que cae dentro de la card.
        for (i in 1..6) {
            drawCircle(
                brush = brush,
                radius = ref * (0.20f + i * 0.17f),
                center = corner,
                style = Stroke(width = stroke),
            )
        }
    }
}
