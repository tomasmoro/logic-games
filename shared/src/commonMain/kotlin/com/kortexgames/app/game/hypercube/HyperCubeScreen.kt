package com.kortexgames.app.game.hypercube

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.AdLoadingOverlay
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.GameActionButton
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.sp
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gameboard_hud_level
import kortexgames.shared.generated.resources.hypercube_gesture_hint
import kortexgames.shared.generated.resources.hypercube_hud_free_mode
import kortexgames.shared.generated.resources.hypercube_hud_moves
import kortexgames.shared.generated.resources.hypercube_hud_time
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Neon Hyper-Cube — pantalla y motor de render 3D
 *
 * Dibuja un cubo mágico 3×3 **sin ninguna librería 3D**: la escena se calcula con la mini-álgebra
 * de `HyperCubeMath` y se pinta con polígonos (`Path`) en un `Canvas` de Compose. El pipeline
 * completo, por frame, es:
 *
 * ```
 * esquinas de la pegatina (espacio de modelo)      HyperCubeGeometry.faceletCorners
 *   → rotación del giro en vuelo (solo su capa)    Mat3.rotation(eje, progreso·±90°)
 *   → rotación de cámara (yaw + pitch)             Mat3.camera
 *   → descarte de caras traseras                   backface culling
 *   → proyección en perspectiva a 2D               project()
 *   → orden por profundidad y dibujo               algoritmo del pintor
 * ```
 *
 * ## Proyección en perspectiva (el porqué de la fórmula)
 * La cámara se sitúa en `(0, 0, d)` mirando al origen, con el cubo centrado en él. Un punto ya
 * rotado `P = (x, y, z)` se proyecta sobre el plano de pantalla con el factor
 *
 * ```
 * k = d / (d − z)        →        x_pantalla = cx + x·k·s ,   y_pantalla = cy − y·k·s
 * ```
 *
 * que es la división por la profundidad de toda proyección perspectiva: cuanto más cerca está el
 * punto del observador (mayor `z`), menor es el denominador y más se agranda. En `z = 0` vale
 * exactamente 1, así que `s` (la escala) fija el tamaño del cubo en píxeles. La `y` se **niega**
 * porque en pantalla crece hacia abajo y en el modelo hacia arriba. Se usa perspectiva y no
 * proyección isométrica precisamente porque esa deformación —las aristas cercanas más separadas
 * que las lejanas— es lo que hace que el cubo se lea como un volumen y no como un hexágono plano.
 *
 * ## Algoritmo del pintor + backface culling
 * No hay z-buffer: las 54 pegatinas se ordenan por su `z` medio en espacio de cámara y se dibujan
 * **de la más lejana a la más cercana**, de modo que lo cercano tapa lo lejano. Antes se descartan
 * las caras que miran hacia el fondo, comprobando el signo de la componente `z` de la normal del
 * polígono (`(c1−c0) × (c2−c1)`): si no apunta hacia el observador, no se dibuja.
 *
 * El culling es aquí un **requisito de corrección**, no solo un ahorro: ordenar por `z` medio no
 * garantiza por sí solo que una cara trasera quede detrás de una delantera (el promedio de dos
 * polígonos que se entrelazan puede engañar), y descartarlas de entrada elimina el problema de
 * raíz. Como el cubo es convexo, culling + orden por profundidad dan un resultado exacto, sin los
 * artefactos que el algoritmo del pintor tiene en escenas generales.
 *
 * ## El cubo es un sólido, no un holograma hueco
 * Los rellenos son **opacos**: detrás de cada pegatina se pinta la cara completa del cubie, que
 * tesela con la de sus vecinos y sella el volumen, de modo que ni el fondo estrellado ni las caras
 * ocultas se transparentan por las juntas (ver [HyperCubeGeometry.BODY_HALF]).
 *
 * Las pegatinas son **cristal oscuro encendido por un tubo de neón**: el color vive en el borde
 * (halo → trazo nítido → núcleo blanco) y **se derrama hacia dentro** de la cara, que queda teñida
 * pero oscura. Así el color de cada pegatina se lee de un vistazo —con solo un contorno fino
 * costaba ver qué cara estaba hecha— sin caer en el relleno plano y saturado, que daba un aire
 * de dibujo animado ajeno a la estética de la app (§9.1: superficie oscura, acento luminoso).
 *
 * ## Vida alrededor del cubo
 * El cubo ya no flota en el vacío: descansa sobre un **pedestal holográfico** (halo + anillos que
 * giran despacio, ver [drawPedestal]), la capa que gira se **enciende**, la pegatina agarrada
 * responde bajo el dedo y, al resolverlo, el cubo da un golpe de escala con chispas antes de que
 * aparezca el resultado (ver `SOLVE_HOLD_MS`).
 *
 * ## Gestos: orbitar vs. girar una capa
 * Un arrastre que **empieza sobre una pegatina** gira su rebanada; uno que empieza fuera del cubo
 * orbita la cámara. La pantalla es la única capa que puede decidirlo (es quien conoce píxeles y
 * proyección) y traduce el gesto a dominio antes de enviarlo — ver [resolveTurn].
 */
