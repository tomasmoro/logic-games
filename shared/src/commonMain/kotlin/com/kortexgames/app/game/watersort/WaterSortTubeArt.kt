package com.kortexgames.app.game.watersort

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * # Arte del frasco de "Ordena las Pociones"
 *
 * Todo el dibujo del tubo de ensayo (vidrio, líquido, burbujas, corcho, chorro)
 * vive aquí, separado de la pantalla, para que `WaterSortScreen` solo orqueste
 * estado y animaciones. Son funciones puras de `DrawScope`: reciben el instante
 * ([time]) y los factores de animación ya resueltos, y no guardan estado — así un
 * mismo reloj compartido redibuja todos los frascos sin recomponer nada.
 *
 * Decisiones de diseño (CLAUDE.md §9.1 "Lógica + Juego"):
 *  - El **líquido es el neón**: degradado vertical + superficie ondulada + burbujas
 *    lo hacen leerse como poción viva, no como bloques de color planos.
 *  - El **vidrio es oscuro y discreto** (brillos especulares finos, borde tenue):
 *    cede el protagonismo al color. Solo se "enciende" con borde neón al
 *    seleccionar, verter o completar (mismas capas que `drawNeonTile`, §9.7: el
 *    contorno en U no es un tile, así que se replica su proporción de capas).
 */

/**
 * Paleta neón de los colores de poción. El índice de color del modelo (0..n-1)
 * indexa esta lista, de modo que la lógica pura sigue sin conocer `Color`. Tonos
 * bien separados en el círculo cromático para distinguirlos de un vistazo (todos
 * salen de [LogicColors], nunca hardcodeados sueltos — CLAUDE.md §9.2).
 */
internal val PotionColors: List<Color> = listOf(
    LogicColors.Violet,
    LogicColors.NeonCyan,
    LogicColors.NeonGreen,
    LogicColors.Magenta,
    LogicColors.Amber,
    LogicColors.Coral,
    LogicColors.Blue,
    LogicColors.Lime,
)

/** Relación de aspecto del tubo (ancho/alto). Esbelto → se lee como tubo de ensayo. */
internal const val TubeAspect = 0.36f

/**
 * Una banda de líquido a dibujar, de abajo hacia arriba. [heightSlots] es la
 * altura en "slots" (1.0 = un segmento completo); permite alturas **fraccionarias**
 * para animar el trasvase (una banda que crece/mengua mientras se vierte).
 */
internal data class LiquidBand(val colorIndex: Int, val heightSlots: Float)

// --- Medidas del frasco (dp) --------------------------------------------------
private val RimHeight = 7.dp          // alto del labio de la boca
private val RimOverhang = 2.5.dp      // cuánto sobresale el labio a cada lado
private val GlassInset = 3.5.dp       // aire entre el vidrio y el líquido
private val MouthGap = 9.dp           // aire en la boca aunque el tubo esté lleno
private val BandCorner = 5.dp         // redondeo de cada banda (aspecto de gota)

/**
 * Separación vertical (aire de vidrio) entre bandas de líquido: deja ver el fondo
 * entre colores para que cada unidad se lea por separado — además de estética, es
 * información de juego (cuántas unidades hay de cada color).
 */
private val BandSeparation = 3.5.dp

/**
 * Cuánto se inclina la superficie del líquido respecto a la inclinación real del
 * frasco (1 = horizontal perfecta en el mundo). Algo menos de 1 porque, con la
 * separación entre bandas, una horizontal exacta a 45° deformaba demasiado las
 * unidades y dejaba de leerse cuántas quedan.
 */
private const val SURFACE_LEVELLING = 0.8f

/**
 * Geometría de la columna de líquido de un frasco de alto [tubeHeightPx]: dónde
 * empieza (fondo) y cuánto mide cada slot. La comparte el dibujo del frasco y el
 * chorro (que necesita saber a qué altura está la superficie del destino para
 * "caer" justo ahí).
 *
 * @return par (distancia del fondo del líquido al borde inferior del frasco, alto de un slot).
 */
