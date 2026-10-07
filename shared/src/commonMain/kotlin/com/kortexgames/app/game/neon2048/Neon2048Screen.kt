package com.kortexgames.app.game.neon2048

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.DifficultyUnlocks
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.DifficultyGateSelector
import com.kortexgames.app.ui.components.DifficultyOption
import com.kortexgames.app.ui.components.FireworksOverlay
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.ModalReveal
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.drawNeonBoardPlate
import com.kortexgames.app.ui.components.drawNeonTile
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import com.kortexgames.app.ui.components.drawSparkBurst
import com.kortexgames.app.ui.components.modalCard
import com.kortexgames.app.ui.components.modalScrim
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.neon2048_hud_best
import kortexgames.shared.generated.resources.neon2048_hud_gain
import kortexgames.shared.generated.resources.neon2048_hud_max
import kortexgames.shared.generated.resources.neon2048_hud_score
import kortexgames.shared.generated.resources.neon2048_win_body
import kortexgames.shared.generated.resources.neon2048_win_continue
import kortexgames.shared.generated.resources.neon2048_win_restart
import kortexgames.shared.generated.resources.neon2048_win_title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ayuda del juego: se muestra en la antesala y también en el menú de pausa. */
private const val NEON_2048_HELP =
    "Desliza el tablero en cualquier dirección: las fichas se van hasta el borde y " +
        "las que llevan el mismo número se fusionan sumándose. Cada movimiento hace " +
        "aparecer una ficha nueva. Llega a 2048 antes de quedarte sin sitio."

/**
 * # Neon Grid 2048 — Renderizado y animaciones (Compose, FASE 3)
 *
 * Pantalla del juego. Solo **observa el estado** del [Neon2048ViewModel] (FASE 2)
 * y traduce los efectos one-shot a sonido, háptica y celebración.
 *
 * ## Por qué cada ficha es un composable propio (y no un Canvas único)
 * El resto de juegos de la app pintan su tablero en un solo `Canvas` por
 * rendimiento. Aquí NO: la gracia del 2048 es ver la ficha **viajar** de su
 * casilla a la siguiente, y eso se consigue dando a cada ficha su propia
 * identidad en el árbol de composición mediante [key] `(tile.id)`. Con esa clave,
 * Compose entiende que la ficha de `(0,3)` y la de `(0,0)` son la misma y
 * conserva su estado de animación entre jugadas; sin ella recrearía el nodo y la
 * ficha se **teletransportaría**. Son 16 nodos como máximo: el coste es
 * irrelevante frente a lo que se gana.
 *
 * @param graph grafo de DI (repos, audio, settings).
 * @param onExit callback para volver al catálogo.
 */
