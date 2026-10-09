package com.kortexgames.app.game.crucigrama

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameMotif
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.ui.components.AdLoadingOverlay
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.GameExitGuard
import com.kortexgames.app.ui.components.GameIntroScreen
import com.kortexgames.app.game.GameHelpContent
import com.kortexgames.app.ui.components.GameOverOverlay
import com.kortexgames.app.ui.components.GamePauseControls
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LevelStripState
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.UPCOMING_LEVEL_TEASERS
import com.kortexgames.app.ui.components.ResumeState
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.collectPressGlow
import com.kortexgames.app.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gameintro_levels_cleared_notice
import androidx.compose.foundation.Canvas
import com.kortexgames.app.ui.components.drawEdgeFlash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import com.kortexgames.app.ui.components.NeonProgressBar
import kortexgames.shared.generated.resources.crucigrama_hud_combo
import kortexgames.shared.generated.resources.crucigrama_hud_words
import kortexgames.shared.generated.resources.gameboard_hud_level
import kotlinx.coroutines.delay
import kotlin.math.sin

// +10% respecto al tamaño original (60dp) para compensar el hueco entre celdas
// más ajustado sin que el tubo de neón se vea apretado.
private val CellSize = 66.dp
private val BankLetterSize = 60.dp
// Separación horizontal entre teclas del banco. Constante compartida por la fila
// y por el cálculo adaptativo de tamaño para que ambos midan lo mismo.
private val BankLetterGap = 12.dp
// Tamaño mínimo al que puede encoger una tecla cuando la fila más llena (hasta 6
// letras) no cabe en el ancho disponible. Por debajo la letra se volvería ilegible;
// preferimos ese piso a seguir achicando.
private val BankLetterMinSize = 40.dp

/** Retraso de la entrada en cascada entre una diagonal de la rejilla y la siguiente (ms). */
private const val ENTRY_STEP_MS = 28L

/** Retraso del encendido entre una letra de la palabra y la siguiente (ms). */
private const val IGNITION_STEP_MS = 55L

/** Capas del resplandor interior del cristal de una celda (se suman hacia el borde). */
private const val GLASS_GLOW_LAYERS = 6

/**
 * Colores neón asignados por palabra. Cada slot recibe un color estable según su
 * orden, de modo que las palabras entrelazadas se distingan visualmente en los
 * cruces: sin esto, leer una fila/columna cruzada produce "palabras" falsas como
 * `AMORA`. El set es un subconjunto de la paleta neón (§9.2) con tonos bien
 * separados en el círculo cromático.
 */
private val WordColors = listOf(
    LogicColors.NeonGreen,
    LogicColors.NeonCyan,
    LogicColors.Violet,
    LogicColors.Coral,
    LogicColors.Blue,
    LogicColors.Amber,
)

/**
 * Pantalla del Crucigrama Neón.
 *
 * UX (pedidos del usuario):
 *  - teclado de letras **centrado** con estética de tecla de juego,
 *  - **Pista** en la esquina superior derecha (no en una barra inferior),
 *  - **sin** "Reiniciar" ni "Deshacer": la corrección se hace con la tecla de
 *    **Borrar** integrada en el teclado, y la escritura es libre (nunca se bloquea),
 *  - cada palabra se ilumina con **su color** para separar los cruces,
 *  - encendido tipo **cartel de neón** (parpadeo → prende) con chispas al resolver.
 */