internal fun Density.liquidMetrics(tubeHeightPx: Float, capacity: Int): Pair<Float, Float> {
    val bottomInset = GlassInset.toPx()
    val top = RimHeight.toPx() * 0.55f + MouthGap.toPx()
    val slot = (tubeHeightPx - bottomInset - top) / capacity.coerceAtLeast(1)
    return bottomInset to slot
}

/** Pseudoaleatorio determinista 0..1 (sin estado): mismo [n] → mismo valor en cada frame. */
internal fun hash01(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - kotlin.math.floor(s)
}

private fun fract(v: Float): Float = v - kotlin.math.floor(v)

/**
 * Pinta un frasco completo en el `DrawScope` actual (que debe medir exactamente el
 * frasco; el halo, el corcho y el labio se salen de esos límites a propósito, el
 * `Canvas` de Compose no recorta).
 *
 * @param bands bandas de líquido de abajo a arriba (la última puede ser fraccionaria).
 * @param time reloj de animación en segundos (olas y burbujas).
 * @param seed semilla estable del frasco (su índice): desfasa olas y burbujas para
 *   que dos frascos vecinos no se muevan sincronizados, que delataría el truco.
 * @param tiltDegrees inclinación actual del frasco; el líquido la compensa para
 *   mantener su superficie (casi) horizontal, como un líquido real.
 * @param agitation 0 = líquido en calma, 1 = muy agitado (recién vertido/levantado).
 * @param edgeColor color del borde neón, o null para el vidrio en reposo.
 * @param edgeAmt intensidad 0..1 del borde neón.
 * @param auraAmt intensidad 0..1 del halo exterior de [edgeColor] (frasco completado).
 * @param cork 0 = sin corcho, 1 = corcho asentado; puede pasarse de 1 (rebote del resorte).
 * @param shine progreso 0..1 del barrido de brillo de celebración; fuera de (0,1) no se pinta.
 */
