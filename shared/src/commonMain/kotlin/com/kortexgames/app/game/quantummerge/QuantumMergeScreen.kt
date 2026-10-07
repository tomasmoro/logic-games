package com.kortexgames.app.game.quantummerge

import kortexgames.shared.generated.resources.quantum_merge_hud_score
import kortexgames.shared.generated.resources.quantum_merge_hud_next
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.DifficultyUnlocks
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.AdLoadingOverlay
import com.kortexgames.app.ui.components.DifficultyGateSelector
import com.kortexgames.app.ui.components.DifficultyOption
import com.kortexgames.app.ui.components.GameActionButton
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.quantum_merge_laser_label
import kortexgames.shared.generated.resources.quantum_merge_revive_body
import kortexgames.shared.generated.resources.quantum_merge_revive_reward
import kortexgames.shared.generated.resources.quantum_merge_revive_title
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource

/**
 * # QuantumMergeScreen — renderizado del reactor (Fase 3)
 *
 * Pantalla del minijuego **Quantum Merge**. Hace exactamente tres cosas: traducir gestos a intents,
 * pedir un frame de física por cada frame de render, y pintar el estado del motor en un `Canvas`.
 * Ninguna regla de juego vive aquí.
 *
 * ## La transformación mundo → píxel (única en toda la pantalla)
 * El motor simula en las unidades fijas de [QuantumWorld] (100 × 132) y no sabe qué es un píxel
 * (ver la Decisión 1 en `QuantumMergeModels.kt`). Aquí se calcula **una sola** magnitud, la escala:
 *
 * ```
 * scale = min(anchoDisponible / QuantumWorld.WIDTH, altoDisponible / QuantumWorld.HEIGHT)
 * ```
 *
 * Con ella, el contenedor se dimensiona a `WIDTH·scale × HEIGHT·scale` y se centra, de modo que
 * **el `Canvas` coincide exactamente con el mundo**: pintar es multiplicar por `scale`, sin
 * desplazamientos que cuadrar. Y el mismo número, invertido, convierte el dedo en coordenada de
 * mundo (`worldX = toque.x / scale`) antes de emitir `MoveDropper`. Al derivarse ambas direcciones
 * del mismo factor, lo que el jugador ve y lo que el motor simula no pueden desalinearse.
 *
 * ## Estética: esferas de luz, no bolas de color
 * Cada esfera se dibuja por capas de alfa decreciente (§9.2): dos halos radiales, un cuerpo con
 * degradado que se aclara hacia el centro, un **borde grueso** de color puro y un brillo especular
 * desplazado. Esa pila es lo que hace que se lean como plasma contenido y no como un círculo
 * plano. El contenedor, en cambio, es deliberadamente **sobrio** —tres trazos en
 * `SurfaceVariantDark`— siguiendo §9.7: un marco de neón intenso alrededor de un tablero lleno de
 * esferas brillantes competiría con ellas y saturaría la pantalla.
 *
 * ## Animación dirigida por estado (§9.4)
 * Los destellos de fusión no se animan desde la UI: son [MergeFlash] del estado, con su propio
 * `progress` que avanza en el tick de la física. La pantalla solo los pinta. Lo único que sí es
 * animación de UI es un **único** bucle ambiental (el latido del halo) y la sacudida corta con
 * `spring` al lograr una fusión grande, que es feedback del gesto, no adorno de fondo.
 */
