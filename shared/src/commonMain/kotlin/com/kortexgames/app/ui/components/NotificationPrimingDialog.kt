package com.kortexgames.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.notif_priming_accept
import kortexgames.shared.generated.resources.notif_priming_benefit_mission
import kortexgames.shared.generated.resources.notif_priming_benefit_record
import kortexgames.shared.generated.resources.notif_priming_benefit_streak
import kortexgames.shared.generated.resources.notif_priming_body
import kortexgames.shared.generated.resources.notif_priming_decline
import kortexgames.shared.generated.resources.notif_priming_title
import org.jetbrains.compose.resources.stringResource

/**
 * **Antesala del permiso de notificaciones**: el paso propio de la app que se muestra
 * ANTES del diálogo del sistema.
 *
 * Existe porque el diálogo del sistema es irrepetible (iOS solo lo muestra una vez;
 * Android deja de mostrarlo tras dos negativas), así que conviene que solo lo vea
 * quien ya ha dicho que sí. Aquí un "ahora no" no cuesta nada — ver
 * `NotificationPrimingPolicy` para las reglas de cuándo aparece.
 *
 * Decisiones de diseño (CLAUDE.md §9):
 *
 *  - Habla de **beneficios que el jugador ya tiene** (su racha, su misión, sus
 *    marcas), no de "activar notificaciones". Se ofrece algo, no se pide un permiso.
 *  - Un único elemento con degradado —el CTA— para que la jerarquía sea inequívoca;
 *    "Ahora no" es texto plano y perfectamente pulsable, sin trucos oscuros para
 *    esconderlo: si se le arrincona, el usuario deniega en el diálogo del sistema y
 *    ahí sí se pierde la oportunidad de verdad.
 *  - `onDismissRequest` cuenta como "ahora no": tocar fuera es una respuesta.
 *
 * @param onAccept el usuario acepta; el llamante debe lanzar el diálogo del sistema.
 * @param onDecline "ahora no" (o descartar tocando fuera).
 */
@Composable
fun NotificationPrimingDialog(
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
            NeonIcon(icon = KortexIcons.Notifications, tint = LogicColors.NeonCyan, size = 40.dp)

            Text(
                stringResource(Res.string.notif_priming_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                stringResource(Res.string.notif_priming_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BenefitRow(
                    icon = KortexIcons.Streak,
                    tint = LogicColors.StreakOrange,
                    text = stringResource(Res.string.notif_priming_benefit_streak),
                )
                BenefitRow(
                    icon = KortexIcons.Check,
                    tint = LogicColors.NeonGreen,
                    text = stringResource(Res.string.notif_priming_benefit_mission),
                )
                BenefitRow(
                    icon = KortexIcons.Trophy,
                    tint = LogicColors.Amber,
                    text = stringResource(Res.string.notif_priming_benefit_record),
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
                    stringResource(Res.string.notif_priming_accept),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LogicColors.BackgroundDark,
                )
            }

            Text(
                stringResource(Res.string.notif_priming_decline),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onDecline)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** Fila de beneficio: icono con halo + una frase corta. */
@Composable
private fun BenefitRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonIcon(icon = icon, tint = tint, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
    }
}