@Composable
fun CrucigramaNeonScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: CrucigramaNeonViewModel = viewModel {
        CrucigramaNeonViewModel(
            graph.progressRepository,
            graph.playerProgressRepository,
            graph.savedGameStateRepository,
            graph.audio,
            graph.adManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Único punto de salida "en juego" (back del sistema y "SALIR" del menú de
    // pausa): guarda la partida en curso antes de navegar atrás.
    val exitWithSave: () -> Unit = { vm.requestExit(onExit) }

    if (state.phase == LeveledGamePhase.LEVEL_SELECT) {
        // Catálogo finito: la selección nunca apunta más allá del último nivel (si
        // ya se superaron todos, "Empezar" rejuega el último — no hay "siguiente").
        var selectedLevel by remember(state.maxUnlocked) {
            mutableStateOf((state.maxUnlocked + 1).coerceAtMost(CrucigramaNeonGenerator.levelCount))
        }
        GameIntroScreen(
            help = GameHelpContent.crucigrama,
            title = "Crucigrama Neón",
            description = "Escribe palabras con el teclado inferior. Si una palabra es correcta, se coloca sola en su lugar dentro del crucigrama.",
            accent = CategoryPalette.Language,
            levels = LevelStripState(
                maxUnlocked = state.maxUnlocked,
                selected = selectedLevel,
                onSelect = { selectedLevel = it },
                playableLevels = CrucigramaNeonGenerator.levelCount,
                // Un par de casillas más, con candado y "Pronto", para adelantar que
                // habrá más niveles: así el carril no se corta en seco en el último.
                maxLevel = CrucigramaNeonGenerator.levelCount + UPCOMING_LEVEL_TEASERS,
            ),
            completionNotice = if (state.allLevelsCompleted) {
                stringResource(Res.string.gameintro_levels_cleared_notice)
            } else {
                null
            },
            motif = GameMotif.CROSSWORD,
            startLabel = "Empezar",
            onStart = {
                // Cuenta para la misión diaria en cuanto se juega, no hace falta terminar
                // la partida (ver DailyGoalManager.markPlayed).
                graph.dailyGoalManager.markPlayed(GameIds.CRUCIGRAMA_NEON)
                vm.onIntent(CrucigramaNeonIntent.PlayLevel(selectedLevel))
            },
            // Partida a medias guardada al salir: la antesala la ofrece como CTA
            // principal, con su nivel para que el jugador sepa qué retoma.
            resume = state.savedLevel?.let { level ->
                ResumeState(
                    onResume = { vm.onIntent(CrucigramaNeonIntent.ResumeSaved) },
                    detail = "Nivel $level en curso",
                )
            },
            onExit = onExit,
            background = { SpaceBackdrop(modifier = Modifier.fillMaxSize()) },
        )
        return
    }

    LifecycleResumeEffect(Unit) {
        vm.onIntent(CrucigramaNeonIntent.Resume)
        onPauseOrDispose { vm.onIntent(CrucigramaNeonIntent.Pause) }
    }

    // Pista: pulsar "Pista" YA es la confirmación del jugador (mismo criterio que
    // Neon Sudoku/Desactivador) — no tiene sentido preguntarle "¿ver anuncio?" otra
    // vez con un overlay de oferta. El anuncio se lanza DIRECTO al AdManager en
    // cuanto se pide, sin ningún diálogo intermedio.
    var awaitingHintAd by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingHintAd) {
        if (!awaitingHintAd) return@LaunchedEffect
        if (graph.adManager.showRewardedAd() == RewardResult.EARNED) {
            vm.onIntent(CrucigramaNeonIntent.HintAdWatched)
        }
        awaitingHintAd = false
    }

    Box(modifier = Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            CrosswordHud(
                level = game.level,
                score = game.score,
                solved = game.correctWords,
                total = game.slots.size,
                combo = game.combo,
            )

            // Cartel de extras: bajo el indicador de nivel/puntos, anclado a la izquierda.
            ExtrasPanel(
                words = game.extraWords,
                found = game.extraFound,
                foundTick = game.extraTick,
                modifier = Modifier.padding(top = 8.dp),
            )

            // La rejilla ocupa el espacio libre y queda centrada; así el elemento de
            // escritura y el teclado bajan hacia la zona cómoda del pulgar.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // `key(nivel)`: al cambiar de nivel la rejilla se monta de cero y repite su
                // entrada en cascada, en vez de reutilizar las celdas del nivel anterior.
                key(game.level) {
                    CrosswordGrid(
                        rows = game.rows,
                        cols = game.cols,
                        cells = game.cells,
                        slots = game.slots,
                    )
                }
            }

            // Palabra en curso con sus dos acciones al lado: papelera (borrar todo) y
            // retroceso (borrar la última letra).
            CurrentWord(
                input = game.inputBuffer,
                hint = state.revealedHint,
                feedbackTick = game.feedbackTick,
                wrong = game.lastOutcome == CrucigramaNeonOutcome.WRONG,
                onBackspace = { vm.onIntent(CrucigramaNeonIntent.Backspace) },
                onClearAll = { vm.onIntent(CrucigramaNeonIntent.ClearWord) },
            )

            Spacer(Modifier.height(14.dp))

            LetterBank(
                letters = game.letters,
                accent = CategoryPalette.Language,
                onTapLetter = { vm.onIntent(CrucigramaNeonIntent.TapLetter(it)) },
            )

            Spacer(Modifier.height(12.dp))

            // Pista debajo del teclado (pedido del usuario), centrada.
            HintButton(
                onClick = { awaitingHintAd = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        FeedbackFlash(eventId = game.feedbackTick, result = game.lastOutcome)

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                headline = "¡Nivel ${state.currentLevel} completado!",
                onPlayAgain = { vm.onIntent(CrucigramaNeonIntent.PlayAgain) },
                onExit = onExit,
                onNextLevel = { vm.onIntent(CrucigramaNeonIntent.NextLevel) },
                onChooseLevel = { vm.onIntent(CrucigramaNeonIntent.ChooseLevel) },
                // Catálogo finito: si esta era la última, "Siguiente nivel" pasa a
                // "Ver niveles" y lleva a la antesala con el cartel de completado.
                hasNextLevel = state.currentLevel < CrucigramaNeonGenerator.levelCount,
                accent = CategoryPalette.Language,
            )
        }

        // Rejilla resuelta pero quedan extras por encontrar: en vez de cerrar la
        // partida sola (antes se llamaba a finish() en cuanto se resolvía la última
        // pista, ver CrucigramaNeonEngine.onCorrect), se ofrece seguir jugando. Solo
        // mientras la partida sigue viva (RUNNING) y el jugador no lo descartó ya.
        if (game.gridComplete && state.status == GameStatus.RUNNING && !state.extrasPromptDismissed) {
            ExtrasPromptDialog(
                remaining = game.extraWords.size - game.extraFound.size,
                onKeepSearching = { vm.onIntent(CrucigramaNeonIntent.KeepSearchingExtras) },
                onFinish = { vm.onIntent(CrucigramaNeonIntent.FinishLevel) },
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(CrucigramaNeonIntent.Pause) },
            onResume = { vm.onIntent(CrucigramaNeonIntent.Resume) },
            onExit = exitWithSave,
            gameTitle = "Crucigrama Neón",
            help = GameHelpContent.crucigrama,
            accent = CategoryPalette.Language,
            exitKeepsProgress = true,
            // Rejilla ya completa con extras pendientes (mismo disparador que el
            // cartel de arriba): ofrece el atajo de avanzar directo también si el
            // jugador pausó en vez de responderle al cartel.
            onAdvanceLevel = if (game.gridComplete) {
                { vm.onIntent(CrucigramaNeonIntent.SkipToNextLevel) }
            } else {
                null
            },
        )

        // Feedback de "cargando anuncio" mientras se resuelve el rewarded de la
        // pista (ver el LaunchedEffect de `awaitingHintAd` más arriba): sin esto,
        // pulsar "Pista" no mostraba nada en pantalla durante la carga real del
        // anuncio (puede tardar varios segundos) y parecía que el botón no hacía nada.
        AdLoadingOverlay(visible = awaitingHintAd, accent = CategoryPalette.Language)

        // Atrás del sistema: reanuda si estaba en pausa, o pregunta antes de salir
        // mientras se juega (la partida se guarda al confirmar, ver exitWithSave).
        GameExitGuard(
            status = state.status,
            onResume = { vm.onIntent(CrucigramaNeonIntent.Resume) },
            onConfirmExit = exitWithSave,
            accent = CategoryPalette.Language,
        )
    }
}