internal fun DrawScope.drawPotionTube(
    bands: List<LiquidBand>,
    capacity: Int,
    time: Float,
    seed: Int,
    tiltDegrees: Float = 0f,
    agitation: Float = 0f,
    edgeColor: Color? = null,
    edgeAmt: Float = 0f,
    auraAmt: Float = 0f,
    cork: Float = 0f,
    shine: Float = 1f,
) {
    val w = size.width
    val h = size.height
    val rimH = RimHeight.toPx()
    val bodyTop = rimH * 0.55f
    val inset = GlassInset.toPx()

    val body = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f, top = bodyTop, right = w, bottom = h,
                topLeftCornerRadius = CornerRadius(3.dp.toPx()),
                topRightCornerRadius = CornerRadius(3.dp.toPx()),
                bottomLeftCornerRadius = CornerRadius(w / 2f),
                bottomRightCornerRadius = CornerRadius(w / 2f),
            ),
        )
    }
    // Silueta interior: el líquido se recorta contra ella para guardar un margen
    // uniforme con el vidrio también en la base en U (antes tocaba el cristal).
    val inner = Path().apply {
        addRoundRect(
            RoundRect(
                left = inset, top = bodyTop, right = w - inset, bottom = h - inset,
                topLeftCornerRadius = CornerRadius(2.dp.toPx()),
                topRightCornerRadius = CornerRadius(2.dp.toPx()),
                bottomLeftCornerRadius = CornerRadius(w / 2f - inset),
                bottomRightCornerRadius = CornerRadius(w / 2f - inset),
            ),
        )
    }

    // 1) Halo exterior del frasco completado: elipse radial alta, por detrás de todo.
    if (edgeColor != null && auraAmt > 0f) {
        scale(scaleX = 0.62f, scaleY = 1f, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(edgeColor.copy(alpha = 0.34f * auraAmt), Color.Transparent),
                    center = center,
                    radius = h * 0.78f,
                ),
                radius = h * 0.78f,
                center = center,
            )
        }
    }

    // 2) Vidrio: fondo oscuro translúcido (deja intuir el muro de detrás).
    drawPath(body, LogicColors.SurfaceDark.copy(alpha = 0.82f))
    drawPath(
        body,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), startY = bodyTop, endY = h),
    )

    // 3) Líquido.
    clipPath(inner) {
        drawLiquid(bands, capacity, time, seed, tiltDegrees, agitation)
        // Sombreado cilíndrico: oscurece los cantos para dar volumen al tubo.
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.20f),
                0.28f to Color.Transparent,
                0.70f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.30f),
                startX = inset,
                endX = w - inset,
            ),
        )
    }

    // 4) Brillos especulares del vidrio (van sobre el líquido).
    clipPath(body) {
        val streakTop = bodyTop + 9.dp.toPx()
        val streakBottom = h - w * 0.62f
        if (streakBottom > streakTop) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.12f to Color.White.copy(alpha = 0.30f),
                    0.75f to Color.White.copy(alpha = 0.14f),
                    1f to Color.Transparent,
                    startY = streakTop,
                    endY = streakBottom,
                ),
                topLeft = Offset(w * 0.17f, streakTop),
                size = Size(w * 0.09f, streakBottom - streakTop),
                cornerRadius = CornerRadius(w * 0.05f),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(w * 0.84f, streakTop + 6.dp.toPx()),
                end = Offset(w * 0.84f, streakBottom),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // Destello curvo en la panza de la base.
        drawArc(
            color = Color.White.copy(alpha = 0.20f),
            startAngle = 115f,
            sweepAngle = 45f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h - w * 0.86f),
            size = Size(w * 0.72f, w * 0.72f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
        )

        // Barrido de brillo de celebración: banda diagonal que sube por el frasco.
        if (shine > 0f && shine < 1f) {
            val bandH = w * 0.9f
            val y = h + bandH - (h + bandH * 2f) * shine
            rotate(degrees = -24f, pivot = Offset(w / 2f, y)) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        startY = y - bandH / 2f,
                        endY = y + bandH / 2f,
                    ),
                    topLeft = Offset(-w, y - bandH / 2f),
                    size = Size(w * 3f, bandH),
                )
            }
        }
    }

    // 5) Borde: vidrio tenue en reposo; tubo de neón por capas al encenderse.
    val glassEdge = lerp(LogicColors.SurfaceVariantDark, Color.White, 0.22f)
    val amt = if (edgeColor != null) edgeAmt.coerceIn(0f, 1f) else 0f
    val edge = if (edgeColor != null) lerp(glassEdge, edgeColor, amt) else glassEdge
    val stroke = (1.6.dp + 1.0.dp * amt).toPx()
    if (amt > 0f) {
        drawPath(body, edge.copy(alpha = 0.26f * amt), style = Stroke(stroke * 4.5f))
        drawPath(body, edge.copy(alpha = 0.50f * amt), style = Stroke(stroke * 2.1f))
    }
    drawPath(body, edge, style = Stroke(stroke))
    if (amt > 0f) {
        drawPath(body, Color.White.copy(alpha = 0.55f * amt), style = Stroke(stroke * 0.42f))
    }

    // 6) Corcho (frasco completado): cae desde arriba y se asienta en la boca.
    if (cork > 0f) drawCork(cork, bodyTop)

    // 7) Labio de la boca: anillo algo más ancho que el cuerpo (encima del corcho,
    //    para que el tapón se vea "metido" en el frasco).
    val over = RimOverhang.toPx()
    val rimTopLeft = Offset(-over, 0f)
    val rimSize = Size(w + over * 2f, rimH)
    val rimCorner = CornerRadius(rimH / 2f)
    drawRoundRect(lerp(LogicColors.SurfaceDark, LogicColors.SurfaceVariantDark, 0.7f), rimTopLeft, rimSize, rimCorner)
    if (amt > 0f) {
        drawRoundRect(edge.copy(alpha = 0.35f * amt), rimTopLeft, rimSize, rimCorner, style = Stroke(stroke * 2.4f))
    }
    drawRoundRect(edge, rimTopLeft, rimSize, rimCorner, style = Stroke(stroke))
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(w * 0.12f, rimH * 0.36f),
        end = Offset(w * 0.42f, rimH * 0.36f),
        strokeWidth = 1.2.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/**
 * Columna de líquido: bandas con degradado, superficie ondulada en la de arriba y
 * burbujas que suben. Se llama ya recortado contra la silueta interior del vidrio.
 */
