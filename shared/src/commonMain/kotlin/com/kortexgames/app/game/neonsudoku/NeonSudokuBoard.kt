package com.kortexgames.app.game.neonsudoku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.ui.components.BoardClock
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonSparks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Estética (CLAUDE.md §9.2 y §9.7): el panel es la **placa** compartida del kit de tableros
 * ([drawNeonBoardPlate], la misma de Línea Neón, Conectores y Bloques Neón), los bloques 3x3
 * alternan un baño muy tenue del azul de Lógica ([CategoryPalette.Logic]) y las pistas fijas
 * descansan sobre una baldosa apenas más clara que las celdas del jugador. Las líneas mayores
 * conservan la receta de tubo de neón de `drawNeonTile` (halo ancho → intermedio → trazo nítido
 * → núcleo blanco) replicada sobre líneas, pero más finas que antes: con los bloques ya
 * diferenciados por tono dejan de cargar solas con toda la estructura.
 *
 * ## Animación
 * Un único [BoardClock] (el de la pantalla) mueve todo lo que late o aparece: la entrada en
 * cascada de los dígitos, el "pop" + anillo de una jugada, la respiración del cursor y el
 * parpadeo de los choques. Se lee **solo en fase de dibujo** (`drawBehind`, `graphicsLayer`),
 * así que avanzar el tiempo redibuja sin recomponer las 81 celdas.
 */

/**
 * Onda de celebración de una unidad completada (fila, columna o bloque 3x3).
 *
 * Réplica del lenguaje de la limpieza de línea de Bloques Neón: un **reloj único**
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
 * @param clock reloj de animación de la pantalla (ver "Animación" en la cabecera del archivo).
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
    clock: BoardClock,
    onSelectCell: (row: Int, col: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Identidad del PUZZLE (no del tablero): solo cambia al empezar otra partida, porque sale de
    // las pistas fijas. Es lo que reinicia la entrada en cascada y descarta los efectos de la
    // partida anterior; keyear con `state.board` los reiniciaría en cada jugada.
    val puzzleKey = remember(state.board) { state.board.fixedSignature() }

    // --- Entrada en cascada -------------------------------------------------
    val introAt = remember(puzzleKey) { clock.peek() }
    // Pasada la cascada las celdas dejan de leer el reloj: sin este corte, los 81 dígitos
    // invalidarían su capa en cada frame durante toda la partida para dibujar siempre escala 1.
    var introDone by remember(puzzleKey) { mutableStateOf(false) }
    LaunchedEffect(puzzleKey) {
        delay(INTRO_TOTAL_MS)
        introDone = true
    }

    // --- Jugadas recientes ("pop" del dígito + anillo) ------------------------
    // Celda → instante en que el jugador escribió su valor. Se detecta comparando con el tablero
    // anterior en vez de pedirle un efecto al ViewModel: es adorno puro, y así cualquier vía que
    // rellene una celda (teclado, pista) lo dispara sin tocar el contrato MVI.
    val placedAt = remember(puzzleKey) { mutableStateMapOf<CellPosition, Float>() }
    val previousBoard = remember(puzzleKey) { arrayOfNulls<Board>(1) }
    LaunchedEffect(state.board) {
        val previous = previousBoard[0]
        previousBoard[0] = state.board
        if (previous == null) return@LaunchedEffect
        for (row in 0 until NeonSudokuConfig.BOARD_SIZE) {
            for (col in 0 until NeonSudokuConfig.BOARD_SIZE) {
                val cell = state.board.cellAt(row, col)
                if (cell.value == null || cell.value == previous.cellAt(row, col).value) continue
                val position = cell.position
                val stamp = clock.peek()
                placedAt[position] = stamp
                // Se retira sola; solo si sigue siendo ESTA jugada (el jugador puede reescribir
                // la misma celda antes de que termine la anterior).
                launch {
                    delay(PLACE_FX_CLEANUP_MS)
                    if (placedAt[position] == stamp) placedAt.remove(position)
                }
            }
        }
    }

    // --- Cursor ---------------------------------------------------------------
    // En unidades de celda (col, fila): el cursor SE DESLIZA de una celda a otra con resorte en
    // vez de teletransportarse, que es lo que hace que seleccionar se sienta físico (§9.4).
    val cursor = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var cursorPlaced by remember { mutableStateOf(false) }
    val selected = state.selectedCell
    LaunchedEffect(selected) {
        if (selected == null) return@LaunchedEffect
        val target = Offset(selected.col.toFloat(), selected.row.toFloat())
        if (cursorPlaced) {
            cursor.animateTo(target, spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMedium))
        } else {
            // Primera selección: no hay "de dónde venir"; deslizarlo desde la esquina sería ruido.
            cursor.snapTo(target)
            cursorPlaced = true
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Tablero SIEMPRE cuadrado: se toma la menor dimensión disponible para que
        // las celdas no se deformen con la relación de aspecto del dispositivo.
        val side = minOf(maxWidth, maxHeight, BoardMaxSize)
        val cellSize = side / NeonSudokuConfig.BOARD_SIZE

        Box(
            modifier = Modifier
                .size(side)
                .drawBehind {
                    drawBoardBackground(
                        state = state,
                        time = clock.seconds,
                        cursor = if (cursorPlaced && selected != null) cursor.value else null,
                        victoryLit = if (sweepProgress > 0f) sin(sweepProgress * PI.toFloat()) else 0f,
                    )
                }
                // Las celebraciones van en drawWithContent (no drawBehind) para
                // pasar POR ENCIMA de los números: son luz sobre el panel, no
                // fondo. La onda de victoria se pinta la última porque es el
                // remate que cierra la partida.
                .drawWithContent {
                    drawContent()
                    if (placedAt.isNotEmpty()) {
                        val now = clock.seconds
                        placedAt.forEach { (position, stamp) -> drawPlacementRing(position, now - stamp) }
                    }
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
                                scale = {
                                    var scale = 1f
                                    if (!introDone) scale = boardCascade(row, col, clock.seconds - introAt)
                                    placedAt[cell.position]?.let { stamp ->
                                        val p = ((clock.seconds - stamp) / PLACE_POP_SEC).coerceIn(0f, 1f)
                                        scale *= PLACE_POP_FROM + (1f - PLACE_POP_FROM) * EaseOutBack.transform(p)
                                    }
                                    scale
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Huella de las pistas fijas del tablero: cambia solo cuando cambia el puzzle, no con las jugadas
 * del jugador (que nunca tocan una celda fija). Sirve de clave para "partida nueva".
 */
