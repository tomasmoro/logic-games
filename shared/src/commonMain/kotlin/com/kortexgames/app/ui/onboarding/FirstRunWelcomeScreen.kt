package com.kortexgames.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.game.FirstRunGames
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.FireworksOverlay
import com.kortexgames.app.ui.components.GameMotifIcon
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LegalPassiveNotice
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.SpaceBackdrop
import com.kortexgames.app.ui.components.StaggeredReveal
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.pulse
import com.kortexgames.app.ui.components.softGlow
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.firstrun_age_notice
import kortexgames.shared.generated.resources.firstrun_welcome_cta
import kortexgames.shared.generated.resources.firstrun_welcome_cta_continue
import kortexgames.shared.generated.resources.firstrun_welcome_cta_done
import kortexgames.shared.generated.resources.firstrun_welcome_games_label
import kortexgames.shared.generated.resources.firstrun_welcome_skip
import kortexgames.shared.generated.resources.firstrun_welcome_subtitle
import kortexgames.shared.generated.resources.firstrun_welcome_subtitle_done
import kortexgames.shared.generated.resources.firstrun_welcome_subtitle_progress
import kortexgames.shared.generated.resources.firstrun_welcome_title
import kortexgames.shared.generated.resources.firstrun_welcome_title_done
import kortexgames.shared.generated.resources.firstrun_welcome_title_progress
import kortexgames.shared.generated.resources.logo_kortex
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Cuánto se deja [FirstRunWelcomeScreen.justCompletedIndex] activo antes de
 * consumirse: el tiempo que tardan en jugarse el rebote del check
 * ([WelcomeGameCard]) y los tres estallidos de su [FireworksOverlay] (ver constantes
 * de duración en ese archivo). Consumir antes cortaría la animación a medias;
 * consumir después es solo higiene de estado (ver KDoc de [onCelebrationConsumed]).
 */
private const val CELEBRATION_DURATION_MS = 2200L

/**
 * **Hub** de la bienvenida de primera apertura: la pantalla a la que se llega antes
 * del primer juego y a la que se **vuelve tras cada uno** de los [FirstRunGames]
 * ([com.kortexgames.app.ui.onboarding.FirstRunFlow] la encadena así en `App.kt`).
 *
 * Es deliberadamente **una sola pantalla con tres estados**, no una distinta por
 * juego —esa es la clave de la escalabilidad: añadir un cuarto juego a
 * [FirstRunGames.sequence] no toca esta función, solo cambia cuántas tarjetas pinta
 * el `forEachIndexed` y qué dice el contador—.
 *
 *  - **Inicio** (`completedCount == 0`): presenta los juegos, invita a empezar.
 *  - **En progreso** (`0 < completedCount < total`): igual, pero el título celebra
 *    el avance y el CTA dice "Seguir jugando"; la tarjeta recién despachada
 *    ([justCompletedIndex]) reproduce su animación de completado, y la siguiente se
 *    **desbloquea** delante del jugador (candado → número, ver [WelcomeGameCard]).
 *  - **Terminada** (`completedCount == total`): mismas tarjetas, todas con check;
 *    el CTA ("Continuar") lleva a la puerta de sesión y desaparece la opción de
 *    saltar (ya no hay nada que saltar).
 *
 * Cada tarjeta refleja además su propio estado ([WelcomeCardState]): la que toca
 * jugar ahora es **clicable** y lleva directo al juego (mismo destino que el CTA:
 * una vía rápida para quien no necesita leer el resto), las jugadas quedan
 * marcadas y quietas, y las que aún no tocan se ven grises, atenuadas y con
 * candado — no reaccionan al toque hasta que les llega el turno.
 *
 * @param games juegos de la bienvenida, en orden (metadatos desde [GameCatalog]).
 * @param completedCount cuántos ya se jugaron (0..games.size).
 * @param justCompletedIndex índice del juego que se acaba de despachar (dispara su
 *        animación de completado una vez), o `null` si no hay ninguno pendiente.
 * @param onCelebrationConsumed se llama [CELEBRATION_DURATION_MS] después de mostrar
 *        la celebración de [justCompletedIndex] (no al instante: cortaría la
 *        animación a mitad), para que no vuelva a reproducirse en una recomposición
 *        futura.
 * @param onCelebrationBurst hook para sonido/háptica del instante de celebración;
 *        no-op por defecto.
 * @param showLegalNotice si hay que mostrar el aviso de condiciones y privacidad.
 * @param onStart continúa: al primer juego, al siguiente pendiente, o —si ya no
 *        queda ninguno— a la puerta de sesión (lo decide el llamador).
 * @param onSkip salta el resto de la bienvenida e va directo a la puerta de sesión.
 *
 * ## Presentación (petición del usuario)
 * Toda la pantalla entra **de arriba abajo, de uno en uno**: logo, título,
 * subtítulo, etiqueta, cada tarjeta y por último el bloque de acciones, cada cual
 * envuelto en [StaggeredReveal] con su propio índice consecutivo (un contador local
 * a la función, no fijo, porque el número de tarjetas varía con [games]). Es la
 * misma entrada escalonada que usa la Home (CLAUDE.md §9.4): el ojo la recorre en
 * el orden en que queremos que se lea, no de golpe.
 */
