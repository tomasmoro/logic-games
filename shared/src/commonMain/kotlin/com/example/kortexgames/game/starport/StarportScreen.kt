package com.example.kortexgames.game.starport

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.game.GameHelpContent
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.LevelStripState
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.softGlow
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.starport_meteor_2
import kortexgames.shared.generated.resources.starport_meteor_3
import kortexgames.shared.generated.resources.starport_vip_ship
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

// --- Constantes de composición del hangar -------------------------------------

/** Aire entre la cápsula y el borde de sus celdas (fracción del tamaño de celda). */
private const val SHIP_INSET_FRACTION = 0.07f

/** Celdas que recorre la VIP al salir por la esclusa (suficiente para salir de cuadro). */
private const val EXIT_FLY_CELLS = 8f

/** Duración del vuelo de escape (easing acelerado = despegue). */
private const val EXIT_ANIM_MS = 650

/** Longitud de la estela de propulsión, en celdas. */
private const val TRAIL_CELLS = 0.9f

/**
 * Pantalla de "Neon Starport Escape".
 *
 * Estructura idéntica a los demás juegos LEVELED: antesala con selector de
 * niveles ([GameIntroScreen]) → hangar a pantalla completa → [GameOverOverlay].
 *
 * El hangar NO es un Canvas monolítico: cada nave es un composable con su
 * propio `Animatable` que **persigue la posición objetivo del motor** (§9.4,
 * animación dirigida por estado). Mientras se arrastra, la persecución es
 * instantánea (`snapTo`, la nave sigue al dedo sin goma elástica); al soltar,
 * el motor publica la celda snapeada y la nave se acopla con un `spring`
 * rápido — ese rebote ES el feedback de "encaje" pedido por diseño.
 *
 * La UI solo traduce px→celdas y proyecta el gesto sobre el eje de la nave
 * (geometría de layout); TODA la física (clamping, snap, choques, victoria)
 * la decide el motor vía intents. Ver el invariante en `StarportModel.kt`.
 */