private fun Board.fixedSignature(): Int {
    var hash = 17
    for (row in 0 until NeonSudokuConfig.BOARD_SIZE) {
        for (col in 0 until NeonSudokuConfig.BOARD_SIZE) {
            val cell = cellAt(row, col)
            hash = hash * 31 + if (cell.isFixed) (cell.value ?: 0) else 0
        }
    }
    return hash
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
 *
 * @param scale escala del contenido (entrada en cascada y "pop" de la jugada). Es una lambda y se
 *   invoca dentro de `graphicsLayer` a propósito: así leer el reloj redibuja la capa de esta
 *   celda sin recomponerla.
 */
@Composable
private fun SudokuCellContent(
    cell: SudokuCell,
    cellSize: Dp,
    shakeOffset: Float,
    scale: () -> Float,
) {
    Box(
        modifier = Modifier
            .size(cellSize)
            .offset { IntOffset(shakeOffset.roundToInt(), 0) }
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                // El rebote se pasa de 1; la opacidad no.
                alpha = s.coerceIn(0f, 1f)
            },
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
 *  1. **Placa** del kit y, recortado a su contorno redondeado, el **relieve** del tablero:
 *     bloques 3x3 alternos y baldosas de las pistas fijas.
 *  2. **Rellenos de resaltado** (fila/columna/bloque → gemelos → choques), de más
 *     tenue a más intenso, para que el más fuerte gane donde varios coinciden.
 *  3. **Rejilla**, que se dibuja SOBRE esos tintes: son fondos, y la estructura
 *     del tablero debe seguir leyéndose por encima de ellos.
 *  4. **Contornos de choque y cursor**, ya por ENCIMA de la rejilla y sin recorte (su halo puede
 *     asomar por el borde del panel). Si fueran debajo, las líneas mayores del bloque cruzarían
 *     el anillo de foco partiéndolo en dos. El foco es el elemento con el que el jugador
 *     interactúa: tiene que flotar sobre la rejilla, no al revés.
 *
 * El cursor además **tapa** el trozo de rejilla que cruza (repinta su fondo en opaco antes del
 * anillo): así se lee como una pieza sólida sobre el panel, también a mitad de un deslizamiento,
 * cuando no coincide con ninguna celda.
 *
 * @param time segundos del reloj del tablero (respiración del cursor y parpadeo de choques).
 * @param cursor posición animada del cursor en unidades de celda (x = columna, y = fila), o
 *   `null` si no hay selección.
 * @param victoryLit 0..1: encendido del marco durante el barrido de victoria.
 */
private fun DrawScope.drawBoardBackground(
    state: NeonSudokuUiState,
    time: Float,
    cursor: Offset?,
    victoryLit: Float,
) {
    val cellPx = size.width / NeonSudokuConfig.BOARD_SIZE
    val accent = CategoryPalette.Logic
    val blockPx = cellPx * NeonSudokuConfig.BLOCK_SIZE
    val blocksPerSide = NeonSudokuConfig.BOARD_SIZE / NeonSudokuConfig.BLOCK_SIZE
    // Parpadeo lento del rojo de choque. Sale del reloj compartido: es el único latido del
    // tablero junto a la respiración del cursor, y ambos son de baja amplitud (§9.4).
    val conflictBlink = 0.675f + 0.325f * sin(time * 2f * PI.toFloat() / CONFLICT_BLINK_SEC)

    drawNeonBoardPlate(accent = accent, lit = victoryLit, litColor = LogicColors.Success, corner = BoardCorner)

    val selected = state.selectedCell
    val conflicted = ArrayList<CellPosition>(4)

    // Todo lo que rellena celdas se recorta al contorno redondeado de la placa: las celdas de
    // las esquinas son rectángulos y, sin recorte, sus tintes asomaban por fuera del radio.
    val plate = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(BoardCorner.toPx())))
    }
    clipPath(plate) {
        // 1a) Bloques 3x3 alternos (en damero): separan los nueve bloques por tono, de modo que
        // la estructura se lee aunque las líneas mayores sean finas.
        for (blockRow in 0 until blocksPerSide) {
            for (blockCol in 0 until blocksPerSide) {
                if ((blockRow + blockCol) % 2 != 0) continue
                drawRect(
                    color = accent.copy(alpha = BLOCK_TINT_ALPHA),
                    topLeft = Offset(blockCol * blockPx, blockRow * blockPx),
                    size = Size(blockPx, blockPx),
                )
            }
        }

        // 1b) Baldosa de las pistas fijas: lo dado "viene con el tablero" y lo del jugador se
        // escribe en hueco. Refuerza la distinción que ya hace el color del dígito.
        val inset = GIVEN_INSET_DP.dp.toPx()
        val givenCorner = CornerRadius(GivenCorner.toPx())
        for (row in 0 until NeonSudokuConfig.BOARD_SIZE) {
            for (col in 0 until NeonSudokuConfig.BOARD_SIZE) {
                val cell = state.board.cellAt(row, col)
                if (cell.hasConflict) conflicted += cell.position
                if (!cell.isFixed) continue
                drawRoundRect(
                    color = LogicColors.OnDark.copy(alpha = GIVEN_PAD_ALPHA),
                    topLeft = Offset(col * cellPx + inset, row * cellPx + inset),
                    size = Size(cellPx - inset * 2f, cellPx - inset * 2f),
                    cornerRadius = givenCorner,
                )
            }
        }

        if (selected != null) {
            // 2a) Fila, columna y bloque del cursor: tinte MUY tenue. Es una ayuda de
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

        // 2b) Todas las celdas con el MISMO número que la seleccionada (ayuda de UX
        // crítica del brief): una ficha encendida con su aro, más marcada que el tinte de
        // fila/columna para que el ojo salte directamente a ellas.
        state.highlightedNumber?.let { digit ->
            val twinInset = TWIN_INSET_DP.dp.toPx()
            val twinCorner = CornerRadius(GivenCorner.toPx())
            state.board.cellsWithValue(digit).forEach { cell ->
                val topLeft = Offset(
                    cell.position.col * cellPx + twinInset,
                    cell.position.row * cellPx + twinInset,
                )
                val twinSize = Size(cellPx - twinInset * 2f, cellPx - twinInset * 2f)
                drawRoundRect(accent.copy(alpha = TWIN_ALPHA), topLeft, twinSize, twinCorner)
                drawRoundRect(
                    color = accent.copy(alpha = TWIN_RING_ALPHA),
                    topLeft = topLeft,
                    size = twinSize,
                    cornerRadius = twinCorner,
                    style = Stroke(width = TWIN_RING_DP.dp.toPx()),
                )
            }
        }

        // 2c) Relleno de las celdas en choque (su contorno va después de la rejilla).
        conflicted.forEach { position ->
            fillCell(position.row, position.col, cellPx, LogicColors.Error.copy(alpha = CONFLICT_ALPHA * conflictBlink))
        }

        // 3) Rejilla: sobre los tintes de fondo, pero bajo los contornos de foco/choque.
        drawGridLines(cellPx, accent)
    }

    // 4a) Contorno de las celdas en choque, ya por encima de la rejilla.
    conflicted.forEach { position ->
        drawRoundRect(
            color = LogicColors.Error.copy(alpha = 0.9f * conflictBlink),
            topLeft = Offset(position.col * cellPx, position.row * cellPx),
            size = Size(cellPx, cellPx),
            cornerRadius = CornerRadius(CellCorner.toPx(), CellCorner.toPx()),
            style = Stroke(width = CONFLICT_STROKE_DP.dp.toPx()),
        )
    }

    // 4b) Cursor: la capa más alta del panel.
    if (cursor != null && selected != null) {
        drawCursor(
            topLeft = Offset(cursor.x * cellPx, cursor.y * cellPx),
            cellPx = cellPx,
            accent = accent,
            // Una celda seleccionada que además choca conserva su rojo: el error manda
            // sobre el acento de foco (es información, no decoración).
            tint = if (state.board.cellAt(selected).hasConflict) {
                LogicColors.Error.copy(alpha = CONFLICT_ALPHA * conflictBlink)
            } else {
                accent.copy(alpha = SELECTED_FILL_ALPHA)
            },
            breath = 0.5f + 0.5f * sin(time * 2f * PI.toFloat() / CURSOR_BREATH_SEC),
        )
    }
}

