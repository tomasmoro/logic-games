package com.example.kortexgames.game.defuser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.drawNeonTile
import com.example.kortexgames.ui.components.softGlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * # Neon Defuser — Panel de celdas (Compose, FASE 3)
 *
 * Rejilla rectangular de desactivación. Igual que el tablero de Neon Sudoku, busca
 * el mínimo coste por frame: **un único `Canvas`** pinta todas las celdas (fondos,
 * contornos, números y minas), y **un único `pointerInput`** traduce el toque a
 * coordenada por aritmética, en vez de un `clickable` por celda. Las banderas se
 * superponen como iconos vectoriales para poder darles el halo neón con [softGlow]
 * (§9.5: los iconos de UI nunca son geometría cuando existe el vectorial adecuado).
 *
 * ## Geometría
 * Todas las celdas son cuadrados del mismo lado `cell`, calculado para que el panel
 * de `columns × rows` quepa entero en el espacio disponible:
 * `cell = min(anchoDisponible / columns, altoDisponible / rows)`, topado por
 * [BoardMaxCellSize] para que en tablet no queden celdas gigantes. El panel
 * resultante se centra. La celda `(row, col)` ocupa el rectángulo cuya esquina
 * superior izquierda es `origin + (col·cell, row·cell)`.
 *
 * ## Detección de toque
 * La inversa es directa: `col = (x − originX) / cell`, `row = (y − originY) / cell`,
 * validando que caiga dentro del panel. Es `O(1)` y exacta, sin recorrer celdas.
 *
 * @param state estado de juego a renderizar.
 * @param detonateProgress avance `0..1` del halo expansivo de la mina detonada
 *   ([DefuserEffect.ExplodeAt]); `0` = sin explosión en curso.
 * @param revealAlphaFor opacidad `0..1` de cada celda que se está revelando, para
 *   la **cascada**: la pantalla la calcula en función de la distancia de la celda
 *   al origen del toque, de modo que la revelación llega como una onda de luz en
 *   vez de aparecer de golpe (ver `DefuserScreen`). Vale `1` para celdas ya
 *   asentadas.
 * @param onReveal callback del **tap corto** con la coordenada tocada.
 * @param onFlag callback del **long press** con la coordenada tocada.
 * @param modifier modificador del contenedor (primer parámetro opcional, §4).
 */
@Composable
fun DefuserBoard(
    state: DefuserUiState,
    detonateProgress: Float,
    revealAlphaFor: (MineCell) -> Float,
    onReveal: (CellPosition) -> Unit,
    onFlag: (CellPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        val maxCellPx = with(density) { BoardMaxCellSize.toPx() }
        val board = state.board

        val cell = min(min(availW / board.columns, availH / board.rows), maxCellPx)
        val originX = (availW - cell * board.columns) / 2f
        val originY = (availH - cell * board.rows) / 2f

        // Tamaño del icono de escudo, derivado del lado de celda para que crezca y
        // encoja con el panel.
        val flagSizeDp = with(density) { (cell * FLAG_ICON_FRACTION).toDp() }

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Clave: reinicia el detector si cambia la geometría (dificultad
                    // o lado de celda), para que el hit-test use los valores vigentes.
                    .pointerInput(state.difficulty, cell) {
                        detectTapGestures(
                            onTap = { pos ->
                                positionAt(pos, originX, originY, cell, board)?.let(onReveal)
                            },
                            onLongPress = { pos ->
                                positionAt(pos, originX, originY, cell, board)?.let(onFlag)
                            },
                        )
                    },
            ) {
                board.cells.forEach { mineCell ->
                    drawCell(
                        cell = mineCell,
                        topLeft = Offset(
                            x = originX + mineCell.position.col * cell,
                            y = originY + mineCell.position.row * cell,
                        ),
                        side = cell,
                        revealAlpha = revealAlphaFor(mineCell),
                        detonateProgress = detonateProgress,
                        measurer = measurer,
                    )
                }
            }

            // Capa de banderas: iconos vectoriales de escudo con halo neón. Son pocas,
            // así que un composable por bandera no penaliza.
            //
            // `key(position)` da identidad estable a cada escudo: sin él, Compose
            // reutilizaría el composable de una bandera quitada para otra puesta en
            // otra celda y el rebote de entrada no se dispararía.
            board.cells.filter { it.isFlagged }.forEach { flagged ->
                key(flagged.position) {
                    val centerX = originX + (flagged.position.col + 0.5f) * cell
                    val centerY = originY + (flagged.position.row + 0.5f) * cell
                    val halfPx = with(density) { (flagSizeDp / 2).toPx() }
                    FlagMarker(
                        sizeDp = flagSizeDp,
                        modifier = Modifier.offset {
                            IntOffset((centerX - halfPx).roundToInt(), (centerY - halfPx).roundToInt())
                        },
                    )
                }
            }
        }
    }
}

