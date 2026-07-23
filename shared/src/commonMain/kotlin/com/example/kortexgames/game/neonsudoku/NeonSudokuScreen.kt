package com.example.kortexgames.game.neonsudoku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameCategory
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.GameExitGuard
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.components.ReviveAdOverlay
import com.example.kortexgames.ui.components.SpaceBackdrop
import com.example.kortexgames.ui.components.bounceClick
import com.example.kortexgames.ui.components.drawNeonTile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.key

/** Texto de la antesala; se reutiliza como ayuda dentro del menú de pausa. */
private const val NEON_SUDOKU_HELP =
    "Completa la matriz 9x9: cada fila, cada columna y cada bloque 3x3 deben " +
        "contener los dígitos del 1 al 9 sin repetirse. Toca una celda, elige un " +
        "número y activa el lápiz para anotar tus hipótesis."

/**
 * # Neon Sudoku Matrix — Pantalla (Compose, FASE 3)
 *
 * Orquesta las tres piezas de la interfaz: el HUD, el [NeonSudokuBoard] y el
 * teclado numérico. La lógica de juego vive entera en el [NeonSudokuViewModel]
 * (FASE 2); esta pantalla solo **observa el estado** y **traduce los efectos**
 * one-shot a sonido, háptica y las dos animaciones del brief (sacudida de error
 * y onda de luz de victoria).
 *
 * Las animaciones se guardan aquí y no en el `UiState` a propósito: son adorno
 * visual con ciclo de vida propio (arrancan por un efecto y se apagan solas), y
 * meterlas en el estado obligaría al ViewModel a emitir un frame por paso.
 *
 * @param graph grafo de DI (repos, audio, settings).
 * @param onExit callback para volver al catálogo de juegos.
 */