/**
 * Cabecera: nivel, avance del crucigrama y puntuación con su combo.
 *
 * Mismo esqueleto que el HUD de los demás tableros por niveles (píldora de nivel + barra): la
 * barra responde a "¿cuántas palabras me faltan?" de un vistazo, que antes era un "3/7" suelto
 * en la esquina —justo debajo del botón de pausa—. La puntuación late al sumar y el combo es
 * una insignia que entra con rebote y late en cada acierto encadenado.
 *
 * Deja libre la esquina superior derecha (el botón de pausa vive ahí).
 */
@Composable
private fun CrosswordHud(
    level: Int,
    score: Int,
    solved: Int,
    total: Int,
    combo: Int,
) {
    val accent = CategoryPalette.Language
    val progress by animateFloatAsState(
        targetValue = if (total <= 0) 0f else solved.toFloat() / total,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "crosswordProgress",
    )
    val scorePop = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(score) }
    LaunchedEffect(score) {
        val grew = score > lastScore
        lastScore = score
        if (!grew) return@LaunchedEffect
        scorePop.snapTo(1.25f)
        scorePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val comboVisible by animateFloatAsState(
        targetValue = if (combo >= 2) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "comboVisible",
    )
    val comboBeat = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        if (combo < 2) return@LaunchedEffect
        comboBeat.snapTo(1.3f)
        comboBeat.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.gameboard_hud_level, level.toString()).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(LogicColors.SurfaceDark.copy(alpha = 0.85f), CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.crucigrama_hud_words, solved.toString(), total.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Bold,
            )
            NeonProgressBar(progress = progress, color = accent, modifier = Modifier.fillMaxWidth())
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    scaleX = scorePop.value
                    scaleY = scorePop.value
                    // Crece desde la derecha: el número está alineado a ese lado.
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            )
            Text(
                // Sigue mostrando la última racha mientras la insignia se encoge al romperse.
                text = stringResource(Res.string.crucigrama_hud_combo, combo.coerceAtLeast(2).toString()),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.NeonGreen,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    val scale = comboVisible * comboBeat.value
                    scaleX = scale
                    scaleY = scale
                    alpha = comboVisible.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            )
        }
    }
}

