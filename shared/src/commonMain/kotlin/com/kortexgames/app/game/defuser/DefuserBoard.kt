package com.kortexgames.app.game.defuser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
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
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.boardCascade
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonTile
import com.kortexgames.app.ui.components.rememberBoardClock
import kotlinx.coroutines.delay
import com.kortexgames.app.ui.components.softGlow
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
 * ## Relieve
 * El panel se lee por **altura**, no solo por color: una celda oculta es una tecla en relieve
 * (cara + repisa) que se hunde bajo el dedo, y una celda revelada es un hueco oscuro. Así "lo
 * que falta por tocar" y "lo ya despejado" se distinguen de un vistazo aunque el panel sea
 * denso, sin necesidad de un borde de neón por celda (§9.7). Todo descansa sobre la placa
 * compartida del kit de tableros ([drawNeonBoardPlate]).
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
        // La placa asoma [PlatePadding] alrededor de la rejilla: ese marco se descuenta del
        // espacio disponible antes de repartir las celdas.
        val platePad = with(density) { PlatePadding.toPx() }
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        val maxCellPx = with(density) { BoardMaxCellSize.toPx() }
        val board = state.board

        val cell = min(
            min((availW - platePad * 2f) / board.columns, (availH - platePad * 2f) / board.rows),
            maxCellPx,
        )
        val originX = (availW - cell * board.columns) / 2f
        val originY = (availH - cell * board.rows) / 2f

        // --- Reloj de ambiente ---------------------------------------------------
        // Solo corre mientras hay algo que animar con él (la entrada en cascada o el pulso
        // del escáner). El resto de la partida el panel es estático y no debe redibujarse
        // a 60 fps para nada: es un juego de pensar con la pantalla quieta.
        var introActive by remember { mutableStateOf(true) }
        val clock = rememberBoardClock(running = introActive || state.scanning)
        var introAt by remember { mutableFloatStateOf(0f) }
        // "Panel intacto" = partida nueva (también tras reiniciar o cambiar de dificultad):
        // es cuando las teclas entran en cascada. Al retomar una partida guardada el panel
        // no está intacto, pero la primera composición también la dispara: es la entrada
        // a la pantalla.
        val untouched = board.cells.none { it.state != MineCellState.HIDDEN }
        val firstEntry = remember { booleanArrayOf(true) }
        LaunchedEffect(untouched, board.columns, board.rows) {
            // El primer toque también cambia `untouched` (a false): eso NO es una entrada.
            if (!untouched && !firstEntry[0]) return@LaunchedEffect
            firstEntry[0] = false
            introAt = clock.peek()
            introActive = true
            delay(introDurationMs(board.rows, board.columns))
            introActive = false
        }

        // Celda bajo el dedo mientras dura la pulsación: su tecla se hunde (§9.4, feedback
        // táctil inmediato, antes incluso de saber si será tap o pulsación larga).
        var pressed by remember { mutableStateOf<CellPosition?>(null) }

        // Tamaño del icono de escudo, derivado del lado de celda para que crezca y
        // encoja con el panel.
        val flagSizeDp = with(density) { (cell * FLAG_ICON_FRACTION).toDp() }

        // Geometría vigente para el hit-test. Va en un holder actualizado por
        // composición (`rememberUpdatedState`) y NO capturada por el closure del
        // gesto: el bloque de `pointerInput` solo se reejecuta si cambia su clave,
        // así que si leyera `originX/originY/cell` directamente se quedaría con los
        // valores del momento en que arrancó. Eso desplazaba el toque respecto a lo
        // dibujado en cuanto cambiaba el alto disponible sin cambiar el lado de
        // celda —`cell` está topado por [BoardMaxCellSize] y suele mandarlo el
        // ancho, pero `originY` depende del alto—, p. ej. al aparecer/desaparecer el
        // botón del escáner o su banner, o al ocultarse las barras del sistema.
        val geometry by rememberUpdatedState(BoardGeometry(originX, originY, cell, board))
        val currentOnReveal by rememberUpdatedState(onReveal)
        val currentOnFlag by rememberUpdatedState(onFlag)

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Clave fija: el detector no necesita reiniciarse nunca, porque
                    // lee la geometría vigente de `geometry` en cada toque.
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { pos ->
                                pressed = geometry.positionAt(pos)
                                tryAwaitRelease()
                                pressed = null
                            },
                            onTap = { pos ->
                                geometry.positionAt(pos)?.let { currentOnReveal(it) }
                            },
                            onLongPress = { pos ->
                                geometry.positionAt(pos)?.let { currentOnFlag(it) }
                            },
                        )
                    },
            ) {
                // Placa: la rejilla más su marco. `inset` recoloca y redimensiona el
                // DrawScope para que la función del kit (que pinta a tamaño completo) caiga
                // justo alrededor del panel.
                inset(
                    left = originX - platePad,
                    top = originY - platePad,
                    right = size.width - (originX + cell * board.columns) - platePad,
                    bottom = size.height - (originY + cell * board.rows) - platePad,
                ) {
                    drawNeonBoardPlate(accent = CategoryPalette.Attention, corner = PlateCorner)
                }

                // Lecturas del reloj condicionadas: si no hay nada que animar, el dibujo no
                // se suscribe y el panel no se invalida por frame.
                val introElapsed = if (introActive) clock.seconds - introAt else Float.MAX_VALUE
                val scanTime = if (state.scanning) clock.seconds else -1f

                board.cells.forEach { mineCell ->
                    val position = mineCell.position
                    val entry = if (introActive) boardCascade(position.row, position.col, introElapsed) else 1f
                    if (entry <= 0f) return@forEach
                    val topLeft = Offset(
                        x = originX + position.col * cell,
                        y = originY + position.row * cell,
                    )
                    val paint: DrawScope.() -> Unit = {
                        drawCell(
                            cell = mineCell,
                            topLeft = topLeft,
                            side = cell,
                            revealAlpha = revealAlphaFor(mineCell),
                            detonateProgress = detonateProgress,
                            measurer = measurer,
                            pressed = pressed == position,
                            scanPulse = if (scanTime < 0f) 0f else scanPulse(position, scanTime),
                        )
                    }
                    if (entry == 1f) {
                        paint()
                    } else {
                        scale(entry, entry, pivot = topLeft + Offset(cell / 2f, cell / 2f)) { paint() }
                    }
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
            // Tamaño FIJO igual al del icono: quien nos coloca resta medio icono para
            // centrarnos en la celda. Sin fijarlo, la caja heredaba el tamaño del halo de
            // [NeonIcon] (1,9× el icono) y el escudo quedaba corrido abajo a la derecha.
            .size(sizeDp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .softGlow(color = LogicColors.Violet, durationMillis = FLAG_GLOW_MS),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.Shield,
            tint = LogicColors.Violet,
            // `unbounded`: el halo puede desbordar la caja sin agrandarla ni descentrarla.
            modifier = Modifier.wrapContentSize(unbounded = true),
            size = sizeDp,
            glow = true,
            contentDescription = "Celda marcada con escudo",
        )
    }
}

