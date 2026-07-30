package com.kortexgames.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameMotif
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fondo decorativo de una tarjeta. Prioriza el **motivo propio del juego**
 * ([motif]) cuando existe, para que juegos de la misma categoría no se vean
 * idénticos; si no hay motivo, cae al **motivo temático de la categoría**:
 *
 *  - [GameMotif.WORD_SEARCH] → sopa de letras con palabras marcadas/tachadas.
 *  - [GameMotif.CROSSWORD] → rejilla de crucigrama entrelazada.
 *  - [GameMotif.WORD_WHEEL] → rueda de letras conectadas.
 *  - [GameMotif.POTIONS] → frasquitos de poción con líquido.
 *  - [GameMotif.TETROMINO] → piezas de tetris (tetrominós).
 *  - [GameMotif.SUDOKU_GRID] → cuadrícula 9×9 con números.
 *  - [GameMotif.NUMBER_TILES] → recuadros con potencias de dos (2048).
 *  - [GameMotif.MINESWEEPER] → rejilla de buscaminas con números, bandera y mina.
 *  - [GameMotif.CIRCUIT_FLOW] → nodos unidos por tuberías redondeadas en rejilla.
 *  - [GameMotif.HYPERGATE] → portal circular con cometas precipitándose hacia él.
 *  - [GameMotif.NEON_PULSE] → pulsos concéntricos espaciados y escalonados en tamaño.
 *  - [GameMotif.SEQUENCE_GRID] → rejilla 3×3 de pads con uno iluminado.
 *  - [GameMotif.MATH_BUBBLES] → burbujas con operaciones y un objetivo.
 *  - [GameMotif.ENERGY_PIPES] → tablero de tuberías con nodos-objetivo conectados.
 *  - [GameMotif.POLARITY_SECTORS] → círculo de 4 sectores con partículas entrantes.
 *  - Memoria → red neuronal ([NeuralCornerTexture]).
 *  - Cálculo Mental → símbolos matemáticos de distintos tamaños.
 *  - Pensamiento Lógico → piezas de rompecabezas.
 *  - Resto → arcos concéntricos que asoman desde la esquina superior derecha.
 *
 * Todos los motivos se tiñen con el acento de la categoría ([GameCategory.accent])
 * para conservar la identidad de color aunque el dibujo cambie por juego.
 *
 * @param motif motivo específico del juego; null = usar el de la categoría.
 * @param boost 0f..1f: sube la intensidad/brillo del motivo (para la animación de
 *   click, que hace "brillar más" el fondo). 0 = reposo.
 * @param centered si true, los motivos que se dibujan escorados a la derecha (para no
 *   tapar el título de la tarjeta) se **recentran**; se usa al reutilizar el motivo
 *   como icono cuadrado (recuadro héroe de la intro), donde no hay texto que esquivar.
 */
@Composable
fun CategoryTexture(
    category: GameCategory,
    modifier: Modifier = Modifier,
    boost: Float = 0f,
    motif: GameMotif? = null,
    centered: Boolean = false,
) {
    // En reposo ~0.5; al pulsar sube hasta ~1.0 (fondo más brillante).
    val intensity = (0.5f + boost * 0.5f).coerceIn(0f, 1f)
    val accent = category.accent
    // El motivo del juego manda sobre el de la categoría (desambigua juegos que la
    // comparten). Solo si no hay motivo propio se usa el fondo de la categoría.
    if (motif != null) {
        MotifTexture(motif = motif, accent = accent, modifier = modifier, intensity = intensity, centered = centered)
    } else {
        when (category) {
            GameCategory.MEMORY ->
                NeuralCornerTexture(accent = accent, modifier = modifier, intensity = intensity)
            GameCategory.MENTAL_MATH ->
                MathSymbolsTexture(accent = accent, modifier = modifier, intensity = intensity)
            GameCategory.LOGIC ->
                PuzzleTexture(accent = accent, modifier = modifier, intensity = intensity)
            else ->
                ConcentricArcsTexture(accent = accent, modifier = modifier, intensity = intensity)
        }
    }
}

/**
 * Renderiza el **motivo de un juego** ([GameMotif]) como icono cuadrado, recentrado
 * ([CategoryTexture] con `centered = true`) para que su contenido —pensado para
 * escorarse a la derecha en la tarjeta— quede equilibrado dentro del recuadro. Es la
 * fuente única del "arte" del juego, reutilizada por el recuadro héroe de la intro y
 * por las miniaturas de la Home (antes eran imágenes `DrawableResource`).
 *
 * @param accent color de acento con el que teñir el motivo (normalmente el de la categoría).
 */
@Composable
fun GameMotifIcon(
    motif: GameMotif,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    MotifTexture(
        motif = motif,
        accent = accent,
        modifier = modifier,
        intensity = 1f, // icono estático a brillo pleno (no hay animación de pulsación)
        centered = true,
    )
}

/**
 * Reparte el dibujo del [motif] a su textura concreta, teñida con [accent]. Es el
 * único punto que conoce el `when` de motivos, compartido por [CategoryTexture] (fondo
 * de tarjeta) y [GameMotifIcon] (icono héroe/miniatura). Los motivos que se escoran a
 * la derecha respetan [centered] para recentrarse cuando se usan como icono.
 */
