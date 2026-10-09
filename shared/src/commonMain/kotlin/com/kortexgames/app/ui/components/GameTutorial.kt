package com.kortexgames.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.data.settings.TutorialStore
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.tutorial_cta_understood
import kortexgames.shared.generated.resources.tutorial_header
import kortexgames.shared.generated.resources.tutorial_next
import kortexgames.shared.generated.resources.tutorial_previous
import kortexgames.shared.generated.resources.tutorial_replay
import kortexgames.shared.generated.resources.tutorial_skip
import kortexgames.shared.generated.resources.tutorial_step_position
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * # Tutorial animado de juego ("mira cómo se juega")
 *
 * Un diálogo que **reproduce una mini-partida guionizada** mientras un texto corto cuenta, paso
 * a paso, lo que está pasando. El jugador no toca nada: solo mira (y puede saltarlo).
 *
 * Complementa a la ayuda escrita ([GameHelpSheet]), no la sustituye: la ayuda es material de
 * consulta (pasos, consejos, diagramas); el tutorial es la demostración de 15 segundos que evita
 * tener que leerla. Para un juego de reflejos, ver el gesto una vez vale más que tres párrafos.
 *
 * ## Cómo se reparte el trabajo (lo que lo hace escalable)
 *  - **El marco es común** ([GameTutorialDialog]): tarjeta, reloj, avance de pasos, barra de
 *    progreso, texto, saltar/repetir. No conoce ningún juego.
 *  - **Cada juego aporta un [GameTutorial]**: la lista de pasos (texto + duración) y una
 *    *escena* que se dibuja a partir de la posición en la línea de tiempo ([TutorialPlayback]).
 *
 * La escena es una **función pura del tiempo**: no guarda estado ni lanza animaciones propias,
 * solo responde "¿qué se ve en el segundo t?". Eso es lo que permite repetir, saltar o congelar
 * el tutorial sin que ninguna escena tenga que saber hacerlo, y que el texto y la animación no
 * puedan desincronizarse (los dos salen del mismo reloj).
 *
 * ## Añadir el tutorial de otro juego
 *  1. Escribe los textos en `strings.xml` (`<juego>_tutorial_step_N`).
 *  2. Crea su `GameTutorial` junto al juego (ver `game/hypergate/HypergateTutorial.kt`),
 *     reutilizando las funciones de dibujo que el juego ya tiene para que el tutorial se vea
 *     idéntico a la partida real. Para señalar un toque, [drawTutorialTap]; para una pulsación
 *     mantenida, [drawTutorialHold]; para un arrastre, [drawTutorialDrag].
 *  3. Pásalo a la antesala: `GameIntroScreen(tutorial = …)`. La primera apertura automática y
 *     el botón "Ver tutorial" salen solos.
 */

/**
 * Un **paso** del tutorial: el tramo de animación durante el que se lee un mismo texto.
 *
 * @property text frase que explica lo que se está viendo.
 * @property durationMs cuánto dura **la animación** del tramo. Es el tiempo que ve la escena.
 * @property holdMs pausa tras la animación, con la escena detenida en el final del tramo y el
 *   texto aún en pantalla, antes de pasar al siguiente. Por defecto dura lo mismo que la
 *   animación: el jugador mira primero lo que pasa y después tiene tiempo de leer por qué, en
 *   vez de tener que hacer las dos cosas a la vez. Alargar el paso así, y no ralentizando la
 *   animación, mantiene los movimientos a la velocidad a la que se ven en la partida.
 */
data class TutorialStep(
    val text: StringResource,
    val durationMs: Int = 2_600,
    val holdMs: Int = durationMs,
)

/**
 * **Tutorial animado** de un juego: lo que cada juego inyecta en el [GameTutorialDialog] genérico.
 *
 * @property gameId id del juego (`GameIds`); es la clave con la que [TutorialStore] recuerda que
 *   ya se vio.
 * @property accent color de acento del diálogo (borde, barra de progreso); el de la categoría.
 * @property steps pasos en orden. La línea de tiempo es la suma de sus duraciones.
 * @property scene dibujo de la mini-partida. Recibe el [TutorialPlayback] y debe leerlo **en la
 *   fase de dibujo** (dentro de un `Canvas`), no en la composición: así cada frame redibuja sin
 *   recomponer. El `Modifier` que recibe ya trae el tamaño; la escena solo lo aplica.
 */