/**
 * Geometría con la que se pinta el panel en el frame actual: dónde empieza la
 * rejilla dentro del `Canvas` y cuánto mide cada celda.
 *
 * Existe como tipo propio para poder pasar el **conjunto** al detector de gestos de
 * una sola vez y garantizar que dibujo y hit-test comparten exactamente los mismos
 * valores (ver el comentario de `pointerInput` en [DefuserBoard]).
 *
 * @property originX x de la esquina superior izquierda del panel, ya centrado.
 * @property originY y de la esquina superior izquierda del panel, ya centrado.
 * @property cell lado de celda en píxeles.
 * @property board panel vigente, para validar los límites de la coordenada.
 */
private data class BoardGeometry(
    val originX: Float,
    val originY: Float,
    val cell: Float,
    val board: MineBoard,
) {
    /**
     * Celda bajo el punto [pos], o `null` si el toque cayó en el margen exterior del
     * panel. Invierte la geometría de la rejilla (ver KDoc del archivo).
     */
    fun positionAt(pos: Offset): CellPosition? {
        // toInt() trunca hacia cero, así que un toque a la izquierda/arriba del
        // origen daría 0 en vez de negativo: se comprueban los dos márgenes antes.
        if (pos.x < originX || pos.y < originY) return null
        val position = CellPosition(
            row = ((pos.y - originY) / cell).toInt(),
            col = ((pos.x - originX) / cell).toInt(),
        )
        return if (board.contains(position)) position else null
    }
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
 * @param pressed el dedo está sobre esta celda: si está oculta, su tecla se dibuja hundida.
 * @param scanPulse 0..1: encendido del borde de una celda oculta en modo escáner (son las
 *   elegibles); 0 fuera de ese modo.
 *
 * `internal` y no privada porque también la usa el tutorial animado (`DefuserTutorial`), para
 * que su mini-panel sea exactamente el de la partida.
 */
internal fun DrawScope.drawCell(
    cell: MineCell,
    topLeft: Offset,
    side: Float,
    revealAlpha: Float,
    detonateProgress: Float,
    measurer: TextMeasurer,
    pressed: Boolean,
    scanPulse: Float,
) {
    when (cell.state) {
        MineCellState.HIDDEN -> drawHiddenTile(topLeft, side, pressed = pressed, scanPulse = scanPulse)

        // Flagged: el tile "blindado" con el tubo neón violeta. Se usa el componente
        // compartido drawNeonTile —con rectTopLeft/rectSize, pensados justo para
        // pintar muchas celdas en un Canvas común— para heredar la estética exacta
        // del resto de la app (§9.7). El icono de escudo lo superpone la capa de
        // composables.
        MineCellState.FLAGGED -> {
            // Misma tecla en relieve que una oculta, teñida de violeta: una celda marcada
            // sigue SIN tocar, solo que blindada.
            drawHiddenTile(topLeft, side, pressed = pressed, scanPulse = 0f, tint = LogicColors.Violet)
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

/**
 * Celda oculta: una **tecla en relieve**. Repisa inferior (la "altura"), cara con degradado y
 * una línea de brillo arriba; al pulsarla la cara baja sobre la repisa y se enciende.
 *
 * Sin tubo neón pleno por celda: §9.7 advierte que un borde intenso repetido sobre un panel
 * denso compite con el contenido. El relieve hace el trabajo y el acento queda en un filo fino.
 *
 * @param pressed el dedo está encima: cara hundida y filo encendido.
 * @param scanPulse 0..1: pulso del filo en modo escáner.
 * @param tint color del filo y del baño de la cara (el acento del juego, o violeta si la
 *   celda lleva escudo).
 */
private fun DrawScope.drawHiddenTile(
    topLeft: Offset,
    side: Float,
    pressed: Boolean = false,
    scanPulse: Float = 0f,
    tint: Color = CategoryPalette.Attention,
) {
    val margin = TileMargin.toPx()
    // La repisa escala con la celda: en el panel grande (celdas pequeñas) no debe comerse la cara.
    val ledge = side * LEDGE_FRACTION
    val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())
    val faceSize = Size(side - margin * 2f, side - margin * 2f - ledge)
    val base = Offset(topLeft.x + margin, topLeft.y + margin)

    drawRoundRect(
        color = lerp(LogicColors.BackgroundDark, tint, 0.24f),
        topLeft = Offset(base.x, base.y + ledge),
        size = faceSize,
        cornerRadius = corner,
    )
    val faceTop = if (pressed) Offset(base.x, base.y + ledge) else base
    val lit = if (pressed) 0.30f else 0.10f + 0.14f * scanPulse
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(lerp(LogicColors.SurfaceVariantDark, Color.White, 0.10f), tint, lit),
                lerp(LogicColors.SurfaceVariantDark, tint, lit * 0.5f),
            ),
            startY = faceTop.y,
            endY = faceTop.y + faceSize.height,
        ),
        topLeft = faceTop,
        size = faceSize,
        cornerRadius = corner,
    )
    // Brillo del canto superior: lo que hace que la cara se lea abombada y no plana.
    if (!pressed) {
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = Offset(faceTop.x + corner.x, faceTop.y + 1.dp.toPx()),
            end = Offset(faceTop.x + faceSize.width - corner.x, faceTop.y + 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    drawRoundRect(
        color = tint.copy(alpha = if (pressed) 0.9f else HIDDEN_BORDER_ALPHA + 0.55f * scanPulse),
        topLeft = faceTop,
        size = faceSize,
        cornerRadius = corner,
        style = Stroke(width = BORDER_DP.dp.toPx()),
    )
}

/**
 * Pulso de una celda oculta en **modo escáner**: una onda diagonal que recorre el panel, de
 * modo que las celdas elegibles laten por turnos en vez de todas a la vez (un panel entero
 * parpadeando al unísono cansa; la onda guía la vista y se lee como un barrido de radar).
 */
private fun scanPulse(position: CellPosition, time: Float): Float {
    val phase = time * 2f * PI.toFloat() / SCAN_PULSE_SEC - (position.row + position.col) * SCAN_WAVE_STEP
    return 0.5f + 0.5f * sin(phase)
}

/**
 * Cuánto dura la entrada en cascada de un panel de [rows] × [columns] (ms): la última diagonal
 * arranca tras `(rows + columns)` pasos de `boardCascade` (35 ms) y tarda 300 ms en asentarse.
 * Pasado ese tiempo el panel deja de leer el reloj.
 */
private fun introDurationMs(rows: Int, columns: Int): Long = (rows + columns) * 35L + 400L

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
    // Sombra del canto superior: el hueco queda POR DEBAJO de las teclas vecinas.
    val margin = TileMargin.toPx()
    drawLine(
        color = Color.Black.copy(alpha = 0.40f * a),
        start = Offset(topLeft.x + margin + CellCorner.toPx(), topLeft.y + margin + 1.dp.toPx()),
        end = Offset(topLeft.x + side - margin - CellCorner.toPx(), topLeft.y + margin + 1.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )

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
        // Halo del color de peligro bajo la cifra: el número "emite" sobre el hueco oscuro
        // y el riesgo se lee por color incluso de reojo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(dangerColor(cell.adjacentMines).copy(alpha = DIGIT_GLOW_ALPHA * a), Color.Transparent),
                center = center,
                radius = side * 0.46f,
            ),
            radius = side * 0.46f,
            center = center,
        )
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

/** Marco de la placa alrededor de la rejilla. */
private val PlatePadding = 8.dp

/** Radio de esquina de la placa. Menor que el de otros tableros: las celdas son pequeñas y
 *  un radio de 24 dp dejaría las de las esquinas asomando fuera de la curva. */
private val PlateCorner = 14.dp

/** Altura de la repisa de una tecla oculta, como fracción del lado de celda. */
private const val LEDGE_FRACTION = 0.085f

/** Opacidad del halo de color bajo el número de peligro. */
private const val DIGIT_GLOW_ALPHA = 0.20f

/** Periodo del pulso de las celdas elegibles en modo escáner (s). */
private const val SCAN_PULSE_SEC = 1.6f

/** Desfase del pulso del escáner entre una diagonal y la siguiente (rad). */
private const val SCAN_WAVE_STEP = 0.55f

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