@Composable
private fun MotifTexture(
    motif: GameMotif,
    accent: Color,
    modifier: Modifier,
    intensity: Float,
    centered: Boolean,
) {
    when (motif) {
        GameMotif.WORD_SEARCH -> WordSearchTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.CROSSWORD -> CrosswordTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.WORD_WHEEL -> WordWheelTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.POTIONS -> PotionsTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.TETROMINO -> TetrominoTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.SUDOKU_GRID -> SudokuTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.NUMBER_TILES -> NumberTilesTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.MINESWEEPER -> MinesweeperTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.CIRCUIT_FLOW -> CircuitFlowTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.HYPERGATE -> HypergateTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.NEON_PULSE -> NeonPulseTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.SEQUENCE_GRID -> SequenceGridTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.MATH_BUBBLES -> MathBubblesTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
        GameMotif.ENERGY_PIPES -> EnergyPipesTexture(accent = accent, modifier = modifier, intensity = intensity)
        GameMotif.POLARITY_SECTORS -> PolaritySectorsTexture(accent = accent, modifier = modifier, intensity = intensity, centered = centered)
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

// ─────────────────────────────────────────────────────────────────────────────
//  Motivos propios de juego (desambiguan juegos que comparten categoría).
//  Todos se dibujan en el lado derecho de la tarjeta, tenues, para no competir
//  con el título (texto claro sobre la izquierda/inferior).
// ─────────────────────────────────────────────────────────────────────────────

/** Dibuja un carácter centrado en ([cx],[cy]) con la fuente monoespaciada del juego. */
private fun DrawScope.drawGlyph(
    measurer: TextMeasurer,
    ch: String,
    cx: Float,
    cy: Float,
    sizeSp: Float,
    color: Color,
) {
    val layout = measurer.measure(
        text = ch,
        style = TextStyle(
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, // rejilla regular, tipo tablero
            color = color,
        ),
    )
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}

/** Filas de la sopa de letras (6×5). Contenido fijo → estable entre recomposiciones. */
private val WORD_SEARCH_ROWS = listOf(
    "LEXICO",
    "NEONAP",
    "GRIDKV",
    "WKORTX",
    "SYPLAY",
)

/**
 * **Sopa de Letras**: cuadrícula de letras en el lado derecho, con dos palabras
 * "encontradas" —una dentro de una cápsula y otra tachada— tal como se marcan al
 * resolverlas en el juego. La rejilla base va tenue para no tapar el título.
 */
@Composable
private fun WordSearchTexture(accent: Color, modifier: Modifier, intensity: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val cols = WORD_SEARCH_ROWS.first().length
        val rows = WORD_SEARCH_ROWS.size
        // La rejilla ocupa la mitad derecha y asoma un poco por el borde derecho.
        val cell = size.height / (rows + 0.4f)
        val gridW = cell * cols
        val originX = size.width - gridW + cell * 0.6f // bleed sutil a la derecha
        val originY = (size.height - cell * rows) / 2f
        val glyphSize = (cell * 0.42f / density).coerceAtLeast(10f)

        fun cx(c: Int) = originX + cell * (c + 0.5f)
        fun cy(r: Int) = originY + cell * (r + 0.5f)

        // Marca 1: cápsula alrededor de "NEON" (fila 1, col 0..3) — palabra hallada.
        val capH = cell * 0.82f
        drawRoundRect(
            color = accent.copy(alpha = 0.16f * intensity),
            topLeft = Offset(cx(0) - cell * 0.5f + cell * 0.1f, cy(1) - capH / 2f),
            size = Size(cell * 3.8f, capH),
            cornerRadius = CornerRadius(capH / 2f),
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.55f * intensity),
            topLeft = Offset(cx(0) - cell * 0.5f + cell * 0.1f, cy(1) - capH / 2f),
            size = Size(cell * 3.8f, capH),
            cornerRadius = CornerRadius(capH / 2f),
            style = Stroke(width = 1.5f.dp.toPx()),
        )

        // Letras: las de las palabras marcadas brillan; el resto queda tenue.
        WORD_SEARCH_ROWS.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val onWord = (r == 1 && c <= 3) || (r == 0) // "NEON" y "LEXICO"
                val alpha = if (onWord) 0.85f else 0.22f
                drawGlyph(measurer, ch.toString(), cx(c), cy(r), glyphSize, accent.copy(alpha = alpha * intensity))
            }
        }

        // Marca 2: tachado sobre "LEXICO" (fila 0, col 0..5) — palabra hallada.
        drawLine(
            color = accent.copy(alpha = 0.7f * intensity),
            start = Offset(cx(0) - cell * 0.34f, cy(0)),
            end = Offset(cx(cols - 1) + cell * 0.34f, cy(0)),
            strokeWidth = 2f.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Bloques del crucigrama (5×5): true = casilla rellena; false = casilla abierta. */
private val CROSSWORD_BLOCKS = listOf(
    booleanArrayOf(false, false, false, true, false),
    booleanArrayOf(true, false, true, false, false),
    booleanArrayOf(false, false, false, false, true),
    booleanArrayOf(false, true, false, false, false),
    booleanArrayOf(true, false, false, true, false),
)

/** Letras sueltas del crucigrama: (fila, col) → carácter. Deletrean "NEON" en diagonal. */
private val CROSSWORD_LETTERS = mapOf(
    (0 to 0) to "N", (1 to 1) to "E", (2 to 2) to "O", (3 to 3) to "N",
)

/**
 * **Crucigrama**: rejilla entrelazada anclada arriba a la derecha (asoma por las
 * esquinas superior/derecha, como los arcos), con casillas rellenas y abiertas
 * alternadas —la silueta inconfundible de un crucigrama— y unas pocas letras.
 */
@Composable
private fun CrosswordTexture(accent: Color, modifier: Modifier, intensity: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val n = CROSSWORD_BLOCKS.size
        val cell = size.height / (n - 0.2f)
        // Ancla arriba-derecha; deja que la fila superior y la col derecha se recorten.
        val originX = size.width - cell * (n - 0.6f)
        val originY = -cell * 0.4f
        val radius = CornerRadius(cell * 0.14f)
        val stroke = 1.5f.dp.toPx()
        val glyphSize = (cell * 0.4f / density).coerceAtLeast(9f)

        for (r in 0 until n) {
            for (c in 0 until CROSSWORD_BLOCKS[r].size) {
                val left = originX + c * cell
                val top = originY + r * cell
                val inset = cell * 0.08f
                val cellSize = Size(cell - inset * 2, cell - inset * 2)
                val tl = Offset(left + inset, top + inset)
                if (CROSSWORD_BLOCKS[r][c]) {
                    // Casilla "negra" del crucigrama: relleno tenue.
                    drawRoundRect(
                        color = accent.copy(alpha = 0.28f * intensity),
                        topLeft = tl, size = cellSize, cornerRadius = radius,
                    )
                } else {
                    // Casilla abierta: solo contorno.
                    drawRoundRect(
                        color = accent.copy(alpha = 0.4f * intensity),
                        topLeft = tl, size = cellSize, cornerRadius = radius,
                        style = Stroke(width = stroke),
                    )
                    CROSSWORD_LETTERS[r to c]?.let { ch ->
                        drawGlyph(
                            measurer, ch,
                            left + cell / 2f, top + cell / 2f,
                            glyphSize, accent.copy(alpha = 0.9f * intensity),
                        )
                    }
                }
            }
        }
    }
}

