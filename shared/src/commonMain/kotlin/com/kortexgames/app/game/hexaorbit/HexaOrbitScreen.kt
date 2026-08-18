package com.kortexgames.app.game.hexaorbit

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.SpaceBackdrop
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.hexa_orbit_hud_orbs
import kortexgames.shared.generated.resources.hexa_orbit_hud_score
import kortexgames.shared.generated.resources.hexa_orbit_hud_speed
import kortexgames.shared.generated.resources.hexa_orbit_intro_description
import kortexgames.shared.generated.resources.hexa_orbit_subtitle
import kortexgames.shared.generated.resources.hexa_orbit_warning
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * # HexaOrbitScreen — renderizado 2D y luces neón (FASE 3)
 *
 * Pantalla del minijuego **Hexa Orbit**. Como el resto de juegos de acción del proyecto es, por
 * diseño, dos cosas: un **gestor de toques** (tap sobre un hexágono → lo gira) y un **Canvas**
 * que traduce el estado del motor a píxeles. Sin botones genéricos ni emojis.
 *
 * ## Una sola escala: el radio del hexágono
 *
 * El dominio trabaja en *radios de hexágono* ([HexPoint]) y no sabe nada de píxeles. Aquí se
 * calcula **una vez por frame** el radio en píxeles que hace caber el tablero en el `Canvas` y
 * todo el dibujo pasa por [BoardTransform]. Esa única frontera de conversión es lo que permite
 * que el juego se vea idéntico en cualquier pantalla sin tocar la física, y que el tap se
 * invierta con la misma fórmula ([HexGeometry.hexAt]) en vez de con una tabla de rectángulos.
 *
 * ## El haz proyectado es la pantalla del juego
 *
 * Los cuatro azulejos que vienen se dibujan como un tubo de luz que **se apaga hacia el final**:
 * el tramo actual va a intensidad plena y el cuarto casi transparente. El desvanecido no es
 * decorativo — comunica *cuánto falta*, que es la información con la que el jugador decide qué
 * girar primero. Si la proyección apunta al vacío ([LookaheadPath.escapes]) el haz entero vira a
 * [LogicColors.Error]: la alarma llega con cuatro azulejos de antelación, que es exactamente el
 * margen de reacción que el juego promete.
 *
 * ## Borde de las celdas: contorno sutil, no `drawNeonTile`
 *
 * Siguiendo la §9.7 de `CLAUDE.md`, el contorno de cada hexágono es un trazo tenue en
 * [LogicColors.SurfaceVariantDark] y **no** el tubo hueco de `drawNeonTile`: sobre un tablero de
 * 37 celdas con caminos, haz y orbes encima, 37 marcos de neón competirían con el contenido y se
 * verían recargados. El neón se reserva para lo que sí es información: el haz, el puntero y los
 * orbes.
 *
 * ## Animaciones dirigidas por el reloj de frames, no por `Animatable`
 *
 * El giro de una pieza y las partículas de recogida se animan a partir del **timestamp del
 * frame** que ya alimenta la física ([HexaOrbitIntent.Tick]), en vez de crear un `Animatable` por
 * azulejo. Con 37 celdas eso serían 37 corrutinas de animación vivas compitiendo por recomponer
 * una pantalla que ya se redibuja entera cada frame; con el reloj compartido, una rotación cuesta
 * una entrada en un mapa y una interpolación en el `Canvas`.
 */