/**
 * Escudo (bandera) sobre una celda marcada: icono vectorial con halo neón violeta.
 *
 * Entra con un **rebote de resorte** (§9.4: la física de `spring` es la
 * interacción táctil por defecto de la app): nace a escala 0 y sobrepasa
 * ligeramente antes de asentarse, de modo que colocar un escudo se siente como
 * clavarlo. El `Animatable` arranca en 0 y solo se anima al entrar en composición,
 * así que solo rebotan los escudos **recién puestos**, no los que ya estaban (que
 * se recomponen sin reiniciar su animación).
 */
@Composable
private fun FlagMarker(sizeDp: Dp, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .softGlow(color = LogicColors.Violet, durationMillis = FLAG_GLOW_MS),
    ) {
        NeonIcon(
            icon = KortexIcons.Shield,
            tint = LogicColors.Violet,
            size = sizeDp,
            glow = true,
            contentDescription = "Celda marcada con escudo",
        )
    }
}

/**
 * Celda bajo el punto [pos], o `null` si el toque cayó en el margen exterior del
 * panel. Invierte la geometría de la rejilla (ver KDoc del archivo).
 */
private fun positionAt(
    pos: Offset,
    originX: Float,
    originY: Float,
    cell: Float,
    board: MineBoard,
): CellPosition? {
    val col = ((pos.x - originX) / cell).toInt()
    val row = ((pos.y - originY) / cell).toInt()
    // toInt() trunca hacia cero, así que un toque a la izquierda/arriba del origen
    // daría 0 en vez de negativo: se comprueban explícitamente los dos márgenes.
    if (pos.x < originX || pos.y < originY) return null
    val position = CellPosition(row, col)
    return if (board.contains(position)) position else null
}

// ---------------------------------------------------------------------------
// Dibujo de una celda
// ---------------------------------------------------------------------------

/**
 * Pinta una celda completa según su estado. Orden de capas por profundidad: fondo
 * → contorno → contenido (número o mina). La **cascada** se resuelve con
 * [revealAlpha]: una celda segura recién revelada arranca en `0` (aún se ve el tile
 * oculto "cerrado") y sube a `1` a medida que la onda de luz la alcanza.
 *
 * @param topLeft esquina superior izquierda del cuadrado de la celda.
 * @param side lado del cuadrado en píxeles.
 */
private fun DrawScope.drawCell(
    cell: MineCell,
    topLeft: Offset,
    side: Float,
    revealAlpha: Float,
    detonateProgress: Float,
    measurer: TextMeasurer,
) {
    when (cell.state) {
        MineCellState.HIDDEN -> drawHiddenTile(topLeft, side)

        // Flagged: el tile "blindado" con el tubo neón violeta. Se usa el componente
        // compartido drawNeonTile —con rectTopLeft/rectSize, pensados justo para
        // pintar muchas celdas en un Canvas común— para heredar la estética exacta
        // del resto de la app (§9.7). El icono de escudo lo superpone la capa de
        // composables.
        MineCellState.FLAGGED -> {
            drawTileFill(topLeft, side, LogicColors.SurfaceVariantDark)
            drawNeonTile(
                baseColor = LogicColors.Violet,
                activeAmt = FLAG_TILE_AMT,
                cornerRadius = CellCorner,
                sparks = false,
                baseMargin = TileMargin,
                strokeScale = NEON_STROKE_SCALE,
                rectTopLeft = topLeft,
                rectSize = Size(side, side),
            )
        }

        MineCellState.REVEALED ->
            if (cell.hasMine) {
                drawMineCell(cell, topLeft, side, detonateProgress)
            } else {
                drawRevealedSafe(cell, topLeft, side, revealAlpha, measurer)
            }
    }
}