data class GameTutorial(
    val gameId: String,
    val accent: Color,
    val steps: List<TutorialStep>,
    val scene: @Composable (playback: TutorialPlayback, modifier: Modifier) -> Unit,
)

/**
 * Almacén de tutoriales vistos, publicado para toda la navegación. Lo consume la antesala
 * ([GameIntroScreen]), a la que no se le puede inyectar por parámetro sin obligar a los ~25
 * juegos que la montan a pasarlo uno a uno (mismo motivo que `LocalFirstRunFlow`).
 *
 * `null` (previews, tests) = no hay apertura automática; el botón "Ver tutorial" sigue funcionando.
 */
val LocalTutorialStore = staticCompositionLocalOf<TutorialStore?> { null }

/**
 * **Reproductor** de un tutorial: la posición actual en su línea de tiempo.
 *
 * Lleva tres tiempos porque responden a preguntas distintas:
 *  - [timelineSec] es el **guion**: lo que ve la escena. Avanza durante la animación de cada paso
 *    y se detiene durante su pausa ([TutorialStep.holdMs]). De él sale todo lo que "pasa".
 *  - la **posición de reproducción** (interna) es el guion más las pausas: decide cuándo se cambia
 *    de paso y cuánto se ha llenado la barra de progreso.
 *  - [clockSec] es el **ambiente**: no se detiene nunca. De él salen los bucles decorativos (un
 *    fondo que se mueve, un halo que respira), para que la escena no se quede congelada como una
 *    foto durante las pausas ni al terminar.
 *
 * [timelineSec], [clockSec] y [stepProgress] son estado observable pensado para leerse **al
 * dibujar**. [stepIndex] e [isFinished] solo cambian en las fronteras entre pasos, así que se
 * pueden leer en composición.
 *
 * Como las escenas son función pura de [timelineSec], saltar a cualquier paso ([next],
 * [previous]) es simplemente mover la posición: ninguna escena necesita saber retroceder.
 */
class TutorialPlayback internal constructor(private val steps: List<TutorialStep>) {
    /** Inicio de cada paso en el guion (s), más el final como último elemento. */
    private val starts: FloatArray = FloatArray(steps.size + 1).also { acc ->
        steps.forEachIndexed { i, step -> acc[i + 1] = acc[i] + step.durationMs / 1000f }
    }

    /** Inicio de cada paso en la reproducción (guion + pausas), más el final. */
    private val playStarts: FloatArray = FloatArray(steps.size + 1).also { acc ->
        steps.forEachIndexed { i, step -> acc[i + 1] = acc[i] + (step.durationMs + step.holdMs) / 1000f }
    }
    private val position = mutableFloatStateOf(0f)
    private val clock = mutableFloatStateOf(0f)
    private val index = mutableIntStateOf(0)
    private val finished = mutableStateOf(steps.isEmpty())

    /** Duración total del guion (s), sin contar las pausas. */
    val totalSec: Float get() = starts.last()

    /** Segundos transcurridos del guion, en `0..totalSec`. Leer al dibujar. */
    val timelineSec: Float
        get() {
            if (steps.isEmpty()) return 0f
            val step = stepAt(position.floatValue)
            // Dentro del paso, el guion avanza hasta el final de la animación y ahí espera.
            return (starts[step] + position.floatValue - playStarts[step]).coerceAtMost(starts[step + 1])
        }

    /** Segundos desde que se abrió el tutorial, sin tope. Para animación ambiental. Leer al dibujar. */
    val clockSec: Float get() = clock.floatValue

    /** Paso en curso (el último, una vez terminado). */
    val stepIndex: Int get() = index.intValue

    /** `true` cuando la reproducción llegó al final. */
    val isFinished: Boolean get() = finished.value