@Composable
fun NeonSudokuScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: NeonSudokuViewModel = viewModel {
        NeonSudokuViewModel(
            graph.progressRepository,
            graph.savedGameStateRepository,
            graph.audio,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la partida en curso antes de navegar atrás (ver requestExit).
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    // Sacudida de la celda infractora: qué celda y en qué punto del recorrido.
    var shakeCell by remember { mutableStateOf<CellPosition?>(null) }
    val shake = remember { Animatable(0f) }
    // Onda de luz que barre el tablero al ganar.
    val sweep = remember { Animatable(0f) }
    // Celebraciones de unidad completada activas. Son varias a la vez a propósito
    // (dos unidades pueden cerrarse con una jugada, o casi seguidas), así que van
    // en una lista y cada una se retira sola al terminar.
    val completionWaves = remember { mutableStateListOf<CompletionWave>() }
    val scope = rememberCoroutineScope()

    // Traducción de efectos one-shot → audio/háptica/animación. Un único colector.
    LaunchedEffect(vm) {
        // Contador local (no `remember`): vive en esta corrutina mientras dure la
        // pantalla y solo da a cada onda una identidad propia.
        var waveSeed = 0
        vm.effect.collect { effect ->
            when (effect) {
                is NeonSudokuEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonSudokuEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
                is NeonSudokuEffect.ShakeCell -> {
                    shakeCell = effect.position
                    // LinearEasing: la amortiguación ya la aplica la propia
                    // sinusoide del tablero; un easing encima la deformaría.
                    shake.snapTo(0f)
                    shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
                    shakeCell = null
                }
                is NeonSudokuEffect.UnitsCompleted -> {
                    waveSeed++
                    val wave = CompletionWave(
                        id = waveSeed,
                        cells = effect.cells,
                        origin = effect.origin,
                        progress = Animatable(0f),
                    )
                    completionWaves += wave
                    // Se anima en `scope` y no aquí: este colector es secuencial, y
                    // esperar dentro bloquearía los efectos siguientes (el sonido de
                    // la jugada posterior llegaría tarde). Así las ondas se solapan.
                    scope.launch {
                        wave.progress.animateTo(1f, tween(WAVE_MS, easing = LinearEasing))
                        completionWaves.remove(wave)
                    }
                }
                NeonSudokuEffect.SweepVictory -> {
                    sweep.snapTo(0f)
                    sweep.animateTo(1f, tween(SWEEP_MS, easing = FastOutSlowInEasing))
                    sweep.snapTo(0f)
                }
            }
        }
    }

    // Antesala mientras el juego está en IDLE, igual que el resto de juegos.
    //
    // El selector de dificultad NO se mete dentro de `GameIntroScreen` (componente
    // compartido por los ~30 juegos del catálogo, sin conocimiento de que este
    // tiene dificultades): se superpone por fuera, en un `Box` que la envuelve,
    // exactamente igual que Neon Grid 2048 hace con su selector de tamaño de
    // tablero. Al ir después en el árbol de composición se dibuja por encima, y se
    // ancla sobre el CTA "Comenzar" que la antesala fija al fondo.
    if (state.status == GameStatus.IDLE) {
        Box(modifier = Modifier.fillMaxSize()) {
            GameIntroScreen(
                title = "Neon Sudoku Matrix",
                description = NEON_SUDOKU_HELP,
                accent = CategoryPalette.Logic,
                // Sin arte "héroe" propio todavía: se cae al icono de la categoría
                // (§9.5, siempre vectorial) en vez de dejar el recuadro vacío.
                icon = GameCategory.LOGIC.icon,
                // "Continuar" si hay partida guardada; si no, "Comenzar". El intent
                // Start decide (reanudar o nueva) — la UI solo cambia la etiqueta.
                startLabel = if (state.hasSavedGame) "Continuar" else "Comenzar",
                onStart = { vm.onIntent(NeonSudokuIntent.Start) },
                onExit = onExit,
                background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
            )
            // El selector solo tiene sentido para una partida nueva: si hay una
            // guardada, "Continuar" la reanuda con su propia dificultad, así que se
            // oculta para no sugerir que se puede cambiar (mismo criterio de "no
            // ofrecer lo que el intent ignoraría").
            if (!state.hasSavedGame) {
                DifficultySelector(
                    selected = state.difficulty,
                    onSelect = { vm.onIntent(NeonSudokuIntent.SelectDifficulty(it)) },
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
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            NeonSudokuHud(
                elapsedMs = state.elapsedMs,
                errorCount = state.errorCount,
                filled = state.board.filledCount,
            )

            // El tablero ocupa el espacio libre y queda centrado; así el teclado
            // baja a la zona cómoda del pulgar.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                NeonSudokuBoard(
                    state = state,
                    shakeCell = shakeCell,
                    shakeProgress = shake.value,
                    sweepProgress = sweep.value,
                    completionWaves = completionWaves,
                    onSelectCell = { row, col -> vm.onIntent(NeonSudokuIntent.SelectCell(row, col)) },
                )
            }

            Spacer(Modifier.height(18.dp))

            NeonSudokuNumpad(
                board = state.board,
                notesMode = state.notesMode,
                onInput = { vm.onIntent(NeonSudokuIntent.InputNumber(it)) },
                onToggleNotes = { vm.onIntent(NeonSudokuIntent.ToggleNotesMode) },
                onErase = { vm.onIntent(NeonSudokuIntent.EraseCell) },
            )
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            // Ganó si el tablero quedó completo y sin choques; si no, perdió por
            // agotar los errores. Cambia solo el titular del overlay de resultado.
            val won = state.board.isComplete && !state.board.hasAnyConflict
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = if (won) "¡Matriz completada!" else "Sin intentos",
                onPlayAgain = { vm.onIntent(NeonSudokuIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú común (Reanudar / audio / ayuda / Salir). "SALIR"
        // guarda la partida en curso (exitKeepsProgress) antes de volver al catálogo.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(NeonSudokuIntent.Pause) },
            onResume = { vm.onIntent(NeonSudokuIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Neon Sudoku Matrix",
            helpText = NEON_SUDOKU_HELP,
            accent = CategoryPalette.Logic,
            exitKeepsProgress = true,
        )

        // Segunda oportunidad: al agotar los errores (una vez por partida) se ofrece
        // continuar con margen extra viendo un anuncio. Componente reutilizable
        // común; se emite EL ÚLTIMO para quedar por encima del botón de pausa (la
        // partida sigue en RUNNING mientras se decide) y bloquear el tablero.
        if (state.awaitingRevive) {
            ReviveAdOverlay(
                adManager = graph.adManager,
                onRevive = { vm.onIntent(NeonSudokuIntent.Revive) },
                onDecline = { vm.onIntent(NeonSudokuIntent.DeclineRevive) },
                title = "¿Otra oportunidad?",
                rewardLabel = "un intento más",
                accent = CategoryPalette.Logic,
                audio = graph.audio,
            )
        }

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la corrida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(NeonSudokuIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = CategoryPalette.Logic,
        )
    }
}

// ---------------------------------------------------------------------------
// Selector de dificultad (antesala)
// ---------------------------------------------------------------------------

/**
 * Tarjeta flotante con un chip por cada [SudokuDifficulty], para elegir la
 * dificultad antes de empezar. Replica **exactamente** el patrón del selector de
 * tamaño de tablero de Neon Grid 2048 (tarjeta con fondo propio superpuesta sobre
 * la antesala) para que ambos juegos ofrezcan la elección de la misma forma.
 *
 * Es **escalable por construcción**: la fila sale de recorrer
 * [SudokuDifficulty.entries], así que añadir un nivel es un cambio en la enum, no
 * aquí.
 *
 * @param selected dificultad actualmente elegida (resaltada).
 * @param onSelect se invoca con la dificultad tocada.
 */
@Composable
private fun DifficultySelector(
    selected: SudokuDifficulty,
    onSelect: (SudokuDifficulty) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SudokuDifficulty.entries.forEach { difficulty ->
                key(difficulty) {
                    DifficultyChip(
                        difficulty = difficulty,
                        selected = difficulty == selected,
                        onClick = { onSelect(difficulty) },
                    )
                }
            }
        }
    }
}

/** Un chip del [DifficultySelector]; resaltado en acento cuando está elegido. */
@Composable
private fun DifficultyChip(difficulty: SudokuDifficulty, selected: Boolean, onClick: () -> Unit) {
    val accent = CategoryPalette.Logic
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            difficulty.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else LogicColors.OnDarkMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * Cabecera: tiempo, errores y progreso de relleno. Son las tres métricas que el
 * jugador consulta de un vistazo sin dejar de mirar el tablero, así que van en
 * una sola fila y con el mismo peso visual.
 *
 * El contador de errores se pinta en [LogicColors.Error] **solo cuando hay
 * alguno**: en 0 sería una mancha roja permanente que el ojo aprende a ignorar
 * (§9.1, el acento vale porque es escaso).
 */
@Composable
private fun NeonSudokuHud(elapsedMs: Long, errorCount: Int, filled: Int) {
    Row(
        // Reserva la esquina superior derecha: ahí vive el botón de pausa que
        // pinta `GamePauseControls` sobre esta capa. Sin este hueco, la última
        // métrica queda debajo del botón (mismo motivo por el que Neon Pulse
        // agrupa su HUD a la izquierda y al centro, nunca en TopEnd).
        modifier = Modifier.fillMaxWidth().padding(end = PauseButtonReserve),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudStat(label = "TIEMPO", value = formatElapsed(elapsedMs), tint = LogicColors.OnDark)
        // Errores como `X/3`: comunica de un vistazo cuánto margen queda antes de
        // perder. Se tiñe de rojo solo al haber alguno (§9.1: acento escaso).
        HudStat(
            label = "ERRORES",
            value = "$errorCount/${NeonSudokuConfig.MAX_ERRORS}",
            tint = if (errorCount > 0) LogicColors.Error else LogicColors.OnDark,
        )
        HudStat(
            label = "CELDAS",
            value = "$filled/${NeonSudokuConfig.CELL_COUNT}",
            tint = CategoryPalette.Logic,
        )
    }
}

/** Una métrica del HUD: etiqueta pequeña arriba y valor destacado debajo. */
@Composable
private fun HudStat(label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.OnDarkMuted,
        )
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
// Teclado numérico
// ---------------------------------------------------------------------------

/**
 * Teclado inferior: los nueve dígitos y las dos acciones (lápiz y borrar).
 *
 * Detalle de UX: un dígito ya colocado nueve veces se **atenúa**. Sigue siendo
 * pulsable (el jugador puede haberlo colocado mal y querer corregir), pero deja
 * de reclamar atención — le ahorra recorrer el tablero contando apariciones.
 *
 * @param board tablero actual; de él se derivan las nueve cuentas de dígitos.
 * @param notesMode si el lápiz está activo (enciende su tecla).
 */
@Composable
private fun NeonSudokuNumpad(
    board: Board,
    notesMode: Boolean,
    onInput: (Int) -> Unit,
    onToggleNotes: () -> Unit,
    onErase: () -> Unit,
) {
    val counts = remember(board) {
        (NeonSudokuConfig.MIN_DIGIT..NeonSudokuConfig.MAX_DIGIT)
            .associateWith { board.cellsWithValue(it).size }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Acciones primero: quedan más lejos del pulgar que los dígitos, que son
        // lo que se pulsa constantemente.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionKey(
                icon = KortexIcons.Pencil,
                label = "Notas",
                active = notesMode,
                contentDescription = if (notesMode) "Modo notas activo" else "Activar modo notas",
                onClick = onToggleNotes,
            )
            ActionKey(
                icon = KortexIcons.Backspace,
                label = "Borrar",
                active = false,
                contentDescription = "Borrar la celda seleccionada",
                onClick = onErase,
            )
        }

        // Los nueve dígitos en dos filas (5 + 4): una sola fila de nueve teclas
        // las dejaría demasiado estrechas para el pulgar en móviles pequeños.
        val digits = (NeonSudokuConfig.MIN_DIGIT..NeonSudokuConfig.MAX_DIGIT).toList()
        digits.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit ->
                    DigitKey(
                        digit = digit,
                        exhausted = (counts[digit] ?: 0) >= NeonSudokuConfig.BOARD_SIZE,
                        onClick = { onInput(digit) },
                    )
                }
            }
        }
    }
}