@Composable
fun Neon2048Screen(graph: AppGraph, onExit: () -> Unit) {
    val vm: Neon2048ViewModel = viewModel {
        Neon2048ViewModel(
            graph.progressRepository,
            graph.savedGameStateRepository,
            graph.audio,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la corrida en curso antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // La celebración de victoria vive en la UI (no en el estado del juego): es
    // adorno puntual con su propio ciclo de vida, disparado por un efecto.
    var celebrating by remember { mutableStateOf(false) }

    // Celebración de fusión grande (>64): a diferencia de `celebrating` (una vez
    // por partida, se queda encendida hasta que el jugador cierra el overlay de
    // victoria), esta puede repetirse muchas veces en la misma partida, así que
    // necesita limpiarse sola — ver el LaunchedEffect de auto-cierre más abajo.
    var mergeCelebration by remember { mutableStateOf<MergeCelebration?>(null) }

    LaunchedEffect(vm) {
        // Contador local (no `remember`): vive dentro de esta única corrutina
        // mientras dure la pantalla, y solo se usa para dar a cada celebración un
        // `id`/semilla propios — así `key(id)` fuerza a FireworksOverlay a
        // reiniciar su patrón si dos fusiones grandes llegan seguidas.
        var mergeCelebrationSeed = 0
        vm.effect.collect { effect ->
            when (effect) {
                is Neon2048Effect.PlaySound -> graph.audio.playSound(effect.sound)
                is Neon2048Effect.Vibrate -> graph.audio.hapticFeedback(effect.feedback)
                Neon2048Effect.ShowWinCelebration -> celebrating = true
                is Neon2048Effect.ShowMergeCelebration -> {
                    mergeCelebrationSeed++
                    mergeCelebration = MergeCelebration(
                        id = mergeCelebrationSeed,
                        accent = tileAccent(effect.value.countTrailingZeroBits()),
                    )
                }
            }
        }
    }

    // Auto-cierre: sin esto, `mergeCelebration` se quedaría montado en pausa
    // indefinidamente tras terminar sus estallidos (inofensivo en rendimiento,
    // pero deja el composable vivo sin motivo). Se relanza en cada celebración
    // nueva (la key es su `id`) y solo se limpia a sí mismo si sigue siendo la
    // MISMA celebración al despertar — evita que una limpieza tardía borre una
    // celebración más reciente si dos fusiones grandes llegan muy seguidas.
    LaunchedEffect(mergeCelebration?.id) {
        val active = mergeCelebration ?: return@LaunchedEffect
        delay(MERGE_CELEBRATION_MS)
        if (mergeCelebration?.id == active.id) mergeCelebration = null
    }

    // Antesala mientras el juego está en IDLE, igual que el resto de juegos.
    //
    // El selector de tamaño de tablero se pasa como `configContent` de
    // `GameIntroScreen`: se pinta DENTRO del propio bloque de acciones, justo
    // encima del CTA principal (no superpuesto por fuera con un padding fijo
    // calculado a ojo, que es como lo hacía esta pantalla antes). Ese padding
    // fijo se descuadraba en cuanto `resume` añadía su resumen + "Empezar de
    // nuevo" debajo del CTA: el bloque crecía, el CTA se desplazaba hacia
    // arriba, y el selector —anclado a una distancia fija del fondo de la
    // pantalla, ajena a ese crecimiento— terminaba solapándolo (ver
    // `NeonSudokuScreen`, que usa el mismo mecanismo con su selector de
    // dificultad). `configContent` vive en el flujo normal de la Column, así
    // que crece y se encoge con el resto del bloque y nunca puede
    // desalinearse, sea cual sea la altura de lo que haya debajo.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.neon2048,
            title = "Neon Grid 2048",
            motif = GameMotif.NUMBER_TILES,
            description = NEON_2048_HELP,
            accent = CategoryPalette.MentalMath,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_2048)
                vm.onIntent(Neon2048Intent.StartGame)
            },
            // Corrida a medias guardada al salir: la antesala la ofrece como CTA
            // principal, con su puntuación para que el jugador sepa qué retoma.
            resume = state.savedScore?.let { score ->
                ResumeState(
                    onResume = { vm.onIntent(Neon2048Intent.ResumeSaved) },
                    detail = "$score pts en curso",
                )
            },
            configContent = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DifficultyGateSelector(
                        title = "TAMAÑO DEL TABLERO",
                        options = BOARD_SIZE_OPTIONS_UI,
                        selectedIndex = Neon2048Config.BOARD_SIZE_OPTIONS.indexOf(state.boardSize),
                        unlockedTiers = state.unlockedBoardSizes,
                        onSelect = { index ->
                            vm.onIntent(
                                Neon2048Intent.SelectBoardSize(Neon2048Config.BOARD_SIZE_OPTIONS[index]),
                            )
                        },
                        accent = CategoryPalette.MentalMath,
                        hint = DifficultyUnlocks.nextUnlockHint(GameIds.NEON_2048, state.unlockedBoardSizes),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Comparativa mundial del tablero elegido, ANTES de jugar (mismo panel
                    // que el diálogo de fin de partida): pedido explícito para que la
                    // antesala también responda "¿cómo me va ahí?".
                    val preview = state.rankingPreview
                    when {
                        state.rankingPreviewLoading -> WorldRankingLoading()
                        preview != null -> WorldRankingPreviewPanel(ranking = preview)
                        else -> RankingPreviewUnavailable(
                            difficultyLabel = "${state.boardSize}×${state.boardSize}",
                        )
                    }
                }
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Neon2048Hud(
                score = state.score,
                bestScore = state.bestScore,
                highest = state.highestValue,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )

            Neon2048Board(
                state = state,
                enabled = state.status == GameStatus.RUNNING,
                onSwipe = { vm.onIntent(Neon2048Intent.Swipe(it)) },
            )
        }

        if (celebrating) {
            FireworksOverlay(
                modifier = Modifier.fillMaxSize(),
                onBurst = { graph.audio.playSound(SoundEffect.SUCCESS) },
            )
        }

        // Salva corta al fusionar una ficha ≥128: más discreta que la de victoria
        // (menos estallidos) y teñida del color de la ficha resultante para que
        // cada hito se sienta suyo. Mismo SFX por estallido que la de victoria
        // ([SoundEffect.SUCCESS] vía `onBurst`, igual que en Defuser): antes se
        // dejaba deliberadamente sin sonido para no repetir el de movimiento/fusión
        // en cada jugada, pero un hito de 3 estallidos NO es "cada jugada" y sin
        // sonido se leía como un fallo visual mudo frente al resto de celebraciones
        // de la app. `key(id)` reinicia el patrón de FireworksOverlay si dos
        // fusiones grandes llegan una detrás de otra en vez de continuar la anterior.
        mergeCelebration?.let { celebration ->
            key(celebration.id) {
                FireworksOverlay(
                    modifier = Modifier.fillMaxSize(),
                    colors = listOf(celebration.accent, LogicColors.Amber),
                    burstCount = MERGE_CELEBRATION_BURSTS,
                    seed = celebration.id,
                    onBurst = { graph.audio.playSound(SoundEffect.SUCCESS) },
                )
            }
        }

        // Overlay de victoria: solo el turno en el que se llega a 2048. El juego
        // NO termina — de ahí que sea un overlay propio y no el de fin de partida.
        if (state.showWinOverlay) {
            WinOverlay(
                onContinue = {
                    celebrating = false
                    vm.onIntent(Neon2048Intent.ContinueAfterWin)
                },
                onRestart = {
                    celebrating = false
                    vm.onIntent(Neon2048Intent.RestartGame)
                },
            )
        }

        // Segunda oportunidad: al quedarse sin movimientos, ofrece limpiar las fichas
        // 2 y 4 viendo un anuncio antes del game-over. El scrim del overlay bloquea el
        // tablero mientras se decide; al aceptar se despejan esas fichas y sigue la
        // corrida, al rechazar cae al game-over normal. Icono de "refrescar": el trato
        // es despejar el tablero, no una vida.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(Neon2048Intent.Revive) },
                onDecline = { vm.onIntent(Neon2048Intent.DeclineRevive) },
                title = "¿Sin movimientos?",
                rewardLabel = "las fichas 2 y 4 despejadas",
                body = "Mira un anuncio y limpiamos todas las fichas 2 y 4 para que sigas jugando.",
                icon = KortexIcons.Refresh,
                accent = CategoryPalette.MentalMath,
                audio = graph.audio,
            )
        }

        state.gameOver?.let { info ->
            GameOverOverlay(
                info = info,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(Neon2048Intent.RestartGame) },
                onExit = onExit,
                unlockedDifficultyLabel = state.justUnlockedBoardSize?.let { size -> "$size×$size" },
                onPlayUnlockedDifficulty = state.justUnlockedBoardSize?.let { size ->
                    { vm.onIntent(Neon2048Intent.PlayBoardSize(size)) }
                },
                accent = CategoryPalette.MentalMath,
            )
        }

        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(Neon2048Intent.Pause) },
            onResume = { vm.onIntent(Neon2048Intent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Neon Grid 2048",
            help = GameHelpContent.neon2048,
            accent = CategoryPalette.MentalMath,
            exitKeepsProgress = true,
        )

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la corrida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(Neon2048Intent.Resume) },
            onConfirmExit = exitWithSave,
            accent = CategoryPalette.MentalMath,
        )
    }
}

