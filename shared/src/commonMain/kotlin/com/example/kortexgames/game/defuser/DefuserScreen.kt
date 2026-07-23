package com.example.kortexgames.game.defuser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameCategory
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.FireworksOverlay
import com.example.kortexgames.ui.components.GameExitGuard
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.ReviveAdOverlay
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.bounceClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Texto de la antesala; se reutiliza como ayuda dentro del menú de pausa. */
private const val DEFUSER_HELP =
    "Desactiva el panel sin detonar ninguna mina. Toca una celda para revelarla: " +
        "el número indica cuántas de sus 8 celdas contiguas ocultan una mina. Un " +
        "toque prolongado coloca un escudo donde crees que hay peligro. El primer " +
        "toque siempre es seguro."

/**
 * # Neon Defuser — Pantalla (Compose, FASE 3)
 *
 * Orquesta la interfaz: antesala con selector de dificultad, HUD, el
 * [DefuserBoard] y los overlays comunes (fin de partida, pausa, guardia de
 * salida). La lógica de juego vive entera en el [DefuserViewModel] (FASE 2); esta
 * pantalla solo **observa el estado** y **traduce los efectos** one-shot a sonido,
 * háptica y las tres animaciones del brief:
 *
 *  - **Cascada**: la revelación en cadena no aparece de golpe. Se escalona por
 *    distancia al toque, produciendo una onda de luz que recorre el panel (ver
 *    [revealAlphaFor] más abajo). Se calcula aquí, en la capa de presentación, a
 *    partir del estado —no en el ViewModel—: es adorno visual con ciclo propio, y
 *    meterlo en el estado obligaría al motor a emitir un frame por anillo.
 *  - **Detonación**: halo rojo expansivo sobre la mina pisada.
 *  - **Victoria**: onda de luz que barre el panel.
 *
 * @param graph grafo de DI (repos, audio, settings).
 * @param onExit callback para volver al catálogo de juegos.
 */