@Composable
fun HexaOrbitScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: HexaOrbitViewModel = viewModel {
        HexaOrbitViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de física.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.hexaOrbit,
            title = "Hexa Orbit",
            motif = GameMotif.HEXA_ORBIT,
            description = stringResource(Res.string.hexa_orbit_intro_description),
            accent = CategoryPalette.SpatialVision,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar la
                // partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.HEXA_ORBIT)
                vm.onIntent(HexaOrbitIntent.Start)
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    // Reloj de frames compartido por física y animaciones (ver KDoc de la clase).
    var frameNanos by remember { mutableLongStateOf(0L) }

    // Momento en que cada pieza empezó a girar; alimenta el "spring" del giro sin Animatable.
    val spinStart = remember { mutableMapOf<HexCoord, Long>() }
    val lastRotation = remember { mutableMapOf<HexCoord, Int>() }

    // Estallidos de partículas pendientes de dibujar (se podan al expirar).
    val bursts = remember { mutableStateListOf<OrbBurst>() }

    // Latido ambiental de baja amplitud: único bucle continuo de la pantalla (§9.4), reservado a
    // los orbes recolectables, que son el objetivo que debe llamar la atención.
    val orbPulse by rememberInfiniteTransition(label = "hexaOrbitPulse").animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hexaOrbitOrbPulse",
    )

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                frameNanos = nanos
                vm.onIntent(HexaOrbitIntent.Tick(nanos))
            }
        }
    }

    // Detección de giros: el motor ya aplicó la rotación, así que aquí solo se anota CUÁNDO
    // ocurrió para que el dibujo llegue con retraso elástico a la posición final.
    LaunchedEffect(game.board) {
        for ((coord, tile) in game.board.tiles) {
            val previous = lastRotation[coord]
            lastRotation[coord] = tile.rotation
            if (previous != null && previous != tile.rotation) spinStart[coord] = frameNanos
        }
    }

    // Partida nueva: el tablero se regenera entero y las rotaciones cambian de golpe, lo que
    // haría girar las 37 piezas a la vez. Se limpian los rastros para que la nueva partida
    // arranque en reposo.
    LaunchedEffect(state.status) {
        if (state.status == GameStatus.RUNNING && game.elapsedSeconds == 0f) {
            spinStart.clear()
            lastRotation.clear()
            bursts.clear()
        }
    }

    // Estallido al recoger: el efecto del motor es semántico y no lleva posición, así que se
    // ancla a donde estaba el puntero en el frame de la recogida — que es justo donde el jugador
    // está mirando.
    LaunchedEffect(game.collected) {
        if (game.collected > 0) bursts += OrbBurst(game.pointer.position, frameNanos)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(game.board.radius) {
                    detectTapGestures { tap ->
                        // Píxel → mundo → celda. El redondeo cúbico de [HexGeometry.hexAt] es lo
                        // que hace que un toque cerca de un vértice acierte la celda correcta.
                        val transform = BoardTransform.of(size.width.toFloat(), size.height.toFloat())
                        vm.onIntent(HexaOrbitIntent.RotateTile(transform.hexAt(tap)))
                    }
                },
        ) {
            val transform = BoardTransform.of(size.width, size.height)

            drawBoardCells(game, transform, frameNanos, spinStart)
            drawIdlePaths(game, transform, frameNanos, spinStart)
            drawProjectedBeam(game, transform)
            drawEnergyOrbs(game, transform, orbPulse)
            drawPointer(game, transform)

            // Las partículas van encima de todo: son la recompensa y no deben quedar tapadas.
            bursts.removeAll { burst -> drawBurst(burst, transform, frameNanos).not() }
        }

        HexaOrbitHud(game = game, modifier = Modifier.align(Alignment.TopCenter))

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(HexaOrbitIntent.RestartGame) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HexaOrbitIntent.Pause) },
            onResume = { vm.onIntent(HexaOrbitIntent.Resume) },
            onExit = onExit,
            gameTitle = "Hexa Orbit",
            help = GameHelpContent.hexaOrbit,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Transformación mundo → pantalla
// ---------------------------------------------------------------------------------------------

/**
 * Conversión entre el espacio del tablero (radios de hexágono) y el `Canvas` (píxeles).
 *
 * El tablero se centra y se escala para caber entero con un margen; la escala es **uniforme**
 * (un solo [radiusPx] para los dos ejes) porque un hexágono estirado dejaría de empalmar con sus
 * vecinos y las curvas mostrarían codos en las fronteras.
 *
 * @property radiusPx radio de un hexágono en píxeles.
 * @property center centro del tablero en el `Canvas`.
 */
private data class BoardTransform(val radiusPx: Float, val center: Offset) {

    /** Punto del mundo → punto del `Canvas`. */
    fun toScreen(point: HexPoint): Offset =
        Offset(center.x + point.x * radiusPx, center.y + point.y * radiusPx)