/** Letras de la rueda de Palabras Conectadas, en orden alrededor del círculo. */
private val WORD_WHEEL_LETTERS = listOf("K", "O", "R", "T", "E", "X")

/**
 * **Palabras Conectadas**: rueda de letras a la derecha unida por un trazo que
 * "arrastra" una palabra (K-O-R-T resaltado) sobre el resto, tenue — igual que el
 * gesto de deslizar para formar palabras en el juego.
 */
@Composable
private fun WordWheelTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val center = Offset(size.width * (if (centered) 0.5f else 0.68f), size.height * 0.5f)
        val radius = size.minDimension * 0.34f
        val n = WORD_WHEEL_LETTERS.size
        // Nodo i en el ángulo i; empieza arriba y gira en sentido horario.
        fun nodeAt(i: Int): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(
                center.x + radius * cos(a).toFloat(),
                center.y + radius * sin(a).toFloat(),
            )
        }

        // Círculo guía tenue de la rueda.
        drawCircle(
            color = accent.copy(alpha = 0.14f * intensity),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5f.dp.toPx()),
        )

        // Trazo de la palabra en curso: une los 4 primeros nodos (K-O-R-T).
        val traced = 3
        for (i in 0 until traced) {
            drawLine(
                color = accent.copy(alpha = 0.6f * intensity),
                start = nodeAt(i),
                end = nodeAt(i + 1),
                strokeWidth = 2.5f.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Nodos + letras: los del trazo brillan; el resto queda tenue.
        val nodeR = radius * 0.3f
        val glyphSize = (nodeR * 1.1f / density).coerceAtLeast(11f)
        WORD_WHEEL_LETTERS.forEachIndexed { i, ch ->
            val p = nodeAt(i)
            val active = i <= traced
            drawCircle(
                color = accent.copy(alpha = (if (active) 0.22f else 0.1f) * intensity),
                radius = nodeR,
                center = p,
            )
            drawCircle(
                color = accent.copy(alpha = (if (active) 0.7f else 0.3f) * intensity),
                radius = nodeR,
                center = p,
                style = Stroke(width = 1.5f.dp.toPx()),
            )
            drawGlyph(measurer, ch, p.x, p.y, glyphSize, accent.copy(alpha = (if (active) 0.95f else 0.4f) * intensity))
        }
    }
}

/** Posición/tamaño/giro/opacidad/nivel de líquido de cada frasquito de poción. */
private data class Potion(
    val xf: Float,
    val yf: Float,
    val sizeF: Float,
    val rot: Float,
    val alpha: Float,
    val fill: Float, // 0f..1f: fracción del bulbo llena de líquido
)

private val POTIONS = listOf(
    Potion(0.66f, 0.34f, 0.46f, -8f, 0.95f, 0.62f),
    Potion(0.96f, 0.72f, 0.35f, 7f, 0.7f, 0.5f),
    Potion(0.44f, 0.74f, 0.55f, 12f, 0.5f, 0.72f),
    Potion(0.38f, 0.15f, 0.50f, 15f, 0.5f, 0.72f),
)

/**
 * Silueta continua de un matraz/poción dentro de una caja [bw]×[h]: cuello recto
 * arriba que se ensancha por los hombros hasta un bulbo de fondo redondeado. Es
 * **un solo trazo** (no un círculo + palito sueltos) para que se lea como un frasco
 * y no como un globo.
 */
private fun potionPath(bw: Float, h: Float): Path {
    val midX = bw / 2f
    val neckHalf = bw * 0.15f
    val mouthY = h * 0.12f
    val neckBotY = h * 0.40f
    val bulbHalf = bw * 0.46f
    val bulbMidY = h * 0.70f
    val bulbBotY = h * 0.98f
    return Path().apply {
        moveTo(midX - neckHalf, mouthY)
        lineTo(midX - neckHalf, neckBotY)                                              // cuello izq.
        cubicTo(midX - neckHalf, h * 0.52f, midX - bulbHalf, h * 0.54f, midX - bulbHalf, bulbMidY) // hombro izq.
        cubicTo(midX - bulbHalf, bulbBotY, midX + bulbHalf, bulbBotY, midX + bulbHalf, bulbMidY)    // fondo redondo
        cubicTo(midX + bulbHalf, h * 0.54f, midX + neckHalf, h * 0.52f, midX + neckHalf, neckBotY)  // hombro der.
        lineTo(midX + neckHalf, mouthY)                                                // cuello der.
        close()                                                                        // boca
    }
}