@Composable
fun FirstRunWelcomeScreen(
    games: List<GameInfo>,
    completedCount: Int,
    justCompletedIndex: Int?,
    onCelebrationConsumed: () -> Unit,
    showLegalNotice: Boolean,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onCelebrationBurst: () -> Unit = {},
) {
    val stage = when {
        completedCount <= 0 -> WelcomeStage.INTRO
        completedCount < games.size -> WelcomeStage.PROGRESS
        else -> WelcomeStage.DONE
    }

    // La celebración es un disparo único por juego despachado: se avisa al sonar y se
    // consume [CELEBRATION_DURATION_MS] después —NO al instante—, para no cortarla a
    // mitad si el consumo disparase una recomposición que apagara `celebrate` antes
    // de que el rebote del check o los estallidos terminen de jugarse.
    LaunchedEffect(justCompletedIndex) {
        if (justCompletedIndex != null) {
            onCelebrationBurst()
            delay(CELEBRATION_DURATION_MS)
            onCelebrationConsumed()
        }
    }

    // Contador de la entrada escalonada: un único índice consecutivo que recorre
    // TODA la pantalla (no solo las tarjetas), así que cada elemento —logo, título,
    // subtítulo, etiqueta, tarjetas y bloque de acciones— aparece de uno en uno, de
    // arriba abajo. Es local a la función (no `remember`): se recalcula igual en
    // cada composición, así que el orden nunca varía.
    var revealIndex = 0

    Box(modifier = modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        SpaceBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            // Cuerpo desplazable: el CTA queda anclado abajo aunque la pantalla sea
            // baja o el idioma alargue los textos.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                StaggeredReveal(index = revealIndex++) {
                    Image(
                        painter = painterResource(Res.drawable.logo_kortex),
                        contentDescription = "Kortex Games",
                        modifier = Modifier.size(104.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))
                StaggeredReveal(index = revealIndex++) {
                    Text(
                        stringResource(stage.title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = LogicColors.OnDark,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(12.dp))
                StaggeredReveal(index = revealIndex++) {
                    Text(
                        stringResource(stage.subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LogicColors.OnDarkMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(28.dp))
                StaggeredReveal(index = revealIndex++) {
                    Text(
                        stringResource(Res.string.firstrun_welcome_games_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = LogicColors.NeonCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // El juego que se acaba de desbloquear (si hay uno): siempre el
                // siguiente al que se acaba de despachar. Se deriva aquí, no se
                // persiste en ningún sitio, porque es una consecuencia directa de
                // [justCompletedIndex] — nada que guardar aparte.
                val justUnlockedIndex = justCompletedIndex?.plus(1)?.takeIf { it < games.size }

                Spacer(Modifier.height(12.dp))
                games.forEachIndexed { index, game ->
                    StaggeredReveal(index = revealIndex++) {
                        WelcomeGameCard(
                            order = index + 1,
                            game = game,
                            state = when {
                                index < completedCount -> WelcomeCardState.DONE
                                index == completedCount -> WelcomeCardState.CURRENT
                                else -> WelcomeCardState.LOCKED
                            },
                            celebrate = index == justCompletedIndex,
                            unlocking = index == justUnlockedIndex,
                            onClick = onStart,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(12.dp))
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StaggeredReveal(index = revealIndex++) {
                    AnimatedGameButton(
                        onClick = onStart,
                        gradient = LogicGradients.play,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulse()
                            .softGlow(LogicColors.NeonGreen),
                        contentPadding = PaddingValues(vertical = 18.dp),
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
                                stringResource(stage.cta),
                                style = MaterialTheme.typography.titleMedium,
                                color = LogicColors.BackgroundDark,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }

                // Salida secundaria + aviso legal: un único paso de la entrada
                // escalonada (son el "pie" de la pantalla, se leen como un bloque).
                StaggeredReveal(index = revealIndex++) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Solo mientras queda algo que saltar. En el estado DONE
                        // sería un segundo botón que hace lo mismo que el CTA.
                        if (stage != WelcomeStage.DONE) {
                            Text(
                                stringResource(Res.string.firstrun_welcome_skip),
                                style = MaterialTheme.typography.labelLarge,
                                color = LogicColors.OnDarkMuted,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bounceClick(onClick = onSkip)
                                    .padding(vertical = 14.dp),
                            )
                        }

                        if (showLegalNotice) {
                            Spacer(Modifier.height(2.dp))
                            LegalPassiveNotice()
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(Res.string.firstrun_age_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LogicColors.OnDarkMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Los tres estados del hub. Cada uno es solo texto + CTA distintos: la lista de
 * tarjetas y su lógica de "cuántas van hechas" no cambian entre estados, así que no
 * hace falta ramificar el resto de la pantalla.
 */
private enum class WelcomeStage(val title: StringResource, val subtitle: StringResource, val cta: StringResource) {
    INTRO(Res.string.firstrun_welcome_title, Res.string.firstrun_welcome_subtitle, Res.string.firstrun_welcome_cta),
    PROGRESS(
        Res.string.firstrun_welcome_title_progress,
        Res.string.firstrun_welcome_subtitle_progress,
        Res.string.firstrun_welcome_cta_continue,
    ),
    DONE(
        Res.string.firstrun_welcome_title_done,
        Res.string.firstrun_welcome_subtitle_done,
        Res.string.firstrun_welcome_cta_done,
    ),
}

/**
 * Los tres estados de una tarjeta de juego dentro del hub. A diferencia de
 * [WelcomeStage] (que describe la pantalla entera), este describe cada tarjeta por
 * separado: en cualquier momento hay como mucho una [CURRENT].
 */
private enum class WelcomeCardState {
    /** Ya jugado: check, borde marcado, no clicable (no hay nada más que hacer ahí). */
    DONE,

    /** El siguiente en la cola: aspecto normal y **clicable**, lleva directo al juego. */
    CURRENT,

    /** Aún no le toca: gris, atenuado, con candado, sin interacción. */
    LOCKED,
}

/** Opacidad del borde según [WelcomeCardState] (candado apenas insinuado, jugado marcado). */
private const val LOCKED_BORDER_ALPHA = 0.18f
private const val CURRENT_BORDER_ALPHA = 0.35f
private const val DONE_BORDER_ALPHA = 0.7f

/** Opacidad de todo el contenido de una tarjeta bloqueada (candado "apagado"). */
private const val LOCKED_CONTENT_ALPHA = 0.45f

/** Retardo antes de animar el desbloqueo: deja que se note primero la celebración
 *  de la tarjeta anterior (§9.4 — no dos animaciones peleando por la atención). */
private const val UNLOCK_START_DELAY_MS = 550L

/**
 * Tarjeta de un juego de la bienvenida: número de orden (o check, o candado según
 * [state]), miniatura con el **motivo** del juego (la misma identidad visual que su
 * tarjeta del catálogo y su antesala), título y la habilidad que entrena.
 *
 * Solo la tarjeta [WelcomeCardState.CURRENT] es **clicable**: tocarla hace lo mismo
 * que el CTA de abajo (una vía directa para quien ya sabe qué quiere jugar sin
 * bajar la vista). Las bloqueadas no reaccionan al toque —nada que hacer ahí
 * todavía— y las jugadas tampoco (ya cumplieron su papel en la bienvenida).
 *
 * @param state qué papel juega esta tarjeta ahora mismo.
 * @param celebrate es el juego que se **acaba** de despachar (implica que ya fue
 *        [WelcomeCardState.DONE] antes de esta llamada): reproduce una vez la
 *        animación de completado (check con rebote + [FireworksOverlay] localizado).
 * @param unlocking es la tarjeta que se acaba de volver [WelcomeCardState.CURRENT]
 *        (antes estaba [WelcomeCardState.LOCKED]): anima el candado quitándose —
 *        borde y contenido pasan de grises/atenuados a su color y opacidad final, y
 *        el badge rebota del candado al número— en vez de aparecer ya desbloqueada.
 * @param onClick acción del CTA/tarjeta actual; se ignora si [state] no es CURRENT.
 */
@Composable
private fun WelcomeGameCard(
    order: Int,
    game: GameInfo,
    state: WelcomeCardState,
    celebrate: Boolean,
    unlocking: Boolean,
    onClick: () -> Unit,
) {
    val accent = game.category.accent
    val shape = RoundedCornerShape(24.dp)

    // Progreso 0→1 de "quitarse el candado". Nace en 0 (aspecto bloqueado) SOLO en
    // la tarjeta marcada [unlocking]; cualquier otra nace directamente en 1 (su
    // aspecto final), sin animar nada que no acaba de cambiar.
    val unlock = remember(unlocking) { Animatable(if (unlocking) 0f else 1f) }
    LaunchedEffect(unlocking) {
        if (unlocking) {
            delay(UNLOCK_START_DELAY_MS)
            unlock.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    val t = unlock.value

    val borderAlpha = when (state) {
        WelcomeCardState.LOCKED -> LOCKED_BORDER_ALPHA
        WelcomeCardState.DONE -> DONE_BORDER_ALPHA
        WelcomeCardState.CURRENT -> LOCKED_BORDER_ALPHA + (CURRENT_BORDER_ALPHA - LOCKED_BORDER_ALPHA) * t
    }
    val borderColor = when (state) {
        WelcomeCardState.LOCKED -> LogicColors.OnDarkMuted
        WelcomeCardState.DONE -> accent
        WelcomeCardState.CURRENT -> lerp(LogicColors.OnDarkMuted, accent, t)
    }
    val contentAlpha = if (state == WelcomeCardState.LOCKED) {
        LOCKED_CONTENT_ALPHA
    } else {
        LOCKED_CONTENT_ALPHA + (1f - LOCKED_CONTENT_ALPHA) * t
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LogicColors.SurfaceDark)
                .border(1.dp, borderColor.copy(alpha = borderAlpha), shape)
                .alpha(contentAlpha)
                .then(
                    if (state == WelcomeCardState.CURRENT) {
                        Modifier.bounceClick(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameThumbnail(game = game, accent = accent)

            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    game.category.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }

            Spacer(Modifier.width(10.dp))
            OrderBadge(order = order, accent = accent, state = state, celebrate = celebrate, unlocking = unlocking)
        }

        // Celebración: un estallido corto y contenido a las medidas de la propia
        // tarjeta (no a pantalla completa) para no competir con el resto del hub —
        // sigue siendo "un juego, una celebración", solo que localizada.
        if (celebrate) {
            FireworksOverlay(
                modifier = Modifier.matchParentSize(),
                burstCount = 3,
                seed = order,
            )
        }
    }
}

/** Miniatura cuadrada del juego, con su motivo sobre un chip del color de acento. */
@Composable
private fun GameThumbnail(game: GameInfo, accent: Color) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        val motif = game.motif
        if (motif != null) {
            GameMotifIcon(motif = motif, accent = accent, modifier = Modifier.fillMaxSize())
        } else {
            NeonIcon(icon = game.category.icon, tint = accent, size = 28.dp)
        }
    }
}

/**
 * Disco de estado de la tarjeta: número mientras es la actual, check cuando ya se
 * jugó, candado mientras está bloqueada. Ni el número ni el candado son decoración:
 * comunican que hay un recorrido con final y que todavía no le toca a ese juego.
 *
 * Rebota con un resorte —en vez de aparecer ya puesto— en dos momentos, ambos
 * "esto acaba de pasar" (CLAUDE.md §9.4: física de resorte para lo que el usuario
 * nota): cuando [celebrate] marca el check recién ganado, y cuando [unlocking]
 * marca el candado convirtiéndose en número jugable. Mismo mecanismo para los dos
 * porque, para el jugador, es la misma clase de aviso: "algo cambió aquí".
 */
@Composable
private fun OrderBadge(order: Int, accent: Color, state: WelcomeCardState, celebrate: Boolean, unlocking: Boolean) {
    // Mientras [unlocking] espera su retardo (ver [UNLOCK_START_DELAY_MS] en
    // [WelcomeGameCard], al que este se sincroniza a propósito: el candado se
    // quita y el resto de la tarjeta se aclara en el mismo instante, como un único
    // gesto), el badge sigue mostrando el candado; al cumplirse, cambia a número Y
    // rebota a la vez — el rebote ES la señal de que acaba de cambiar.
    var showsLocked by remember(unlocking) { mutableStateOf(unlocking) }
    val pop = celebrate || unlocking
    val scale = remember(pop) { Animatable(if (pop) 0.3f else 1f) }
    LaunchedEffect(pop) {
        if (pop) {
            if (unlocking) {
                delay(UNLOCK_START_DELAY_MS)
                showsLocked = false
            }
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    // Qué se pinta: el candado gana mientras la tarjeta sigue "pareciendo" bloqueada
    // (bloqueada de verdad, o desbloqueándose pero aún dentro del retardo).
    val displayLocked = state == WelcomeCardState.LOCKED || showsLocked
    val badgeColor = if (displayLocked) LogicColors.OnDarkMuted else accent
    val badgeAlpha = when {
        displayLocked -> 0.14f
        state == WelcomeCardState.DONE -> 0.28f
        else -> 0.18f
    }

    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(scale.value)
            .clip(CircleShape)
            .background(badgeColor.copy(alpha = badgeAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            displayLocked -> NeonIcon(icon = KortexIcons.Lock, tint = LogicColors.OnDarkMuted, size = 14.dp, glow = false)
            state == WelcomeCardState.DONE -> NeonIcon(icon = KortexIcons.Check, tint = accent, size = 16.dp, glow = false)
            else -> Text(
                "$order",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Black,
            )
        }
    }
}