    /** Centro del azulejo [coord] en el `Canvas`. */
    fun screenCenter(coord: HexCoord): Offset = toScreen(HexGeometry.center(coord))

    /** Toque en el `Canvas` → celda del tablero (inversa completa, con redondeo cúbico). */
    fun hexAt(tap: Offset): HexCoord = HexGeometry.hexAt(
        HexPoint((tap.x - center.x) / radiusPx, (tap.y - center.y) / radiusPx),
    )

    companion object {

        /**
         * Calcula la escala que hace caber un tablero de radio
         * [HexaOrbitBalance.BOARD_RADIUS] en un lienzo de [width] × [height].
         *
         * Las dos extensiones salen de la geometría pointy-top: a lo ancho el tablero mide
         * `√3 · (2R + 1)` radios (centros extremos a `√3·R` más media anchura de hexágono a cada
         * lado) y a lo alto `3R + 2` (centros a `1.5·R` más un radio completo arriba y abajo). Se
         * toma el mínimo de los dos ajustes para que nunca se recorte por el lado estrecho.
         */
        fun of(width: Float, height: Float): BoardTransform {
            val r = HexaOrbitBalance.BOARD_RADIUS
            val worldWidth = SQRT_3 * (2f * r + 1f)
            val worldHeight = 3f * r + 2f
            val radiusPx = min(width / worldWidth, height / worldHeight) * BOARD_FILL
            return BoardTransform(radiusPx, Offset(width * 0.5f, height * 0.5f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Capas de dibujo
// ---------------------------------------------------------------------------------------------

/**
 * Contorno de cada celda: hexágono tenue que da la rejilla sin robar protagonismo (§9.7).
 *
 * El contorno **no gira** con la pieza aunque sus caminos sí: un hexágono girado 60° ocupa el
 * mismo sitio, así que animar su borde solo produciría un parpadeo sin significado.
 */
private fun DrawScope.drawBoardCells(
    game: HexaOrbitState,
    transform: BoardTransform,
    frameNanos: Long,
    spinStart: Map<HexCoord, Long>,
) {
    for (coord in game.board.tiles.keys) {
        val center = transform.screenCenter(coord)
        val path = Path()
        for (i in 0 until HEX_EDGES) {
            val corner = HexGeometry.corner(i)
            val point = Offset(center.x + corner.x * transform.radiusPx, center.y + corner.y * transform.radiusPx)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()

        // La celda recién girada se ilumina un instante: confirma el tap incluso si el jugador
        // no está mirando ese punto exacto del tablero.
        val spin = spinProgress(coord, frameNanos, spinStart)
        val highlight = (1f - spin) * 0.5f
        drawPath(
            path = path,
            color = lerp(LogicColors.SurfaceVariantDark, CategoryPalette.SpatialVision, highlight),
            style = Stroke(width = transform.radiusPx * 0.045f),
        )
    }
}

/**
 * Los tres caminos de cada azulejo en tono apagado: el "circuito impreso" sobre el que después
 * se enciende el haz.
 *
 * Aquí es donde se aplica el **retraso elástico del giro**: el motor ya dejó la rotación en su
 * valor final, así que se dibuja con un desfase de `−60°` que se consume con `EaseOutBack`. El
 * resultado es el rebote de resorte que pide la §9.4 sin una sola corrutina de animación.
 */
private fun DrawScope.drawIdlePaths(
    game: HexaOrbitState,
    transform: BoardTransform,
    frameNanos: Long,
    spinStart: Map<HexCoord, Long>,
) {
    for ((coord, tile) in game.board.tiles) {
        val spin = spinProgress(coord, frameNanos, spinStart)
        val offsetDeg = -SPIN_DEGREES * (1f - spin)
        for (pair in tile.connections) {
            drawCurve(
                curve = HexGeometry.curveFor(pair),
                coord = coord,
                transform = transform,
                rotationDeg = offsetDeg,
                color = LogicColors.SurfaceVariantDark,
                widthFactor = 0.10f,
                alpha = 0.9f,
            )
        }
    }
}

/**
 * El haz proyectado: los azulejos que el puntero va a recorrer, encendidos con intensidad
 * decreciente.
 *
 * Cada tramo se dibuja en tres capas —halo ancho, halo intermedio y trazo nítido— que es la misma
 * proporción que usa `drawNeonTile` para su tubo de luz (§9.7 de `CLAUDE.md`: si el trazo no es
 * el contorno de un tile, se replica la estructura de capas en vez de inventar otro halo).
 *
 * El color va de [LogicColors.NeonCyan] a [LogicColors.NeonGreen] según la profundidad, salvo
 * cuando la proyección se escapa del tablero: entonces todo el haz es [LogicColors.Error].
 */
private fun DrawScope.drawProjectedBeam(game: HexaOrbitState, transform: BoardTransform) {
    val steps = game.projection.steps
    if (steps.isEmpty()) return

    steps.forEachIndexed { index, step ->
        val depth = index.toFloat() / (HexaOrbitBalance.LOOKAHEAD_TILES + 1).toFloat()
        // Desvanecido cuadrático: la caída es más marcada al principio, así que los dos primeros
        // azulejos —donde de verdad hay que decidir— destacan sobre el resto de la cola.
        val intensity = (1f - depth) * (1f - depth)
        val color = if (game.projection.escapes) {
            LogicColors.Error
        } else {
            lerp(LogicColors.NeonCyan, LogicColors.NeonGreen, depth)
        }

        val curve = HexGeometry.orientedCurve(step.coord, step.entryEdge, step.exitEdge)
        // Halo ancho → halo intermedio → trazo nítido (misma escalera que drawNeonTile).
        drawCurve(curve, step.coord, transform, 0f, color, 0.34f, 0.10f * intensity, absolute = true)
        drawCurve(curve, step.coord, transform, 0f, color, 0.20f, 0.26f * intensity, absolute = true)
        drawCurve(curve, step.coord, transform, 0f, color, 0.09f, 0.95f * intensity, absolute = true)
    }
}

/**
 * Orbes de energía: gema pulsante con halo radial.
 *
 * Alternan [LogicColors.Amber] y [LogicColors.Violet] según su id (paridad estable durante toda
 * su vida) para que tres orbes en pantalla se distingan de un vistazo sin recurrir al parpadeo
 * desfasado, que a esta velocidad se leería como ruido.
 */
private fun DrawScope.drawEnergyOrbs(game: HexaOrbitState, transform: BoardTransform, pulse: Float) {
    for (orb in game.orbs) {
        val center = transform.toScreen(orb.position)
        val color = if (orb.id % 2L == 0L) LogicColors.Amber else LogicColors.Violet
        val radius = transform.radiusPx * ORB_RADIUS_FACTOR * pulse

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.45f * pulse), Color.Transparent),
                center = center,
                radius = radius * 3.2f,
            ),
            radius = radius * 3.2f,
            center = center,
        )
        drawCircle(color = color, radius = radius, center = center)
        drawCircle(color = Color.White.copy(alpha = 0.55f), radius = radius * 0.42f, center = center)
    }
}

/**
 * El puntero: estela que se desvanece hacia atrás más el orbe brillante en la cabeza.
 *
 * La estela se dibuja como segmentos independientes con alfa creciente y no como un `Path`
 * único: un trazo continuo solo admite una opacidad, y es precisamente el degradado —lo viejo
 * casi transparente, la cabeza a plena luz— lo que da la sensación de velocidad.
 */
private fun DrawScope.drawPointer(game: HexaOrbitState, transform: BoardTransform) {
    val trail = game.pointer.trail
    for (i in 1 until trail.size) {
        val fade = i.toFloat() / trail.size.toFloat()
        drawLine(
            color = LogicColors.NeonCyan.copy(alpha = fade * 0.65f),
            start = transform.toScreen(trail[i - 1]),
            end = transform.toScreen(trail[i]),
            strokeWidth = transform.radiusPx * 0.12f * fade,
            cap = StrokeCap.Round,
        )
    }

    val head = transform.toScreen(game.pointer.position)
    val radius = transform.radiusPx * POINTER_RADIUS_FACTOR
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(LogicColors.NeonCyan.copy(alpha = 0.55f), Color.Transparent),
            center = head,
            radius = radius * 4f,
        ),
        radius = radius * 4f,
        center = head,
    )
    drawCircle(color = LogicColors.NeonCyan, radius = radius, center = head)
    drawCircle(color = Color.White, radius = radius * 0.5f, center = head)
}