private fun DrawScope.drawLiquid(
    bands: List<LiquidBand>,
    capacity: Int,
    time: Float,
    seed: Int,
    tiltDegrees: Float,
    agitation: Float,
) {
    val w = size.width
    val h = size.height
    val inset = GlassInset.toPx()
    val (bottomInset, slotH) = liquidMetrics(h, capacity)
    val left = inset
    val right = w - inset
    val cx = w / 2f
    val sep = BandSeparation.toPx()
    val topIndex = bands.indexOfLast { it.heightSlots > 0f }
    if (topIndex < 0) return

    // Pendiente de la superficie en coordenadas del frasco: compensa el giro para
    // que el líquido se "acueste" hacia el lado del pico al servir.
    val slope = -tan(tiltDegrees / 180f * PI.toFloat()) * SURFACE_LEVELLING
    val phase = seed * 1.7f

    var yBottom = h - bottomInset
    val columnBottom = yBottom
    bands.forEachIndexed { index, band ->
        val bandH = band.heightSlots * slotH
        if (bandH <= 0f) return@forEachIndexed
        val isBottom = index == 0
        val isTop = index == topIndex
        val yTop = yBottom - bandH + min(sep, bandH * 0.5f)
        val yBot = yBottom
        val drawH = yBot - yTop
        val r = min(BandCorner.toPx(), drawH * 0.45f)

        // Ola solo en la superficie libre (la banda superior); las interfaces entre
        // colores quedan rectas, como líquidos de distinta densidad en reposo.
        val amp = if (isTop) min((0.8.dp + 2.8.dp * agitation).toPx(), drawH * 0.4f) else 0f
        val topAt: (Float) -> Float = { x ->
            val u = (x - left) / (right - left)
            yTop + slope * (x - cx) +
                amp * sin(u * 6.9f + time * 3.4f + phase) +
                amp * 0.4f * sin(u * 14.5f - time * 4.6f + phase)
        }
        val botAt: (Float) -> Float = { x -> yBot + slope * (x - cx) }

        val color = PotionColors[band.colorIndex]
        val path = Path()
        val surface = Path()
        val steps = 10
        path.moveTo(left, topAt(left) + r)
        path.quadraticTo(left, topAt(left), left + r, topAt(left + r))
        surface.moveTo(left + r, topAt(left + r))
        for (k in 1..steps) {
            val x = left + r + (right - left - 2f * r) * k / steps
            path.lineTo(x, topAt(x))
            surface.lineTo(x, topAt(x))
        }
        path.quadraticTo(right, topAt(right), right, topAt(right) + r)
        if (isBottom) {
            // La banda del fondo se hunde bajo el frasco: el recorte contra el vidrio
            // la redondea EXACTAMENTE igual que la base en U.
            path.lineTo(right, h + w)
            path.lineTo(left, h + w)
        } else {
            path.lineTo(right, botAt(right) - r)
            path.quadraticTo(right, botAt(right), right - r, botAt(right - r))
            path.lineTo(left + r, botAt(left + r))
            path.quadraticTo(left, botAt(left), left, botAt(left) - r)
        }
        path.close()

        val gradTop = yTop - abs(slope) * w / 2f
        val gradBottom = if (isBottom) h else yBot + abs(slope) * w / 2f
        drawPath(
            path,
            Brush.verticalGradient(
                0f to lerp(color, Color.White, 0.26f),
                0.35f to color,
                1f to lerp(color, LogicColors.BackgroundDark, 0.34f),
                startY = gradTop,
                endY = gradBottom,
            ),
        )
        // Menisco: filo claro en la superficie de cada banda (más vivo en la libre).
        drawPath(
            surface,
            Color.White.copy(alpha = if (isTop) 0.55f else 0.26f),
            style = Stroke(width = (if (isTop) 1.6.dp else 1.1.dp).toPx(), cap = StrokeCap.Round),
        )
        yBottom -= bandH
    }

    // Burbujas: suben por la columna y se desvanecen al llegar a la superficie. Se
    // apagan al inclinar el frasco (subirían "torcidas" respecto al mundo).
    val columnH = columnBottom - yBottom
    val calm = (1f - abs(tiltDegrees) / 20f).coerceIn(0f, 1f)
    if (columnH > slotH * 0.6f && calm > 0f) {
        val travel = columnH - w * 0.25f
        for (j in 0 until BUBBLES_PER_TUBE) {
            val k = seed * 31 + j * 17
            val speed = 0.09f + 0.13f * hash01(k)
            val f = fract(time * speed + hash01(k + 1))
            val x = left + (0.2f + 0.6f * hash01(k + 2)) * (right - left) + sin(time * 2.1f + j) * 1.6.dp.toPx()
            val y = columnBottom - w * 0.2f - f * travel
            val a = sin(f * PI.toFloat()) * calm
            val radius = (1.1.dp + 1.5.dp * hash01(k + 3)).toPx() * (0.7f + 0.3f * f)
            drawCircle(Color.White.copy(alpha = 0.14f * a), radius, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.50f * a), radius, Offset(x, y), style = Stroke(0.9.dp.toPx()))
        }
    }
}