/**
 * Tecla de dígito con la estética de tubo neón compartida (`drawNeonTile`, la
 * fuente única de bordes neón de la app — §9.7) y rebote al pulsar
 * (`bounceClick`, la interacción táctil por defecto — §9.4).
 *
 * @param exhausted el dígito ya está colocado nueve veces: se atenúa el tubo y
 *   el número, sin deshabilitar la tecla.
 */
@Composable
private fun DigitKey(digit: Int, exhausted: Boolean, onClick: () -> Unit) {
    // animateFloatAsState en vez de un valor seco: al colocar el noveno dígito la
    // tecla se apaga con una transición corta, no de golpe.
    val activeAmt by animateFloatAsState(
        targetValue = if (exhausted) KEY_EXHAUSTED_AMT else KEY_ACTIVE_AMT,
        animationSpec = tween(KEY_FADE_MS),
        label = "digitKeyActive",
    )
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(KeySize)
            .drawBehind {
                drawNeonTile(
                    baseColor = CategoryPalette.Logic,
                    activeAmt = activeAmt,
                    cornerRadius = 16.dp,
                    sparks = false,
                    baseMargin = 4.dp,
                    strokeScale = 0.7f,
                )
            }
            .clip(shape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = if (exhausted) LogicColors.OnDarkMuted else LogicColors.OnDark,
        )
    }
}

