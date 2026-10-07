package com.kortexgames.app.ui.components

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.core.theme.LogicColors
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gameboard_hud_level
import kortexgames.shared.generated.resources.gameboard_hud_restart
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * # Kit de tablero neón
 *
 * Piezas de dibujo y de HUD compartidas por los juegos de **rejilla con cables de luz**: nació
 * con el rediseño de Línea Neón y está pensado para llevarse tal cual a Conectores y a Flujo de
 * Energía, que dibujan lo mismo con otras reglas (una placa, celdas, cables de neón, nodos).
 *
 * Antes cada uno de los tres tenía su propia copia de "fondo de placa + cable de cuatro capas +
 * píldoras de HUD"; con el kit, un ajuste de aspecto se hace una vez y los tres quedan iguales
 * (mismo criterio que `drawNeonTile`/`NeonFrame`, §9.7 de `CLAUDE.md`).
 *
 * Todo lo de `DrawScope` son funciones puras: reciben geometría, color y el reloj; no guardan
 * estado ni conocen las reglas de ningún juego.
 */

/**
 * Reloj de animación de un tablero, en segundos.
 *
 * Tiene dos lecturas a propósito:
 *  - [seconds] es estado observable: léelo **dentro del dibujo** (`Canvas`, `graphicsLayer`) para
 *    que avanzar el tiempo redibuje sin recomponer;
 *  - [peek] devuelve el mismo instante **sin suscribirse**: úsalo desde la composición o desde un
 *    callback para anotar "cuándo pasó algo" (p. ej. `remember(nivel) { clock.peek() }`). Leer
 *    [seconds] ahí recompondría la pantalla entera en cada frame.
 */
class BoardClock internal constructor() {
    private val state = mutableFloatStateOf(0f)
    private var now = 0f

    /** Instante actual, observable (para leer en la fase de dibujo). */
    val seconds: Float get() = state.floatValue

    /** Instante actual sin registrar lectura (para anotar momentos fuera del dibujo). */
    fun peek(): Float = now

    internal fun advance(dt: Float) {
        now += dt
        state.floatValue = now
    }
}

/**
 * Crea y hace avanzar el [BoardClock] de la pantalla.
 *
 * @param running si es false el reloj se congela (p. ej. con el juego en pausa).
 */
@Composable
fun rememberBoardClock(running: Boolean = true): BoardClock {
    val clock = remember { BoardClock() }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val nanos = withFrameNanos { it }
            // Tope por frame: al volver de segundo plano el tiempo no "salta".
            clock.advance(((nanos - last) / 1_000_000_000f).coerceAtMost(0.05f))
            last = nanos
        }
    }
    return clock
}

/** Pseudoaleatorio determinista 0..1 (sin estado): mismo [n] → mismo valor en cada frame. */
private fun kitHash(n: Int): Float {
    val s = sin(n * 12.9898f) * 43758.547f
    return s - floor(s)
}

/** Desfase de la entrada en cascada entre una diagonal del tablero y la siguiente. */
private const val CASCADE_STEP_SEC = 0.035f

/** Cuánto tarda cada celda en asentarse durante la entrada en cascada. */
private const val CASCADE_CELL_SEC = 0.30f

/**
 * Escala 0..1 (con un punto de rebote) de la celda ([row], [col]) durante la **entrada en
 * cascada** del tablero: las celdas aparecen por diagonales desde la esquina superior izquierda.
 *
 * @param elapsedSec segundos desde que se montó el nivel.
 */
fun boardCascade(row: Int, col: Int, elapsedSec: Float): Float {
    val local = (elapsedSec - (row + col) * CASCADE_STEP_SEC) / CASCADE_CELL_SEC
    return when {
        local <= 0f -> 0f
        local >= 1f -> 1f
        else -> EaseOutBack.transform(local)
    }
}

