package com.example.kortexgames.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import com.example.kortexgames.game.GameOverInfo
import kotlin.math.roundToInt

/**
 * Capa modal de fin de partida. Muestra puntaje, precisión y tiempo, y si hay
 * percentil (usuario autenticado) el mensaje "Eres mejor que el X% de los
 * jugadores" de la FASE 2. Botones para reintentar o salir.
 */
@Composable
fun GameOverOverlay(
    info: GameOverInfo,
    onPlayAgain: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("¡Partida terminada!", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark)
            Text("${info.result.score}", style = MaterialTheme.typography.displayLarge, color = LogicColors.Electric)
            Text("puntos", style = MaterialTheme.typography.labelLarge, color = LogicColors.OnDarkMuted)

            Text(
                "Precisión ${info.result.accuracyPercentage.roundToInt()}%  ·  " +
                    "${info.result.completionTimeMs / 1000}s",
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
            )

            // Mensaje de percentil (FASE 2) o aviso de modo invitado.
            val percentile = info.percentile
            val message = if (percentile != null) {
                "🏆 Eres mejor que el ${percentile.betterThanPct.roundToInt()}% de los jugadores"
            } else {
                "Guardado localmente · inicia sesión para comparar con el mundo"
            }
            Text(
                message,
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.Amber,
                textAlign = TextAlign.Center,
            )

            AnimatedGameButton(
                text = "JUGAR DE NUEVO",
                onClick = onPlayAgain,
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.success,
            )
            AnimatedGameButton(
                text = "SALIR",
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
                gradient = LogicGradients.energy,
            )
        }
    }
}