/**
 * Estallido de partículas de una recogida. Devuelve `false` cuando ya se apagó, y la lista de
 * estallidos usa ese valor para podarse: así no hace falta un temporizador aparte que limpie.
 *
 * Las chispas salen en radial con velocidad constante y se **frenan** con el tiempo (el factor
 * `1 − t²`): una explosión que se expande a ritmo uniforme se lee como artificial, mientras que
 * frenar imita la resistencia y remata el momento.
 */
private fun DrawScope.drawBurst(burst: OrbBurst, transform: BoardTransform, frameNanos: Long): Boolean {
    val elapsed = (frameNanos - burst.startNanos) / 1_000_000_000f
    if (elapsed < 0f || elapsed > BURST_DURATION_SEC) return false

    val t = elapsed / BURST_DURATION_SEC
    val center = transform.toScreen(burst.world)
    val spread = transform.radiusPx * BURST_SPREAD_FACTOR * (1f - (1f - t) * (1f - t))
    val alpha = (1f - t) * (1f - t)

    for (i in 0 until BURST_PARTICLES) {
        val angle = i * (2f * PI_F / BURST_PARTICLES)
        drawCircle(
            color = LogicColors.Amber.copy(alpha = alpha),
            radius = transform.radiusPx * 0.05f * (1f - t),
            center = Offset(center.x + cos(angle) * spread, center.y + sin(angle) * spread),
        )
    }
    // Onda de choque: un anillo que se abre y se apaga, para que la recogida se lea aunque las
    // chispas queden sobre un tramo ya iluminado del haz.
    drawCircle(
        color = LogicColors.Amber.copy(alpha = alpha * 0.5f),
        radius = spread,
        center = center,
        style = Stroke(width = transform.radiusPx * 0.03f),
    )
    return true
}