/**
 * La **placa** del tablero: superficie oscura con un baño del acento, viñeta y un marco fino.
 *
 * El marco es deliberadamente discreto y no un bezel de neón encendido: encima van celdas, cables
 * y nodos brillantes, y un marco intenso competiría con ellos (§9.7 lo dice explícitamente para
 * tableros con mucho contenido). Solo se **enciende** de verdad con [lit], reservado para el
 * momento de resolver el nivel.
 *
 * @param accent color del cromo del juego (marco y baño).
 * @param lit 0..1: encendido del marco (celebración de nivel resuelto).
 * @param litColor color al que vira el marco al encenderse (normalmente el del cable).
 */
fun DrawScope.drawNeonBoardPlate(accent: Color, lit: Float = 0f, litColor: Color = accent, corner: Dp = 24.dp) {
    val radius = CornerRadius(corner.toPx())
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                lerp(LogicColors.SurfaceDark, accent, 0.07f).copy(alpha = 0.90f),
                lerp(LogicColors.SurfaceDark, LogicColors.BackgroundDark, 0.45f).copy(alpha = 0.92f),
            ),
        ),
        cornerRadius = radius,
    )
    val edge = lerp(accent, litColor, lit)
    val amount = lit.coerceIn(0f, 1f)
    if (amount > 0f) {
        // Halo por FUERA del contorno, en capas finas que se solapan (sin escalones).
        val layers = 6
        for (i in 1..layers) {
            val w = 18.dp.toPx() * i / layers
            drawRoundRect(
                color = edge.copy(alpha = 0.05f * amount),
                topLeft = Offset(-w / 2f, -w / 2f),
                size = Size(size.width + w, size.height + w),
                cornerRadius = CornerRadius(radius.x + w / 2f),
                style = Stroke(width = w),
            )
        }
    }
    drawRoundRect(
        color = edge.copy(alpha = 0.30f + 0.65f * amount),
        cornerRadius = radius,
        style = Stroke(width = (1.5.dp + 1.dp * amount).toPx()),
    )
}

/**
 * Una pieza **maciza** de tablero ("gema"): cuerpo con degradado, franja de brillo en la cara
 * superior y el tubo de neón compartido ([drawNeonTile], §9.7) rematando el borde.
 *
 * Nació en Bloques Neón y la usa también Neon 2048: en ambos el tablero se llena de piezas, y
 * con solo el contorno hueco costaba distinguir de un vistazo lo ocupado de lo vacío. Vive aquí
 * para que las dos compartan proporciones y un ajuste de aspecto se haga una sola vez.
 *
 * @param topLeft esquina de la **celda** que ocupa la pieza.
 * @param cellPx lado de la celda en píxeles.
 * @param bodyFraction lado del cuerpo respecto a la celda. Menor que 1 deja aire para el halo
 *   cuando las celdas están pegadas; cerca de 1 si la rejilla ya separa las piezas.
 * @param faceShade 0..1: cuánto se oscurece la cara hacia el fondo. Con 0 la gema es de color
 *   pleno; súbelo si lleva texto claro encima (un número blanco no se lee sobre ámbar o lima).
 * @param glow 0..1: encendido del tubo del borde.
 */
