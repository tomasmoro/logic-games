package com.kortexgames.app.game.quantummerge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import kotlin.math.min

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
                DifficultySelector(
                    selected = game.difficulty,
                    onSelect = { vm.onIntent(QuantumMergeIntent.SelectDifficulty(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        return
    }

    // Bucle de juego: la física se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos -> vm.onIntent(QuantumMergeIntent.Tick(frameNanos)) }
        }
    }

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
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            val isBigMerge = effect is QuantumMergeEffect.Vibrate &&
                effect.cue == QuantumMergeEffect.Vibrate.Cue.MERGE_BIG
            if (isBigMerge) {
                impactShake.snapTo(1f)
                impactShake.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = SHAKE_DAMPING,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
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
                        drawContainer(dangerProgress = game.dangerProgress, glowPulse = glowPulse)
                        drawDangerLine(
                            scale = scale,
                            dangerLineY = game.difficulty.dangerLineY,
                            dangerProgress = game.dangerProgress,
                            glowPulse = glowPulse,
                        )

                        game.currentDropSphere?.let { drop ->
                            drawAimGuide(sphere = drop, scale = scale, glowPulse = glowPulse)
                        }

                        for (sphere in game.activeSpheres) {
                            drawEnergySphere(
                                center = Offset(sphere.x * scale, sphere.y * scale),
                                radius = sphere.radius * scale,
                                color = sphere.tier.accent.color(),
                                glowPulse = glowPulse,
                            )
                        }

                        // El destello va ENCIMA de las esferas: es la explosión de luz del
                        // momento de la fusión y debe leerse por delante de la esfera nacida.
                        for (flash in game.flashes) {
                            drawMergeFlash(
                                center = Offset(flash.x * scale, flash.y * scale),
                                radius = flash.radius * scale,
                                color = flash.accent.color(),
                                progress = flash.progress,
                            )
                        }

                        game.currentDropSphere?.let { drop ->
                            drawEnergySphere(
                                center = Offset(drop.x * scale, drop.y * scale),
                                radius = drop.radius * scale,
                                color = drop.tier.accent.color(),
                                glowPulse = glowPulse,
                            )
                        }
                    }
                }
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(QuantumMergeIntent.RestartGame) },
                onExit = onExit,
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
 * Selector de nivel de la antesala: los tres [QuantumDifficulty] como fichas de igual ancho.
 *
 * Cada ficha enseña **las dos consecuencias reales** de elegirla —cuánto crecen las esferas y
 * cuánta altura de apilado queda— en vez de un adjetivo suelto. En un juego donde la dificultad es
 * geometría, "Difícil" no dice nada; "+20 % · 92 de alto" sí, y el jugador puede anticipar en qué
 * se está metiendo antes de gastar una partida.
 */
@Composable
private fun DifficultySelector(
    selected: QuantumDifficulty,
    onSelect: (QuantumDifficulty) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.dp, LogicColors.SurfaceVariantDark),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "DIFICULTAD",
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
        )
        // `weight(1f)` por ficha: todas ocupan el mismo ancho exacto en vez de ajustarse a su
        // contenido, así los rótulos más largos no descuadran la fila.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuantumDifficulty.entries.forEach { difficulty ->
                DifficultyChip(
                    difficulty = difficulty,
                    selected = difficulty == selected,
                    onClick = { onSelect(difficulty) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Una ficha del [DifficultySelector], resaltada en acento cuando está elegida.
 *
 * El `bounceClick` va **antes** de `clip`/`background`/`border` en la cadena de modificadores, como
 * en `AnimatedGameButton`: si el escalado quedara detrás de esos modificadores de dibujo, su capa
 * los dejaría fuera y el borde podría quedarse pintado con el valor viejo al cambiar la selección.
 */
@Composable
private fun DifficultyChip(
    difficulty: QuantumDifficulty,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = CategoryPalette.SpatialVision
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else LogicColors.SurfaceVariantDark)
            .border(
                BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) accent else LogicColors.OnDarkMuted.copy(alpha = 0.2f),
                ),
                shape,
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = difficulty.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else LogicColors.OnDarkMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        // Dos líneas cortas fijas en vez de una larga con separador: una línea larga se parte donde
        // el layout decida y desiguala la altura de las fichas.
        Text(
            text = if (difficulty.radiusScale == 1f) "esferas base"
            else "+${((difficulty.radiusScale - 1f) * 100f).toInt()} % tamaño",
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
            maxLines = 1,
        )
        Text(
            text = "${difficulty.stackHeight.toInt()} de alto",
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
            maxLines = 1,
        )
    }
}