/** Celda oculta: relleno [LogicColors.SurfaceVariantDark] y contorno sutil del
 *  acento de la categoría. Sin tubo neón pleno por celda: §9.7 advierte que un
 *  borde intenso repetido sobre un panel denso compite con el contenido. */
private fun DrawScope.drawHiddenTile(topLeft: Offset, side: Float) {
    drawTileFill(topLeft, side, LogicColors.SurfaceVariantDark)
    drawTileStroke(topLeft, side, CategoryPalette.Attention.copy(alpha = HIDDEN_BORDER_ALPHA))
}

/**
 * Celda segura revelada: un "hueco" oscuro en el panel con su número de peligro.
 * Durante la cascada ([revealAlpha] < 1) se dibuja el tile oculto por debajo y el
 * hueco + número van fundiéndose por encima, con un breve destello del acento que
 * decae con la onda — así la revelación se lee como luz recorriendo el panel.
 */
private fun DrawScope.drawRevealedSafe(
    cell: MineCell,
    topLeft: Offset,
    side: Float,
    revealAlpha: Float,
    measurer: TextMeasurer,
) {
    val a = revealAlpha.coerceIn(0f, 1f)
    // Mientras se abre, el tile oculto sigue debajo (la onda aún no llegó).
    if (a < 0.999f) drawHiddenTile(topLeft, side)

    // "Pop" de apertura: el hueco entra ligeramente encogido y crece hasta su
    // tamaño final. Combinado con el alfa, la celda se siente destapada de un
    // golpe seco en vez de simplemente aparecer (§9.4: feedback con peso).
    val pop = REVEAL_POP_MIN + (1f - REVEAL_POP_MIN) * a

    // Hueco: BackgroundDark casi opaco (cristal apagado). Su opacidad sube con la
    // onda para tapar el tile oculto al completarse.
    drawTileFill(topLeft, side, LogicColors.BackgroundDark.copy(alpha = a), scale = pop)
    // Destello de la onda: acento que aparece al frente de la cascada y se apaga.
    if (a in 0.001f..0.999f) {
        drawTileFill(
            topLeft,
            side,
            CategoryPalette.Attention.copy(alpha = REVEAL_FLASH_ALPHA * (1f - a)),
            scale = pop,
        )
    }
    // Contorno interior tenue para que el hueco no sea un vacío plano.
    drawTileStroke(topLeft, side, CategoryPalette.Attention.copy(alpha = REVEALED_BORDER_ALPHA * a))

    // Número de peligro 1..8 con la progresión de color de [dangerColor].
    if (cell.adjacentMines > 0) {
        val layout = measurer.measure(
            text = cell.adjacentMines.toString(),
            style = TextStyle(
                fontSize = (side * DIGIT_FRACTION).toSp(),
                fontWeight = FontWeight.Black,
                color = dangerColor(cell.adjacentMines).copy(alpha = a),
            ),
        )
        val center = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)
        // El dígito entra sobredimensionado y se asienta: un "golpe" de número que
        // acompaña al pop del hueco. Se escala con la transformación del DrawScope
        // porque `drawText` no admite escala propia.
        scale(scaleX = pop, scaleY = pop, pivot = center) {
            drawText(
                layout,
                topLeft = Offset(
                    x = topLeft.x + (side - layout.size.width) / 2f,
                    y = topLeft.y + (side - layout.size.height) / 2f,
                ),
            )
        }
    }
}

/**
 * Celda con mina revelada (solo al perder). Relleno rojo tenue + glifo de mina
 * dibujado como **geometría** (§9.5: la mina no es un icono de UI, es un objeto del
 * juego). La mina que el jugador pisó ([MineCell.isDetonated]) añade el tubo neón
 * rojo y un **halo expansivo** dirigido por [detonateProgress].
 */
private fun DrawScope.drawMineCell(
    cell: MineCell,
    topLeft: Offset,
    side: Float,
    detonateProgress: Float,
) {
    val detonated = cell.isDetonated
    drawTileFill(topLeft, side, LogicColors.Error.copy(alpha = if (detonated) MINE_FILL_HOT else MINE_FILL_COLD))
    val center = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)

    if (detonated) {
        drawNeonTile(
            baseColor = LogicColors.Error,
            activeAmt = 1f,
            cornerRadius = CellCorner,
            sparks = false,
            baseMargin = TileMargin,
            strokeScale = NEON_STROKE_SCALE,
            rectTopLeft = topLeft,
            rectSize = Size(side, side),
        )
        if (detonateProgress > 0f) drawExplosion(center, side, detonateProgress)
    }
    // La mina detonada desaparece bajo su propia explosión al avanzar: dibujarla
    // encima del fogonazo la haría parecer intacta en mitad del estallido.
    if (!detonated || detonateProgress < GLYPH_HIDE_AT) {
        drawMineGlyph(center, side, detonated)
    }
}