/**
 * El cursor: una baldosa opaca con el tinte de foco y un anillo de neón que respira.
 *
 * Mismas capas que `drawNeonTile` (halo ancho → intermedio → trazo nítido → núcleo blanco); no
 * se llama a esa función porque aquí el halo ancho es lo único que late y `drawNeonTile` anima
 * el tubo entero con su `activeAmt`.
 *
 * @param topLeft esquina del cursor en píxeles (puede caer entre dos celdas mientras se desliza).
 * @param tint relleno translúcido sobre el fondo opaco (acento, o rojo si la celda choca).
 * @param breath 0..1, fase de la respiración del halo.
 */
private fun DrawScope.drawCursor(topLeft: Offset, cellPx: Float, accent: Color, tint: Color, breath: Float) {
    val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())
    val cellSize = Size(cellPx, cellPx)
    val stroke = SELECTED_STROKE_DP.dp.toPx()

    drawRoundRect(lerp(LogicColors.SurfaceDark, LogicColors.BackgroundDark, 0.35f), topLeft, cellSize, corner)
    drawRoundRect(tint, topLeft, cellSize, corner)

    drawRoundRect(
        color = accent.copy(alpha = 0.10f + 0.12f * breath),
        topLeft = topLeft,
        size = cellSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * (3.2f + 1.2f * breath)),
    )
    drawRoundRect(accent.copy(alpha = 0.34f), topLeft, cellSize, corner, style = Stroke(width = stroke * 2.1f))
    drawRoundRect(accent, topLeft, cellSize, corner, style = Stroke(width = stroke))
    drawRoundRect(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = topLeft,
        size = cellSize,
        cornerRadius = corner,
        style = Stroke(width = stroke * 0.4f),
    )
}