/**
 * **Ordena las Pociones**: matraces de poción esparcidos a distintos tamaños y
 * giros —silueta de frasco con líquido, superficie y corcho— en lugar del motivo
 * genérico de piezas de rompecabezas de la categoría Lógica, para reflejar la
 * mecánica real del juego (trasvasar líquidos entre frascos).
 */
@Composable
private fun PotionsTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val stroke = 2f.dp.toPx()
        val dx = if (centered) -0.11f * size.width else 0f
        POTIONS.forEach { potion ->
            val h = potion.sizeF * minDim
            val bw = h * 0.62f
            val cx = potion.xf * size.width + dx
            val cy = potion.yf * size.height
            val a = potion.alpha * intensity
            rotate(degrees = potion.rot, pivot = Offset(cx, cy)) {
                translate(left = cx - bw / 2f, top = cy - h / 2f) {
                    val path = potionPath(bw, h)
                    // Superficie del líquido: cuanto mayor fill, más arriba empieza.
                    val liquidY = h * (0.98f - 0.56f * potion.fill)
                    // Líquido: relleno tenue recortado por debajo de su superficie.
                    clipRect(top = liquidY) {
                        drawPath(path = path, color = accent.copy(alpha = a * 0.32f))
                    }
                    // Línea de la superficie del líquido.
                    drawLine(
                        color = accent.copy(alpha = a * 0.7f),
                        start = Offset(bw * 0.1f, liquidY),
                        end = Offset(bw * 0.9f, liquidY),
                        strokeWidth = stroke * 0.8f,
                        cap = StrokeCap.Round,
                    )
                    // Vidrio del frasco: contorno nítido.
                    drawPath(
                        path = path,
                        color = accent.copy(alpha = a),
                        style = Stroke(width = stroke),
                    )
                    // Corcho sobre la boca.
                    val corkW = bw * 0.34f
                    drawRoundRect(
                        color = accent.copy(alpha = a * 0.75f),
                        topLeft = Offset(bw / 2f - corkW / 2f, 0f),
                        size = Size(corkW, h * 0.12f),
                        cornerRadius = CornerRadius(corkW * 0.25f),
                    )
                }
            }
        }
    }
}

/** Tetrominós como listas de celdas (col, fila). Las formas clásicas del Tetris. */
private val TETROMINOES = listOf(
    listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1), // T
    listOf(0 to 0, 0 to 1, 0 to 2, 1 to 2), // L
    listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1), // S
    listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), // O
)

/** Colocación de una pieza: qué tetrominó, dónde (centro), tamaño de celda, giro y opacidad. */
private data class TetroPlacement(
    val shape: Int,
    val xf: Float,
    val yf: Float,
    val cellF: Float,
    val rot: Float,
    val alpha: Float,
)

private val TETRO_PLACEMENTS = listOf(
    TetroPlacement(0, 0.66f, 0.26f, 0.17f, -10f, 0.95f), // T arriba
    TetroPlacement(1, 0.92f, 0.62f, 0.16f, 12f, 0.7f),   // L a la derecha
    TetroPlacement(2, 0.5f, 0.74f, 0.14f, -6f, 0.5f),    // S abajo
    TetroPlacement(3, 0.4f, 0.32f, 0.13f, 8f, 0.45f),    // O
)

/**
 * **Tetris Neón**: piezas de tetrominó (T, L, S, O) esparcidas a distintos tamaños
 * y giros, dibujadas como celdas redondeadas con relleno tenue y contorno neón —el
 * mismo lenguaje de bloque del tablero— para reflejar la mecánica del juego.
 */
@Composable
private fun TetrominoTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val stroke = 1.5f.dp.toPx()
        val dx = if (centered) -0.12f * size.width else 0f
        TETRO_PLACEMENTS.forEach { p ->
            val cell = p.cellF * minDim
            val shape = TETROMINOES[p.shape]
            val cols = shape.maxOf { it.first } + 1
            val rows = shape.maxOf { it.second } + 1
            val cx = p.xf * size.width + dx
            val cy = p.yf * size.height
            val a = p.alpha * intensity
            val inset = cell * 0.08f
            val radius = CornerRadius(cell * 0.2f)
            rotate(degrees = p.rot, pivot = Offset(cx, cy)) {
                // Centra la pieza en (cx, cy).
                val originX = cx - cols * cell / 2f
                val originY = cy - rows * cell / 2f
                shape.forEach { (c, r) ->
                    val tl = Offset(originX + c * cell + inset, originY + r * cell + inset)
                    val cellSize = Size(cell - inset * 2, cell - inset * 2)
                    drawRoundRect(
                        color = accent.copy(alpha = a * 0.28f),
                        topLeft = tl, size = cellSize, cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = a),
                        topLeft = tl, size = cellSize, cornerRadius = radius,
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
    }
}

/** Números "dados" del sudoku: (col, fila) → dígito. Repartidos por la rejilla. */
private val SUDOKU_CLUES = mapOf(
    (1 to 1) to "5", (4 to 2) to "3", (7 to 1) to "9",
    (2 to 4) to "8", (5 to 4) to "1", (8 to 5) to "7",
    (0 to 6) to "4", (3 to 7) to "6", (6 to 6) to "2",
)

/**
 * **Sudoku**: cuadrícula 9×9 anclada a la derecha (asoma por los bordes), con las
 * líneas finas de celda tenues y las **separadoras de bloque 3×3** más marcadas —la
 * seña de identidad del sudoku— y algunos números "dados".
 */
@Composable
private fun SudokuTexture(accent: Color, modifier: Modifier, intensity: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val n = 9
        // La rejilla es algo más alta que la card: asoma por arriba y por abajo.
        val gridSize = size.height * 1.16f
        val cell = gridSize / n
        val originX = size.width - gridSize + cell * 0.5f // bleed sutil a la derecha
        val originY = (size.height - gridSize) / 2f
        val thin = 1f.dp.toPx()
        val thick = 2f.dp.toPx()

        for (i in 0..n) {
            val block = i % 3 == 0
            val w = if (block) thick else thin
            val alpha = (if (block) 0.5f else 0.22f) * intensity
            val col = accent.copy(alpha = alpha)
            // Vertical.
            drawLine(col, Offset(originX + i * cell, originY), Offset(originX + i * cell, originY + gridSize), w)
            // Horizontal.
            drawLine(col, Offset(originX, originY + i * cell), Offset(originX + gridSize, originY + i * cell), w)
        }

        val glyphSize = (cell * 0.55f / density).coerceAtLeast(9f)
        SUDOKU_CLUES.forEach { (pos, digit) ->
            val (c, r) = pos
            drawGlyph(
                measurer, digit,
                originX + (c + 0.5f) * cell, originY + (r + 0.5f) * cell,
                glyphSize, accent.copy(alpha = 0.85f * intensity),
            )
        }
    }
}