/** Píldora neón de "Pista". Ahora vive bajo el teclado (pedido del usuario). */
@Composable
private fun HintButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(LogicColors.SurfaceDark.copy(alpha = 0.9f))
            .border(BorderStroke(1.dp, CategoryPalette.Language.copy(alpha = 0.6f)), shape)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NeonIcon(icon = KortexIcons.Help, tint = CategoryPalette.Language, size = 18.dp, glow = true)
        Text("Pista", style = MaterialTheme.typography.labelLarge, color = CategoryPalette.Language, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Palabra que se está escribiendo, **flanqueada por sus dos acciones**: a la izquierda
 * la papelera (borrar todo, [onClearAll]) y a la derecha el retroceso (borrar la última
 * letra, [onBackspace]). El texto va al centro (peso 1) para quedar ópticamente centrado
 * entre botones simétricos. Ambos botones se atenúan cuando no hay nada que borrar.
 *
 * La palabra **responde a lo que se escribe**: da un pequeño golpe con cada letra nueva y, si el
 * intento no era una palabra del nivel, se sacude y parpadea en rojo. Antes el fallo solo se
 * notaba por un velo de color en toda la pantalla, lejos de donde el jugador está mirando.
 *
 * @param feedbackTick contador de intentos resueltos; cada cambio dispara una reacción.
 * @param wrong si el último intento fue fallido (decide entre sacudida y nada).
 */
@Composable
private fun CurrentWord(
    input: String,
    hint: String?,
    feedbackTick: Long,
    wrong: Boolean,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
) {
    val hasInput = input.isNotEmpty()
    // Golpe al teclear: solo al CRECER la palabra (borrar no es un logro).
    val typePop = remember { Animatable(1f) }
    var lastLength by remember { mutableIntStateOf(input.length) }
    LaunchedEffect(input.length) {
        val grew = input.length > lastLength
        lastLength = input.length
        if (!grew) return@LaunchedEffect
        typePop.snapTo(1.12f)
        typePop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    // Sacudida del fallo, 0→1 una vez por intento fallido.
    val shake = remember { Animatable(1f) }
    LaunchedEffect(feedbackTick) {
        if (feedbackTick == 0L || !wrong) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(1f, tween(360, easing = LinearEasing))
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Papelera: borra toda la palabra en curso de una vez.
            WordActionButton(
                icon = KortexIcons.Trash,
                tint = LogicColors.Coral,
                enabled = hasInput,
                onClick = onClearAll,
                contentDescription = "Borrar todo",
            )
            Text(
                text = if (input.isBlank()) "_" else input.toCharArray().joinToString(" "),
                // displaySmall (~36sp) escalado ~25% -> 45sp para agrandar las letras. A partir de
                // la sexta letra el cuerpo encoge para que la palabra siga en UNA línea: al
                // partirse en dos empujaba la rejilla hacia arriba a mitad de escritura.
                fontSize = (45f * (5.2f / input.length.coerceAtLeast(1)).coerceAtMost(1f)).sp,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.displaySmall,
                color = when {
                    shake.value < 1f -> lerp(LogicColors.Error, LogicColors.OnDarkMuted, shake.value)
                    input.isBlank() -> LogicColors.OnDarkMuted
                    else -> LogicColors.OnDark
                },
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).graphicsLayer {
                    scaleX = typePop.value
                    scaleY = typePop.value
                    val p = shake.value
                    // Sinusoide amortiguada: se frena sola en su sitio (como en Neon Sudoku).
                    if (p < 1f) translationX = sin(p * 3f * 2f * PI.toFloat()) * 9.dp.toPx() * (1f - p)
                },
            )
            // Retroceso: borra solo la última letra.
            WordActionButton(
                icon = KortexIcons.Backspace,
                tint = LogicColors.NeonCyan,
                enabled = hasInput,
                onClick = onBackspace,
                contentDescription = "Borrar",
            )
        }
        Text(
            text = hint ?: "Palabra actual",
            style = MaterialTheme.typography.labelLarge,
            color = CategoryPalette.Language,
        )
    }
}

/**
 * Botón circular de acción de escritura (retroceso / papelera) con la estética de tubo
 * neón de la app. Se atenúa y se vuelve inerte cuando [enabled] es false, para señalar
 * que no hay nada que borrar sin sacarlo del layout (evita saltos).
 */
@Composable
private fun WordActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val shape = RoundedCornerShape(14.dp)
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(180),
        label = "actionAlpha",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(alpha)
            .drawBehind {
                drawNeonTile(tint, activeAmt = 0.7f, pressAmt = pressGlow, cornerRadius = 14.dp, sparks = false, baseMargin = 5.dp)
            }
            .clip(shape)
            .bounceClick(enabled = enabled, interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 22.dp, glow = false, contentDescription = contentDescription)
    }
}