@Composable
fun DefuserScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: DefuserViewModel = viewModel {
        DefuserViewModel(
            graph.progressRepository,
            graph.savedGameStateRepository,
            graph.audio,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val accent = CategoryPalette.Attention

    // Único punto de salida "en juego": guarda la partida antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // --- Estado de animación (vive en la UI, no en el UiState) -----------------
    val detonate = remember { Animatable(0f) }
    // Destello rojo a pantalla completa del instante de la explosión.
    val blast = remember { Animatable(0f) }
    // Sacudida del panel al detonar (avance 0..1 de la sinusoide amortiguada).
    val shake = remember { Animatable(0f) }
    // Celebración de victoria: mientras es true, la capa de fuegos está viva.
    var celebrating by remember { mutableStateOf(false) }

    // Cascada: qué celdas están revelándose ahora, desde dónde y hasta qué anillo.
    var animatingCells by remember { mutableStateOf<Set<CellPosition>>(emptySet()) }
    var waveOrigin by remember { mutableStateOf<CellPosition?>(null) }
    var waveMaxDistance by remember { mutableStateOf(0) }
    val cascade = remember { Animatable(1f) } // 1 = asentado (sin onda en curso)
    // Última celda tocada: origen de la onda de la cascada (el estado no lo lleva).
    var lastTap by remember { mutableStateOf<CellPosition?>(null) }
    // Celdas ya reveladas en el frame anterior, para detectar las nuevas.
    var prevRevealed by remember { mutableStateOf<Set<CellPosition>>(emptySet()) }

    // Detecta qué celdas se acaban de revelar y lanza la onda. Se reevalúa solo
    // cuando cambia el tablero (no en cada tick del cronómetro, que no lo toca).
    LaunchedEffect(state.board) {
        val current = state.board.cells
            .filter { it.state == MineCellState.REVEALED }
            .map { it.position }
            .toSet()
        when {
            // Reinicio / nueva partida: el tablero se vació de reveladas.
            current.size < prevRevealed.size -> {
                prevRevealed = current
                animatingCells = emptySet()
            }
            else -> {
                val newly = current - prevRevealed
                prevRevealed = current
                if (newly.isNotEmpty()) {
                    val origin = lastTap ?: newly.first()
                    waveOrigin = origin
                    waveMaxDistance = newly.maxOf { it.distanceTo(origin) }
                    animatingCells = newly
                    cascade.snapTo(0f)
                    cascade.animateTo(1f, tween(cascadeDurationMs(waveMaxDistance), easing = LinearEasing))
                    animatingCells = emptySet() // asentar: todas quedan a alpha 1
                }
            }
        }
    }

    // Traducción de efectos one-shot → audio/háptica/animación. Un único colector.
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when (effect) {
                is DefuserEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is DefuserEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
                is DefuserEffect.ExplodeAt -> {
                    // Tres pistas en paralelo (destello, sacudida y la explosión del
                    // panel) más una réplica háptica: se lanzan en corrutinas hijas
                    // para que corran a la vez con ritmos distintos en lugar de
                    // encadenarse (el destello dura un tercio de la explosión).
                    launch {
                        blast.snapTo(1f)
                        blast.animateTo(0f, tween(BLAST_FLASH_MS, easing = LinearEasing))
                    }
                    launch {
                        shake.snapTo(0f)
                        shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
                    }
                    launch {
                        // Réplica: un segundo golpe háptico cuando la onda ya se ha
                        // expandido, para que la explosión "retumbe" en vez de dar
                        // un único toque seco (el primer HEAVY lo emite el motor).
                        delay(AFTERSHOCK_DELAY_MS)
                        graph.audio.hapticFeedback(HapticFeedback.MEDIUM)
                    }
                    detonate.snapTo(0f)
                    detonate.animateTo(1f, tween(DETONATE_MS, easing = FastOutSlowInEasing))
                }
                DefuserEffect.VictoryFireworks -> celebrating = true
            }
        }
    }

    // Opacidad de cada celda para la cascada: 1 salvo las que la onda aún no
    // alcanzó. El frente avanza `cascade.value·(maxDist+FADE)`; una celda a
    // distancia d se enciende cuando el frente la sobrepasa, con una rampa suave
    // de [CASCADE_FADE_RINGS] anillos para que no "salte".
    val revealAlphaFor: (MineCell) -> Float = revealAlphaFor@{ cell ->
        if (cell.state != MineCellState.REVEALED || cell.position !in animatingCells) {
            return@revealAlphaFor 1f
        }
        val distance = waveOrigin?.let { cell.position.distanceTo(it) } ?: 0
        val front = cascade.value * (waveMaxDistance + CASCADE_FADE_RINGS)
        ((front - distance) / CASCADE_FADE_RINGS).coerceIn(0f, 1f)
    }

    // Antesala mientras el juego está en IDLE (mismo patrón que el resto de juegos:
    // el selector de dificultad se superpone por fuera del componente compartido).
    if (state.status == GameStatus.IDLE) {
        Box(modifier = Modifier.fillMaxSize()) {
            GameIntroScreen(
                title = "Neon Defuser",
                description = DEFUSER_HELP,
                accent = accent,
                icon = GameCategory.ATTENTION.icon,
                startLabel = if (state.hasSavedGame) "Continuar" else "Comenzar",
                onStart = { vm.onIntent(DefuserIntent.Start) },
                onExit = onExit,
                background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
            )
            if (!state.hasSavedGame) {
                DifficultySelector(
                    selected = state.difficulty,
                    onSelect = { vm.onIntent(DefuserIntent.SelectDifficulty(it)) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = DIFFICULTY_SELECTOR_BOTTOM_GAP),
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 18.dp),
        ) {
            DefuserHud(
                elapsedMs = state.elapsedMs,
                minesRemaining = state.minesRemaining,
                difficulty = state.difficulty,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Sacudida del panel entero al detonar: el golpe se siente en la
                    // pantalla, no solo en la celda. Se aplica con offset (no con
                    // layout) para no reposicionar nada ni invalidar la medida.
                    .offset { IntOffset(shakeOffsetPx(shake.value).roundToInt(), 0) },
                contentAlignment = Alignment.Center,
            ) {
                DefuserBoard(
                    state = state,
                    detonateProgress = detonate.value,
                    revealAlphaFor = revealAlphaFor,
                    onReveal = { position ->
                        lastTap = position
                        vm.onIntent(DefuserIntent.RevealCell(position))
                    },
                    onFlag = { position -> vm.onIntent(DefuserIntent.ToggleFlag(position)) },
                )
            }
        }

        // Destello rojo a pantalla completa en el instante de la detonación: es lo
        // que hace que la explosión "salga" del tablero y golpee toda la pantalla.
        // Va sobre el panel pero por debajo de los overlays, y sin pointer input,
        // así que no intercepta toques.
        if (blast.value > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = LogicColors.Error.copy(alpha = BLAST_MAX_ALPHA * blast.value))
            }
        }

        // Celebración de victoria: tanda larga de fuegos artificiales neón, con
        // sonido y háptica sincronizados a cada estallido (§9.4: puntual, no en
        // bucle). Sustituye al antiguo barrido de luz: una victoria merece una
        // celebración con cuerpo, no un destello que cruza y se va.
        if (celebrating) {
            FireworksOverlay(
                modifier = Modifier.fillMaxSize(),
                burstCount = VICTORY_BURSTS,
                onBurst = { index ->
                    graph.audio.playSound(SoundEffect.SUCCESS)
                    graph.audio.hapticFeedback(
                        if (index == 0) HapticFeedback.HEAVY else HapticFeedback.LIGHT,
                    )
                },
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            val won = state.phase == MinePhase.WON
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = if (won) "¡Panel desactivado!" else "Panel detonado",
                onPlayAgain = { vm.onIntent(DefuserIntent.RestartGame) },
                onExit = onExit,
            )
        }

        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(DefuserIntent.Pause) },
            onResume = { vm.onIntent(DefuserIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Neon Defuser",
            helpText = DEFUSER_HELP,
            accent = accent,
            exitKeepsProgress = true,
        )

        // Segunda oportunidad: al pisar una mina (una vez por partida) se ofrece
        // neutralizarla viendo un anuncio y continuar. Componente reutilizable
        // común (mismo que Neon Sudoku); se emite EL ÚLTIMO —salvo GameExitGuard—
        // para quedar por encima del botón de pausa (la partida sigue en RUNNING
        // mientras se decide) y bloquear el tablero.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(DefuserIntent.Revive) },
                onDecline = { vm.onIntent(DefuserIntent.DeclineRevive) },
                title = "¿Otra oportunidad?",
                rewardLabel = "desactivar esta mina",
                accent = accent,
                audio = graph.audio,
            )
        }

        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(DefuserIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = accent,
        )
    }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * Cabecera: tiempo, minas restantes (con icono de escudo) y dificultad. Son las
 * métricas que el jugador consulta de un vistazo; van en una fila con el mismo
 * peso visual, dejando hueco a la derecha para el botón de pausa.
 */