/** Recuadro de 2048: posición (centro), tamaño, valor y opacidad. */
private data class NumberTile(val xf: Float, val yf: Float, val sizeF: Float, val value: String, val alpha: Float)

private val NUMBER_TILES = listOf(
    NumberTile(0.64f, 0.28f, 0.34f, "2", 0.95f),
    NumberTile(0.92f, 0.6f, 0.3f, "8", 0.7f),
    NumberTile(0.46f, 0.72f, 0.26f, "4", 0.55f),
    NumberTile(0.86f, 0.2f, 0.24f, "16", 0.5f),
)

/**
 * **Neon 2048**: recuadros redondeados con potencias de dos (2, 4, 8, 16) del juego,
 * esparcidos a distintos tamaños —el mismo lenguaje de ficha del tablero— en lugar
 * del fondo genérico de símbolos matemáticos de la categoría Cálculo Mental.
 */
@Composable
private fun NumberTilesTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val stroke = 1.5f.dp.toPx()
        val dx = if (centered) -0.22f * size.width else 0f
        NUMBER_TILES.forEach { t ->
            val s = t.sizeF * minDim
            val cx = t.xf * size.width + dx
            val cy = t.yf * size.height
            val a = t.alpha * intensity
            val tl = Offset(cx - s / 2f, cy - s / 2f)
            val tileSize = Size(s, s)
            val radius = CornerRadius(s * 0.22f)
            drawRoundRect(color = accent.copy(alpha = a * 0.28f), topLeft = tl, size = tileSize, cornerRadius = radius)
            drawRoundRect(
                color = accent.copy(alpha = a), topLeft = tl, size = tileSize,
                cornerRadius = radius, style = Stroke(width = stroke),
            )
            val glyphSize = (s * 0.42f / density).coerceAtLeast(10f)
            drawGlyph(measurer, t.value, cx, cy, glyphSize, accent.copy(alpha = a))
        }
    }
}

/** Números "pistas" del buscaminas: (col, fila) → cuántas minas adyacentes. */
private val MINE_CLUES = mapOf(
    (1 to 0) to "1", (3 to 1) to "2", (0 to 2) to "3", (4 to 2) to "1", (2 to 3) to "2",
)

/** Celdas cubiertas (sin abrir) del buscaminas: se dibujan con relleno tenue. */
private val MINE_COVERED = setOf(0 to 0, 4 to 0, 3 to 0, 1 to 1, 0 to 3, 4 to 3)

/** Bandera en (2,0); mina destapada en (2,2). */
private val MINE_FLAG = 2 to 0
private val MINE_BOMB = 2 to 2

/**
 * **Neon Defuser**: rejilla de buscaminas anclada a la derecha —celdas cubiertas
 * con relleno, celdas abiertas con su número de minas adyacentes, una bandera y una
 * mina destapada— para comunicar de un vistazo la mecánica del juego.
 */
@Composable
private fun MinesweeperTexture(accent: Color, modifier: Modifier, intensity: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val cols = 5
        val rows = 4
        val cell = size.height / (rows - 0.3f)
        val originX = size.width - cols * cell + cell * 0.55f // bleed sutil a la derecha
        val originY = (size.height - rows * cell) / 2f
        val inset = cell * 0.08f
        val radius = CornerRadius(cell * 0.16f)
        val stroke = 1.5f.dp.toPx()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tl = Offset(originX + c * cell + inset, originY + r * cell + inset)
                val cellSize = Size(cell - inset * 2, cell - inset * 2)
                val cx = originX + (c + 0.5f) * cell
                val cy = originY + (r + 0.5f) * cell
                // Celda cubierta: relleno tenue. Abierta: solo contorno.
                if ((c to r) in MINE_COVERED || (c to r) == MINE_FLAG) {
                    drawRoundRect(color = accent.copy(alpha = 0.24f * intensity), topLeft = tl, size = cellSize, cornerRadius = radius)
                }
                drawRoundRect(
                    color = accent.copy(alpha = 0.4f * intensity),
                    topLeft = tl, size = cellSize, cornerRadius = radius, style = Stroke(width = stroke),
                )
                when (c to r) {
                    MINE_FLAG -> drawFlag(cx, cy, cell, accent.copy(alpha = 0.9f * intensity), stroke)
                    MINE_BOMB -> drawMine(cx, cy, cell, accent.copy(alpha = 0.9f * intensity), stroke)
                    else -> MINE_CLUES[c to r]?.let { n ->
                        drawGlyph(measurer, n, cx, cy, (cell * 0.42f / density).coerceAtLeast(9f), accent.copy(alpha = 0.85f * intensity))
                    }
                }
            }
        }
    }
}

