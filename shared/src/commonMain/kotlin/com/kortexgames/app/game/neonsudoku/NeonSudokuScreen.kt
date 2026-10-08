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
import com.kortexgames.app.game.DifficultyUnlocks
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.ui.events.EventExitConfirmDialog
import com.kortexgames.app.ui.events.EventRulesPanel
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_intro_label
import kortexgames.shared.generated.resources.event_intro_board_unavailable
import org.jetbrains.compose.resources.stringResource
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.ui.components.AdLoadingOverlay
import com.kortexgames.app.ui.components.DifficultyGateSelector
import com.kortexgames.app.ui.components.DifficultyOption
import com.kortexgames.app.ui.components.FireworksOverlay
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.RankingPreviewUnavailable
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.ReviveAdOverlay
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.WorldRankingLoading
import com.kortexgames.app.ui.components.WorldRankingPreviewPanel
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.collectPressGlow
import com.kortexgames.app.ui.components.drawNeonTile
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kortexgames.app.ui.components.NeonProgressBar
import com.kortexgames.app.ui.components.rememberBoardClock
import kortexgames.shared.generated.resources.sudoku_hud_errors
import kortexgames.shared.generated.resources.sudoku_hud_time
import kortexgames.shared.generated.resources.sudoku_key_erase
import kortexgames.shared.generated.resources.sudoku_key_hint
import kortexgames.shared.generated.resources.sudoku_key_hint_desc
import kortexgames.shared.generated.resources.sudoku_key_notes
import kortexgames.shared.generated.resources.sudoku_key_notes_off
import kortexgames.shared.generated.resources.sudoku_key_notes_on

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
    // ¿Esta partida es de torneo? Se resuelve UNA vez al montar la pantalla: si la
    // sesión se cerrara a mitad de partida (no debería: se cierra al salir del
    // juego), la partida en curso debe seguir siendo la del torneo hasta terminar.
    val play = remember { graph.eventPlaySession.activeFor(GameIds.NEON_SUDOKU_MATRIX) }
    val event = play?.event
    val vm: NeonSudokuViewModel = viewModel(key = event?.id ?: VIEWMODEL_KEY_FREE_PLAY) {
        NeonSudokuViewModel(
            graph.progressRepository,
            graph.sudokuPuzzleRepository,
            graph.savedGameStateRepository,
            graph.audio,
            event = event,
            events = graph.eventsRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la partida en curso antes de navegar atrás (ver requestExit).
    // En modo torneo no guarda: pide confirmación, porque salir gasta el intento.
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

    // Aviso de abandono del torneo. Se monta antes que cualquier pantalla (y fuera
    // del `if` de la antesala) porque la salida se puede pedir desde la partida o
    // desde el menú de pausa, y el diálogo es modal en ambos casos.
    if (state.showEventExitConfirm) {
        EventExitConfirmDialog(
            // Intentos que quedarán tras gastar este. `null` si el torneo no los
            // limita: no hay nada escaso que advertir.
            // Cupo REAL (incluye los intentos extra ya comprados con anuncios), no
            // `event.attemptsLimit`: prometerle "te quedará 0" a quien acaba de ver
            // un anuncio para tener otro sería justo el engaño que este aviso evita.
            attemptsLeftAfter = play?.attemptsAllowed?.let { allowed ->
                (allowed - play.attemptsUsed - 1).coerceAtLeast(0)
            },
            onConfirm = { vm.confirmEventExit(onExit) },
            onDismiss = { vm.dismissEventExit() },
        )
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
            tutorial = NeonSudokuTutorial.tutorial,
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
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.NEON_SUDOKU_MATRIX)
                vm.onIntent(NeonSudokuIntent.Start)
            },
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
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Modo torneo: ni selector de dificultad ni comparativa mundial.
                    // La dificultad la fija el evento (cambiarla sería jugar otra
                    // cosa, y el backend rechazaría la marca) y lo que el jugador
                    // necesita leer antes de gastar un intento son las REGLAS del
                    // torneo, no su puesto mundial de siempre.
                    if (event != null) {
                        Text(
                            text = stringResource(Res.string.event_intro_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = LogicColors.Amber,
                        )
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = LogicColors.OnDark,
                        )
                        EventRulesPanel(event = event, accent = CategoryPalette.Logic)
                        if (state.eventBoardUnavailable) {
                            Text(
                                text = stringResource(Res.string.event_intro_board_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LogicColors.Error,
                            )
                        }
                        return@Column
                    }
                    DifficultyGateSelector(
                        title = "DIFICULTAD",
                        options = SUDOKU_DIFFICULTY_OPTIONS,
                        selectedIndex = state.difficulty.ordinal,
                        unlockedTiers = state.unlockedDifficulties,
                        onSelect = { index ->
                            vm.onIntent(NeonSudokuIntent.SelectDifficulty(SudokuDifficulty.entries[index]))
                        },
                        accent = CategoryPalette.Logic,
                        hint = DifficultyUnlocks.nextUnlockHint(
                            GameIds.NEON_SUDOKU_MATRIX,
                            state.unlockedDifficulties,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Comparativa mundial de la dificultad elegida, ANTES de jugar (mismo
                    // panel que el diálogo de fin de partida): pedido explícito para que
                    // la antesala también responda "¿cómo me va ahí?".
                    val preview = state.rankingPreview
                    when {
                        state.rankingPreviewLoading -> WorldRankingLoading()
                        preview != null -> WorldRankingPreviewPanel(ranking = preview)
                        else -> RankingPreviewUnavailable(difficultyLabel = state.difficulty.displayName)
                    }
                }
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    // Reloj único de las animaciones del tablero. Se congela fuera de RUNNING: en pausa o con
    // el resultado en pantalla no debe seguir latiendo nada detrás del diálogo.
    val boardClock = rememberBoardClock(running = state.status == GameStatus.RUNNING)

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
                    clock = boardClock,
                    onSelectCell = { row, col -> vm.onIntent(NeonSudokuIntent.SelectCell(row, col)) },
                )
            }

            Spacer(Modifier.height(18.dp))

            NeonSudokuNumpad(
                board = state.board,
                highlightedDigit = state.highlightedNumber,
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
                unlockedDifficultyLabel = state.justUnlockedDifficulty?.displayName,
                onPlayUnlockedDifficulty = state.justUnlockedDifficulty?.let { difficulty ->
                    { vm.onIntent(NeonSudokuIntent.PlayDifficulty(difficulty)) }
                },
                accent = CategoryPalette.Logic,
                // En torneo, volver a jugar se decide en la pantalla del torneo: es la
                // que sabe cuántos intentos quedan y la que ofrece el anuncio.
                singleBackCta = event != null,
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
            // En torneo salir NO guarda nada (y encima gasta el intento): prometer
            // lo contrario bajo el botón "SALIR" sería justo el engaño que este
            // aviso intenta evitar.
            exitKeepsProgress = event == null,
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

        // Feedback de "cargando anuncio" mientras se resuelve el rewarded de la
        // pista (ver el LaunchedEffect de `awaitingHint` más arriba): sin esto,
        // pulsar "Pista" no mostraba nada en pantalla durante la carga real del
        // anuncio (puede tardar varios segundos) y parecía que el botón no hacía nada.
        AdLoadingOverlay(visible = state.awaitingHint, accent = CategoryPalette.Logic)

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la corrida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(NeonSudokuIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = CategoryPalette.Logic,
            // En torneo la confirmación la pone el propio juego
            // (`EventExitConfirmDialog`): habla de perder el intento, que es lo que
            // de verdad está en juego, en vez de "¿salir del juego?".
            confirmsExternally = event != null,
        )
    }
}

// ---------------------------------------------------------------------------
// Selector de dificultad (antesala)
// ---------------------------------------------------------------------------

/**
 * Escalones del selector de dificultad, en el orden de [SudokuDifficulty]. Salen de recorrer
 * la propia enum —no son una copia—, así que añadir un nivel es un cambio ahí y no aquí.
 *
 * Sin líneas de detalle: el rótulo ("Experto") ya dice todo lo que hay que saber de un
 * Sudoku, cuyo tablero es siempre 9×9 (a diferencia de Neon Defuser, donde cada dificultad
 * cambia la geometría del panel y el chip sí necesita anunciarla).
 */
private val SUDOKU_DIFFICULTY_OPTIONS: List<DifficultyOption> =
    SudokuDifficulty.entries.map { DifficultyOption(label = it.displayName) }

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

/**
 * Cabecera: tiempo, progreso de relleno y errores. Son las tres métricas que el jugador
 * consulta de un vistazo sin dejar de mirar el tablero, así que van en una sola fila.
 *
 * Cada una usa la forma que mejor responde a su pregunta, en vez de tres pares "ETIQUETA/valor"
 * iguales: el **tiempo** es una cifra (píldora con icono), "¿cuánto me falta?" es una **barra**
 * que se llena (la misma de los demás tableros, [NeonProgressBar]) y "¿cuánto margen me queda?"
 * son **tres testigos** que se encienden en rojo — se cuentan sin leer.
 */
@Composable
private fun NeonSudokuHud(elapsedMs: Long, errorCount: Int, filled: Int) {
    val accent = CategoryPalette.Logic
    val progress by animateFloatAsState(
        targetValue = filled.toFloat() / NeonSudokuConfig.CELL_COUNT,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sudokuHudProgress",
    )
    Row(
        // Reserva la esquina superior derecha: ahí vive el botón de pausa que
        // pinta `GamePauseControls` sobre esta capa. Sin este hueco, la última
        // métrica queda debajo del botón (mismo motivo por el que Neon Pulse
        // agrupa su HUD a la izquierda y al centro, nunca en TopEnd).
        modifier = Modifier.fillMaxWidth().padding(end = PauseButtonReserve),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonIcon(
                icon = KortexIcons.Timer,
                tint = accent,
                size = 18.dp,
                glow = false,
                contentDescription = stringResource(Res.string.sudoku_hud_time),
            )
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = LogicColors.OnDark,
                // Ancho mínimo: sin él la píldora "respira" al cambiar de un dígito estrecho
                // (1) a uno ancho (0) cada segundo y empuja la barra de al lado.
                modifier = Modifier.widthIn(min = 42.dp),
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$filled/${NeonSudokuConfig.CELL_COUNT}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LogicColors.OnDark,
            )
            NeonProgressBar(progress = progress, color = accent, modifier = Modifier.fillMaxWidth())
        }

        val errorsLabel = stringResource(
            Res.string.sudoku_hud_errors,
            errorCount.toString(),
            NeonSudokuConfig.MAX_ERRORS.toString(),
        )
        Row(
            // Los tres testigos se anuncian como UNA métrica ("Errores: 1 de 3"), no como tres
            // iconos sueltos sin significado para un lector de pantalla.
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = errorsLabel },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(NeonSudokuConfig.MAX_ERRORS) { index -> ErrorPip(used = index < errorCount) }
        }
    }
}