@Composable
private fun DefuserHud(elapsedMs: Long, minesRemaining: Int, difficulty: MineDifficulty) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = PauseButtonReserve),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudStat(label = "TIEMPO", value = formatElapsed(elapsedMs), tint = LogicColors.OnDark)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MINAS",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NeonIcon(
                    icon = KortexIcons.Shield,
                    tint = LogicColors.Violet,
                    size = 18.dp,
                    glow = false,
                    contentDescription = null,
                )
                Text(
                    text = minesRemaining.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = LogicColors.OnDark,
                )
            }
        }
        HudStat(label = "NIVEL", value = difficulty.displayName, tint = CategoryPalette.Attention)
    }
}

/** Una métrica del HUD: etiqueta pequeña arriba y valor destacado debajo. */
@Composable
private fun HudStat(label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDarkMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = tint,
        )
    }
}

/** `mm:ss` a partir de los milisegundos transcurridos. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

// ---------------------------------------------------------------------------
// Selector de dificultad (antesala)
// ---------------------------------------------------------------------------

/**
 * Tarjeta flotante con un chip por cada [MineDifficulty]. Replica el patrón del
 * selector de Neon Sudoku (y del tamaño de tablero de Neon Grid 2048) para que
 * todos los juegos con dificultad la ofrezcan igual. Es escalable por construcción:
 * la fila recorre [MineDifficulty.entries], así que añadir un nivel es un cambio en
 * la enum, no aquí.
 */
@Composable
private fun DifficultySelector(
    selected: MineDifficulty,
    onSelect: (MineDifficulty) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LogicColors.SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "DIFICULTAD",
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
        )
        // fillMaxWidth + weight(1f) por chip: los tres ocupan el mismo ancho exacto
        // en vez de ajustarse a su propio contenido. Antes, "Difícil" (el texto más
        // largo: "10×14 · 37 minas") no cabía en el ancho que le tocaba por
        // contenido propio y envolvía en 3 líneas, descuadrando la tarjeta entera.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MineDifficulty.entries.forEach { difficulty ->
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

/** Un chip del [DifficultySelector]; resaltado en acento cuando está elegido.
 *  Muestra además la geometría del panel para que el jugador sepa a qué se mete.
 *
 *  La estadística va en **dos líneas cortas fijas** ("8×10" y "13 minas") en vez
 *  de un único string con "·": una línea larga se parte donde el layout decida
 *  (a veces a mitad de un número), mientras que dos líneas cortas nunca necesitan
 *  envolver, así los tres chips —de igual [modifier] con `weight(1f)`— quedan
 *  siempre con la misma altura. */
