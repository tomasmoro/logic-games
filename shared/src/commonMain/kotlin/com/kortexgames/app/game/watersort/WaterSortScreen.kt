package com.kortexgames.app.game.watersort

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGamesTheme
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.AdLoadingOverlay
import com.kortexgames.app.ui.components.ArcadeBrickBackground
import com.kortexgames.app.ui.components.GameActionButton
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.watersort_hud_level
import kortexgames.shared.generated.resources.watersort_hud_moves
import kortexgames.shared.generated.resources.watersort_hud_moves_one
import kortexgames.shared.generated.resources.watersort_hud_progress_description
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

/**
 * Factor de velocidad de un vertido "con prisa": cuando el jugador toca cualquier
 * frasco mientras hay vertidos en curso, estos NO se cortan — terminan su animación
 * completa, pero acelerada, para no hacerle esperar ni perder el feedback.
 */
private const val RUSH_SPEED = 2.6f

/**
 * Un vertido cuya animación está en curso. La pantalla lleva su propia lista en vez
 * de depender de `WaterSortState.lastPour`: el motor pisa ese campo en cuanto llega
 * la siguiente acción (seleccionar otro frasco lo pone a null, otro vertido lo
 * reemplaza) y la animación se cortaba a medias. Con la lista, cada vertido vive
 * hasta terminar y varios pueden animarse a la vez (siempre sobre frascos distintos:
 * los que participan en uno no aceptan jugadas hasta que acaba).
 *
 * @property linear progreso lineal 0→1 (el suavizado se aplica al leerlo).
 * @property rushed true si debe terminar acelerado (ver [RUSH_SPEED]).
 */
private class PourAnimation(val event: PourEvent) {
    var linear by mutableFloatStateOf(0f)
    var rushed by mutableStateOf(false)
}

/** Foto de un vertido en este frame: su evento y los factores ya resueltos. */
private class PourFrame(val event: PourEvent, val p: Float) {
    /** Fracción 0→1 del trasvase de líquido (solo avanza en la fase de derrame). */
    val pourFactor: Float = ((p - POUR_START) / (POUR_END - POUR_START)).coerceIn(0f, 1f)
}

// --- Disposición de la estantería --------------------------------------------
/** Ancho máximo de un frasco; por debajo se adapta al hueco (ver la estantería). */
private val MaxTubeWidth = 64.dp

/** Separación horizontal entre frascos de una misma repisa. */
private val TubeGap = 14.dp

/**
 * Separación vertical entre repisas. Holgada a propósito: entre fila y fila tienen
 * que caber la repisa de abajo, el frasco levantado de la fila siguiente y su corcho.
 */
private val ShelfGap = 52.dp

/** Aire reservado arriba de la estantería para el frasco levantado y el corcho. */
private val RackHeadroom = 44.dp

/** Cuánto sube el frasco seleccionado. */
private val LiftHeight = 18.dp

// --- Celebraciones -------------------------------------------------------------
/** Desfase entre frascos en la ola de saltos de la victoria (segundos). */
private const val VICTORY_STAGGER_S = 0.07f

/** Duración del salto de un frasco en la ola de la victoria (segundos). */
private const val VICTORY_HOP_S = 0.5f

/**
 * Tiempo que la pantalla se queda celebrando antes de mostrar el cartel de fin de
 * nivel: sin esta pausa el overlay tapaba la ola y las chispas nada más empezar.
 */
private const val VICTORY_HOLD_MS = 1300L