/**
 * Un testigo de error: apagado mientras queda margen, rojo con halo cuando se ha gastado.
 *
 * El rojo solo aparece al fallar: en 0 errores no hay ninguna mancha roja permanente que el ojo
 * aprenda a ignorar (§9.1, el acento vale porque es escaso). Se enciende con un resorte con
 * rebote para que el fallo "golpee" el HUD además de sacudir la celda.
 */
@Composable
private fun ErrorPip(used: Boolean) {
    val amount by animateFloatAsState(
        targetValue = if (used) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "errorPip",
    )
    val lit = amount.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(26.dp)
            .graphicsLayer {
                // `amount` se pasa de 1 en el rebote: esa sobreoscilación es el "golpe".
                val scale = 1f + 0.22f * amount * (1f - lit) + 0.35f * (amount - lit)
                scaleX = scale
                scaleY = scale
            }
            .background(lerp(LogicColors.SurfaceDark, LogicColors.Error, 0.22f * lit).copy(alpha = 0.85f), CircleShape)
            .border(1.5.dp, lerp(LogicColors.SurfaceVariantDark, LogicColors.Error, lit), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.Close,
            tint = lerp(LogicColors.OnDarkMuted.copy(alpha = 0.35f), LogicColors.Error, lit),
            size = 14.dp,
            glow = used,
            // La descripción la da la fila completa (ver NeonSudokuHud).
            contentDescription = null,
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
 * Teclado inferior: dos acciones (lápiz y pista) y una rejilla 5x2 con los nueve dígitos y la
 * tecla de borrar.
 *
 * Borrar ocupa la décima casilla de la rejilla a propósito: nueve dígitos en dos filas dejaban
 * una fila de cinco y otra de cuatro descentrada, y borrar es —después de los dígitos— lo que
 * más se pulsa, así que gana estar bajo el pulgar. Las teclas se reparten el ancho (`weight`)
 * en vez de medir un lado fijo: salen más anchas y la rejilla cuadra con el tablero.
 *
 * Detalles de UX:
 *  - cada dígito lleva una **barrita de progreso** con cuántas de sus nueve apariciones están ya
 *    en el tablero, y se **atenúa** al agotarse. Sigue siendo pulsable (el jugador puede haberlo
 *    colocado mal y querer corregir), pero deja de reclamar atención;
 *  - la tecla del dígito de la celda seleccionada se **enciende**, igual que sus gemelos en el
 *    tablero: teclado y panel señalan el mismo número.
 *
 * @param board tablero actual; de él se derivan las nueve cuentas de dígitos.
 * @param highlightedDigit dígito de la celda seleccionada (ver
 *   [NeonSudokuUiState.highlightedNumber]), o `null`.
 * @param notesMode si el lápiz está activo (enciende su tecla).
 * @param hintAvailable si hay algo que revelar en la celda seleccionada (ver
 *   [NeonSudokuUiState.hintAvailable]); deshabilita la tecla de pista en vez de
 *   dejarla pulsable sin ningún efecto.
 */
@Composable
private fun NeonSudokuNumpad(
    board: Board,
    highlightedDigit: Int?,
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
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        // Acciones primero: quedan más lejos del pulgar que los dígitos, que son
        // lo que se pulsa constantemente.
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            ActionKey(
                icon = KortexIcons.Pencil,
                label = stringResource(Res.string.sudoku_key_notes),
                active = notesMode,
                contentDescription = stringResource(
                    if (notesMode) Res.string.sudoku_key_notes_on else Res.string.sudoku_key_notes_off,
                ),
                onClick = onToggleNotes,
            )
            ActionKey(
                icon = KortexIcons.Hint,
                label = stringResource(Res.string.sudoku_key_hint),
                active = false,
                enabled = hintAvailable,
                contentDescription = stringResource(Res.string.sudoku_key_hint_desc),
                onClick = onRequestHint,
            )
        }

        val digits = (NeonSudokuConfig.MIN_DIGIT..NeonSudokuConfig.MAX_DIGIT).toList()
        digits.chunked(KEYS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEach { digit ->
                    DigitKey(
                        digit = digit,
                        placed = counts[digit] ?: 0,
                        highlighted = digit == highlightedDigit,
                        onClick = { onInput(digit) },
                    )
                }
                // La fila corta (6–9) se completa con borrar.
                if (row.size < KEYS_PER_ROW) EraseKey(onClick = onErase)
            }
        }
    }
}