fun DrawScope.drawNeonGem(
    topLeft: Offset,
    cellPx: Float,
    accent: Color,
    alpha: Float = 1f,
    scale: Float = 1f,
    bodyFraction: Float = 0.80f,
    faceShade: Float = 0f,
    glow: Float = 0.42f,
) {
    if (alpha <= 0f || scale <= 0f) return
    val side = cellPx * bodyFraction * scale
    val center = topLeft + Offset(cellPx / 2f, cellPx / 2f)
    val origin = Offset(center.x - side / 2f, center.y - side / 2f)
    val corner = CornerRadius(cellPx * 0.18f * scale)
    val face = lerp(accent, LogicColors.BackgroundDark, faceShade.coerceIn(0f, 1f))
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to lerp(face, Color.White, 0.32f * (1f - faceShade * 0.5f)),
            0.45f to face,
            1f to lerp(face, LogicColors.BackgroundDark, 0.48f),
            startY = origin.y,
            endY = origin.y + side,
        ),
        topLeft = origin,
        size = Size(side, side),
        cornerRadius = corner,
        alpha = alpha,
    )
    // Brillo de la cara superior: una franja clara que le da el "pulido" de gema.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.26f * (1f - faceShade * 0.45f) * alpha),
        topLeft = Offset(origin.x + side * 0.14f, origin.y + side * 0.12f),
        size = Size(side * 0.72f, side * 0.20f),
        cornerRadius = CornerRadius(side * 0.10f),
    )
    drawNeonTile(
        baseColor = accent,
        activeAmt = glow,
        cornerRadius = (cellPx * 0.22f).toDp(),
        sparks = false,
        // Mismo reparto que tenía Bloques Neón (cuerpo 0.80 → margen 0.07 de celda).
        baseMargin = (cellPx * (1f - bodyFraction) * 0.35f).toDp(),
        strokeScale = 0.7f,
        rectTopLeft = topLeft,
        rectSize = Size(cellPx, cellPx),
        alpha = alpha,
        scale = scale,
    )
}

/**
 * Una **celda** del tablero como zócalo: una baldosa hundida que se llena de luz al ocuparse.
 *
 * Sustituye a "líneas de rejilla + un punto": con baldosas el tablero se lee como piezas que hay
 * que encender, y ver cómo se van iluminando es parte de la recompensa.
 *
 * @param side lado de la baldosa en píxeles (ya con la escala de entrada aplicada).
 * @param fill 0 = vacía, 1 = encendida; puede pasarse de 1 en el rebote de su aparición.
 * @param color color de la luz de la celda encendida.
 * @param padAlpha opacidad del punto de contacto central de la celda vacía (0 = sin punto).
 */
fun DrawScope.drawBoardSocket(center: Offset, side: Float, fill: Float, color: Color, padAlpha: Float = 0.5f) {
    if (side <= 0f) return
    val corner = CornerRadius(side * 0.22f)
    val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
    val tile = Size(side, side)
    drawRoundRect(LogicColors.BackgroundDark.copy(alpha = 0.55f), topLeft, tile, corner)
    drawRoundRect(LogicColors.SurfaceVariantDark.copy(alpha = 0.70f), topLeft, tile, corner, style = Stroke(1.dp.toPx()))

    val lit = fill.coerceIn(0f, 1f)
    if (lit > 0f) {
        // La luz crece desde el centro con el mismo rebote que la celda.
        val litSide = side * fill.coerceAtMost(1.08f)
        val litTopLeft = Offset(center.x - litSide / 2f, center.y - litSide / 2f)
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.34f * lit), color.copy(alpha = 0.12f * lit)),
                center = center,
                radius = side * 0.75f,
            ),
            topLeft = litTopLeft,
            size = Size(litSide, litSide),
            cornerRadius = corner,
        )
        drawRoundRect(color.copy(alpha = 0.45f * lit), litTopLeft, Size(litSide, litSide), corner, style = Stroke(1.2.dp.toPx()))
    } else if (padAlpha > 0f) {
        // Punto de contacto: el "aquí falta pasar" de la celda vacía.
        drawCircle(LogicColors.OnDarkMuted.copy(alpha = 0.55f * padAlpha), side * 0.085f, center)
        drawCircle(LogicColors.OnDarkMuted.copy(alpha = 0.30f * padAlpha), side * 0.16f, center, style = Stroke(1.dp.toPx()))
    }
}