    /** Segundo del guion en que empieza el paso [step]. */
    fun stepStartSec(step: Int): Float = starts[step.coerceIn(0, steps.size)]

    /** Duración de la animación del paso [step] (s), sin su pausa. */
    fun stepDurationSec(step: Int): Float = stepStartSec(step + 1) - stepStartSec(step)

    /**
     * Avance `0..1` de la animación del paso [step]: 0 mientras no ha llegado, 1 cuando ya pasó
     * (también durante su pausa). Es la lectura habitual de una escena ("¿cuánto lleva recorrido
     * el cometa en este tramo?").
     */
    fun stepProgress(step: Int): Float {
        val duration = stepDurationSec(step)
        if (duration <= 0f) return 1f
        return ((timelineSec - stepStartSec(step)) / duration).coerceIn(0f, 1f)
    }

    /** Avance `0..1` del paso [step] contando su pausa: lo que marca la barra de progreso. */
    internal fun stepPlayProgress(step: Int): Float {
        val length = playStarts[step + 1] - playStarts[step]
        if (length <= 0f) return 1f
        return ((position.floatValue - playStarts[step]) / length).coerceIn(0f, 1f)
    }

    /** Paso al que pertenece la posición de reproducción [at]. */
    private fun stepAt(at: Float): Int {
        var i = 0
        while (i < steps.lastIndex && at >= playStarts[i + 1]) i++
        return i
    }

    private fun moveTo(at: Float) {
        val clamped = at.coerceIn(0f, playStarts.last())
        position.floatValue = clamped
        index.intValue = stepAt(clamped)
        finished.value = clamped >= playStarts.last()
    }

    internal fun advance(dt: Float) {
        clock.floatValue += dt
        if (!finished.value) moveTo(position.floatValue + dt)
    }

    /** Vuelve al principio (el reloj ambiental sigue, para que el fondo no dé un salto). */
    internal fun restart() = moveTo(0f)

    /** Salta al principio del paso siguiente; desde el último, al final. */
    internal fun next() = moveTo(playStarts[(stepIndex + 1).coerceAtMost(steps.size)])

    /**
     * Vuelve atrás un paso. Con la reproducción terminada vuelve al principio del último, que es
     * el que el jugador tiene delante: "anterior" no debe saltarse el paso que estaba mirando.
     */
    internal fun previous() = moveTo(playStarts[if (finished.value) steps.lastIndex.coerceAtLeast(0) else (stepIndex - 1).coerceAtLeast(0)])
}

/**
 * # Diálogo del tutorial animado
 *
 * Overlay modal **genérico** que reproduce el [GameTutorial] que le inyecta cualquier juego:
 * escena animada arriba, barra de progreso por pasos, y el texto del paso en curso debajo.
 *
 * **Es para mirar, no para jugar**: la escena no recibe gestos y tocar fuera de la tarjeta no la
 * cierra (un toque de más se llevaría por delante lo que el jugador estaba leyendo). Las únicas
 * salidas son deliberadas: "Saltar tutorial" mientras se reproduce, "¡Entendido!" al acabar, o
 * el gesto de atrás.
 *
 * **Avanza solo, pero el ritmo es del jugador**: cada paso reproduce su animación y espera un
 * rato antes de seguir, y las flechas bajo el texto permiten pasar al siguiente sin esperar o
 * volver al anterior para verlo otra vez. Nadie lee a la misma velocidad.
 *
 * Al terminar el guion la escena se queda en su estado final y se ofrece repetirlo, en vez de
 * cerrarse sola o entrar en bucle: cerrarse sola quitaría el último texto de delante a medio
 * leer, y un bucle no dejaría claro cuándo "ya lo has visto todo".
 *
 * Comparte scrim, tarjeta y física de entrada con el menú de pausa y el fin de partida (§9.4).
 *
 * @param tutorial guion y escena a reproducir.
 * @param visible si el diálogo está abierto; cada apertura reproduce desde el principio.
 * @param onClose cierra el diálogo (saltar, entendido o atrás; la pantalla no los distingue).
 */