/**
 * Cuerpo de una tecla del teclado: un **capuchón macizo** (repisa inferior + cara con degradado
 * + brillo superior) rematado por el tubo de neón compartido (`drawNeonTile`, la fuente única de
 * bordes neón de la app — §9.7).
 *
 * Antes las teclas eran solo el tubo, huecas: sobre el fondo estrellado se leían como contornos
 * y no como botones. Con cuerpo y repisa tienen peso, y el neón queda como acento del borde.
 *
 * @param activeAmt encendido del tubo (0 apagado … 1 pleno).
 * @param pressGlow 0..1 mientras se pulsa: hunde la cara sobre la repisa y aviva el tubo.
 * @param dim 0..1: cuánto se apaga la cara (dígito agotado o acción deshabilitada).
 */
private fun Modifier.keycap(activeAmt: Float, pressGlow: Float, dim: Float = 0f): Modifier = drawBehind {
    val accent = CategoryPalette.Logic
    val margin = KeyMargin.toPx()
    val ledge = KeyLedge.toPx()
    val corner = CornerRadius(KeyCorner.toPx())
    val faceSize = Size(size.width - margin * 2f, size.height - margin * 2f - ledge)
    val sink = ledge * 0.7f * pressGlow

    // Repisa: la "altura" de la tecla. No se mueve; la cara baja sobre ella al pulsar.
    drawRoundRect(
        color = lerp(LogicColors.BackgroundDark, accent, 0.16f * (1f - dim)),
        topLeft = Offset(margin, margin + ledge),
        size = faceSize,
        cornerRadius = corner,
    )
    val faceTop = Offset(margin, margin + sink)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(LogicColors.SurfaceVariantDark, accent, (0.10f + 0.22f * activeAmt) * (1f - dim)),
                lerp(LogicColors.SurfaceDark, accent, 0.04f * (1f - dim)),
            ),
            startY = faceTop.y,
            endY = faceTop.y + faceSize.height,
        ),
        topLeft = faceTop,
        size = faceSize,
        cornerRadius = corner,
    )
    // Brillo superior: una línea de luz que da volumen a la cara.
    drawLine(
        color = LogicColors.OnDark.copy(alpha = 0.16f * (1f - 0.6f * dim)),
        start = Offset(margin + corner.x, faceTop.y + 1.5.dp.toPx()),
        end = Offset(size.width - margin - corner.x, faceTop.y + 1.5.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    drawNeonTile(
        baseColor = accent,
        activeAmt = activeAmt,
        pressAmt = pressGlow,
        cornerRadius = KeyCorner,
        sparks = false,
        baseMargin = 0.dp,
        strokeScale = 0.55f,
        rectTopLeft = faceTop,
        rectSize = faceSize,
    )
}

/**
 * Tecla de dígito: capuchón ([keycap]) con el número y su barrita de apariciones, y rebote al
 * pulsar (`bounceClick`, la interacción táctil por defecto — §9.4).
 *
 * @param placed cuántas veces está ya el dígito en el tablero (0..9). A nueve la tecla se atenúa
 *   sin deshabilitarse.
 * @param highlighted es el dígito de la celda seleccionada: enciende el tubo al máximo.
 */
@Composable
private fun RowScope.DigitKey(digit: Int, placed: Int, highlighted: Boolean, onClick: () -> Unit) {
    val exhausted = placed >= NeonSudokuConfig.BOARD_SIZE
    // animateFloatAsState en vez de un valor seco: al colocar el noveno dígito la
    // tecla se apaga con una transición corta, no de golpe.
    val activeAmt by animateFloatAsState(
        targetValue = when {
            exhausted -> KEY_EXHAUSTED_AMT
            highlighted -> KEY_ON_AMT
            else -> KEY_ACTIVE_AMT
        },
        animationSpec = tween(KEY_FADE_MS),
        label = "digitKeyActive",
    )
    val dim by animateFloatAsState(
        targetValue = if (exhausted) 1f else 0f,
        animationSpec = tween(KEY_FADE_MS),
        label = "digitKeyDim",
    )
    val fill by animateFloatAsState(
        targetValue = placed.coerceAtMost(NeonSudokuConfig.BOARD_SIZE).toFloat() / NeonSudokuConfig.BOARD_SIZE,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "digitKeyFill",
    )
    // Interaction source hoisteado: lo lee el propio capuchón (pressGlow) y bounceClick
    // (rebote de escala), para que ambos feedbacks respondan al mismo toque.
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    Box(
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight)
            .keycap(activeAmt = activeAmt, pressGlow = pressGlow, dim = dim)
            .drawBehind {
                // Barrita de apariciones, pegada al pie de la cara de la tecla.
                val barWidth = size.width * 0.42f
                val barHeight = 3.dp.toPx()
                val left = (size.width - barWidth) / 2f
                val top = size.height - KeyMargin.toPx() - KeyLedge.toPx() - 9.dp.toPx() +
                    KeyLedge.toPx() * 0.7f * pressGlow
                val corner = CornerRadius(barHeight / 2f)
                drawRoundRect(LogicColors.BackgroundDark.copy(alpha = 0.55f), Offset(left, top), Size(barWidth, barHeight), corner)
                if (fill > 0f) {
                    drawRoundRect(
                        color = lerp(CategoryPalette.Logic, LogicColors.OnDarkMuted, dim).copy(alpha = 0.9f),
                        topLeft = Offset(left, top),
                        size = Size(barWidth * fill.coerceIn(0f, 1f), barHeight),
                        cornerRadius = corner,
                    )
                }
            }
            .clip(RoundedCornerShape(KeyCorner))
            .bounceClick(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = lerp(LogicColors.OnDark, LogicColors.OnDarkMuted.copy(alpha = 0.6f), dim),
            // Sube el dígito para dejar sitio a la barrita y compensar la repisa.
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

/**
 * Tecla de borrar: mismo capuchón que un dígito, con icono vectorial (§9.5). Ocupa la décima
 * casilla de la rejilla (ver [NeonSudokuNumpad]). Tubo en reposo: es una acción de apoyo y no
 * debe brillar tanto como los dígitos disponibles.
 */
@Composable
private fun RowScope.EraseKey(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    Box(
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight)
            .keycap(activeAmt = KEY_IDLE_AMT, pressGlow = pressGlow)
            .clip(RoundedCornerShape(KeyCorner))
            .bounceClick(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.Backspace,
            tint = LogicColors.OnDark,
            size = 24.dp,
            glow = false,
            contentDescription = stringResource(Res.string.sudoku_key_erase),
            modifier = Modifier.padding(bottom = KeyLedge),
        )
    }
}

/**
 * Tecla de acción (lápiz / pista): mismo capuchón que [DigitKey] pero con icono vectorial y
 * etiqueta. El estado "encendido" del lápiz se comunica por partida doble —tubo pleno + halo
 * del icono ([NeonIcon] con `glow`)— para que se lea de un vistazo si el siguiente número irá
 * como nota o como valor.
 *
 * @param enabled si es `false` (p. ej. la pista sin celda válida seleccionada,
 *   ver [NeonSudokuUiState.hintAvailable]) la tecla se apaga y deja de reaccionar al toque, en
 *   vez de quedar pulsable sin ningún efecto — la app no debe ofrecer una acción que luego ignora.
 */
@Composable
private fun RowScope.ActionKey(
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
    val dim by animateFloatAsState(
        targetValue = if (enabled) 0f else 1f,
        animationSpec = tween(KEY_FADE_MS),
        label = "actionKeyDim",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    val tint = when {
        !enabled -> LogicColors.OnDarkMuted.copy(alpha = 0.4f)
        active -> CategoryPalette.Logic
        else -> LogicColors.OnDark
    }
    Row(
        modifier = Modifier
            .weight(1f)
            .height(ActionKeyHeight)
            .keycap(activeAmt = activeAmt, pressGlow = pressGlow, dim = dim)
            .clip(RoundedCornerShape(KeyCorner))
            .bounceClick(enabled = enabled, interactionSource = interaction, onClick = onClick)
            .padding(bottom = KeyLedge),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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

/** Alto de una tecla de la rejilla (dígitos y borrar); el ancho se reparte con `weight`. */
private val KeyHeight = 56.dp

/** Alto de una tecla de acción (lápiz / pista): algo más baja, se pulsa menos. */
private val ActionKeyHeight = 46.dp

/** Separación entre teclas, en ambos ejes. */
private val KeyGap = 8.dp

/** Teclas por fila de la rejilla: 9 dígitos + borrar = dos filas completas de cinco. */
private const val KEYS_PER_ROW = 5

/** Radio de esquina del capuchón de una tecla. */
private val KeyCorner = 14.dp

/** Aire entre el capuchón y el borde de su hueco (deja sitio al halo del tubo). */
private val KeyMargin = 2.dp

/** Altura de la repisa inferior del capuchón: lo que la cara se hunde al pulsar. */
private val KeyLedge = 4.dp

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
 * restante. Mismo criterio y orden de magnitud que la limpieza de Bloques Neón.
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

/** Encendido del tubo de una tecla de dígito disponible. A media luz: ahora la tecla tiene
 *  cuerpo propio, y el tubo pleno se reserva para el dígito de la celda seleccionada. */
private const val KEY_ACTIVE_AMT = 0.5f

/** Encendido del tubo de un dígito ya colocado nueve veces (apagado). */
private const val KEY_EXHAUSTED_AMT = 0.2f

/** Encendido del tubo de una tecla activa: lápiz on, o el dígito de la celda seleccionada. */
private const val KEY_ON_AMT = 0.9f

/** Encendido del tubo de una tecla de acción en reposo. */
private const val KEY_IDLE_AMT = 0.3f

/** Encendido del tubo de una tecla de acción deshabilitada (p. ej. "Pista" sin
 *  celda válida seleccionada): por debajo del reposo normal, para que se lea
 *  como apagada y no como una acción disponible más. */
private const val KEY_DISABLED_AMT = 0.12f

/**
 * Clave del ViewModel en partida libre. Se separa de la del torneo (`event.id`)
 * para que entrar a un torneo NO reutilice el ViewModel de la partida normal —y al
 * revés—: comparten pantalla, pero son dos partidas con reglas distintas y el
 * estado de una no tiene nada que hacer en la otra.
 */
private const val VIEWMODEL_KEY_FREE_PLAY = "libre"
