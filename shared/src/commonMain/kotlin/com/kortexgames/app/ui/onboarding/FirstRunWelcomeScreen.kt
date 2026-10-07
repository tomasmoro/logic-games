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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kortexgames.shared.generated.resources.firstrun_welcome_progress_count
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
                // Cabecera compacta (logo y aire contenidos) para que las TRES tarjetas
                // quepan sin scroll en un móvil estándar: antes la tercera quedaba
                // cortada por el bloque del CTA y el recorrido no se veía entero.
                Spacer(Modifier.height(18.dp))
                StaggeredReveal(index = revealIndex++) {
                    Image(
                        painter = painterResource(Res.drawable.logo_kortex),
                        contentDescription = "Kortex Games",
                        modifier = Modifier.size(108.dp),
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

                Spacer(Modifier.height(22.dp))
                StaggeredReveal(index = revealIndex++) {
                    SessionProgress(
                        games = games,
                        completedCount = completedCount,
                        justCompletedIndex = justCompletedIndex,
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
                        shimmer = true,
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
private const val CURRENT_BORDER_ALPHA = 0.85f
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

    // La tarjeta que toca jugar se ENCIENDE: baño del color del juego, borde de neón
    // y resplandor exterior. Antes solo se distinguía de las bloqueadas por la
    // opacidad, y la pantalla no decía "empieza por aquí" más que con el CTA de abajo.
    // `lit` sigue al desbloqueo (t), así que la luz llega junto con el candado que cae.
    val lit = if (state == WelcomeCardState.CURRENT) t.coerceIn(0f, 1f) else 0f
    val background = when (state) {
        WelcomeCardState.CURRENT -> Brush.horizontalGradient(
            listOf(lerp(LogicColors.SurfaceDark, accent, 0.26f * lit), LogicColors.SurfaceDark),
        )
        WelcomeCardState.DONE -> Brush.horizontalGradient(
            listOf(lerp(LogicColors.SurfaceDark, accent, 0.10f), LogicColors.SurfaceDark),
        )
        WelcomeCardState.LOCKED -> SolidColor(LogicColors.SurfaceDark)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Resplandor por FUERA del contorno (capas finas que se solapan, misma
                // receta que las tarjetas modales). Estático: el único bucle de la
                // pantalla es el del CTA (§9.4).
                .drawBehind {
                    if (lit <= 0f) return@drawBehind
                    val radius = 24.dp.toPx()
                    val layers = 6
                    for (i in 1..layers) {
                        val w = 16.dp.toPx() * i / layers
                        drawRoundRect(
                            color = accent.copy(alpha = 0.045f * lit),
                            topLeft = Offset(-w / 2f, -w / 2f),
                            size = Size(size.width + w, size.height + w),
                            cornerRadius = CornerRadius(radius + w / 2f),
                            style = Stroke(width = w),
                        )
                    }
                }
                .clip(shape)
                .background(background)
                .border((1.dp + 0.6.dp * lit), borderColor.copy(alpha = borderAlpha), shape)
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
                    // En la tarjeta activa el subtítulo toma el color del juego: refuerza
                    // qué habilidad entrena justo en la que se va a jugar.
                    color = lerp(LogicColors.OnDarkMuted, accent, 0.75f * lit),
                )
            }

            Spacer(Modifier.width(10.dp))
            OrderBadge(accent = accent, state = state, celebrate = celebrate, unlocking = unlocking)
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
            .size(60.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.10f))))
            .border(1.dp, accent.copy(alpha = 0.40f), shape),
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
 * Disco de estado de la tarjeta: un mini botón de **jugar** (disco relleno con ▶)
 * mientras es la actual, check cuando ya se jugó, candado mientras está bloqueada.
 * El orden del recorrido ya lo cuentan la posición en la lista y la barra de
 * [SessionProgress]; aquí lo que importa es decir de un vistazo "esta se puede
 * pulsar" frente a "esta aún no" — por eso la actual dejó de mostrar su número.
 *
 * Rebota con un resorte —en vez de aparecer ya puesto— en dos momentos, ambos
 * "esto acaba de pasar" (CLAUDE.md §9.4: física de resorte para lo que el usuario
 * nota): cuando [celebrate] marca el check recién ganado, y cuando [unlocking]
 * marca el candado convirtiéndose en botón de jugar. Mismo mecanismo para los dos
 * porque, para el jugador, es la misma clase de aviso: "algo cambió aquí".
 */
@Composable
private fun OrderBadge(accent: Color, state: WelcomeCardState, celebrate: Boolean, unlocking: Boolean) {
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
    val shape = CircleShape

    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale.value)
            .clip(shape)
            .then(
                when {
                    // Bloqueada: disco apagado.
                    displayLocked -> Modifier.background(LogicColors.OnDarkMuted.copy(alpha = 0.14f))
                    // Jugada: disco tenue con aro del color del juego.
                    state == WelcomeCardState.DONE -> Modifier
                        .background(accent.copy(alpha = 0.22f))
                        .border(1.5.dp, accent.copy(alpha = 0.8f), shape)
                    // La que toca: disco RELLENO, un mini botón de "jugar".
                    else -> Modifier.background(
                        Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.30f), accent)),
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            displayLocked -> NeonIcon(icon = KortexIcons.Lock, tint = LogicColors.OnDarkMuted, size = 16.dp, glow = false)
            state == WelcomeCardState.DONE -> NeonIcon(icon = KortexIcons.Check, tint = accent, size = 18.dp, glow = false)
            else -> NeonIcon(
                icon = KortexIcons.Play,
                tint = LogicColors.BackgroundDark,
                size = 20.dp,
                glow = false,
            )
        }
    }
}