/**
 * Traduce el acento semántico del tier al token de color del sistema de diseño.
 *
 * El mapa vive en la UI (y no en el `enum` de dominio) igual que en Tetris Neón: el motor de física
 * no conoce `Color`, y así el sistema de diseño mantiene UNA sola fuente de color (§9.2).
 */
private fun TierAccent.color(): Color = when (this) {
    TierAccent.CYAN -> LogicColors.NeonCyan
    TierAccent.GREEN -> LogicColors.NeonGreen
    TierAccent.LIME -> LogicColors.Lime
    TierAccent.AMBER -> LogicColors.Amber
    TierAccent.CORAL -> LogicColors.Coral
    TierAccent.MAGENTA -> LogicColors.Magenta
    TierAccent.VIOLET -> LogicColors.Violet
    TierAccent.BLUE -> LogicColors.Blue
    // El "blanco incandescente" del tier máximo es el blanco de la paleta, no un hex suelto.
    TierAccent.WHITE_HOT -> LogicColors.OnDark
}

/**
 * HUD superior: marcador y previsor de la siguiente esfera.
 *
 * Deja libre la esquina superior derecha (el botón de pausa vive ahí) mediante el padding final.
 * Solo lleva **dos** indicadores a propósito: son los únicos que cambian una decisión en marcha, y
 * en un móvil estrecho una tercera píldora empujaría el previsor fuera de la pantalla. El resto de
 * cifras de la partida (fusiones, lanzamientos, precisión) ya salen en la tarjeta de resultados.
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
    Column(
        modifier = modifier.padding(start = 20.dp, end = 68.dp, top = 16.dp),
    ) {
        Text(
            // El nivel viaja en el título en vez de en una píldora propia: es un dato que no
            // cambia en toda la partida, así que no merece ocupar sitio en la fila de marcadores.
            text = "Quantum Merge · ${difficulty.displayName}",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuantumHudPill(label = "Puntos", value = score.toString())
            NextSpherePreview(
                tier = nextTier,
                glowPulse = glowPulse,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Aviso de desbordamiento: barra que se llena con el tiempo de gracia consumido. Es
        // información de estado puro (no un bucle), así que solo existe cuando hay peligro real.
        if (dangerProgress > 0f) {
            Canvas(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(4.dp),
            ) {
                drawRoundRect(
                    color = LogicColors.SurfaceVariantDark,
                    cornerRadius = CornerRadius(size.height * 0.5f),
                )
                drawRoundRect(
                    color = LogicColors.Error.copy(alpha = 0.55f + 0.45f * glowPulse),
                    size = Size(size.width * dangerProgress, size.height),
                    cornerRadius = CornerRadius(size.height * 0.5f),
                )
            }
        }
    }
}

/** Píldora del HUD (etiqueta + valor), mismo lenguaje visual que el resto de juegos. */
@Composable
private fun QuantumHudPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                LogicColors.SurfaceDark.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LogicColors.OnDarkMuted)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDark)
    }
}

/**
 * Previsor de la siguiente esfera. Se dibuja con la MISMA rutina que las del tablero (a escala
 * reducida) en lugar de con un icono: el jugador tiene que reconocerla de un vistazo, y para eso
 * debe ser literalmente la misma esfera que va a caer.
 */
