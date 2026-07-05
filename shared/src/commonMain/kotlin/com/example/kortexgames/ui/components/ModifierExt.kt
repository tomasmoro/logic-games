package com.example.kortexgames.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/**
 * Click sin ripple (el feedback lo da la animación de escala del propio botón).
 */
fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/** Aplica alpha solo si [condition] es cierta. */
fun Modifier.alphaIf(condition: Boolean, value: Float): Modifier =
    if (condition) this.alpha(value) else this