@Composable
private fun CrosswordGrid(
    rows: Int,
    cols: Int,
    cells: List<CrucigramaNeonCellState>,
    slots: List<CrucigramaNeonSlotState>,
) {
    val byCoord = remember(cells) { cells.associateBy { it.row to it.col } }

    // Color y momento de encendido por celda. El color es el de la palabra que la
    // resolvió; en un cruce (celda compartida por 2 palabras) gana la resuelta más
    // tarde, para que el último encendido "reclame" la intersección.
    val slotColor = remember(slots) {
        slots.mapIndexed { i, slot -> slot.number to WordColors[i % WordColors.size] }.toMap()
    }
    val cellVisual = remember(cells, slots) {
        val solved = slots.filter { it.solved }
        cells.associate { cell ->
            val owner = solved
                .filter { it.number in cell.slotNumbers }
                .maxByOrNull { it.solvedAtTick ?: 0L }
            cell.index to (slotColor[owner?.number] to owner?.solvedAtTick)
        }
    }
    // Puesto de cada celda dentro de la palabra que la enciende (0 = su primera letra). Con él
    // el encendido recorre la palabra letra a letra, como un rótulo que se va prendiendo, en
    // vez de titilar todas las celdas a la vez.
    val cellOrder = remember(cells, slots) {
        val solved = slots.filter { it.solved }
        cells.associate { cell ->
            val owner = solved
                .filter { it.number in cell.slotNumbers }
                .maxByOrNull { it.solvedAtTick ?: 0L }
            val start = if (owner == null) 0 else {
                cells.filter { owner.number in it.slotNumbers }.minOf { it.row + it.col }
            }
            cell.index to (cell.row + cell.col - start).coerceAtLeast(0)
        }
    }

    // Tamaño de celda adaptativo: crece hasta [CellSize] pero se encoge para que la
    // rejilla completa quepa (los niveles avanzados tienen más columnas/filas).
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Hueco entre celdas un 10% más ajustado (4 -> 3.6dp) para que las letras
        // de una misma palabra se lean más "juntas" en la rejilla.
        val gap = 3.6.dp
        val byWidth = (maxWidth - gap * (cols - 1)) / cols
        val byHeight = if (maxHeight.value.isFinite()) (maxHeight - gap * (rows - 1)) / rows else CellSize
        val cell = minOf(CellSize, byWidth, byHeight)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(cols) { col ->
                        val cellState = byCoord[row to col]
                        if (cellState == null) {
                            Spacer(Modifier.size(cell))
                        } else {
                            val (wordColor, tick) = cellVisual[cellState.index] ?: (null to null)
                            GridCell(
                                cell = cellState,
                                wordColor = wordColor ?: CategoryPalette.Language,
                                solvedTick = tick,
                                order = cellOrder[cellState.index] ?: 0,
                                cellSize = cell,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Celda del crucigrama con estética de juego y encendido de neón real.
 *
 * Vacía es un **zócalo** oscuro con un filo tenue. Al resolverse la palabra que la contiene se
 * enciende como un tubo de neón: una secuencia de [ignition] con **parpadeos irregulares** que
 * "engancha" y queda encendida, una **respiración** sutil continua ([breath]) y una ráfaga de
 * **chispas** ([spark]). La luz del tubo se derrama sobre el cristal de la celda ([drawCellGlass])
 * y la letra entra con un rebote y un halo del color de su palabra.
 *
 * @param order puesto de la celda dentro de la palabra que la enciende: retrasa su encendido
 *   para que la palabra se prenda letra a letra (ver [IGNITION_STEP_MS]).
 */
@Composable
private fun GridCell(
    cell: CrucigramaNeonCellState,
    wordColor: Color,
    solvedTick: Long?,
    order: Int,
    cellSize: androidx.compose.ui.unit.Dp,
) {
    val solved = cell.fixed
    val shape = RoundedCornerShape(12.dp)

    // Entrada del nivel: las celdas aparecen en cascada diagonal, una sola vez al montarse.
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((cell.row + cell.col) * ENTRY_STEP_MS)
        entry.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }

    val ignition = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }
    // Rebote de la letra al encenderse su celda (1 = asentada).
    val letterPop = remember { Animatable(if (solved) 1f else 0f) }
    LaunchedEffect(solvedTick) {
        if (solvedTick == null || solvedTick == 0L) {
            ignition.snapTo(if (solved) 1f else 0f)
            spark.snapTo(0f)
            // Solo las ya resueltas muestran su letra asentada: si las vacías quedaran en 1, la
            // letra asomaría un frame entero antes de que arranque su encendido.
            letterPop.snapTo(if (solved) 1f else 0f)
            return@LaunchedEffect
        }
        ignition.snapTo(0f)
        letterPop.snapTo(0f)
        // La palabra se prende letra a letra.
        delay(order * IGNITION_STEP_MS)
        // Chispas y rebote de la letra, en paralelo al encendido.
        launch {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(560, easing = LinearEasing))
        }
        launch {
            letterPop.snapTo(0.4f)
            letterPop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        // Parpadeo tipo tubo de neón que titila y "engancha".
        ignition.animateTo(0.85f, tween(55))
        ignition.animateTo(0.08f, tween(45))
        ignition.animateTo(0.7f, tween(35))
        ignition.animateTo(0.05f, tween(60))
        ignition.animateTo(1f, tween(150))
    }

    // Respiración lenta y de baja amplitud una vez encendida (§9.4).
    val breath by rememberInfiniteTransition(label = "cellBreath").animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "cellBreathValue",
    )
    // "Encendido" del tubo neón: 0 = apagado (celda vacía en espera), 1 = pleno.
    val activeAmt = if (solved) (ignition.value * (0.1f + 0.3f * breath)).coerceIn(0f, 1f) else 0f
    // Base neutra para las celdas vacías (tubo tenue "en espera"); color de la palabra
    // al resolverse.
    val tileColor = if (solved) wordColor else LogicColors.SurfaceVariantDark

    Box(
        modifier = Modifier
            .size(cellSize)
            .graphicsLayer {
                scaleX = entry.value
                scaleY = entry.value
                alpha = entry.value.coerceIn(0f, 1f)
            }
            .drawBehind {
                // Cristal de la celda bajo el tubo: zócalo oscuro, teñido por la luz al encender.
                drawCellGlass(
                    margin = 5.6.dp.toPx(),
                    corner = 12.dp.toPx(),
                    color = wordColor,
                    lit = if (solved) ignition.value * breath else 0f,
                )
                // Borde neón tipo tubo (misma estética que Memoria vía [drawNeonTile]).
                // baseMargin reducido ~20% (7 -> 5.6dp) para que el tubo llene más la celda.
                // strokeScale 0.6 = tubo un 40% más fino: en niveles con muchas palabras las
                // celdas se encogen y un borde grueso tapaba la letra (pedido del usuario).
                drawNeonTile(tileColor, activeAmt, cornerRadius = 12.dp, sparks = false, baseMargin = 5.6.dp, strokeScale = 0.6f)

                // Chispas propias del crucigrama: partículas radiales que salen del
                // centro y se apagan al encender la palabra.
                //
                // NO delega en `drawNeonSparks` (la ráfaga compartida de §9.7) a
                // propósito: aquel efecto son esquirlas *con estela* lanzadas a
                // ángulos aleatorios y con frenada ease-out; este es un anillo de
                // puntos equiespaciados que se expande a velocidad constante y
                // encoge. Difieren en forma, reparto angular, curva temporal y
                // color (aquí sin aclarado hacia blanco): unificarlos exigiría
                // tantos parámetros de modo que la función compartida dejaría de
                // describir un solo efecto. Son efectos distintos, no un duplicado.
                val sp = spark.value
                if (sp > 0f && sp < 1f) {
                    val count = 7
                    val seed = cell.index * 1.7f
                    val dist = size.minDimension * (0.3f + 0.8f * sp)
                    val fade = 1f - sp
                    val dot = (2.6f * (1f - sp * 0.4f)).dp.toPx()
                    for (i in 0 until count) {
                        val ang = seed + i * (2f * PI.toFloat() / count)
                        drawCircle(
                            color = wordColor.copy(alpha = fade),
                            radius = dot,
                            center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
                        )
                    }
                }
            }
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cell.entry?.toString() ?: "",
            style = MaterialTheme.typography.headlineSmall.copy(
                // Halo del color de la palabra: la letra se lee como rótulo de neón encendido.
                shadow = if (solved) Shadow(color = wordColor.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 14f) else null,
            ),
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            // Letra un 10% más pequeña (16 -> 14.4sp) para respirar dentro del tubo fino.
            fontSize = 14.4.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = letterPop.value
                scaleY = letterPop.value
                alpha = letterPop.value.coerceIn(0f, 1f)
            },
        )
    }
}