/** Desfase de la caída de cada frasco al montar el nivel (entrada escalonada). */
private const val ENTRY_STAGGER_MS = 55

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
        WaterSortViewModel(
            graph.progressRepository,
            graph.playerProgressRepository,
            graph.savedGameStateRepository,
            graph.audio,
            graph.adManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la partida en curso antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // Fase de intro: antesala del juego (icono, descripción y carril de niveles). El
    // nivel elegido arranca por defecto en la frontera (récord + 1) y se reinicia si el
    // récord sube; "Comenzar" arranca el motor y pasamos a la vista de juego.
    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        var selectedLevel by remember(state.maxUnlocked) { mutableStateOf(state.maxUnlocked + 1) }
        // Comparativa mundial del nivel resaltado, ANTES de jugarlo: se relanza en
        // cada cambio de nivel del carril (y en la entrada a la antesala, con el
        // nivel por defecto). Ver WaterSortViewModel.refreshRankingPreview.
        LaunchedEffect(selectedLevel) { vm.onIntent(WaterSortIntent.PreviewLevel(selectedLevel)) }
        GameIntroScreen(
            help = GameHelpContent.waterSort,
            title = "Ordena las Pociones",
            description = "Vierte colores iguales hasta dejar cada tubo de un solo color.",
            accent = CategoryPalette.Logic,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
            ),
            motif = GameMotif.POTIONS,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.WATER_SORT)
                vm.onIntent(WaterSortIntent.PlayLevel(selectedLevel))
            },
            // Partida a medias guardada al salir: la antesala la ofrece como CTA
            // principal, con su nivel para que el jugador sepa qué retoma.
            resume = state.savedLevel?.let { level ->
                ResumeState(
                    onResume = { vm.onIntent(WaterSortIntent.ResumeSaved) },
                    detail = "Nivel $level en curso",
                )
            },
            // Ranking mundial del nivel elegido, justo encima del CTA (mismo panel que
            // el diálogo de fin de nivel; mismo mecanismo que Neon Grid 2048): pedido
            // explícito para que la antesala también responda "¿cómo me va ahí?".
            configContent = {
                val preview = state.rankingPreview
                when {
                    state.rankingPreviewLoading -> WorldRankingLoading()
                    preview != null -> WorldRankingPreviewPanel(ranking = preview)
                    else -> RankingPreviewUnavailable(difficultyLabel = "Nivel $selectedLevel")
                }
            },
            onExit = onExit,
            background = {
                ArcadeBrickBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.Logic)
            },
        )
        return
    }

    val game = state.game

    // Reloj único de animación (olas, burbujas, motas, chispas). Se congela en pausa.
    val clock = rememberPotionClock(running = state.status != GameStatus.PAUSED)

    // Posición (en coords de ROOT) de cada tubo y del contenedor. Se **congelan
    // mientras NO se anima**: leer las coordenadas en vivo justo cuando el layout
    // cambia (el origen pasa a placeholder) devolvía left=0 durante un frame — el
    // "destello" del tubo a la izquierda. Al usar el último valor estable, el tubo
    // viajero arranca desde la posición correcta ya en el primer frame.
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val tubeBounds = remember { mutableStateMapOf<Int, Rect>() }

    // Vertidos con animación en curso (ver PourAnimation) y el id del último que ya
    // se registró. Arranca en el `lastPour` que hubiera al componer para no repetir
    // la animación de un vertido viejo (p. ej. al recrearse la pantalla).
    val pours = remember { mutableStateListOf<PourAnimation>() }
    var registeredPourId by remember { mutableStateOf(game.lastPour?.id) }
    val scope = rememberCoroutineScope()

    // Anuncios: "Tubo extra" y el deshacer de pago piden el rewarded real al
    // AdManager (mismo trato que Neon Sudoku/Desactivador — pulsar el botón YA es
    // la confirmación del jugador, así que el anuncio se lanza directo, sin overlay
    // de por medio) y, si concede la recompensa, envían el intent que ejecuta la
    // acción. El `collect` es secuencial pero el Channel del efecto está bufferizado
    // (ver Mvi.kt), así que no se pierde ningún efecto mientras se espera el anuncio.
    // `awaitingAd` solo alimenta el feedback visual (AdLoadingOverlay más abajo): sin
    // él, pulsar el botón no mostraba nada en pantalla durante la carga real del
    // anuncio (puede tardar varios segundos) y parecía que el botón no hacía nada.
    var awaitingAd by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            val onEarned = when (effect) {
                WaterSortEffect.ShowRewardedAd -> WaterSortIntent.ExtraTubeRewarded
                WaterSortEffect.ShowUndoAd -> WaterSortIntent.UndoRewarded
            }
            awaitingAd = true
            val result = graph.adManager.showRewardedAd()
            awaitingAd = false
            if (result == RewardResult.EARNED) {
                vm.onIntent(onEarned)
            }
        }
    }

    val pour = game.lastPour

    LaunchedEffect(pour?.id) {
        val event = pour ?: return@LaunchedEffect
        if (event.id == registeredPourId) return@LaunchedEffect
        registeredPourId = event.id
        val anim = PourAnimation(event)
        pours += anim
        // Se lanza en el scope de la pantalla, NO en este efecto: el efecto se cancela
        // en cuanto `lastPour` cambia (que es justo lo que cortaba la animación).
        scope.launch {
            var last = withFrameNanos { it }
            while (anim.linear < 1f) {
                val now = withFrameNanos { it }
                val speed = if (anim.rushed) RUSH_SPEED else 1f
                anim.linear = (anim.linear + (now - last) / 1_000_000f / POUR_DURATION_MS * speed).coerceAtMost(1f)
                last = now
            }
            pours -= anim
        }
    }

    // Vertidos a pintar en este frame. El recién emitido que aún no está registrado
    // (el LaunchedEffect corre DESPUÉS de componer) se incluye con p=0: así se pinta
    // ya su estado inicial y no hay un "frame fantasma" con el estado final.
    val frames: List<PourFrame> = buildList {
        pours.forEach { add(PourFrame(it.event, FastOutSlowInEasing.transform(it.linear))) }
        if (pour != null && pour.id != registeredPourId) add(PourFrame(pour, 0f))
    }
    val animating = frames.isNotEmpty()

    /** Acelera los vertidos en curso: el jugador ya quiere seguir jugando. */
    fun rushPours() = pours.forEach { it.rushed = true }

    /** Rect del tubo [i] en coordenadas del contenedor (o null si aún no medido). */
    fun boundsOf(i: Int): Rect? =
        tubeBounds[i]?.translate(-containerOrigin.x, -containerOrigin.y)

    /**
     * ¿El frasco [i] está completado **a la vista**? El motor ya da por lleno el
     * destino en cuanto se decide el vertido, pero en pantalla el líquido aún está
     * cayendo: hasta que termina de caer no se celebra (ni brilla, ni cuenta en el
     * HUD), para que el premio coincida con lo que el jugador ve.
     */
    fun settledComplete(i: Int, tube: Tube): Boolean =
        tube.isGlowing(game.capacity) && frames.none { it.event.to == i && it.pourFactor < 1f }

    // Estallidos de chispas vivos. Cada uno es solo "dónde y cuándo"; se podan al
    // añadir uno nuevo (no hace falta un bucle de limpieza para una lista tan corta).
    val bursts = remember { mutableStateListOf<PotionBurst>() }
    fun burstAt(index: Int, color: Color, at: Float) {
        val rect = boundsOf(index) ?: return
        bursts.removeAll { clock.value - it.start > BURST_LIFETIME_S }
        bursts += PotionBurst(start = at, center = Offset(rect.center.x, rect.top + rect.height * 0.12f), color = color, seed = index + bursts.size * 7)
    }

    // Victoria: cuando el último vertido termina de caer, los frascos saltan en ola
    // con sus chispas y, tras una pausa corta, aparece el cartel de fin de nivel.
    var victoryAt by remember { mutableStateOf<Float?>(null) }
    var celebrated by remember { mutableStateOf(false) }
    LaunchedEffect(game.solved, animating) {
        if (!game.solved) {
            victoryAt = null
            celebrated = false
            return@LaunchedEffect
        }
        if (animating) return@LaunchedEffect
        val now = clock.value
        victoryAt = now
        game.tubes.forEachIndexed { i, tube ->
            tube.topColorOrNull()?.let { burstAt(i, it, now + i * VICTORY_STAGGER_S + VICTORY_HOP_S / 2f) }
        }
        delay(VICTORY_HOLD_MS)
        celebrated = true
    }

    Box(Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Textura ambiental de muro arcade "neo-retro" (azul Lógica), muy sutil, y
        // encima el ambiente de laboratorio (foco de luz + motas que suben).
        ArcadeBrickBackground(
            modifier = Modifier.fillMaxSize(),
            accent = CategoryPalette.Logic,
        )
        PotionLabAmbience(clock = clock, accent = CategoryPalette.Logic, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Colores del nivel y cuáles están ya completados → orbes de progreso.
            val levelColors = remember(game.tubes) { game.tubes.flatMap { it.segments }.distinct().sorted() }
            val completedColors = game.tubes
                .filterIndexed { i, tube -> settledComplete(i, tube) }
                .map { it.segments.first() }
                .toSet()
            PotionHud(
                level = game.round,
                moves = game.moves,
                colors = levelColors,
                completed = completedColors,
            )

            // Tubos repartidos en dos filas equilibradas (como el clásico).
            val perRow = ((game.tubes.size + 1) / 2).coerceAtLeast(1)
            val rows = game.tubes.chunked(perRow)

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { containerOrigin = it.boundsInRoot().topLeft },
                contentAlignment = Alignment.Center,
            ) {
                // El frasco crece hasta llenar el hueco (antes medía 54dp fijos y dejaba
                // media pantalla vacía en niveles con pocos tubos), limitado por el ancho
                // de la fila más larga y por el alto disponible para las dos repisas.
                val rowCount = rows.size.coerceAtLeast(1)
                val byWidth = (maxWidth - TubeGap * (perRow - 1)) / perRow
                val byHeight = (maxHeight - ShelfGap * (rowCount - 1) - RackHeadroom) / rowCount * TubeAspect
                val tubeWidth = minOf(MaxTubeWidth, byWidth, byHeight)

                Column(
                    modifier = Modifier.padding(top = RackHeadroom / 2),
                    verticalArrangement = Arrangement.spacedBy(ShelfGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    rows.forEachIndexed { rowIdx, rowTubes ->
                        // La fila que contiene el origen se dibuja por encima de las demás,
                        // para que el frasco pueda "volar" sobre la otra fila (§ z-order).
                        val rowHasSource = frames.any { it.event.from / perRow == rowIdx }
                        Row(
                            modifier = Modifier
                                .zIndex(if (rowHasSource) 1f else 0f)
                                .drawBehind { drawShelf(CategoryPalette.Logic) },
                            horizontalArrangement = Arrangement.spacedBy(TubeGap),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            rowTubes.forEachIndexed { colIdx, tube ->
                                val index = rowIdx * perRow + colIdx
                                val asSource = frames.firstOrNull { it.event.from == index }
                                val asDest = frames.firstOrNull { it.event.to == index }
                                val isSource = asSource != null
                                val isDest = asDest != null
                                val isSel = game.selected == index
                                val top = tube.topColorOrNull()

                                // El origen VIAJA en su propio sitio (mismo nodo → nunca
                                // recién compuesto, sin destello): se traslada y gira con
                                // graphicsLayer hacia el destino. El destino se rellena in
                                // situ; el resto, normal. Bandas fraccionarias animan el
                                // trasvase (origen drena, destino sube) según `pourFactor`.
                                val bands = when {
                                    asSource != null -> sourceBands(tube, asSource.event, asSource.pourFactor)
                                    asDest != null -> destBands(tube, asDest.event, asDest.pourFactor)
                                    else -> tube.segments.map { LiquidBand(it, 1f) }
                                }

                                var travelX = 0f
                                var travelY = 0f
                                var angle = 0f
                                if (asSource != null) {
                                    val fromRect = boundsOf(index)
                                    val destRect = boundsOf(asSource.event.to)
                                    if (fromRect != null && destRect != null) {
                                        val travel = travelFactor(asSource.p)
                                        val dir = if (destRect.center.x >= fromRect.center.x) 1f else -1f
                                        val cur = travelingRect(fromRect, destRect, dir, travel)
                                        // Traslación relativa a SU sitio (por eso -from).
                                        travelX = cur.left - fromRect.left
                                        travelY = cur.top - fromRect.top
                                        angle = dir * TILT_DEGREES * travel
                                    }
                                }

                                // Seleccionado o vertiendo → por encima de sus vecinos.
                                val elevated = isSel || isSource
                                val completed = settledComplete(index, tube)
                                TubeView(
                                    bands = bands,
                                    capacity = game.capacity,
                                    seed = index,
                                    clock = clock,
                                    // Borde neón: color vertido si está sirviendo (se conserva
                                    // al derramar), o color superior si está seleccionado.
                                    highlightColor = when {
                                        asSource != null -> PotionColors[asSource.event.color]
                                        isSel -> top
                                        else -> null
                                    },
                                    lifted = isSel,
                                    completed = completed,
                                    glowColor = top ?: LogicColors.NeonCyan,
                                    // El reflejo en la repisa toma el color del fondo del frasco.
                                    reflectionColor = bands.firstOrNull()?.let { PotionColors[it.colorIndex] },
                                    grounded = !isSel && !isSource,
                                    tiltDegrees = angle,
                                    travelX = travelX,
                                    travelY = travelY,
                                    // El líquido se agita mientras se vierte (y un rato después).
                                    agitated = isSource || isDest,
                                    rejectId = game.lastReject?.takeIf { it.to == index }?.id,
                                    entryKey = game.round,
                                    entryDelayMs = index * ENTRY_STAGGER_MS,
                                    hopAt = victoryAt?.let { it + index * VICTORY_STAGGER_S },
                                    onCompleted = { top?.let { burstAt(index, it, clock.value) } },
                                    // Tocar cualquier frasco durante un vertido lo ACELERA en vez
                                    // de cortarlo. Los que participan en un vertido no aceptan
                                    // jugada hasta que termina (su contenido aún está en el
                                    // aire); el resto se juega con normalidad, en paralelo.
                                    enabled = true,
                                    onClick = {
                                        rushPours()
                                        if (!isSource && !isDest) vm.onIntent(WaterSortIntent.TapTube(index))
                                    },
                                    modifier = Modifier
                                        .width(tubeWidth)
                                        .zIndex(if (elevated) 1f else 0f)
                                        // Congela la posición SOLO mientras no se anima; durante
                                        // el vertido se reutiliza el último valor estable.
                                        .onGloballyPositioned { coords ->
                                            if (coords.isAttached && !animating) {
                                                val r = coords.boundsInRoot()
                                                if (tubeBounds[index] != r) tubeBounds[index] = r
                                            }
                                        },
                                )
                            }
                        }
                    }
                }

                // --- Capa superior: chorro neón y chispas (el tubo viaja en la propia rejilla) ---
                Canvas(Modifier.matchParentSize()) {
                    val time = clock.value
                    frames.forEach { frame ->
                        val event = frame.event
                        val fromRect = boundsOf(event.from) ?: return@forEach
                        val destRect = boundsOf(event.to) ?: return@forEach
                        val travel = travelFactor(frame.p)
                        val dir = if (destRect.center.x >= fromRect.center.x) 1f else -1f
                        val current = travelingRect(fromRect, destRect, dir, travel)
                        // El chorro cae hasta la SUPERFICIE del líquido del destino
                        // (que sube mientras se llena), no hasta la boca: se ve
                        // atravesar el vidrio y romper contra la poción.
                        val (bottomInset, slotH) = liquidMetrics(destRect.height, game.capacity)
                        val destTube = game.tubes.getOrNull(event.to) ?: return@forEach
                        val filled = destTube.segments.size - event.count + event.count * frame.pourFactor
                        drawPourStream(
                            lip = lipPoint(current, dir, dir * TILT_DEGREES * travel),
                            impact = Offset(
                                x = destRect.center.x + dir * destRect.width * 0.05f,
                                y = destRect.bottom - bottomInset - filled * slotH,
                            ),
                            color = PotionColors[event.color],
                            pourFactor = frame.pourFactor,
                            time = time,
                        )
                    }
                    drawPotionBursts(bursts, time)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Barra de acciones: Deshacer y Reiniciar (bloqueadas mientras sirve).
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                GameActionButton(
                    icon = KortexIcons.Undo,
                    label = "Deshacer",
                    tint = LogicColors.NeonCyan,
                    enabled = game.canUndo && !animating,
                    // El primer deshacer del intento es gratis; a partir de ahí cuesta
                    // un anuncio y el botón lo anuncia con el distintivo (petición del
                    // usuario). El cobro lo resuelve el ViewModel: aquí siempre se
                    // manda el mismo intent.
                    costsAd = !game.nextUndoIsFree,
                    onClick = { vm.onIntent(WaterSortIntent.Undo) },
                )
                GameActionButton(
                    icon = KortexIcons.Refresh,
                    label = "Reiniciar",
                    tint = LogicColors.Amber,
                    enabled = !animating,
                    // Reinicio inmediato, sin anuncio (petición del usuario: demasiados
                    // anuncios en este juego).
                    onClick = { vm.onIntent(WaterSortIntent.Restart) },
                )
                // Ayuda opcional monetizada: ver un anuncio para sumar un tubo vacío.
                // Se oculta al agotar el cupo (canAddTube = false) para no ofrecer una
                // acción imposible; deshabilitado mientras un vertido está en curso.
                if (game.canAddTube) {
                    GameActionButton(
                        icon = KortexIcons.RewardedAd,
                        label = "Tubo extra",
                        tint = LogicColors.NeonGreen,
                        enabled = !animating && !game.solved,
                        onClick = { vm.onIntent(WaterSortIntent.WatchAdForExtraTube) },
                    )
                }
            }
        }

        // El overlay de fin de partida espera a que termine el último vertido Y la
        // celebración (ola de saltos + chispas) para no taparlos.
        if (state.status == GameStatus.FINISHED && state.gameOver != null && !animating && celebrated) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(WaterSortIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(WaterSortIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(WaterSortIntent.ChooseLevel) },
                accent = CategoryPalette.Logic,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(WaterSortIntent.Pause) },
            onResume = { vm.onIntent(WaterSortIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Ordena las Pociones",
            help = GameHelpContent.waterSort,
            accent = CategoryPalette.Logic,
            exitKeepsProgress = true,
            // Reiniciar no tiene coste de anuncio en este juego (ver el botón equivalente
            // del HUD más arriba), así que es seguro ofrecerlo también desde la pausa;
            // se reanuda a la vez para volver directo al tablero ya limpio.
            onRestart = {
                vm.onIntent(WaterSortIntent.Restart)
                vm.onIntent(WaterSortIntent.Resume)
            },
        )

        // Feedback de "cargando anuncio" mientras se resuelve el rewarded del tubo
        // extra o el deshacer de pago (ver el LaunchedEffect de `awaitingAd` más arriba).
        AdLoadingOverlay(visible = awaitingAd, accent = CategoryPalette.Logic)

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la partida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(WaterSortIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = CategoryPalette.Logic,
        )
    }
}

/**
 * Cabecera de partida: píldora de nivel, contador de movimientos (con "pop" al
 * cambiar) y una fila de **orbes de progreso**, uno por color del nivel, que se
 * encienden a medida que se completa su poción. Sustituye a la cabecera de texto
 * (título + instrucción): el título ya está en la antesala y en el menú de pausa, y
 * los orbes responden a "¿cuánto me falta?" de un vistazo, que es lo que un HUD de
 * juego debe decir.
 *
 * @param colors índices de color presentes en el nivel, en orden estable.
 * @param completed subconjunto de [colors] cuya poción ya está completa.
 */
@Composable
private fun PotionHud(level: Int, moves: Int, colors: List<Int>, completed: Set<Int>) {
    val accent = CategoryPalette.Logic
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Alto fijo = el del botón de pausa (esquina superior derecha), para que la
        // píldora quede centrada en su misma línea.
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.watersort_hud_level, level.toString()).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
                color = accent,
                modifier = Modifier
                    .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                    .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        // "Pop" del contador en cada vertido: confirma la jugada sin distraer.
        val pop = remember { Animatable(1f) }
        LaunchedEffect(moves) {
            if (moves > 0) {
                pop.snapTo(1.28f)
                pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = moves.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = LogicColors.OnDark,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (moves == 1) Res.string.watersort_hud_moves_one else Res.string.watersort_hud_moves,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
        Spacer(Modifier.height(10.dp))

        val progressLabel = stringResource(
            Res.string.watersort_hud_progress_description,
            completed.size.toString(),
            colors.size.toString(),
        )
        Row(
            modifier = Modifier.semantics { contentDescription = progressLabel },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { color -> GoalOrb(color = PotionColors[color], lit = color in completed) }
        }
    }
}

/**
 * Orbe de progreso de un color: aro tenue mientras su poción está pendiente y
 * esfera encendida con halo cuando se completa. El encendido usa un resorte con
 * rebote (§9.4) para que completar un frasco "pague" también en el HUD.
 */
@Composable
private fun GoalOrb(color: Color, lit: Boolean) {
    val amount by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "goalOrb",
    )
    Canvas(Modifier.size(20.dp)) {
        val on = amount.coerceIn(0f, 1f)
        val radius = size.minDimension * 0.30f * (1f + 0.28f * amount)
        if (on > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f * on), Color.Transparent),
                    radius = size.minDimension * 0.85f,
                ),
                radius = size.minDimension * 0.85f,
            )
        }
        drawCircle(color.copy(alpha = 0.14f + 0.86f * on), radius)
        drawCircle(color.copy(alpha = 0.55f + 0.45f * on), radius, style = Stroke(1.4.dp.toPx()))
        if (on > 0f) {
            drawCircle(
                color = Color.White.copy(alpha = 0.75f * on),
                radius = radius * 0.26f,
                center = center - Offset(radius * 0.32f, radius * 0.32f),
            )
        }
    }
}

