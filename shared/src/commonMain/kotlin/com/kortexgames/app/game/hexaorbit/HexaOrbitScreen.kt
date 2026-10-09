package com.kortexgames.app.game.hexaorbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.events.EventExitConfirmDialog
import com.kortexgames.app.ui.events.EventRulesPanel
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_intro_label
import kortexgames.shared.generated.resources.hexa_orbit_hud_orbs
import kortexgames.shared.generated.resources.hexa_orbit_hud_score
import kortexgames.shared.generated.resources.hexa_orbit_hud_speed
import kortexgames.shared.generated.resources.hexa_orbit_intro_description
import kortexgames.shared.generated.resources.hexa_orbit_revive_body
import kortexgames.shared.generated.resources.hexa_orbit_revive_reward
import kortexgames.shared.generated.resources.hexa_orbit_revive_title
import kortexgames.shared.generated.resources.hexa_orbit_subtitle
import kortexgames.shared.generated.resources.hexa_orbit_warning
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
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
 * Los [HexaOrbitBalance.LOOKAHEAD_TILES] azulejos que vienen se dibujan como un tubo de luz que
 * **se apaga hacia el final**: el tramo actual va a intensidad plena y la cola queda tenue pero
 * legible. El desvanecido no es decorativo — comunica *cuánto falta*, que es la información con
 * la que el jugador decide qué girar primero.
 *
 * La alarma roja NO se enciende con cualquier fuga dentro del haz, sino solo con las
 * **inminentes** ([LookaheadPath.imminent], dentro de [HexaOrbitBalance.ESCAPE_ALERT_TILES]).
 * Con un horizonte de nueve azulejos sobre un tablero de tres anillos, el haz toca la frontera
 * casi siempre: una alarma atada a eso estaría encendida de forma permanente y dejaría de
 * informar. Las fugas lejanas solo tiñen su último tramo.
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
    // ¿Esta corrida es de torneo? Se resuelve UNA vez al montar: si la sesión se cerrara a
    // mitad de partida, la que está en curso debe seguir siendo la del torneo hasta terminar.
    val play = remember { graph.eventPlaySession.activeFor(GameIds.HEXA_ORBIT) }
    val event = play?.event
    val vm: HexaOrbitViewModel = viewModel(key = event?.id ?: VIEWMODEL_KEY_FREE_PLAY) {
        HexaOrbitViewModel(
            graph.progressRepository,
            graph.audio,
            event = event,
            events = graph.eventsRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Único punto de salida "en juego": fuera de torneo sale directo (ENDLESS, no hay corrida
    // que guardar); en torneo pide confirmación, porque salir gasta el intento.
    val exitInPlay: () -> Unit = { vm.requestExit(onExit) }

    // Aviso de abandono del torneo. Va antes que cualquier pantalla porque la salida se puede
    // pedir desde la partida o desde el menú de pausa, y es modal en ambos casos.
    if (state.showEventExitConfirm) {
        EventExitConfirmDialog(
            // Cupo REAL (incluye los intentos extra comprados con anuncios).
            attemptsLeftAfter = play?.attemptsAllowed?.let { allowed ->
                (allowed - play.attemptsUsed - 1).coerceAtLeast(0)
            },
            onConfirm = { vm.confirmEventExit(onExit) },
            onDismiss = { vm.dismissEventExit() },
        )
    }

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de física.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.hexaOrbit,
            tutorial = HexaOrbitTutorial.tutorial,
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
            configContent = if (event == null) {
                null
            } else {
                {
                    // En torneo, la antesala explica las reglas del evento antes de que el
                    // jugador gaste un intento. Hexa Orbit no tiene selector de dificultad,
                    // así que este hueco estaba libre.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(Res.string.event_intro_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = LogicColors.Amber,
                        )
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = LogicColors.OnDark,
                        )
                        EventRulesPanel(event = event, accent = CategoryPalette.SpatialVision)
                    }
                }
            },
        )
        return
    }

    // Reloj de frames compartido por física y animaciones (ver KDoc de la clase).
    var frameNanos by remember { mutableLongStateOf(0L) }

    // Momento en que cada pieza empezó a girar; alimenta el "spring" del giro sin Animatable.
    val spinStart = remember { mutableMapOf<HexCoord, Long>() }
    val lastRotation = remember { mutableMapOf<HexCoord, Int>() }

    // Estallidos de partículas y ondas de toque pendientes de dibujar (se podan al expirar).
    val bursts = remember { mutableStateListOf<OrbBurst>() }
    val ripples = remember { mutableStateListOf<TapRipple>() }

    // Origen del reloj de animación. Los nanos del frame son enormes (tiempo desde el
    // arranque del dispositivo): pasados a segundos en `Float` pierden precisión y las
    // animaciones por seno irían a saltos. Restando un origen propio el reloj arranca en 0.
    var clockOrigin by remember { mutableLongStateOf(0L) }
    // Frame en que arrancó la partida (entrada en cascada del tablero) y en que el puntero
    // se escapó (sacudida de pantalla).
    var introStart by remember { mutableLongStateOf(0L) }
    var shakeStart by remember { mutableLongStateOf(Long.MIN_VALUE) }

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                if (clockOrigin == 0L) {
                    clockOrigin = nanos
                    introStart = nanos
                }
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
    // arranque en reposo, y se relanza la entrada en cascada de las piezas.
    LaunchedEffect(state.status) {
        if (state.status == GameStatus.RUNNING && game.elapsedSeconds == 0f) {
            spinStart.clear()
            lastRotation.clear()
            bursts.clear()
            ripples.clear()
            introStart = frameNanos
        }
    }

    // Estallido al recoger: el efecto del motor es semántico y no lleva posición, así que se
    // busca qué orbe ha desaparecido de la lista (para estallar EN su sitio y con SU color).
    val previousOrbs = remember { mutableMapOf<Long, HexPoint>() }
    LaunchedEffect(game.collected, game.orbs) {
        val current = game.orbs.associate { it.id to it.position }
        if (game.collected > 0) {
            val gone = previousOrbs.keys.firstOrNull { it !in current }
            if (gone != null) {
                bursts += OrbBurst(previousOrbs.getValue(gone), frameNanos, orbColor(gone))
            }
        }
        previousOrbs.clear()
        previousOrbs.putAll(current)
    }

    // Fuga: el puntero revienta en rojo y la pantalla se sacude. Es el golpe que cierra la
    // partida (o abre la oferta de revivir): sin él, el overlay aparecía "porque sí".
    LaunchedEffect(game.escaped) {
        if (game.escaped) {
            shakeStart = frameNanos
            bursts += OrbBurst(game.pointer.position, frameNanos, LogicColors.Error, big = true)
        }
    }

    // Alarma de fuga inminente, suavizada: entra rápido (es urgente) y sale algo más lenta.
    val danger by animateFloatAsState(
        targetValue = if (game.projection.imminent && state.status == GameStatus.RUNNING) 1f else 0f,
        animationSpec = tween(durationMillis = if (game.projection.imminent) 120 else 320),
        label = "hexaOrbitDanger",
    )

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Sacudida de la fuga: oscilación rápida que se amortigua. Va en la capa (no en
                // el dibujo) para que mueva el tablero entero sin recalcular nada.
                .graphicsLayer {
                    val t = (frameNanos - shakeStart) / 1_000_000_000f
                    if (shakeStart != Long.MIN_VALUE && t >= 0f && t < SHAKE_DURATION_SEC) {
                        val decay = 1f - t / SHAKE_DURATION_SEC
                        val amp = with(density) { 9.dp.toPx() } * decay * decay
                        translationX = sin(t * 85f) * amp
                        translationY = cos(t * 67f) * amp * 0.6f
                    }
                }
                .pointerInput(game.board.radius) {
                    detectTapGestures { tap ->
                        // Píxel → mundo → celda. El redondeo cúbico de [HexGeometry.hexAt] es lo
                        // que hace que un toque cerca de un vértice acierte la celda correcta.
                        val transform = BoardTransform.of(size.width.toFloat(), size.height.toFloat())
                        val coord = transform.hexAt(tap)
                        // Onda inmediata en la pieza tocada (solo si el toque cayó en el tablero).
                        if (coord.ring <= HexaOrbitBalance.BOARD_RADIUS) ripples += TapRipple(coord, frameNanos)
                        vm.onIntent(HexaOrbitIntent.RotateTile(coord))
                    }
                },
        ) {
            val transform = BoardTransform.of(size.width, size.height)
            val time = (frameNanos - clockOrigin) / 1_000_000_000f
            val introSec = (frameNanos - introStart) / 1_000_000_000f

            drawBoardAmbience(transform, time, danger)
            drawBoardCells(game, transform, frameNanos, spinStart, introSec)
            drawIdlePaths(game, transform, frameNanos, spinStart, introSec)
            ripples.removeAll { ripple -> drawTapRipple(ripple, transform, frameNanos).not() }
            drawProjectedBeam(game, transform)
            drawEnergyOrbs(game, transform, time)
            // El puntero desaparece al escaparse: lo sustituye su estallido.
            if (!game.escaped) drawPointer(game, transform, time, danger)

            // Las partículas van encima de todo: son la recompensa y no deben quedar tapadas.
            bursts.removeAll { burst -> drawBurst(burst, transform, frameNanos).not() }
            drawDangerVignette(danger, time)
        }

        HexaOrbitHud(game = game, danger = danger, modifier = Modifier.align(Alignment.TopCenter))

        // Oferta de revivir viendo un anuncio: el motor congeló la partida; aquí solo se decide.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(HexaOrbitIntent.Revive) },
                onDecline = { vm.onIntent(HexaOrbitIntent.DeclineRevive) },
                title = stringResource(Res.string.hexa_orbit_revive_title),
                rewardLabel = stringResource(Res.string.hexa_orbit_revive_reward),
                body = stringResource(Res.string.hexa_orbit_revive_body),
                accent = CategoryPalette.SpatialVision,
                audio = graph.audio,
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(HexaOrbitIntent.RestartGame) },
                onExit = onExit,
                accent = CategoryPalette.SpatialVision,
                // En torneo, volver a jugar se decide en la pantalla del torneo: es la
                // que sabe cuántos intentos quedan y la que ofrece el anuncio.
                singleBackCta = event != null,
            )
        }

        // Atrás del sistema: SOLO en torneo. Fuera de él, Hexa Orbit nunca tuvo guardia de
        // salida (es ENDLESS y salir no cuesta nada), y añadirla ahora cambiaría el
        // comportamiento de siempre. `confirmsExternally` evita encadenar dos diálogos: el
        // aviso de abandono lo pone el propio juego.
        if (event != null) {
            GameExitGuard(
                status = state.status,
                onResume = { vm.onIntent(HexaOrbitIntent.Resume) },
                onConfirmExit = exitInPlay,
                accent = CategoryPalette.SpatialVision,
                confirmsExternally = true,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HexaOrbitIntent.Pause) },
            onResume = { vm.onIntent(HexaOrbitIntent.Resume) },
            onExit = exitInPlay,
            gameTitle = "Hexa Orbit",
            help = GameHelpContent.hexaOrbit,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------------------------