// ---------------------------------------------------------------------------
// Selector de tamaño de tablero (antesala)
// ---------------------------------------------------------------------------

/**
 * Escalones del selector de tablero ("4×4".."8×8"), en el orden de
 * [Neon2048Config.BOARD_SIZE_OPTIONS]. Salen de recorrer esa lista —no son una copia—, así
 * que añadir o quitar un tamaño jugable sigue siendo un cambio de una línea en
 * [Neon2048Config] y no aquí.
 *
 * El tamaño hace de dificultad del juego: los que el jugador aún no se ha ganado se pintan
 * con candado (ver [DifficultyGateSelector] y [DifficultyUnlocks]).
 */
private val BOARD_SIZE_OPTIONS_UI: List<DifficultyOption> =
    Neon2048Config.BOARD_SIZE_OPTIONS.map { DifficultyOption(label = "$it×$it") }


// ---------------------------------------------------------------------------
// Tablero
// ---------------------------------------------------------------------------

/**
 * Tablero cuadrado (lado [Neon2048UiState.boardSize], elegido en la antesala) con
 * las fichas y la captura del gesto.
 *
 * La geometría se resuelve una sola vez aquí (con [BoxWithConstraints]) y se pasa
 * ya calculada en píxeles a cada ficha: así ninguna ficha necesita saber del
 * tamaño del tablero ni recalcular nada al animarse.
 *
 * @param state estado renderizable (fichas, fantasmas, tamaño elegido).
 * @param enabled false en pausa / fin de partida: el tablero deja de aceptar gestos.
 * @param onSwipe se invoca con la dirección **ya destilada** del arrastre.
 */