/** Alto de cada tramo de la barra de progreso de la sesión. */
private val ProgressSegmentHeight = 7.dp

/** Retardo del llenado del tramo recién ganado: entra cuando el check ya rebotó. */
private const val SEGMENT_FILL_DELAY_MS = 350L

/**
 * Cabecera de la lista de juegos: rótulo de la sesión, contador ("1/3") y una
 * **barra de progreso por tramos**, uno por juego y cada uno del color de su
 * categoría. Convierte "tres tarjetas" en un recorrido con final a la vista —lo que
 * antes solo insinuaban los números de las tarjetas— y hace que cada vuelta al hub
 * tras un juego tenga recompensa visible: el tramo recién ganado se llena delante
 * del jugador.
 *
 * @param justCompletedIndex juego recién despachado: su tramo se llena animado en
 *   vez de aparecer ya lleno.
 */
@Composable
private fun SessionProgress(games: List<GameInfo>, completedCount: Int, justCompletedIndex: Int?) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.firstrun_welcome_games_label),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.NeonCyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    Res.string.firstrun_welcome_progress_count,
                    completedCount.coerceIn(0, games.size).toString(),
                    games.size.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Black,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            games.forEachIndexed { index, game ->
                val accent = game.category.accent
                val done = index < completedCount
                val animateIn = index == justCompletedIndex
                // Solo el tramo recién ganado nace vacío; el resto, en su estado final.
                val fill = remember(animateIn) { Animatable(if (done && !animateIn) 1f else 0f) }
                LaunchedEffect(done, animateIn) {
                    if (done && animateIn) {
                        delay(SEGMENT_FILL_DELAY_MS)
                        fill.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
                    } else {
                        fill.snapTo(if (done) 1f else 0f)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(ProgressSegmentHeight)
                        .drawBehind {
                            val corner = CornerRadius(size.height / 2f)
                            drawRoundRect(LogicColors.SurfaceVariantDark, cornerRadius = corner)
                            // El tramo del juego en curso se insinúa con su color: se
                            // ve cuál es el siguiente antes de bajar a las tarjetas.
                            if (index == completedCount) {
                                drawRoundRect(accent.copy(alpha = 0.28f), cornerRadius = corner)
                            }
                            val f = fill.value.coerceIn(0f, 1f)
                            if (f > 0f) {
                                val filled = Size(size.width * f, size.height)
                                // Halo bajo el tramo lleno + relleno con brillo superior.
                                drawRoundRect(
                                    color = accent.copy(alpha = 0.30f),
                                    topLeft = Offset(0f, -2.dp.toPx()),
                                    size = Size(filled.width, size.height + 4.dp.toPx()),
                                    cornerRadius = CornerRadius(size.height),
                                )
                                drawRoundRect(
                                    brush = Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.35f), accent)),
                                    size = filled,
                                    cornerRadius = corner,
                                )
                            }
                        },
                )
            }
        }
    }
}
