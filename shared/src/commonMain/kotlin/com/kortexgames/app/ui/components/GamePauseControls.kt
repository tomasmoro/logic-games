package com.kortexgames.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.game.GameStatus
import kotlinx.coroutines.launch

/**
 * # Control de pausa universal de los juegos
 *
 * Overlay **reutilizable** que dota a cualquier minijuego del botón de pausa y su
 * menú, de forma uniforme (petición: "que todos los juegos tengan botón de pausa
 * de una forma limpia y escalable"). En vez de reimplementarlo en cada pantalla,
 * cada juego coloca este único componente como **última capa** de su `Box` raíz y
 * lo cablea con tres callbacks; toda la UI del menú vive aquí.
 *
 * ## Por qué se conduce por [status] y no por estado local
 * La verdad de "estoy en pausa" es el ciclo de vida del motor ([GameStatus.PAUSED]),
 * no un booleano de UI. Al derivar la visibilidad del menú directamente de [status]
 * (reactivo, MVI) evitamos que la UI y el motor se desincronicen: pulsar pausa emite
 * el intent, el motor pasa a PAUSED (congelando cronómetro/física/loops —ver
 * [com.kortexgames.app.game.BaseGameEngine]) y el menú aparece como consecuencia.
 *
 * El componente NO intercepta toques mientras se juega (solo el botón lo hace); al
 * pausar, el propio scrim del menú bloquea la interacción con el tablero de debajo.
 *
 * @param status ciclo de vida del juego; el botón se ve en [GameStatus.RUNNING] y el
 *        menú en [GameStatus.PAUSED].
 * @param settings preferencias de audio/háptica (se leen y togglean desde el menú).
 * @param audio manager para el feedback inmediato (tap) de los propios controles.
 * @param onPause emite la pausa del juego (normalmente `vm.onIntent(...Pause)`).
 * @param onResume reanuda el juego (`vm.onIntent(...Resume)`).
 * @param onExit abandona la partida y vuelve a la lista (el `onExit` de la pantalla).
 * @param gameTitle nombre del juego, como subtítulo del menú (opcional).
 * @param help diseño de ayuda "¿Cómo se juega?" ([GameHelp], almacenados en
 *        [com.kortexgames.app.game.GameHelpContent]). Si no es null, el menú muestra una
 *        fila que abre la pantalla de ayuda genérica ([GameHelpSheet]) por encima del propio
 *        menú. Es la forma preferida; tiene prioridad sobre [helpText].
 * @param helpText **(legado)** explicación de "cómo se juega" en una sola cadena, que se
 *        muestra plegable en el menú. Se usa solo si [help] es null; si ambos son null, no
 *        hay ayuda. Preferir [help] para el nuevo diseño estructurado.
 * @param accent color de acento de la categoría (halo del botón, secciones del menú).
 * @param exitKeepsProgress si el juego guarda la partida en curso al salir (ver
 *        [com.kortexgames.app.game.ResumableGameEngine] / [GameExitGuard]): cuando
 *        es `true` se aclara bajo "SALIR" que no se pierde el progreso. `false` por
 *        defecto para no cambiar el copy de los juegos que aún no lo activan.
 */
@Composable
fun GamePauseControls(
    status: GameStatus,
    settings: SettingsRepository,
    audio: AudioAndHapticManager,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    gameTitle: String? = null,
    help: GameHelp? = null,
    helpText: String? = null,
    accent: Color = LogicColors.NeonCyan,
    exitKeepsProgress: Boolean = false,
) {
    // Estado de la hoja de ayuda genérica (solo cuando se inyecta [help]): se abre desde el
    // menú de pausa y se dibuja como última capa para quedar por encima de él.
    var showHelp by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // Botón de pausa: solo mientras se juega (en PAUSED lo sustituye el menú, y en
        // IDLE/FINISHED mandan la antesala / el overlay de fin de partida).
        if (status == GameStatus.RUNNING) {
            PauseButton(
                accent = accent,
                onClick = {
                    audio.playSound(SoundEffect.TAP)
                    audio.hapticFeedback(HapticFeedback.LIGHT)
                    onPause()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 16.dp),
            )
        }

        PauseMenu(
            visible = status == GameStatus.PAUSED,
            settings = settings,
            audio = audio,
            gameTitle = gameTitle,
            hasRichHelp = help != null,
            onOpenHelp = { showHelp = true },
            helpText = helpText,
            accent = accent,
            onResume = onResume,
            onExit = onExit,
            exitKeepsProgress = exitKeepsProgress,
        )

        // Hoja de ayuda genérica por encima del menú de pausa (su propio scrim lo tapa).
        if (help != null) {
            GameHelpSheet(
                help = help,
                visible = showHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
}

/**
 * Botón circular de pausa (esquina superior derecha). Mismo lenguaje que los botones
 * de cabecera de la antesala: disco de superficie con icono neón y [bounceClick].
 */
@Composable
private fun PauseButton(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), CircleShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = KortexIcons.Pause, tint = accent, size = 24.dp, glow = false)
    }
}

