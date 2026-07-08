package com.example.kortexgames.game.watersort

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGamesTheme
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.ArcadeBrickBackground
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.bounceClick
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Paleta neón de los colores de poción. El índice de color del modelo (0..n-1)
 * indexa esta lista, de modo que la lógica pura sigue sin conocer `Color`. Tonos
 * bien separados en el círculo cromático para distinguirlos de un vistazo (todos
 * salen de [LogicColors], nunca hardcodeados sueltos — CLAUDE.md §9.2).
 */
private val PotionColors: List<Color> = listOf(
    LogicColors.Violet,
    LogicColors.NeonCyan,
    LogicColors.NeonGreen,
    LogicColors.Magenta,
    LogicColors.Amber,
    LogicColors.Coral,
    LogicColors.Blue,
    LogicColors.Lime,
)

// --- Fases del vertido animado (fracción del progreso global 0→1) ------------
// El tubo origen: (1) viaja/sube hasta el destino inclinándose ~45°, (2) queda
// inclinado, (3) VIERTE el líquido despacio, (4) vuelve a su sitio. El líquido
// solo se trasvasa en la fase de vertido, lenta a propósito (petición del usuario).
private const val POUR_DURATION_MS = 1700
private const val TRAVEL_OUT_END = 0.26f   // fin del viaje de ida (ya inclinado)
private const val POUR_START = 0.40f        // empieza a derramarse
private const val POUR_END = 0.86f          // fin del derrame
private const val RETURN_START = 0.90f      // empieza el viaje de vuelta
private const val TILT_DEGREES = 45f        // inclinación al servir

/** Ancho fijo de un tubo; se reutiliza para el tubo "viajero" del overlay. */
private val TubeWidth = 54.dp

/** Relación de aspecto del tubo (ancho/alto). Más esbelto → aspecto de frasco. */
private const val TubeAspect = 0.36f

/**
 * Forma del vidrio del tubo de ensayo: boca apenas redondeada arriba y **base en
 * U** (radio ~= mitad del ancho) para que se lea como un frasco real y no como una
 * caja. El líquido se recorta contra esta silueta.
 */
private val TubeShape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 27.dp, bottomEnd = 27.dp)

/**
 * Separación vertical (aire de vidrio) entre bandas de líquido. El usuario pidió
 * "agrandar la separación": deja ver el fondo del vidrio entre colores para que
 * cada franja se lea como un líquido independiente, no como un bloque continuo.
 */
private val BandSeparation = 4.dp

/** Radio de esquina de cada banda de líquido (superficie con aspecto de gota). */
private val BandCorner = 5.dp

/**
 * Una banda de líquido a dibujar, de abajo hacia arriba. [heightSlots] es la
 * altura en "slots" (1.0 = un segmento completo); permite alturas **fraccionarias**
 * para animar el trasvase (una banda que crece/mengua mientras se vierte).
 */
private data class LiquidBand(val colorIndex: Int, val heightSlots: Float)

/**
 * Pantalla de "Ordena las Pociones". Observa el estado del ViewModel y pinta los
 * tubos; un toque en un tubo se traduce en [WaterSortIntent.TapTube] (el motor
 * decide si es selección de origen o vertido). Barra inferior con **Deshacer** y
 * **Reiniciar**. Al ganar, superpone [GameOverOverlay].
 *
 * **Animación de vertido** (dirigida por un único `progress` 0→1): al emitir el
 * motor un [PourEvent], el tubo origen se dibuja en una **capa superior** (para
 * quedar siempre por encima), **viaja** hasta quedar **al lado y por encima** del
 * destino inclinado 45°, y entonces **vierte** el líquido despacio con un chorro
 * neón que sale de la **esquina del pico**; luego vuelve a su sitio. El estado del
 * motor ya está actualizado, así que se reconstruye el estado *previo* al vertido
 * a partir del propio evento para animar el trasvase.
 *
 * El progreso efectivo se calcula de forma **síncrona** (`p`): mientras el
 * `LaunchedEffect` aún no ha arrancado la animación del vertido nuevo, `p` vale 0
 * (estado inicial), evitando el "frame fantasma" en que se vería el estado final.
 */