@Composable
fun StarportScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: StarportViewModel = viewModel {
        StarportViewModel(graph.progressRepository, graph.playerProgressRepository, graph.audio, graph.adManager)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto donde los Effects se vuelven sonido/vibración.
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            when (effect) {
                is StarportEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is StarportEffect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                // El vuelo de salida lo dirige el estado (vipEscaped → animación
                // en ShipCapsule); el efecto queda para ganchos futuros.
                StarportEffect.ShowExitAnim -> Unit
            }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        GameIntroScreen(
            help = GameHelpContent.starport,
            title = "Neon Starport Escape",
            description = "El hangar está colapsado. Desliza cada nave a lo largo de su eje y despeja el camino para que la nave insignia escape por la esclusa.",
            accent = CategoryPalette.Logic,
            icon = Icons.Rounded.RocketLaunch,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            onStart = { vm.onIntent(StarportIntent.PlayLevel(selectedLevel)) },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    val game = state.game

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            StarportHud(
                level = state.currentLevel,
                moves = game.moves,
                onRestart = { vm.onIntent(StarportIntent.Restart) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StarportBoard(
                    game = game,
                    onIntent = vm::onIntent,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nave insignia liberada!",
                onPlayAgain = { vm.onIntent(StarportIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(StarportIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(StarportIntent.ChooseLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(StarportIntent.Pause) },
            onResume = { vm.onIntent(StarportIntent.Resume) },
            onExit = onExit,
            gameTitle = "Neon Starport Escape",
            help = GameHelpContent.starport,
            accent = CategoryPalette.Logic,
        )
    }
}

/** HUD superior: título, píldoras de nivel/movimientos y reinicio. */
@Composable
private fun StarportHud(
    level: Int,
    moves: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Neon Starport Escape",
            style = MaterialTheme.typography.headlineSmall,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPill(label = "Nivel", value = level.toString())
            HudPill(label = "Movimientos", value = moves.toString())
            Box(
                modifier = Modifier
                    .bounceClick(onClick = onRestart)
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = KortexIcons.Refresh,
                    contentDescription = "Reiniciar nivel",
                    tint = LogicColors.OnDarkMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun HudPill(label: String, value: String, modifier: Modifier = Modifier) {
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
 * El hangar (de 6×6 a 10×10 según el nivel): rejilla + esclusa dibujadas en un
 * Canvas de fondo, y una capa de naves-composable encima. `BoxWithConstraints`
 * fija la equivalencia celda↔px que comparten dibujo, posicionamiento y
 * traducción de gestos.
 */
@Composable
private fun StarportBoard(
    game: StarportGameState,
    onIntent: (StarportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cellDp: Dp = maxWidth / game.hangarSize
        val cellPx: Float = with(LocalDensity.current) { cellDp.toPx() }

        HangarBackdrop(exit = game.exit, hangarSize = game.hangarSize, modifier = Modifier.fillMaxSize())

        // key(id): la identidad de composición sigue a la nave, no a su índice,
        // para que los Animatable no "salten" de una nave a otra entre niveles.
        for (ship in game.ships) {
            key(ship.id) {
                ShipCapsule(
                    ship = ship,
                    drag = game.drag?.takeIf { it.shipId == ship.id },
                    escaping = game.vipEscaped && ship.isVip,
                    exit = game.exit,
                    cellDp = cellDp,
                    cellPx = cellPx,
                    onIntent = onIntent,
                )
            }
        }
    }
}

/**
 * Fondo del hangar: placa oscura redondeada, rejilla sutil y la **esclusa**
 * (única mancha verde del tablero: señala la meta sin necesidad de texto,
 * regla de "acento escaso" de §9.1).
 */
@Composable
private fun HangarBackdrop(exit: HangarExit, hangarSize: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cell = size.width / hangarSize
        val corner = CornerRadius(24.dp.toPx())

        drawRoundRect(color = LogicColors.SurfaceDark.copy(alpha = 0.62f), cornerRadius = corner)

        // Rejilla interior: guía la lectura de celdas sin competir con las naves.
        val gridColor = LogicColors.SurfaceVariantDark.copy(alpha = 0.55f)
        for (i in 1 until hangarSize) {
            drawLine(gridColor, Offset(i * cell, 0f), Offset(i * cell, size.height), strokeWidth = 1.5f)
            drawLine(gridColor, Offset(0f, i * cell), Offset(size.width, i * cell), strokeWidth = 1.5f)
        }

        // Marco exterior frío (detalle NeonCyan, apagado para no robar foco).
        drawRoundRect(
            color = LogicColors.NeonCyan.copy(alpha = 0.28f),
            cornerRadius = corner,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )

        // Esclusa: barra verde con halo en el borde correspondiente. El halo se
        // pinta con capas concéntricas de alpha decreciente (glow barato en
        // Canvas, sin shaders específicos de plataforma).
        val gate = 6.dp.toPx()
        val (gateTopLeft, gateSize) = when (exit.side) {
            ExitSide.RIGHT -> Offset(size.width - gate, exit.index * cell + cell * 0.12f) to
                Size(gate, cell * 0.76f)
            ExitSide.LEFT -> Offset(0f, exit.index * cell + cell * 0.12f) to
                Size(gate, cell * 0.76f)
            ExitSide.TOP -> Offset(exit.index * cell + cell * 0.12f, 0f) to
                Size(cell * 0.76f, gate)
            ExitSide.BOTTOM -> Offset(exit.index * cell + cell * 0.12f, size.height - gate) to
                Size(cell * 0.76f, gate)
        }
        for (layer in 3 downTo 1) {
            val spread = layer * 4.dp.toPx()
            drawRoundRect(
                color = LogicColors.NeonGreen.copy(alpha = 0.10f * (4 - layer)),
                topLeft = gateTopLeft - Offset(spread, spread),
                size = Size(gateSize.width + spread * 2, gateSize.height + spread * 2),
                cornerRadius = CornerRadius(gate),
            )
        }
        drawRoundRect(
            color = LogicColors.NeonGreen,
            topLeft = gateTopLeft,
            size = gateSize,
            cornerRadius = CornerRadius(gate),
        )
    }
}

/**
 * Una nave del hangar: cápsula redondeada que se arrastra por su eje.
 *
 *  - **Posición**: un [Animatable] en celdas persigue el objetivo del motor.
 *    Arrastrando usa `snapTo` (seguir al dedo, sin lag); al soltar, `spring`
 *    rígido y poco rebotón — el "clac" de acoplarse a la cuadrícula.
 *  - **Gesto**: `detectDragGestures` proyecta el delta sobre el eje de la nave
 *    (la componente perpendicular se descarta aquí, por contrato del intent) y
 *    lo reporta en celdas; el motor clampa y publica el offset resultante.
 *  - **Escape**: cuando el motor marca la victoria, la VIP acelera
 *    [EXIT_FLY_CELLS] a través de la esclusa desvaneciéndose y al terminar se
 *    lo confirma al dominio ([StarportIntent.ExitAnimFinished]).
 *  - **Estela**: un degradado corto detrás de la nave, solo mientras hay
 *    movimiento real (arrastre o escape), en la dirección opuesta al avance.
 */
@Composable
private fun ShipCapsule(
    ship: Ship,
    drag: ShipDrag?,
    escaping: Boolean,
    exit: HangarExit,
    cellDp: Dp,
    cellPx: Float,
    onIntent: (StarportIntent) -> Unit,
) {
    val horizontal = ship.orientation == Orientation.HORIZONTAL
    val isDragging = drag != null

    // Posición a lo largo del eje, en celdas (col si horizontal, row si vertical).
    val targetAxis = ship.axisPosition + (drag?.axisOffset ?: 0f)
    val axisAnim = remember { Animatable(targetAxis) }
    LaunchedEffect(targetAxis, isDragging) {
        if (isDragging) {
            axisAnim.snapTo(targetAxis)
        } else {
            // Snap-to-grid: rápido y con un pelín de rebote (se nota el encaje).
            axisAnim.animateTo(targetAxis, spring(dampingRatio = 0.62f, stiffness = 900f))
        }
    }

    // Vuelo de escape de la VIP: acelerado (despegue) y de un solo disparo.
    val exitFly = remember { Animatable(0f) }
    val onIntentCurrent by rememberUpdatedState(onIntent)
    LaunchedEffect(escaping) {
        if (escaping) {
            exitFly.animateTo(EXIT_FLY_CELLS, tween(EXIT_ANIM_MS, easing = FastOutLinearInEasing))
            onIntentCurrent(StarportIntent.ExitAnimFinished)
        } else {
            // Rebobina el vuelo de escape. La MISMA cápsula VIP se reutiliza entre
            // niveles (key(ship.id)), así que su `exitFly` queda parado en
            // EXIT_FLY_CELLS tras un escape; sin este reset, al pasar al siguiente
            // nivel (o rejugar) la VIP reaparecería fuera de cuadro e invisible
            // (escapeAlpha=0). snapTo la devuelve a su celda, visible.
            exitFly.snapTo(0f)
        }
    }
    // Sentido del vuelo según el borde de la esclusa (−1 hacia arriba/izquierda).
    val exitSign = when (exit.side) {
        ExitSide.RIGHT, ExitSide.BOTTOM -> 1f
        ExitSide.LEFT, ExitSide.TOP -> -1f
    }

    // Dirección del último avance (−1/0/+1) para orientar la estela. Estado de
    // presentación puro: al motor no le importa hacia dónde miró el gesto.
    var motionSign by remember { mutableFloatStateOf(0f) }

    val axisCells = axisAnim.value + exitFly.value * exitSign
    val xCells = if (horizontal) axisCells else ship.col.toFloat()
    val yCells = if (horizontal) ship.row.toFloat() else axisCells

    // La nave levantada "flota": crece un 6% y proyecta más halo (§9.4 táctil).
    val liftScale by animateFloatAsState(
        targetValue = if (isDragging) 1.06f else 1f,
        animationSpec = spring(stiffness = 1200f),
        label = "liftScale",
    )
    // Desvanecimiento del tramo final del vuelo (sale "a hiperespacio").
    val escapeAlpha = 1f - (exitFly.value / EXIT_FLY_CELLS).coerceIn(0f, 1f)

    val accent = if (ship.isVip) LogicColors.NeonGreen else LogicColors.NeonCyan
    val shape = RoundedCornerShape(percent = 50) // cápsula: extremos totalmente curvos
    val inset = cellDp * SHIP_INSET_FRACTION

    // Arte de la VIP: el sprite nace con la proa hacia arriba, así que se rota
    // hasta apuntar al lado de la esclusa por la que escapa (§9.4: coherencia
    // física del gesto — la nave "mira" hacia donde va a salir). Cuando la
    // rotación es de 90/270° el eje visual se intercambia, así que el tamaño
    // pre-rotación también se intercambia para que, tras rotar, encaje exacto
    // en el hueco de la celda (sin recorte ni deformación).
    val vipRotation = when (exit.side) {
        ExitSide.TOP -> 0f
        ExitSide.BOTTOM -> 180f
        ExitSide.RIGHT -> 90f
        ExitSide.LEFT -> -90f
    }
    // Mismo cálculo de caja para la VIP y los meteoritos-obstáculo: ambos son
    // sprites que nacen en vertical y deben caer exactos en el hueco de la
    // celda tras rotar (si la nave/obstáculo es horizontal, ancho y alto se
    // intercambian pre-rotación). `width` cubre los meteoritos anchos (2×2,
    // 3×2), que ocupan más de un carril perpendicular al eje de movimiento.
    val shipBoxWidth = cellDp * (if (horizontal) ship.length else ship.width) - inset * 2
    val shipBoxHeight = cellDp * (if (horizontal) ship.width else ship.length) - inset * 2
    val imageWidth = if (horizontal) shipBoxHeight else shipBoxWidth
    val imageHeight = if (horizontal) shipBoxWidth else shipBoxHeight
    // Los obstáculos no tienen un "frente" que deba mirar a ningún lado (a
    // diferencia de la VIP): solo se rotan 90° para que su eje largo, nacido
    // vertical, se acueste sobre el eje horizontal cuando la nave es H.
    val meteorRotation = if (horizontal) 90f else 0f
    // Sprite según la huella: la roca corta para piezas de 2 de largo, la
    // grande para 3 y 4 (FillBounds la estira ese pelín extra sin despeinarse:
    // es roca amorfa, no hay proporciones "correctas" que respetar).
    val meteorRes = if (ship.length == SHIP_LENGTH_SHORT) Res.drawable.starport_meteor_2 else Res.drawable.starport_meteor_3

    Box(
        modifier = Modifier
            .offset { IntOffset((xCells * cellPx).roundToInt(), (yCells * cellPx).roundToInt()) }
            .size(
                width = cellDp * (if (horizontal) ship.length else ship.width),
                height = cellDp * (if (horizontal) ship.width else ship.length),
            )
            .padding(inset)
            .graphicsLayer {
                scaleX = liftScale
                scaleY = liftScale
                alpha = escapeAlpha
            }
            // Estela de propulsión: degradado corto que nace del "motor" (lado
            // opuesto al avance). Solo con movimiento real: nada de bucles.
            .drawBehind {
                val moving = isDragging && motionSign != 0f
                val sign = if (escaping) exitSign else motionSign
                if (!moving && !escaping) return@drawBehind
                val trailLen = TRAIL_CELLS * cellPx * (if (escaping) 1.6f else 1f)
                val trailAlpha = if (escaping) 0.55f else 0.35f
                val across = (if (horizontal) size.height else size.width) * 0.5f
                val start = if (sign > 0f) 0f else (if (horizontal) size.width else size.height)
                val colors =
                    if (sign > 0f) listOf(Color.Transparent, accent.copy(alpha = trailAlpha))
                    else listOf(accent.copy(alpha = trailAlpha), Color.Transparent)
                if (horizontal) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(colors, startX = start - sign * trailLen, endX = start),
                        topLeft = Offset(start - (if (sign > 0f) trailLen else 0f), size.height * 0.25f),
                        size = Size(trailLen, across),
                        cornerRadius = CornerRadius(across / 2f),
                    )
                } else {
                    drawRoundRect(
                        brush = Brush.verticalGradient(colors, startY = start - sign * trailLen, endY = start),
                        topLeft = Offset(size.width * 0.25f, start - (if (sign > 0f) trailLen else 0f)),
                        size = Size(across, trailLen),
                        cornerRadius = CornerRadius(across / 2f),
                    )
                }
            }
            // El glow continuo queda RESERVADO a la VIP: es el "premio" visual
            // que guía el objetivo (§9.4: un solo foco de atención en bucle).
            .then(if (ship.isVip) Modifier.softGlow(accent, shape) else Modifier)
            // Tanto la VIP como los obstáculos son ahora sprites con su propio
            // arte (casco+ventanilla / roca fracturada): ninguno necesita ya
            // la cápsula procedural (relleno + borde + "ventanilla") que se
            // dibujaba antes para las naves normales — se vería duplicado.
            .pointerInput(ship.id, cellPx, horizontal) {
                detectDragGestures(
                    onDragStart = { onIntent(StarportIntent.SelectShip(ship.id)) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Proyección sobre el eje: se descarta la componente
                        // perpendicular ANTES del intent (contrato de DragShip).
                        val axisDeltaPx = if (horizontal) dragAmount.x else dragAmount.y
                        if (abs(axisDeltaPx) > 0.01f) motionSign = if (axisDeltaPx > 0f) 1f else -1f
                        onIntent(StarportIntent.DragShip(ship.id, axisDeltaPx / cellPx))
                    },
                    onDragEnd = {
                        motionSign = 0f
                        onIntent(StarportIntent.ReleaseShip(ship.id))
                    },
                    onDragCancel = {
                        motionSign = 0f
                        onIntent(StarportIntent.ReleaseShip(ship.id))
                    },
                )
            },
    ) {
        Image(
            painter = painterResource(if (ship.isVip) Res.drawable.starport_vip_ship else meteorRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.Center)
                // requiredSize (no size): el tamaño pre-rotación es PORTRAIT
                // (más alto que ancho) y excede el máximo que el padre le
                // pasa como constraint (su alto es de 1 celda); size() lo
                // recortaría a ese máximo antes de rotar. requiredSize
                // ignora el constraint del padre y fuerza el tamaño real;
                // tras rotar 90°, el resultado SÍ encaja en el padre.
                .requiredSize(width = imageWidth, height = imageHeight)
                .graphicsLayer { rotationZ = if (ship.isVip) vipRotation else meteorRotation },
        )
    }
}