@Composable
private fun NextSpherePreview(tier: QuantumTier, glowPulse: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                LogicColors.SurfaceDark.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Siguiente",
            style = MaterialTheme.typography.labelSmall,
            color = LogicColors.OnDarkMuted,
        )
        Canvas(modifier = Modifier.size(PREVIEW_SIZE)) {
            // El radio se normaliza contra el mayor tier lanzable para que el previsor comunique
            // el tamaño RELATIVO de lo que viene sin salirse nunca de su píldora.
            val maxRadius = QuantumTier.SPAWN_POOL.last().baseRadius
            val radius = size.minDimension * 0.42f * (tier.baseRadius / maxRadius)
            drawEnergySphere(
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = radius,
                color = tier.accent.color(),
                glowPulse = glowPulse,
            )
        }
    }
}

/**
 * Contenedor: **tres** paredes (izquierda, derecha y suelo) en `SurfaceVariantDark` sobre un fondo
 * apenas más claro que el de la app.
 *
 * Que el borde superior no exista no es un olvido: la caja está abierta por arriba, exactamente
 * como el AABB de tres lados que resuelve el motor, y dejarlo abierto comunica al jugador por dónde
 * puede desbordar. Cuando hay peligro, la boca se tiñe con un degradado de [LogicColors.Error].
 */
private fun DrawScope.drawContainer(dangerProgress: Float, glowPulse: Float) {
    val wall = size.minDimension * WALL_WIDTH_FACTOR

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                LogicColors.BackgroundDark,
                LogicColors.SurfaceDark.copy(alpha = 0.55f),
            ),
        ),
        cornerRadius = CornerRadius(wall * 2f),
    )

    if (dangerProgress > 0f) {
        // Degradado de alarma que baja desde la boca: cuanto más cerca la derrota, más presente.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    LogicColors.Error.copy(alpha = 0.22f * dangerProgress * glowPulse),
                    Color.Transparent,
                ),
            ),
            size = Size(size.width, size.height * 0.35f),
        )
    }

    // Los muros se trazan SOBRE el borde exacto del canvas y con el doble de grosor: el `Canvas`
    // recorta la mitad exterior y la cara interior queda justo en el límite que usa el motor. Si se
    // dibujaran por dentro, una esfera apoyada aparecería incrustada en la pared —el motor la
    // detiene cuando su borde llega a la coordenada 0, no cuando llega al muro pintado—.
    listOf(
        Offset(0f, 0f) to Offset(0f, size.height),
        Offset(size.width, 0f) to Offset(size.width, size.height),
        Offset(0f, size.height) to Offset(size.width, size.height),
    ).forEach { (start, end) ->
        drawLine(
            color = LogicColors.SurfaceVariantDark,
            start = start,
            end = end,
            strokeWidth = wall * 2f,
            cap = StrokeCap.Square,
        )
    }
}

/**
 * Línea de peligro: trazo discontinuo a la altura de [QuantumWorld.DANGER_LINE_Y].
 *
 * En reposo es casi invisible (una guía, no una alarma) y va **tomando el rojo de error** conforme
 * [dangerProgress] avanza, de modo que el mismo elemento informa de la regla y de su inminencia sin
 * añadir un segundo indicador que compita por la atención.
 */
private fun DrawScope.drawDangerLine(
    scale: Float,
    dangerLineY: Float,
    dangerProgress: Float,
    glowPulse: Float,
) {
    val y = dangerLineY * scale
    val color = lerp(LogicColors.SurfaceVariantDark, LogicColors.Error, dangerProgress)
    val alpha = 0.45f + 0.55f * dangerProgress * glowPulse
    val dash = size.minDimension * 0.022f

    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = size.minDimension * 0.006f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.4f)),
    )
}

/**
 * Guía vertical de puntería: marca la columna por la que caerá la esfera sostenida.
 *
 * Sin ella el jugador tiene que estimar la vertical desde la boca del contenedor, que es justo la
 * fricción que arruina una mecánica de precisión. Se dibuja discontinua y a baja opacidad para
 * ayudar sin robar protagonismo a las esferas.
 */