@Composable
private fun Neon2048Board(
    state: Neon2048UiState,
    enabled: Boolean,
    onSwipe: (Direction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boardSize = state.boardSize
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // La placa compartida del kit de tableros (la misma de Bloques Neón y Sudoku).
            .drawBehind { drawNeonBoardPlate(accent = CategoryPalette.MentalMath) }
            .padding(BOARD_PADDING)
            .swipeGestures(enabled, onSwipe),
    ) {
        // Reparto: `boardSize` celdas + (boardSize-1) huecos dentro del ancho
        // disponible. Un tablero 8×8 reparte el mismo espacio entre el doble de
        // celdas que uno 4×4: cada celda sale proporcionalmente más pequeña, lo
        // que ya resuelven bien tanto el radio de esquina (ver [tileCornerFor])
        // como el tamaño de fuente (ver [fontSizeFor]), ambos derivados de la
        // celda real y no de una constante fija.
        val cellSize: Dp = (maxWidth - CELL_GAP * (boardSize - 1)) / boardSize
        val cornerRadius = tileCornerFor(cellSize)
        // "Paso" = lo que hay que desplazarse para avanzar una casilla. Se calcula
        // en px enteros porque el desplazamiento de las fichas se anima como
        // IntOffset (evita el temblor de subpíxel al interpolar).
        val stepPx = with(density) { (cellSize + CELL_GAP).toPx() }.roundToInt()

        // Casillas vacías: zócalos hundidos donde encajan las fichas (fondo oscuro + filo
        // fino, como las celdas del resto de tableros). Un único Canvas — es decoración
        // estática, no necesita identidad.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = with(density) { cellSize.toPx() }
            val radius = CornerRadius(with(density) { cornerRadius.toPx() })
            for (row in 0 until boardSize) {
                for (col in 0 until boardSize) {
                    val topLeft = Offset(col * stepPx.toFloat(), row * stepPx.toFloat())
                    drawRoundRect(
                        color = LogicColors.BackgroundDark.copy(alpha = 0.55f),
                        topLeft = topLeft,
                        size = Size(side, side),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = LogicColors.SurfaceVariantDark.copy(alpha = 0.70f),
                        topLeft = topLeft,
                        size = Size(side, side),
                        cornerRadius = radius,
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
        }

        // Fantasmas PRIMERO: se dibujan debajo de las fichas vivas para quedar
        // ocultos justo cuando terminan de deslizarse hasta el punto de fusión.
        for (ghost in state.ghosts) {
            key(ghost.id) {
                Neon2048GhostView(ghost = ghost, cellSize = cellSize, stepPx = stepPx)
            }
        }
        for (tile in state.tiles) {
            key(tile.id) {
                Neon2048TileView(tile = tile, cellSize = cellSize, stepPx = stepPx)
            }
        }
    }
}

/**
 * Captura del swipe.
 *
 * Se dispara **en cuanto** el arrastre supera el umbral, sin esperar a que el
 * dedo se levante: en un juego de deslizar, esperar al `onDragEnd` se siente
 * pegajoso. A partir de ahí el gesto queda "consumido" (`fired`) y el resto del
 * arrastre se ignora hasta que el dedo se levanta, para que un único movimiento
 * largo no encadene varias jugadas.
 *
 * La dirección se decide por el **eje dominante** del desplazamiento acumulado
 * (no del último delta): un arrastre en diagonal se resuelve al eje en el que el
 * jugador avanzó más, que es lo que su intención sugiere.
 */
private fun Modifier.swipeGestures(enabled: Boolean, onSwipe: (Direction) -> Unit): Modifier =
    this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val threshold = Neon2048Config.SWIPE_THRESHOLD_DP.dp.toPx()
        var accumulated = Offset.Zero
        var fired = false
        detectDragGestures(
            onDragStart = { accumulated = Offset.Zero; fired = false },
            onDragEnd = { accumulated = Offset.Zero; fired = false },
            onDragCancel = { accumulated = Offset.Zero; fired = false },
        ) { change, delta ->
            change.consume()
            if (fired) return@detectDragGestures
            accumulated += delta
            val dx = accumulated.x
            val dy = accumulated.y
            if (maxOf(abs(dx), abs(dy)) < threshold) return@detectDragGestures
            onSwipe(
                if (abs(dx) > abs(dy)) {
                    if (dx > 0f) Direction.RIGHT else Direction.LEFT
                } else {
                    if (dy > 0f) Direction.DOWN else Direction.UP
                },
            )
            fired = true
        }
    }

// ---------------------------------------------------------------------------
// Ficha
// ---------------------------------------------------------------------------

/**
 * Ficha absorbida en una fusión, animada viajando de [Ghost.from] a [Ghost.to].
 *
 * @param cellSize lado de la casilla.
 * @param stepPx distancia en px entre casillas contiguas (celda + hueco).
 */
@Composable
private fun Neon2048GhostView(ghost: Ghost, cellSize: Dp, stepPx: Int) {
    val fromOffset = IntOffset(x = ghost.from.col * stepPx, y = ghost.from.row * stepPx)
    val toOffset = IntOffset(x = ghost.to.col * stepPx, y = ghost.to.row * stepPx)

    // A diferencia de la ficha superviviente —cuyo `id` persiste entre jugadas y
    // por tanto conserva su animación en curso—, esta composable nace y muere en
    // UNA sola jugada: es su primera Y única composición. Si se animara su offset
    // directamente con `animateIntOffsetAsState`, esa API toma el primer
    // `targetValue` como valor INICIAL (no hay "antes" que animar): el fantasma
    // nacería ya plantado en el destino, sin viajar. Por eso se anima un simple
    // progreso `0f..1f` con `Animatable` (que si arranca en 0 explícitamente) y se
    // interpola la posición a mano entre [fromOffset] y [toOffset]. Mismo
    // `animationSpec` que [Neon2048TileView] para que ambas fichas —la que llega
    // y la que la absorbe— recorran su tramo y se encuentren a la vez.
    val progress = remember(ghost.id) { Animatable(0f) }

    // Por qué el fantasma se desvanece al llegar en vez de quedarse debajo confiando en que
    // la superviviente lo tape: su cuerpo es algo menor que la casilla y el "pop" de fusión
    // escala a la superviviente, así que durante unos frames las dos caras no coinciden y el
    // borde del fantasma asomaría. (Cuando las fichas eran tubos huecos el problema era peor:
    // su número se transparentaba a través de la otra.) Mientras viaja debe verse sólido —si
    // no, el "encuentro" se leería como una ficha ya desvaneciéndose en vez de una fusión— y
    // en cuanto llega se apaga rápido para no depender nunca de que algo lo tape.
    val alpha = remember(ghost.id) { Animatable(1f) }
    LaunchedEffect(ghost.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
        alpha.animateTo(targetValue = 0f, animationSpec = tween(GHOST_FADE_MS))
    }
    val offset = IntOffset(
        x = (fromOffset.x + (toOffset.x - fromOffset.x) * progress.value).roundToInt(),
        y = (fromOffset.y + (toOffset.y - fromOffset.y) * progress.value).roundToInt(),
    )

    Box(
        modifier = Modifier
            .size(cellSize)
            .offset { offset }
            .graphicsLayer { this.alpha = alpha.value },
        contentAlignment = Alignment.Center,
    ) {
        TileFace(value = ghost.value, cellSize = cellSize)
    }
}

/**
 * Una ficha del tablero, con sus tres animaciones.
 *
 * Se compone **dentro de un [key] con `tile.id`**, así que este composable
 * sobrevive a los movimientos y sus `Animatable` conservan su valor entre
 * jugadas — que es justo lo que permite animar en vez de saltar:
 *
 *  1. **Deslizamiento**: el desplazamiento se aplica en el modificador `offset`
 *     con lambda, de modo que al animarse solo se repite la fase de *layout*, sin
 *     recomponer ni redibujar el contenido de la ficha.
 *  2. **Aparición**: la ficha nueva nace a escala 0 y crece con `spring`.
 *  3. **Pop de fusión**: la resultante late a 1.2 y vuelve a 1; a partir de
 *     [MERGE_BURST_MIN_VALUE] suelta además una ráfaga de chispas de su color.
 *
 * @param cellSize lado de la casilla.
 * @param stepPx distancia en px entre casillas contiguas (celda + hueco).
 */
@Composable
private fun Neon2048TileView(tile: Tile, cellSize: Dp, stepPx: Int) {
    val targetOffset = IntOffset(x = tile.col * stepPx, y = tile.row * stepPx)
    // Sin rebote: una ficha que se pasa de casilla y vuelve se lee como un error
    // de posición. El "peso" táctil lo dan la aparición y el pop, no el viaje.
    val offset by androidx.compose.animation.core.animateIntOffsetAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tileOffset",
    )

    val scale = remember { Animatable(if (tile.isNew) 0f else 1f) }
    // Avance 0..1 de la ráfaga de fusión; en reposo vale 1 (= nada que dibujar).
    val burst = remember { Animatable(1f) }

    // Aparición: solo en la primera composición de esta ficha (su id es nuevo).
    LaunchedEffect(Unit) {
        if (tile.isNew) {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    // Pop de fusión. Se recompone la clave con `tile.value` además de la bandera
    // porque una ficha puede fusionarse en jugadas consecutivas: `isMerged` se
    // quedaría en true y el efecto no volvería a lanzarse. El valor, en cambio,
    // se duplica en cada fusión, así que siempre cambia.
    LaunchedEffect(tile.isMerged, tile.value) {
        if (tile.isMerged) {
            // La ráfaga corre en paralelo al pop (dura más que él): si fuera detrás, las
            // chispas saldrían cuando la ficha ya está quieta y se leerían como otro evento.
            if (tile.value >= MERGE_BURST_MIN_VALUE) {
                launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(MERGE_BURST_MS, easing = LinearEasing))
                }
            }
            scale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = MERGE_POP_MS
                    1f at 0
                    MERGE_POP_SCALE at MERGE_POP_MS / 2
                    1f at MERGE_POP_MS
                },
            )
        }
    }

    val accent = tileAccent(tile.power)
    Box(
        modifier = Modifier
            .size(cellSize)
            .offset { offset }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        contentAlignment = Alignment.Center,
    ) {
        // Chispas de fusión, DETRÁS de la cara: salen despedidas por los bordes de la ficha
        // sin tapar su número (encima, el destello y los rayos lo emborronaban justo cuando
        // el jugador quiere leer el valor nuevo). Se salen de los límites a propósito: ni
        // Canvas ni graphicsLayer recortan por defecto.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSparkBurst(
                center = center,
                color = accent,
                reach = size.width * MERGE_BURST_REACH,
                progress = burst.value,
                seed = tile.id.toInt(),
            )
        }
        TileFace(value = tile.value, cellSize = cellSize)
    }
}

