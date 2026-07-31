package com.kortexgames.app.game.neonsudoku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import kotlinx.coroutines.delay
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
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.FireworksOverlay
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.collectPressGlow
import com.kortexgames.app.ui.components.drawNeonTile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.key

/** Texto de la antesala; se reutiliza como ayuda dentro del menú de pausa. */
private const val NEON_SUDOKU_HELP =
    "Completa la matriz 9x9: cada fila, cada columna y cada bloque 3x3 deben " +
        "contener los dígitos del 1 al 9 sin repetirse. Toca una celda, elige un " +
        "número y activa el lápiz para anotar tus hipótesis. Si te atascas, " +
        "selecciona una celda y pulsa Pista para ver un anuncio y revelar su número."

/**
 * Celebración de "dígito agotado" en curso (ver [NeonSudokuEffect.DigitCompleted]).
 * [id] le da identidad propia para que el `LaunchedEffect` de auto-cierre no borre
 * una celebración más reciente si dos dígitos se agotan casi seguidos (mismo
 * patrón que `MergeCelebration` en Neon Grid 2048); [digit] no se usa hoy para
 * pintar nada (los fuegos son genéricos), pero queda disponible por si el diseño
 * quiere personalizarlos por dígito más adelante.
 */
private data class DigitFireworks(val id: Int, val digit: Int)

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
            graph.sudokuPuzzleRepository,
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
    // Fuegos artificiales de "dígito agotado": null = ninguno en curso. Vive en la
    // UI (no en el estado del juego) porque es adorno puntual con su propio ciclo
    // de vida, igual que la celebración de fusión grande de Neon Grid 2048.
    var digitFireworks by remember { mutableStateOf<DigitFireworks?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-cierre de los fuegos artificiales: se relanza en cada celebración nueva
    // (la key es su `id`) y solo se limpia a sí mismo si sigue siendo la MISMA
    // celebración al despertar — evita que una limpieza tardía borre una
    // celebración más reciente si dos dígitos se agotan muy seguidos.
    LaunchedEffect(digitFireworks?.id) {
        val active = digitFireworks ?: return@LaunchedEffect
        delay(DIGIT_FIREWORKS_MS)
        if (digitFireworks?.id == active.id) digitFireworks = null
    }

    // Traducción de efectos one-shot → audio/háptica/animación. Un único colector.
    LaunchedEffect(vm) {
        // Contadores locales (no `remember`): viven en esta corrutina mientras
        // dure la pantalla y solo dan a cada celebración una identidad propia.
        var waveSeed = 0
        var fireworksSeed = 0

        // Registra una onda y la anima hasta apagarla. Se anima en `scope` y no en
        // el colector: este es secuencial, y esperar dentro retrasaría los efectos
        // siguientes (el sonido de la jugada posterior llegaría tarde). Así, además,
        // varias ondas pueden solaparse — que es justo lo que pasa cuando una jugada
        // cierra una unidad y agota un dígito a la vez.
        fun launchCompletionWave(wave: CompletionWave) {
            completionWaves += wave
            scope.launch {
                wave.progress.animateTo(1f, tween(WAVE_MS, easing = LinearEasing))
                completionWaves.remove(wave)
            }
        }

        vm.effect.collect { effect ->
            when (effect) {
                is NeonSudokuEffect.PlaySound -> graph.audio.playSound(effect.sound)
                is NeonSudokuEffect.Vibrate -> graph.audio.hapticFeedback(effect.haptic)
                is NeonSudokuEffect.DigitCompleted -> {
                    fireworksSeed++
                    digitFireworks = DigitFireworks(id = fireworksSeed, digit = effect.digit)
                    // Además de los fuegos, la MISMA onda expansiva de una unidad
                    // completada, pero recorriendo las 9 apariciones del dígito: es
                    // lo que le dice al jugador QUÉ acaba de completar (los fuegos
                    // solo dicen "algo grande"). Comparte lista y mecánica con las
                    // ondas de unidad, así que las dos pueden solaparse si una misma
                    // jugada cierra fila y dígito a la vez.
                    waveSeed++
                    launchCompletionWave(
                        CompletionWave(
                            id = waveSeed,
                            cells = effect.cells,
                            origin = effect.origin,
                            progress = Animatable(0f),
                        ),
                    )
                }
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
                    launchCompletionWave(
                        CompletionWave(
                            id = waveSeed,
                            cells = effect.cells,
                            origin = effect.origin,
                            progress = Animatable(0f),
                        ),
                    )
                }
                NeonSudokuEffect.SweepVictory -> {
                    sweep.snapTo(0f)
                    sweep.animateTo(1f, tween(SWEEP_MS, easing = FastOutSlowInEasing))
                    sweep.snapTo(0f)
                }
            }
        }
    }

    // Pista: a diferencia de "revivir" (que ofrece un diálogo con cuenta atrás,
    // ver ReviveAdOverlay más abajo), pulsar "Pista" YA es la confirmación del
    // jugador — no tiene sentido preguntarle "¿ver anuncio?" otra vez. Por eso el
    // anuncio se lanza DIRECTO en cuanto el ViewModel marca `awaitingHint`, sin
    // ningún overlay de por medio. Se relanza cada vez que `awaitingHint` pasa de
    // `false` a `true` (la key); el resultado se traduce al intent que corresponda.
    LaunchedEffect(state.awaitingHint) {
        if (!state.awaitingHint) return@LaunchedEffect
        when (graph.adManager.showRewardedAd()) {
            RewardResult.EARNED -> vm.onIntent(NeonSudokuIntent.ConfirmHint)
            RewardResult.DISMISSED, RewardResult.UNAVAILABLE -> vm.onIntent(NeonSudokuIntent.CancelHint)
        }
    }

    // Antesala mientras el juego está en IDLE, igual que el resto de juegos.
    //
    // El selector de dificultad se pasa como `configContent` de `GameIntroScreen`:
    // se pinta DENTRO del propio bloque de acciones, justo encima del CTA
    // principal (no superpuesto por fuera con un padding fijo calculado a ojo,
    // que es como lo hacía la primera versión de esta pantalla —y como sigue
    // haciéndolo hoy Neon Grid 2048 con su selector de tamaño—). Ese padding fijo
    // se descuadraba en cuanto `resume` añadía su resumen + "Empezar de nuevo"
    // debajo del CTA: el bloque crecía, el CTA se desplazaba hacia arriba, y el
    // selector —anclado a una distancia fija del fondo de la pantalla, ajena a
    // ese crecimiento— terminaba solapándolo. `configContent` vive en el flujo
    // normal de la Column, así que crece y se encoge con el resto del bloque y
    // nunca puede desalinearse, sea cual sea la altura de lo que haya debajo.
    //
    // El selector queda SIEMPRE visible, haya o no partida guardada: `resume`
    // solo decide qué CTA es el principal, nunca oculta la posibilidad de elegir
    // dificultad y empezar de cero — antes, ocultarlo cuando había un guardado
    // dejaba al jugador sin ninguna vía para abandonarlo y arrancar una partida
    // nueva, que es el bug original que esto corrige.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            help = GameHelpContent.neonSudoku,
            title = "Neon Sudoku Matrix",
            motif = GameMotif.SUDOKU_GRID,
            description = NEON_SUDOKU_HELP,
            accent = CategoryPalette.Logic,
            // Sin arte "héroe" propio todavía: se cae al icono de la categoría
            // (§9.5, siempre vectorial) en vez de dejar el recuadro vacío.
            icon = GameCategory.LOGIC.icon,
            // Start SIEMPRE arranca partida nueva (ver KDoc del intent); es el
            // CTA principal cuando no hay guardado, y baja a "Empezar de nuevo"
            // (acción secundaria de GameIntroScreen) cuando sí lo hay.
            onStart = { vm.onIntent(NeonSudokuIntent.Start) },
            // Partida guardada al salir: la antesala la ofrece como CTA
            // principal (ResumeSaved), con su resumen para que el jugador sepa
            // qué retoma. Mismo mecanismo que `savedScore` en Neon Grid 2048.
            resume = state.savedSummary?.let { summary ->
                ResumeState(
                    onResume = { vm.onIntent(NeonSudokuIntent.ResumeSaved) },
                    detail = summary,
                )
            },
            configContent = {
                DifficultySelector(
                    selected = state.difficulty,
                    onSelect = { vm.onIntent(NeonSudokuIntent.SelectDifficulty(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
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
                hintAvailable = state.hintAvailable,
                onInput = { vm.onIntent(NeonSudokuIntent.InputNumber(it)) },
                onToggleNotes = { vm.onIntent(NeonSudokuIntent.ToggleNotesMode) },
                onErase = { vm.onIntent(NeonSudokuIntent.EraseCell) },
                onRequestHint = { vm.onIntent(NeonSudokuIntent.RequestHint) },
            )
        }

        // Fuegos artificiales de "dígito agotado": puramente visual, no bloquea la
        // interacción con el tablero (el jugador puede seguir jugando mientras
        // estallan). `key(id)` reinicia el patrón si dos dígitos se agotan casi
        // seguidos, en vez de continuar la secuencia de estallidos anterior.
        digitFireworks?.let { fireworks ->
            key(fireworks.id) {
                FireworksOverlay(
                    modifier = Modifier.fillMaxSize(),
                    // 3 estallidos (no los 6 por defecto): agotar un dígito es
                    // frecuente durante la partida —hay nueve—, así que una
                    // ráfaga más corta lo celebra sin saturar de fuegos una
                    // partida con varios dígitos agotados seguidos.
                    burstCount = DIGIT_FIREWORKS_BURSTS,
                    seed = fireworks.id,
                    onBurst = { graph.audio.playSound(SoundEffect.SUCCESS) },
                )
            }
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
            help = GameHelpContent.neonSudoku,
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
            // `bounceClick` (scale) va ANTES de clip/background/border —igual que
            // `AnimatedGameButton`—: si el scale queda detrás de esos modificadores
            // de dibujo en la cadena, su capa (graphicsLayer) los deja fuera y el
            // borde/fondo puede quedarse pintado con el valor viejo al cambiar
            // `selected` (visto en el emulador: el texto sí cambiaba de color pero
            // el borde no), aunque el propio texto sí se redibuje bien al no
            // depender de esa capa.
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
 * @param hintAvailable si hay algo que revelar en la celda seleccionada (ver
 *   [NeonSudokuUiState.hintAvailable]); deshabilita la tecla de pista en vez de
 *   dejarla pulsable sin ningún efecto.
 */
@Composable
private fun NeonSudokuNumpad(
    board: Board,
    notesMode: Boolean,
    hintAvailable: Boolean,
    onInput: (Int) -> Unit,
    onToggleNotes: () -> Unit,
    onErase: () -> Unit,
    onRequestHint: () -> Unit,
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
            ActionKey(
                icon = KortexIcons.Hint,
                label = "Pista",
                active = false,
                enabled = hintAvailable,
                contentDescription = "Ver un anuncio y revelar el número correcto de la celda seleccionada",
                onClick = onRequestHint,
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
    // Interaction source hoisteado: lo lee el propio tile (pressGlow, brillo del
    // tubo) y bounceClick (rebote de escala), para que ambos feedbacks respondan
    // al mismo toque.
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(KeySize)
            .drawBehind {
                drawNeonTile(
                    baseColor = CategoryPalette.Logic,
                    activeAmt = activeAmt,
                    pressAmt = pressGlow,
                    cornerRadius = 16.dp,
                    sparks = false,
                    baseMargin = 4.dp,
                    strokeScale = 0.7f,
                )
            }
            .clip(shape)
            .bounceClick(interactionSource = interaction, onClick = onClick),
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
 * Tecla de acción (lápiz / borrar / pista): mismo tubo neón que [DigitKey] pero
 * con icono vectorial y etiqueta. El estado "encendido" del lápiz se comunica
 * por partida doble —tubo pleno + halo del icono ([NeonIcon] con `glow`)— para
 * que se lea de un vistazo si el siguiente número irá como nota o como valor.
 *
 * @param enabled si es `false` (p. ej. la pista sin celda válida seleccionada,
 *   ver [NeonSudokuUiState.hintAvailable]) el tubo se atenúa por debajo del
 *   reposo normal y deja de reaccionar al toque, en vez de quedar pulsable sin
 *   ningún efecto — la app no debe ofrecer una acción que luego ignora.
 */
@Composable
private fun ActionKey(
    icon: ImageVector,
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val activeAmt by animateFloatAsState(
        targetValue = when {
            !enabled -> KEY_DISABLED_AMT
            active -> KEY_ON_AMT
            else -> KEY_IDLE_AMT
        },
        animationSpec = tween(KEY_FADE_MS),
        label = "actionKeyActive",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    val tint = when {
        !enabled -> LogicColors.OnDarkMuted.copy(alpha = 0.4f)
        active -> CategoryPalette.Logic
        else -> LogicColors.OnDarkMuted
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .drawBehind {
                drawNeonTile(
                    baseColor = CategoryPalette.Logic,
                    activeAmt = activeAmt,
                    pressAmt = pressGlow,
                    cornerRadius = 16.dp,
                    sparks = false,
                    baseMargin = 4.dp,
                    strokeScale = 0.7f,
                )
            }
            .clip(shape)
            .bounceClick(enabled = enabled, interactionSource = interaction, onClick = onClick)
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

/** Estallidos de la celebración de "dígito agotado" (menos que los 6 por
 *  defecto de [FireworksOverlay]: hay nueve dígitos por partida, así que una
 *  ráfaga más corta celebra sin saturar si se agotan varios seguidos). */
private const val DIGIT_FIREWORKS_BURSTS = 2

/**
 * Tiempo de vida de la celebración de "dígito agotado" (ms) antes de desmontar
 * [FireworksOverlay]. [FireworksOverlay] no avisa cuando termina de estallar —
 * simplemente deja de dibujar—, así que quien lo monta decide cuándo retirarlo
 * (mismo patrón que `MERGE_CELEBRATION_MS` en Neon Grid 2048). El valor cubre el
 * peor caso para [DIGIT_FIREWORKS_BURSTS] estallidos (~300 ms de cadencia con
 * jitter + ~1050 ms de vida del último) con margen.
 */
private const val DIGIT_FIREWORKS_MS = 1900L

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

/** Encendido del tubo de una tecla de acción deshabilitada (p. ej. "Pista" sin
 *  celda válida seleccionada): por debajo del reposo normal, para que se lea
 *  como apagada y no como una acción disponible más. */
private const val KEY_DISABLED_AMT = 0.12f
