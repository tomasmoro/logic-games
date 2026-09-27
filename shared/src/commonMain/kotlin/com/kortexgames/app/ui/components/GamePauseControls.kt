package com.kortexgames.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.game.GameStatus
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gamepause_audio_haptics
import kortexgames.shared.generated.resources.gamepause_audio_music
import kortexgames.shared.generated.resources.gamepause_audio_section
import kortexgames.shared.generated.resources.gamepause_audio_sound
import kortexgames.shared.generated.resources.gamepause_cta_exit
import kortexgames.shared.generated.resources.gamepause_cta_next_level
import kortexgames.shared.generated.resources.gamepause_cta_restart
import kortexgames.shared.generated.resources.gamepause_cta_resume
import kortexgames.shared.generated.resources.gamepause_exit_keeps_progress
import kortexgames.shared.generated.resources.gamepause_help_title
import kortexgames.shared.generated.resources.gamepause_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
 * @param suppressMenu oculta el menú de pausa aunque [status] sea [GameStatus.PAUSED].
 *        Lo usan los juegos en tiempo real que pausan el motor al abrir el cartel de
 *        [GameExitGuard]: sin esto, esa pausa "de cortesía" haría aparecer este menú
 *        por detrás del cartel de confirmación. `false` por defecto.
 * @param exitKeepsProgress si el juego guarda la partida en curso al salir (ver
 *        [com.kortexgames.app.game.ResumableGameEngine] / [GameExitGuard]): cuando
 *        es `true` se aclara bajo "SALIR" que no se pierde el progreso. `false` por
 *        defecto para no cambiar el copy de los juegos que aún no lo activan.
 * @param onAdvanceLevel si no es `null`, el menú de pausa suma un botón "SIGUIENTE
 *        NIVEL" que cierra el nivel actual (con lo ya resuelto) y avanza directo,
 *        sin pasar por el cartel de fin de partida. Pensado para el momento en que
 *        un juego LEVELED deja la partida "técnicamente ganada" pero con contenido
 *        opcional pendiente (p. ej. el Crucigrama Neón con extras por descubrir
 *        tras completar la rejilla, ver [com.kortexgames.app.game.crucigrama.CrucigramaNeonState.gridComplete]):
 *        el jugador puede saltarse ese contenido opcional sin tener que reanudar
 *        primero. `null` (por defecto) no cambia el menú del resto de juegos.
 * @param onRestart si no es `null`, el menú suma un botón "REINICIAR" junto a
 *        "SALIR" (mitad y mitad) que reinicia el nivel actual desde cero. Solo
 *        tiene sentido en juegos donde reiniciar es una acción segura e inmediata
 *        (sin coste de anuncio); el propio callback decide si además reanuda la
 *        partida. `null` (por defecto): el menú muestra "SALIR" a todo el ancho,
 *        como antes.
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
    suppressMenu: Boolean = false,
    exitKeepsProgress: Boolean = false,
    onAdvanceLevel: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
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
            visible = status == GameStatus.PAUSED && !suppressMenu,
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
            onAdvanceLevel = onAdvanceLevel,
            onRestart = onRestart,
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
    onAdvanceLevel: (() -> Unit)?,
    onRestart: (() -> Unit)?,
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
        // Envoltorio propio (además de la tarjeta) para poder anclar los acentos de
        // esquina a las mismas coordenadas que anima la tarjeta (escala + fundido).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .alpha(cardAlpha),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(LogicColors.SurfaceDark)
                    .border(
                        BorderStroke(
                            1.5.dp,
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f)),
                            ),
                        ),
                        cardShape,
                    )
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PauseHeader(accent = accent, gameTitle = gameTitle, onClose = onResume)

                // --- Opciones de audio -------------------------------------------------
                SectionLabel(stringResource(Res.string.gamepause_audio_section), accent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AudioToggle(
                        iconOn = KortexIcons.SfxOn,
                        iconOff = KortexIcons.SfxOff,
                        label = stringResource(Res.string.gamepause_audio_sound),
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
                        label = stringResource(Res.string.gamepause_audio_music),
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
                        label = stringResource(Res.string.gamepause_audio_haptics),
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

                // --- Acciones principales -----------------------------------------------
                // CTA principal en el color propio del juego (cada categoría tiene el suyo,
                // ver [CategoryPalette]): degradado de [accent] hacia una versión más clara,
                // en vez de un verde fijo que ignoraría esa identidad. Único bucle de
                // atención (pulse) reservado al CTA que guía: reanudar (§9.4).
                AnimatedGameButton(
                    onClick = onResume,
                    gradient = accentCtaGradient(accent),
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
                            stringResource(Res.string.gamepause_cta_resume),
                            style = MaterialTheme.typography.titleMedium,
                            color = LogicColors.BackgroundDark,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                // Atajo condicional: nivel "técnicamente ganado" con contenido opcional
                // pendiente (ver KDoc de [onAdvanceLevel]). Sin `pulse()`: el único bucle
                // de atención va a "REANUDAR" (§9.4), este es un CTA secundario.
                if (onAdvanceLevel != null) {
                    AnimatedGameButton(
                        onClick = onAdvanceLevel,
                        gradient = LogicGradients.reward,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NeonIcon(
                                icon = KortexIcons.ChevronRight,
                                tint = LogicColors.BackgroundDark,
                                size = 22.dp,
                                glow = false,
                            )
                            Text(
                                stringResource(Res.string.gamepause_cta_next_level),
                                style = MaterialTheme.typography.titleMedium,
                                color = LogicColors.BackgroundDark,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
                // "SALIR" (y, si aplica, "REINICIAR" junto a él a medias): en contorno,
                // no relleno, para no competir con "REANUDAR" como segunda acción del menú.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (onRestart != null) {
                        PauseOutlineButton(
                            icon = KortexIcons.Refresh,
                            label = stringResource(Res.string.gamepause_cta_restart),
                            tint = LogicColors.OnDarkMuted,
                            onClick = onRestart,
                        )
                    }
                    PauseOutlineButton(
                        icon = KortexIcons.Exit,
                        label = stringResource(Res.string.gamepause_cta_exit),
                        tint = LogicColors.Magenta,
                        onClick = onExit,
                    )
                }
                // Solo en juegos que activan el guardado al salir (ver GameExitGuard):
                // tranquiliza antes de que el jugador pulse "SALIR".
                if (exitKeepsProgress) {
                    Text(
                        stringResource(Res.string.gamepause_exit_keeps_progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogicColors.OnDarkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Acentos de esquina (marco tipo "visor"), ecos del mockup: puramente
            // ornamentales, en el color de acento del juego (§9.4 "los detalles hacen
            // grande a cualquier app"). La esquina inferior-derecha reutiliza el mismo
            // trazo rotado 180°: una "L" superior-izquierda girada media vuelta es,
            // geométricamente, la misma "L" pero abierta hacia la esquina opuesta.
            CornerBracket(
                accent = accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp),
            )
            CornerBracket(
                accent = accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 6.dp)
                    .graphicsLayer { rotationZ = 180f },
            )
        }
    }
}

/**
 * Cabecera del menú de pausa: insignia del icono de pausa (cuadrado redondeado, en el
 * color de acento del juego) + eyebrow "EN PAUSA" + título grande. Si hay [gameTitle]
 * (el caso normal) es él quien ocupa el titular; "EN PAUSA" queda como etiqueta
 * pequeña encima, igual que en el mockup. Sin [gameTitle], "En pausa" pasa a ser el
 * propio titular (no lo repetimos dos veces). El botón de cerrar comparte fila con la
 * insignia y el texto, como en la referencia, en vez de flotar suelto en la esquina.
 */
@Composable
private fun PauseHeader(accent: Color, gameTitle: String?, onClose: () -> Unit) {
    val pauseLabel = stringResource(Res.string.gamepause_title)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(accent.copy(alpha = 0.16f))
                .border(BorderStroke(1.2.dp, accent.copy(alpha = 0.5f)), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(icon = KortexIcons.Pause, tint = accent, size = 24.dp, glow = false)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (gameTitle != null) {
                Text(
                    pauseLabel.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                gameTitle ?: pauseLabel,
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        // Cerrar rápido (X): mismo efecto que "REANUDAR" pero como salida discreta,
        // a la altura de la cabecera en vez de flotar sola en la esquina de la tarjeta.
        CloseButton(onClick = onClose)
    }
}

/**
 * Degradado del CTA principal de un diálogo modal (REANUDAR de la pausa, "SIGUIENTE
 * NIVEL"/"JUGAR DE NUEVO" de fin de partida): el color propio del juego ([accent],
 * de [CategoryPalette]) aclarado hacia blanco en el primer punto. Reemplaza degradados
 * fijos (verde, cian...) que ignoraban la categoría del juego — CLAUDE.md §9.2: cada
 * categoría tiene su color, y este es el sitio donde más se nota.
 */
internal fun accentCtaGradient(accent: Color): List<Color> =
    listOf(lerp(accent, Color.White, 0.22f), accent)

/**
 * Botón de acción secundaria de un diálogo modal (REINICIAR/SALIR de la pausa,
 * REPETIR/SALIR de fin de partida): contorno en el color semántico [tint], sin
 * relleno, para quedar por debajo del CTA principal (con relleno) en la jerarquía
 * visual. Ocupa el ancho disponible a partes iguales con cualquier hermano en el
 * mismo [Row] (`weight(1f)`); envuelto solo, llena el ancho completo.
 */
@Composable
internal fun RowScope.PauseOutlineButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.55f))
            .border(BorderStroke(1.3.dp, tint.copy(alpha = 0.55f)), shape)
            .bounceClick(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 18.dp, glow = false)
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Acento decorativo de esquina en forma de "L" (marco tipo visor), del color de
 * acento del juego. Puramente ornamental: el borde real de la tarjeta ya lo da el
 * degradado de [Brush.linearGradient] en [PauseMenu]; esto añade el detalle de
 * esquina del mockup sin dibujar un segundo borde completo (que competiría con él).
 */
@Composable
internal fun CornerBracket(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val strokePx = 2.5.dp.toPx()
        drawLine(
            color = accent,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent,
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
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
        // Tamaño de icono fijo (el máximo que ocupa CON halo) para que activar/desactivar
        // no cambie la altura del chip: si el Box de NeonIcon se ajustara al contenido,
        // el halo (26dp * 1.9) solo existe cuando enabled, así que al deshabilitar el
        // icono "encogería" su hueco y el chip entero (borde incluido) se reflowearía,
        // desalineando el icono en vez de solo apagar su brillo.
        NeonIcon(
            icon = if (enabled) iconOn else iconOff,
            tint = tint,
            size = 26.dp,
            glow = enabled,
            modifier = Modifier.size(26.dp * 1.9f),
        )
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
            stringResource(Res.string.gamepause_help_title),
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
                stringResource(Res.string.gamepause_help_title),
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
