package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * # Segunda oportunidad viendo un anuncio (rewarded)
 *
 * Overlay **reutilizable** que cualquier minijuego puede mostrar cuando el jugador
 * pierde, ofreciéndole **continuar la partida a cambio de ver un anuncio** (p. ej.
 * recibir una vida extra en "Burbujas de Cálculo"). Centraliza aquí el "trato" —
 * mismo lenguaje visual, misma cuenta atrás, misma orquestación del anuncio— para
 * que ningún juego lo reimplemente: basta con renderizarlo cuando su estado indique
 * "esperando decisión de revivir" y cablearlo con dos callbacks.
 *
 * ## Máquina de estados interna
 *  - **OFFER**: muestra el trato con una **cuenta atrás** (anillo que se vacía). El
 *    tiempo corre a favor de la retención: si el jugador no decide, se rechaza solo
 *    (llama a [onDecline]) y el juego cae a su fin de partida normal — la oferta no
 *    bloquea indefinidamente.
 *  - **LOADING**: tras pulsar "Ver anuncio" se solicita el rewarded a [adManager] y
 *    se espera; la cuenta atrás se detiene (el jugador ya decidió).
 *  - Al resolverse: [RewardResult.EARNED] → [onRevive]; cualquier otro resultado
 *    (cerró el anuncio, no había disponible) → [onDecline]. Un usuario premium
 *    obtiene la recompensa sin anuncio (lo resuelve [AdManager.showRewardedAd]).
 *
 * Al igual que [GameOverOverlay] y [GamePauseControls], se autogestiona la aparición
 * con "física de juego" (scrim en fundido + tarjeta que entra con resorte y rebote) y
 * su scrim **bloquea** la interacción con el tablero de debajo mientras decide.
 *
 * @param adManager gestor de anuncios; provee el rewarded real (o premium sin anuncio).
 * @param onRevive se invoca cuando el anuncio concede la recompensa: el juego debe
 *        continuar la partida (p. ej. sumar una vida y reanudar). **Una sola vez**:
 *        limitar la oferta a una por partida es responsabilidad del juego.
 * @param onDecline se invoca al rechazar, agotarse la cuenta atrás o fallar el anuncio:
 *        el juego debe proceder a su fin de partida normal.
 * @param rewardLabel qué se gana, en minúsculas para encajar en la frase por defecto
 *        (p. ej. "una vida extra", "un tubo más"). Personaliza el trato por juego. Se
 *        ignora si se pasa un [body] propio.
 * @param body texto explicativo del trato. Por defecto, la frase de "revivir" derivada
 *        de [rewardLabel]; se sobreescribe cuando el trato NO es revivir y la frase
 *        genérica no aplica (p. ej. una pista que revela un número, no una vida).
 * @param icon icono central dentro del anillo de cuenta atrás. Por defecto un corazón
 *        (semántica de "vida" al revivir); se cambia según el trato (p. ej. una bombilla
 *        de pista) para no prometer una vida que la oferta no da.
 * @param accent color de acento (anillo de cuenta atrás y halo del icono).
 * @param audio manager para el feedback inmediato (tap/recompensa); opcional.
 * @param countdownSeconds segundos de la oferta antes de auto-rechazar.
 */