@Composable
fun WaterSortScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: WaterSortViewModel = viewModel {
        WaterSortViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Fase de intro: antesala del juego (icono, descripción y carril de niveles). El
    // nivel elegido arranca por defecto en la frontera (récord + 1) y se reinicia si el
    // récord sube; "Comenzar" arranca el motor y pasamos a la vista de juego.
    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            title = "Ordena las Pociones",
            description = "Vierte colores iguales hasta dejar cada tubo de un solo color.",
            accent = CategoryPalette.Logic,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = { vm.onIntent(WaterSortIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            background = {
                ArcadeBrickBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.Logic)
            },
        )
        return
    }

    val game = state.game

    // Coordenadas de cada tubo y del contenedor, para posicionar el tubo viajero
    // y el chorro respecto a los tubos reales (sin importar en qué fila estén).
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tubeCoords = remember { mutableMapOf<Int, LayoutCoordinates>() }

    val progress = remember { Animatable(0f) }
    // Id del vertido cuya animación ya arrancó. Sirve para (1) lanzar la animación
    // y (2) saber si el `progress` actual corresponde ya al vertido en curso.
    var animId by remember { mutableStateOf<Long?>(null) }

    val pour = game.lastPour

    LaunchedEffect(pour?.id) {
        val pr = pour ?: return@LaunchedEffect
        animId = pr.id
        progress.snapTo(0f)
        progress.animateTo(1f, tween(POUR_DURATION_MS, easing = FastOutSlowInEasing))
    }

    val raw = progress.value                  // registra lecturas → recomposición por frame
    val started = pour != null && animId == pour.id
    val p = if (started) raw else 0f          // aún sin arrancar ⇒ estado inicial (p=0)
    val animating = pour != null && (!started || raw < 1f)

    /** Rect del tubo [i] en coordenadas del contenedor (o null si aún no medido). */
    fun boundsOf(i: Int): Rect? {
        val container = containerCoords ?: return null
        val tube = tubeCoords[i] ?: return null
        if (!tube.isAttached || !container.isAttached) return null
        return container.localBoundingBoxOf(tube)
    }

    Box(Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Textura ambiental de muro arcade "neo-retro" (azul Lógica), muy sutil.
        ArcadeBrickBackground(
            modifier = Modifier.fillMaxSize(),
            accent = CategoryPalette.Logic,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Ordena las Pociones",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${game.moves} movimientos",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.Electric,
            )
            Text(
                "Nivel ${game.round}",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.NeonGreen,
            )
            Text(
                "Vierte colores iguales hasta dejar cada tubo de un solo color",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            Spacer(Modifier.height(28.dp))

            // Tubos repartidos en dos filas equilibradas (como el clásico).
            val perRow = ((game.tubes.size + 1) / 2).coerceAtLeast(1)
            val pourFactor = ((p - POUR_START) / (POUR_END - POUR_START)).coerceIn(0f, 1f)
            val activePour = if (animating) pour else null

            Box(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { containerCoords = it },
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    game.tubes.chunked(perRow).forEachIndexed { rowIdx, rowTubes ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            rowTubes.forEachIndexed { colIdx, tube ->
                                val index = rowIdx * perRow + colIdx
                                val isTravelingSource = activePour != null && index == activePour.from

                                // El tubo seleccionado (o el origen que va a viajar) se
                                // dibuja por encima del resto (§ petición: siempre arriba).
                                val elevated = game.selected == index || isTravelingSource
                                val slotModifier = Modifier
                                    .width(TubeWidth)
                                    .zIndex(if (elevated) 1f else 0f)
                                    .onGloballyPositioned { tubeCoords[index] = it }

                                when {
                                    // El origen viaja en el overlay: aquí solo reservamos
                                    // su hueco (placeholder vacío) para no colapsar la fila.
                                    isTravelingSource -> Box(
                                        slotModifier.aspectRatio(TubeAspect),
                                    )
                                    // El destino se rellena in situ durante el vertido. Ya
                                    // brilla si este vertido lo deja completo.
                                    activePour != null && index == activePour.to -> TubeView(
                                        bands = destBands(tube, activePour, pourFactor),
                                        capacity = game.capacity,
                                        highlightColor = null,
                                        lifted = false,
                                        glowing = tube.isGlowing(game.capacity),
                                        glowColor = PotionColors[activePour.color],
                                        tiltDegrees = 0f,
                                        enabled = false,
                                        onClick = {},
                                        modifier = slotModifier,
                                    )
                                    else -> {
                                        val isSel = game.selected == index
                                        val top = tube.topColorOrNull()
                                        TubeView(
                                            bands = tube.segments.map { LiquidBand(it, 1f) },
                                            capacity = game.capacity,
                                            // Borde neón del color superior al seleccionar (§ petición).
                                            highlightColor = if (isSel) top else null,
                                            lifted = isSel,
                                            glowing = tube.isGlowing(game.capacity),
                                            glowColor = top ?: LogicColors.NeonCyan,
                                            tiltDegrees = 0f,
                                            // Los demás frascos siguen tocables aunque otro esté
                                            // vertiendo (§ petición: no bloquear el resto).
                                            enabled = true,
                                            onClick = { vm.onIntent(WaterSortIntent.TapTube(index)) },
                                            modifier = slotModifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Capa superior: tubo viajero + chorro (solo durante el vertido) ---
                if (activePour != null) {

                    val fromRect = boundsOf(activePour.from)
                    val destRect = boundsOf(activePour.to)
                    if (fromRect != null && destRect != null) {
                        val travel = travelFactor(p)
                        val dir = if (destRect.center.x >= fromRect.center.x) 1f else -1f
                        val current = travelingRect(fromRect, destRect, dir, travel)
                        val angle = dir * TILT_DEGREES * travel

                        // Tubo origen "en vuelo": posición y giro interpolados; el líquido
                        // solo baja durante la fase de vertido (pourFactor).
                        TubeView(
                            bands = sourceBands(game.tubes[activePour.from], activePour, pourFactor),
                            capacity = game.capacity,
                            // Mantiene el borde neón del color vertido durante todo el
                            // trasvase (§ petición: conservar el borde al derramar).
                            highlightColor = PotionColors[activePour.color],
                            lifted = false,
                            glowing = false,
                            glowColor = PotionColors[activePour.color],
                            tiltDegrees = angle,
                            enabled = false,
                            onClick = {},
                            // Posicionamos con `graphicsLayer` (traslación en fase de
                            // dibujo), NO con `offset { }`: el offset diferido no se
                            // aplica en el primer frame del nodo recién compuesto, lo que
                            // hacía destellar el tubo un frame en la esquina (TopStart)
                            // antes de saltar al origen.
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .width(TubeWidth)
                                .graphicsLayer {
                                    translationX = current.left
                                    translationY = current.top
                                },
                        )

                        // Chorro neón desde la esquina del pico del tubo viajero al destino.
                        Canvas(Modifier.fillMaxSize()) {
                            drawPourStream(current, destRect, dir, angle, PotionColors[activePour.color], pourFactor)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Barra de acciones: Deshacer y Reiniciar (bloqueadas mientras sirve).
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                ActionButton(
                    icon = KortexIcons.Undo,
                    label = "Deshacer",
                    tint = LogicColors.NeonCyan,
                    enabled = game.canUndo && !animating,
                    onClick = { vm.onIntent(WaterSortIntent.Undo) },
                )
                ActionButton(
                    icon = KortexIcons.Refresh,
                    label = "Reiniciar",
                    tint = LogicColors.Amber,
                    enabled = !animating,
                    onClick = { vm.onIntent(WaterSortIntent.Restart) },
                )
            }
        }

        // El overlay de fin de partida espera a que termine el último vertido para
        // no tapar la animación de la jugada ganadora.
        if (state.status == GameStatus.FINISHED && state.gameOver != null && !animating) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(WaterSortIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(WaterSortIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(WaterSortIntent.ChooseLevel) },
            )
        }
    }
}

/**
 * Factor de "estar en el destino" 0→1→0: sube durante el viaje de ida (con
 * suavizado), se mantiene en 1 mientras vierte y baja durante la vuelta. Controla
 * a la vez la posición y la inclinación del tubo viajero.
 */
private fun travelFactor(progress: Float): Float = when {
    progress < TRAVEL_OUT_END -> FastOutSlowInEasing.transform((progress / TRAVEL_OUT_END).coerceIn(0f, 1f))
    progress < RETURN_START -> 1f
    else -> 1f - FastOutSlowInEasing.transform(((progress - RETURN_START) / (1f - RETURN_START)).coerceIn(0f, 1f))
}

/**
 * Rectángulo actual del tubo viajero. El destino es tal que, al girar 45°, la
 * **esquina del pico** ([lipPoint]) queda justo sobre la boca del tubo destino,
 * con el cuerpo **al lado y por encima** (no pisándolo). Se interpola desde el
 * hueco de origen con [travel].
 */
private fun travelingRect(from: Rect, dest: Rect, dir: Float, travel: Float): Rect {
    val w = from.width
    val h = from.height
    // Desplazamiento del pico respecto a la base al girar TILT_DEGREES (ver lipPoint).
    val rad = dir * TILT_DEGREES / 180f * PI.toFloat()
    val c = cos(rad)
    val s = sin(rad)
    val vx = dir * w / 2f
    val vy = -h
    val lipDx = vx * c - vy * s
    val lipDy = vx * s + vy * c
    val gap = h * 0.18f // el pico queda algo por encima del borde del destino

    // Queremos: pivote(base) + (lipDx,lipDy) == (dest.center.x, dest.top - gap).
    val targetPivotX = dest.center.x - lipDx
    val targetPivotY = (dest.top - gap) - lipDy
    val targetLeft = targetPivotX - w / 2f
    val targetTop = targetPivotY - h

    val left = lerp(from.left, targetLeft, travel)
    val top = lerp(from.top, targetTop, travel)
    return Rect(left, top, left + w, top + h)
}

/**
 * Punto de la **esquina del pico** de un tubo girado [angleDeg] alrededor de su
 * base (centro inferior). Antes de girar, el pico es la esquina superior del lado
 * hacia donde se inclina ([dir]); se rota ese vértice para obtener de dónde cae
 * realmente el líquido (como en la vida real).
 */
private fun lipPoint(rect: Rect, dir: Float, angleDeg: Float): Offset {
    val rad = angleDeg / 180f * PI.toFloat()
    val c = cos(rad)
    val s = sin(rad)
    val pivotX = rect.center.x
    val pivotY = rect.bottom
    val vx = dir * rect.width / 2f
    val vy = -rect.height
    return Offset(pivotX + (vx * c - vy * s), pivotY + (vx * s + vy * c))
}

/**
 * Bandas del tubo **origen** a mitad de vertido: las que le quedan (post-vertido)
 * a altura completa, más una banda del color vertido que **mengua** de `count`→0.
 */
private fun sourceBands(tube: Tube, pour: PourEvent, pourFactor: Float): List<LiquidBand> {
    val base = tube.segments.map { LiquidBand(it, 1f) }
    val draining = pour.count * (1f - pourFactor)
    return if (draining > 0f) base + LiquidBand(pour.color, draining) else base
}

/**
 * Bandas del tubo **destino** a mitad de vertido: las que ya tenía antes del
 * vertido a altura completa, más una banda del color vertido que **crece** 0→`count`.
 */
private fun destBands(tube: Tube, pour: PourEvent, pourFactor: Float): List<LiquidBand> {
    val previous = tube.segments.dropLast(pour.count.coerceAtMost(tube.segments.size))
    val base = previous.map { LiquidBand(it, 1f) }
    val filling = pour.count * pourFactor
    return if (filling > 0f) base + LiquidBand(pour.color, filling) else base
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Dibuja el chorro como un arco de Bézier desde la **esquina del pico** del tubo
 * viajero hasta la boca del destino. Un tramo (cabeza a cola) recorre el arco
 * según [pourFactor]: sale del pico y "cae" en el destino, con un halo neón ancho
 * detrás del núcleo brillante.
 */
private fun DrawScope.drawPourStream(source: Rect, dest: Rect, dir: Float, angleDeg: Float, color: Color, pourFactor: Float) {
    if (pourFactor <= 0f || pourFactor >= 1f) return

    val start = lipPoint(source, dir, angleDeg)
    val end = Offset(dest.center.x, dest.top + 3.dp.toPx())
    val control = Offset((start.x + end.x) / 2f, minOf(start.y, end.y) - 20.dp.toPx())

    val path = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(control.x, control.y, end.x, end.y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length

    // Cabeza avanza más rápido que la cola → el chorro "vuela" y luego drena.
    val head = (pourFactor * 1.8f).coerceIn(0f, 1f) * length
    val tail = ((pourFactor - 0.30f) / 0.70f).coerceIn(0f, 1f) * length
    if (head - tail <= 1f) return

    val segment = Path()
    measure.getSegment(tail, head, segment, true)
    drawPath(segment, color.copy(alpha = 0.30f), style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round))
    drawPath(segment, color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
}

/** Color neón de la banda superior del tubo (lo próximo a verter), o null si vacío. */
private fun Tube.topColorOrNull(): Color? = segments.lastOrNull()?.let { PotionColors[it] }

/**
 * ¿El tubo debe **brillar** (halo pulsante)? Solo cuando está resuelto de verdad:
 * lleno y de un único color. Un tubo vacío también cumple `isComplete`, pero no
 * tiene nada que celebrar, así que no brilla.
 */
private fun Tube.isGlowing(capacity: Int): Boolean =
    segments.isNotEmpty() && isComplete(capacity)

/**
 * Un tubo de ensayo dibujado con [Canvas] para poder pintar bandas de altura
 * fraccionaria (necesario para animar el trasvase). Feedback visual (§9.4):
 *  - **[lifted]** (seleccionado) → se eleva con `spring` (sensación táctil),
 *  - **[highlightColor]** → borde neón de ese color con halo estático (selección
 *    o tubo origen en vuelo; el usuario pidió conservarlo al derramar),
 *  - **[glowing]** (tubo completado) → halo neón **pulsante** de [glowColor],
 *  - **[tiltDegrees]** (sirviendo) → se inclina pivotando sobre su base.
 */
@Composable
private fun TubeView(
    bands: List<LiquidBand>,
    capacity: Int,
    highlightColor: Color?,
    lifted: Boolean,
    glowing: Boolean,
    glowColor: Color,
    tiltDegrees: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El tubo seleccionado se eleva con física de resorte (sensación táctil, §9.4).
    val lift by animateDpAsState(
        targetValue = if (lifted) (-18).dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tubeLift",
    )

    // Latido lento del halo del tubo completado (ambiente de baja amplitud, §9.4).
    val glowPulse by rememberInfiniteTransition(label = "tubeGlow").animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tubeGlowAlpha",
    )

    // Prioridad del halo: completado (pulsante) > seleccionado/en vuelo (fijo).
    val haloColor = when {
        glowing -> glowColor
        highlightColor != null -> highlightColor
        else -> null
    }
    val haloAlpha = when {
        glowing -> glowPulse
        highlightColor != null -> 0.5f
        else -> 0f
    }
    val borderColor = highlightColor ?: if (glowing) glowColor else LogicColors.SurfaceVariantDark
    val borderDp = if (highlightColor != null || glowing) 2.5.dp else 1.5.dp

    Box(modifier = modifier.aspectRatio(TubeAspect)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = lift)
                .graphicsLayer {
                    // Pivota sobre la base (como un tubo real al servir).
                    rotationZ = tiltDegrees
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .bounceClick(enabled = enabled, onClick = onClick),
        ) {
            drawTube(bands, capacity, borderColor, borderDp.toPx(), haloColor, haloAlpha)
        }
    }
}

/**
 * Pinta el frasco de ensayo: vidrio (fondo + reflejo), líquido en **bandas
 * separadas** (con aire de vidrio entre colores para que se lean como líquidos
 * distintos, § petición) y borde/halo neón. La última banda puede ser fraccionaria
 * (superficie del líquido durante el vertido).
 *
 * @param haloColor color del halo neón interior (selección/en vuelo/completado); null = sin halo.
 * @param haloAlpha intensidad del halo 0..1 (pulsa cuando el tubo está completado).
 */
private fun DrawScope.drawTube(
    bands: List<LiquidBand>,
    capacity: Int,
    borderColor: Color,
    borderWidthPx: Float,
    haloColor: Color?,
    haloAlpha: Float,
) {
    val outline = TubeShape.createOutline(size, layoutDirection, this)
    val glass = Path().apply { addOutline(outline) }

    drawPath(glass, LogicColors.SurfaceDark) // vidrio

    clipPath(glass) {
        val inset = 4.dp.toPx()
        val topGap = 7.dp.toPx() // deja aire en la boca aunque esté lleno
        val sep = BandSeparation.toPx()
        val corner = CornerRadius(BandCorner.toPx(), BandCorner.toPx())
        val slotHeight = (size.height - topGap) / capacity
        val innerWidth = size.width - inset * 2
        var yBottom = size.height - inset // pequeño colchón sobre la base en U
        for (band in bands) {
            val h = band.heightSlots * slotHeight
            if (h <= 0f) continue
            // Reserva `sep` de aire en la parte alta de cada banda → franjas separadas.
            val drawH = (h - sep).coerceAtLeast(1f)
            drawRoundRect(
                color = PotionColors[band.colorIndex],
                topLeft = Offset(inset, yBottom - drawH),
                size = Size(innerWidth, drawH),
                cornerRadius = corner,
            )
            // Menisco: fina franja superior más clara → aspecto de superficie líquida.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(inset, yBottom - drawH),
                size = Size(innerWidth, 3.dp.toPx()),
                cornerRadius = corner,
            )
            yBottom -= h
        }

        // Reflejo de vidrio: brillo vertical translúcido en el lado izquierdo (§ "los
        // detalles hacen grande a la app"). Va sobre el líquido, muy sutil.
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                startX = inset,
                endX = inset + innerWidth * 0.5f,
            ),
            topLeft = Offset(inset, inset),
            size = Size(innerWidth * 0.32f, size.height),
            cornerRadius = corner,
        )

        // Halo neón **interior**: varios trazos del borde a alfa baja dan un
        // resplandor hacia dentro sin salirse de los límites del Canvas.
        if (haloColor != null && haloAlpha > 0f) {
            drawPath(glass, haloColor.copy(alpha = haloAlpha * 0.5f), style = Stroke(11.dp.toPx()))
            drawPath(glass, haloColor.copy(alpha = haloAlpha), style = Stroke(5.dp.toPx()))
        }
    }

    drawPath(glass, borderColor, style = Stroke(borderWidthPx)) // borde neón/tenue
}

/**
 * Botón de acción con icono neón y etiqueta. El icono se envuelve en un contenedor
 * de **tamaño fijo** para que activar/desactivar el halo (glow) no cambie su
 * footprint y, con ello, no desplace el resto de la UI (bug de "salto" al servir).
 */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveTint = if (enabled) tint else LogicColors.OnDarkMuted
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .bounceClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reserva fija (~1.9x el glifo) para que el halo no altere el layout.
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            NeonIcon(icon = icon, tint = effectiveTint, glow = enabled, size = 28.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = effectiveTint)
    }
}

@Preview
@Composable
private fun PreviewTubeNormal() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(0, 1f), LiquidBand(2, 1f), LiquidBand(5, 1f)),
                capacity = 4,
                highlightColor = null,
                lifted = false,
                glowing = false,
                glowColor = LogicColors.NeonCyan,
                tiltDegrees = 0f,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(TubeWidth),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTubeSeleccionado() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(1, 1f), LiquidBand(1, 1f), LiquidBand(3, 1f)),
                capacity = 4,
                highlightColor = LogicColors.NeonCyan,
                lifted = true,
                glowing = false,
                glowColor = LogicColors.NeonCyan,
                tiltDegrees = 0f,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(TubeWidth),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTubeCompletado() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
        ) {
            TubeView(
                bands = listOf(
                    LiquidBand(4, 1f),
                    LiquidBand(4, 1f),
                    LiquidBand(4, 1f),
                    LiquidBand(4, 1f),
                ),
                capacity = 4,
                highlightColor = null,
                lifted = false,
                glowing = true,
                glowColor = LogicColors.Amber,
                tiltDegrees = 0f,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(TubeWidth),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTubeSirviendo() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(6, 1f), LiquidBand(6, 1f), LiquidBand(6, 0.45f)),
                capacity = 4,
                highlightColor = LogicColors.Blue,
                lifted = false,
                glowing = false,
                glowColor = LogicColors.Blue,
                tiltDegrees = -38f,
                enabled = false,
                onClick = {},
                modifier = Modifier.width(TubeWidth),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewActionButtons() {
    LogicGamesTheme {
        Row(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ActionButton(
                icon = KortexIcons.Undo,
                label = "Deshacer",
                tint = LogicColors.NeonCyan,
                enabled = true,
                onClick = {},
            )
            ActionButton(
                icon = KortexIcons.Refresh,
                label = "Reiniciar",
                tint = LogicColors.Amber,
                enabled = false,
                onClick = {},
            )
        }
    }
}