/**
 * Anillo que se abre desde la celda donde el jugador acaba de escribir, más un destello corto.
 * Acompaña al "pop" del dígito: es la respuesta inmediata a la jugada (§9.4, micro-feedback).
 *
 * @param age segundos desde la jugada; fuera de `0..PLACE_RING_SEC` no dibuja nada.
 */
private fun DrawScope.drawPlacementRing(position: CellPosition, age: Float) {
    val p = age / PLACE_RING_SEC
    if (p <= 0f || p >= 1f) return
    val cellPx = size.width / NeonSudokuConfig.BOARD_SIZE
    val fade = 1f - p
    val grow = cellPx * PLACE_RING_GROW * p
    val topLeft = Offset(position.col * cellPx - grow / 2f, position.row * cellPx - grow / 2f)
    val ringSize = Size(cellPx + grow, cellPx + grow)
    val corner = CornerRadius(CellCorner.toPx() + grow / 2f)

    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f * fade * fade),
        topLeft = Offset(position.col * cellPx, position.row * cellPx),
        size = Size(cellPx, cellPx),
        cornerRadius = CornerRadius(CellCorner.toPx()),
    )
    drawRoundRect(
        color = CategoryPalette.Logic.copy(alpha = 0.85f * fade),
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(width = PLACE_RING_STROKE_DP.dp.toPx() * (0.4f + 0.6f * fade)),
    )
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
 *
 * El marco exterior no se dibuja aquí: lo pone [drawNeonBoardPlate], deliberadamente sutil
 * (§9.7: un bezel intenso sobre un tablero denso compite con el contenido).
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
}

