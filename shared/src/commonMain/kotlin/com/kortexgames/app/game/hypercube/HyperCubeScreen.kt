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
import kotlin.math.min

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
 * ocultas se transparentan por las juntas (ver [HyperCubeGeometry.BODY_HALF]). El brillo neón vive
 * en los contornos, dibujados encima de ese cuerpo.
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
            onStart = { vm.onIntent(HyperCubeIntent.PlayLevel(selectedLevel)) },
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
                Text(
                    text = "Arrastra sobre una fila para girarla · fuera del cubo para orbitar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                )
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

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
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
                            turnEmitted = false
                        },
                        onDragCancel = { grabbed = null; turnEmitted = false },
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
                                    }
                                }
                            }
                        },
                    )
                },
        ) {
            val strokeWidth = (scale * STROKE_WIDTH_FRACTION).coerceAtLeast(1.5f)
            // Algoritmo del pintor: la lista ya viene ordenada de lejos a cerca.
            scene.forEach { facelet -> drawFacelet(facelet, strokeWidth, flash) }
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
 * @property depth profundidad media en espacio de cámara; clave de ordenación del pintor.
 * @property color color neón de la pegatina, ya resuelto desde el tema.
 * @property cubie posición lógica del cubie al que pertenece (para saber qué capa girar).
 * @property candidates los cuatro giros que puede pedir un arrastre sobre esta pegatina, cada uno
 *   con la dirección **en pantalla** hacia la que movería la pieza (ver [resolveTurn]).
 */
private data class ProjectedFacelet(
    val path: Path,
    val bodyPath: Path,
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
            if ((edge1 cross edge2).z <= 0f) continue

            val projected = view.map { project(it, center, scale) }
            // El cuerpo se transforma aparte (no vale escalar el polígono ya proyectado: la
            // perspectiva no es una transformación afín, así que agrandar en 2D deformaría).
            val bodyProjected = HyperCubeGeometry
                .faceletCorners(cubie, sticker, HyperCubeGeometry.BODY_HALF)
                .map { project(toView(it), center, scale) }

            scene += ProjectedFacelet(
                path = projected.toPath(),
                bodyPath = bodyProjected.toPath(),
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
 * Pinta una pegatina como **tubo de luz sobre cuerpo sólido**: primero la cara opaca del cubie,
 * luego la pegatina y su contorno neón en varias pasadas (halo ancho → halo intermedio → trazo
 * nítido → núcleo blanco al destellar).
 *
 * Sigue la §9.7 de CLAUDE.md: como el contorno no es el de un *tile* rectangular sino un
 * cuadrilátero arbitrario ya deformado por la perspectiva, no se puede llamar a `drawNeonTile`;
 * se replica su **misma proporción de capas** para que la estética sea idéntica a la de los demás
 * tableros del juego.
 *
 * ## Por qué el relleno es opaco y la profundidad se hace con mezcla de color
 * El cubo debe leerse como un **sólido**: nada de lo que hay detrás (ni el fondo estrellado ni las
 * caras ocultas) puede transparentarse. Por eso los rellenos van a alfa 1 y la atenuación por
 * distancia ([depthDim]) se aplica **mezclando el color hacia el fondo** en vez de bajando el
 * alfa, que es lo que reintroduciría la transparencia. Los halos sí conservan alfa: se dibujan
 * *encima* del cuerpo opaco, así que su translucidez no deja ver nada de detrás.
 */
private fun DrawScope.drawFacelet(facelet: ProjectedFacelet, strokeWidth: Float, flash: Float) {
    val dim = depthDim(facelet.depth)
    val neon = facelet.color

    // Cuerpo del cubie: opaco y teselando con sus vecinos, sella el volumen (ver BODY_HALF).
    drawPath(facelet.bodyPath, color = LogicColors.SurfaceDark.dimmed(dim))
    // Pegatina: superficie algo más clara que el cuerpo + un velo del color de la cara que la
    // identifica sin convertirla en un relleno plano de color.
    drawPath(facelet.path, color = LogicColors.SurfaceVariantDark.dimmed(dim))
    drawPath(facelet.path, color = neon.copy(alpha = 0.16f * dim))

    drawPath(
        facelet.path,
        color = neon.copy(alpha = 0.10f * dim),
        style = Stroke(width = strokeWidth * 3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        facelet.path,
        color = neon.copy(alpha = 0.24f * dim),
        style = Stroke(width = strokeWidth * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        facelet.path,
        color = neon.copy(alpha = 0.95f * dim),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    if (flash > 0f) {
        drawPath(
            facelet.path,
            color = Color.White.copy(alpha = flash * 0.85f),
            style = Stroke(
                width = strokeWidth * 0.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
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
 * juegos con ayudas.
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudPill(
            label = if (isFreeMode) "MODO" else "NIVEL",
            value = if (isFreeMode) "Libre" else level.toString(),
        )
        // El par (longitud de la mezcla) es la referencia de "solución corta" con la que se puntúa
        // la eficiencia, así que se muestra... salvo en modo libre: ahí la mezcla es de 20 giros
        // aleatorios, que NO es un objetivo alcanzable ni pretende serlo, y enseñarlo como meta
        // ("3 / 20") solo daría una sensación falsa de ir perdiendo. Sin nivel, no hay par.
        HudPill(
            label = "MOV.",
            value = if (isFreeMode || par <= 0) "$moves" else "$moves / $par",
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
    HudPill(label = "TIEMPO", value = display, monospace = true)
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

/** Píldora de dato del HUD (superficie elevada + etiqueta atenuada sobre valor destacado). */
@Composable
private fun HudPill(label: String, value: String, monospace: Boolean = false) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = ACCENT,
            fontWeight = FontWeight.Bold,
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
private const val MIN_DEPTH_DIM = 0.5f

/** Grosor del trazo nítido, como fracción de la escala de proyección. */
private const val STROKE_WIDTH_FRACTION = 0.022f

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