/**
 * Tecla de acción (lápiz / borrar): mismo tubo neón que [DigitKey] pero con
 * icono vectorial y etiqueta. El estado "encendido" del lápiz se comunica por
 * partida doble —tubo pleno + halo del icono ([NeonIcon] con `glow`)— para que
 * se lea de un vistazo si el siguiente número irá como nota o como valor.
 */
@Composable
private fun ActionKey(
    icon: ImageVector,
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val activeAmt by animateFloatAsState(
        targetValue = if (active) KEY_ON_AMT else KEY_IDLE_AMT,
        animationSpec = tween(KEY_FADE_MS),
        label = "actionKeyActive",
    )
    val tint = if (active) CategoryPalette.Logic else LogicColors.OnDarkMuted
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .drawBehind {
                drawNeonTile(
                    baseColor = CategoryPalette.Logic,
                    activeAmt = activeAmt,
                    cornerRadius = 16.dp,
                    sparks = false,
                    baseMargin = 4.dp,
                    strokeScale = 0.7f,
                )
            }
            .clip(shape)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(
            icon = icon,
            tint = tint,
            size = 20.dp,
            glow = active,
            contentDescription = contentDescription,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

// --- Constantes de render ---------------------------------------------------

/** Lado de una tecla de dígito. */
private val KeySize = 54.dp

/** Separación del selector de dificultad respecto al fondo de la antesala: deja
 *  hueco para el CTA "Comenzar" que `GameIntroScreen` ancla abajo (mismo valor
 *  que el gap del selector de tamaño de Neon Grid 2048). */
private val DIFFICULTY_SELECTOR_BOTTOM_GAP = 108.dp

/** Hueco que el HUD deja libre a su derecha para el botón de pausa (44 dp de
 *  botón + 16 dp de margen, según `GamePauseControls`). */
private val PauseButtonReserve = 60.dp

/** Duración de la sacudida de error (ms). Micro-feedback (§9.4: 100–250 ms). */
private const val SHAKE_MS = 240

/** Duración de la onda de luz de victoria (ms). Revelado (§9.4: 300–600 ms). */
private const val SWEEP_MS = 600

/**
 * Duración de la celebración de unidad completada (ms). Algo más larga que un
 * destello simple porque incluye el "reparto" escalonado de la onda
 * (`WAVE_STAGGER_SPAN`): el destello real de cada celda ocupa la fracción
 * restante. Mismo criterio y orden de magnitud que la limpieza de Tetris Neón.
 *
 * `LinearEasing` en el reloj a propósito: la curva de cada celda ya la da el
 * `sin(π·p)` del destello, y encadenar dos easings aplanaría la propagación.
 */
private const val WAVE_MS = 520

/** Duración del fundido entre estados de una tecla (ms). */
private const val KEY_FADE_MS = 220

/** Encendido del tubo de una tecla de dígito disponible. */
private const val KEY_ACTIVE_AMT = 0.85f

/** Encendido del tubo de un dígito ya colocado nueve veces (apagado). */
private const val KEY_EXHAUSTED_AMT = 0.2f

/** Encendido del tubo de una tecla de acción activa (lápiz on). */
private const val KEY_ON_AMT = 0.9f

/** Encendido del tubo de una tecla de acción en reposo. */
private const val KEY_IDLE_AMT = 0.3f