/**
 * Cristal de una celda o tecla: un fondo oscuro opaco bajo el tubo de neón y, al encenderse, la
 * luz del tubo derramándose hacia dentro (intensa junto al borde, apagada en el centro).
 *
 * Sin él las celdas eran contornos huecos sobre el fondo estrellado y las estrellas asomaban
 * entre las letras. El resplandor son varias capas finas que se solapan, para que la caída sea
 * suave; se pintan como trazos sobre el propio contorno y solo cuenta la mitad interior.
 *
 * @param margin separación entre el cristal y el borde del composable (la del tubo).
 * @param lit 0..1, cuánta luz del color [color] recibe el cristal.
 */
private fun DrawScope.drawCellGlass(margin: Float, corner: Float, color: Color, lit: Float) {
    val topLeft = Offset(margin, margin)
    val body = Size(size.width - margin * 2f, size.height - margin * 2f)
    val radius = CornerRadius(corner)
    drawRoundRect(
        color = lerp(LogicColors.BackgroundDark, LogicColors.SurfaceDark, 0.55f).copy(alpha = 0.92f),
        topLeft = topLeft,
        size = body,
        cornerRadius = radius,
    )
    val amount = lit.coerceIn(0f, 1f)
    if (amount <= 0f) return
    val reach = body.width * 0.26f
    for (i in 1..GLASS_GLOW_LAYERS) {
        // El trazo se centra en el contorno: se mete medio ancho hacia dentro para que toda
        // la capa caiga dentro del cristal y no ensucie el hueco entre celdas.
        val width = reach * i / GLASS_GLOW_LAYERS
        drawRoundRect(
            color = color.copy(alpha = 0.055f * amount),
            topLeft = Offset(topLeft.x + width / 2f, topLeft.y + width / 2f),
            size = Size(body.width - width, body.height - width),
            cornerRadius = CornerRadius((corner - width / 2f).coerceAtLeast(0f)),
            style = Stroke(width = width),
        )
    }
}