@Composable
private fun DifficultyChip(
    difficulty: MineDifficulty,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = CategoryPalette.Attention
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else LogicColors.SurfaceVariantDark)
            .border(
                BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) accent else LogicColors.OnDarkMuted.copy(alpha = 0.2f),
                ),
                shape,
            )
            .bounceClick(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            difficulty.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else LogicColors.OnDarkMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        Text(
            "${difficulty.columns}×${difficulty.rows}",
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
            maxLines = 1,
        )
        Text(
            "${difficulty.mineCount} minas",
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Sacudida de la detonación
// ---------------------------------------------------------------------------

/**
 * Desplazamiento horizontal del panel durante la sacudida de la explosión: una
 * **sinusoide amortiguada**. La amplitud decae con `(1 - progress)` para que el
 * panel se frene solo en su sitio en vez de cortarse en seco a mitad de
 * oscilación (misma receta que la sacudida de error de Neon Sudoku).
 *
 * @param progress avance `0..1` de la sacudida; `0`/`1` = en reposo.
 */
private fun shakeOffsetPx(progress: Float): Float {
    if (progress <= 0f || progress >= 1f) return 0f
    val amplitude = SHAKE_AMPLITUDE_PX * (1f - progress)
    return sin(progress * SHAKE_CYCLES * 2f * PI.toFloat()) * amplitude
}

// --- Constantes de render ---------------------------------------------------

/** Separación del selector de dificultad respecto al fondo de la antesala (deja
 *  hueco al CTA "Comenzar" que ancla `GameIntroScreen`, igual que Neon Sudoku). */
private val DIFFICULTY_SELECTOR_BOTTOM_GAP = 108.dp

/** Hueco que el HUD deja a su derecha para el botón de pausa (44 dp + 16 dp). */
private val PauseButtonReserve = 60.dp

/** Anillos de rampa suave del frente de la cascada. */
private const val CASCADE_FADE_RINGS = 1.6f

/** Estallidos de la celebración de victoria. Bastante por encima del defecto de
 *  [FireworksOverlay] (6): despejar el panel es el hito del juego y se pidió
 *  expresamente una traca larga. */
private const val VICTORY_BURSTS = 14

/** Duración de la explosión de la mina (ms): onda expansiva + metralla. Más larga
 *  que un micro-feedback porque es un revelado dramático (§9.4: 300–600 ms). */
private const val DETONATE_MS = 620

/** Duración del destello rojo a pantalla completa (ms). Muy corto: es el fogonazo
 *  del impacto, no un tinte que se quede. */
private const val BLAST_FLASH_MS = 190

/** Opacidad máxima del destello rojo. Contenido a propósito: a pantalla completa,
 *  un rojo pleno resultaría agresivo (§9.4: nunca invasivo). */
private const val BLAST_MAX_ALPHA = 0.42f

/** Duración de la sacudida del panel al detonar (ms). */
private const val SHAKE_MS = 420

/** Amplitud de la sacudida en píxeles. */
private const val SHAKE_AMPLITUDE_PX = 26f

/** Oscilaciones completas de la sacudida. */
private const val SHAKE_CYCLES = 4f

/** Retardo de la réplica háptica tras la detonación (ms): cae cuando la onda ya
 *  se ha expandido, para que el golpe "retumbe". */
private const val AFTERSHOCK_DELAY_MS = 170L

/**
 * Duración de una cascada según su alcance ([maxDistance] anillos): un revelado de
 * una sola celda es casi instantáneo, mientras que una gran apertura de ceros se
 * escalona hasta un tope para que la onda se aprecie sin llegar a sentirse lenta
 * (§9.4: fluida y no invasiva).
 */
private fun cascadeDurationMs(maxDistance: Int): Int =
    (CASCADE_BASE_MS + maxDistance * CASCADE_MS_PER_RING).coerceAtMost(CASCADE_MAX_MS)

/** Duración base de la cascada (una celda) en ms. */
private const val CASCADE_BASE_MS = 120

/** Milisegundos que añade cada anillo de distancia a la cascada. */
private const val CASCADE_MS_PER_RING = 55

/** Tope de duración de la cascada en ms (aperturas muy grandes). */
private const val CASCADE_MAX_MS = 700