@Composable
fun QuantumMergeScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: QuantumMergeViewModel = viewModel {
        QuantumMergeViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de física.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.quantumMerge,
            title = "Quantum Merge",
            // Sin `motif` todavía: el arte propio del juego (y su tarjeta de catálogo) llega con
            // el alta en el catálogo; hasta entonces el glifo de burbujas hace de identidad.
            icon = Icons.Rounded.BubbleChart,
            description = "Arrastra para apuntar y suelta la esfera. Dos esferas iguales que se " +
                "tocan se fusionan en la siguiente de la escala. Si alguna se queda quieta por " +
                "encima de la línea de peligro, el reactor desborda.",
            accent = CategoryPalette.SpatialVision,
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.QUANTUM_MERGE)
                vm.onIntent(QuantumMergeIntent.Start)
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
            // El selector va como `configContent` (dentro del bloque de acciones de la intro) y no
            // superpuesto por fuera: overlayarlo lo dejaría por encima de la hoja de ayuda, que es
            // la última capa de la propia intro, y taparía el diálogo de "¿Cómo se juega?".
            configContent = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DifficultyGateSelector(
                        title = "TAMAÑO",
                        options = DIFFICULTY_OPTIONS_UI,
                        selectedIndex = game.difficulty.ordinal,
                        unlockedTiers = state.unlockedTiers,
                        onSelect = { index ->
                            vm.onIntent(QuantumMergeIntent.SelectDifficulty(QuantumDifficulty.entries[index]))
                        },
                        accent = CategoryPalette.SpatialVision,
                        hint = DifficultyUnlocks.nextUnlockHint(GameIds.QUANTUM_MERGE, state.unlockedTiers),
                        equalWidth = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Comparativa mundial del escalón elegido, ANTES de jugar (mismo panel que el
                    // diálogo de fin de partida): pedido explícito para que la antesala también
                    // responda "¿cómo me va ahí?".
                    val preview = state.rankingPreview
                    when {
                        state.rankingPreviewLoading -> WorldRankingLoading()
                        preview != null -> WorldRankingPreviewPanel(ranking = preview)
                        else -> RankingPreviewUnavailable(difficultyLabel = game.difficulty.displayName)
                    }
                }
            },
        )
        return
    }

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    // De paso alimenta el reloj de animación de la pantalla (motas de la cámara, aparición de
    // esferas): se lee solo dentro del `Canvas`, así que avanzarlo redibuja sin recomponer.
    var clockOrigin by remember { mutableLongStateOf(0L) }
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                if (clockOrigin == 0L) clockOrigin = frameNanos
                // Se resta un origen propio: los nanos del frame, pasados a segundos en `Float`,
                // pierden precisión y las animaciones irían a saltos.
                timeSec = (frameNanos - clockOrigin) / 1_000_000_000f
                vm.onIntent(QuantumMergeIntent.Tick(frameNanos))
            }
        }
    }

    // Instante de nacimiento de cada esfera del tablero, para su "pop" de aparición. No es
    // estado observable (no dispara recomposición): lo consulta y lo rellena el propio dibujo.
    val sphereBirths = remember { mutableMapOf<Long, Float>() }
    // Id de la última esfera sostenida en el dispensador: al soltarla pasa al tablero y NO debe
    // hacer "pop" (ya estaba a la vista); solo aparecen así las nacidas de una fusión.
    var lastDropId by remember { mutableLongStateOf(-1L) }

    // Latido ambiental de baja amplitud: el ÚNICO bucle continuo de la pantalla (§9.4, regla 5).
    val glowPulse by rememberInfiniteTransition(label = "quantumGlow").animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "quantumGlowAlpha",
    )

    // Sacudida del reactor al conseguir una fusión grande. Se dispara desde el efecto one-shot
    // (no desde el estado) porque es un evento instantáneo: un `spring` poco amortiguado desde 1
    // hasta 0 oscila solo y se apaga, que es exactamente el gesto de "golpe" que se busca.
    val impactShake = remember { Animatable(0f) }

    // Anuncios: el botón "Láser" del HUD pide el rewarded real al AdManager (pulsar el botón YA es
    // la confirmación del jugador, mismo trato que "Tubo extra" en Ordena las Pociones) y, si
    // concede la recompensa, dispara el láser. `awaitingAd` solo alimenta el feedback visual
    // (AdLoadingOverlay más abajo) mientras se resuelve la carga real del anuncio.
    var awaitingAd by remember { mutableStateOf(false) }

    // Un único colector para TODOS los efectos: `vm.effect` es un `Channel` de un solo consumidor
    // (ver Mvi.kt), así que dos `LaunchedEffect` separados se repartirían los eventos en vez de
    // verlos ambos.
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when {
                effect is QuantumMergeEffect.Vibrate && effect.cue == QuantumMergeEffect.Vibrate.Cue.MERGE_BIG -> {
                    impactShake.snapTo(1f)
                    impactShake.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = SHAKE_DAMPING,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                }
                effect == QuantumMergeEffect.ShowRewardedAd -> {
                    awaitingAd = true
                    val result = graph.adManager.showRewardedAd()
                    awaitingAd = false
                    if (result == RewardResult.EARNED) vm.onIntent(QuantumMergeIntent.LaserRewarded)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            QuantumHud(
                score = game.score,
                difficulty = game.difficulty,
                nextTier = game.nextSphereTier,
                dangerProgress = game.dangerProgress,
                glowPulse = glowPulse,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                // Escala única mundo→píxel: el contenedor entra completo sin deformarse.
                val scale = with(density) {
                    min(
                        maxWidth.toPx() / QuantumWorld.WIDTH,
                        maxHeight.toPx() / QuantumWorld.HEIGHT,
                    )
                }
                val boardWidth = with(density) { (QuantumWorld.WIDTH * scale).toDp() }
                val boardHeight = with(density) { (QuantumWorld.HEIGHT * scale).toDp() }

                Box(
                    modifier = Modifier
                        .size(boardWidth, boardHeight)
                        .graphicsLayer {
                            // La sacudida oscila alrededor de 0, así que basta multiplicarla.
                            translationY = impactShake.value * SHAKE_TRAVEL.toPx()
                        }
                        // Arrastrar apunta y levantar el dedo suelta: el gesto natural del género.
                        .pointerInput(scale) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    vm.onIntent(QuantumMergeIntent.MoveDropper(offset.x / scale))
                                },
                                onDragEnd = { vm.onIntent(QuantumMergeIntent.DropSphere) },
                            ) { change, _ ->
                                vm.onIntent(QuantumMergeIntent.MoveDropper(change.position.x / scale))
                            }
                        }
                        // Un toque seco también vale: apunta y suelta en el mismo gesto.
                        .pointerInput(scale) {
                            detectTapGestures { offset ->
                                vm.onIntent(QuantumMergeIntent.MoveDropper(offset.x / scale))
                                vm.onIntent(QuantumMergeIntent.DropSphere)
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val time = timeSec
                        drawReactor(
                            accent = CategoryPalette.SpatialVision,
                            dangerProgress = game.dangerProgress,
                            glowPulse = glowPulse,
                            time = time,
                        )
                        drawDangerLine(
                            scale = scale,
                            dangerLineY = game.difficulty.dangerLineY,
                            dangerProgress = game.dangerProgress,
                            glowPulse = glowPulse,
                        )

                        game.currentDropSphere?.let { drop ->
                            drawAimGuide(sphere = drop, scale = scale, glowPulse = glowPulse)
                        }

                        // Tablero vacío = partida nueva: se olvidan los nacimientos anteriores.
                        if (game.activeSpheres.isEmpty()) sphereBirths.clear()
                        for (sphere in game.activeSpheres) {
                            // Primera vez que se ve esta esfera: si es la que se acaba de soltar,
                            // nace ya "aparecida"; si no, viene de una fusión y hace pop.
                            val born = sphereBirths.getOrPut(sphere.id) {
                                if (sphere.id == lastDropId) Float.NEGATIVE_INFINITY else time
                            }
                            val age = (time - born) / SPHERE_APPEAR_SEC
                            val appear = if (age >= 1f) 1f else 0.55f + 0.45f * EaseOutBack.transform(age.coerceAtLeast(0f))
                            drawEnergySphere(
                                center = Offset(sphere.x * scale, sphere.y * scale),
                                radius = sphere.radius * scale,
                                color = sphere.tier.accent.color(),
                                glowPulse = glowPulse,
                                marks = sphere.tier.ordinal + 1,
                                vx = sphere.vx,
                                vy = sphere.vy,
                                appear = appear,
                            )
                        }
                        // Poda: ids que ya no existen (fusionados o eliminados por el láser).
                        if (sphereBirths.size > game.activeSpheres.size + 32) {
                            val alive = game.activeSpheres.mapTo(HashSet()) { it.id }
                            sphereBirths.keys.retainAll(alive)
                        }

                        // El destello va ENCIMA de las esferas: es la explosión de luz del
                        // momento —fusión o "burbuja" eliminada por el láser— y debe leerse por
                        // delante de la esfera nacida. Misma rutina para ambos: ver KDoc de
                        // [MergeFlash].
                        for (flash in game.flashes) {
                            drawMergeFlash(
                                center = Offset(flash.x * scale, flash.y * scale),
                                radius = flash.radius * scale,
                                color = flash.accent.color(),
                                progress = flash.progress,
                                seed = flash.id.toInt(),
                            )
                        }

                        game.currentDropSphere?.let { drop ->
                            lastDropId = drop.id
                            drawEnergySphere(
                                center = Offset(drop.x * scale, drop.y * scale),
                                radius = drop.radius * scale,
                                color = drop.tier.accent.color(),
                                glowPulse = glowPulse,
                                marks = drop.tier.ordinal + 1,
                            )
                        }
                    }
                }
            }

            // Barra de acciones: el láser. Franja canónica bajo el tablero (mismo lugar que
            // "Tubo extra" en Ordena las Pociones), a propósito por debajo de "algo que ya está
            // pasando en el reactor" y no flotando encima del gesto de apuntar/soltar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                GameActionButton(
                    icon = KortexIcons.Laser,
                    label = stringResource(Res.string.quantum_merge_laser_label),
                    tint = LogicColors.NeonCyan,
                    // Disponible casi siempre: el dispensador reabastece
                    // [QuantumTier.LASER_TARGETS] todo el rato, así que solo se apaga si el
                    // tablero está realmente vacío de esos tiers (misma comprobación que
                    // revalida el ViewModel; evita gastar un anuncio en un disparo al vacío).
                    enabled = state.status == GameStatus.RUNNING &&
                        game.activeSpheres.any { it.tier in QuantumTier.LASER_TARGETS },
                    costsAd = true,
                    onClick = { vm.onIntent(QuantumMergeIntent.WatchAdForLaser) },
                )
            }
        }

        // Segunda oportunidad: al primer desbordamiento del reactor, ofrece disparar el láser
        // viendo un anuncio antes del game-over. El scrim del overlay bloquea el tablero (y la
        // física está congelada, ver KDoc de `QuantumMergeState.awaitingRevive`) mientras se
        // decide; al aceptar el láser limpia la zona de peligro y la partida sigue, al rechazar
        // cae al game-over normal.
        if (state.game.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(QuantumMergeIntent.Revive) },
                onDecline = { vm.onIntent(QuantumMergeIntent.DeclineRevive) },
                title = stringResource(Res.string.quantum_merge_revive_title),
                rewardLabel = stringResource(Res.string.quantum_merge_revive_reward),
                body = stringResource(Res.string.quantum_merge_revive_body),
                icon = KortexIcons.Laser,
                accent = CategoryPalette.SpatialVision,
                audio = graph.audio,
            )
        }

        // Aviso de "cargando anuncio" del botón del HUD (no hay oferta+cuenta atrás de por medio:
        // pulsar el botón ya es la confirmación, ver KDoc de AdLoadingOverlay).
        AdLoadingOverlay(visible = awaitingAd, accent = LogicColors.NeonCyan)

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(QuantumMergeIntent.RestartGame) },
                onExit = onExit,
                unlockedDifficultyLabel = state.justUnlockedDifficulty?.displayName,
                onPlayUnlockedDifficulty = state.justUnlockedDifficulty?.let { difficulty ->
                    { vm.onIntent(QuantumMergeIntent.PlayDifficulty(difficulty)) }
                },
                accent = CategoryPalette.SpatialVision,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(QuantumMergeIntent.Pause) },
            onResume = { vm.onIntent(QuantumMergeIntent.Resume) },
            onExit = onExit,
            gameTitle = "Quantum Merge",
            help = GameHelpContent.quantumMerge,
            accent = CategoryPalette.SpatialVision,
        )
    }
}

