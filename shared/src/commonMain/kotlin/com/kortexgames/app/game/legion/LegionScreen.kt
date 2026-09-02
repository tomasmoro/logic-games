package com.kortexgames.app.game.legion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameExitProgress
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.legion_gameover_headline
import kortexgames.shared.generated.resources.legion_hud_boss
import kortexgames.shared.generated.resources.legion_hud_enemy
import kortexgames.shared.generated.resources.legion_hud_gates
import kortexgames.shared.generated.resources.legion_hud_round
import kortexgames.shared.generated.resources.legion_intro_description
import kortexgames.shared.generated.resources.legion_mathtutorial_cta
import kortexgames.shared.generated.resources.legion_mathtutorial_tip_1
import kortexgames.shared.generated.resources.legion_mathtutorial_tip_2
import kortexgames.shared.generated.resources.legion_mathtutorial_tip_3
import kortexgames.shared.generated.resources.legion_mathtutorial_title
import kortexgames.shared.generated.resources.legion_quiz_subtitle
import kortexgames.shared.generated.resources.legion_quiz_title
import kortexgames.shared.generated.resources.legion_revive_body
import kortexgames.shared.generated.resources.legion_revive_reward
import kortexgames.shared.generated.resources.legion_revive_title
import kortexgames.shared.generated.resources.starport_vip_ship
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # LegionScreen — pantalla Compose de Neon Legion (Fase 5)
 *
 * Antesala ([GameIntroScreen]) + tablero en `Canvas` + HUD + overlays (examen, revive, fin de
 * partida, pausa). La pantalla es deliberadamente "tonta": convierte gestos a intents y estado a
 * dibujo; todas las reglas viven en [LegionEngine].
 *
 * ## Mundo → píxel
 * El motor trabaja en unidades normalizadas (y ∈ 0..1, carriles por índice; ver Decisión 1 de
 * `LegionModels.kt`). La conversión es local al `Canvas`: `yPx = y * altura` y
 * `xPx = (carril + 0.5) * anchoCarril`. Nadie más conoce píxeles.
 *
 * ## El enjambre (una nave por unidad… hasta un techo)
 * El ejército se dibuja como un enjambre de naves —un triángulo con la proa hacia el enemigo por
 * tropa— PERO con techo de [MAX_SWARM_DOTS] y radio que crece con `ln(tropas)`: el ejército llega
 * a cientos, y dibujar una nave por unidad hundiría el frame rate sin aportar percepción (a esa
 * densidad se funden en una mancha). El techo + escala logarítmica mantienen la LECTURA de "masa
 * que crece" a coste constante; la cifra exacta la da el rótulo sobre el enjambre. Ver
 * [drawSwarm] para la distribución (girasol/filotaxis) y su vaivén.
 *
 * ## El choque
 * El enemigo frena en [LegionBalance.ENEMY_CLASH_Y] (tercer quinto) y el jugador está en
 * [LegionBalance.PLAYER_Y] (cuarto): quedan **enfrentados** con un quinto de pantalla de por
 * medio, que es el pasillo por el que cruzan los disparos. Los dos enjambres menguan a la vez
 * —una pareja aniquilada por cada nave que cae de cada bando— y cada nave alcanzada recibe su
 * disparo y revienta en su propia posición ([drawArmyClashPops]). Todo derivado de
 * [CombatStats.progress], sin listas de partículas.
 */
