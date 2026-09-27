package com.kortexgames.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.pulse
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_exit_attempts_last
import kortexgames.shared.generated.resources.event_exit_attempts_left
import kortexgames.shared.generated.resources.event_exit_body
import kortexgames.shared.generated.resources.event_exit_cancel
import kortexgames.shared.generated.resources.event_exit_confirm
import kortexgames.shared.generated.resources.event_exit_title
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmación antes de abandonar una partida de torneo.
 *
 * ## Por qué existe
 * Desde la migración 0056 salir a mitad de partida **gasta el intento**. Esa es
 * una consecuencia cara e invisible: el jugador pulsa "salir" creyendo que
 * aparca la partida, como en cualquier otro juego de la app, y pierde una de sus
 * tres oportunidades. Una acción con coste irreversible no puede ocurrir sin
 * avisar.
 *
 * ## Decisiones de diseño (§9)
 * - **Mismo molde que `GuestRiskDialog`**: `Dialog` sobre `SurfaceDark`, esquinas
 *   de 28dp, 24dp de aire. Un diálogo con coste debe reconocerse al instante como
 *   los otros de la app, no inventar su propia forma.
 * - **El icono de aviso late** (`pulse`), y es lo único que se mueve aquí: dentro
 *   de un diálogo modal no compite con nada (§9.4, regla 5).
 * - **Ámbar, no rojo.** El rojo (`Error`) es el color del fallo de juego —fallar
 *   una celda, agotar el tiempo—. Esto no es un error, es una decisión con
 *   consecuencia; usar el mismo rojo diluiría el que sí importa durante la
 *   partida.
 * - **La acción segura se lleva el botón**; salir queda como texto atenuado
 *   debajo. Exactamente el mismo reparto que en `GuestRiskDialog`: el CTA empuja
 *   a lo que conviene al jugador, la salida sigue estando a un toque.
 *
 * @param attemptsLeftAfter intentos que quedarán DESPUÉS de gastar este, o null si
 *   el torneo no los limita (entonces no se menciona: no hay nada escaso que
 *   perder). Se dice el saldo resultante y no el actual porque es la cifra sobre
 *   la que se decide.
 */
@Composable
fun EventExitConfirmDialog(
    attemptsLeftAfter: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(
                icon = Icons.Rounded.WarningAmber,
                tint = LogicColors.Amber,
                size = 40.dp,
                modifier = Modifier.pulse(maxScale = 1.08f),
            )
            Text(
                text = stringResource(Res.string.event_exit_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                text = stringResource(Res.string.event_exit_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            attemptsLeftAfter?.let { left ->
                Text(
                    text = if (left <= 0) {
                        stringResource(Res.string.event_exit_attempts_last)
                    } else {
                        stringResource(Res.string.event_exit_attempts_left, left.toString())
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (left <= 0) LogicColors.Amber else LogicColors.OnDark,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(4.dp))

            AnimatedGameButton(
                text = stringResource(Res.string.event_exit_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.play,
            )
            Text(
                text = stringResource(Res.string.event_exit_confirm),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onConfirm)
                    .padding(vertical = 10.dp),
            )
        }
    }
}