private const val BUBBLES_PER_TUBE = 5

/**
 * Corcho del frasco completado. [amount] 0→1 lo baja desde arriba hasta encajarlo;
 * por encima de 1 (rebote del resorte) se hunde un poco más, que es lo que le da
 * el "pop" de taponar.
 */
private fun DrawScope.drawCork(amount: Float, bodyTop: Float) {
    val w = size.width
    val cx = w / 2f
    val corkH = 21.dp.toPx()
    // Asentado, medio corcho asoma por encima del labio: tiene que leerse como
    // tapón a tamaño de móvil, no como una rayita sobre la boca.
    val rest = bodyTop - corkH * 0.72f
    val y = rest - (1f - amount) * 38.dp.toPx()
    val alpha = amount.coerceIn(0f, 1f)
    // Tonos "madera" derivados de la paleta (ámbar/coral), no hardcodeados (§9.2).
    val light = lerp(LogicColors.Amber, LogicColors.Coral, 0.30f)
    val dark = lerp(light, LogicColors.BackgroundDark, 0.50f)
    val capH = corkH * 0.56f

    // Cuña que entra en el frasco (más estrecha abajo).
    val plug = Path().apply {
        moveTo(cx - w * 0.33f, y + capH * 0.6f)
        lineTo(cx + w * 0.33f, y + capH * 0.6f)
        lineTo(cx + w * 0.27f, y + corkH)
        lineTo(cx - w * 0.27f, y + corkH)
        close()
    }
    drawPath(plug, Brush.verticalGradient(listOf(light, dark), startY = y, endY = y + corkH), alpha = alpha)
    // Cabeza del corcho, con su brillo: la parte que se ve sobre el labio.
    val capLeft = cx - w * 0.39f
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(lerp(light, Color.White, 0.30f), light, dark), startY = y, endY = y + capH),
        topLeft = Offset(capLeft, y),
        size = Size(w * 0.78f, capH),
        cornerRadius = CornerRadius(4.dp.toPx()),
        alpha = alpha,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.55f * alpha),
        start = Offset(capLeft + w * 0.10f, y + capH * 0.30f),
        end = Offset(capLeft + w * 0.34f, y + capH * 0.30f),
        strokeWidth = 1.4.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/**
 * Chorro de poción cayendo desde el pico ([lip]) hasta la superficie del líquido
 * del destino ([impact]). El tramo visible lo marca [pourFactor] (0→1 = duración del
 * vertido): la cabeza cae rápido, el chorro se mantiene y la cola se suelta al
 * final. Mismas capas que el borde neón (§9.7) + salpicaduras en el impacto.
 */
