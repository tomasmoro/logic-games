package com.example.kortexgames.game.neonsudoku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.ui.components.drawNeonSparks
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Neon Sudoku Matrix — Tablero (Compose, FASE 3)
 *
 * "Panel holográfico" 9x9. El reparto de responsabilidades busca el mínimo coste
 * por frame:
 *
 *  - **Un solo `drawBehind`** pinta TODO lo que no es texto (resaltados de
 *    fila/columna/bloque, celdas con el mismo número, choques, líneas menores y
 *    mayores). Son 81 celdas: un `Canvas`/composable de fondo por celda
 *    multiplicaría por 81 las capas de dibujo sin ganar nada, porque el fondo no
 *    tiene estado propio — se deriva entero del `state`.
 *  - **81 composables de texto** encima, uno por celda, para los dígitos y las
 *    mini-notas. Aquí sí interesa la granularidad de Compose: solo se recompone
 *    la celda cuyo valor cambia.
 *  - **Un solo `pointerInput`** resuelve el toque por geometría (`offset / cellPx`)
 *    en vez de 81 `clickable`.
 *
 * Estética (CLAUDE.md §9.2 y §9.7): fondo [LogicColors.BackgroundDark], líneas
 * menores finas en [LogicColors.SurfaceVariantDark] y líneas mayores 3x3 en el
 * azul de la categoría Lógica ([CategoryPalette.Logic]) con la MISMA receta de
 * tubo de neón que `drawNeonTile` (halo ancho → halo intermedio → trazo nítido →
 * núcleo blanco), replicada aquí sobre líneas porque no son contornos de tile.
 * El marco exterior es deliberadamente sutil: §9.7 advierte que un borde neón
 * intenso alrededor de un tablero denso compite con su contenido.
 */

/**
 * Onda de celebración de una unidad completada (fila, columna o bloque 3x3).
 *
 * Réplica del lenguaje de la limpieza de línea de Tetris Neón: un **reloj único**
 * ([progress], 0→1) del que cada celda deriva su propio avance restándole una
 * **demora proporcional a su distancia al epicentro** ([origin], la celda recién
 * rellenada). Así el destello se propaga como una onda desde donde el jugador
 * jugó, en vez de encenderse todo a la vez, y sin necesidad de un temporizador
 * por celda: todo se recalcula por frame a partir del reloj + la distancia.
 *
 * Las demoras se precalculan una sola vez aquí (en el constructor) y no en cada
 * frame: el conjunto de celdas y el epicentro no cambian durante la onda.
 *
 * @property id identidad estable de esta onda; permite que varias coexistan (dos
 *   unidades completadas casi a la vez) sin mezclarse.
 * @property cells celdas que participan en la onda.
 * @property progress reloj de la animación, gobernado por la pantalla.
 */
class CompletionWave(
    val id: Int,
    val cells: List<CellPosition>,
    origin: CellPosition,
    val progress: Animatable<Float, *>,
) {
    /** Demora de arranque de cada celda, en fracción del reloj `0..WAVE_STAGGER_SPAN`. */
    private val delays: Map<CellPosition, Float> = run {
        val distances = cells.associateWith { pos ->
            val dCol = (pos.col - origin.col).toFloat()
            val dRow = (pos.row - origin.row).toFloat()
            sqrt(dCol * dCol + dRow * dRow)
        }
        // Se normaliza al alcance máximo del conjunto: la celda más lejana
        // arranca justo en WAVE_STAGGER_SPAN y aun así termina al cerrar el reloj.
        val maxDist = distances.values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        distances.mapValues { (_, d) -> d / maxDist * WAVE_STAGGER_SPAN }
    }

    /** Avance local `0..1` de [position] para el valor actual del reloj. */
    fun localProgress(position: CellPosition): Float {
        val delay = delays[position] ?: 0f
        return ((progress.value - delay) / (1f - WAVE_STAGGER_SPAN)).coerceIn(0f, 1f)
    }
}