// ---------------------------------------------------------------------------------------------
// Utilidades de dibujo
// ---------------------------------------------------------------------------------------------

/**
 * Dibuja una [HexCurve] como `Path.cubicTo` sobre el `Canvas`.
 *
 * @param coord azulejo al que pertenece; da el desplazamiento y el pivote del giro.
 * @param rotationDeg giro extra (grados, horario) que se aplica alrededor del centro del
 *   azulejo. Es lo que permite dibujar la pieza "llegando" a su rotación final.
 * @param widthFactor grosor del trazo como fracción del radio del hexágono.
 * @param absolute `true` si la curva ya viene en coordenadas del tablero
 *   ([HexGeometry.orientedCurve]); `false` si es local al azulejo ([HexGeometry.curveFor]).
 */
private fun DrawScope.drawCurve(
    curve: HexCurve,
    coord: HexCoord,
    transform: BoardTransform,
    rotationDeg: Float,
    color: Color,
    widthFactor: Float,
    alpha: Float,
    absolute: Boolean = false,
) {
    if (alpha <= 0.004f) return
    val origin = HexGeometry.center(coord)
    val pivot = transform.toScreen(origin)

    fun project(point: HexPoint): Offset {
        val world = if (absolute) point else point + origin
        val screen = transform.toScreen(world)
        if (rotationDeg == 0f) return screen
        // Rotación en pantalla alrededor del centro del azulejo (Y hacia abajo → horario).
        val rad = rotationDeg * PI_F / 180f
        val dx = screen.x - pivot.x
        val dy = screen.y - pivot.y
        return Offset(
            x = pivot.x + dx * cos(rad) - dy * sin(rad),
            y = pivot.y + dx * sin(rad) + dy * cos(rad),
        )
    }

    val start = project(curve.start)
    val c1 = project(curve.control1)
    val c2 = project(curve.control2)
    val end = project(curve.end)

    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = alpha),
        style = Stroke(width = transform.radiusPx * widthFactor, cap = StrokeCap.Round),
    )
}