/** Banderita (asta + triángulo) centrada en ([cx],[cy]) dentro de una celda [cell]. */
private fun DrawScope.drawFlag(cx: Float, cy: Float, cell: Float, color: Color, stroke: Float) {
    val poleX = cx + cell * 0.12f
    val top = cy - cell * 0.26f
    val bottom = cy + cell * 0.28f
    drawLine(color, Offset(poleX, top), Offset(poleX, bottom), stroke, cap = StrokeCap.Round)
    val flag = Path().apply {
        moveTo(poleX, top)
        lineTo(poleX, top + cell * 0.24f)
        lineTo(cx - cell * 0.24f, top + cell * 0.12f)
        close()
    }
    drawPath(flag, color)
}

/** Mina (círculo + púas radiales) centrada en ([cx],[cy]) dentro de una celda [cell]. */
private fun DrawScope.drawMine(cx: Float, cy: Float, cell: Float, color: Color, stroke: Float) {
    val r = cell * 0.2f
    drawCircle(color, radius = r, center = Offset(cx, cy))
    val spike = cell * 0.32f
    for (i in 0 until 8) {
        val a = PI / 4 * i
        drawLine(
            color,
            Offset(cx + (r * 0.6f) * cos(a).toFloat(), cy + (r * 0.6f) * sin(a).toFloat()),
            Offset(cx + spike * cos(a).toFloat(), cy + spike * sin(a).toFloat()),
            stroke, cap = StrokeCap.Round,
        )
    }
}

/**
 * Una tubería del circuito: la lista de puntos de rejilla (col, fila) que recorre.
 * Sus dos extremos son los "nodos" (puntos que hay que conectar en el juego).
 */
private val CIRCUIT_ROUTES = listOf(
    listOf(0 to 1, 0 to 3),                 // conector izquierdo (equilibra la columna 0)
    listOf(1 to 0, 1 to 3),                 // conector vertical (recto)
    listOf(2 to 0, 3 to 0, 3 to 1),         // traza superior derecha (L)
    listOf(2 to 2, 2 to 3, 3 to 3),         // traza inferior derecha (L)
)

/**
 * **Conectores** (Neon Circuit Flow / flow free): nodos unidos por **tuberías
 * redondeadas** que recorren una rejilla, tal como se resuelve el juego uniendo
 * pares de puntos sin cruzarlos. Se dibuja en el lado derecho: rejilla tenue de
 * fondo, tubos con esquinas redondeadas y nodos (disco + anillo) en los extremos.
 */
@Composable
private fun CircuitFlowTexture(accent: Color, modifier: Modifier, intensity: Float) {
    Canvas(modifier = modifier) {
        val n = 4 // rejilla 4×4 de puntos
        val side = size.height * 1.08f
        val cell = side / n
        val originX = size.width - side + cell * 0.35f // bleed sutil a la derecha
        val originY = (size.height - side) / 2f
        fun node(c: Int, r: Int) = Offset(originX + (c + 0.5f) * cell, originY + (r + 0.5f) * cell)

        // Rejilla de fondo tenue.
        val gridAlpha = 0.14f * intensity
        for (i in 0 until n) {
            for (j in 0 until n) {
                drawCircle(accent.copy(alpha = gridAlpha), radius = cell * 0.06f, center = node(i, j))
            }
        }

        val pipe = cell * 0.28f
        CIRCUIT_ROUTES.forEach { route ->
            // Tubería: polilínea con esquinas y extremos redondeados (aire de "flow").
            val path = Path().apply {
                val (c0, r0) = route.first()
                moveTo(node(c0, r0).x, node(c0, r0).y)
                route.drop(1).forEach { (c, r) -> lineTo(node(c, r).x, node(c, r).y) }
            }
            drawPath(
                path = path,
                color = accent.copy(alpha = 0.5f * intensity),
                style = Stroke(width = pipe, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Nodos en los dos extremos: disco relleno + anillo.
            listOf(route.first(), route.last()).forEach { (c, r) ->
                val p = node(c, r)
                drawCircle(accent.copy(alpha = 0.85f * intensity), radius = cell * 0.24f, center = p)
                drawCircle(
                    accent.copy(alpha = 0.95f * intensity), radius = cell * 0.32f, center = p,
                    style = Stroke(width = 1.5f.dp.toPx()),
                )
            }
        }
    }
}

/** Un cometa: ángulo alrededor del portal, distancia (en radios), tamaño y opacidad. */
private data class Comet(val angleDeg: Float, val dist: Float, val sizeF: Float, val alpha: Float)

private val HYPERGATE_COMETS = listOf(
    Comet(-52f, 1.75f, 1.0f, 0.95f),
    Comet(150f, 1.55f, 0.8f, 0.7f),
    Comet(205f, 2.0f, 0.62f, 0.55f),
)

/**
 * **Hypergate**: un portal circular (anillo con halo) a la derecha y varios cometas
 * —cabeza brillante con estela— precipitándose hacia él, reflejando el juego de
 * reflejos de interceptar objetos que caen sobre la puerta.
 */
@Composable
private fun HypergateTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val center = Offset(size.width * (if (centered) 0.5f else 0.68f), size.height * 0.5f)
        val r = minDim * 0.24f

        // Halo del portal: resplandor radial que se desvanece.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.2f * intensity), Color.Transparent),
                center = center,
                radius = r * 2.1f,
            ),
            radius = r * 2.1f,
            center = center,
        )
        // Anillo exterior (la "puerta") + anillo interior tenue.
        drawCircle(accent.copy(alpha = 0.9f * intensity), radius = r, center = center, style = Stroke(width = r * 0.14f))
        drawCircle(accent.copy(alpha = 0.3f * intensity), radius = r * 0.6f, center = center, style = Stroke(width = r * 0.05f))

        // Cometas: estela apuntando hacia afuera (parecen caer hacia el portal).
        HYPERGATE_COMETS.forEach { comet ->
            val a = comet.angleDeg * (PI.toFloat() / 180f)
            val dir = Offset(cos(a.toDouble()).toFloat(), sin(a.toDouble()).toFloat())
            val head = Offset(center.x + dir.x * r * comet.dist, center.y + dir.y * r * comet.dist)
            val headR = r * 0.15f * comet.sizeF
            val tailEnd = Offset(head.x + dir.x * r * comet.sizeF * 1.5f, head.y + dir.y * r * comet.sizeF * 1.5f)
            // Estela.
            drawLine(
                color = accent.copy(alpha = comet.alpha * 0.5f * intensity),
                start = head,
                end = tailEnd,
                strokeWidth = headR * 1.4f,
                cap = StrokeCap.Round,
            )
            // Halo de la cabeza + núcleo.
            drawCircle(accent.copy(alpha = comet.alpha * 0.25f * intensity), radius = headR * 2f, center = head)
            drawCircle(accent.copy(alpha = comet.alpha * intensity), radius = headR, center = head)
        }
    }
}

