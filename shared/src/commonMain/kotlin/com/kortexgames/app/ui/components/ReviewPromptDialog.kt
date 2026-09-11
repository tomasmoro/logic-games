package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.review_prompt_accept
import kortexgames.shared.generated.resources.review_prompt_benefit_grow
import kortexgames.shared.generated.resources.review_prompt_benefit_shape
import kortexgames.shared.generated.resources.review_prompt_body
import kortexgames.shared.generated.resources.review_prompt_decline
import kortexgames.shared.generated.resources.review_prompt_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Retardo entre la entrada de una estrella y la siguiente (ms). */
private const val STAR_STEP_MS = 70L

/** Estrellas de la fila decorativa: las cinco de una valoración máxima. */
private const val STAR_COUNT = 5

/** Tamaño del glifo de cada estrella. */
private val STAR_SIZE = 26.dp

/**
 * Huella fija que ocupa cada estrella en la fila, halo incluido.
 *
 * Es la pieza clave para que las cinco midan **exactamente lo mismo**: [NeonIcon] se
 * dimensiona por su halo (~1.9x el glifo), así que cinco estrellas sueltas pedían más
 * ancho del que tiene el diálogo y `Row` repartía las sobras a la última, que salía
 * encogida. Con una caja de tamaño fijo el reparto deja de depender del espacio
 * disponible, y de paso el halo se ajusta a ella en vez de imponer el ancho.
 */
private val STAR_SLOT = 34.dp

/**
 * **Invitación a valorar la app** en la tienda de la plataforma.
 *
 * Cuándo aparece —y cuántas veces— lo decide
 * [com.kortexgames.app.core.review.ReviewPromptPolicy]; este composable solo pinta la
 * propuesta. Abrir la ficha es cosa del llamante ([onAccept]), que dispone del enlace
 * en [com.kortexgames.app.core.review.ReviewPromptManager.storeLink].
 *
 * Decisiones de diseño (CLAUDE.md §9):
 *
 *  - Hermana visual de [NotificationPrimingDialog]: misma caja, mismo ritmo, mismo
 *    único CTA con degradado. Las dos peticiones que la app le hace al jugador se
 *    presentan igual, así que la segunda ya le resulta familiar.
 *  - Las cinco estrellas entran **escalonadas** al abrirse: es el único adorno, y da
 *    el "guiño" de valoración sin escribir la palabra reseña en ningún sitio.
 *  - **No se pregunta primero si le gusta la app.** Filtrar por respuesta ("¿te
 *    gusta? sí/no" y solo mandar a la tienda a los síes) es exactamente lo que
 *    prohíbe la política de reseñas de Google Play, además de ser deshonesto con la
 *    nota de la ficha. Aquí se invita a opinar y quien opina decide qué escribe.
 *  - `onDismissRequest` cuenta como "ahora no": tocar fuera es una respuesta.
 *
 * @param onAccept el usuario quiere opinar; el llamante abre la ficha de la tienda.
 * @param onDecline "ahora no" (o descartar tocando fuera).
 */
@Composable
fun ReviewPromptDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(onDismissRequest = onDecline) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StarRow()

            Text(
                stringResource(Res.string.review_prompt_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                stringResource(Res.string.review_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewBenefitRow(
                    icon = KortexIcons.Trophy,
                    tint = LogicColors.NeonGreen,
                    text = stringResource(Res.string.review_prompt_benefit_grow),
                )
                ReviewBenefitRow(
                    icon = KortexIcons.Check,
                    tint = LogicColors.NeonCyan,
                    text = stringResource(Res.string.review_prompt_benefit_shape),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(LogicGradients.play))
                    .bounceClick(onClick = onAccept),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.review_prompt_accept),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LogicColors.BackgroundDark,
                )
            }

            Text(
                stringResource(Res.string.review_prompt_decline),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                // Centrado de verdad: ocupa todo el ancho (para que el área pulsable sea
                // cómoda) y es el `textAlign` —no el `align` del Column, que con
                // `fillMaxWidth` no tiene nada que decidir— quien centra el texto.
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onDecline)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Las cinco estrellas de cabecera, encendiéndose de izquierda a derecha con un
 * resorte. Es decorativa: sin `contentDescription` (no se puede puntuar aquí, la
 * valoración se deja en la tienda) para no anunciar a un lector de pantalla un
 * control que no existe.
 */
@Composable
private fun StarRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // El bloque de cinco estrellas se centra como una unidad: `spacedBy` con
        // alineación reparte el sobrante a los dos lados en vez de dejarlo todo a la
        // derecha, así que la separación entre estrellas sigue siendo la misma.
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
        repeat(STAR_COUNT) { index ->
            val scale = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(index * STAR_STEP_MS)
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .size(STAR_SLOT)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = scale.value
                    },
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = KortexIcons.Star, tint = LogicColors.Amber, size = STAR_SIZE)
            }
        }
    }
}

/** Fila de argumento: icono con halo + una frase corta. */
@Composable
private fun ReviewBenefitRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonIcon(icon = icon, tint = tint, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
    }
}
