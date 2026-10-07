package com.kortexgames.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors

/**
 * **Botón de acción de la barra inferior de un juego** (Deshacer, Reiniciar, ayudas): icono neón
 * con halo sobre su etiqueta.
 *
 * Es la forma canónica de ofrecer acciones durante la partida, en la franja bajo el tablero, y la
 * comparten todos los juegos que las tienen para que el jugador reconozca el mismo control vaya
 * donde vaya. Nació como componente privado de "Ordena las Pociones" y se extrajo aquí al
 * necesitarlo el segundo juego: un ajuste de aspecto se hace ahora en un solo sitio.
 *
 * ## Dos cuidados que parecen menores y no lo son
 *  - **El icono vive en un contenedor de tamaño fijo**, así que encender o apagar su halo no
 *    cambia el espacio que ocupa. Sin eso, el botón "salta" y arrastra al resto de la fila cada
 *    vez que se habilita o deshabilita.
 *  - **El distintivo de anuncio es una superposición**, no un icono distinto ni un texto extra:
 *    el botón mide exactamente igual siendo gratis que costando un anuncio, de modo que gastar el
 *    "Deshacer" gratuito no recoloca la barra entera.
 *
 * @param icon glifo de la acción (de [KortexIcons]; nunca un emoji, §9.5).
 * @param label texto bajo el icono.
 * @param tint color de acento de la acción; deshabilitado se atenúa y pierde el halo.
 * @param enabled si `false`, no responde al toque y se pinta apagado — en vez de desaparecer, que
 *   recolocaría los botones vecinos justo cuando el jugador va a pulsarlos.
 * @param onClick acción a ejecutar.
 * @param costsAd si la acción cuesta un anuncio recompensado, superpone el distintivo
 *   [KortexIcons.RewardedAd] en la esquina del icono. El jugador debe saber **antes** de pulsar
 *   que va a ver un anuncio; esa es también la razón de que estas acciones puedan lanzarlo sin
 *   preguntar de nuevo.
 */
@Composable
fun GameActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    costsAd: Boolean = false,
) {
    val effectiveTint = if (enabled) tint else LogicColors.OnDarkMuted
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .bounceClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reserva fija (~1.9x el glifo) para que el halo no altere el layout. El icono
        // va sobre un disco con aro del color de la acción: se lee como una TECLA que
        // se puede pulsar, no como un glifo suelto flotando sobre el fondo.
        Box(
            modifier = Modifier
                .size(54.dp)
                .drawBehind {
                    val radius = size.minDimension / 2f - 2.dp.toPx()
                    drawCircle(LogicColors.SurfaceDark.copy(alpha = 0.90f), radius)
                    if (enabled) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(tint.copy(alpha = 0.26f), Color.Transparent),
                                radius = radius,
                            ),
                            radius = radius,
                        )
                        // Halo ceñido + aro nítido (misma receta de capas que §9.7).
                        drawCircle(tint.copy(alpha = 0.18f), radius, style = Stroke(5.dp.toPx()))
                    }
                    drawCircle(
                        color = effectiveTint.copy(alpha = if (enabled) 0.75f else 0.28f),
                        radius = radius,
                        style = Stroke(1.5.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(icon = icon, tint = effectiveTint, glow = enabled, size = 26.dp)
            if (costsAd) {
                NeonIcon(
                    icon = KortexIcons.RewardedAd,
                    tint = LogicColors.OnDarkMuted,
                    glow = false,
                    size = 14.dp,
                    contentDescription = "Cuesta ver un anuncio",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // Pastilla opaca bajo el distintivo: sobre el aro del disco se
                        // perdía; así se sigue leyendo "cuesta un anuncio".
                        .background(LogicColors.SurfaceVariantDark, CircleShape)
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = effectiveTint)
    }
}
