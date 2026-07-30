package com.kortexgames.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.game.GameStatus

/**
 * # Guardia de salida por botón atrás, común a los juegos que activan el guardado
 *
 * Overlay **reutilizable** (mismo espíritu que [GamePauseControls]: cada juego lo
 * suma a su `Box` raíz solo si lo necesita) que intercepta el atrás del sistema
 * mientras se está jugando:
 *
 *  - En [GameStatus.PAUSED] el atrás simplemente reanuda (mismo efecto que la "X" del
 *    menú de pausa) — es el comportamiento estándar de "atrás cierra el diálogo
 *    abierto", y evita apilar un segundo cartel sobre el menú de pausa.
 *  - En [GameStatus.RUNNING] el atrás abre un cartel de confirmación: el jugador
 *    puede seguir jugando o confirmar la salida.
 *
 * No hace nada por sí solo en el resto de estados (IDLE/FINISHED): ahí no hay
 * partida en curso que perder, así que el atrás del sistema sigue su curso normal
 * (`NavHost` hace `popBackStack`).
 *
 * @param status ciclo de vida del juego (igual fuente de verdad que [GamePauseControls]).
 * @param onResume reanuda el juego (atrás mientras está en pausa).
 * @param onConfirmExit el jugador confirmó salir; normalmente guarda progreso y navega
 *        atrás (ver `requestExit` de cada ViewModel que lo activa).
 * @param keepsProgress si el cartel debe aclarar que el progreso no se pierde (el único
 *        caso real hoy; se deja como parámetro por si algún juego futuro reanuda pero
 *        sin garantizarlo, p. ej. un modo por tiempo).
 * @param accent color de acento del juego (icono y CTA del cartel).
 */
@Composable
fun GameExitGuard(
    status: GameStatus,
    onResume: () -> Unit,
    onConfirmExit: () -> Unit,
    keepsProgress: Boolean = true,
    accent: Color = LogicColors.NeonCyan,
) {
    var showExitDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = status == GameStatus.RUNNING || status == GameStatus.PAUSED) {
        if (status == GameStatus.PAUSED) onResume() else showExitDialog = true
    }

    if (showExitDialog) {
        ConfirmExitDialog(
            keepsProgress = keepsProgress,
            accent = accent,
            onKeepPlaying = { showExitDialog = false },
            onExit = {
                showExitDialog = false
                onConfirmExit()
            },
        )
    }
}

/**
 * Cartel "¿Salir del juego?" con el mismo lenguaje visual que el resto de diálogos
 * de la app (tarjeta redondeada [LogicColors.SurfaceDark], icono neón, CTA con
 * degradado). Privado: solo lo monta [GameExitGuard].
 */
@Composable
private fun ConfirmExitDialog(
    keepsProgress: Boolean,
    accent: Color,
    onKeepPlaying: () -> Unit,
    onExit: () -> Unit,
) {
    Dialog(onDismissRequest = onKeepPlaying) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(icon = KortexIcons.Exit, tint = accent, size = 40.dp)
            Text(
                "¿Salir del juego?",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                if (keepsProgress) {
                    "Tu progreso se guarda automáticamente: podrás continuar donde " +
                        "lo dejaste."
                } else {
                    "Si sales ahora, esta partida no se guardará."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            AnimatedGameButton(
                onClick = onKeepPlaying,
                gradient = LogicGradients.play,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "SEGUIR JUGANDO",
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.BackgroundDark,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(
                "SALIR",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onExit)
                    .padding(vertical = 10.dp),
            )
        }
    }
}