/**
 * Teclado inferior de letras, **centrado**, con estética de tecla de juego. Las acciones
 * de borrado ya no viven aquí: se movieron junto a la palabra en curso (retroceso y
 * papelera). La escritura es libre: el jugador teclea y corrige con esos botones.
 */
@Composable
private fun LetterBank(
    letters: List<Char>,
    accent: Color,
    onTapLetter: (Char) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LETRAS", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        val rows = remember(letters) { letters.chunked(6) }
        // Teclas por fila de la fila más llena: manda para el cálculo de tamaño,
        // así todas las filas usan el mismo tamaño y la más llena entra completa.
        val maxPerRow = rows.maxOfOrNull { it.size } ?: 0
        // El tamaño de tecla se adapta al ancho disponible: parte de BankLetterSize
        // y se encoge SOLO cuando la fila más llena no cabría (p. ej. 6 letras en
        // pantallas angostas), para que nunca se corte la última letra. Con 5 o
        // menos letras se mantiene el tamaño original.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val keySize = if (maxPerRow > 0) {
                ((maxWidth - BankLetterGap * (maxPerRow - 1)) / maxPerRow)
                    .coerceIn(BankLetterMinSize, BankLetterSize)
            } else {
                BankLetterSize
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BankLetterGap, Alignment.CenterHorizontally),
                    ) {
                        row.forEach { letter ->
                            LetterKey(letter = letter, size = keySize, accent = accent, onClick = { onTapLetter(letter) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tecla de letra con la **misma estética neón del crucigrama**: un tubo de neón
 * encendido ([drawNeonTile]) con la letra dentro. Rebota con [bounceClick] al pulsar.
 */
@Composable
private fun LetterKey(letter: Char, size: Dp, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressGlow by interaction.collectPressGlow()
    // La letra escala con la tecla (~40% del lado) para mantener la proporción
    // original (24sp a 60dp) cuando el banco se encoge por falta de ancho.
    val fontSize = (size.value * 0.4f).sp
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                // Cristal bajo el tubo: la tecla deja de ser un contorno hueco sobre las
                // estrellas, y al pulsarla se llena de luz.
                drawCellGlass(margin = 5.dp.toPx(), corner = 16.dp.toPx(), color = accent, lit = 0.55f + 0.45f * pressGlow)
                drawNeonTile(accent, activeAmt = 0.85f, pressAmt = pressGlow, cornerRadius = 16.dp, sparks = false, baseMargin = 5.dp)
            }
            .clip(shape)
            .bounceClick(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontSize = fontSize,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * Cartel que aparece al resolver toda la rejilla si aún quedan palabras extra sin
 * descubrir (ver [CrucigramaNeonState.gridComplete]). Antes el nivel se cerraba solo
 * en cuanto se completaba la última pista, así que las extras pendientes quedaban
 * fuera de alcance para siempre; ahora se le da al jugador la opción de seguir
 * escribiendo antes de cerrar la partida. Mismo lenguaje visual que
 * [com.kortexgames.app.ui.components.GameExitGuard] (tarjeta redondeada, icono neón,
 * CTA con degradado + acción secundaria en texto).
 */
@Composable
private fun ExtrasPromptDialog(
    remaining: Int,
    onKeepSearching: () -> Unit,
    onFinish: () -> Unit,
) {
    val accent = LogicColors.Amber
    Dialog(onDismissRequest = onKeepSearching) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(icon = KortexIcons.Star, tint = accent, size = 40.dp, glow = true)
            Text(
                "¡Crucigrama completo!",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                if (remaining == 1) {
                    "Resolviste todas las palabras. Queda 1 palabra extra por " +
                        "descubrir. ¿Seguís buscándola antes de cerrar el nivel?"
                } else {
                    "Resolviste todas las palabras. Quedan $remaining palabras extra " +
                        "por descubrir. ¿Seguís buscándolas antes de cerrar el nivel?"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            AnimatedGameButton(
                onClick = onKeepSearching,
                gradient = LogicGradients.reward,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "SEGUIR BUSCANDO",
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.BackgroundDark,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(
                "TERMINAR NIVEL",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onFinish)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/**
 * Cartel de palabras **extra** (bonus), anclado arriba a la izquierda bajo el marcador.
 * Es una píldora-contador siempre visible; al tocarla (o al encontrar una extra) se
 * despliega debajo la lista de palabras. Las encontradas se revelan en ámbar; las
 * pendientes se enmascaran con puntos. Ámbar = recompensa (§9.2).
 *
 * **Flota por encima, no empuja layout**: la lista desplegable se pinta en un
 * [Popup] anclado bajo la píldora, en vez de vivir dentro del `Column` de la
 * pantalla. Antes ocupaba espacio real ahí: al abrirse, achicaba el `Box` con
 * `weight(1f)` del tablero y con él el tamaño de celda/letra del crucigrama
 * (pedido del usuario: "debería verse por encima y no ocupar lugar del
 * tablero"). El `Popup` se dibuja en su propia capa —no participa en la
 * medición del `Column` padre— así que el tablero nunca se resize al abrir o
 * cerrar el cartel.
 *
 * **Animación de entrada y salida** ([AnimatedVisibility]): la lista aparece con un
 * fundido + expansión vertical con resorte (orgánico, §9.4) y se retira con fundido +
 * colapso. Al descubrir una extra ([foundTick] cambia) el cartel se **abre solo 3 s**,
 * luego se cierra con su animación, y salta una **ráfaga de chispas** ámbar sobre la
 * píldora para reforzar el hallazgo.
 */
@Composable
private fun ExtrasPanel(
    words: List<String>,
    found: Set<String>,
    foundTick: Long,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) } // abierto manualmente por el usuario
    val accent = LogicColors.Amber
    val pillShape = RoundedCornerShape(14.dp)
    val cardShape = RoundedCornerShape(16.dp)
    val spark = remember { Animatable(0f) }
    var pillHeightPx by remember { mutableStateOf(0) }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }

    // Al encontrar una extra: chispas + auto-abrir 3 s (salvo que el usuario lo haya fijado).
    LaunchedEffect(foundTick) {
        if (foundTick == 0L) return@LaunchedEffect
        launch {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(650, easing = LinearEasing))
        }
        open = true
        delay(3000)
        if (!pinned) open = false
    }

    // Box (no Column): su tamaño lo fija solo la píldora, ya que el Popup vive
    // en una capa aparte y no aporta altura al padre.
    Box(modifier = modifier) {
        // Píldora-contador siempre visible, con las chispas del hallazgo detrás.
        Row(
            modifier = Modifier
                .onGloballyPositioned { pillHeightPx = it.size.height }
                .drawBehind {
                    val sp = spark.value
                    if (sp > 0f && sp < 1f) {
                        val count = 9
                        val dist = size.minDimension * (0.4f + 1.1f * sp)
                        val fade = 1f - sp
                        val dot = (3f * (1f - sp * 0.4f)).dp.toPx()
                        for (i in 0 until count) {
                            val ang = i * (2f * PI.toFloat() / count)
                            drawCircle(
                                color = accent.copy(alpha = fade),
                                radius = dot,
                                center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
                            )
                        }
                    }
                }
                .clip(pillShape)
                .background(accent.copy(alpha = 0.14f))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.5f)), pillShape)
                .bounceClick {
                    open = !open
                    pinned = open
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NeonIcon(icon = KortexIcons.Star, tint = accent, size = 16.dp, glow = true)
            Text("EXTRAS", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
            Text("${found.size}/${words.size}", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        }

        // Siempre montado (para poder reproducir la animación de salida): con la
        // lista cerrada se colapsa a tamaño 0 dentro del Popup, invisible y sin
        // coste de layout para el resto de la pantalla.
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, pillHeightPx + gapPx),
            properties = PopupProperties(focusable = false),
        ) {
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(200)) + expandVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                ),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .clip(cardShape)
                        .background(LogicColors.SurfaceDark.copy(alpha = 0.96f))
                        .border(BorderStroke(1.dp, accent.copy(alpha = 0.5f)), cardShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    words.forEach { w ->
                        val f = w in found
                        Text(
                            text = if (f) w else "•".repeat(w.length),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (f) accent else LogicColors.OnDarkMuted,
                            fontWeight = if (f) FontWeight.Black else FontWeight.Normal,
                            letterSpacing = if (f) 1.sp else 3.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackFlash(eventId: Long, result: CrucigramaNeonOutcome?) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(eventId) {
        if (eventId == 0L || result == null) return@LaunchedEffect
        alpha.snapTo(0.27f)
        alpha.animateTo(0f, tween(380))
    }
    if (alpha.value <= 0f || result == null) return
    val color = if (result == CrucigramaNeonOutcome.CORRECT) LogicColors.Success else LogicColors.Error
    // Por los cantos y no como velo plano: el velo teñía también la rejilla justo cuando la
    // palabra se está encendiendo con su propio color.
    Canvas(modifier = Modifier.fillMaxSize()) { drawEdgeFlash(color, alpha.value / 0.27f) }
}