internal fun DrawScope.drawPourStream(
    lip: Offset,
    impact: Offset,
    color: Color,
    pourFactor: Float,
    time: Float,
) {
    if (pourFactor <= 0f || pourFactor >= 1f) return

    // Sale del pico casi horizontal y la gravedad lo curva hacia abajo.
    val path = Path().apply {
        moveTo(lip.x, lip.y)
        quadraticTo(impact.x + sin(time * 9f) * 1.dp.toPx(), lip.y + (impact.y - lip.y) * 0.12f, impact.x, impact.y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val head = (pourFactor / STREAM_HEAD_END).coerceIn(0f, 1f) * length
    val tail = ((pourFactor - STREAM_TAIL_START) / (1f - STREAM_TAIL_START)).coerceIn(0f, 1f) * length
    if (head - tail <= 1f) return

    val segment = Path()
    measure.getSegment(tail, head, segment, true)
    // El chorro "late" un poco de grosor: evita que parezca una barra rígida.
    val width = 5.dp.toPx() * (0.9f + 0.1f * sin(time * 21f))
    drawPath(segment, color.copy(alpha = 0.22f), style = Stroke(width * 3.4f, cap = StrokeCap.Round))
    drawPath(segment, color.copy(alpha = 0.50f), style = Stroke(width * 1.9f, cap = StrokeCap.Round))
    drawPath(segment, color, style = Stroke(width, cap = StrokeCap.Round))
    drawPath(segment, Color.White.copy(alpha = 0.60f), style = Stroke(width * 0.34f, cap = StrokeCap.Round))

    // Impacto: resplandor + gotas que saltan en parábola mientras el chorro toca.
    if (head >= length - 1f) {
        val fade = 1f - (tail / length)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(lerp(color, Color.White, 0.5f).copy(alpha = 0.55f * fade), Color.Transparent),
                center = impact,
                radius = 11.dp.toPx(),
            ),
            radius = 11.dp.toPx(),
            center = impact,
        )
        for (j in 0 until 6) {
            val f = fract(time * 2.4f + j / 6f)
            val side = (hash01(j * 7 + 1) - 0.5f) * 2f
            val x = impact.x + side * 11.dp.toPx() * f
            val y = impact.y - (7.dp + 9.dp * hash01(j * 7 + 2)).toPx() * 4f * f * (1f - f)
            drawCircle(
                color = lerp(color, Color.White, 0.35f).copy(alpha = (1f - f) * fade),
                radius = (1.9.dp.toPx()) * (1f - 0.6f * f),
                center = Offset(x, y),
            )
        }
    }
}

private const val STREAM_HEAD_END = 0.12f    // fracción del vertido en que la cabeza llega al líquido
private const val STREAM_TAIL_START = 0.90f  // fracción en que la cola se suelta del pico

/**
 * Reflejo de color que el frasco proyecta sobre la repisa: una elipse radial del
 * color del líquido del fondo. Se dibuja FUERA de la capa que se mueve con el
 * frasco, para que al levantarlo el reflejo se quede en la repisa y se apague.
 *
 * @param tube rectángulo del frasco en el `DrawScope` actual.
 * @param amount 0..1 (0 = frasco en el aire, sin reflejo).
 */
internal fun DrawScope.drawShelfReflection(tube: Rect, color: Color, amount: Float) {
    if (amount <= 0f) return
    val center = Offset(tube.center.x, tube.bottom + 6.dp.toPx())
    val radius = tube.width * 0.62f
    scale(scaleX = 1f, scaleY = 0.20f, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.50f * amount), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}