/**
 * **Explosión neón con onda expansiva** de la mina pisada. Cuatro capas apiladas,
 * cada una con su propio ritmo, que es lo que la hace leerse como una detonación y
 * no como un simple círculo que crece:
 *
 *  1. **Fogonazo** (`flash`): un núcleo blanco que nace enorme y se apaga en el
 *     primer ~25 % del avance. Es el golpe de luz inicial.
 *  2. **Resplandor de calor**: un degradado radial rojo→transparente que se
 *     expande detrás de todo, dando volumen al estallido.
 *  3. **Ondas expansivas**: [SHOCKWAVE_RINGS] anillos escalonados en el tiempo
 *     ([SHOCKWAVE_DELAY]). Cada uno se expande con `easeOutCubic` (rápido al
 *     salir, frenando) y **adelgaza** conforme crece, igual que una onda de
 *     choque real que se disipa.
 *  4. **Metralla**: chispas que salen radialmente, frenan y caen un poco por
 *     gravedad mientras se apagan (ver [ExplosionDebris]).
 *
 * @param progress avance `0..1` de la detonación.
 */
private fun DrawScope.drawExplosion(center: Offset, side: Float, progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    // easeOutCubic: la onda sale disparada y frena, en vez de avanzar lineal.
    val ease = 1f - (1f - p) * (1f - p) * (1f - p)
    val fade = 1f - p

    // 2) Resplandor de calor detrás de todo.
    val glowRadius = side * (0.6f + ease * HEAT_GLOW_GROWTH)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                LogicColors.Error.copy(alpha = 0.55f * fade),
                LogicColors.Coral.copy(alpha = 0.28f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = center,
    )

    // 3) Ondas expansivas escalonadas: cada anillo arranca un poco más tarde.
    for (i in 0 until SHOCKWAVE_RINGS) {
        val ringP = ((p - i * SHOCKWAVE_DELAY) / (1f - i * SHOCKWAVE_DELAY)).coerceIn(0f, 1f)
        if (ringP <= 0f) continue
        val ringEase = 1f - (1f - ringP) * (1f - ringP) * (1f - ringP)
        val ringFade = 1f - ringP
        // El trazo adelgaza al expandirse: la onda se disipa, no engorda.
        val width = SHOCKWAVE_MAX_DP.dp.toPx() * ringFade
        if (width <= 0.25f) continue
        drawCircle(
            color = (if (i == 0) Color.White else LogicColors.Error).copy(alpha = ringFade * SHOCKWAVE_ALPHA),
            radius = side * (0.45f + ringEase * SHOCKWAVE_GROWTH),
            center = center,
            style = Stroke(width = width),
        )
    }

    // 4) Metralla: sale rápido, frena y cae mientras se apaga.
    val gravity = side * DEBRIS_GRAVITY * p * p
    for (debris in ExplosionDebris) {
        val distance = debris.speed * side * ease
        val tip = Offset(
            x = center.x + cos(debris.angleRad) * distance,
            y = center.y + sin(debris.angleRad) * distance + gravity,
        )
        val color = DebrisColors[debris.colorIndex]
        val r = side * debris.size
        drawCircle(color.copy(alpha = 0.25f * fade), radius = r * 2.4f, center = tip)
        drawCircle(color.copy(alpha = fade), radius = r, center = tip)
    }

    // 1) Fogonazo blanco inicial (se dibuja al final para quedar por encima).
    if (p < FLASH_PORTION) {
        val flash = 1f - p / FLASH_PORTION
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * flash),
            radius = side * (0.30f + (1f - flash) * 0.55f),
            center = center,
        )
    }
}