/**
 * Progreso `0..1` del giro de la pieza [coord], con la elasticidad ya aplicada. Vale `1f`
 * (reposo) si la pieza no ha girado nunca o si su animación ya terminó.
 */
private fun spinProgress(coord: HexCoord, frameNanos: Long, spinStart: Map<HexCoord, Long>): Float {
    val start = spinStart[coord] ?: return 1f
    val elapsed = (frameNanos - start) / 1_000_000_000f
    if (elapsed <= 0f) return 0f
    if (elapsed >= SPIN_DURATION_SEC) return 1f
    return EaseOutBack.transform(elapsed / SPIN_DURATION_SEC)
}

/** Estallido de partículas de una recogida, anclado al mundo y al frame en que ocurrió. */
private data class OrbBurst(val world: HexPoint, val startNanos: Long)

// ---------------------------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------------------------

/**
 * Marcador superior: puntos, orbes recogidos y rapidez actual.
 *
 * La rapidez se muestra como un múltiplo de la inicial (`×1.4`) y no en radios por segundo: al
 * jugador no le dice nada la unidad interna, pero sí "voy a 1,4 veces la velocidad de salida",
 * que es la lectura de tensión que la rampa quiere transmitir.
 */
@Composable
private fun HexaOrbitHud(game: HexaOrbitState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Hexa Orbit",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(
                if (game.projection.escapes) Res.string.hexa_orbit_warning else Res.string.hexa_orbit_subtitle,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (game.projection.escapes) LogicColors.Error else LogicColors.OnDarkMuted,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            HexaOrbitHudPill(
                label = stringResource(Res.string.hexa_orbit_hud_score),
                value = game.score.toString(),
            )
            HexaOrbitHudPill(
                label = stringResource(Res.string.hexa_orbit_hud_orbs),
                value = game.collected.toString(),
                modifier = Modifier.padding(start = 8.dp),
            )
            HexaOrbitHudPill(
                label = stringResource(Res.string.hexa_orbit_hud_speed),
                value = "×${speedMultiplierLabel(game.speed)}",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Píldora del HUD (etiqueta + valor), mismo lenguaje visual que el resto de juegos. */
@Composable
private fun HexaOrbitHudPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark)
    }
}

/**
 * Multiplicador de rapidez con un decimal, sin `String.format` (no existe en `commonMain`):
 * se redondea a décimas con aritmética entera y se compone el texto a mano.
 */
private fun speedMultiplierLabel(speed: Float): String {
    val tenths = ((speed / HexaOrbitBalance.INITIAL_SPEED) * 10f).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

// ---------------------------------------------------------------------------------------------
// Constantes de render
// ---------------------------------------------------------------------------------------------

/** `√3`: anchura de un hexágono pointy-top respecto a su radio (ver [BoardTransform.of]). */
private const val SQRT_3 = 1.7320508f

/** π en `Float`, para no promover a `Double` en cada rotación de punto. */
private const val PI_F = 3.1415927f

/** Fracción del lienzo que ocupa el tablero; el resto es margen para el HUD y el halo. */
private const val BOARD_FILL = 0.94f

/** Grados que recorre el giro de una pieza (60° = un paso de arista). */
private const val SPIN_DEGREES = 60f

/** Duración del giro: dentro de los 100-250 ms de micro-feedback de la §9.4. */
private const val SPIN_DURATION_SEC = 0.22f

/** Radio del orbe recolectable como fracción del radio del hexágono. */
private const val ORB_RADIUS_FACTOR = 0.16f

/** Radio del puntero como fracción del radio del hexágono. */
private const val POINTER_RADIUS_FACTOR = 0.13f

/** Duración del estallido de recogida. */
private const val BURST_DURATION_SEC = 0.5f

/** Alcance de las chispas, en fracción del radio del hexágono. */
private const val BURST_SPREAD_FACTOR = 0.9f

/** Chispas por estallido: suficientes para leerse como explosión, pocas para no ensuciar. */
private const val BURST_PARTICLES = 10