private fun DrawScope.drawAimGuide(sphere: Sphere, scale: Float, glowPulse: Float) {
    val x = sphere.x * scale
    val top = (sphere.y + sphere.radius) * scale
    val dash = size.minDimension * 0.03f

    drawLine(
        color = sphere.tier.accent.color().copy(alpha = 0.16f + 0.12f * glowPulse),
        start = Offset(x, top),
        end = Offset(x, size.height),
        strokeWidth = size.minDimension * 0.008f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.8f)),
    )
}

/**
 * Dibuja una **esfera de energía**: capas de alfa decreciente que simulan luz contenida.
 *
 * De fuera hacia dentro:
 *  1. **Halo exterior** (radial, muy tenue): el resplandor que la esfera derrama sobre el fondo.
 *  2. **Halo interior** (radial, más concentrado): la transición entre el resplandor y el cuerpo.
 *  3. **Cuerpo**: degradado radial que se aclara hacia el centro (mezcla con blanco) y se apaga en
 *     el borde. Es lo que le da volumen sin necesidad de una textura.
 *  4. **Borde grueso** de color puro: el "tubo de neón" que la define contra el fondo oscuro.
 *  5. **Brillo especular** desplazado arriba-izquierda: el detalle que la convierte en una esfera
 *     y no en un disco.
 *
 * Todas las medidas son fracciones del radio, así que la misma función sirve para una esfera del
 * tablero y para la miniatura del previsor.
 *
 * @param glowPulse factor del latido ambiental (0.78..1); modula solo los halos, nunca el cuerpo,
 *   para que el tablero respire sin que parpadeen los objetos.
 */
private fun DrawScope.drawEnergySphere(
    center: Offset,
    radius: Float,
    color: Color,
    glowPulse: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.26f * glowPulse), Color.Transparent),
            center = center,
            radius = radius * 2.1f,
        ),
        radius = radius * 2.1f,
        center = center,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.38f * glowPulse), Color.Transparent),
            center = center,
            radius = radius * 1.4f,
        ),
        radius = radius * 1.4f,
        center = center,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(color, Color.White, 0.55f).copy(alpha = 0.95f),
                color.copy(alpha = 0.72f),
                color.copy(alpha = 0.28f),
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = color.copy(alpha = 0.92f),
        radius = radius * 0.93f,
        center = center,
        style = Stroke(width = radius * 0.15f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.4f),
        radius = radius * 0.17f,
        center = Offset(center.x - radius * 0.32f, center.y - radius * 0.34f),
    )
}

/**
 * Destello de fusión: un anillo que se expande y se apaga, con un núcleo de luz blanca.
 *
 * El [progress] llega del estado (lo avanza el tick de la física), no de un `animate*AsState`: así
 * el destello sigue el mismo reloj que el resto de la simulación y no se descuelga si el motor va
 * en cámara lenta tras un frame largo. El anillo crece con la raíz del progreso —rápido al
 * principio y frenando— porque es como se percibe una onda expansiva real.
 */
private fun DrawScope.drawMergeFlash(
    center: Offset,
    radius: Float,
    color: Color,
    progress: Float,
) {
    val fade = 1f - progress
    val ringRadius = radius * (0.65f + 1.5f * progress)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f * fade),
                color.copy(alpha = 0.35f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = ringRadius * 1.2f,
        ),
        radius = ringRadius * 1.2f,
        center = center,
    )
    drawCircle(
        color = lerp(color, Color.White, 0.45f).copy(alpha = 0.8f * fade),
        radius = ringRadius,
        center = center,
        style = Stroke(width = radius * 0.2f * fade),
    )
}

/** Grosor de las paredes del contenedor como fracción del lado menor del tablero. */
private const val WALL_WIDTH_FACTOR = 0.014f

/** Amortiguación de la sacudida por fusión grande: baja = rebota un par de veces y para. */
private const val SHAKE_DAMPING = 0.3f

/** Recorrido máximo de esa sacudida. Corto a propósito: se siente, no marea. */
private val SHAKE_TRAVEL = 5.dp

/** Lado de la miniatura del previsor de la siguiente esfera. */
private val PREVIEW_SIZE = 34.dp
