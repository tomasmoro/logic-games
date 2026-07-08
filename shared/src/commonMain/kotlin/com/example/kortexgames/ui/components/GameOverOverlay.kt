package com.example.kortexgames.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import com.example.kortexgames.game.GameOverInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Retardo tras terminar la partida antes de revelar el diálogo. Da un "beat" de
 * respiración para que el jugador registre el estado final del juego (última
 * jugada, tablero resuelto) antes de que el modal robe la atención. CLAUDE.md
 * §9.4: la animación sirve al usuario, no lo apura.
 */
private const val REVEAL_DELAY_MS = 1000L

/**
 * Capa modal de fin de partida. Muestra puntaje, precisión y tiempo, y si hay
 * percentil (usuario autenticado) el mensaje "Eres mejor que el X% de los
 * jugadores" de la FASE 2. Botones para reintentar o salir.
 *
 * Se autogestiona la aparición: espera [REVEAL_DELAY_MS] y luego entra con una
 * animación "de juego" (resorte con rebote + fundido), de modo que las pantallas
 * solo tienen que renderizar el overlay cuando la partida termina; el timing y el
 * "juice" viven aquí, centralizados para los tres juegos.
 */
@Composable
fun GameOverOverlay(
    info: GameOverInfo,
    onPlayAgain: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    headline: String? = null,
    onNextLevel: (() -> Unit)? = null,
    onChooseLevel: (() -> Unit)? = null,
) {
    // `visible` arranca en false: durante REVEAL_DELAY_MS no se dibuja nada y la
    // pantalla de juego queda a la vista; luego dispara scrim + entrada del card.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(REVEAL_DELAY_MS)
        visible = true
    }

    // Scrim: fundido suave para no cortar de golpe.
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.78f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "scrimAlpha",
    )
    // Card: escala con rebote (sensación táctil, "con peso") + fundido. El
    // sobreimpulso del resorte es lo que le da el aire "gamey" a la entrada.
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.82f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "cardScale",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cardAlpha",
    )
    // Conteo ascendente del puntaje: pequeño detalle que hace la recompensa más
    // satisfactoria que un número que aparece plano.
    val animatedScore by animateIntAsState(
        targetValue = if (visible) info.result.score else 0,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "scoreCountUp",
    )

    // Mientras no sea visible no montamos el scrim (evita tapar el juego durante
    // el retardo).
    if (scrimAlpha <= 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .alpha(cardAlpha)
                // Borde neón en degradado cian→verde: la identidad "Juego" enmarca
                // la superficie oscura "Lógica" sin inundarla.
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
            // Trofeo con halo: remate visual de recompensa.
            NeonIcon(icon = KortexIcons.Trophy, tint = LogicColors.Amber, size = 46.dp)

            Text(
                headline ?: "¡Partida terminada!",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.OnDarkMuted,
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$animatedScore",
                    style = MaterialTheme.typography.displayLarge,
                    color = LogicColors.Electric,
                )
                Text(
                    "PUNTOS",
                    style = MaterialTheme.typography.labelLarge,
                    color = LogicColors.OnDarkMuted,
                )
            }

            // Métricas en "chips" para dar jerarquía y ritmo (no un renglón plano).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatChip(
                    label = "Precisión",
                    value = "${info.result.accuracyPercentage.roundToInt()}%",
                    accent = LogicColors.NeonGreen,
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = "Tiempo",
                    value = "${info.result.completionTimeMs / 1000}s",
                    accent = LogicColors.NeonCyan,
                    modifier = Modifier.weight(1f),
                )
            }

            // Mensaje de percentil (FASE 2) o aviso de modo invitado, destacado en
            // su propio contenedor.
            PercentileBanner(info)

            Spacer(Modifier.height(2.dp))

            if (onNextLevel != null) {
                // Juego LEVELED: el CTA principal es avanzar; luego repetir el nivel
                // y volver al selector. El único bucle (pulse) va al CTA que guía (§9.4).
                AnimatedGameButton(
                    text = "SIGUIENTE NIVEL",
                    onClick = onNextLevel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulse(),
                    gradient = LogicGradients.play,
                )
                AnimatedGameButton(
                    text = "REPETIR NIVEL",
                    onClick = onPlayAgain,
                    modifier = Modifier.fillMaxWidth(),
                    gradient = LogicGradients.energy,
                )
                if (onChooseLevel != null) {
                    Text(
                        "Elegir nivel",
                        style = MaterialTheme.typography.labelLarge,
                        color = LogicColors.OnDarkMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick(onClick = onChooseLevel)
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                AnimatedGameButton(
                    text = "JUGAR DE NUEVO",
                    onClick = onPlayAgain,
                    // Único bucle de la pantalla (pulse) reservado al CTA principal,
                    // como manda §9.4: guía la acción sin competir con otros elementos.
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulse(),
                    gradient = LogicGradients.play,
                )
                AnimatedGameButton(
                    text = "SALIR",
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                    gradient = LogicGradients.energy,
                )
            }
        }
    }
}

/**
 * Métrica individual de fin de partida (precisión/tiempo) sobre superficie
 * elevada, con el valor teñido de su color de acento neón.
 */
@Composable
private fun StatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceVariantDark)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
        )
    }
}

/**
 * Banner de comparación global (percentil) o, en invitado/offline, aviso de que
 * el resultado quedó guardado localmente y se comparará al iniciar sesión.
 */
@Composable
private fun PercentileBanner(info: GameOverInfo) {
    val percentile = info.percentile
    val shape = RoundedCornerShape(16.dp)
    if (percentile != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    // Degradado recompensa translúcido: destaca el logro sin
                    // saturar (el neón brilla porque es escaso).
                    Brush.horizontalGradient(LogicGradients.reward.map { it.copy(alpha = 0.16f) }),
                )
                .border(BorderStroke(1.dp, LogicColors.Amber.copy(alpha = 0.5f)), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonIcon(icon = KortexIcons.Trophy, tint = LogicColors.Amber, size = 22.dp)
            Text(
                "Eres mejor que el ${percentile.betterThanPct.roundToInt()}% de los jugadores",
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.Amber,
                textAlign = TextAlign.Start,
            )
        }
    } else {
        Text(
            "Guardado localmente · inicia sesión para comparar con el mundo",
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LogicColors.SurfaceVariantDark)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