/**
 * La cara de una ficha: **cristal oscuro encendido por un tubo de neón**, con su número.
 *
 * El color de la potencia vive en el borde (el tubo compartido, [drawNeonTile], §9.7) y se
 * **derrama hacia dentro** del cristal, que queda teñido pero oscuro; el número brilla con un
 * halo de ese mismo color. Dos versiones anteriores quedaron descartadas:
 *  - solo el tubo hueco: con el tablero medio lleno las fichas se confundían con las casillas
 *    vacías y el número de un fantasma se transparentaba a través de la superviviente;
 *  - un relleno macizo y saturado: se leía como dibujo animado, ajeno al lenguaje de la app
 *    (§9.1: superficie oscura, acento luminoso).
 *
 * El **encendido crece con la potencia** ([tileGlow]): las fichas pequeñas, que son mayoría,
 * quedan tranquilas, y las grandes brillan — el neón vale porque es escaso.
 *
 * Lo comparten la ficha viva y el fantasma para que sean indistinguibles mientras viajan.
 */
@Composable
private fun TileFace(value: Int, cellSize: Dp) {
    val power = value.countTrailingZeroBits()
    val accent = tileAccent(power)
    val glow = tileGlow(power)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val margin = size.width * TILE_MARGIN_FRACTION
        val topLeft = Offset(margin, margin)
        val body = Size(size.width - margin * 2f, size.height - margin * 2f)
        val corner = CornerRadius(size.width * TILE_CORNER_FRACTION)

        // Cristal: opaco (tapa la casilla y cualquier fantasma) y apenas teñido.
        drawRoundRect(
            color = lerp(LogicColors.SurfaceDark, accent, TILE_GLASS_TINT),
            topLeft = topLeft,
            size = body,
            cornerRadius = corner,
        )
        // Resplandor interior: la luz del tubo sobre el cristal, intensa junto al borde y
        // apagándose hacia el centro. Muchas capas finas que se solapan, para que la caída
        // sea suave y no se vean escalones.
        val outline = Path().apply {
            addRoundRect(RoundRect(Rect(topLeft, body), corner))
        }
        clipPath(outline) {
            val reach = size.width * TILE_INNER_GLOW_REACH
            for (i in 1..TILE_INNER_GLOW_LAYERS) {
                drawRoundRect(
                    color = accent.copy(alpha = TILE_INNER_GLOW_ALPHA * (0.55f + 0.45f * glow)),
                    topLeft = topLeft,
                    size = body,
                    cornerRadius = corner,
                    // La mitad del ancho cae dentro del cristal (el resto lo recorta el clip).
                    style = Stroke(width = reach * 2f * i / TILE_INNER_GLOW_LAYERS),
                )
            }
        }
        // Tubo de neón del borde, desde la fuente única del proyecto.
        drawNeonTile(
            baseColor = accent,
            activeAmt = glow,
            cornerRadius = (size.width * TILE_CORNER_FRACTION).toDp(),
            sparks = false,
            baseMargin = margin.toDp(),
            strokeScale = 0.75f,
        )
    }
    Text(
        text = "$value",
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = fontSizeFor(value, cellSize).value.sp,
            // Halo del color de la ficha: el número se lee como rótulo de neón encendido.
            shadow = Shadow(color = accent.copy(alpha = 0.90f), offset = Offset.Zero, blurRadius = 18f),
        ),
        color = lerp(LogicColors.OnDark, accent, TILE_TEXT_TINT),
        textAlign = TextAlign.Center,
    )
}

