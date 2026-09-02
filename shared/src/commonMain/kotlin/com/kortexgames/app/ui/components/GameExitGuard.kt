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
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.gameexit_ends_run_notice
import org.jetbrains.compose.resources.stringResource

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
 * ## Pausar mientras se decide (juegos en tiempo real)
 * Por defecto el cartel se superpone sin tocar la partida: vale para los juegos
 * por turnos, donde el tablero espera. En un juego de acción (p. ej. Neon Legion)
 * eso castiga al jugador que pulsó atrás sin querer —la amenaza sigue avanzando
 * detrás del cartel—. Pasando [onPause] el guard **congela la partida** al abrir
 * el cartel y la **reanuda** si elige "SEGUIR JUGANDO" (o descarta el cartel). Como
 * pausar lleva el motor a [GameStatus.PAUSED] y eso, por sí solo, dispararía el
 * menú de pausa de [GamePauseControls], el juego debe además silenciar ese menú
 * mientras el cartel está visible (ver `suppressMenu` de [GamePauseControls] y
 * [onExitPromptVisibilityChange]).
 *
 * @param status ciclo de vida del juego (igual fuente de verdad que [GamePauseControls]).
 * @param onResume reanuda el juego (atrás mientras está en pausa, o "SEGUIR JUGANDO"
 *        cuando se pasó [onPause]).
 * @param onConfirmExit el jugador confirmó salir; normalmente guarda progreso y navega
 *        atrás (ver `requestExit` de cada ViewModel que lo activa).
 * @param progress qué le pasa a la partida en curso al confirmar (ver [GameExitProgress]):
 *        decide el texto que tranquiliza/advierte al jugador antes del botón "SALIR".
 * @param accent color de acento del juego (icono y CTA del cartel).
 * @param onPause si no es `null`, pausa la partida al abrir el cartel de confirmación
 *        (pensado para juegos en tiempo real). `null` (por defecto) mantiene el
 *        comportamiento clásico: el cartel no toca la partida.
 * @param onExitPromptVisibilityChange notifica cuándo el cartel de confirmación está
 *        en pantalla. El juego lo usa para silenciar el menú de pausa mientras tanto
 *        (necesario solo si se pasó [onPause], porque entonces el motor está en PAUSED).
 */
@Composable
fun GameExitGuard(
    status: GameStatus,
    onResume: () -> Unit,
    onConfirmExit: () -> Unit,
    progress: GameExitProgress = GameExitProgress.RESUMES,
    accent: Color = LogicColors.NeonCyan,
    onPause: (() -> Unit)? = null,
    onExitPromptVisibilityChange: (Boolean) -> Unit = {},
) {
    var showExitDialog by remember { mutableStateOf(false) }

    /** Abre el cartel y, si el juego lo pidió, congela la partida. */
    val openExitPrompt: () -> Unit = {
        onPause?.invoke()
        showExitDialog = true
        // Se notifica aquí (síncrono, no vía efecto) para que el juego pueda silenciar su
        // menú de pausa en el mismo frame en que el motor entra en PAUSED.
        onExitPromptVisibilityChange(true)
    }

    /** Cierra el cartel y, si habíamos pausado por él, reanuda la partida. */
    val keepPlaying: () -> Unit = {
        showExitDialog = false
        onExitPromptVisibilityChange(false)
        if (onPause != null) onResume()
    }

    PlatformBackHandler(enabled = status == GameStatus.RUNNING || status == GameStatus.PAUSED) {
        when {
            // Atrás con el cartel ya abierto: equivale a "SEGUIR JUGANDO" (en Android el
            // propio Dialog suele capturar este atrás vía onDismissRequest; esto cubre
            // el resto de casos sin dejar la partida pausada y el cartel colgado).
            showExitDialog -> keepPlaying()
            // Atrás en pausa (menú de pausa abierto): reanuda, igual que la "X" del menú.
            status == GameStatus.PAUSED -> onResume()
            // Atrás jugando: congela la partida (si el juego lo pidió) y pregunta.
            else -> openExitPrompt()
        }
    }

    if (showExitDialog) {
        ConfirmExitDialog(
            progress = progress,
            accent = accent,
            onKeepPlaying = keepPlaying,
            onExit = {
                showExitDialog = false
                onExitPromptVisibilityChange(false)
                onConfirmExit()
            },
        )
    }
}

/**
 * Qué le pasa al progreso de la partida en curso al confirmar la salida por [GameExitGuard].
 * Determina el texto del cartel — no basta un booleano porque hay dos formas distintas de "no
 * perder nada": reanudar la partida tal cual, o cerrarla guardando su resultado.
 */
enum class GameExitProgress {
    /**
     * El motor implementa `ResumableGameEngine`: al volver a entrar retoma exactamente donde
     * el jugador lo dejó (mismo tablero, mismos movimientos). Caso por defecto —hoy el único
     * usado— para no tocar el copy de los juegos que ya activan el guardado.
     */
    RESUMES,

    /**
     * Juego ENDLESS sin reanudación (ver KDoc de
     * [com.kortexgames.app.game.ResumableGameEngine]): no existe "seguir la carrera a medias",
     * así que salir CIERRA la corrida y guarda su resultado real (ronda alcanzada, puntaje)
     * igual que si se hubiera perdido aquí mismo — pero la próxima partida empieza de cero.
     */
    ENDS_RUN,

    /** Nada se guarda: la partida en curso se pierde por completo al confirmar la salida. */
    LOSES_PROGRESS,
}

/**
 * Cartel "¿Salir del juego?" con el mismo lenguaje visual que el resto de diálogos
 * de la app (tarjeta redondeada [LogicColors.SurfaceDark], icono neón, CTA con
 * degradado). Privado: solo lo monta [GameExitGuard].
 */
@Composable
private fun ConfirmExitDialog(
    progress: GameExitProgress,
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
                when (progress) {
                    GameExitProgress.RESUMES ->
                        "Tu progreso se guarda automáticamente: podrás continuar donde " +
                            "lo dejaste."
                    GameExitProgress.ENDS_RUN -> stringResource(Res.string.gameexit_ends_run_notice)
                    GameExitProgress.LOSES_PROGRESS -> "Si sales ahora, esta partida no se guardará."
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
