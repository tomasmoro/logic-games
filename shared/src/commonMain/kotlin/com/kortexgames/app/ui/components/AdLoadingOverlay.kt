package com.kortexgames.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients

/**
 * Aviso mínimo de "cargando anuncio", para los tratos donde pulsar el botón YA es
 * la confirmación del jugador (pista de Sudoku/Crucigrama, "tubo extra"/deshacer de
 * pago en WaterSort): ahí no hay [ReviveAdOverlay] con oferta+cuenta atrás de por
 * medio, así que sin esto el jugador pulsaba y no pasaba NADA en pantalla durante la
 * carga real del rewarded (llamada de red a AdMob, puede tardar varios segundos) —
 * parecía que el botón no había hecho nada. Reutiliza el mismo lenguaje visual que
 * la fila de carga de [ReviveAdOverlay] (spinner + "Cargando anuncio…"), pero como
 * overlay independiente: sin trato que ofrecer, solo feedback de espera. El scrim
 * bloquea el tablero para que no se pueda volver a pulsar mientras se resuelve.
 *
 * @param visible controla la aparición/desaparición con fundido; el llamador ata
 *   esto a su propio flag de "esperando anuncio" (p. ej. `awaitingHint`).
 * @param accent color de acento del spinner (coherente con la categoría del juego).
 */
@Composable
fun AdLoadingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = LogicColors.NeonGreen,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                // Bloquea la interacción con el tablero de debajo mientras carga.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardShape = RoundedCornerShape(20.dp)
            Row(
                modifier = Modifier
                    .clip(cardShape)
                    .background(LogicColors.SurfaceDark)
                    .border(BorderStroke(1.5.dp, Brush.linearGradient(LogicGradients.ring)), cardShape)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "Cargando anuncio…",
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