@Composable
fun LegionScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: LegionViewModel = viewModel {
        LegionViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Único punto de salida "en juego" (atrás del sistema y "SALIR" del menú de pausa): cierra
    // la corrida guardando su resultado real antes de navegar (Legion es ENDLESS, no reanuda
    // una carrera a medias — ver KDoc de `LegionViewModel.requestExit`).
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // Antesala: mientras no arranca (IDLE) se muestra la intro y NO corre el bucle de juego.
    if (state.status == GameStatus.IDLE) {
        // Tutorial de las cuentas matemáticas: se interpone ANTES de arrancar la ronda 1 la
        // primera vez que se juega (ver KDoc de LegionUiState.isFirstEverPlay), nunca más. Vive
        // en estado LOCAL de la pantalla, no en el ViewModel: es puramente presentacional (no
        // cambia nada del dominio), solo demora el intent Start un toque.
        var showMathTutorial by remember { mutableStateOf(false) }

        val startPlaying: () -> Unit = {
            // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
            // la partida (ver DailyGoalManager.markPlayed).
            graph.dailyGoalManager.markPlayed(GameIds.NEON_LEGION)
            vm.onIntent(LegionIntent.Start)
        }

        GameIntroScreen(
            help = GameHelpContent.legion,
            title = "Neon Legion",
            icon = Icons.Rounded.Groups,
            description = stringResource(Res.string.legion_intro_description),
            accent = CategoryPalette.MentalSpeed,
            onStart = {
                if (state.isFirstEverPlay) showMathTutorial = true else startPlaying()
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
            // Comparativa mundial en la antesala (tabla única: sin selector de dificultad).
            configContent = {
                val preview = state.rankingPreview
                when {
                    state.rankingPreviewLoading -> WorldRankingLoading()
                    preview != null -> WorldRankingPreviewPanel(ranking = preview)
                    else -> RankingPreviewUnavailable(difficultyLabel = null)
                }
            },
        )

        if (showMathTutorial) {
            LegionMathTutorialDialog(
                onDismiss = {
                    showMathTutorial = false
                    startPlaying()
                },
            )
        }
        return
    }

    // Bucle de juego: la simulación se sincroniza al reloj de render (withFrameNanos → Tick).
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { frameNanos ->
                vm.onIntent(LegionIntent.Tick(frameNanos))
            }
        }
    }

    // Latido ambiental de baja amplitud: el ÚNICO bucle continuo de la pantalla (§9.4, regla 5).
    val glowPulse by rememberInfiniteTransition(label = "legionGlow").animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "legionGlowAlpha",
    )

    // Sacudida del tablero en los golpes duros (impacto de láser, combate perdido). Se dispara
    // desde el efecto one-shot, no desde el estado: es un evento instantáneo, y un `spring` poco
    // amortiguado desde 1 hasta 0 oscila solo y se apaga (mismo patrón que Quantum Merge).
    val impactShake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        vm.effect.collect { effect ->
            val isHardHit = effect is LegionEffect.PlaySound &&
                (
                    effect.cue == LegionEffect.PlaySound.Cue.LASER_HIT ||
                        effect.cue == LegionEffect.PlaySound.Cue.COMBAT_LOSS
                    )
            if (isHardHit) {
                impactShake.snapTo(1f)
                impactShake.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.18f, stiffness = Spring.StiffnessLow),
                )
            }
        }
    }

    // Vaivén del enjambre. Es el ÚNICO bucle continuo que no es ambiental, y no contradice la
    // regla 5 del §9.4 ("bucles solo en el CTA"): esa regla protege la atención de adornos que
    // compiten entre sí, y aquí el movimiento del enjambre no es un adorno — es el sujeto del
    // juego (el ejército "vivo"), lo mismo que la caída de las puertas.
    val swarmPhase by rememberInfiniteTransition(label = "legionSwarm").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWARM_CYCLE_MS, easing = LinearEasing),
        ),
        label = "legionSwarmPhase",
    )

    // OJO: aquí NO hay animación de posición. El retardo de seguimiento vive en el motor
    // (`LegionState.playerX`), que es lo que garantiza que la legión colisione exactamente donde
    // se la ve. Un `animateFloatAsState` encima sumaría un segundo retardo y devolvería el
    // desfase entre vista y lógica que precisamente se quería evitar.

    val measurer = rememberTextMeasurer()
    val gateTextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
    val countTextStyle = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.Bold,
        color = LogicColors.OnDark,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark),
    ) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            LegionHud(
                round = game.round,
                isBoss = game.isBossRound,
                rowsCleared = game.rowsCleared,
                rowsTotal = game.rowsTotal,
                enemyTroops = game.enemyTroops,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        // El spring poco amortiguado oscila alrededor de 0 él solo: basta
                        // multiplicar (mismo patrón que la sacudida de Quantum Merge).
                        translationX = impactShake.value * 10.dp.toPx()
                    }
                    // La legión SIGUE al dedo: se apunta al posarlo y en cada movimiento, y el
                    // motor hace el resto con su retardo de seguimiento.
                    //
                    // Se usa el gestor de bajo nivel (`awaitEachGesture`) y no `detectDrag…`
                    // porque este solo empieza a reportar tras superar el umbral de arrastre:
                    // un toque seco no movería nada, y arrastres cortos responderían tarde. Aquí
                    // el primer `down` ya apunta, así que tocar y arrastrar son el mismo gesto.
                    .pointerInput(game.lanes) {
                        val laneWidthPx = size.width / game.lanes
                        // Píxel → unidades de carril: el centro del carril `n` está en
                        // `(n + 0.5) · ancho`, así que se invierte esa misma expresión. El
                        // recorte a la pista lo hace el motor (contrato de AimAt).
                        fun aim(x: Float) =
                            vm.onIntent(LegionIntent.AimAt(x / laneWidthPx - 0.5f))

                        awaitEachGesture {
                            val down = awaitFirstDown()
                            aim(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                aim(change.position.x)
                                change.consume()
                            }
                            // Al levantar el dedo no se reposiciona nada: la mira se queda donde
                            // quedó y la legión termina de alcanzarla por inercia.
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLaneDividers(lanes = game.lanes, glowPulse = glowPulse)

                    // Las puertas "desaparecen" durante el examen: el barrido las oculta en el
                    // RENDER; el dominio las conserva congeladas (ver cabecera de LegionEngine).
                    if (game.phase != LegionPhase.QUIZ) {
                        for (row in game.gateRows) {
                            drawGateRow(row, game.lanes, glowPulse, measurer, gateTextStyle)
                        }
                    }

                    // Durante el choque los dos ejércitos MENGUAN a la vez: cada pareja
                    // aniquilada desaparece de ambos enjambres, así que el bando mayor se queda
                    // solo con su excedente — el mismo número con el que entrará a la ronda
                    // siguiente. Fuera del combate se pintan enteros.
                    val combat = game.combat?.takeIf { game.phase == LegionPhase.COMBAT }
                    val fallen = combat?.destroyedNow ?: 0
                    val playerAlive = (game.troops - fallen).coerceAtLeast(0)
                    val enemyAlive = (game.enemyTroops - fallen).coerceAtLeast(0)

                    // Duelo contra el Jefe: su casco flota en la línea de combate con vaivén
                    // lateral. La X la comparten el rayo del Canvas y el sprite del overlay.
                    val boss = game.boss?.takeIf { game.phase == LegionPhase.BOSS }
                    val bossX = 0.5f + (boss?.drift ?: 0f) * BOSS_DRIFT_AMPLITUDE

                    // En las rondas de Jefe la amenaza es una nave única (sprite en la capa de
                    // overlays), no una masa: no hay enjambre enemigo que pintar.
                    if (!game.isBossRound) {
                        drawEnemyArmy(
                            enemyY = game.enemyY,
                            troops = enemyAlive,
                            glowPulse = glowPulse,
                            phase = swarmPhase,
                            measurer = measurer,
                            countStyle = countTextStyle,
                            // Layout congelado en el tamaño de entrada al choque: así las naves
                            // supervivientes no se recolocan cada vez que muere una compañera.
                            layoutTroops = combat?.enemyTroops ?: enemyAlive,
                        )
                    }

                    for (laser in game.verticalLasers) {
                        drawVerticalLaser(laser, game.lanes, glowPulse)
                    }
                    // La nave del barrido BAJA durante los segundos de gracia y frena encima de
                    // la legión para dispararle; su rayo la acompaña en la bajada.
                    val sweep = game.sweep?.takeIf { game.phase == LegionPhase.QUIZ }
                    val sweepY = sweep?.let {
                        SWEEP_START_Y + (SWEEP_ATTACK_Y - SWEEP_START_Y) * it.descentProgress
                    }
                    if (sweepY != null) drawSweepBeam(glowPulse, sweepY)

                    // Ejército del jugador, anclado al 80 % de la altura (LegionBalance.PLAYER_Y).
                    val laneWidth = size.width / game.lanes
                    val playerCenter = Offset(
                        x = (game.playerX + 0.5f) * laneWidth,
                        y = LegionBalance.PLAYER_Y * size.height,
                    )
                    val playerMaxRadius = laneWidth * 0.42f
                    drawSwarm(
                        center = playerCenter,
                        troops = playerAlive,
                        maxRadius = playerMaxRadius,
                        color = LogicColors.NeonGreen,
                        glowPulse = glowPulse,
                        phase = swarmPhase,
                        // El jugador avanza hacia el enemigo: proas hacia ARRIBA (−1).
                        facing = -1f,
                        measurer = measurer,
                        countStyle = countTextStyle,
                        // Layout congelado también durante el examen y el duelo: en ambos van
                        // muriendo naves, y sin esto el enjambre se recolocaría a cada baja.
                        layoutTroops = combat?.playerTroops
                            ?: sweep?.troopsAtStart
                            ?: boss?.troopsAtStart
                            ?: playerAlive,
                    )

                    // Duelo: fuego sostenido de la legión hacia el Jefe, su rayo y las
                    // explosiones de la última andanada.
                    if (boss != null) {
                        val bossCenter = Offset(
                            x = bossX * size.width,
                            y = LegionBalance.ENEMY_CLASH_Y * size.height,
                        )
                        drawLegionFire(
                            from = playerCenter,
                            to = bossCenter,
                            spread = playerMaxRadius * 0.7f,
                            phase = swarmPhase,
                        )
                        drawBossVolley(
                            boss = boss,
                            bossCenter = bossCenter,
                            playerCenter = playerCenter,
                            playerMaxRadius = playerMaxRadius,
                            troopsAlive = game.troops,
                            phase = swarmPhase,
                        )
                        drawBossHealthBar(boss, bossCenter, glowPulse)
                    }

                    // Refriega: disparos cruzados y explosiones sobre cada nave alcanzada, por
                    // ENCIMA de los dos enjambres (los impactos deben leerse por delante de las
                    // naves que los causan). Cada bando recibe el fuego del contrario.
                    if (combat != null) {
                        val enemyCenter = Offset(
                            x = size.width / 2f,
                            y = (game.enemyY ?: LegionBalance.ENEMY_CLASH_Y) * size.height,
                        )
                        val enemyMaxRadius = size.width * 0.26f
                        drawArmyClashPops(
                            center = enemyCenter,
                            shooterCenter = playerCenter,
                            maxRadius = enemyMaxRadius,
                            troops = combat.enemyTroops,
                            destroyedPairs = combat.destroyedPairs,
                            progress = combat.progress,
                            phase = swarmPhase,
                            shotColor = LogicColors.NeonGreen,
                        )
                        drawArmyClashPops(
                            center = playerCenter,
                            shooterCenter = enemyCenter,
                            maxRadius = playerMaxRadius,
                            troops = combat.playerTroops,
                            destroyedPairs = combat.destroyedPairs,
                            progress = combat.progress,
                            phase = swarmPhase,
                            shotColor = LogicColors.Magenta,
                        )
                    }

                    // Fuego de la nave del barrido: pasada la gracia, dispara desde donde frenó
                    // y revienta naves de la legión. Es EL MISMO efecto que el choque final —el
                    // castigo por tardar se ve, no solo se lee en el contador— y sale gratis
                    // porque el drenaje del motor y estas explosiones leen el mismo
                    // `attackProgress`, así que nunca se desincronizan.
                    if (sweep != null && sweepY != null && !sweep.inGrace) {
                        drawArmyClashPops(
                            center = playerCenter,
                            shooterCenter = Offset(size.width / 2f, sweepY * size.height),
                            maxRadius = playerMaxRadius,
                            troops = sweep.troopsAtStart,
                            destroyedPairs = sweep.doomedTroops,
                            progress = sweep.attackProgress,
                            phase = swarmPhase,
                            shotColor = LogicColors.Error,
                        )
                    }

                    for (flash in game.flashes) {
                        drawGateFlash(flash, game.lanes)
                    }
                }

                // Naves: el mismo sprite de Starport (`starport_vip_ship`), reutilizado en vez
                // de un casco procedural nuevo — un solo diseño de "nave neón" para toda la app
                // en vez de uno distinto por juego (ver KDoc de [LegionShip]). Son `Image`
                // superpuestas al `Canvas` (no se puede dibujar un recurso de imagen dentro de
                // un `DrawScope`) y se posicionan en Dp con las medidas de [BoxWithConstraints],
                // que es lo único que da el tablero en unidades reales sin pasar por píxeles.
                val laneWidthDp = maxWidth / game.lanes
                for (laser in game.verticalLasers) {
                    LegionShip(
                        // Proa hacia el jugador (180°): la nave amenaza HACIA ABAJO.
                        rotation = 180f,
                        scale = VERTICAL_SHIP_SCALE,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = laneWidthDp * (laser.lane.index + 0.5f) -
                                    SHIP_WIDTH_DP.dp * VERTICAL_SHIP_SCALE / 2f,
                                y = maxHeight * VERTICAL_SHIP_Y -
                                    SHIP_HEIGHT_DP.dp * VERTICAL_SHIP_SCALE / 2f,
                            ),
                    )
                }
                // El Jefe: la nave más grande del juego. Durante la aproximación cae por la
                // pista como el ejército al que sustituye; en el duelo se queda en la línea de
                // combate meciéndose de lado a lado.
                if (game.isBossRound) {
                    val bossFight = game.boss?.takeIf { game.phase == LegionPhase.BOSS }
                    val bossYNorm = if (bossFight != null) {
                        LegionBalance.ENEMY_CLASH_Y
                    } else {
                        game.enemyY ?: LegionBalance.ENEMY_VISIBLE_Y
                    }
                    if (bossYNorm >= LegionBalance.ENEMY_VISIBLE_Y) {
                        val driftX = 0.5f + (bossFight?.drift ?: 0f) * BOSS_DRIFT_AMPLITUDE
                        LegionShip(
                            // Proa hacia la legión, como toda amenaza que baja.
                            rotation = 180f,
                            scale = BOSS_SHIP_SCALE,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = maxWidth * driftX - SHIP_WIDTH_DP.dp * BOSS_SHIP_SCALE / 2f,
                                    y = maxHeight * bossYNorm - SHIP_HEIGHT_DP.dp * BOSS_SHIP_SCALE / 2f,
                                ),
                        )
                    }
                }

                // Nave del barrido: baja durante la gracia y frena encima de la legión para
                // dispararle. La Y sale del propio evento, así que su descenso ES el reloj del
                // examen — cuanto más cerca la tiene el jugador, menos tiempo le queda.
                game.sweep?.takeIf { game.phase == LegionPhase.QUIZ }?.let { sweep ->
                    val shipY = SWEEP_START_Y + (SWEEP_ATTACK_Y - SWEEP_START_Y) * sweep.descentProgress
                    LegionShip(
                        // Proa hacia la legión (180°), como la escolta: viene a por ella.
                        rotation = 180f,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = maxWidth / 2f - SHIP_WIDTH_DP.dp / 2f,
                                y = maxHeight * shipY - SHIP_HEIGHT_DP.dp / 2f,
                            ),
                    )
                }

                // Examen del barrido: se ancla ARRIBA y no centrado a propósito. La mitad
                // inferior es el escenario de la amenaza (la nave bajando hacia la legión) y un
                // panel centrado la taparía justo cuando empieza a disparar — que es el momento
                // que el jugador necesita ver para entender que se le acaba el tiempo.
                game.sweep?.takeIf { game.phase == LegionPhase.QUIZ }?.let { sweep ->
                    LegionQuizPanel(
                        sweep = sweep,
                        onAnswer = { index -> vm.onIntent(LegionIntent.AnswerQuiz(index)) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Oferta de revivir viendo un anuncio: el motor congeló la partida; aquí solo se decide.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(LegionIntent.Revive) },
                onDecline = { vm.onIntent(LegionIntent.DeclineRevive) },
                title = stringResource(Res.string.legion_revive_title),
                rewardLabel = stringResource(Res.string.legion_revive_reward),
                body = stringResource(Res.string.legion_revive_body, game.round.toString()),
                accent = CategoryPalette.MentalSpeed,
                audio = graph.audio,
            )
        }

        state.gameOver?.let { info ->
            GameOverOverlay(
                info = info,
                audio = graph.audio,
                headline = stringResource(Res.string.legion_gameover_headline, game.round.toString()),
                onPlayAgain = { vm.onIntent(LegionIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // El cartel "¿Salir del juego?" del atrás pausa la partida (Neon Legion es de acción:
        // la amenaza no puede seguir avanzando mientras el jugador decide). Mientras ese cartel
        // está en pantalla el motor queda en PAUSED, así que hay que silenciar el menú de pausa
        // para que no aparezca por detrás (ver GameExitGuard.onPause / suppressMenu).
        var exitPromptVisible by remember { mutableStateOf(false) }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(LegionIntent.Pause) },
            onResume = { vm.onIntent(LegionIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Neon Legion",
            help = GameHelpContent.legion,
            accent = CategoryPalette.MentalSpeed,
            suppressMenu = exitPromptVisible,
        )

        // Atrás del sistema: reanuda si estaba en pausa, o pausa y pregunta antes de salir
        // mientras se corre (la ronda alcanzada y el puntaje se guardan al confirmar, ver
        // exitWithSave). Si elige "SEGUIR JUGANDO", el propio guard reanuda la partida.
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(LegionIntent.Resume) },
            onConfirmExit = exitWithSave,
            progress = GameExitProgress.ENDS_RUN,
            accent = CategoryPalette.MentalSpeed,
            onPause = { vm.onIntent(LegionIntent.Pause) },
            onExitPromptVisibilityChange = { exitPromptVisible = it },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Tutorial de las cuentas matemáticas (primera partida)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Cartel que explica el mecanismo de las puertas ANTES de la ronda 1 — solo la primera vez que
 * se juega Neon Legion (ver KDoc de [LegionUiState.isFirstEverPlay]).
 *
 * Existe aparte de [GameHelpContent.legion] (el "¿Cómo se juega?" genérico del menú de pausa,
 * bajo demanda) porque enseña algo más puntual y fácil de pasar por alto jugando a ojo: que un
 * `×2` no es automáticamente la mejor puerta — el generador a veces empareja un `×2` con una
 * suma que da MÁS tropas (ver KDoc de `LegionEngine.positiveRow`), así que hay que comparar los
 * números, no reconocer el símbolo. Ese aviso puntual se pierde en la ayuda general, pensada
 * para el flujo completo del juego, no para esta trampa concreta.
 *
 * Mismo lenguaje visual que el resto de diálogos modales de la app (p. ej.
 * [ReviveAdOverlay] o `ConfirmExitDialog` de [GameExitGuard]): tarjeta redondeada
 * [LogicColors.SurfaceDark], icono neón, CTA con degradado. Sin botón de descarte aparte: la
 * única salida es "Entendido", porque no hay nada que perder por leerlo — todavía no arrancó
 * ninguna ronda.
 */
@Composable
private fun LegionMathTutorialDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(icon = KortexIcons.Hint, tint = CategoryPalette.MentalSpeed, size = 40.dp)
            Text(
                stringResource(Res.string.legion_mathtutorial_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            MathTutorialTip(stringResource(Res.string.legion_mathtutorial_tip_1))
            MathTutorialTip(stringResource(Res.string.legion_mathtutorial_tip_2))
            MathTutorialTip(stringResource(Res.string.legion_mathtutorial_tip_3))

            AnimatedGameButton(
                onClick = onDismiss,
                gradient = LogicGradients.play,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(Res.string.legion_mathtutorial_cta),
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.BackgroundDark,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/** Una fila del tutorial: viñeta + texto, sin icono propio (el del cartel ya alcanza). */
@Composable
private fun MathTutorialTip(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyLarge,
            color = CategoryPalette.MentalSpeed,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// HUD
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * HUD superior: ronda (con distintivo de Jefe), progreso de puertas y tamaño del enemigo.
 *
 * El enemigo se muestra DURANTE la carrera a propósito: saber cuánto hay que superar es lo que
 * convierte "cruza puertas" en "elige bien las puertas". Deja libre la esquina superior derecha
 * (el botón de pausa vive ahí) mediante el padding final.
 */
@Composable
private fun LegionHud(
    round: Int,
    isBoss: Boolean,
    rowsCleared: Int,
    rowsTotal: Int,
    enemyTroops: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 72.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudPill(
            text = stringResource(Res.string.legion_hud_round, round.toString()),
            accent = CategoryPalette.MentalSpeed,
        )
        if (isBoss) {
            HudPill(
                text = stringResource(Res.string.legion_hud_boss),
                accent = LogicColors.Error,
            )
        }
        HudPill(
            text = stringResource(
                Res.string.legion_hud_gates,
                rowsCleared.toString(),
                rowsTotal.toString(),
            ),
            accent = LogicColors.OnDarkMuted,
        )
        Spacer(modifier = Modifier.weight(1f))
        // En las rondas de Jefe no hay tropas enemigas que superar (el duelo se gana por vida),
        // así que el contador se calla en vez de enseñar un número que no significa nada.
        if (!isBoss) {
            HudPill(
                text = stringResource(Res.string.legion_hud_enemy, enemyTroops.toString()),
                accent = LogicColors.Magenta,
            )
        }
    }
}

/** Píldora de dato del HUD: fondo de superficie + texto teñido con su acento. */
@Composable
private fun HudPill(text: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Examen del barrido horizontal
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Panel del examen: la cuenta en grande, la barra de tiempo y los botones de opción flotantes.
 *
 * La barra cambia de color al agotarse la gracia (cian → Error): es el aviso visual de que el
 * drenaje ha empezado, sincronizado con el dominio ([SpaceshipEvent.HorizontalSweep.inGrace])
 * en vez de re-calcular umbrales aquí.
 */
@Composable
private fun LegionQuizPanel(
    sweep: SpaceshipEvent.HorizontalSweep,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark.copy(alpha = 0.94f))
            .border(1.5.dp, LogicColors.Magenta.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.legion_quiz_title),
            style = MaterialTheme.typography.titleLarge,
            color = LogicColors.Magenta,
        )
        Text(
            text = stringResource(Res.string.legion_quiz_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = sweep.quiz.prompt,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp),
            color = LogicColors.OnDark,
        )

        // Barra de tiempo: fracción restante sobre el límite; roja en cuanto drena.
        val fraction = (sweep.timeRemainingSec / LegionBalance.QUIZ_TIME_LIMIT_SEC).coerceIn(0f, 1f)
        val barColor = if (sweep.inGrace) LogicColors.NeonCyan else LogicColors.Error
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LogicColors.SurfaceVariantDark),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor),
            )
        }

        // Opciones en filas de máximo 3: con 5 opciones, una única fila dejaría botones por
        // debajo del mínimo táctil de 48 dp en móviles estrechos.
        sweep.quiz.options.withIndex().chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowOptions.forEach { (index, value) ->
                    QuizOptionButton(
                        label = value.toString(),
                        onClick = { onAnswer(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Botón flotante de respuesta: rebote táctil ([bounceClick]) y altura táctil ≥ 48 dp. */
@Composable
private fun QuizOptionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceVariantDark)
            .border(1.5.dp, LogicColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .bounceClick { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = LogicColors.OnDark,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Dibujo del mundo (DrawScope)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Techo de puntos dibujados por enjambre (ver cabecera de la pantalla). */
private const val MAX_SWARM_DOTS = 180

/** Tropas a partir de las cuales el radio logarítmico del enjambre satura. */
private const val SWARM_LOG_CAP = 5_000f

/** Ángulo áureo (radianes) de la distribución de girasol de [drawSwarm]. */
private const val GOLDEN_ANGLE = 2.39996f

/** Largo (proa→popa) y ancho de la nave de una tropa, en Dp. */
private const val SHIP_UNIT_LENGTH_DP = 9
private const val SHIP_UNIT_WIDTH_DP = 5.5f

/** Amplitud de la respiración radial del enjambre, como fracción de su radio. */
private const val SWARM_BREATH = 0.05f

/**
 * Cuánto se "enrosca" la espiral: rotación extra del interior respecto al borde. La formación
 * gira entera una vuelta por ciclo y, además, el centro adelanta y retrasa al borde — que es lo
 * que hace que la masa se lea como una **espiral viva** (una galaxia) y no como un disco rígido.
 */
private const val SPIRAL_SHEAR = 0.9f

/** Duración de un ciclo completo del giro del enjambre. */
private const val SWARM_CYCLE_MS = 2_600

/** Vuelta completa en radianes: el ciclo cierra aquí para que no dé un salto al repetir. */
private const val TWO_PI = (2.0 * PI).toFloat()

/** Vida de cada explosión, en fracción del progreso del combate (≈0,3 s del beat de 1,4 s). */
private const val CLASH_POP_LIFE = 0.22f

/**
 * Vida de una explosión medida en SEGUNDOS, para el duelo contra el Jefe. El choque normal mide
 * su tiempo en "progreso del beat" (de ahí [CLASH_POP_LIFE]); el duelo no tiene beat —dura lo
 * que dure— así que sus andanadas se apagan por reloj.
 */
private const val CLASH_POP_LIFE_SEC = 0.4f

/** Trazos simultáneos del fuego de la legión contra el Jefe (ver [drawLegionFire]). */
private const val BOSS_VOLLEY_BOLTS = 9

/** Vueltas que da el chorro de disparos por ciclo del vaivén: sube el ritmo de la ráfaga. */
private const val BOSS_FIRE_SPEED = 4f

/** Escala del sprite del Jefe: es la nave más grande del juego, y debe imponerse como tal. */
private const val BOSS_SHIP_SCALE = 2.6f

/** A cuánto por encima del casco del Jefe se dibuja su barra de vida, en Dp. */
private const val BOSS_BAR_OFFSET_DP = 62

/** Amplitud del vaivén lateral del Jefe, como fracción del ancho de la pista. */
private const val BOSS_DRIFT_AMPLITUDE = 0.24f

/** Tiempo de vuelo del disparo antes de reventar su objetivo, en fracción del progreso. */
private const val CLASH_SHOT_LEAD = 0.16f

/** Radio máximo del anillo de una explosión, en Dp. */
private const val CLASH_POP_RADIUS_DP = 12

/** Largo del trazo de un disparo neón, en Dp. */
private const val CLASH_BOLT_LEN_DP = 14

/**
 * Hash determinista índice → [0,1) (LCG barato). Da "azar" que NO cambia entre frames: si se
 * re-muestreara, lo que dependa de él vibraría en pantalla.
 */
private fun hash01(i: Int): Float = ((i * 1103515245 + 12345) and 0x7FFFFFFF) / 2147483647f

/** Naves dibujadas para un ejército de [troops] tropas (con el techo de [MAX_SWARM_DOTS]). */
private fun swarmDots(troops: Int): Int = min(troops, MAX_SWARM_DOTS)

/** Radio del disco del enjambre: crece con `ln(tropas)` y satura en [SWARM_LOG_CAP]. */
private fun swarmRadius(maxRadius: Float, troops: Int): Float =
    maxRadius * (0.30f + 0.70f * (ln(1f + troops) / ln(1f + SWARM_LOG_CAP))).coerceAtMost(1f)

/**
 * Posición de la nave [index] dentro de su enjambre. **Fuente única de la geometría**: la usan
 * tanto el dibujo de las naves ([drawSwarm]) como las explosiones y los disparos
 * ([drawArmyClashPops]), que es lo que garantiza que cada detonación caiga exactamente ENCIMA
 * de la nave que revienta y no en un punto aproximado del frente.
 *
 * ## La espiral
 * La base es la filotaxis (girasol): `r = R·√(i/n)`, `θ = i·ángulo áureo`, que reparte las naves
 * con densidad uniforme en el disco. Sobre ella se aplica el movimiento:
 *  - **Giro global** (`+ phase`): la formación entera da una vuelta por ciclo.
 *  - **Cizalla** (`sin(phase)·SPIRAL_SHEAR·(1−f)`): el interior adelanta y retrasa al borde, de
 *    modo que los brazos se enroscan y desenroscan. Es lo que convierte un giro rígido en una
 *    espiral que respira.
 *  - **Respiración radial**, desfasada por nave para que el enjambre "palpite" sin unísono.
 *
 * ## Por qué los coeficientes de [phase] son enteros
 * [phase] recorre `0..2π` en bucle. Cualquier término `sin(k·phase)` con `k` **no entero** vale
 * distinto en `0` y en `2π` y produciría un salto visible cada vuelta (un tirón del enjambre
 * cada ciclo). Los desfases por índice sí pueden ser arbitrarios: son constantes en el tiempo.
 */
private fun swarmShipOffset(
    center: Offset,
    index: Int,
    dots: Int,
    radius: Float,
    phase: Float,
): Offset {
    val ringFraction = sqrt((index + 0.5f) / dots)
    val breath = 1f + SWARM_BREATH * sin(2f * phase + index * 0.9f)
    val ring = radius * ringFraction * breath
    // Jitter determinista: rompe la perfección geométrica de la espiral sin re-muestrear.
    val jitter = (hash01(index) - 0.5f) * radius * 0.10f
    val angle = index * GOLDEN_ANGLE + phase + sin(phase) * SPIRAL_SHEAR * (1f - ringFraction)
    return center + Offset(
        x = (ring + jitter) * cos(angle),
        y = (ring + jitter) * sin(angle),
    )
}

/** Y a la que ENTRA la nave del barrido horizontal, antes de empezar a bajar. */
private const val SWEEP_START_Y = 0.06f

/**
 * Y en la que la nave del barrido FRENA para disparar: justo encima de la legión
 * ([LegionBalance.PLAYER_Y] = 0.8), dejando un pasillo para que se vean los disparos. Baja hasta
 * aquí durante los segundos de gracia y a partir de ahí se queda apuntando.
 */
private const val SWEEP_ATTACK_Y = 0.62f

/** Y normalizada de las naves de láser vertical (comparte el mismo valor el `Canvas` y el sprite). */
private const val VERTICAL_SHIP_Y = 0.055f

/** Ancho del sprite de nave, en Dp. El alto sale de su proporción real (685×1548 px). */
private const val SHIP_WIDTH_DP = 34

/** Alto del sprite de nave: `SHIP_WIDTH_DP / (685f / 1548f)`, redondeado. */
private const val SHIP_HEIGHT_DP = 77

/**
 * La escolta con láser vertical se dibuja un 40 % más grande que la nave del barrido. No es solo
 * escala: es jerarquía. Es la amenaza del tramo final —la que cubre al ejército enemigo— y tiene
 * que imponerse sobre una pista que a esas alturas ya lleva enjambre, enemigo y rótulos; al
 * tamaño base se perdía entre todo eso.
 */
private const val VERTICAL_SHIP_SCALE = 1.4f

/** Y su rayo, un 50 % más grueso, por el mismo motivo: debe leerse como el peligro dominante. */
private const val VERTICAL_LASER_SCALE = 1.5f

/** Divisores de carril: líneas verticales tenues que insinúan la pista sin competir con ella. */
private fun DrawScope.drawLaneDividers(lanes: Int, glowPulse: Float) {
    val laneWidth = size.width / lanes
    for (i in 1 until lanes) {
        val x = i * laneWidth
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    LogicColors.NeonCyan.copy(alpha = 0.16f * glowPulse),
                    Color.Transparent,
                ),
            ),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

/**
 * Color de TODAS las puertas: uno solo, el acento de la categoría.
 *
 * Decisión de diseño deliberada, y la razón de que esto sea una constante y no un `when` sobre
 * la operación: si `+`/`×` fueran verdes y `−`/`÷` rojas, el jugador elegiría por color y no
 * llegaría a leer la operación — el juego dejaría de ser de cálculo mental para ser de
 * reflejos cromáticos. Pintándolas todas igual, la única forma de decidir es comparar las
 * cifras, que es exactamente la habilidad que se quiere entrenar.
 *
 * El código de color semántico del §9.2 no desaparece: se aplica DESPUÉS de cruzar, en el
 * destello ([drawGateFlash], verde acierto / rojo pérdida) y en el sonido. Ahí ya no revela la
 * respuesta, solo confirma lo que pasó.
 */
private val GATE_COLOR: Color = CategoryPalette.MentalSpeed

/**
 * Una fila de puertas: panel translúcido + tubo neón por carril ([drawNeonTile], §9.7 — las
 * puertas SÍ son tiles, así que se reutiliza el componente compartido en vez de dibujar bordes
 * ad-hoc) con el rótulo de la operación centrado.
 */
private fun DrawScope.drawGateRow(
    row: GateRow,
    lanes: Int,
    glowPulse: Float,
    measurer: TextMeasurer,
    textStyle: TextStyle,
) {
    val yPx = row.y * size.height
    val gateHeight = 58.dp.toPx()
    // No dibujar filas completamente fuera de pantalla (nacen en y < 0).
    if (yPx + gateHeight < 0f || yPx - gateHeight > size.height) return

    val laneWidth = size.width / lanes
    for (gate in row.gates) {
        // Mismo color para todas: la puerta no debe delatar si suma o resta (ver [GATE_COLOR]).
        val color = GATE_COLOR
        val tileWidth = laneWidth * 0.88f
        val topLeft = Offset(
            x = gate.lane.index * laneWidth + (laneWidth - tileWidth) / 2f,
            y = yPx - gateHeight / 2f,
        )
        drawNeonTile(
            baseColor = color,
            activeAmt = 0.55f * glowPulse,
            cornerRadius = 16.dp,
            sparks = false,
            baseMargin = 4.dp,
            rectTopLeft = topLeft,
            rectSize = Size(tileWidth, gateHeight),
        )

        val layout = measurer.measure(AnnotatedString(gate.operation.label), textStyle.copy(color = color))
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = topLeft.x + (tileWidth - layout.size.width) / 2f,
                y = topLeft.y + (gateHeight - layout.size.height) / 2f,
            ),
        )
    }
}

/**
 * Rayo neón sobre una línea arbitraria. NO llama a [drawNeonTile] (un rayo no es el contorno de
 * un tile) pero replica su MISMA proporción de capas —halo ancho → halo intermedio → trazo
 * nítido → núcleo blanco— como exige el §9.7 para que todos los neones de la app compartan
 * idéntica estética.
 */
private fun DrawScope.drawNeonBeam(start: Offset, end: Offset, baseWidth: Float, color: Color, alpha: Float = 1f) {
    drawLine(color.copy(alpha = 0.30f * alpha), start, end, baseWidth * 4.5f, StrokeCap.Round)
    drawLine(color.copy(alpha = 0.55f * alpha), start, end, baseWidth * 2.1f, StrokeCap.Round)
    drawLine(color.copy(alpha = alpha), start, end, baseWidth, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.55f * alpha), start, end, baseWidth * 0.42f, StrokeCap.Round)
}

/**
 * Halo + rayo del láser vertical: línea de aviso que se intensifica con la carga (la telegrafía
 * del peligro: el jugador debe poder leer cuánto le queda) y el rayo pleno al disparar.
 *
 * El casco YA NO se dibuja aquí: lo pone el sprite [LegionShip] superpuesto al `Canvas` (ver el
 * bloque de overlays en [LegionScreen]) — un `DrawScope` no puede pintar un `Res.drawable`. El
 * halo se conserva porque el sprite no lleva resplandor ambiental propio y da profundidad al
 * conjunto.
 */
private fun DrawScope.drawVerticalLaser(
    laser: SpaceshipEvent.VerticalLaser,
    lanes: Int,
    glowPulse: Float,
) {
    val laneWidth = size.width / lanes
    val x = (laser.lane.index + 0.5f) * laneWidth
    val shipY = VERTICAL_SHIP_Y * size.height
    val beamEnd = Offset(x, LegionBalance.PLAYER_Y * size.height)

    // Halo magenta (peligro §9.2) que respira con el pulso ambiental, detrás del sprite. Crece
    // con la nave para seguir envolviéndola.
    val haloRadius = laneWidth * 0.34f * VERTICAL_SHIP_SCALE
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(LogicColors.Magenta.copy(alpha = 0.5f * glowPulse), Color.Transparent),
            center = Offset(x, shipY),
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = Offset(x, shipY),
    )

    if (laser.firing) {
        drawNeonBeam(
            start = Offset(x, shipY),
            end = beamEnd,
            baseWidth = 7.dp.toPx() * VERTICAL_LASER_SCALE,
            color = LogicColors.Error,
        )
    } else {
        // Aviso de carga: fino y translúcido, gana presencia según se acerca el disparo.
        drawLine(
            color = LogicColors.Error.copy(alpha = 0.12f + 0.4f * laser.chargeProgress),
            start = Offset(x, shipY),
            end = beamEnd,
            strokeWidth = (1.5f + 3f * laser.chargeProgress).dp.toPx() * VERTICAL_LASER_SCALE,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Rayo del barrido horizontal durante el examen: cruza todos los carriles (Error, §9.2) a la
 * altura a la que va la nave. Al bajar, el rayo baja con ella — es la guadaña que se acerca.
 */
private fun DrawScope.drawSweepBeam(glowPulse: Float, y: Float) {
    val yPx = y * size.height
    drawNeonBeam(
        start = Offset(0f, yPx),
        end = Offset(size.width, yPx),
        baseWidth = 6.dp.toPx(),
        color = LogicColors.Error,
        alpha = 0.75f + 0.25f * glowPulse,
    )
}

/**
 * Enjambre de puntos de luz: **un punto por unidad hasta [MAX_SWARM_DOTS]**, distribuidos con
 * la espiral de girasol (filotaxis): `r = R·√(i/n)`, `θ = i·ángulo áureo`.
 *
 * ## Por qué esta dispersión y no puntos aleatorios o una malla
 *  - La filotaxis reparte los puntos con densidad UNIFORME en el disco (la √ compensa que un
 *    anillo exterior tenga más circunferencia), sin los grumos ni huecos del muestreo aleatorio
 *    y sin las filas visibles de una malla — exactamente el "enjambre orgánico" que pide el spec.
 *  - Es **determinista por índice**: cada tropa conserva su posición entre frames, así que el
 *    enjambre se mueve como un cuerpo sólido en vez de "hervir" (re-muestrear al azar cada frame
 *    haría vibrar todos los puntos). El jitter que rompe la perfección geométrica también es
 *    determinista (hash del índice), por el mismo motivo.
 *  - El radio del disco crece con `ln(tropas)` (saturando en [SWARM_LOG_CAP]): duplicar el
 *    ejército debe VERSE, pero linealmente no cabría en el carril tras dos puertas `×`.
 *
 * ## Cada unidad es una NAVE, no un punto
 * Se dibuja un triángulo isósceles por tropa, con la proa apuntando hacia el enemigo ([facing]):
 * a distancia el enjambre se sigue leyendo como una masa de luz, pero de cerca se reconoce que
 * son naves — y la orientación comunica hacia dónde ataca cada bando sin necesidad de flechas.
 *
 * **La proa nunca gira**: aunque la formación rote en espiral, todas las naves apuntan siempre
 * al frente. Es deliberado — una escuadra en formación mantiene el rumbo mientras la formación
 * maniobra, y con las proas girando el enjambre se leía como confeti en vez de como un ejército.
 *
 * ## Y se MUEVE (espiral viva)
 * La posición de cada nave sale de [swarmShipOffset]: filotaxis + giro global + cizalla que
 * enrosca los brazos. Toda la masa fluye en espiral sin que ninguna nave pierda el rumbo.
 *
 * ## Layout estable durante el choque
 * [layoutTroops] fija la geometría (radio y número de posiciones) mientras [troops] dice cuántas
 * siguen vivas. Normalmente son el mismo número; en combate difieren, y esa separación es lo que
 * evita que el enjambre entero se recoloque cada vez que muere una nave — las supervivientes se
 * quedan donde están y solo desaparecen las caídas, de fuera hacia dentro (el frente primero).
 *
 * Todo el enjambre son DOS llamadas a `drawPath` (halo + cuerpo) sobre un único `Path` con los
 * 180 triángulos: coste constante por frame, sin un composable ni un draw call por tropa.
 */
private fun DrawScope.drawSwarm(
    center: Offset,
    troops: Int,
    maxRadius: Float,
    color: Color,
    glowPulse: Float,
    phase: Float,
    facing: Float,
    measurer: TextMeasurer,
    countStyle: TextStyle,
    layoutTroops: Int = troops,
) {
    val layoutDots = swarmDots(layoutTroops)
    if (layoutDots <= 0) return
    val radius = swarmRadius(maxRadius, layoutTroops)
    // Cuántas de las posiciones del layout siguen ocupadas (proporcional, porque una tropa no
    // es una nave dibujada cuando el ejército supera el techo de MAX_SWARM_DOTS).
    val aliveDots = (layoutDots.toLong() * troops / layoutTroops.coerceAtLeast(1)).toInt()
        .coerceIn(0, layoutDots)
    if (aliveDots <= 0) return

    // Halo colectivo: la "luz" del enjambre como masa, debajo de las naves individuales.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.30f * glowPulse), Color.Transparent),
            center = center,
            radius = radius * 1.7f,
        ),
        radius = radius * 1.7f,
        center = center,
    )

    val shipLength = SHIP_UNIT_LENGTH_DP.dp.toPx()
    val halfWidth = SHIP_UNIT_WIDTH_DP.dp.toPx() * 0.5f
    val swarm = Path()
    for (i in 0 until aliveDots) {
        val pos = swarmShipOffset(center, i, layoutDots, radius, phase)
        // Proa fija hacia [facing] (−1 arriba, +1 abajo): el triángulo no rota nunca.
        val tipY = pos.y + facing * shipLength * 0.6f
        val backY = pos.y - facing * shipLength * 0.4f
        swarm.moveTo(pos.x, tipY)
        swarm.lineTo(pos.x + halfWidth, backY)
        swarm.lineTo(pos.x - halfWidth, backY)
        swarm.close()
    }
    // Halo de contorno + cuerpo relleno: el mínimo para que el triángulo "brille" como neón sin
    // pagar las cuatro capas de [drawNeonBeam] por cada una de las 180 naves.
    drawPath(swarm, color = color.copy(alpha = 0.30f), style = Stroke(width = 3.dp.toPx()))
    drawPath(swarm, color = color)

    // La cifra exacta del ejército: el enjambre comunica la magnitud, el número la precisión.
    val layout = measurer.measure(AnnotatedString(troops.toString()), countStyle)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = center.x - layout.size.width / 2f,
            y = center.y - radius * 1.6f - layout.size.height,
        ),
    )
}

/**
 * Ejército enemigo cayendo por la pista: enjambre magenta centrado (ocupa todos los carriles:
 * no se esquiva, se supera).
 *
 * En las rondas de Jefe no se llama: allí la amenaza es una nave única y no una masa, y la pinta
 * el sprite de [LegionShip] en la capa de overlays.
 */
private fun DrawScope.drawEnemyArmy(
    enemyY: Float?,
    troops: Int,
    glowPulse: Float,
    phase: Float,
    measurer: TextMeasurer,
    countStyle: TextStyle,
    layoutTroops: Int = troops,
) {
    enemyY ?: return
    val yPx = enemyY * size.height
    // Mismo umbral que usa el motor para lanzar la escolta: "el enemigo aparece" tiene que
    // significar exactamente lo mismo aquí y allí, o dejarían de entrar juntos en escena.
    if (enemyY < LegionBalance.ENEMY_VISIBLE_Y || yPx > size.height) return
    val center = Offset(size.width / 2f, yPx)

    drawSwarm(
        center = center,
        troops = troops,
        maxRadius = size.width * 0.26f,
        color = LogicColors.Magenta,
        glowPulse = glowPulse,
        phase = phase,
        // El enemigo baja: sus proas apuntan HACIA el jugador (+1 = hacia abajo).
        facing = 1f,
        measurer = measurer,
        countStyle = countStyle,
        layoutTroops = layoutTroops,
    )
}

/**
 * Destello de cruce de puerta / impacto: anillo que se expande y desvanece en el carril del
 * suceso, verde si fue ganancia y Error si fue pérdida (feedback semántico §9.2).
 */
private fun DrawScope.drawGateFlash(flash: GateFlash, lanes: Int) {
    val progress = (flash.ageSec / LegionBalance.FLASH_DURATION_SEC).coerceIn(0f, 1f)
    val laneWidth = size.width / lanes
    val center = Offset(
        x = (flash.lane.index + 0.5f) * laneWidth,
        y = LegionBalance.PLAYER_Y * size.height,
    )
    val color = if (flash.positive) LogicColors.NeonGreen else LogicColors.Error
    drawCircle(
        color = color.copy(alpha = (1f - progress) * 0.55f),
        radius = laneWidth * (0.25f + 0.55f * progress),
        center = center,
        style = Stroke(width = 3.dp.toPx() * (1f - progress * 0.6f)),
    )
}

/**
 * Sprite de nave enemiga: **el mismo asset** que usa la VIP de Starport
 * ([starport_vip_ship]), reutilizado tal cual (sin recolorear: el magenta/cian del arte ya
 * encaja con el peligro del §9.2) en vez de un casco procedural nuevo por juego.
 *
 * Por qué compartir el asset y no dibujar uno propio: la app ya tiene UN diseño de "nave neón"
 * con identidad propia (casco puntiagudo, ventanilla cian, propulsores dobles); inventar un
 * segundo para Neon Legion fragmentaría esa identidad sin aportar nada — el jugador ve "la nave
 * de la app", no "la nave de este juego en concreto".
 *
 * El sprite nace en vertical con la proa hacia ARRIBA (igual que en Starport); aquí se rota
 * según el rol de la nave: 180° para el láser vertical (amenaza hacia abajo, hacia el jugador)
 * y 90° para el barrido horizontal (se lee en movimiento lateral). El tamaño es fijo
 * ([SHIP_WIDTH_DP]/[SHIP_HEIGHT_DP]) porque, a diferencia de Starport, aquí la nave no encaja
 * en una celda de rejilla que cambie de tamaño.
 */
@Composable
private fun LegionShip(rotation: Float, modifier: Modifier = Modifier, scale: Float = 1f) {
    Image(
        painter = painterResource(Res.drawable.starport_vip_ship),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            // El tamaño se escala en el propio `size` y no con `graphicsLayer.scale`: así el
            // hueco que ocupa la nave es el real y el `offset` que la centra sigue cuadrando.
            .width(SHIP_WIDTH_DP.dp * scale)
            .height(SHIP_HEIGHT_DP.dp * scale)
            .graphicsLayer { rotationZ = rotation },
    )
}

/**
 * Refriega del choque final sobre UN ejército: el disparo neón que llega desde el bando
 * contrario y la explosión de cada nave alcanzada, **en la posición exacta de esa nave**.
 *
 * ## Cada explosión cae sobre su nave
 * La posición sale de [swarmShipOffset] con los mismos parámetros que usó [drawSwarm] para
 * dibujarla, así que la detonación aparece justo encima de la nave que desaparece — no en un
 * punto aproximado del frente. El disparo que la mata viaja desde el enjambre contrario hasta
 * ese mismo punto.
 *
 * ## Cuándo muere cada nave (derivado, sin lista de partículas)
 * Las naves caen de FUERA hacia dentro (el índice más alto es el del anillo exterior, que es el
 * frente): la nave `j` muere cuando el ejército baja a `j` naves vivas, lo que ocurre en el
 * progreso `(1 − j/layoutDots) · tropas / parejas`. De ahí sale su edad, y con ella si toca
 * dibujar el disparo en vuelo (edad negativa, dentro de [CLASH_SHOT_LEAD]) o la explosión
 * (0..1). Las naves que sobreviven al choque dan un progreso de muerte > 1 y nunca se encienden
 * — el bando ganador conserva su excedente intacto, sin un solo disparo de más.
 *
 * Como todo se deriva de [CombatStats.progress] —que ya viaja en el estado—, el efecto es
 * reproducible, no acumula basura entre rondas y sobrevive a una recomposición sin reiniciarse a
 * medias. Una lista de partículas mutable habría necesitado su propio ciclo de vida, justo el
 * estado paralelo que MVI evita.
 *
 * @param center centro del enjambre que RECIBE los disparos.
 * @param shooterCenter centro del enjambre que dispara (origen de los trazos).
 * @param troops tropas de este bando al empezar el choque.
 * @param destroyedPairs naves que este bando va a perder (las parejas del choque).
 * @param shotColor color del disparo entrante — el del bando que dispara, no el de la víctima.
 */
private fun DrawScope.drawArmyClashPops(
    center: Offset,
    shooterCenter: Offset,
    maxRadius: Float,
    troops: Int,
    destroyedPairs: Int,
    progress: Float,
    phase: Float,
    shotColor: Color,
) {
    if (troops <= 0 || destroyedPairs <= 0) return
    val layoutDots = swarmDots(troops)
    val radius = swarmRadius(maxRadius, troops)
    val popRadius = CLASH_POP_RADIUS_DP.dp.toPx()
    val boltLength = CLASH_BOLT_LEN_DP.dp.toPx()
    // Fracción del ejército que cae: con ella se traduce "tropas muertas" a "naves dibujadas".
    val doomedRatio = troops.toFloat() / destroyedPairs.toFloat()

    for (j in 0 until layoutDots) {
        // Progreso al que muere esta nave (ver "Cuándo muere cada nave" en el KDoc).
        val deathAt = (1f - j.toFloat() / layoutDots) * doomedRatio
        if (deathAt > 1f) continue // Sobrevive al choque: ni disparo ni explosión.

        val age = (progress - deathAt) / CLASH_POP_LIFE
        if (age > 1f) continue // Ya se apagó.

        val pos = swarmShipOffset(center, j, layoutDots, radius, phase)

        if (age < 0f) {
            // Disparo en vuelo: aún no ha llegado. `t` es lo recorrido desde el origen.
            val lead = (deathAt - progress) / CLASH_SHOT_LEAD
            if (lead > 1f) continue // Todavía ni se ha disparado.
            val t = 1f - lead
            // Origen disperso dentro del enjambre atacante: los trazos salen de distintas
            // naves y no todos del mismo punto, que se leería como un único cañón.
            val origin = shooterCenter + Offset(
                x = (hash01(j * 3) - 0.5f) * radius * 1.2f,
                y = (hash01(j * 3 + 1) - 0.5f) * radius * 0.5f,
            )
            val head = origin + (pos - origin) * t
            val tail = origin + (pos - origin) * (t - boltLength / (pos - origin).getDistance())
                .coerceAtLeast(0f)
            // Trazo corto con la misma proporción de capas del neón del §9.7 (halo → nítido).
            drawLine(shotColor.copy(alpha = 0.35f), tail, head, 5.dp.toPx(), StrokeCap.Round)
            drawLine(shotColor, tail, head, 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.7f), tail, head, 0.8.dp.toPx(), StrokeCap.Round)
            continue
        }

        // Impacto: anillo de choque que se expande al apagarse + núcleo blanco que muere antes.
        val fade = 1f - age
        drawCircle(
            color = LogicColors.Amber.copy(alpha = 0.75f * fade),
            radius = popRadius * (0.35f + 0.65f * age),
            center = pos,
            style = Stroke(width = 1.8.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * fade * fade),
            radius = popRadius * 0.34f * fade,
            center = pos,
        )
    }
}

/**
 * Fuego continuo de la legión contra el Jefe: un puñado de trazos neón subiendo desde el
 * enjambre hasta el casco.
 *
 * Se dibuja un número FIJO de trazos ([BOSS_VOLLEY_BOLTS]) y no uno por nave: el daño real es
 * continuo (`tropas × dt`), así que los trazos representan el fuego, no lo contabilizan.
 * Intentar dibujar un disparo por nave con 500 naves sería una pared blanca y costaría un frame.
 *
 * Cada trazo recorre el trayecto con su propia fase (desfasada por índice sobre [phase]), de modo
 * que el chorro se lee como una ráfaga sostenida y no como una salva sincronizada.
 */
private fun DrawScope.drawLegionFire(from: Offset, to: Offset, spread: Float, phase: Float) {
    val direction = to - from
    val boltLength = CLASH_BOLT_LEN_DP.dp.toPx()
    val distance = direction.getDistance().coerceAtLeast(1f)

    for (i in 0 until BOSS_VOLLEY_BOLTS) {
        // Progreso 0..1 del trazo `i`, desfasado para que no viajen todos a la vez.
        val t = ((phase / TWO_PI) * BOSS_FIRE_SPEED + i.toFloat() / BOSS_VOLLEY_BOLTS) % 1f
        val lateral = (hash01(i) - 0.5f) * 2f * spread
        val origin = from + Offset(lateral, 0f)
        val target = to + Offset(lateral * 0.35f, 0f)
        val path = target - origin
        val head = origin + path * t
        val tail = origin + path * (t - boltLength / distance).coerceAtLeast(0f)
        drawLine(LogicColors.NeonGreen.copy(alpha = 0.35f), tail, head, 4.dp.toPx(), StrokeCap.Round)
        drawLine(LogicColors.NeonGreen, tail, head, 1.6.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * Andanada del Jefe: su rayo ancho barriendo hacia la legión y las explosiones de las naves que
 * acaba de fulminar.
 *
 * Las explosiones salen de la MISMA geometría del enjambre ([swarmShipOffset]) que usa
 * [drawSwarm], igual que en el choque final: revientan encima de las naves que desaparecen. El
 * tramo `[vivas, vivasAntes)` del layout es exactamente el 30 % que se llevó esta andanada.
 */
private fun DrawScope.drawBossVolley(
    boss: BossFight,
    bossCenter: Offset,
    playerCenter: Offset,
    playerMaxRadius: Float,
    troopsAlive: Int,
    phase: Float,
) {
    // Rayo: solo mientras dura el destello del disparo.
    if (boss.firing) {
        val fade = (boss.beamRemainingSec / LegionBalance.BOSS_BEAM_DURATION_SEC).coerceIn(0f, 1f)
        drawNeonBeam(
            start = bossCenter,
            end = Offset(playerCenter.x, playerCenter.y),
            baseWidth = 9.dp.toPx() * VERTICAL_LASER_SCALE,
            color = LogicColors.Error,
            alpha = fade,
        )
    }

    // Explosiones de la última andanada, mientras no se hayan apagado.
    val age = boss.volleyAgeSec / CLASH_POP_LIFE_SEC
    if (age > 1f || boss.troopsBeforeVolley <= troopsAlive) return

    val layoutDots = swarmDots(boss.troopsAtStart)
    if (layoutDots <= 0) return
    val radius = swarmRadius(playerMaxRadius, boss.troopsAtStart)
    val start = boss.troopsAtStart.coerceAtLeast(1)
    // Traducción tropas → posiciones del layout, la misma proporción que usa drawSwarm.
    val dotsBefore = (layoutDots.toLong() * boss.troopsBeforeVolley / start).toInt()
    val dotsAfter = (layoutDots.toLong() * troopsAlive / start).toInt()
    val popRadius = CLASH_POP_RADIUS_DP.dp.toPx()
    val fade = 1f - age

    for (j in dotsAfter until dotsBefore.coerceAtMost(layoutDots)) {
        val pos = swarmShipOffset(playerCenter, j, layoutDots, radius, phase)
        drawCircle(
            color = LogicColors.Amber.copy(alpha = 0.75f * fade),
            radius = popRadius * (0.35f + 0.65f * age),
            center = pos,
            style = Stroke(width = 1.8.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * fade * fade),
            radius = popRadius * 0.34f * fade,
            center = pos,
        )
    }
}

/**
 * Barra de vida del Jefe, bajo el HUD: la única lectura que el jugador tiene del duelo.
 *
 * Se dibuja en el `Canvas` y no como composable porque tiene que ir pegada a la nave —encima de
 * su casco, moviéndose con su vaivén—, y para eso necesita las coordenadas del mundo que solo el
 * `DrawScope` conoce.
 */
private fun DrawScope.drawBossHealthBar(boss: BossFight, bossCenter: Offset, glowPulse: Float) {
    val barWidth = size.width * 0.46f
    val barHeight = 7.dp.toPx()
    val left = bossCenter.x - barWidth / 2f
    val top = bossCenter.y - BOSS_BAR_OFFSET_DP.dp.toPx()

    drawRoundRect(
        color = LogicColors.SurfaceVariantDark.copy(alpha = 0.9f),
        topLeft = Offset(left, top),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barHeight / 2f),
    )
    drawRoundRect(
        color = LogicColors.Error.copy(alpha = 0.75f + 0.25f * glowPulse),
        topLeft = Offset(left, top),
        size = Size(barWidth * boss.hpFraction, barHeight),
        cornerRadius = CornerRadius(barHeight / 2f),
    )
}