/** Un pulso: centro (fracciones), radio (fracción de minDim) y opacidad. */
private data class Pulse(val xf: Float, val yf: Float, val rF: Float, val alpha: Float)

// Tres pulsos espaciados; cada uno un 5% más pequeño que el anterior (0.20 → 0.19 → 0.18).
private val NEON_PULSES = listOf(
    Pulse(0.66f, 0.26f, 0.18f, 0.9f),
    Pulse(0.88f, 0.58f, 0.16f, 0.72f),
    Pulse(0.48f, 0.74f, 0.13f, 0.58f),
)

/** Dibuja un pulso concéntrico (halo + anillos + núcleo) centrado en [center] de radio [r]. */
private fun DrawScope.drawPulse(center: Offset, r: Float, accent: Color, alpha: Float) {
    // Halo exterior que se desvanece.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = alpha * 0.22f), Color.Transparent),
            center = center,
            radius = r * 1.3f,
        ),
        radius = r * 1.3f,
        center = center,
    )
    // Anillos concéntricos.
    drawCircle(accent.copy(alpha = alpha * 0.9f), radius = r, center = center, style = Stroke(width = r * 0.15f))
    drawCircle(accent.copy(alpha = alpha * 0.7f), radius = r * 0.66f, center = center, style = Stroke(width = r * 0.13f))
    // Núcleo relleno.
    drawCircle(accent.copy(alpha = alpha * 0.55f), radius = r * 0.3f, center = center)
}

/**
 * **Pulso Neon**: tres pulsos concéntricos espaciados, escalonados en tamaño (cada
 * uno ~5% menor que el anterior), evocando los anillos que laten y se contraen en el
 * juego de reflejos.
 */
@Composable
private fun NeonPulseTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val dx = if (centered) -0.17f * size.width else 0f
        NEON_PULSES.forEach { pulse ->
            val center = Offset(pulse.xf * size.width + dx, pulse.yf * size.height)
            drawPulse(center, pulse.rF * minDim, accent, pulse.alpha * intensity)
        }
    }
}

/** Pads iluminados de la secuencia: (col, fila) → brillo relativo (0..1). */
private val SEQUENCE_LIT = mapOf((2 to 0) to 1f, (1 to 1) to 0.55f)

/**
 * **Memoria de Secuencias**: rejilla 3×3 de pads (estilo Simon) con uno encendido
 * con fuerza y otro tenue —insinuando la secuencia que hay que memorizar— y el resto
 * apagados (solo contorno).
 */
@Composable
private fun SequenceGridTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val n = 3
        val cell = size.height / 4f
        val gridSize = n * cell
        val originX = if (centered) (size.width - gridSize) / 2f else size.width - gridSize + cell * 0.4f
        val originY = (size.height - gridSize) / 2f
        val inset = cell * 0.1f
        val radius = CornerRadius(cell * 0.22f)
        val stroke = 1.5f.dp.toPx()
        for (r in 0 until n) {
            for (c in 0 until n) {
                val tl = Offset(originX + c * cell + inset, originY + r * cell + inset)
                val cellSize = Size(cell - inset * 2, cell - inset * 2)
                val lit = SEQUENCE_LIT[c to r]
                if (lit != null) {
                    // Halo del pad encendido.
                    drawRoundRect(color = accent.copy(alpha = lit * 0.3f * intensity), topLeft = tl, size = cellSize, cornerRadius = radius)
                }
                drawRoundRect(
                    color = accent.copy(alpha = ((lit ?: 0f) * 0.6f + 0.3f) * intensity),
                    topLeft = tl, size = cellSize, cornerRadius = radius, style = Stroke(width = stroke),
                )
            }
        }
    }
}

/** Una burbuja de cálculo: centro, radio, texto y si es la correcta (resaltada). */
private data class Bubble(val xf: Float, val yf: Float, val rF: Float, val text: String, val correct: Boolean)

private val MATH_BUBBLES = listOf(
    Bubble(0.6f, 0.24f, 0.17f, "6×4", true),
    Bubble(0.87f, 0.48f, 0.14f, "9+8", false),
    Bubble(0.52f, 0.64f, 0.12f, "7×3", false),
)

/**
 * **Burbujas de Cálculo**: burbujas con operaciones flotando y un **objetivo** abajo;
 * la burbuja "correcta" (la que resuelve el objetivo) va resaltada, como en el juego.
 */
@Composable
private fun MathBubblesTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val stroke = 1.5f.dp.toPx()
        val dx = if (centered) -0.16f * size.width else 0f
        MATH_BUBBLES.forEach { b ->
            val center = Offset(b.xf * size.width + dx, b.yf * size.height)
            val r = b.rF * minDim
            val a = (if (b.correct) 0.9f else 0.55f) * intensity
            drawCircle(accent.copy(alpha = a * 0.18f), radius = r, center = center)
            drawCircle(accent.copy(alpha = a), radius = r, center = center, style = Stroke(width = stroke))
            drawGlyph(measurer, b.text, center.x, center.y, (r * 0.62f / density).coerceAtLeast(9f), accent.copy(alpha = a))
        }
    }
}