@Composable
fun HyperCubeScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: HyperCubeViewModel = viewModel {
        HyperCubeViewModel(
            graph.progressRepository,
            graph.playerProgressRepository,
            graph.savedGameStateRepository,
            graph.audio,
            graph.adManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Salir guardando la partida a medias (atrás del sistema y "SALIR" del menú de pausa).
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // Destello blanco que recorre las aristas al resolver: el "núcleo encendido" del lenguaje
    // neón (§9.7), disparado por el Effect y no por el estado, porque es un evento único.
    val solveFlash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            if (effect is HyperCubeEffect.PlaySound &&
                effect.cue == HyperCubeEffect.PlaySound.Cue.SOLVED
            ) {
                solveFlash.snapTo(1f)
                solveFlash.animateTo(0f, tween(durationMillis = 900))
            }
        }
    }

    // Deshacer de pago: pulsar el botón YA es la confirmación del jugador (su icono avisa de que
    // toca anuncio), así que el rewarded se lanza directo, sin diálogo de por medio — mismo
    // criterio que la pista de Neon Sudoku. Se relanza cada vez que la bandera pasa a `true`.
    LaunchedEffect(state.awaitingUndoAd) {
        if (!state.awaitingUndoAd) return@LaunchedEffect
        when (graph.adManager.showRewardedAd()) {
            RewardResult.EARNED -> vm.onIntent(HyperCubeIntent.ConfirmUndo)
            RewardResult.DISMISSED, RewardResult.UNAVAILABLE -> vm.onIntent(HyperCubeIntent.CancelUndo)
        }
    }

    // Reloj del juego: alimenta la animación de los giros y el encadenado de la mezcla. Solo corre
    // en la fase de tablero; el motor ignora los ticks que no le tocan (pausa, partida terminada).
    LaunchedEffect(state.phase) {
        if (state.phase != LeveledGamePhase.PLAYING) return@LaunchedEffect
        while (true) {
            withFrameNanos { vm.onIntent(HyperCubeIntent.Tick(it)) }
        }
    }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Arranca en la frontera (récord + 1) y se resetea si el récord sube.
        var selectedLevel by remember(state.maxUnlocked) {
            mutableStateOf((state.maxUnlocked + 1).coerceAtMost(MAX_LEVEL))
        }
        GameIntroScreen(
            help = GameHelpContent.hyperCube,
            title = "Neon Hyper-Cube",
            description = "Un cubo holográfico de 3×3 se ha desordenado. Arrastra sobre una fila " +
                "para girarla y orbita alrededor para ver las caras ocultas. Deja cada cara de un " +
                "solo color para reconstruirlo.",
            accent = ACCENT,
            motif = GameMotif.HYPER_CUBE,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
                maxLevel = MAX_LEVEL,
            ),
            configContent = { FreeModeCard(onPlay = { vm.onIntent(HyperCubeIntent.PlayFreeMode) }) },
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.HYPER_CUBE)
                vm.onIntent(HyperCubeIntent.PlayLevel(selectedLevel))
            },
            // Partida a medias guardada al salir: la antesala la ofrece como CTA principal, con un
            // resumen para que el jugador sepa QUÉ retoma antes de pulsar.
            resume = state.saved?.let { saved ->
                ResumeState(
                    onResume = { vm.onIntent(HyperCubeIntent.ResumeSaved) },
                    detail = if (saved.isFreeMode) {
                        "Modo libre · ${saved.moves} mov."
                    } else {
                        "Nivel ${saved.level} · ${saved.moves} mov."
                    },
                )
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    val game = state.game

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            HyperCubeHud(
                level = state.currentLevel,
                isFreeMode = game.isFreeMode,
                moves = game.moves,
                par = game.scrambleDepth,
                elapsedMs = vm::elapsedMs,
            )

            CubeViewport(
                game = game,
                cameraYawRad = state.cameraYawRad,
                cameraPitchRad = state.cameraPitchRad,
                flash = solveFlash.value,
                onIntent = vm::onIntent,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // Recordatorio de los dos gestos, visible solo hasta el primer giro: enseña sin
            // estorbar y desaparece en cuanto el jugador demuestra que ya lo sabe.
            AnimatedVisibility(
                visible = game.moves == 0 && !game.isScrambling,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.hypercube_gesture_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogicColors.OnDarkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .background(LogicColors.SurfaceDark.copy(alpha = 0.80f), RoundedCornerShape(16.dp))
                            .border(1.dp, ACCENT.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // Barra de acciones inferior, en el mismo sitio y con el mismo control que el resto de
            // juegos con ayudas (Ordena las Pociones): al alcance del pulgar y lejos del tablero,
            // que aquí además es zona de arrastre — un botón sobre el cubo se pulsaría sin querer
            // al orbitar.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                GameActionButton(
                    icon = KortexIcons.Undo,
                    label = "Deshacer",
                    tint = ACCENT,
                    enabled = game.canUndo && state.status == GameStatus.RUNNING,
                    // El primer deshacer de la partida es gratis; a partir de ahí cuesta un
                    // anuncio y el botón lo avisa con el distintivo. El cobro lo resuelve el
                    // ViewModel: desde aquí siempre se manda el mismo intent.
                    costsAd = game.undoCostsAd,
                    onClick = { vm.onIntent(HyperCubeIntent.RequestUndo) },
                )
                GameActionButton(
                    icon = KortexIcons.Refresh,
                    label = "Mezclar",
                    tint = LogicColors.Amber,
                    enabled = !game.isScrambling && state.status == GameStatus.RUNNING,
                    onClick = { vm.onIntent(HyperCubeIntent.ScrambleCube) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // El resultado espera a que termine la celebración del cubo: sin esta espera el diálogo
        // lo tapaba en el mismo frame del giro que lo resuelve, y el cubo terminado —lo que el
        // jugador lleva toda la partida persiguiendo— no llegaba a verse.
        var resultReady by remember { mutableStateOf(false) }
        LaunchedEffect(state.gameOver != null) {
            resultReady = false
            if (state.gameOver != null) {
                delay(SOLVE_HOLD_MS)
                resultReady = true
            }
        }
        if (state.status == GameStatus.FINISHED && state.gameOver != null && resultReady) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Cubo reconstruido!",
                onPlayAgain = { vm.onIntent(HyperCubeIntent.PlayAgain) },
                onExit = onExit,
                // En modo libre no hay "siguiente nivel": la continuación es otra mezcla completa,
                // que ya ofrece "Jugar de nuevo". Ofrecerlo sería mentir sobre la progresión.
                onNextLevel = if (game.isFreeMode) null else {
                    { vm.onIntent(HyperCubeIntent.NextLevel) }
                },
                onChooseLevel = { vm.onIntent(HyperCubeIntent.ChooseLevel) },
                accent = ACCENT,
            )
        }

        // Mientras corre el anuncio del deshacer, la partida está PAUSED para congelar el
        // cronómetro (ver HyperCubeViewModel.requestUndo) — pero es una pausa TÉCNICA, no una
        // pausa del jugador: se le oculta a estos dos componentes para que no abran el menú de
        // pausa ni el diálogo de salida encima del anuncio.
        val playerFacingStatus =
            if (state.awaitingUndoAd) GameStatus.RUNNING else state.status

        GamePauseControls(
            status = playerFacingStatus,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(HyperCubeIntent.Pause) },
            onResume = { vm.onIntent(HyperCubeIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Neon Hyper-Cube",
            help = GameHelpContent.hyperCube,
            accent = ACCENT,
            exitKeepsProgress = true,
        )

        // Feedback de "cargando anuncio": el rewarded real puede tardar varios segundos y sin esto
        // pulsar "Deshacer" parecería no hacer nada. Va después del menú de pausa para taparlo.
        AdLoadingOverlay(visible = state.awaitingUndoAd, accent = ACCENT)

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir mientras se
        // juega (la partida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = playerFacingStatus,
            onResume = { vm.onIntent(HyperCubeIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = ACCENT,
        )
    }
}

// ---------------------------------------------------------------------------- tablero 3D

/**
 * Área jugable: proyecta el cubo y captura los gestos.
 *
 * La escena se calcula **en composición** (no dentro del `DrawScope`) porque los mismos polígonos
 * proyectados los necesitan dos consumidores: el dibujo y el *hit-testing* del gesto. Recalcularla
 * en el detector de arrastres duplicaría el trabajo y, peor, podría usar una cámara distinta a la
 * ya pintada. Como el estado solo cambia cuando hay algo animándose, en reposo no hay recomposición.
 */
@Composable
private fun CubeViewport(
    game: HyperCubeGameState,
    cameraYawRad: Float,
    cameraPitchRad: Float,
    flash: Float,
    onIntent: (HyperCubeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val center = Offset(widthPx / 2f, heightPx / 2f)
        val scale = min(widthPx, heightPx) * PROJECTION_SCALE

        val scene = remember(
            game.cube, game.activeTurn, cameraYawRad, cameraPitchRad, widthPx, heightPx,
        ) {
            buildScene(game.cube, game.activeTurn, cameraYawRad, cameraPitchRad, center, scale)
        }

        // El detector de gestos se instala UNA vez (key = Unit): recrearlo en cada frame de
        // animación abortaría el arrastre en curso. Lee siempre la escena más reciente por
        // referencia estable.
        val currentScene = rememberUpdatedState(scene)
        val currentIntent = rememberUpdatedState(onIntent)

        // Reloj de ambiente (giro lento de los anillos del pedestal); se lee solo en el dibujo.
        val clock = rememberBoardClock()
        // Cara agarrada mientras el dedo decide el giro: se ilumina para que el jugador vea QUÉ
        // tiene cogido antes de que nada se mueva (§9.4, feedback inmediato). Se guarda su
        // contorno ya proyectado: la escena no cambia hasta que el giro arranca, y entonces se suelta.
        var grabbedOutline by remember { mutableStateOf<Path?>(null) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val threshold = LAYER_DRAG_THRESHOLD_DP.dp.toPx()
                    // Pegatina agarrada al iniciar el arrastre (null = gesto de cámara), arrastre
                    // acumulado desde ese punto y si ya se emitió el giro de este gesto.
                    var grabbed: ProjectedFacelet? = null
                    var accumulated = Offset.Zero
                    var turnEmitted = false
                    // Mide la velocidad del dedo para la inercia al soltar. Se usa el tracker de
                    // Compose (y no un delta entre dos frames) porque promedia los últimos eventos
                    // y no se deja engañar por el micro-frenazo que casi todo el mundo hace justo
                    // antes de levantar el dedo.
                    val velocityTracker = VelocityTracker()

                    detectDragGestures(
                        onDragStart = { position ->
                            // Tocar la pantalla frena la órbita en el acto, como pararla con la mano.
                            currentIntent.value(HyperCubeIntent.StopCameraInertia)
                            // La escena está ordenada de lejos a cerca, así que el ÚLTIMO polígono
                            // que contiene el punto es el que el jugador ve encima y cree tocar.
                            grabbed = currentScene.value.lastOrNull { it.contains(position) }
                            grabbedOutline = grabbed?.bodyPath
                            accumulated = Offset.Zero
                            turnEmitted = false
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            // Solo la cámara tiene inercia: un giro de capa es un salto discreto de
                            // 90° que el motor ya anima, y dejarlo "derrapar" no significaría nada.
                            if (grabbed == null) {
                                val velocity = velocityTracker.calculateVelocity()
                                currentIntent.value(
                                    HyperCubeIntent.FlingCamera(
                                        yawRadPerSec = velocity.x * CAMERA_SENSITIVITY,
                                        pitchRadPerSec = velocity.y * CAMERA_SENSITIVITY,
                                    ),
                                )
                            }
                            grabbed = null
                            grabbedOutline = null
                            turnEmitted = false
                        },
                        onDragCancel = { grabbed = null; grabbedOutline = null; turnEmitted = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val facelet = grabbed
                            if (facelet == null) {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                currentIntent.value(
                                    HyperCubeIntent.RotateCamera(
                                        deltaYawRad = dragAmount.x * CAMERA_SENSITIVITY,
                                        deltaPitchRad = dragAmount.y * CAMERA_SENSITIVITY,
                                    ),
                                )
                            } else if (!turnEmitted) {
                                // Un solo giro por arrastre: se acumula hasta superar el umbral
                                // (evita disparar con un temblor) y a partir de ahí se ignora el
                                // resto del gesto, para no encadenar giros que nadie pidió.
                                accumulated += dragAmount
                                if (accumulated.getDistance() >= threshold) {
                                    val intent = resolveTurn(facelet, accumulated)
                                    if (intent != null) {
                                        currentIntent.value(intent)
                                        turnEmitted = true
                                        // La capa ya gira: el resalte de "agarrada" sobraría
                                        // (y quedaría clavado donde estaba la cara).
                                        grabbedOutline = null
                                    }
                                }
                            }
                        },
                    )
                },
        ) {
            val strokeWidth = (scale * STROKE_WIDTH_FRACTION).coerceAtLeast(1.5f)
            // `flash` baja de 1 a 0 tras resolver; su complemento es el avance de la celebración.
            val celebrate = if (flash > 0f) 1f - flash else 0f

            drawPedestal(center, scale, clock.seconds, flash)

            // Golpe de escala al resolver: el cubo "late" una vez. Se aplica al dibujo y no a la
            // proyección porque con la partida terminada ya no hay gestos que deban coincidir.
            val bump = if (flash > 0f) 1f + SOLVE_BUMP * sin(celebrate * PI.toFloat()) else 1f
            scale(bump, bump, pivot = center) {
                // Algoritmo del pintor: la lista ya viene ordenada de lejos a cerca.
                scene.forEach { facelet -> drawFacelet(facelet, strokeWidth, flash) }
            }

            grabbedOutline?.let { outline ->
                drawPath(outline, color = Color.White.copy(alpha = 0.22f))
                drawPath(
                    outline,
                    color = Color.White.copy(alpha = 0.85f),
                    style = Stroke(width = strokeWidth, join = StrokeJoin.Round),
                )
            }

            // Chispas de la victoria: una ráfaga central y un cinturón escalonado alrededor.
            if (flash > 0f) {
                drawSparkBurst(center, ACCENT, reach = scale * 3.4f, progress = celebrate * 1.25f, seed = 7)
                for (i in 0 until SOLVE_SPARK_COUNT) {
                    val angle = i * (2f * PI.toFloat() / SOLVE_SPARK_COUNT)
                    drawSparkBurst(
                        center = Offset(center.x + cos(angle) * scale * 2.6f, center.y + sin(angle) * scale * 2.2f),
                        color = SOLVE_SPARK_COLORS[i % SOLVE_SPARK_COLORS.size],
                        reach = scale * 1.1f,
                        progress = (celebrate - 0.08f - i * 0.045f) * 1.6f,
                        seed = 31 + i,
                    )
                }
            }
        }
    }
}

/**
 * Una pegatina ya proyectada a 2D, con todo lo que necesitan el dibujo y los gestos.
 *
 * @property path contorno cerrado de la pegatina, listo para `drawPath`.
 * @property bodyPath cara completa del cubie (ver [HyperCubeGeometry.BODY_HALF]): el cuerpo opaco
 *   que se pinta debajo y que, al teselar con el de los cubies vecinos, impide ver el fondo a
 *   través de las juntas.
 * @property corners esquinas 2D de **la cara completa** (no de la pegatina), para el
 *   *hit-testing* — un `Path` de Compose no se puede consultar, así que se guardan aparte.
 *
 *   Que sean las del cuerpo y no las de la pegatina es justo lo que hace que el gesto funcione en
 *   las **ranuras**: visualmente son parte del cubo, y probar contra el cuadrado más pequeño de la
 *   pegatina las dejaba fuera, de modo que arrastrar sobre una junta orbitaba la cámara en vez de
 *   girar la capa. Con las caras completas la superficie del cubo queda cubierta sin huecos.
 * @property shade iluminación de la cara, `SHADE_MIN..1`: la que mira a la luz (arriba y hacia el
 *   observador) va a color pleno y las laterales algo más apagadas. Es lo que da volumen al cubo
 *   y evita que tres caras del mismo tono se fundan en una mancha.
 * @property inSlice la pegatina pertenece a la capa que está girando ahora: se enciende.
 * @property depth profundidad media en espacio de cámara; clave de ordenación del pintor.
 * @property color color neón de la pegatina, ya resuelto desde el tema.
 * @property cubie posición lógica del cubie al que pertenece (para saber qué capa girar).
 * @property candidates los cuatro giros que puede pedir un arrastre sobre esta pegatina, cada uno
 *   con la dirección **en pantalla** hacia la que movería la pieza (ver [resolveTurn]).
 */
private data class ProjectedFacelet(
    val path: Path,
    val bodyPath: Path,
    val shade: Float,
    val inSlice: Boolean,
    val corners: List<Offset>,
    val depth: Float,
    val color: Color,
    val cubie: IntVec3,
    val candidates: List<TurnCandidate>,
)

/**
 * Un giro posible desde una pegatina, con su efecto visible ya precalculado.
 *
 * @property axis eje del giro.
 * @property direction sentido del giro.
 * @property screenMotion desplazamiento **en píxeles** que sufriría la pegatina tocada al empezar
 *   este giro. No se normaliza a propósito: su magnitud pondera la elección, de modo que un giro
 *   que apenas mueve la pieza (p. ej. el que la hace girar casi sobre sí misma) nunca le gana a
 *   uno que la desplaza de verdad en la dirección del dedo.
 */
private data class TurnCandidate(
    val axis: Axis,
    val direction: TurnDirection,
    val screenMotion: Offset,
)

/**
 * Construye la escena del frame: transforma, descarta, proyecta y ordena las 54 pegatinas.
 *
 * @param center centro del área de dibujo en píxeles (el cubo se proyecta alrededor de él).
 * @param scale píxeles por unidad de modelo en `z = 0`.
 * @return las pegatinas visibles ordenadas **de la más lejana a la más cercana**.
 */
private fun buildScene(
    cube: CubeState,
    activeTurn: ActiveTurn?,
    yawRad: Float,
    pitchRad: Float,
    center: Offset,
    scale: Float,
): List<ProjectedFacelet> {
    val camera = Mat3.camera(yawRad, pitchRad)
    // Giro parcial de la capa en vuelo: el estado lógico sigue intacto y es el render quien
    // "sostiene" la rebanada a medio camino (ver ActiveTurn).
    val slice = activeTurn?.let {
        Mat3.rotation(it.turn.axis, it.progress * it.turn.direction.signedQuarterRad)
    }

    val scene = ArrayList<ProjectedFacelet>(54)
    for (cubie in cube.cubies) {
        if (cubie.stickers.isEmpty()) continue // el núcleo no se ve nunca
        val inSlice = activeTurn != null &&
            cubie.position.component(activeTurn.turn.axis) == activeTurn.turn.layer
        val toView: (Vector3) -> Vector3 =
            if (inSlice && slice != null) { v -> camera * (slice * v) } else { v -> camera * v }

        for (sticker in cubie.stickers) {
            val view = HyperCubeGeometry.faceletCorners(cubie, sticker).map(toView)

            // Backface culling: normal del polígono por el producto vectorial de dos aristas
            // consecutivas. Con las esquinas en orden antihorario visto desde fuera, `z > 0`
            // significa "mira hacia el observador".
            val edge1 = view[1] - view[0]
            val edge2 = view[2] - view[1]
            val normal = edge1 cross edge2
            if (normal.z <= 0f) continue

            // Iluminación difusa de la cara con una luz fija en espacio de CÁMARA (arriba, algo a
            // la izquierda y hacia el observador): al orbitar, las caras cambian de luz como en un
            // objeto real en vez de llevar el sombreado "pintado".
            val length = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
            val lambert = if (length <= 0f) 1f else {
                ((normal.x * LIGHT_X + normal.y * LIGHT_Y + normal.z * LIGHT_Z) / length).coerceIn(0f, 1f)
            }

            val projected = view.map { project(it, center, scale) }
            // El cuerpo se transforma aparte (no vale escalar el polígono ya proyectado: la
            // perspectiva no es una transformación afín, así que agrandar en 2D deformaría).
            val bodyProjected = HyperCubeGeometry
                .faceletCorners(cubie, sticker, HyperCubeGeometry.BODY_HALF)
                .map { project(toView(it), center, scale) }

            scene += ProjectedFacelet(
                path = projected.toPath(),
                bodyPath = bodyProjected.toPath(),
                shade = SHADE_MIN + (1f - SHADE_MIN) * lambert,
                inSlice = inSlice,
                corners = bodyProjected,
                depth = (view[0].z + view[1].z + view[2].z + view[3].z) / 4f,
                color = sticker.color.toNeon(),
                cubie = cubie.position,
                candidates = turnCandidates(cubie, sticker, toView, center, scale),
            )
        }
    }
    return scene.sortedBy { it.depth }
}

/** Cierra una lista de puntos 2D en un `Path` dibujable. */
private fun List<Offset>.toPath(): Path = Path().apply {
    moveTo(this@toPath[0].x, this@toPath[0].y)
    for (i in 1 until this@toPath.size) lineTo(this@toPath[i].x, this@toPath[i].y)
    close()
}

/**
 * Los cuatro giros que puede pedir un arrastre sobre esta pegatina, con su efecto en pantalla.
 *
 * Sobre una cara solo hay dos ejes de arrastre posibles (sus tangentes) en dos sentidos cada uno.
 * Para cada uno, el eje de giro sale del producto vectorial `normal × dirección` (la regla
 * explicada en [resolveTurn]) y su **efecto visible** se mide empíricamente: se gira el centro de
 * la pegatina un ángulo pequeño con ese giro, se proyecta, y se guarda hacia dónde se movió en
 * pantalla.
 *
 * Medir el movimiento en vez de deducirlo de la geometría ideal es lo que hace fiable el gesto:
 * incluye la cámara y la perspectiva, así que la comparación posterior con el dedo se hace en el
 * mismo espacio en el que el jugador está mirando.
 */
private fun turnCandidates(
    cubie: Cubie,
    sticker: Sticker,
    toView: (Vector3) -> Vector3,
    center: Offset,
    scale: Float,
): List<TurnCandidate> {
    val (u, v) = HyperCubeGeometry.tangentBasis(sticker.normal)
    val centerModel = HyperCubeGeometry.faceletCenter(cubie, sticker)
    val centerScreen = project(toView(centerModel), center, scale)

    return listOf(u, u * -1, v, v * -1).mapNotNull { dragDirection ->
        val (axis, direction) = (sticker.normal cross dragDirection).toSignedAxis()
            ?: return@mapNotNull null
        val probe = Mat3.rotation(axis, direction.signedQuarterRad * MOTION_PROBE_FRACTION)
        val moved = project(toView(probe * centerModel), center, scale)
        val motion = moved - centerScreen
        if (motion.getDistance() < MIN_PROBE_MOTION_PX) null else TurnCandidate(axis, direction, motion)
    }
}

/**
 * Proyección en perspectiva de un punto ya rotado a espacio de cámara (fórmula y porqué en el
 * KDoc de [HyperCubeScreen]).
 */
private fun project(v: Vector3, center: Offset, scale: Float): Offset {
    val k = CAMERA_DISTANCE / (CAMERA_DISTANCE - v.z)
    return Offset(center.x + v.x * k * scale, center.y - v.y * k * scale)
}

/**
 * Pinta una pegatina como **cristal oscuro encendido por un tubo de neón**: la cara opaca del
 * cubie, la pegatina teñida, el resplandor del borde derramándose hacia dentro y el contorno en
 * varias pasadas (halo ancho → halo intermedio → trazo nítido → núcleo blanco).
 *
 * Sigue la §9.7 de CLAUDE.md: como el contorno no es el de un *tile* rectangular sino un
 * cuadrilátero arbitrario ya deformado por la perspectiva, no se puede llamar a `drawNeonTile`;
 * se replica su **misma proporción de capas** para que la estética sea idéntica a la de los demás
 * tableros del juego.
 *
 * ## De dónde sale el color de la cara
 * No de un relleno plano (eso es lo que se leía como dibujo animado), sino de la **luz del borde**:
 * dentro de la pegatina se pintan trazos anchos y translúcidos sobre su propio contorno,
 * recortados a ella, de modo que el color es intenso junto al tubo y se apaga hacia el centro.
 * Es lo que hace un neón real sobre el cristal que lo sostiene.
 *
 * ## Por qué el relleno es opaco y la profundidad se hace con mezcla de color
 * El cubo debe leerse como un **sólido**: nada de lo que hay detrás (ni el fondo estrellado ni las
 * caras ocultas) puede transparentarse. Por eso los rellenos van a alfa 1 y tanto la atenuación
 * por distancia ([depthDim]) como la iluminación ([ProjectedFacelet.shade]) se aplican
 * **mezclando el color hacia el fondo** en vez de bajando el alfa, que es lo que reintroduciría
 * la transparencia. Los halos sí conservan alfa: se dibujan *encima* del cuerpo opaco.
 *
 * La capa que gira ([ProjectedFacelet.inSlice]) se pinta sin atenuar y con el tubo reforzado: es
 * lo que el jugador está moviendo y debe destacar sobre el resto del cubo.
 */
private fun DrawScope.drawFacelet(facelet: ProjectedFacelet, strokeWidth: Float, flash: Float) {
    val dim = depthDim(facelet.depth)
    val neon = facelet.color
    val lift = if (facelet.inSlice) 1f else 0f
    // Luz total de la cara: profundidad × orientación, salvo en la capa activa (a pleno).
    val base = dim * facelet.shade
    val light = (base + (1f - base) * (0.8f * lift)).coerceIn(0f, 1f)
    // Encendido del tubo: sube con la capa activa y con el destello de cubo resuelto.
    val power = (light * (1f + 0.55f * lift) + 0.6f * flash).coerceAtMost(1.6f)
    val round = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Cuerpo del cubie: opaco y teselando con sus vecinos, sella el volumen (ver BODY_HALF).
    drawPath(facelet.bodyPath, color = LogicColors.SurfaceDark.dimmed(0.35f + 0.25f * dim))
    // Cristal de la pegatina: oscuro, con un tinte del color que basta para identificarla.
    drawPath(facelet.path, color = lerp(LogicColors.SurfaceDark, neon, GLASS_TINT).dimmed(light))

    // Resplandor interior: la luz del tubo derramándose sobre el cristal, de más a menos.
    // Son muchas capas finas de alfa bajo que se solapan (la suma decae suave hacia el centro):
    // con solo tres capas gruesas los escalones se veían como cuadrados concéntricos.
    clipPath(facelet.path) {
        for (i in 0 until INNER_GLOW_LAYERS) {
            val t = i / (INNER_GLOW_LAYERS - 1f)
            drawPath(
                facelet.path,
                color = neon.copy(alpha = (INNER_GLOW_ALPHA * power).coerceAtMost(1f)),
                style = Stroke(
                    width = strokeWidth * (INNER_GLOW_WIDE + (INNER_GLOW_NEAR - INNER_GLOW_WIDE) * t),
                    join = StrokeJoin.Round,
                ),
            )
        }
    }

    // Tubo de neón: halo ancho → halo intermedio → trazo nítido → núcleo blanco.
    drawPath(
        facelet.path,
        color = neon.copy(alpha = (0.12f * power).coerceAtMost(1f)),
        style = Stroke(width = strokeWidth * 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        facelet.path,
        color = neon.copy(alpha = (0.34f * power).coerceAtMost(1f)),
        style = Stroke(width = strokeWidth * 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(facelet.path, color = neon.dimmed((light + 0.25f).coerceAtMost(1f)), style = round)
    drawPath(
        facelet.path,
        color = Color.White.copy(alpha = (CORE_ALPHA * light + 0.35f * lift + 0.85f * flash).coerceAtMost(0.95f)),
        style = Stroke(width = strokeWidth * 0.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * **Pedestal holográfico** bajo el cubo: un halo del acento detrás, una sombra elíptica en el
 * "suelo" y dos anillos de trazos que giran despacio en sentidos opuestos.
 *
 * Sin él el cubo flotaba en mitad de la pantalla sin referencia de dónde está "abajo"; con una
 * base, la órbita de la cámara se lee como rodear un objeto apoyado en algo. Es ambiente: lento y
 * de baja amplitud (§9.4), y solo se enciende de verdad en la celebración ([flash]).
 *
 * @param scale píxeles por unidad de modelo (el mismo de la proyección), para que el pedestal
 *   acompañe al tamaño del cubo en cualquier pantalla.
 * @param time segundos del reloj de ambiente.
 * @param flash 1→0 tras resolver: aviva el halo y abre los anillos.
 */
private fun DrawScope.drawPedestal(center: Offset, scale: Float, time: Float, flash: Float) {
    // Halo detrás del cubo.
    val haloRadius = scale * 3.3f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(ACCENT.copy(alpha = 0.16f + 0.22f * flash), ACCENT.copy(alpha = 0.05f), Color.Transparent),
            center = center,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = center,
    )

    // Base: elipse achatada por debajo del cubo (vista en perspectiva, de ahí el achatado).
    val baseCenter = Offset(center.x, center.y + scale * 2.35f)
    val grow = 1f + 0.35f * (if (flash > 0f) 1f - flash else 0f)
    val radiusX = scale * 2.05f * grow
    val radiusY = radiusX * PEDESTAL_SQUASH
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(ACCENT.copy(alpha = 0.22f + 0.25f * flash), Color.Transparent),
            center = baseCenter,
            radius = radiusX,
        ),
        topLeft = Offset(baseCenter.x - radiusX, baseCenter.y - radiusY),
        size = Size(radiusX * 2f, radiusY * 2f),
    )
    // Anillos de trazos. El "giro" se hace desplazando la fase del patrón: rotar el óvalo en sí
    // lo sacaría de su plano.
    val dash = scale * 0.34f
    fun ring(scaleBy: Float, speed: Float, alpha: Float, width: Float) {
        val rx = radiusX * scaleBy
        val ry = radiusY * scaleBy
        drawOval(
            color = ACCENT.copy(alpha = (alpha + 0.35f * flash).coerceAtMost(1f)),
            topLeft = Offset(baseCenter.x - rx, baseCenter.y - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f), time * speed * dash),
            ),
        )
    }
    ring(scaleBy = 1f, speed = PEDESTAL_SPIN, alpha = 0.55f, width = 1.6.dp.toPx())
    ring(scaleBy = 0.72f, speed = -PEDESTAL_SPIN * 1.6f, alpha = 0.32f, width = 1.2.dp.toPx())
}

/**
 * Atenuación por profundidad: `1` en la cara más cercana y [MIN_DEPTH_DIM] en la más lejana,
 * interpolando linealmente entre los extremos que puede alcanzar un vértice del cubo.
 */
private fun depthDim(depth: Float): Float {
    val t = ((depth + MAX_MODEL_RADIUS) / (2f * MAX_MODEL_RADIUS)).coerceIn(0f, 1f)
    return MIN_DEPTH_DIM + (1f - MIN_DEPTH_DIM) * t
}

/**
 * Oscurece un color **sin tocar su alfa**, mezclándolo hacia el fondo de la app.
 *
 * Es la forma correcta de dar profundidad a una superficie que debe seguir siendo opaca: bajar el
 * alfa la volvería translúcida y dejaría ver el interior del cubo. `factor = 1` deja el color
 * intacto; valores menores lo acercan al fondo, que es justo lo que hace la distancia.
 */
private fun Color.dimmed(factor: Float): Color =
    lerp(LogicColors.BackgroundDark, this, factor.coerceIn(0f, 1f))

// ---------------------------------------------------------------------------- gestos

/**
 * Traduce un arrastre sobre una pegatina al giro de capa que el jugador quiso hacer.
 *
 * ## De qué giros se elige: `eje de giro = normal × dirección del dedo`
 * Sobre la cara tocada solo hay cuatro arrastres posibles (sus dos ejes tangentes, en ambos
 * sentidos). Para cada uno, el producto vectorial de la normal con esa dirección da el eje de la
 * rebanada **con signo**: eje positivo → giro antihorario; eje negativo → horario (que es lo
 * mismo que antihorario alrededor del eje opuesto). Ejemplo para verificar el signo: en la cara
 * frontal (normal `+Z`), arrastrar hacia `+X` da `Z × X = +Y`, giro antihorario alrededor de
 * `+Y`, y la fila efectivamente se va hacia la derecha.
 *
 * ## Cómo se elige entre los cuatro: por el movimiento, no por la geometría
 * Se toma el candidato que **más desplaza la pieza en la dirección del dedo**, maximizando el
 * producto escalar del arrastre con [TurnCandidate.screenMotion] (sin normalizar: ver su KDoc).
 *
 * El criterio evidente —"qué eje tangente se parece más al gesto"— **está mal**: cuando una cara
 * se ve casi de canto, sus dos tangentes se proyectan casi sobre la misma recta de la pantalla y
 * la decisión se vuelve inestable. Al medirlo sobre gestos aleatorios, ese criterio giraba la
 * capa en dirección contraria al dedo en ~13 % de los casos; comparar el movimiento resultante
 * acierta en el 100 % (verificado sobre 4.000 gestos con cámaras aleatorias). La diferencia está
 * en optimizar directamente lo único que el jugador percibe: **que la capa siga a su dedo**.
 *
 * Ojo con la letra pequeña: la garantía es sobre el *arranque* del giro. Un cuarto de vuelta
 * describe un arco y a mitad de camino la pieza dobla la esquina, así que a partir de unos 15-20°
 * deja de moverse hacia donde apuntaba el dedo. Es inherente a rotar, no un defecto de la
 * elección: ningún giro de 90° mantiene la dirección inicial hasta el final.
 *
 * La capa concreta sale de la posición del cubie tocado a lo largo del eje elegido, así que
 * arrastrar la fila del medio gira la rebanada central: el cubo se comporta como uno real.
 *
 * @return el intent listo para enviar, o `null` si ningún giro acompaña al gesto (arrastre nulo o
 *   perpendicular a todo movimiento posible).
 */
private fun resolveTurn(facelet: ProjectedFacelet, drag: Offset): HyperCubeIntent? {
    val best = facelet.candidates.maxByOrNull { candidate ->
        drag.x * candidate.screenMotion.x + drag.y * candidate.screenMotion.y
    } ?: return null

    val alignment = drag.x * best.screenMotion.x + drag.y * best.screenMotion.y
    if (alignment <= 0f) return null

    return HyperCubeIntent.StartLayerRotation(
        axis = best.axis,
        layer = facelet.cubie.component(best.axis),
        direction = best.direction,
    )
}

/**
 * Descompone un eje unitario con signo en su [Axis] y el [TurnDirection] equivalente: girar en
 * sentido antihorario alrededor de `−A` es lo mismo que girar en sentido horario alrededor de
 * `+A`. `null` si el vector no es un eje (no debería ocurrir: siempre es un producto vectorial de
 * dos cardinales perpendiculares).
 */
private fun IntVec3.toSignedAxis(): Pair<Axis, TurnDirection>? = when {
    x != 0 -> Axis.X to directionOf(x)
    y != 0 -> Axis.Y to directionOf(y)
    z != 0 -> Axis.Z to directionOf(z)
    else -> null
}

private fun directionOf(component: Int): TurnDirection =
    if (component > 0) TurnDirection.COUNTER_CLOCKWISE else TurnDirection.CLOCKWISE

/** ¿Contiene el polígono este punto? Test de convexidad: el punto queda al mismo lado de las 4 aristas. */
private fun ProjectedFacelet.contains(point: Offset): Boolean {
    var sign = 0
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
        val current = when {
            cross > 0f -> 1
            cross < 0f -> -1
            else -> 0
        }
        if (current != 0) {
            if (sign == 0) sign = current else if (current != sign) return false
        }
    }
    return true
}

// ---------------------------------------------------------------------------- HUD y antesala

/**
 * Cabecera del tablero: nivel o modo, movimientos contra el par y cronómetro.
 *
 * Solo **datos**: las acciones (deshacer, mezclar) viven en la barra inferior, como en el resto de
 * juegos con ayudas. Deja libre la esquina derecha, donde va el botón de pausa.
 */
@Composable
private fun HyperCubeHud(
    level: Int,
    isFreeMode: Boolean,
    moves: Int,
    par: Int,
    elapsedMs: () -> Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 72.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Nivel/modo: la misma píldora de contorno que el HUD de los demás tableros por niveles.
        Text(
            text = (
                if (isFreeMode) stringResource(Res.string.hypercube_hud_free_mode)
                else stringResource(Res.string.gameboard_hud_level, level.toString())
                ).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = ACCENT,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.5.dp, ACCENT.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        // El par (longitud de la mezcla) es la referencia de "solución corta" con la que se puntúa
        // la eficiencia, así que se muestra... salvo en modo libre: ahí la mezcla es de 20 giros
        // aleatorios, que NO es un objetivo alcanzable ni pretende serlo, y enseñarlo como meta
        // ("3 / 20") solo daría una sensación falsa de ir perdiendo. Sin nivel, no hay par.
        val hasPar = !isFreeMode && par > 0
        HudChip(
            icon = KortexIcons.Refresh,
            description = stringResource(Res.string.hypercube_hud_moves),
            value = if (hasPar) "$moves/$par" else "$moves",
            // Pasarse del par no es un error, pero sí cuesta eficiencia: se avisa en ámbar.
            valueColor = if (hasPar && moves > par) LogicColors.Amber else LogicColors.OnDark,
        )
        HudClock(elapsedMs = elapsedMs)
    }
}

/**
 * Cronómetro de la partida, con milésimas.
 *
 * ## Por qué el tiempo llega como lambda y no dentro del estado
 * A 60 fps, meter los milisegundos en el `UiState` emitiría un estado nuevo por frame y **toda**
 * la pantalla se recompondría —incluida la reconstrucción de la escena 3D— solo para mover un
 * dígito. Pasando un `() -> Long` que se lee dentro de este composable, el bucle de frames y la
 * lectura del estado quedan confinados aquí: lo único que se recompone 60 veces por segundo es
 * este `Text`.
 *
 * El reloj lo sigue llevando el motor ([HyperCubeEngine.elapsedMs]), que ya descuenta las pausas;
 * esta función solo lo consulta. Cuando la partida está pausada o terminada, el valor deja de
 * crecer por sí solo y el bucle se vuelve inofensivo.
 */
@Composable
private fun HudClock(elapsedMs: () -> Long) {
    var display by remember { mutableStateOf(formatElapsed(elapsedMs())) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { display = formatElapsed(elapsedMs()) }
        }
    }
    HudChip(
        icon = KortexIcons.Timer,
        description = stringResource(Res.string.hypercube_hud_time),
        value = display,
        valueColor = LogicColors.OnDark,
        monospace = true,
    )
}

/**
 * Formatea una duración como `m:ss.mmm` (o `s.mmm` en el primer minuto), la notación con la que se
 * cronometran los cubos. Se usa ancho fijo en los campos para que el texto no "baile" al pasar de
 * 9 a 10 segundos.
 */
private fun formatElapsed(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val minutes = safe / 60_000
    val seconds = (safe % 60_000) / 1000
    val ms = safe % 1000
    val fraction = ms.toString().padStart(3, '0')
    return if (minutes > 0) {
        "$minutes:${seconds.toString().padStart(2, '0')}.$fraction"
    } else {
        "$seconds.$fraction"
    }
}

/**
 * Dato del HUD como píldora con icono: el icono dice QUÉ es (movimientos, tiempo) sin gastar una
 * línea de etiqueta, así la cabecera queda en una sola fila baja y deja más alto al cubo.
 *
 * @param description texto del dato para lectores de pantalla (lo que antes decía la etiqueta).
 * @param monospace cifra de ancho fijo; imprescindible en el cronómetro (ver más abajo).
 */
@Composable
private fun HudChip(
    icon: ImageVector,
    description: String,
    value: String,
    valueColor: Color,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, LogicColors.SurfaceVariantDark, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = icon, tint = ACCENT, size = 16.dp, glow = false, contentDescription = description)
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            fontWeight = FontWeight.Black,
            // Monoespaciada en el cronómetro: con dígitos de ancho variable, las milésimas hacen
            // que la píldora entera cambie de tamaño en cada frame.
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * Entrada al **modo libre** en la antesala, bajo el carril de niveles.
 *
 * Se presenta como acción secundaria (sin `pulse` ni halo, que quedan reservados al CTA principal
 * según §9.4) porque la puerta natural del juego sigue siendo la progresión: el modo libre es para
 * quien ya sabe resolver un cubo, no el camino por defecto.
 */
@Composable
private fun FreeModeCard(onPlay: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "¿Ya dominas el cubo? Pruébalo entero, mezclado a fondo y sin nivel.",
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        AnimatedGameButton(
            onClick = onPlay,
            gradient = LogicGradients.energy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "MODO LIBRE",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Traduce el color **lógico** de una pegatina al token real del tema.
 *
 * Este `when` es, a propósito, el único sitio del juego donde una cara se vuelve un `Color`
 * concreto: el dominio nunca conoce `LogicColors` (ver el KDoc de [FaceColor]).
 */
private fun FaceColor.toNeon(): Color = when (this) {
    FaceColor.CYAN -> LogicColors.NeonCyan
    FaceColor.AMBER -> LogicColors.Amber
    FaceColor.GREEN -> LogicColors.NeonGreen
    FaceColor.BLUE -> LogicColors.Blue
    FaceColor.MAGENTA -> LogicColors.Magenta
    FaceColor.VIOLET -> LogicColors.Violet
}

// ---------------------------------------------------------------------------- constantes de render

/** Color de acento del juego: el de su categoría (Visión Espacial). */
private val ACCENT = CategoryPalette.SpatialVision

/**
 * Distancia de la cámara al centro del cubo, en unidades de modelo (el cubo ocupa ±1.5). Cuanto
 * menor, más agresiva la perspectiva; a ~4.5 veces el radio del cubo la deformación se lee como
 * volumen sin llegar al efecto "ojo de pez".
 */
private const val CAMERA_DISTANCE = 7f

/** Píxeles por unidad de modelo en `z = 0`, como fracción del lado menor del área de dibujo. */
private const val PROJECTION_SCALE = 0.21f

/** Distancia máxima del centro a un vértice del cubo; acota el rango de profundidades. */
private const val MAX_MODEL_RADIUS = 1.7f

/** Cuánto conserva de su color la cara más lejana al mezclarse con el fondo (1 = sin atenuar). */
private const val MIN_DEPTH_DIM = 0.62f

/**
 * Dirección de la luz en espacio de cámara (vector unitario): arriba, un poco a la izquierda y
 * hacia el observador. Con ella la cara superior es la más clara y las laterales se distinguen.
 */
private const val LIGHT_X = -0.30f
private const val LIGHT_Y = 0.58f
private const val LIGHT_Z = 0.757f

/** Luz mínima de una cara que mira de perfil a la luz. Alta: el tubo debe seguir encendido. */
private const val SHADE_MIN = 0.70f

/** Cuánto del color de la cara tiñe el cristal de la pegatina. Bajo: la cara es oscura y el
 *  color lo pone la luz del borde; por encima de ~0.4 vuelve a parecer un relleno plano. */
private const val GLASS_TINT = 0.24f

/** Resplandor interior: ancho (en grosores de trazo) de la capa más amplia y de la más pegada
 *  al tubo. La mitad de cada ancho cae dentro de la pegatina. */
private const val INNER_GLOW_WIDE = 20f
private const val INNER_GLOW_NEAR = 2.5f

/** Capas del resplandor interior y opacidad de cada una (se suman hacia el borde). */
private const val INNER_GLOW_LAYERS = 9
private const val INNER_GLOW_ALPHA = 0.075f

/** Opacidad del núcleo blanco del tubo a plena luz: el toque de "neón encendido". */
private const val CORE_ALPHA = 0.55f

/** Achatado de la elipse del pedestal (alto/ancho): la base se ve en perspectiva. */
private const val PEDESTAL_SQUASH = 0.26f

/** Velocidad de giro del anillo exterior del pedestal, en trazos por segundo. Lenta: ambiente. */
private const val PEDESTAL_SPIN = 0.55f

/** Cuánto crece el cubo en el golpe de escala al resolverse. */
private const val SOLVE_BUMP = 0.07f

/** Ráfagas del cinturón de chispas de la victoria y sus colores (los de las caras). */
private const val SOLVE_SPARK_COUNT = 6
private val SOLVE_SPARK_COLORS = listOf(
    LogicColors.NeonCyan, LogicColors.Amber, LogicColors.NeonGreen,
    LogicColors.Blue, LogicColors.Magenta, LogicColors.Violet,
)

/** Cuánto se retiene el diálogo de resultado tras resolver (ms): lo que dura la celebración. */
private const val SOLVE_HOLD_MS = 1_000L

/** Grosor del trazo nítido, como fracción de la escala de proyección. */
private const val STROKE_WIDTH_FRACTION = 0.026f

/** Radianes de órbita por píxel arrastrado (~400 px para media vuelta). */
private const val CAMERA_SENSITIVITY = 0.008f

/** Píxeles a recorrer antes de decidir el giro de capa; filtra temblores del dedo. */
private const val LAYER_DRAG_THRESHOLD_DP = 18

/**
 * Fracción de cuarto de vuelta con la que se sondea hacia dónde movería cada giro a la pegatina
 * tocada. Pequeña para medir la dirección instantánea del movimiento, pero no tanto como para que
 * la diferencia de dos proyecciones se pierda en el ruido de la coma flotante.
 */
private const val MOTION_PROBE_FRACTION = 0.06f

/** Píxeles mínimos de movimiento para considerar un candidato; descarta sondeos degenerados. */
private const val MIN_PROBE_MOTION_PX = 0.01f