/**
 * Rejilla 9x9 interactiva.
 *
 * @param state estado de juego a renderizar (tablero, selección, notas).
 * @param shakeCell celda que debe sacudirse por un choque recién cometido, o
 *   `null`. Viene de [NeonSudokuEffect.ShakeCell]; la anima la pantalla y aquí
 *   solo se aplica el desplazamiento.
 * @param shakeProgress avance `0..1` de la sacudida en curso.
 * @param sweepProgress avance `0..1` de la onda de luz de victoria
 *   ([NeonSudokuEffect.SweepVictory]); `0` = sin barrido.
 * @param completionWaves celebraciones de unidad completada activas (ver
 *   [CompletionWave]); normalmente vacía.
 * @param onSelectCell callback con la coordenada tocada.
 * @param modifier modificador del contenedor (primer parámetro opcional, §4).
 */
@Composable
fun NeonSudokuBoard(
    state: NeonSudokuUiState,
    shakeCell: CellPosition?,
    shakeProgress: Float,
    sweepProgress: Float,
    completionWaves: List<CompletionWave>,
    onSelectCell: (row: Int, col: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // El halo de las celdas en choque parpadea (§3 del brief). Es el ÚNICO bucle
    // continuo del tablero: §9.4 prohíbe varios elementos latiendo a la vez.
    val conflictBlink by rememberInfiniteTransition(label = "conflictBlink").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CONFLICT_BLINK_MS), RepeatMode.Reverse),
        label = "conflictBlinkAlpha",
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Tablero SIEMPRE cuadrado: se toma la menor dimensión disponible para que
        // las celdas no se deformen con la relación de aspecto del dispositivo.
        val side = minOf(maxWidth, maxHeight, BoardMaxSize)
        val cellSize = side / NeonSudokuConfig.BOARD_SIZE

        Box(
            modifier = Modifier
                .size(side)
                .drawBehind {
                    drawBoardBackground(state, conflictBlink)
                }
                // Las celebraciones van en drawWithContent (no drawBehind) para
                // pasar POR ENCIMA de los números: son luz sobre el panel, no
                // fondo. La onda de victoria se pinta la última porque es el
                // remate que cierra la partida.
                .drawWithContent {
                    drawContent()
                    completionWaves.forEach { drawCompletionWave(it) }
                    if (sweepProgress > 0f) drawVictorySweep(sweepProgress)
                }
                .pointerInput(cellSize) {
                    detectTapGestures { pos ->
                        val cellPx = size.width.toFloat() / NeonSudokuConfig.BOARD_SIZE
                        val col = (pos.x / cellPx).toInt().coerceIn(0, NeonSudokuConfig.BOARD_SIZE - 1)
                        val row = (pos.y / cellPx).toInt().coerceIn(0, NeonSudokuConfig.BOARD_SIZE - 1)
                        onSelectCell(row, col)
                    }
                },
        ) {
            Column {
                repeat(NeonSudokuConfig.BOARD_SIZE) { row ->
                    Row {
                        repeat(NeonSudokuConfig.BOARD_SIZE) { col ->
                            val cell = state.board.cellAt(row, col)
                            val shaking = shakeCell == cell.position
                            SudokuCellContent(
                                cell = cell,
                                cellSize = cellSize,
                                shakeOffset = if (shaking) shakeOffsetPx(shakeProgress, cellSize) else 0f,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Contenido de una celda: el dígito, o las mini-notas si está vacía.
 *
 * Reglas tipográficas del brief (§3), resueltas por color y peso —nunca por
 * colores sueltos hardcodeados (§9.2)—:
 *  - pista fija → [LogicColors.OnDark] en `displayLarge` (peso Black),
 *  - dígito del jugador → azul de la categoría Lógica,
 *  - dígito en choque → [LogicColors.Error] (su halo parpadeante lo pinta el fondo),
 *  - notas → [LogicColors.OnDarkMuted] en `bodyMedium`.
 *
 * Los tamaños se derivan de [cellSize] en vez de fijarse en `sp` constantes: el
 * tablero es responsivo (§ tablero cuadrado adaptativo) y una tipografía fija se
 * saldría de la celda en pantallas estrechas.
 */
@Composable
private fun SudokuCellContent(
    cell: SudokuCell,
    cellSize: Dp,
    shakeOffset: Float,
) {
    Box(
        modifier = Modifier
            .size(cellSize)
            .offset { IntOffset(shakeOffset.roundToInt(), 0) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            cell.value != null -> Text(
                text = cell.value.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontSize = (cellSize.value * DIGIT_SIZE_FRACTION).sp,
                fontWeight = if (cell.isFixed) FontWeight.Black else FontWeight.Bold,
                color = when {
                    cell.hasConflict -> LogicColors.Error
                    cell.isFixed -> LogicColors.OnDark
                    else -> CategoryPalette.Logic
                },
                textAlign = TextAlign.Center,
            )

            cell.notes.isNotEmpty() -> NotesGrid(notes = cell.notes, cellSize = cellSize)
        }
    }
}

/**
 * Mini-rejilla 3x3 de notas de lápiz dentro de una celda vacía. Cada dígito
 * ocupa SIEMPRE su casilla fija (el `1` arriba-izquierda, el `9` abajo-derecha),
 * aunque no esté anotado: así el jugador localiza una nota por posición sin
 * releer todos los números, igual que en un Sudoku de papel.
 */
@Composable
private fun NotesGrid(notes: Set<Int>, cellSize: Dp) {
    Column(verticalArrangement = Arrangement.Center) {
        repeat(NeonSudokuConfig.BLOCK_SIZE) { noteRow ->
            Row(horizontalArrangement = Arrangement.Center) {
                repeat(NeonSudokuConfig.BLOCK_SIZE) { noteCol ->
                    val digit = noteRow * NeonSudokuConfig.BLOCK_SIZE + noteCol + 1
                    Box(
                        modifier = Modifier.size(cellSize / NeonSudokuConfig.BLOCK_SIZE),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (digit in notes) {
                            Text(
                                text = digit.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = (cellSize.value * NOTE_SIZE_FRACTION).sp,
                                color = LogicColors.OnDarkMuted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dibujo del panel (resaltados + rejilla)
// ---------------------------------------------------------------------------

/**
 * Pinta el panel completo bajo los números.
 *
 * ## Orden de capas (el "porqué")
 * El orden importa y no es el ingenuo "todo lo pintado, y la rejilla al final":
 *
 *  1. **Rellenos de resaltado** (fila/columna/bloque → gemelos → choques), de más
 *     tenue a más intenso, para que el más fuerte gane donde varios coinciden.
 *  2. **Rejilla**, que se dibuja SOBRE esos tintes: son fondos, y la estructura
 *     del tablero debe seguir leyéndose por encima de ellos.
 *  3. **Contornos de choque y de celda seleccionada**, ya por ENCIMA de la
 *     rejilla. Antes iban debajo y las líneas mayores del bloque cruzaban por
 *     encima del anillo de foco, partiéndolo en dos y haciendo que el cursor
 *     pareciera hundido en el tablero. El foco es el elemento con el que el
 *     jugador interactúa: tiene que flotar sobre la rejilla, no al revés.
 *
 * La celda seleccionada además **tapa** el trozo de rejilla que la cruza (se
 * repinta su fondo en opaco antes del anillo): así el foco se lee como una pieza
 * sólida por encima del panel, en vez de como un recuadro transparente con
 * líneas atravesándolo.
 *
 * @param conflictBlink alfa animado `0..1` del halo de las celdas en choque.
 */
private fun DrawScope.drawBoardBackground(state: NeonSudokuUiState, conflictBlink: Float) {
    val cellPx = size.width / NeonSudokuConfig.BOARD_SIZE
    val accent = CategoryPalette.Logic

    // Fondo del panel: BackgroundDark (§3 del brief) con esquinas redondeadas.
    drawRoundRect(
        color = LogicColors.BackgroundDark,
        cornerRadius = CornerRadius(BoardCorner.toPx(), BoardCorner.toPx()),
    )

    val selected = state.selectedCell
    if (selected != null) {
        // 1) Fila, columna y bloque del cursor: tinte MUY tenue. Es una ayuda de
        // lectura, no un elemento protagonista; si compitiera con los números el
        // tablero se volvería ruidoso (§9.1: acento escaso).
        for (i in 0 until NeonSudokuConfig.BOARD_SIZE) {
            fillCell(selected.row, i, cellPx, accent.copy(alpha = PEER_ALPHA))
            fillCell(i, selected.col, cellPx, accent.copy(alpha = PEER_ALPHA))
        }
        val blockRow = (selected.row / NeonSudokuConfig.BLOCK_SIZE) * NeonSudokuConfig.BLOCK_SIZE
        val blockCol = (selected.col / NeonSudokuConfig.BLOCK_SIZE) * NeonSudokuConfig.BLOCK_SIZE
        for (dRow in 0 until NeonSudokuConfig.BLOCK_SIZE) {
            for (dCol in 0 until NeonSudokuConfig.BLOCK_SIZE) {
                fillCell(blockRow + dRow, blockCol + dCol, cellPx, accent.copy(alpha = PEER_ALPHA))
            }
        }
    }

    // 2) Todas las celdas con el MISMO número que la seleccionada (ayuda de UX
    // crítica del brief): tinte más marcado que el de fila/columna para que el
    // ojo salte directamente a ellas.
    state.highlightedNumber?.let { digit ->
        state.board.cellsWithValue(digit).forEach { cell ->
            fillCell(cell.position.row, cell.position.col, cellPx, accent.copy(alpha = TWIN_ALPHA))
        }
    }

    // 3) Relleno de las celdas en choque (su contorno va después de la rejilla).
    // Se resuelven las posiciones una sola vez: se recorren dos veces (relleno
    // ahora, contorno tras la rejilla) y son como mucho un puñado de celdas.
    val conflicted = ArrayList<CellPosition>(4)
    for (row in 0 until NeonSudokuConfig.BOARD_SIZE) {
        for (col in 0 until NeonSudokuConfig.BOARD_SIZE) {
            if (!state.board.cellAt(row, col).hasConflict) continue
            conflicted += CellPosition(row, col)
            fillCell(row, col, cellPx, LogicColors.Error.copy(alpha = CONFLICT_ALPHA * conflictBlink))
        }
    }

    // 4) Rejilla: sobre los tintes de fondo, pero bajo los contornos de foco/choque.
    drawGridLines(cellPx, accent)

    // 5) Contorno de las celdas en choque, ya por encima de la rejilla.
    conflicted.forEach { position ->
        drawRoundRect(
            color = LogicColors.Error.copy(alpha = 0.9f * conflictBlink),
            topLeft = Offset(position.col * cellPx, position.row * cellPx),
            size = Size(cellPx, cellPx),
            cornerRadius = CornerRadius(CellCorner.toPx(), CellCorner.toPx()),
            style = Stroke(width = CONFLICT_STROKE_DP.dp.toPx()),
        )
    }

    // 6) Celda seleccionada: la capa más alta del panel. Primero tapa la rejilla
    // que la cruza repintando su fondo en opaco, luego su propio tinte, y encima
    // el anillo neón (halo ancho + trazo nítido) que marca el cursor.
    if (selected != null) {
        val topLeft = Offset(selected.col * cellPx, selected.row * cellPx)
        val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())
        val cellSize = Size(cellPx, cellPx)

        drawRoundRect(LogicColors.BackgroundDark, topLeft, cellSize, corner)
        // Una celda seleccionada que además choca conserva su rojo: el error manda
        // sobre el acento de foco (es información, no decoración).
        val focusTint = if (state.board.cellAt(selected).hasConflict) {
            LogicColors.Error.copy(alpha = CONFLICT_ALPHA * conflictBlink)
        } else {
            accent.copy(alpha = SELECTED_FILL_ALPHA)
        }
        drawRoundRect(focusTint, topLeft, cellSize, corner)

        drawRoundRect(
            color = accent.copy(alpha = 0.28f),
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = corner,
            style = Stroke(width = SELECTED_STROKE_DP.dp.toPx() * 3.5f),
        )
        drawRoundRect(
            color = accent,
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = corner,
            style = Stroke(width = SELECTED_STROKE_DP.dp.toPx()),
        )
    }
}

/** Rellena la celda `(row, col)` con [color]. Helper local: la conversión
 *  coordenada→rectángulo se repite en los tres resaltados. */
private fun DrawScope.fillCell(row: Int, col: Int, cellPx: Float, color: Color) {
    drawRect(color = color, topLeft = Offset(col * cellPx, row * cellPx), size = Size(cellPx, cellPx))
}

/**
 * Rejilla del panel.
 *
 * Las **líneas menores** (separación 1x1) son un trazo fino y sutil en
 * [LogicColors.SurfaceVariantDark]: estructuran sin pedir atención.
 *
 * Las **líneas mayores** (bloques 3x3) llevan el azul de la categoría Lógica y
 * se dibujan con la MISMA proporción de capas que `drawNeonTile` —halo ancho →
 * halo intermedio → trazo nítido → núcleo blanco—, tal y como exige CLAUDE.md
 * §9.7 para trazos que no son contornos de tile (el mismo criterio que los
 * cables de Neon Circuit). Se resuelve dentro del `DrawScope` en lugar de con
 * `Modifier.softGlow`, porque ese modificador aplica una sombra al composable
 * entero: aquí el halo debe seguir cada línea interior del panel, no su borde.
 */
private fun DrawScope.drawGridLines(cellPx: Float, accent: Color) {
    val minorWidth = MINOR_LINE_DP.dp.toPx()
    val majorWidth = MAJOR_LINE_DP.dp.toPx()

    // Líneas menores: todas las divisiones que NO son frontera de bloque.
    for (i in 1 until NeonSudokuConfig.BOARD_SIZE) {
        if (i % NeonSudokuConfig.BLOCK_SIZE == 0) continue
        val p = i * cellPx
        drawLine(LogicColors.SurfaceVariantDark, Offset(p, 0f), Offset(p, size.height), minorWidth)
        drawLine(LogicColors.SurfaceVariantDark, Offset(0f, p), Offset(size.width, p), minorWidth)
    }

    // Líneas mayores: las dos divisiones interiores de bloque en cada eje.
    for (i in NeonSudokuConfig.BLOCK_SIZE until NeonSudokuConfig.BOARD_SIZE step NeonSudokuConfig.BLOCK_SIZE) {
        val p = i * cellPx
        drawNeonGridLine(Offset(p, 0f), Offset(p, size.height), majorWidth, accent)
        drawNeonGridLine(Offset(0f, p), Offset(size.width, p), majorWidth, accent)
    }

    // Marco exterior: contorno sutil, NO un bezel neón completo. §9.7 advierte
    // explícitamente que un marco intenso sobre un tablero denso se ve
    // sobrecargado y compite con el contenido.
    drawRoundRect(
        color = accent.copy(alpha = 0.45f),
        cornerRadius = CornerRadius(BoardCorner.toPx(), BoardCorner.toPx()),
        style = Stroke(width = majorWidth),
    )
}

/**
 * Una línea mayor como tubo de neón (misma receta de capas que `drawNeonTile`).
 *
 * Usa [StrokeCap.Butt] y NO `Round`: un cap redondeado sobresale media anchura
 * de trazo más allá del punto final, y con el halo (4.5x de ancho) eso asomaba
 * como un bulbo fuera del marco del panel en los cuatro extremos.
 */
private fun DrawScope.drawNeonGridLine(start: Offset, end: Offset, width: Float, color: Color) {
    drawLine(color.copy(alpha = 0.22f), start, end, width * 4.5f, StrokeCap.Butt)
    drawLine(color.copy(alpha = 0.45f), start, end, width * 2.1f, StrokeCap.Butt)
    drawLine(color, start, end, width, StrokeCap.Butt)
    drawLine(Color.White.copy(alpha = 0.5f), start, end, width * 0.4f, StrokeCap.Butt)
}

/**
 * Celebración de unidad completada: recorre las celdas de la [wave] encendiendo
 * cada una con un **destello** (sube y baja) y una **ráfaga de chispas**, con el
 * arranque escalonado por distancia al epicentro (ver [CompletionWave]).
 *
 * El destello usa una curva `sin(π·p)`: nace en 0, alcanza su pico a mitad del
 * recorrido y vuelve a 0. Así la celda se enciende y se apaga sola, sin cortes
 * secos y sin dejar residuo cuando la onda termina — importante porque esto se
 * pinta ENCIMA del dígito, y cualquier resto permanente lo ensuciaría.
 *
 * Las chispas salen del componente compartido [drawNeonSparks] (fuente única del
 * efecto en la app), con semilla derivada de la celda para que cada una chispee
 * distinto pero de forma estable entre frames.
 */
private fun DrawScope.drawCompletionWave(wave: CompletionWave) {
    val cellPx = size.width / NeonSudokuConfig.BOARD_SIZE
    val accent = CategoryPalette.Logic
    val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())

    wave.cells.forEach { position ->
        val p = wave.localProgress(position)
        if (p <= 0f || p >= 1f) return@forEach

        val flash = sin(p * PI.toFloat())
        val topLeft = Offset(position.col * cellPx, position.row * cellPx)
        val cellSize = Size(cellPx, cellPx)

        // Relleno que se enciende y se apaga.
        drawRoundRect(
            color = accent.copy(alpha = WAVE_FILL_ALPHA * flash),
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = corner,
        )
        // Aro nítido con núcleo blanco: el mismo remate "prendido" del tubo neón.
        drawRoundRect(
            color = accent.copy(alpha = 0.9f * flash),
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = corner,
            style = Stroke(width = WAVE_STROKE_DP.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.7f * flash),
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = corner,
            style = Stroke(width = WAVE_STROKE_DP.dp.toPx() * 0.4f),
        )

        drawNeonSparks(
            center = topLeft + Offset(cellPx / 2f, cellPx / 2f),
            reach = cellPx * 0.9f,
            accent = accent,
            progress = p,
            count = WAVE_SPARK_COUNT,
            // Semilla estable por celda: sin ella las chispas "hervirían" con
            // ángulos nuevos en cada frame (ver KDoc de drawNeonSparks).
            seed = position.row * NeonSudokuConfig.BOARD_SIZE + position.col,
        )
    }
}

/**
 * Onda de luz de victoria: una banda diagonal de gradiente que cruza el panel de
 * esquina a esquina. Se pinta sobre el contenido (números incluidos) porque es
 * luz atravesando el panel holográfico, no un fondo.
 *
 * @param progress `0` = banda fuera por la izquierda, `1` = fuera por la derecha.
 */
private fun DrawScope.drawVictorySweep(progress: Float) {
    val band = size.width * SWEEP_BAND_FRACTION
    // El recorrido va de -band hasta ancho+band para que la banda entre y salga
    // por completo del panel en vez de aparecer/desaparecer de golpe en el borde.
    val head = -band + progress * (size.width + band * 2f)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                CategoryPalette.Logic.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.55f),
                LogicColors.Success.copy(alpha = 0.35f),
                Color.Transparent,
            ),
            // Diagonal (el desplazamiento en Y da la inclinación): una banda
            // vertical pura se leería como un simple parpadeo de columna.
            start = Offset(head - band, 0f),
            end = Offset(head + band, size.height),
        ),
        cornerRadius = CornerRadius(BoardCorner.toPx(), BoardCorner.toPx()),
    )
}

/**
 * Desplazamiento horizontal de la sacudida de error: una **sinusoide
 * amortiguada**. La amplitud decae con `(1 - progress)` para que la celda se
 * frene sola en su sitio en vez de cortarse en seco a mitad de oscilación.
 */
private fun shakeOffsetPx(progress: Float, cellSize: Dp): Float {
    if (progress <= 0f || progress >= 1f) return 0f
    val amplitude = cellSize.value * SHAKE_AMPLITUDE_FRACTION * (1f - progress)
    return sin(progress * SHAKE_CYCLES * 2f * PI.toFloat()) * amplitude
}

// --- Constantes de render (no de balance; el balance vive en NeonSudokuConfig) ---

/** Lado máximo del panel: más allá, el tablero se vería desproporcionado en tablet. */
private val BoardMaxSize = 400.dp

/** Radio de esquina del panel (§9.6: bordes muy redondeados). */
private val BoardCorner = 24.dp

/** Radio de esquina del resaltado de una celda (escala `small` de §9.6). */
private val CellCorner = 8.dp

/** Grosor de las líneas menores 1x1 (delgadas y sutiles). */
private const val MINOR_LINE_DP = 1f

/** Grosor del trazo nítido de las líneas mayores 3x3. */
private const val MAJOR_LINE_DP = 2.4f

/** Alfa del tinte de fila/columna/bloque del cursor. */
private const val PEER_ALPHA = 0.07f

/** Alfa del tinte de las celdas con el mismo número que la seleccionada. */
private const val TWIN_ALPHA = 0.20f

/** Alfa base del relleno de una celda en choque (lo modula el parpadeo). */
private const val CONFLICT_ALPHA = 0.22f

/** Alfa del relleno de la celda con el foco. Algo más marcado que el de los
 *  gemelos ([TWIN_ALPHA]) —el cursor debe ganar a cualquier otro resaltado— pero
 *  lo bastante bajo para no comerse el dígito que hay encima. */
private const val SELECTED_FILL_ALPHA = 0.26f

/** Grosor del contorno de una celda en choque. */
private const val CONFLICT_STROKE_DP = 2f

/** Grosor del contorno nítido de la celda seleccionada. */
private const val SELECTED_STROKE_DP = 2.4f

/** Periodo del parpadeo del halo de choque (ms). Lento: §9.4 pide bucles
 *  ambientales de baja amplitud (~1.2–2 s). */
private const val CONFLICT_BLINK_MS = 900

/** Tamaño del dígito como fracción del lado de la celda. */
private const val DIGIT_SIZE_FRACTION = 0.52f

/** Tamaño de una nota como fracción del lado de la celda. */
private const val NOTE_SIZE_FRACTION = 0.20f

/** Amplitud de la sacudida como fracción del lado de la celda. */
private const val SHAKE_AMPLITUDE_FRACTION = 0.22f

/** Oscilaciones completas de la sacudida de error. */
private const val SHAKE_CYCLES = 3f

/** Anchura de la banda de luz de victoria como fracción del lado del panel. */
private const val SWEEP_BAND_FRACTION = 0.28f

/**
 * Fracción del reloj de la onda de compleción dedicada a **escalonar** el
 * arranque por distancia al epicentro: la celda más lejana empieza a destellar
 * cuando el reloj llega aquí, y el resto (1 − esto) es lo que dura el destello
 * de cada celda. Subirlo marca más la propagación; bajarlo la acerca a un
 * destello simultáneo. Mismo parámetro que la limpieza de Tetris Neón.
 */
private const val WAVE_STAGGER_SPAN = 0.5f

/** Alfa del pico del relleno de una celda durante la onda de compleción. */
private const val WAVE_FILL_ALPHA = 0.55f

/** Grosor del aro que enciende cada celda en la onda de compleción. */
private const val WAVE_STROKE_DP = 2.6f

/** Chispas que libera cada celda al completarse su unidad. */
private const val WAVE_SPARK_COUNT = 6