/**
 * Una línea mayor como tubo de neón (misma receta de capas que `drawNeonTile`), a media
 * intensidad: los bloques ya se distinguen por tono, así que la línea acompaña en vez de mandar.
 *
 * Usa [StrokeCap.Butt] y NO `Round`: un cap redondeado sobresale media anchura
 * de trazo más allá del punto final, y con el halo (4.5x de ancho) eso asomaba
 * como un bulbo fuera del marco del panel en los cuatro extremos.
 */
private fun DrawScope.drawNeonGridLine(start: Offset, end: Offset, width: Float, color: Color) {
    drawLine(color.copy(alpha = 0.14f), start, end, width * 4.5f, StrokeCap.Butt)
    drawLine(color.copy(alpha = 0.32f), start, end, width * 2.1f, StrokeCap.Butt)
    drawLine(color.copy(alpha = 0.9f), start, end, width, StrokeCap.Butt)
    drawLine(Color.White.copy(alpha = 0.4f), start, end, width * 0.4f, StrokeCap.Butt)
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
private const val MAJOR_LINE_DP = 1.8f

/** Radio de esquina de la baldosa de una pista fija y de la ficha de un gemelo. */
private val GivenCorner = 6.dp

/** Baño del acento sobre los bloques 3x3 alternos. Muy bajo: es relieve, no color. */
private const val BLOCK_TINT_ALPHA = 0.055f

/** Margen entre la baldosa de una pista fija y el borde de su celda. */
private const val GIVEN_INSET_DP = 2.5f

/** Opacidad de la baldosa de una pista fija (blanco del tema sobre la placa). */
private const val GIVEN_PAD_ALPHA = 0.05f

/** Margen de la ficha de un gemelo respecto al borde de su celda. */
private const val TWIN_INSET_DP = 2f

/** Opacidad del aro de la ficha de un gemelo. */
private const val TWIN_RING_ALPHA = 0.55f

/** Grosor del aro de la ficha de un gemelo. */
private const val TWIN_RING_DP = 1.2f

/** Periodo de la respiración del halo del cursor (s). Ambiente lento (§9.4). */
private const val CURSOR_BREATH_SEC = 1.8f

/**
 * Tiempo tras el que las celdas dejan de leer el reloj para la entrada en cascada (ms). Cubre la
 * última diagonal del 9x9 (16 pasos de 35 ms + 300 ms de asentado, ver `boardCascade`) con margen.
 */
private const val INTRO_TOTAL_MS = 1_100L

/** Duración del "pop" del dígito recién escrito (s). Micro-feedback (§9.4). */
private const val PLACE_POP_SEC = 0.24f

/** Escala desde la que nace el dígito en su "pop". */
private const val PLACE_POP_FROM = 0.5f

/** Duración del anillo que se abre desde la celda jugada (s). */
private const val PLACE_RING_SEC = 0.42f

/** Cuánto crece el anillo de la jugada, en fracción del lado de la celda. */
private const val PLACE_RING_GROW = 0.7f

/** Grosor inicial del anillo de la jugada. */
private const val PLACE_RING_STROKE_DP = 2.4f

/** Vida de una entrada de "jugada reciente" antes de retirarla (ms); cubre pop y anillo. */
private const val PLACE_FX_CLEANUP_MS = 600L

/** Alfa del tinte de fila/columna/bloque del cursor. */
private const val PEER_ALPHA = 0.09f

/** Alfa del relleno de la ficha de las celdas con el mismo número que la seleccionada. */
private const val TWIN_ALPHA = 0.22f

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

/** Periodo del parpadeo del halo de choque (s). Lento: §9.4 pide bucles
 *  ambientales de baja amplitud (~1.2–2 s). */
private const val CONFLICT_BLINK_SEC = 1.8f

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
 * destello simultáneo. Mismo parámetro que la limpieza de Bloques Neón.
 */
private const val WAVE_STAGGER_SPAN = 0.5f

/** Alfa del pico del relleno de una celda durante la onda de compleción. */
private const val WAVE_FILL_ALPHA = 0.55f

/** Grosor del aro que enciende cada celda en la onda de compleción. */
private const val WAVE_STROKE_DP = 2.6f

/** Chispas que libera cada celda al completarse su unidad. */
private const val WAVE_SPARK_COUNT = 6