/**
 * Opciones del [DifficultyGateSelector] de la antesala: los tres [QuantumDifficulty], con **las
 * dos consecuencias reales** de elegir cada uno —cuánto crecen las esferas y cuánta altura de
 * apilado queda— en vez de fiarlo todo al nombre. En un juego donde la dificultad es geometría,
 * "Grande" ya dice bastante, pero "+20 % · 92 de alto" es lo que de verdad permite anticipar en
 * qué se está metiendo el jugador antes de gastar una partida.
 */
private val DIFFICULTY_OPTIONS_UI: List<DifficultyOption> = QuantumDifficulty.entries.map { difficulty ->
    DifficultyOption(
        label = difficulty.displayName,
        details = listOf(
            if (difficulty.radiusScale == 1f) "esferas base"
            else "+${((difficulty.radiusScale - 1f) * 100f).toInt()} % tamaño",
            "${difficulty.stackHeight.toInt()} de alto",
        ),
    )
}

/**
 * HUD superior: la **puntuación en grande** a la izquierda (con el nivel como etiqueta) y el
 * previsor de la siguiente esfera a su lado.
 *
 * Deja libre la esquina superior derecha (el botón de pausa vive ahí) mediante el padding final.
 * Solo lleva **dos** indicadores a propósito: son los únicos que cambian una decisión en marcha, y
 * en un móvil estrecho un tercero empujaría el previsor fuera de la pantalla. El resto de cifras de
 * la partida (fusiones, lanzamientos, precisión) ya salen en la tarjeta de resultados.
 */