/**
 * Encendido del borde de una ficha según su exponente: de [TILE_GLOW_MIN] (la ficha 2) a
 * [TILE_GLOW_MAX] al llegar a [TILE_GLOW_FULL_POWER] (128, el mismo umbral que ya dispara la
 * vibración fuerte y los fuegos).
 */
private fun tileGlow(power: Int): Float {
    val t = ((power - 1).toFloat() / (TILE_GLOW_FULL_POWER - 1)).coerceIn(0f, 1f)
    return TILE_GLOW_MIN + (TILE_GLOW_MAX - TILE_GLOW_MIN) * t
}

/**
 * Color de una ficha a partir de su exponente (`log2` del valor, ver [Tile.power]).
 *
 * No hay HEX crudos ni un `when` por valor: la rampa es una lista de tokens de
 * `LogicColors` indexada por el exponente, así que la progresión 2→4→8… recorre
 * el círculo cromático de frío a cálido y el jugador aprende a leer "de un
 * vistazo" cuánto vale una ficha por su color (cian = pequeña, magenta/violeta =
 * grande), sin necesidad de leer el número.
 *
 * Pasado el final de la rampa (fichas de 4096 en adelante, territorio de
 * jugadores expertos) se **cicla** en vez de saturarse en un color final: mantiene
 * la variedad y ninguna ficha se queda sin identidad visual.
 */
private fun tileAccent(power: Int): Color {
    val ramp = TILE_RAMP
    // power vale 1 para la ficha 2; el índice 0 de la rampa le corresponde a ella.
    val index = (power - 1).coerceAtLeast(0)
    return ramp[index % ramp.size]
}

/**
 * Rampa de color por potencia: 2 cian → 4 azul → 8 verde → 16 ámbar →
 * 32 naranja → 64 coral → 128 magenta → 256 violeta → 512 turquesa →
 * 1024 lima → 2048 verde neón (el color de "acierto" de la app, reservado al
 * hito). Todos son tokens de la paleta (CLAUDE.md §9.2), nunca hex sueltos.
 */
private val TILE_RAMP = listOf(
    LogicColors.NeonCyan,
    LogicColors.Blue,
    LogicColors.NeonGreenDeep,
    LogicColors.Amber,
    LogicColors.StreakOrange,
    LogicColors.Coral,
    LogicColors.Magenta,
    LogicColors.Violet,
    CategoryPalette.CognitiveFlexibility,
    LogicColors.Lime,
    LogicColors.NeonGreen,
)

/**
 * Tamaño de fuente del número, proporcional a la casilla y menor cuantos más
 * dígitos tenga: un "1024" con el cuerpo de un "2" se saldría de la ficha. Se
 * deriva del tamaño real de celda (y no de constantes fijas) para que el tablero
 * se vea igual de equilibrado en un móvil pequeño y en una tablet.
 */
private fun fontSizeFor(value: Int, cellSize: Dp): Dp = when {
    value < 100 -> cellSize * 0.42f
    value < 1000 -> cellSize * 0.32f
    else -> cellSize * 0.24f
}

/**
 * Radio de esquina de ficha/casilla, proporcional al tamaño real de celda en vez
 * de una constante fija.
 *
 * Con tablero seleccionable (4×4 a 8×8, ver [Neon2048Config.BOARD_SIZE_OPTIONS])
 * la celda deja de tener un tamaño único: en un 8×8 puede rondar la mitad de
 * ancho que en un 4×4. Un radio fijo pensado para la celda grande del 4×4 se ve
 * casi circular en una celda pequeña del 8×8 y se come la legibilidad del
 * número. El factor `0.22f` está calibrado para reproducir exactamente
 * [TILE_CORNER] (14dp) en el tamaño de celda del tablero 4×4 por defecto, así
 * que el look actual no cambia; el `coerceAtMost` evita que un tablero muy
 * pequeño (pocas celdas, celdas grandes) supere el radio "de marca" del resto
 * de tiles neón de la app.
 */
