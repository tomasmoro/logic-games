package com.example.kortexgames.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients

/**
 * Botón con "juice": al presionar hace scale-down y al soltar rebota (spring
 * de baja rigidez y bounce alto). Es la unidad de interacción principal del
 * juego, por eso el feedback es inmediato y táctil.
 *
 * @param onClick acción; ideal para disparar aquí SoundEffect.TAP + háptica.
 * @param gradient degradado de fondo (por defecto el primario de marca).
 * @param enabled deshabilita interacción y baja opacidad.
 */
@Composable
fun AnimatedGameButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = LogicGradients.primary,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Escala animada: 0.92 al presionar, rebote al volver a 1f.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "buttonScale",
    )

    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(brush = Brush.horizontalGradient(gradient), shape = shape)
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

/** Versión de conveniencia solo-texto. */
@Composable
fun AnimatedGameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = LogicGradients.primary,
    enabled: Boolean = true,
) = AnimatedGameButton(onClick, modifier, gradient, enabled) { Text(text) }