/**
 * Marcador superior: la **puntuación en grande** (es lo que el jugador persigue), y debajo los
 * orbes recogidos y la rapidez actual con su indicador.
 *
 * La rapidez se muestra como un múltiplo de la inicial (`×1.4`) y no en radios por segundo: al
 * jugador no le dice nada la unidad interna, pero sí "voy a 1,4 veces la velocidad de salida".
 * La barra bajo el número la traduce a un vistazo: se llena y vira de cian a ámbar y a rojo a
 * medida que se acerca al tope, que es la lectura de tensión que la rampa quiere transmitir.
 *
 * @param danger 0..1, alarma de fuga inminente: convierte la línea de ayuda en aviso.
 */
@Composable
private fun HexaOrbitHud(game: HexaOrbitState, danger: Float, modifier: Modifier = Modifier) {
    // "Pop" de la puntuación al sumar: la recompensa también se nota en el marcador.
    val scorePop = remember { Animatable(1f) }
    LaunchedEffect(game.score) {
        if (game.score > 0) {
            scorePop.snapTo(1.35f)
            scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val orbPop = remember { Animatable(1f) }
    LaunchedEffect(game.collected) {
        if (game.collected > 0) {
            orbPop.snapTo(1.4f)
            orbPop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val speedFraction = ((game.speed - HexaOrbitBalance.INITIAL_SPEED) /
        (HexaOrbitBalance.MAX_SPEED - HexaOrbitBalance.INITIAL_SPEED)).coerceIn(0f, 1f)
    val speedColor = if (speedFraction < 0.5f) {
        lerp(LogicColors.NeonCyan, LogicColors.Amber, speedFraction * 2f)
    } else {
        lerp(LogicColors.Amber, LogicColors.Error, (speedFraction - 0.5f) * 2f)
    }

    Column(
        modifier = modifier.padding(top = 14.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.hexa_orbit_hud_score).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = LogicColors.OnDarkMuted,
        )
        Text(
            text = game.score.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = LogicColors.OnDark,
            modifier = Modifier.graphicsLayer {
                scaleX = scorePop.value
                scaleY = scorePop.value
            },
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexaOrbitHudPill(accent = LogicColors.Amber) {
                // Mini gema: el mismo rombo de los orbes del tablero, para que se asocien.
                Canvas(
                    Modifier.size(14.dp).graphicsLayer {
                        scaleX = orbPop.value
                        scaleY = orbPop.value
                    },
                ) {
                    val gem = Path().apply {
                        moveTo(center.x, 0f)
                        lineTo(size.width * 0.9f, center.y)
                        lineTo(center.x, size.height)
                        lineTo(size.width * 0.1f, center.y)
                        close()
                    }
                    drawPath(gem, LogicColors.Amber)
                }
                Text(
                    text = game.collected.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.hexa_orbit_hud_orbs),
                    style = MaterialTheme.typography.labelSmall,
                    color = LogicColors.OnDarkMuted,
                )
            }
            HexaOrbitHudPill(accent = speedColor) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "×${speedMultiplierLabel(game.speed)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = speedColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(Res.string.hexa_orbit_hud_speed),
                            style = MaterialTheme.typography.labelSmall,
                            color = LogicColors.OnDarkMuted,
                        )
                    }
                    // Barra de rapidez: pista tenue + tramo lleno con halo.
                    Canvas(Modifier.width(92.dp).height(4.dp)) {
                        val corner = CornerRadius(size.height / 2f)
                        drawRoundRect(LogicColors.SurfaceVariantDark, cornerRadius = corner)
                        val filled = Size(size.width * (0.06f + 0.94f * speedFraction), size.height)
                        drawRoundRect(
                            color = speedColor.copy(alpha = 0.35f),
                            topLeft = Offset(0f, -2.dp.toPx()),
                            size = Size(filled.width, size.height + 4.dp.toPx()),
                            cornerRadius = CornerRadius(size.height),
                        )
                        drawRoundRect(speedColor, size = filled, cornerRadius = corner)
                    }
                }
            }
        }
        // Ayuda / alarma. En alarma pasa a ser una píldora roja: se lee de reojo.
        val warning = danger > 0.5f
        Text(
            text = stringResource(if (warning) Res.string.hexa_orbit_warning else Res.string.hexa_orbit_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) LogicColors.OnDark else LogicColors.OnDarkMuted,
            fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .padding(top = 10.dp)
                .background(LogicColors.Error.copy(alpha = 0.85f * danger), CircleShape)
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}

/**
 * Píldora del HUD: superficie oscura con borde del color de su métrica. El contenido va en fila
 * (icono, valor, etiqueta), más compacto que la píldora de dos líneas anterior.
 */
@Composable
private fun HexaOrbitHudPill(accent: Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
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
// Constantes
// ---------------------------------------------------------------------------------------------

/** Duración de la sacudida de pantalla cuando el puntero se escapa. */
private const val SHAKE_DURATION_SEC = 0.45f

/**
 * Clave del ViewModel en partida libre. Se separa de la del torneo (`event.id`) para que entrar
 * a un torneo no reutilice el ViewModel de la partida normal —y al revés—: comparten pantalla,
 * pero son dos corridas con reglas distintas.
 */
private const val VIEWMODEL_KEY_FREE_PLAY = "libre"