private fun tileCornerFor(cellSize: Dp): Dp = (cellSize * 0.22f).coerceAtMost(TILE_CORNER)

// ---------------------------------------------------------------------------
// HUD y overlays
// ---------------------------------------------------------------------------

/**
 * Cabecera: puntuación actual, récord y ficha más alta alcanzada.
 *
 * Deja de ser tres cajas iguales: la **puntuación** es lo que el jugador persigue en cada
 * jugada, así que va grande, late al sumar y enseña cuánto acaba de ganar ("+16"); el
 * **récord** es referencia y va en una píldora discreta; y la **ficha más alta** se enseña como
 * lo que es, una ficha en miniatura con su color.
 */
@Composable
private fun Neon2048Hud(score: Int, bestScore: Int, highest: Int, modifier: Modifier = Modifier) {
    val accent = CategoryPalette.MentalMath
    val scorePop = remember { Animatable(1f) }
    // Avance 0..1 del "+N" que sube y se apaga; en reposo vale 1 (invisible).
    val gainRise = remember { Animatable(1f) }
    var gain by remember { mutableIntStateOf(0) }
    var lastScore by remember { mutableIntStateOf(score) }
    LaunchedEffect(score) {
        val delta = score - lastScore
        lastScore = score
        // Solo al SUMAR: reiniciar la partida (la puntuación baja a 0) no es un logro.
        if (delta <= 0) return@LaunchedEffect
        gain = delta
        launch {
            gainRise.snapTo(0f)
            gainRise.animateTo(1f, tween(GAIN_RISE_MS, easing = LinearEasing))
        }
        scorePop.snapTo(SCORE_POP_SCALE)
        scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.neon2048_hud_score).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                    color = LogicColors.OnDarkMuted,
                )
                Text(
                    text = stringResource(Res.string.neon2048_hud_gain, gain.toString()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = accent,
                    modifier = Modifier.graphicsLayer {
                        val p = gainRise.value
                        // Aparece de golpe y se apaga subiendo: se lee como algo que "sale" de la jugada.
                        alpha = if (p >= 1f) 0f else (1f - p * p)
                        translationY = -GAIN_RISE.toPx() * p
                    },
                )
            }
            Text(
                text = "$score",
                style = MaterialTheme.typography.displayLarge,
                color = LogicColors.OnDark,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    scaleX = scorePop.value
                    scaleY = scorePop.value
                    // Crece desde la izquierda: el número está alineado a ese lado.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
        }

        Row(
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.dp, LogicColors.Amber.copy(alpha = 0.50f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonIcon(
                icon = KortexIcons.Trophy,
                tint = LogicColors.Amber,
                size = 18.dp,
                glow = false,
                contentDescription = stringResource(Res.string.neon2048_hud_best),
            )
            Text(
                text = "$bestScore",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = LogicColors.OnDark,
            )
        }

        HighestTileBadge(highest = highest)
    }
}

/**
 * La ficha más alta de la partida, como ficha en miniatura (misma cara que las del tablero,
 * [TileFace]). Late cada vez que el jugador la supera: es el hito que más ilusión hace.
 */
