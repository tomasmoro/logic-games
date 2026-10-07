package com.kortexgames.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients

/** Radio de esquina del botón (§9.6: botones 20dp). */
private val ButtonCorner = 20.dp

/**
 * Alto del "canto" del botón: la franja oscura bajo la cara que le da grosor de
 * tecla. Es también el recorrido que la cara se hunde al presionar.
 */
private val ButtonDepth = 5.dp

/** Duración de un ciclo del destello; el barrido en sí ocupa solo [SHIMMER_ACTIVE]. */
private const val SHIMMER_PERIOD_MS = 3200

/** Fracción del ciclo en la que el destello cruza el botón; el resto es pausa. */
private const val SHIMMER_ACTIVE = 0.28f

/**
 * Botón principal de la app, con aspecto de **tecla arcade**: una cara con degradado
 * y brillo de "gloss" sobre un canto más oscuro que le da grosor. Al presionar, la
 * cara se **hunde** sobre el canto (además del scale-down con rebote), que es lo que
 * hace que pulsarlo se sienta físico y no como tocar un rectángulo de color.
 *
 * Es la unidad de interacción principal del juego (CTA de la antesala, "Reanudar",
 * "Siguiente nivel"...), por eso el feedback es inmediato y táctil (§9.4: resorte,
 * no `tween`, en todo lo que se toca).
 *
 * @param onClick acción; ideal para disparar aquí SoundEffect.TAP + háptica.
 * @param gradient degradado de la cara (por defecto el primario de marca). El canto
 *   se deriva de su último color, así que cualquier degradado trae su relieve.
 * @param enabled deshabilita interacción y baja opacidad.
 * @param shimmer si true, un destello diagonal cruza la cara cada pocos segundos.
 *   Es un bucle de atención: resérvalo para EL CTA principal de la pantalla (§9.4),
 *   igual que [pulse].
 */
@Composable
fun AnimatedGameButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = LogicGradients.primary,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
    shimmer: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 0 = en reposo, 1 = hundido. Resorte con rebote: al soltar, la tecla "salta".
    val press by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "buttonPress",
    )

    val shimmerPhase: State<Float>? = if (shimmer && enabled) {
        rememberInfiniteTransition(label = "buttonShimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "buttonShimmerPhase",
        )
    } else {
        null
    }

    val shape = RoundedCornerShape(ButtonCorner)
    val ledge = lerp(gradient.last(), LogicColors.BackgroundDark, 0.58f)

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = 1f - 0.035f * press
                scaleX = s
                scaleY = s
            }
            // Canto: un rectángulo redondeado desplazado hacia abajo, bajo la cara.
            .drawBehind {
                val depth = ButtonDepth.toPx()
                drawRoundRect(
                    color = ledge,
                    topLeft = Offset(0f, depth),
                    size = Size(size.width, size.height - depth),
                    cornerRadius = CornerRadius(ButtonCorner.toPx()),
                )
            }
            .padding(bottom = ButtonDepth)
            // La cara se hunde sobre el canto (se queda 1dp por encima: nunca "plana").
            .graphicsLayer { translationY = press * (ButtonDepth - 1.dp).toPx() }
            .clip(shape)
            .background(brush = Brush.horizontalGradient(gradient), shape = shape)
            .drawBehind {
                // Gloss: luz en la mitad superior y sombra suave en la inferior.
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.30f),
                        0.50f to Color.White.copy(alpha = 0.04f),
                        0.51f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.14f),
                    ),
                )
                // Filo de luz en el borde superior de la cara.
                val edge = 1.2.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.55f),
                        0.40f to Color.Transparent,
                    ),
                    topLeft = Offset(edge / 2f, edge / 2f),
                    size = Size(size.width - edge, size.height - edge),
                    cornerRadius = CornerRadius(ButtonCorner.toPx() - edge / 2f),
                    style = Stroke(width = edge),
                )
                // Destello: banda diagonal que cruza y descansa el resto del ciclo.
                val phase = shimmerPhase?.value ?: return@drawBehind
                if (phase < SHIMMER_ACTIVE) {
                    val t = phase / SHIMMER_ACTIVE
                    val band = size.height * 0.9f
                    val x = -band * 2f + (size.width + band * 4f) * t
                    rotate(degrees = 22f, pivot = Offset(x, size.height / 2f)) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.38f), Color.Transparent),
                                startX = x - band / 2f,
                                endX = x + band / 2f,
                            ),
                            topLeft = Offset(x - band / 2f, -size.height),
                            size = Size(band, size.height * 3f),
                        )
                    }
                }
            }
            .then(
                Modifier.clickableNoRipple(
                    interactionSource = interaction,
                    enabled = enabled,
                    onClick = onClick,
                )
            )
            .padding(contentPadding)
            .alphaIf(!enabled, 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides LogicColors.OnDark) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/**
 * Versión de conveniencia solo-texto.
 *
 * @param textColor color del rótulo. Por defecto claro (pensado para los degradados
 *   oscuros/saturados de marca); sobre un degradado CLARO —p. ej. el de acento de los
 *   diálogos, [accentCtaGradient]— pasa un color oscuro o el texto pierde contraste.
 */
@Composable
fun AnimatedGameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = LogicGradients.primary,
    enabled: Boolean = true,
    shimmer: Boolean = false,
    textColor: Color = LogicColors.OnDark,
) = AnimatedGameButton(onClick, modifier, gradient, enabled, shimmer = shimmer) { Text(text, color = textColor) }