/**
 * Un **cable de neón** sobre un [path] arbitrario: halo ancho → halo intermedio → trazo nítido →
 * núcleo blanco, la receta única de neón del proyecto (§9.7; un cable no es el contorno de un
 * tile, así que no usa `drawNeonTile` pero replica su proporción de capas).
 *
 * @param strokeWidth grosor del trazo nítido, en píxeles.
 * @param glow multiplicador de brillo de los halos (1 = normal; >1 para un cable "conectado").
 * @param core opacidad del núcleo blanco (0 = sin núcleo, cable "apagado").
 * @param flowPhase si no es null, dibuja la **corriente**: trazos claros y finos que avanzan por
 *   el cable hacia su final. Pásale un tiempo en segundos. Es deliberadamente tenue —un matiz de
 *   "esto lleva energía", no un segundo elemento que seguir con la vista—.
 */
fun DrawScope.drawNeonWire(
    path: Path,
    color: Color,
    strokeWidth: Float,
    glow: Float = 1f,
    core: Float = 0.30f,
    flowPhase: Float? = null,
) {
    fun stroke(width: Float, effect: PathEffect? = null) =
        Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)

    drawPath(path, color.copy(alpha = (0.20f * glow).coerceAtMost(1f)), style = stroke(strokeWidth * 2.7f))
    drawPath(path, color.copy(alpha = (0.40f * glow).coerceAtMost(1f)), style = stroke(strokeWidth * 1.7f))
    drawPath(path, color, style = stroke(strokeWidth))
    if (core > 0f) {
        drawPath(path, Color.White.copy(alpha = core.coerceAtMost(1f)), style = stroke(strokeWidth * 0.42f))
    }
    if (flowPhase != null) {
        val dash = strokeWidth * 0.9f
        val gap = strokeWidth * 3.6f
        val period = dash + gap
        // Fase decreciente = los trazos avanzan en el sentido del path.
        val phase = period - (flowPhase * strokeWidth * 2.6f) % period
        drawPath(
            path,
            Color.White.copy(alpha = 0.50f),
            style = stroke(strokeWidth * 0.26f, PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase)),
        )
    }
}

/**
 * Un **nodo** de luz: el extremo de un cable (la punta que sigue al dedo, una fuente, un destino).
 *
 * Halo radial, disco del color, núcleo blanco y —si [active]— un aro de dos arcos que gira
 * despacio a su alrededor: es la marca de "este es el punto vivo del tablero".
 *
 * @param pulse 0..1 del latido (agranda nodo y halo); 0 = quieto.
 * @param time reloj en segundos (giro del aro).
 * @param active dibuja el aro giratorio.
 */
fun DrawScope.drawNeonNode(center: Offset, radius: Float, color: Color, pulse: Float = 0f, time: Float = 0f, active: Boolean = false) {
    val r = radius * (1f + 0.22f * pulse)
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.55f + 0.20f * pulse), Color.Transparent), center, r * 2.6f),
        radius = r * 2.6f,
        center = center,
    )
    if (active) {
        val ring = r * 1.75f
        val topLeft = Offset(center.x - ring, center.y - ring)
        val bounds = Size(ring * 2f, ring * 2f)
        val style = Stroke(width = radius * 0.16f, cap = StrokeCap.Round)
        val angle = time * 90f
        drawArc(color.copy(alpha = 0.85f), angle, 110f, false, topLeft, bounds, style = style)
        drawArc(color.copy(alpha = 0.85f), angle + 180f, 110f, false, topLeft, bounds, style = style)
    }
    drawCircle(color, r, center)
    drawCircle(Color.White.copy(alpha = 0.95f), r * 0.58f, center)
}

/**
 * **Estallido de chispas**: destello central, anillo de choque y trazos radiales que salen
 * frenando. Para celebrar un cierre (circuito completo, par conectado, objetivo iluminado).
 *
 * @param reach alcance máximo de las chispas, en píxeles.
 * @param progress 0..1; fuera de (0, 1) no dibuja nada.
 * @param seed semilla estable para variar ángulos y alcances entre estallidos.
 */