/**
 * Glifo de mina: un núcleo circular con ocho púas (una por cada dirección vecina,
 * guiño a las 8 adyacencias del juego) y un punto blanco de brillo. Se dibuja a
 * mano para poder teñirlo con el rojo de peligro y darle volumen, cosa que un emoji
 * no permitiría (§9.5).
 */
private fun DrawScope.drawMineGlyph(center: Offset, side: Float, detonated: Boolean) {
    val core = side * MINE_CORE_FRACTION
    val spike = side * MINE_SPIKE_FRACTION
    val color = if (detonated) Color.White else LogicColors.Error
    val spikeWidth = MINE_SPIKE_DP.dp.toPx()
    // Ocho púas cada 45°, alineadas con las direcciones de vecino de la celda.
    for (i in 0 until 8) {
        val angle = (i * 45f) * (PI.toFloat() / 180f)
        val dir = Offset(cos(angle), sin(angle))
        drawLine(
            color = color,
            start = center + dir * core,
            end = center + dir * (core + spike),
            strokeWidth = spikeWidth,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color = color, radius = core, center = center)
    // Brillo especular para dar volumen al núcleo.
    drawCircle(
        color = Color.White.copy(alpha = 0.85f),
        radius = core * 0.32f,
        center = center - Offset(core * 0.3f, core * 0.3f),
    )
}

/**
 * Rellena el cuadrado de la celda con [color] (esquinas redondeadas, §9.6).
 *
 * @param scale factor de tamaño alrededor del **centro** de la celda (1 = normal).
 *   Encogerlo desde el centro —y no desde la esquina— es lo que hace que el "pop"
 *   de apertura se vea como la celda abriéndose in situ en vez de desplazándose.
 */
private fun DrawScope.drawTileFill(topLeft: Offset, side: Float, color: Color, scale: Float = 1f) {
    if (color.alpha <= 0f) return
    val margin = TileMargin.toPx()
    val full = side - margin * 2f
    val scaled = full * scale
    val inset = (full - scaled) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(topLeft.x + margin + inset, topLeft.y + margin + inset),
        size = Size(scaled, scaled),
        cornerRadius = CornerRadius(CellCorner.toPx() * scale, CellCorner.toPx() * scale),
    )
}

/** Contorno del cuadrado de la celda, alineado con [drawTileFill]. */
private fun DrawScope.drawTileStroke(topLeft: Offset, side: Float, color: Color) {
    if (color.alpha <= 0f) return
    val margin = TileMargin.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(topLeft.x + margin, topLeft.y + margin),
        size = Size(side - margin * 2f, side - margin * 2f),
        cornerRadius = CornerRadius(CellCorner.toPx(), CellCorner.toPx()),
        style = Stroke(width = BORDER_DP.dp.toPx()),
    )
}

/**
 * Color del número de peligro: progresión fría → cálida → alarma, para que el
 * jugador lea el riesgo por color antes que por cifra.
 *
 * Con 8 vecinas el rango es `1..8` (más ancho que el `1..6` de una malla
 * hexagonal), así que se añade un escalón naranja en el 4 antes del rojo: pintar
 * de rojo todo lo que va del 4 al 8 aplanaría justo la mitad alta de la escala,
 * que es donde el jugador más necesita distinguir.
 */
private fun dangerColor(count: Int): Color = when (count) {
    1 -> LogicColors.NeonCyan
    2 -> LogicColors.NeonGreen
    3 -> LogicColors.Amber
    4 -> LogicColors.Coral
    else -> LogicColors.Error
}

// --- Constantes de render (no de balance; el balance vive en DefuserConfig) ---

/** Lado máximo de celda: evita celdas gigantes en tablet. */
private val BoardMaxCellSize = 46.dp

/** Radio de esquina de una celda (escala `small` de §9.6, a escala de celda). */
private val CellCorner = 7.dp

/** Margen entre el borde de la celda y su dibujo: es lo que crea la rejilla de
 *  separación entre celdas sin dibujar líneas de rejilla explícitas. */
private val TileMargin = 1.5.dp

/** Grosor del contorno de una celda. */
private const val BORDER_DP = 1.5f

/** Alfa del borde de una celda oculta. */
private const val HIDDEN_BORDER_ALPHA = 0.28f

/** Alfa del borde interior de un hueco revelado (más tenue que el oculto). */
private const val REVEALED_BORDER_ALPHA = 0.12f