@Composable
fun ReviveAdOverlay(
    adManager: AdManager,
    onRevive: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "¿Otra oportunidad?",
    rewardLabel: String = "una vida extra",
    body: String = "Mira un anuncio y continúa con $rewardLabel.",
    icon: ImageVector = KortexIcons.Heart,
    accent: Color = LogicColors.NeonGreen,
    audio: AudioAndHapticManager? = null,
    countdownSeconds: Int = 6,
) {
    val scope = rememberCoroutineScope()

    // Fase interna. Solo avanza OFFER → LOADING (una dirección): una vez el jugador
    // decide ver el anuncio ya no vuelve a la oferta.
    var loading by remember { mutableStateOf(false) }

    // Entrada: aparece enseguida (un pequeño beat para que el último feedback de la
    // derrota se registre) y luego dispara scrim + tarjeta.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Beat corto (no el segundo completo de GameOverOverlay): revivir es una
        // oferta con urgencia, debe sentirse inmediata.
        delay(450)
        visible = true
    }

    // Cuenta atrás: anima 1→0 de forma lineal durante la oferta. Al terminar,
    // auto-rechaza. Se detiene en cuanto el jugador pasa a LOADING (key = loading).
    val progress = remember { Animatable(1f) }
    LaunchedEffect(loading, visible) {
        if (loading || !visible) return@LaunchedEffect
        // Reanuda desde donde iba (progress.value), no reinicia, por si recompone.
        val remainingMs = (progress.value * countdownSeconds * 1000).toInt()
        progress.animateTo(0f, tween(remainingMs, easing = LinearEasing))
        if (!loading) onDecline()
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.82f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "reviveScrim",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.82f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "reviveCardScale",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "reviveCardAlpha",
    )

    if (scrimAlpha <= 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            // Bloquea la interacción con el tablero de debajo mientras se decide.
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
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Icono dentro del anillo de cuenta atrás: comunica de un vistazo el trato
            // (corazón = vida al revivir; otro icono según el juego) + "te queda poco
            // tiempo para decidir".
            CountdownIcon(
                progress = progress.value,
                totalSeconds = countdownSeconds,
                accent = accent,
                icon = icon,
            )

            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(2.dp))

            if (loading) {
                // Puente mientras se carga/reproduce el rewarded (el anuncio real toma
                // la pantalla completa; esto solo cubre la transición).
                LoadingRow(accent = accent)
            } else {
                // CTA principal: ver el anuncio. Único bucle de atención (pulse), como §9.4.
                AnimatedGameButton(
                    onClick = {
                        audio?.playSound(SoundEffect.TAP)
                        audio?.hapticFeedback(HapticFeedback.LIGHT)
                        loading = true
                        scope.launch {
                            when (adManager.showRewardedAd()) {
                                RewardResult.EARNED -> {
                                    audio?.playSound(SoundEffect.LEVEL_UP)
                                    audio?.hapticFeedback(HapticFeedback.SUCCESS)
                                    onRevive()
                                }
                                // Cerró el anuncio o no había: fin de partida normal.
                                RewardResult.DISMISSED,
                                RewardResult.UNAVAILABLE -> onDecline()
                            }
                        }
                    },
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
                            icon = KortexIcons.RewardedAd,
                            tint = LogicColors.BackgroundDark,
                            size = 22.dp,
                            glow = false,
                        )
                        Text(
                            "VER ANUNCIO",
                            style = MaterialTheme.typography.titleMedium,
                            color = LogicColors.BackgroundDark,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }

                // Rechazo explícito: texto discreto (no compite con el CTA).
                Text(
                    "No, gracias",
                    style = MaterialTheme.typography.labelLarge,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick(onClick = {
                            audio?.playSound(SoundEffect.TAP)
                            onDecline()
                        })
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** Alto/ancho del anillo de cuenta atrás con el corazón centrado. */
private val CountdownRingSize = 96.dp

/**
 * [icon] del trato rodeado por un **anillo de cuenta atrás** que se vacía con
 * [progress] (1→0). El arco restante va en [accent] con extremos redondeados; el
 * "hueco" ya consumido queda como pista tenue de superficie elevada. En el centro,
 * un [NeonIcon] con halo. Comunica sin texto: "esto te da [el premio], pero el tiempo
 * corre". El icono lo elige el juego (corazón para revivir, otro para tratos distintos).
 *
 * @param progress fracción de tiempo restante (1 = recién ofrecido, 0 = agotado).
 * @param totalSeconds segundos totales de la oferta; escala el rótulo numérico.
 * @param icon icono central del trato (ver [ReviveAdOverlay]).
 */
@Composable
private fun CountdownIcon(progress: Float, totalSeconds: Int, accent: Color, icon: ImageVector) {
    Box(
        modifier = Modifier.size(CountdownRingSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // Pista del anillo (tiempo ya consumido): tenue, para dar contorno completo.
            drawArc(
                color = LogicColors.SurfaceVariantDark,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Arco restante: arranca arriba (-90°) y se vacía en sentido horario.
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        // Segundos restantes bajo el corazón: refuerzo numérico de la urgencia.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NeonIcon(icon = icon, tint = accent, size = 34.dp)
            Text(
                "${ceil(progress * totalSeconds).toInt().coerceAtLeast(0)}",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Fila de "cargando anuncio": un indicador de progreso y el rótulo. Se muestra
 * mientras se resuelve el rewarded (antes de que el anuncio real tome la pantalla).
 */
@Composable
private fun LoadingRow(accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceVariantDark)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(22.dp),
        )
        Text(
            "Cargando anuncio…",
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
        )
    }
}
