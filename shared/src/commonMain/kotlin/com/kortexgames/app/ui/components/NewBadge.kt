package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.catalog_badge_new
import org.jetbrains.compose.resources.stringResource

/** Nº de pasadas del destello de la insignia: llama la atención y se calma (no es un bucle). */
private const val NEW_BADGE_SHIMMER_PASSES = 2

/**
 * Píldora "NUEVO": marca los juegos recién incorporados al catálogo
 * ([com.kortexgames.app.game.GameInfo.isNew]) tanto en su tarjeta de
 * [com.kortexgames.app.ui.games.GameListScreen] como en las miniaturas de la
 * tarjeta "Juegos nuevos" de la Home.
 *
 * Mismo idioma de movimiento que la píldora "¡NUEVO RÉCORD MUNDIAL!" de
 * `WorldRankingPanel.kt`: entra con un "pop" de resorte y la recorre un
 * **destello finito** ([NEW_BADGE_SHIMMER_PASSES] pasadas) que barre la píldora de
 * lado a lado — se anima y se calma en vez de dejar un bucle permanente, para no
 * competir con el CTA de la pantalla (CLAUDE.md §9.4) aunque haya varias insignias
 * visibles a la vez (p. ej. varios juegos nuevos en el catálogo).
 *
 * Usa [LogicGradients.primary] (violeta→magenta) a propósito: [LogicGradients.reward]
 * (ámbar) ya está tomado por las insignias de récord/recompensa en otras pantallas,
 * y "novedad de catálogo" es un logro distinto que no debe leerse igual de un vistazo.
 *
 * @param compact variante más pequeña para espacios ajustados (la miniatura de un
 *   juego en un carrusel), frente a la variante por defecto para el hueco más
 *   generoso de una fila del catálogo.
 */
@Composable
fun NewBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    // `shown` arranca en false para que el primer frame se pinte en escala 0: si
    // `animateFloatAsState` partiera ya de `visible = true` no habría salto de
    // valor que animar y la píldora aparecería sin "pop".
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "newBadgeScale",
    )

    // 0→1 = el destello cruza la píldora una vez; `repeatable` lo repite y se detiene.
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shimmer.animateTo(
            targetValue = 1f,
            animationSpec = repeatable(
                iterations = NEW_BADGE_SHIMMER_PASSES,
                animation = tween(durationMillis = 900, easing = LinearEasing),
            ),
        )
    }

    val shape = RoundedCornerShape(percent = 50)
    val hPad = if (compact) 8.dp else 10.dp
    val vPad = if (compact) 3.dp else 4.dp
    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(Brush.horizontalGradient(LogicGradients.primary))
            .drawWithContent {
                drawContent()
                // La banda va de -0.5 a 1.5 del ancho para entrar y salir fuera de
                // cuadro; el `clip` de arriba recorta lo que sobra.
                val x = (shimmer.value * 2f - 0.5f) * size.width
                val half = size.width * 0.22f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        start = Offset(x - half, 0f),
                        end = Offset(x + half, size.height),
                    ),
                )
            }
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        Text(
            stringResource(Res.string.catalog_badge_new),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
    }
}