@Composable
private fun HighestTileBadge(highest: Int) {
    // Con el tablero vacío no hay ficha que enseñar; se pinta la más pequeña como marcador.
    val shown = if (highest <= 0) Neon2048Config.SPAWN_VALUE else highest
    val pop = remember { Animatable(1f) }
    var last by remember { mutableIntStateOf(shown) }
    LaunchedEffect(shown) {
        val grew = shown > last
        last = shown
        if (!grew) return@LaunchedEffect
        pop.snapTo(HIGHEST_POP_SCALE)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    val label = stringResource(Res.string.neon2048_hud_max)
    Box(
        modifier = Modifier
            .size(HighestBadgeSize)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .semantics(mergeDescendants = true) { contentDescription = "$label: $shown" },
        contentAlignment = Alignment.Center,
    ) {
        TileFace(value = shown, cellSize = HighestBadgeSize)
    }
}

/**
 * Overlay del hito de 2048. Deliberadamente **no** reutiliza `GameOverOverlay`:
 * la partida no ha terminado y ese componente cierra la sesión (guarda, muestra
 * percentil y ofrece salir). Aquí la decisión del jugador es otra: seguir jugando
 * sobre el mismo tablero o empezar de cero.
 *
 * Sí comparte su **lenguaje**: velo, tarjeta con halo y entrada escalonada salen de
 * `GameModal` (los mismos del menú de pausa y el fin de partida), para que este diálogo no
 * parezca de otra app.
 */
@Composable
private fun WinOverlay(onContinue: () -> Unit, onRestart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim(WIN_SCRIM_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .modalCard(LogicColors.NeonGreen)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            ModalReveal(index = 0, visible = true) {
                // La propia ficha 2048 como trofeo: es lo que el jugador acaba de conseguir.
                Box(modifier = Modifier.size(WinTileSize), contentAlignment = Alignment.Center) {
                    TileFace(value = Neon2048Config.WINNING_VALUE, cellSize = WinTileSize)
                }
            }
            ModalReveal(index = 1, visible = true) {
                Text(
                    text = stringResource(Res.string.neon2048_win_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = LogicColors.NeonGreen,
                )
            }
            ModalReveal(index = 2, visible = true) {
                Text(
                    text = stringResource(Res.string.neon2048_win_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                )
            }
            ModalReveal(index = 3, visible = true) {
                AnimatedGameButton(
                    text = stringResource(Res.string.neon2048_win_continue),
                    onClick = onContinue,
                    gradient = com.kortexgames.app.core.theme.LogicGradients.play,
                )
            }
            ModalReveal(index = 4, visible = true) {
                AnimatedGameButton(text = stringResource(Res.string.neon2048_win_restart), onClick = onRestart)
            }
        }
    }
}

// --- Constantes de render (el balance del juego vive en Neon2048Config) --------

/** Margen interior del tablero (el "marco" alrededor de la rejilla). */
private val BOARD_PADDING = 10.dp

/** Separación entre casillas contiguas. */
private val CELL_GAP = 8.dp

/** Tope del radio de esquina de ficha y casilla vacía (más cerrado que el de
 *  tarjeta: la ficha es un elemento pequeño y con 24dp se vería casi circular).
 *  El radio real que se dibuja sale de [tileCornerFor], proporcional a la celda. */
private val TILE_CORNER = 14.dp

/** Margen entre el cristal de la ficha y el borde de su casilla (deja sitio al halo del tubo). */
private const val TILE_MARGIN_FRACTION = 0.035f

/** Radio de esquina del cristal, en fracción del lado de la casilla. */
private const val TILE_CORNER_FRACTION = 0.18f

/** Cuánto del color de la ficha tiñe el cristal. Bajo: la cara es oscura y el color lo pone la
 *  luz del borde; por encima de ~0.4 vuelve a parecer un relleno plano. */
private const val TILE_GLASS_TINT = 0.10f

/** Hasta dónde entra el resplandor del borde en el cristal, en fracción del lado. */
private const val TILE_INNER_GLOW_REACH = 0.24f

/** Capas del resplandor interior y opacidad de cada una (se suman hacia el borde). */
private const val TILE_INNER_GLOW_LAYERS = 9
private const val TILE_INNER_GLOW_ALPHA = 0.05f

/** Cuánto del color de la ficha lleva el número (el resto es blanco): legible pero teñido. */
private const val TILE_TEXT_TINT = 0.12f

/** Encendido del borde de la ficha más pequeña. */
private const val TILE_GLOW_MIN = 0.45f

/** Encendido del borde de una ficha grande. */
private const val TILE_GLOW_MAX = 0.95f

/** Exponente al que el borde alcanza su encendido máximo (2^7 = 128). */
private const val TILE_GLOW_FULL_POWER = 7

/**
 * Valor mínimo de la ficha resultante para que la fusión suelte chispas. Por debajo solo late:
 * las fusiones de 2 y 4 ocurren en casi cada jugada y con chispas el tablero no descansaría.
 */
private const val MERGE_BURST_MIN_VALUE = 16

/** Duración de la ráfaga de chispas de una fusión. */
private const val MERGE_BURST_MS = 420

/** Alcance de la ráfaga, en lados de ficha. */
private const val MERGE_BURST_REACH = 1.0f

/** Escala inicial del latido de la puntuación al sumar. */
private const val SCORE_POP_SCALE = 1.18f

/** Vida del "+N" que sube junto a la puntuación. */
private const val GAIN_RISE_MS = 750

/** Cuánto sube el "+N" mientras se apaga. */
private val GAIN_RISE = 10.dp

/** Lado de la ficha en miniatura del HUD. */
private val HighestBadgeSize = 54.dp

/** Escala inicial del latido de la ficha más alta al superarse. */
private const val HIGHEST_POP_SCALE = 1.35f

/** Lado de la ficha-trofeo del overlay de 2048. */
private val WinTileSize = 96.dp

/** Opacidad del velo del overlay de 2048 (deja entrever el tablero ganador). */
private const val WIN_SCRIM_ALPHA = 0.72f

/** Duración del "pop" de fusión: corto para que no retrase la siguiente jugada. */
private const val MERGE_POP_MS = 180

/** Escala máxima del "pop" de fusión. */
private const val MERGE_POP_SCALE = 1.2f

/**
 * Duración del desvanecido del fantasma al llegar a destino. Corta a propósito:
 * es solo la red de seguridad que garantiza que desaparece del todo (ver el
 * porqué en [Neon2048GhostView]), no un efecto que deba notarse por sí mismo.
 */
private const val GHOST_FADE_MS = 120

/**
 * Celebración de fusión grande (>64) en curso.
 *
 * @property id identificador/semilla de esta celebración concreta: fuerza a
 *   [FireworksOverlay] a reiniciar su patrón vía `key(id)` si llegan dos
 *   fusiones grandes seguidas, y sirve al LaunchedEffect de auto-cierre para
 *   distinguir "sigue siendo esta" de "ya la reemplazó una más nueva".
 * @property accent color de la ficha que se acaba de fusionar (tiñe los fuegos).
 */
private data class MergeCelebration(val id: Int, val accent: Color)

/** Nº de estallidos de la salva de fusión: menos que la de victoria (por
 *  defecto 6) porque esta puede repetirse varias veces por partida — una
 *  celebración tan grande como la del 2048 en cada fusión perdería impacto. */
private const val MERGE_CELEBRATION_BURSTS = 3

/** Vida total de la salva de fusión (holgada sobre el peor caso real de
 *  [MERGE_CELEBRATION_BURSTS] estallidos) antes de retirar el overlay del árbol. */
private const val MERGE_CELEBRATION_MS = 1800L