fun DrawScope.drawSparkBurst(center: Offset, color: Color, reach: Float, progress: Float, seed: Int = 0) {
    if (progress <= 0f || progress >= 1f) return
    val fade = 1f - progress
    val ease = 1f - fade * fade * fade
    val flash = (1f - progress * 3f).coerceAtLeast(0f)
    if (flash > 0f) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * flash), Color.Transparent), center, reach * 0.7f),
            radius = reach * 0.7f,
            center = center,
        )
    }
    drawCircle(color.copy(alpha = 0.75f * fade), reach * ease, center, style = Stroke(reach * 0.06f * fade + 1f))
    val rays = 12
    for (i in 0 until rays) {
        val angle = (i + kitHash(seed * 31 + i) * 0.7f) * (2f * PI.toFloat() / rays)
        val dist = reach * (0.35f + 0.75f * kitHash(seed * 17 + i)) * ease
        val length = reach * 0.22f * fade
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = (if (i % 3 == 0) Color.White else color).copy(alpha = fade),
            start = Offset(center.x + dx * dist, center.y + dy * dist),
            end = Offset(center.x + dx * (dist + length), center.y + dy * (dist + length)),
            strokeWidth = reach * 0.045f * fade + 1f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Barra de progreso de neón: carril oscuro, relleno con brillo superior y un halo corto.
 *
 * Es la barra de [NeonBoardHud] extraída para que un HUD con otra composición (p. ej. el de
 * Sudoku, que no tiene niveles ni botón de reinicio) pinte **la misma** barra sin copiarla.
 *
 * No anima por sí misma: quien la usa decide la curva (normalmente un `animateFloatAsState` con
 * resorte) y le pasa el valor ya animado.
 *
 * @param progress fracción 0..1 ya animada.
 * @param color color del relleno.
 */
@Composable
fun NeonProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(7.dp)) {
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(LogicColors.SurfaceVariantDark, cornerRadius = corner)
        val filled = Size(size.width * progress.coerceIn(0f, 1f), size.height)
        if (filled.width > 0f) {
            drawRoundRect(
                color = color.copy(alpha = 0.30f),
                topLeft = Offset(0f, -2.dp.toPx()),
                size = Size(filled.width, size.height + 4.dp.toPx()),
                cornerRadius = CornerRadius(size.height),
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(lerp(color, Color.White, 0.35f), color)),
                size = filled,
                cornerRadius = corner,
            )
        }
    }
}

/**
 * HUD superior de un juego de tablero por niveles: píldora de **nivel**, **barra de progreso**
 * con su cifra y botón redondo de **reiniciar**.
 *
 * Sustituye al bloque "título + dos píldoras de etiqueta/valor": el título ya está en la antesala
 * y en el menú de pausa, y una barra que se llena responde a "¿cuánto me falta?" de un vistazo,
 * que es lo que este HUD tiene que decir mientras el jugador traza.
 *
 * Deja libre la esquina superior derecha (el botón de pausa vive ahí).
 *
 * @param progress fracción 0..1 completada; se anima con resorte al cambiar.
 * @param progressLabel cifra junto a la barra ("12/25").
 * @param accent color del cromo del juego (píldora y botón).
 * @param progressColor color de la barra; normalmente el del cable, para que se asocien.
 */
@Composable
fun NeonBoardHud(
    level: Int,
    progress: Float,
    progressLabel: String,
    accent: Color,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    progressColor: Color = accent,
) {
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "boardHudProgress",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 72.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.gameboard_hud_level, level.toString()).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Bold,
            )
            NeonProgressBar(progress = shown, color = progressColor, modifier = Modifier.fillMaxWidth())
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.dp, LogicColors.SurfaceVariantDark, CircleShape)
                .bounceClick(onClick = onRestart),
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(
                icon = KortexIcons.Refresh,
                tint = accent,
                size = 22.dp,
                glow = false,
                contentDescription = stringResource(Res.string.gameboard_hud_restart),
            )
        }
    }
}