@Composable
fun BoxScope.GameTutorialDialog(
    tutorial: GameTutorial,
    visible: Boolean,
    onClose: () -> Unit,
) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.86f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "tutorialScrim",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "tutorialCardScale",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "tutorialCardAlpha",
    )

    val playback = remember(tutorial) { TutorialPlayback(tutorial.steps) }
    // El reloj solo corre con el diálogo abierto, y cada apertura empieza de cero.
    LaunchedEffect(visible, playback) {
        if (!visible) return@LaunchedEffect
        playback.restart()
        var last = withFrameNanos { it }
        while (true) {
            val nanos = withFrameNanos { it }
            // Tope por frame: al volver de segundo plano el guion no "salta" varios pasos.
            playback.advance(((nanos - last) / 1_000_000_000f).coerceAtMost(0.05f))
            last = nanos
        }
    }

    PlatformBackHandler(enabled = visible, onBack = onClose)

    // Mientras el scrim es transparente no montamos nada: ni tapa la antesala ni roba toques.
    if (scrimAlpha <= 0f) return

    Box(
        modifier = Modifier
            .matchParentSize()
            .modalScrim(scrimAlpha)
            // Bloquea los toques del fondo, pero NO cierra (ver KDoc).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .scale(cardScale)
                .alpha(cardAlpha)
                .modalCard(tutorial.accent)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.tutorial_header),
                style = MaterialTheme.typography.labelLarge,
                color = tutorial.accent,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))

            // --- Escena: la mini-partida guionizada ------------------------------------
            // Recortada: las escenas dibujan piezas que entran desde fuera del recuadro.
            // Sin semántica propia: lo que cuenta la animación ya lo dice el texto del paso.
            val sceneShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(SCENE_ASPECT)
                    .clip(sceneShape)
                    .background(LogicColors.BackgroundDark)
                    .border(BorderStroke(1.dp, tutorial.accent.copy(alpha = 0.35f)), sceneShape)
                    .clearAndSetSemantics {},
            ) {
                tutorial.scene(playback, Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(14.dp))
            TutorialProgress(playback = playback, steps = tutorial.steps.size, accent = tutorial.accent)
            Spacer(Modifier.height(14.dp))

            // --- Texto del paso en curso ------------------------------------------------
            // Alto mínimo de ~3 líneas: pasar de un texto corto a uno largo no debe mover los
            // botones de debajo justo cuando el jugador va a pulsarlos.
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = playback.stepIndex,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                    label = "tutorialCaption",
                ) { step ->
                    Text(
                        tutorial.steps.getOrNull(step)?.let { stringResource(it.text) }.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LogicColors.OnDark,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TutorialStepControls(playback = playback, steps = tutorial.steps.size)
            Spacer(Modifier.height(12.dp))

            // --- Acciones ---------------------------------------------------------------
            // Mientras se reproduce, salir es secundario (un botón apagado); al terminar pasa
            // a ser la acción principal. Los dos ocupan lo mismo para que el cambio no salte.
            if (playback.isFinished) {
                AnimatedGameButton(
                    onClick = onClose,
                    gradient = LogicGradients.play,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    Text(
                        stringResource(Res.string.tutorial_cta_understood),
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.BackgroundDark,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            } else {
                val skipShape = RoundedCornerShape(20.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(skipShape)
                        .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.6f))
                        .border(BorderStroke(1.dp, LogicColors.OnDarkMuted.copy(alpha = 0.35f)), skipShape)
                        .bounceClick(onClick = onClose)
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Res.string.tutorial_skip),
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.OnDark,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // "Ver de nuevo" reserva su sitio siempre (invisible e inerte hasta el final) por
            // el mismo motivo: que aparezca no debe estirar la tarjeta.
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .alpha(if (playback.isFinished) 1f else 0f)
                    .bounceClick(enabled = playback.isFinished, onClick = playback::restart)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NeonIcon(icon = KortexIcons.Refresh, tint = LogicColors.OnDarkMuted, size = 18.dp, glow = false)
                Text(
                    stringResource(Res.string.tutorial_replay),
                    style = MaterialTheme.typography.labelLarge,
                    color = LogicColors.OnDarkMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Proporción (ancho/alto) del recuadro de la escena: algo apaisado, para que la tarjeta quepa en pantallas bajas. */
private const val SCENE_ASPECT = 1.15f

/**
 * Barra de progreso **por pasos** (un segmento por paso, que se llena mientras dura): dice de un
 * vistazo cuánto queda y marca el cambio de texto. El relleno incluye la pausa de cada paso (es
 * lo que falta para que cambie, no lo que le queda a la animación) y se lee al dibujar, así que
 * avanza sin recomponer.
 */
@Composable
private fun TutorialProgress(playback: TutorialPlayback, steps: Int, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(steps) { step ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(LogicColors.SurfaceVariantDark)
                    .drawBehind {
                        drawRoundRect(
                            color = accent,
                            size = Size(size.width * playback.stepPlayProgress(step), size.height),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    },
            )
        }
    }
}

/**
 * Controles de paso: **anterior · "Paso 3 de 8" · siguiente**. Apagados cuando no llevan a ningún
 * sitio (anterior en el primer paso, siguiente una vez terminado), en vez de desaparecer, para
 * que la fila no se recoloque bajo el dedo.
 */
@Composable
private fun TutorialStepControls(playback: TutorialPlayback, steps: Int) {
    val canGoBack = playback.stepIndex > 0 || playback.isFinished
    val canGoForward = !playback.isFinished
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TutorialStepButton(
            description = stringResource(Res.string.tutorial_previous),
            enabled = canGoBack,
            // Espejado horizontal → apunta a la izquierda.
            iconModifier = Modifier.graphicsLayer(scaleX = -1f),
            onClick = playback::previous,
        )
        Text(
            stringResource(
                Res.string.tutorial_step_position,
                (playback.stepIndex + 1).toString(),
                steps.toString(),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.OnDarkMuted,
        )
        TutorialStepButton(
            description = stringResource(Res.string.tutorial_next),
            enabled = canGoForward,
            onClick = playback::next,
        )
    }
}

/** Flecha redonda de [TutorialStepControls]. 44 dp: el mínimo cómodo para el dedo. */
@Composable
private fun TutorialStepButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(CircleShape)
            .background(LogicColors.SurfaceVariantDark)
            .bounceClick(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = KortexIcons.ChevronRight,
            tint = LogicColors.OnDark,
            size = 24.dp,
            glow = false,
            modifier = iconModifier,
        )
    }
}

/**
 * Momento, dentro del avance `0..1` de [drawTutorialTap], en que el dedo **pulsa**. Las escenas
 * lo usan para disparar en ese mismo instante lo que el toque provoca en el juego.
 */
const val TUTORIAL_TAP_AT = 0.5f

/** Pintor del "dedo fantasma" de [drawTutorialTap]. Se crea en composición y se usa al dibujar. */
@Composable
fun rememberTutorialTapPainter(): VectorPainter = rememberVectorPainter(KortexIcons.Tap)

/**
 * Dibuja un **toque simulado**: un dedo que entra, pulsa en [at] y se retira, con una onda que
 * marca el punto pulsado. Es la forma común de decir "aquí tocarías tú" en cualquier tutorial,
 * para que todos los juegos señalen el gesto con el mismo lenguaje.
 *
 * El dedo es blanco y no del color de acento a propósito: no es una pieza del juego, es la mano
 * del jugador, y tiene que distinguirse de lo que hay en el tablero.
 *
 * @param at punto que se pulsa (la yema del dedo acaba exactamente ahí).
 * @param progress avance `0..1` del gesto completo; el toque ocurre en [TUTORIAL_TAP_AT]. Fuera
 *   de `0..1` (exclusivo) no dibuja nada, así que se le puede pasar directamente
 *   [TutorialPlayback.stepProgress] del paso que enseña el toque.
 * @param finger pintor de [rememberTutorialTapPainter].
 * @param ripple color de la onda del toque; normalmente el de lo que se está pulsando.
 */
fun DrawScope.drawTutorialTap(at: Offset, progress: Float, finger: VectorPainter, ripple: Color) {
    if (progress <= 0f || progress >= 1f) return
    val iconSize = 44.dp.toPx()

    // Onda del toque: nace al pulsar y se abre apagándose.
    val wave = (progress - TUTORIAL_TAP_AT) / 0.34f
    if (wave in 0f..1f) {
        val ease = 1f - (1f - wave) * (1f - wave)
        drawCircle(
            color = ripple.copy(alpha = 0.85f * (1f - wave)),
            radius = iconSize * (0.25f + 0.85f * ease),
            center = at,
            style = Stroke(width = 3.dp.toPx() * (1f - wave) + 1f),
        )
        drawCircle(Color.White.copy(alpha = 0.30f * (1f - wave)), iconSize * 0.25f, at)
    }

    // Dedo: entra en diagonal (0→0.35), se hunde al pulsar y se va apagando (0.8→1).
    val arrive = (progress / 0.35f).coerceIn(0f, 1f)
    val approach = 1f - (1f - arrive) * (1f - arrive)
    val fade = arrive * (1f - ((progress - 0.8f) / 0.2f).coerceIn(0f, 1f))
    val press = (1f - kotlin.math.abs(progress - TUTORIAL_TAP_AT) / 0.1f).coerceIn(0f, 1f)
    drawTutorialFinger(at, finger, iconSize * (1f - 0.14f * press), away = iconSize * 0.7f * (1f - approach), alpha = fade)
}

/** Avance de [drawTutorialHold] en el que el dedo **empieza a pulsar**. */
const val TUTORIAL_HOLD_FROM = 0.2f

/** Avance de [drawTutorialHold] en el que el dedo **suelta**. */
const val TUTORIAL_HOLD_TO = 0.85f

/**
 * Dibuja una **pulsación mantenida** simulada: el dedo entra, pulsa en [at], se queda apretando
 * —con un aro que late mientras dura, para que se lea "sigue pulsado" y no "tocó y se quedó ahí"—
 * y se retira. Hermano de [drawTutorialTap] para los gestos de mantener.
 *
 * @param progress avance `0..1` del gesto; aprieta entre [TUTORIAL_HOLD_FROM] y [TUTORIAL_HOLD_TO].
 *   Fuera de `0..1` (exclusivo) no dibuja nada.
 * @param time reloj ambiental ([TutorialPlayback.clockSec]) que mueve el latido del aro.
 */
fun DrawScope.drawTutorialHold(at: Offset, progress: Float, finger: VectorPainter, ripple: Color, time: Float) {
    if (progress <= 0f || progress >= 1f) return
    val iconSize = 44.dp.toPx()
    val pressed = progress in TUTORIAL_HOLD_FROM..TUTORIAL_HOLD_TO

    if (pressed) {
        val beat = 0.5f + 0.5f * kotlin.math.sin(time * 7f)
        drawCircle(ripple.copy(alpha = 0.30f), iconSize * 0.42f, at)
        drawCircle(
            color = ripple.copy(alpha = 0.55f + 0.35f * beat),
            radius = iconSize * (0.46f + 0.10f * beat),
            center = at,
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    val arrive = (progress / (TUTORIAL_HOLD_FROM * 0.9f)).coerceIn(0f, 1f)
    val approach = 1f - (1f - arrive) * (1f - arrive)
    val leave = ((progress - TUTORIAL_HOLD_TO) / (1f - TUTORIAL_HOLD_TO)).coerceIn(0f, 1f)
    drawTutorialFinger(
        at = at,
        finger = finger,
        drawn = iconSize * if (pressed) 0.86f else 1f,
        away = iconSize * 0.7f * (1f - approach) + iconSize * 0.4f * leave,
        alpha = arrive * (1f - leave),
    )
}

/** Avance de [drawTutorialDrag] en el que el dedo, ya apoyado, **empieza a moverse**. */
const val TUTORIAL_DRAG_FROM = 0.3f

/** Avance de [drawTutorialDrag] en el que el dedo **deja de moverse** (aún sin soltar). */
const val TUTORIAL_DRAG_TO = 0.75f

/**
 * Fracción `0..1` del recorrido de [drawTutorialDrag] ya hecha en el avance [progress], con
 * arranque y frenada suaves. Es pública para que la escena mueva con el MISMO valor lo que el
 * dedo arrastra (un disco que gira, una pieza que se desliza): así no pueden desacompasarse.
 */
fun tutorialDragFraction(progress: Float): Float {
    val p = ((progress - TUTORIAL_DRAG_FROM) / (TUTORIAL_DRAG_TO - TUTORIAL_DRAG_FROM)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/**
 * Dibuja un **arrastre** simulado: el dedo entra, se apoya, recorre [path] dejando una estela y
 * se retira. Hermano de [drawTutorialTap] y [drawTutorialHold] para los gestos de deslizar.
 *
 * @param progress avance `0..1` del gesto. Se apoya en [TUTORIAL_HOLD_FROM], se mueve entre
 *   [TUTORIAL_DRAG_FROM] y [TUTORIAL_DRAG_TO] y suelta en [TUTORIAL_HOLD_TO]. Fuera de `0..1`
 *   (exclusivo) no dibuja nada.
 * @param path posición del dedo para cada fracción `0..1` del recorrido. Es una función y no dos
 *   puntos porque el recorrido no siempre es recto (p. ej. un arco para girar algo).
 */
fun DrawScope.drawTutorialDrag(progress: Float, finger: VectorPainter, ripple: Color, path: (fraction: Float) -> Offset) {
    if (progress <= 0f || progress >= 1f) return
    val iconSize = 44.dp.toPx()
    val pressed = progress in TUTORIAL_HOLD_FROM..TUTORIAL_HOLD_TO
    val travelled = tutorialDragFraction(progress)
    val at = path(travelled)

    if (pressed) {
        // Estela: el tramo recién recorrido, apagándose hacia atrás. Dice "se arrastra", no "salta".
        val steps = 10
        for (i in 1..steps) {
            val back = travelled - i * 0.035f
            if (back < 0f) break
            drawCircle(ripple.copy(alpha = 0.50f * (1f - i / (steps + 1f))), iconSize * 0.11f, path(back))
        }
        drawCircle(ripple.copy(alpha = 0.30f), iconSize * 0.42f, at)
        drawCircle(ripple.copy(alpha = 0.85f), iconSize * 0.48f, at, style = Stroke(width = 2.5.dp.toPx()))
    }

    val arrive = (progress / (TUTORIAL_HOLD_FROM * 0.9f)).coerceIn(0f, 1f)
    val approach = 1f - (1f - arrive) * (1f - arrive)
    val leave = ((progress - TUTORIAL_HOLD_TO) / (1f - TUTORIAL_HOLD_TO)).coerceIn(0f, 1f)
    drawTutorialFinger(
        at = at,
        finger = finger,
        drawn = iconSize * if (pressed) 0.86f else 1f,
        away = iconSize * 0.7f * (1f - approach) + iconSize * 0.4f * leave,
        alpha = arrive * (1f - leave),
    )
}

/**
 * El dedo de los gestos simulados, con la yema anclada en [at].
 *
 * @param drawn lado del glifo en píxeles (menor cuando "aprieta").
 * @param away cuánto se separa en diagonal (abajo-derecha) del punto: 0 = tocándolo.
 */
private fun DrawScope.drawTutorialFinger(at: Offset, finger: VectorPainter, drawn: Float, away: Float, alpha: Float) {
    // La yema del glifo `TouchApp` cae en ~(0.38, 0.10) de su caja: ese punto es el que se ancla.
    translate(left = at.x - drawn * 0.38f + away, top = at.y - drawn * 0.10f + away) {
        with(finger) {
            draw(size = Size(drawn, drawn), alpha = alpha, colorFilter = ColorFilter.tint(Color.White))
        }
    }
}