/**
 * Botón "X" de cierre rápido, en la esquina de una tarjeta modal (pausa o fin de
 * partida). Alternativa compacta al CTA principal de abajo, sin su peso visual;
 * el color se mantiene neutro (no de acento) para no competir con el CTA.
 */
@Composable
internal fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(LogicColors.SurfaceVariantDark)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = KortexIcons.Close, tint = LogicColors.OnDarkMuted, size = 18.dp, glow = false)
    }
}

/**
 * Menú modal de pausa: Reanudar, opciones de audio, ayuda y Salir. Se autogestiona la
 * aparición con la misma "física de juego" que [GameOverOverlay] (scrim en fundido +
 * tarjeta que entra con resorte y rebote), de modo que las pantallas no orquestan
 * ninguna animación: basta con cambiar [visible].
 */
@Composable
private fun BoxScope.PauseMenu(
    visible: Boolean,
    settings: SettingsRepository,
    audio: AudioAndHapticManager,
    gameTitle: String?,
    hasRichHelp: Boolean,
    onOpenHelp: () -> Unit,
    helpText: String?,
    accent: Color,
    onResume: () -> Unit,
    onExit: () -> Unit,
    exitKeepsProgress: Boolean,
) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.82f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "pauseScrim",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "pauseCardScale",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "pauseCardAlpha",
    )

    // Mientras el scrim es transparente no montamos nada: ni tapa el juego ni roba toques.
    if (scrimAlpha <= 0f) return

    val settingsState by settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Scrim a pantalla completa: oscurece y —clave— **bloquea** la interacción con el
    // tablero de debajo mientras el juego está pausado (clickable sin efecto).
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .alpha(cardAlpha)
                .clip(cardShape)
                .background(LogicColors.SurfaceDark)
                .border(
                    BorderStroke(1.5.dp, Brush.linearGradient(LogicGradients.ring)),
                    cardShape,
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cerrar rápido (X): mismo efecto que "REANUDAR" pero como salida discreta
            // en la esquina, sin competir con el CTA principal.
            CloseButton(
                onClick = onResume,
                modifier = Modifier.align(Alignment.End),
            )
            NeonIcon(icon = KortexIcons.Pause, tint = accent, size = 42.dp)
            Text(
                "En pausa",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
            )
            if (gameTitle != null) {
                Text(
                    gameTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }

            // --- Opciones de audio -------------------------------------------------
            SectionLabel("AUDIO", accent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AudioToggle(
                    iconOn = KortexIcons.SfxOn,
                    iconOff = KortexIcons.SfxOff,
                    label = "Sonido",
                    enabled = settingsState.isSfxEnabled,
                    accent = accent,
                    onToggle = {
                        audio.playSound(SoundEffect.TAP)
                        audio.hapticFeedback(HapticFeedback.LIGHT)
                        scope.launch { settings.setSfxEnabled(!settingsState.isSfxEnabled) }
                    },
                )
                AudioToggle(
                    iconOn = KortexIcons.MusicOn,
                    iconOff = KortexIcons.MusicOff,
                    label = "Música",
                    enabled = settingsState.isMusicEnabled,
                    accent = accent,
                    onToggle = {
                        audio.playSound(SoundEffect.TAP)
                        audio.hapticFeedback(HapticFeedback.LIGHT)
                        scope.launch { settings.setMusicEnabled(!settingsState.isMusicEnabled) }
                    },
                )
                AudioToggle(
                    iconOn = KortexIcons.Haptics,
                    iconOff = KortexIcons.Haptics,
                    label = "Vibración",
                    enabled = settingsState.isHapticsEnabled,
                    accent = accent,
                    onToggle = {
                        // Toca disparar la háptica ANTES de togglear para que se sienta
                        // aunque se esté desactivando (confirma qué hace el control).
                        audio.hapticFeedback(HapticFeedback.LIGHT)
                        audio.playSound(SoundEffect.TAP)
                        scope.launch { settings.setHapticsEnabled(!settingsState.isHapticsEnabled) }
                    },
                )
            }

            // --- Ayuda (cómo se juega) ---------------------------------------------
            // Con diseño estructurado ([help]): fila que abre la pantalla de ayuda genérica.
            // Sin él, respaldo de legado: texto plegable en línea ([helpText]).
            if (hasRichHelp) {
                HelpOpenRow(onClick = onOpenHelp, accent = accent)
            } else if (helpText != null) {
                HelpSection(helpText = helpText, accent = accent)
            }

            // --- Acciones principales ---------------------------------------------
            // Único bucle de atención (pulse) reservado al CTA que guía: reanudar (§9.4).
            AnimatedGameButton(
                onClick = onResume,
                gradient = LogicGradients.play,
                modifier = Modifier
                    .fillMaxWidth()
                    .pulse(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NeonIcon(
                        icon = KortexIcons.Play,
                        tint = LogicColors.BackgroundDark,
                        size = 22.dp,
                        glow = false,
                    )
                    Text(
                        "REANUDAR",
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.BackgroundDark,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            AnimatedGameButton(
                onClick = onExit,
                gradient = LogicGradients.energy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NeonIcon(
                        icon = KortexIcons.Exit,
                        tint = LogicColors.OnDark,
                        size = 20.dp,
                        glow = false,
                    )
                    Text(
                        "SALIR",
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.OnDark,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Solo en juegos que activan el guardado al salir (ver GameExitGuard):
            // tranquiliza antes de que el jugador pulse "SALIR".
            if (exitKeepsProgress) {
                Text(
                    "No perderás tu progreso: podrás continuar donde lo dejaste.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Etiqueta de sección del menú (p. ej. "AUDIO"), alineada a la izquierda en acento. */
@Composable
private fun SectionLabel(text: String, accent: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = accent,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Chip de conmutación de una preferencia de audio/háptica. Su lenguaje visual comunica
 * el estado: **activo** → acento neón con halo y superficie elevada; **inactivo** →
 * atenuado y sin halo (mismo icono tachado cuando existe variante "off").
 */
@Composable
private fun RowScope.AudioToggle(
    iconOn: ImageVector,
    iconOff: ImageVector,
    label: String,
    enabled: Boolean,
    accent: Color,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val tint = if (enabled) accent else LogicColors.OnDarkMuted
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (enabled) LogicColors.SurfaceVariantDark else LogicColors.SurfaceDark)
            .border(
                BorderStroke(
                    1.dp,
                    if (enabled) accent.copy(alpha = 0.5f) else LogicColors.SurfaceVariantDark,
                ),
                shape,
            )
            .bounceClick(onClick = onToggle)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NeonIcon(icon = if (enabled) iconOn else iconOff, tint = tint, size = 26.dp, glow = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * Fila "¿Cómo se juega?" que **abre** la pantalla de ayuda genérica ([GameHelpSheet]) por
 * encima del menú. Se usa cuando el juego inyecta un [GameHelp] estructurado; a diferencia
 * de [HelpSection] no despliega texto en línea, sino que lanza la hoja completa (con arte,
 * pasos y consejos). La flecha apunta a la derecha como "ir a".
 */
@Composable
private fun HelpOpenRow(onClick: () -> Unit, accent: Color) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LogicColors.SurfaceVariantDark)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NeonIcon(icon = KortexIcons.Help, tint = accent, size = 22.dp, glow = false)
        Text(
            "¿Cómo se juega?",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        NeonIcon(
            icon = KortexIcons.ChevronRight,
            tint = LogicColors.OnDarkMuted,
            size = 22.dp,
            glow = false,
        )
    }
}

/**
 * Sección de ayuda plegable: cabecera "¿Cómo se juega?" que despliega el texto
 * explicativo del juego. Plegada por defecto para no alargar el menú; la flecha rota
 * al abrir (feedback de estado, §9.4).
 */
@Composable
private fun HelpSection(helpText: String, accent: Color) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "helpChevron",
    )
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LogicColors.SurfaceVariantDark)
                .bounceClick(onClick = { expanded = !expanded })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NeonIcon(icon = KortexIcons.Help, tint = accent, size = 22.dp, glow = false)
            Text(
                "¿Cómo se juega?",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
                modifier = Modifier.weight(1f),
            )
            NeonIcon(
                icon = KortexIcons.ChevronRight,
                tint = LogicColors.OnDarkMuted,
                size = 22.dp,
                glow = false,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                helpText,
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(shape)
                    .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.6f))
                    .padding(14.dp),
            )
        }
    }
}