/** Nodo-objetivo (halo + anillo + núcleo) de un extremo de la tubería. */
private fun DrawScope.drawEnergyNode(center: Offset, cell: Float, accent: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = alpha * 0.3f), Color.Transparent),
            center = center, radius = cell * 0.55f,
        ),
        radius = cell * 0.55f, center = center,
    )
    drawCircle(accent.copy(alpha = alpha), radius = cell * 0.3f, center = center, style = Stroke(width = cell * 0.1f))
    drawCircle(accent.copy(alpha = alpha), radius = cell * 0.13f, center = center)
}

/** Recorrido de la tubería (col, fila) entre los dos nodos (primer y último punto). */
private val ENERGY_ROUTE = listOf(1 to 2, 1 to 1, 3 to 1, 3 to 0)

/**
 * **Flujo de Energía**: tablero 4×4 de celdas (cada una con su punto de pieza) y una
 * **tubería redondeada** que conecta dos nodos-objetivo iluminados, tal como se ve el
 * juego al cerrar el circuito rotando las piezas.
 */
@Composable
private fun EnergyPipesTexture(accent: Color, modifier: Modifier, intensity: Float) {
    Canvas(modifier = modifier) {
        val n = 4
        val cell = size.height / 4.4f
        val gridSize = n * cell
        val originX = size.width - gridSize + cell * 0.3f
        val originY = (size.height - gridSize) / 2f
        val inset = cell * 0.08f
        val radius = CornerRadius(cell * 0.18f)
        val stroke = 1.5f.dp.toPx()
        fun center(c: Int, r: Int) = Offset(originX + (c + 0.5f) * cell, originY + (r + 0.5f) * cell)

        // Celdas del tablero con un punto tenue en el centro (las piezas apagadas).
        for (r in 0 until n) for (c in 0 until n) {
            drawRoundRect(
                color = accent.copy(alpha = 0.16f * intensity),
                topLeft = Offset(originX + c * cell + inset, originY + r * cell + inset),
                size = Size(cell - inset * 2, cell - inset * 2),
                cornerRadius = radius, style = Stroke(width = stroke),
            )
            drawCircle(accent.copy(alpha = 0.12f * intensity), radius = cell * 0.06f, center = center(c, r))
        }
        // Tubería energizada (esquinas y extremos redondeados).
        val path = Path().apply {
            val (c0, r0) = ENERGY_ROUTE.first()
            moveTo(center(c0, r0).x, center(c0, r0).y)
            ENERGY_ROUTE.drop(1).forEach { (c, r) -> lineTo(center(c, r).x, center(c, r).y) }
        }
        drawPath(path, accent.copy(alpha = 0.55f * intensity), style = Stroke(width = cell * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Nodos-objetivo en los dos extremos.
        listOf(ENERGY_ROUTE.first(), ENERGY_ROUTE.last()).forEach { (c, r) ->
            drawEnergyNode(center(c, r), cell, accent, 0.9f * intensity)
        }
    }
}

/** Partículas entrantes de Atracción Geométrica: ángulo (grados) y distancia en radios. */
private val POLARITY_PARTICLES = listOf(-48f to 1.7f, 160f to 1.55f, 235f to 1.9f)

/**
 * **Atracción Geométrica**: el círculo de **4 sectores** que el jugador rota, con dos
 * sectores opuestos sombreados y **partículas** que llegan desde fuera con estela —la
 * mecánica de capturar cada partícula con el sector de su color.
 */
@Composable
private fun PolaritySectorsTexture(accent: Color, modifier: Modifier, intensity: Float, centered: Boolean = false) {
    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        val center = Offset(size.width * (if (centered) 0.5f else 0.66f), size.height * 0.5f)
        val r = minDim * 0.22f
        val rot = 20f
        val arcTopLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        // Dos sectores opuestos sombreados (sugieren los colores).
        drawArc(accent.copy(alpha = 0.22f * intensity), rot, 90f, useCenter = true, topLeft = arcTopLeft, size = arcSize)
        drawArc(accent.copy(alpha = 0.22f * intensity), rot + 180f, 90f, useCenter = true, topLeft = arcTopLeft, size = arcSize)
        // Anillo y separadores de sector (dos diámetros).
        drawCircle(accent.copy(alpha = 0.85f * intensity), radius = r, center = center, style = Stroke(width = r * 0.1f))
        listOf(rot, rot + 90f).forEach { deg ->
            val a = deg * (PI.toFloat() / 180f)
            val d = Offset(cos(a.toDouble()).toFloat(), sin(a.toDouble()).toFloat())
            drawLine(
                accent.copy(alpha = 0.5f * intensity),
                Offset(center.x - d.x * r, center.y - d.y * r),
                Offset(center.x + d.x * r, center.y + d.y * r),
                1.5f.dp.toPx(),
            )
        }
        // Partículas entrantes con estela.
        POLARITY_PARTICLES.forEach { (deg, dist) ->
            val a = deg * (PI.toFloat() / 180f)
            val d = Offset(cos(a.toDouble()).toFloat(), sin(a.toDouble()).toFloat())
            val p = Offset(center.x + d.x * r * dist, center.y + d.y * r * dist)
            val tail = Offset(p.x + d.x * r * 0.5f, p.y + d.y * r * 0.5f)
            drawLine(accent.copy(alpha = 0.4f * intensity), p, tail, r * 0.14f, cap = StrokeCap.Round)
            drawCircle(accent.copy(alpha = 0.9f * intensity), radius = r * 0.12f, center = p)
        }
    }
}
