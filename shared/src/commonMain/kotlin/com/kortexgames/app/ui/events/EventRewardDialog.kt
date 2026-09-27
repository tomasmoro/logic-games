package com.kortexgames.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.events.EventReward
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.FireworksOverlay
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.pulse
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_reward_badge
import kortexgames.shared.generated.resources.event_reward_close
import kortexgames.shared.generated.resources.event_reward_rank
import kortexgames.shared.generated.resources.event_reward_title_champion
import kortexgames.shared.generated.resources.event_reward_title_podium
import org.jetbrains.compose.resources.stringResource

/**
 * Celebración de un torneo ganado: insignia, puesto y fuegos artificiales.
 *
 * ## Por qué un diálogo y no un cartel dentro del torneo
 * El premio se resuelve cuando la clasificación se congela, a una hora en la que
 * el jugador no está mirando. Cuando vuelve a abrir la app hay que **ir a buscarlo**
 * con la noticia; esperar a que entre por su cuenta en la pantalla del torneo
 * convertiría el mejor momento del producto en un dato escondido.
 *
 * ## Decisiones (§9)
 * - **Los fuegos van detrás del cartel, a pantalla completa.** Es el único sitio de
 *   la app donde se celebra algo ganado contra OTRAS personas, y merece la misma
 *   fiesta que un récord (`FireworksOverlay`, el mismo componente que usa el
 *   combo de Bloques Neón) — pero por detrás, para no estorbar la lectura.
 * - **Oro para el campeón, acento del juego para el resto del podio.** El primer
 *   puesto es la única vez que la app usa el ámbar como identidad y no como aviso;
 *   el 2º y el 3º llevan el color del juego, que es el de su torneo.
 * - **Un solo botón.** No hay nada que decidir aquí: se lee y se cierra. Añadir un
 *   "ver clasificación" repartiría la atención justo en el momento de la fiesta, y
 *   el torneo sigue a un toque en la Home.
 *
 * @param reward puesto premiado ya resuelto (ver `EventRewardManager`).
 * @param onDismiss cerrar. El manager lo anota para no repetir la celebración.
 */
@Composable
fun EventRewardDialog(reward: EventReward, onDismiss: () -> Unit) {
    val isChampion = reward.rank == 1L
    val accent = if (isChampion) {
        LogicColors.Amber
    } else {
        GameCatalog.byId(reward.gameId)?.category?.accent ?: LogicColors.Electric
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(LogicColors.SurfaceDark)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BadgeMedallion(isChampion = isChampion, accent = accent)

                Text(
                    text = if (isChampion) {
                        stringResource(Res.string.event_reward_title_champion)
                    } else {
                        stringResource(Res.string.event_reward_title_podium)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = reward.eventTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.OnDark,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(
                        Res.string.event_reward_rank,
                        reward.rank.toString(),
                        reward.totalPlayers.toString(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(Res.string.event_reward_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )

                Spacer(Modifier.height(4.dp))

                AnimatedGameButton(
                    text = stringResource(Res.string.event_reward_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    gradient = if (isChampion) LogicGradients.reward else LogicGradients.play,
                )
            }

            // Los fuegos van DELANTE de la tarjeta: estallan sobre ella, como en una
            // celebración de verdad, en vez de asomar tímidamente por los bordes. Se
            // emiten los últimos porque en un `Box` el orden de declaración ES el orden
            // de dibujo.
            //
            // No roban el toque: `FireworksOverlay` es un `Canvas` sin gestos, y en
            // Compose un hijo que no maneja punteros deja pasar el evento al que sí lo
            // hace. El botón de abajo sigue siendo pulsable aunque le pase una chispa
            // por encima.
            FireworksOverlay(
                modifier = Modifier.fillMaxSize(),
                burstCount = if (isChampion) CHAMPION_BURSTS else PODIUM_BURSTS,
            )
        }
    }
}

/**
 * La insignia: disco con halo y el icono del puesto. Late despacio —es lo único que
 * se mueve dentro de la tarjeta— para que el ojo aterrice ahí antes que en el texto.
 */
@Composable
private fun BadgeMedallion(isChampion: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .pulse(maxScale = 1.06f)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(
            icon = if (isChampion) KortexIcons.Trophy else KortexIcons.Medal,
            tint = accent,
            size = 52.dp,
            contentDescription = null,
        )
    }
}

/** Al campeón se le tira la casa por la ventana; al podio, algo más contenido. */
private const val CHAMPION_BURSTS = 8
private const val PODIUM_BURSTS = 5