@Composable
private fun QuantumHud(
    score: Int,
    difficulty: QuantumDifficulty,
    nextTier: QuantumTier,
    dangerProgress: Float,
    glowPulse: Float,
    modifier: Modifier = Modifier,
) {
    // "Pop" del marcador al puntuar: cada fusión se celebra también en el número.
    val scorePop = remember { Animatable(1f) }
    LaunchedEffect(score) {
        if (score > 0) {
            scorePop.snapTo(1.22f)
            scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Column(
        modifier = modifier.padding(start = 20.dp, end = 68.dp, top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // El nivel acompaña a la etiqueta en vez de ocupar una píldora propia: es un
                    // dato que no cambia en toda la partida.
                    text = "${stringResource(Res.string.quantum_merge_hud_score)} · ${difficulty.displayName}".uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                    color = LogicColors.OnDarkMuted,
                )
                Text(
                    text = score.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = LogicColors.OnDark,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scorePop.value
                        scaleY = scorePop.value
                        // Crece desde la izquierda: el número está alineado a ese lado.
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
            }
            NextSpherePreview(tier = nextTier, glowPulse = glowPulse)
        }
        // Aviso de desbordamiento: barra que se llena con el tiempo de gracia consumido. Es
        // información de estado puro (no un bucle), así que solo existe cuando hay peligro real.
        if (dangerProgress > 0f) {
            Canvas(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(5.dp),
            ) {
                val corner = CornerRadius(size.height * 0.5f)
                drawRoundRect(color = LogicColors.SurfaceVariantDark, cornerRadius = corner)
                val filled = Size(size.width * dangerProgress, size.height)
                drawRoundRect(
                    color = LogicColors.Error.copy(alpha = 0.30f * glowPulse),
                    topLeft = Offset(0f, -2.dp.toPx()),
                    size = Size(filled.width, size.height + 4.dp.toPx()),
                    cornerRadius = CornerRadius(size.height),
                )
                drawRoundRect(
                    color = LogicColors.Error.copy(alpha = 0.55f + 0.45f * glowPulse),
                    size = filled,
                    cornerRadius = corner,
                )
            }
        }
    }
}

/**
 * Previsor de la siguiente esfera. Se dibuja con la MISMA rutina que las del tablero (a escala
 * reducida) en lugar de con un icono: el jugador tiene que reconocerla de un vistazo, y para eso
 * debe ser literalmente la misma esfera que va a caer. La píldora lleva el borde del color de esa
 * esfera, así que el cambio de "siguiente" se nota también de reojo.
 */
@Composable
private fun NextSpherePreview(tier: QuantumTier, glowPulse: Float, modifier: Modifier = Modifier) {
    val color = tier.accent.color()
    Row(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.50f), CircleShape)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.quantum_merge_hud_next),
            style = MaterialTheme.typography.labelSmall,
            color = LogicColors.OnDarkMuted,
        )
        Canvas(modifier = Modifier.size(PREVIEW_SIZE)) {
            // El radio se normaliza contra el mayor tier lanzable para que el previsor comunique
            // el tamaño RELATIVO de lo que viene sin salirse nunca de su píldora.
            val maxRadius = QuantumTier.SPAWN_POOL.last().baseRadius
            val radius = size.minDimension * 0.44f * (tier.baseRadius / maxRadius)
            drawEnergySphere(
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = radius,
                color = color,
                glowPulse = glowPulse,
                marks = tier.ordinal + 1,
            )
        }
    }
}

/** Amortiguación de la sacudida por fusión grande: baja = rebota un par de veces y para. */
private const val SHAKE_DAMPING = 0.3f

/** Recorrido máximo de esa sacudida. Corto a propósito: se siente, no marea. */
private val SHAKE_TRAVEL = 5.dp

/** Lado de la miniatura del previsor de la siguiente esfera. */
private val PREVIEW_SIZE = 40.dp

/** Duración del "pop" con que aparece una esfera recién nacida de una fusión. */
private const val SPHERE_APPEAR_SEC = 0.24f