/** Alfa del destello del acento al frente de la cascada. */
private const val REVEAL_FLASH_ALPHA = 0.45f

/** Escala inicial del "pop" de apertura de una celda (1 = sin encoger). */
private const val REVEAL_POP_MIN = 0.55f

/** Encendido del tubo neón de una celda con escudo. */
private const val FLAG_TILE_AMT = 0.85f

/** Factor de grosor del tubo neón en las celdas (rejilla densa → más fino). */
private const val NEON_STROKE_SCALE = 0.45f

/** Tamaño del icono de escudo como fracción del lado de celda. */
private const val FLAG_ICON_FRACTION = 0.72f

/** Periodo del latido del halo del escudo (ms). Lento (§9.4: ambiente 1.2–2 s). */
private const val FLAG_GLOW_MS = 1500

/** Relleno rojo de una mina normal revelada (al perder). */
private const val MINE_FILL_COLD = 0.16f

/** Relleno rojo de la mina que el jugador pisó (más intenso). */
private const val MINE_FILL_HOT = 0.30f

/** Tamaño del dígito de peligro como fracción del lado de celda. */
private const val DIGIT_FRACTION = 0.58f

/** Radio del núcleo de la mina como fracción del lado de celda. */
private const val MINE_CORE_FRACTION = 0.17f

/** Longitud de las púas de la mina como fracción del lado de celda. */
private const val MINE_SPIKE_FRACTION = 0.12f

/** Grosor de las púas de la mina. */
private const val MINE_SPIKE_DP = 2f

// --- Explosión de la mina detonada ------------------------------------------

/** Número de anillos de la onda expansiva. */
private const val SHOCKWAVE_RINGS = 3

/** Retardo de arranque de cada anillo sucesivo, como fracción del avance total.
 *  Es lo que convierte un círculo que crece en una onda de choque escalonada. */
private const val SHOCKWAVE_DELAY = 0.16f

/** Cuánto crece cada anillo respecto al lado de celda. */
private const val SHOCKWAVE_GROWTH = 4.2f

/** Grosor máximo del anillo (al nacer); adelgaza hasta 0 al disiparse. */
private const val SHOCKWAVE_MAX_DP = 5f

/** Alfa base de los anillos de la onda expansiva. */
private const val SHOCKWAVE_ALPHA = 0.85f

/** Cuánto crece el resplandor de calor respecto al lado de celda. */
private const val HEAT_GLOW_GROWTH = 3.4f

/** Porción inicial del avance que dura el fogonazo blanco. */
private const val FLASH_PORTION = 0.25f

/** Avance a partir del cual el glifo de la mina deja de dibujarse (ya lo tapa
 *  su propia explosión). */
private const val GLYPH_HIDE_AT = 0.35f

/** Caída de la metralla (fracción del lado de celda) al final de su vuelo. */
private const val DEBRIS_GRAVITY = 1.1f

/** Paleta de la metralla: rojo → coral → ámbar, de más caliente a más brasa. */
private val DebrisColors: List<Color> =
    listOf(LogicColors.Error, LogicColors.Coral, LogicColors.Amber)

/** Una esquirla de la explosión: sale del centro en [angleRad] a [speed] (fracción
 *  del lado de celda). */
private data class Debris(
    val angleRad: Float,
    val speed: Float,
    val colorIndex: Int,
    val size: Float,
)

/**
 * Metralla de la explosión, precalculada **una sola vez** al cargar la clase.
 *
 * Se genera con [Random] sembrado (patrón fijo ⇒ explosión reproducible) y se
 * guarda en una constante en vez de sortearla dentro del `DrawScope`: el dibujo
 * corre en cada frame de la animación, y sortear ahí crearía basura por frame
 * además de hacer temblar las esquirlas al cambiar de posición en cada repintado.
 */
private val ExplosionDebris: List<Debris> = Random(0xDEF).let { random ->
    val count = 22
    List(count) { i ->
        // Reparto uniforme del círculo + jitter → estallido irregular natural.
        val angle = (i / count.toFloat()) * (2f * PI.toFloat()) +
            (random.nextFloat() - 0.5f) * 0.4f
        Debris(
            angleRad = angle,
            speed = 0.9f + random.nextFloat() * 2.6f,
            colorIndex = random.nextInt(3),
            size = 0.05f + random.nextFloat() * 0.06f,
        )
    }
}