/**
 * Repisa de neón sobre la que descansa una fila de frascos: una balda oscura con
 * un filo de luz del color de la categoría. Se dibuja por detrás de la fila y
 * sobresale por los lados; ancla visualmente los frascos (antes flotaban en el
 * muro) y da pie al reflejo de color de cada uno ([drawShelfReflection]).
 */
private fun DrawScope.drawShelf(accent: Color) {
    val over = 16.dp.toPx()
    val y = size.height + 5.dp.toPx()
    val thickness = 5.dp.toPx()
    val start = Offset(-over, y)
    val length = size.width + over * 2f
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(lerp(LogicColors.SurfaceVariantDark, accent, 0.30f), LogicColors.SurfaceDark),
            startY = y,
            endY = y + thickness,
        ),
        topLeft = start,
        size = Size(length, thickness),
        cornerRadius = CornerRadius(thickness / 2f),
    )
    // Filo de luz: halo ancho + trazo nítido (misma receta que el borde neón, §9.7).
    val lineY = y + 0.8.dp.toPx()
    val from = Offset(-over + 4.dp.toPx(), lineY)
    val to = Offset(size.width + over - 4.dp.toPx(), lineY)
    drawLine(accent.copy(alpha = 0.16f), from, to, strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
    drawLine(accent.copy(alpha = 0.80f), from, to, strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
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
 * **esquina del pico** ([lipPoint]) queda sobre la boca del tubo destino (un poco
 * antes del centro, para que el chorro caiga en arco hacia dentro), con el cuerpo
 * **al lado y por encima** (no pisándolo). Se interpola desde el hueco de origen
 * con [travel].
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
    val gap = h * 0.16f // el pico queda algo por encima del borde del destino

    // Queremos: pivote(base) + (lipDx,lipDy) == (dest.center.x - dir*w*0.14, dest.top - gap).
    val targetPivotX = dest.center.x - dir * w * 0.14f - lipDx
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
 * Un frasco de la estantería: envuelve el dibujo ([drawPotionTube]) con todas sus
 * animaciones dirigidas por estado (§9.4). Cada interacción tiene su respuesta:
 *  - **entrada** → cae a su sitio con rebote, escalonado por [entryDelayMs];
 *  - **[lifted]** (seleccionado) → se eleva con `spring`, flota y su líquido se agita;
 *  - **[highlightColor]** → borde de neón de ese color (selección u origen en vuelo);
 *  - **[tiltDegrees]/[travelX]/[travelY]** (sirviendo) → viaja e inclina sobre su base;
 *  - **[rejectId]** (vertido rechazado) → sacudida lateral con destello de error;
 *  - **[completed]** → corcho que cae y tapona, barrido de brillo, "squash" elástico
 *    y halo que late; avisa por [onCompleted] para que la pantalla suelte chispas;
 *  - **[hopAt]** (victoria) → salto en el instante indicado del reloj (ola).
 *
 * Todas las transformaciones van en UNA `graphicsLayer` cuyo bloque lee el reloj y
 * los `Animatable`: se resuelven en la fase de dibujo, sin recomponer el frasco.
 *
 * @param seed semilla estable (índice del frasco) para desfasar olas y burbujas.
 * @param clock reloj compartido de la pantalla (ver [rememberPotionClock]).
 * @param reflectionColor color del reflejo en la repisa (el del fondo), o null si vacío.
 * @param grounded el frasco está posado en la repisa (su reflejo se enciende).
 * @param agitated el líquido está recibiendo/soltando poción ahora mismo.
 * @param entryKey al cambiar (nivel nuevo) se repite la animación de entrada.
 */
@Composable
private fun TubeView(
    bands: List<LiquidBand>,
    capacity: Int,
    seed: Int,
    clock: State<Float>,
    highlightColor: Color?,
    lifted: Boolean,
    completed: Boolean,
    glowColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reflectionColor: Color? = null,
    grounded: Boolean = true,
    tiltDegrees: Float = 0f,
    travelX: Float = 0f,
    travelY: Float = 0f,
    agitated: Boolean = false,
    rejectId: Long? = null,
    entryKey: Any? = null,
    entryDelayMs: Int = 0,
    hopAt: Float? = null,
    onCompleted: () -> Unit = {},
) {
    // El tubo seleccionado se eleva con física de resorte (sensación táctil, §9.4).
    val lift by animateDpAsState(
        targetValue = if (lifted) -LiftHeight else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tubeLift",
    )
    // Encendido del borde neón al seleccionar (resorte corto: responde al dedo).
    val highlight by animateFloatAsState(
        targetValue = if (highlightColor != null) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tubeHighlight",
    )
    // Agitación del líquido: sube rápido al verter/levantar y se calma despacio,
    // como un líquido real que tarda en asentarse.
    val agitationTarget = when {
        agitated -> 1f
        lifted -> 0.55f
        else -> 0f
    }
    val agitation by animateFloatAsState(
        targetValue = agitationTarget,
        animationSpec = tween(if (agitationTarget > 0f) 220 else 1100),
        label = "tubeAgitation",
    )
    val groundedAmt by animateFloatAsState(if (grounded) 1f else 0f, tween(260), label = "tubeGrounded")

    // Entrada: cae a su sitio al montar el nivel (0 = arriba e invisible, 1 = posado).
    val entry = remember(entryKey) { Animatable(0f) }
    LaunchedEffect(entryKey) {
        delay(entryDelayMs.toLong())
        entry.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 210f))
    }

    // Sacudida de rechazo (1 → 0). No se dispara con el rechazo que ya estuviera en
    // el estado al componer (p. ej. al volver de pausa): solo con uno nuevo.
    val shake = remember { Animatable(0f) }
    val initialReject = remember { rejectId }
    LaunchedEffect(rejectId) {
        if (rejectId != null && rejectId != initialReject) {
            shake.snapTo(1f)
            shake.animateTo(0f, tween(440, easing = LinearEasing))
        }
    }

    // Celebración al completarse. Si el frasco YA estaba completo al componerse
    // (partida reanudada), nace con el corcho puesto y sin fanfarria.
    val cork = remember { Animatable(if (completed) 1f else 0f) }
    val shine = remember { Animatable(1f) }
    val squash = remember { Animatable(0f) }
    var wasCompleted by remember { mutableStateOf(completed) }
    LaunchedEffect(completed) {
        if (completed && !wasCompleted) {
            onCompleted()
            launch { cork.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 260f)) }
            launch {
                shine.snapTo(0f)
                shine.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            }
            launch {
                squash.snapTo(1f)
                squash.animateTo(0f, spring(dampingRatio = 0.30f, stiffness = 240f))
            }
        } else if (!completed) {
            cork.snapTo(0f) // deshacer: el corcho desaparece sin ceremonia
        }
        wasCompleted = completed
    }

    Box(
        modifier = modifier
            .aspectRatio(TubeAspect)
            // El reflejo va FUERA de la capa que se mueve: se queda en la repisa.
            .drawBehind {
                if (reflectionColor != null) {
                    drawShelfReflection(Rect(Offset.Zero, size), reflectionColor, groundedAmt * entry.value.coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val time = clock.value
                    val liftPx = lift.toPx()
                    val liftFrac = abs(liftPx) / LiftHeight.toPx()
                    val e = entry.value
                    var ty = travelY + liftPx - (1f - e) * 70.dp.toPx()
                    // Flota suavemente mientras está en la mano.
                    ty += sin(time * 2.6f + seed) * 2.2.dp.toPx() * liftFrac
                    if (hopAt != null) {
                        val hop = (time - hopAt) / VICTORY_HOP_S
                        if (hop > 0f && hop < 1f) ty -= sin(hop * PI.toFloat()) * 20.dp.toPx()
                    }
                    val s = shake.value
                    translationX = travelX + sin((1f - s) * PI.toFloat() * 5f) * s * 7.dp.toPx()
                    translationY = ty
                    rotationZ = tiltDegrees
                    scaleX = 1f - 0.05f * squash.value
                    scaleY = 1f + 0.07f * squash.value
                    alpha = (e * 1.6f).coerceIn(0f, 1f)
                    // Sin capa offscreen: con alfa < 1 se recortaría a los límites del
                    // frasco y se comería el labio, el corcho y el halo.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .bounceClick(enabled = enabled, onClick = onClick),
        ) {
            val time = clock.value
            val flash = shake.value
            // Prioridad del borde: rechazo (rojo) > selección/en vuelo > completado.
            val edgeColor: Color?
            val edgeAmt: Float
            var aura = 0f
            when {
                flash > 0f -> {
                    edgeColor = LogicColors.Error
                    edgeAmt = flash
                }
                highlightColor != null -> {
                    edgeColor = highlightColor
                    edgeAmt = highlight
                }
                completed -> {
                    // Latido lento y de baja amplitud (ambiente, §9.4), desfasado por frasco.
                    val pulse = 0.5f + 0.5f * sin(time * 2.2f + seed * 0.9f)
                    edgeColor = glowColor
                    edgeAmt = 0.45f + 0.40f * pulse
                    aura = (0.55f + 0.45f * pulse) * cork.value.coerceIn(0f, 1f)
                }
                else -> {
                    edgeColor = null
                    edgeAmt = 0f
                }
            }
            drawPotionTube(
                bands = bands,
                capacity = capacity,
                time = time,
                seed = seed,
                tiltDegrees = tiltDegrees,
                agitation = agitation,
                edgeColor = edgeColor,
                edgeAmt = edgeAmt,
                auraAmt = aura,
                cork = cork.value,
                shine = shine.value,
            )
        }
    }
}

/** Reloj fijo para las previews (el arte se ve en su instante 0, sin animar). */
@Composable
private fun previewClock(): State<Float> = remember { mutableFloatStateOf(0.6f) }

@Preview
@Composable
private fun PreviewTubeNormal() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(24.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(0, 1f), LiquidBand(2, 1f), LiquidBand(5, 1f)),
                capacity = 4,
                seed = 0,
                clock = previewClock(),
                highlightColor = null,
                lifted = false,
                completed = false,
                glowColor = LogicColors.NeonCyan,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(MaxTubeWidth),
                reflectionColor = PotionColors[0],
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
                .padding(24.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(1, 1f), LiquidBand(1, 1f), LiquidBand(3, 1f)),
                capacity = 4,
                seed = 1,
                clock = previewClock(),
                highlightColor = LogicColors.Magenta,
                lifted = true,
                completed = false,
                glowColor = LogicColors.NeonCyan,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(MaxTubeWidth),
                grounded = false,
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
                .padding(24.dp),
        ) {
            TubeView(
                bands = List(4) { LiquidBand(4, 1f) },
                capacity = 4,
                seed = 2,
                clock = previewClock(),
                highlightColor = null,
                lifted = false,
                completed = true,
                glowColor = LogicColors.Amber,
                enabled = true,
                onClick = {},
                modifier = Modifier.width(MaxTubeWidth),
                reflectionColor = LogicColors.Amber,
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
                .padding(24.dp),
        ) {
            TubeView(
                bands = listOf(LiquidBand(6, 1f), LiquidBand(6, 1f), LiquidBand(6, 0.45f)),
                capacity = 4,
                seed = 3,
                clock = previewClock(),
                highlightColor = LogicColors.Blue,
                lifted = false,
                completed = false,
                glowColor = LogicColors.Blue,
                enabled = false,
                onClick = {},
                modifier = Modifier.width(MaxTubeWidth),
                grounded = false,
                tiltDegrees = -38f,
                agitated = true,
            )
        }
    }
}

@Preview
@Composable
private fun PreviewPotionHud() {
    LogicGamesTheme {
        Box(
            modifier = Modifier
                .background(LogicColors.BackgroundDark)
                .padding(16.dp),
        ) {
            PotionHud(level = 7, moves = 12, colors = listOf(0, 1, 2, 3, 4), completed = setOf(1, 3))
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
            // Deshacer en sus dos estados: gratis (el primero) y de pago (con el
            // distintivo de anuncio), para poder comparar ambos de un vistazo.
            GameActionButton(
                icon = KortexIcons.Undo,
                label = "Deshacer",
                tint = LogicColors.NeonCyan,
                enabled = true,
                onClick = {},
            )
            GameActionButton(
                icon = KortexIcons.Undo,
                label = "Deshacer",
                tint = LogicColors.NeonCyan,
                enabled = true,
                onClick = {},
                costsAd = true,
            )
            GameActionButton(
                icon = KortexIcons.Refresh,
                label = "Reiniciar",
                tint = LogicColors.Amber,
                enabled = false,
                onClick = {},
            )
        }
    }
}
