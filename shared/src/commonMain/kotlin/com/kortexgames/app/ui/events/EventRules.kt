package com.kortexgames.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.game.GameRankingScopes
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_rule_attempts
import kortexgames.shared.generated.resources.event_rule_ad_attempt_one
import kortexgames.shared.generated.resources.event_rule_ad_attempts
import kortexgames.shared.generated.resources.event_rule_attempts_unlimited
import kortexgames.shared.generated.resources.event_rule_own_board
import kortexgames.shared.generated.resources.event_rule_rank_score
import kortexgames.shared.generated.resources.event_rule_rank_time
import kortexgames.shared.generated.resources.event_rule_reward
import kortexgames.shared.generated.resources.event_rule_same_board
import kortexgames.shared.generated.resources.event_rules_title
import org.jetbrains.compose.resources.stringResource

/**
 * Las reglas de un torneo, derivadas del propio evento. Vive fuera de la pantalla
 * de torneo porque las lee también la **antesala del juego**: el jugador debe ver
 * las mismas reglas justo antes de gastar un intento, y tenerlas en dos sitios
 * distintos es garantía de que un día dejen de coincidir.
 *
 * Se pintan siempre todas las líneas —reto, criterio, intentos y premio— aunque
 * alguna sea "ilimitado": en una competición, la regla que NO se enuncia es la que
 * acaba generando la discusión.
 */
@Composable
fun EventRulesPanel(event: GameEvent, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.event_rules_title),
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
        )

        val fixedChallenge = event.puzzleId != null || event.seed != null
        RuleLine(
            accent = accent,
            text = if (fixedChallenge) {
                stringResource(Res.string.event_rule_same_board)
            } else {
                stringResource(Res.string.event_rule_own_board)
            },
        )
        RuleLine(
            accent = accent,
            text = if (event.rankByTime) {
                stringResource(Res.string.event_rule_rank_time)
            } else {
                stringResource(Res.string.event_rule_rank_score)
            },
        )
        RuleLine(
            accent = accent,
            text = event.attemptsLimit?.let {
                stringResource(Res.string.event_rule_attempts, it.toString())
            } ?: stringResource(Res.string.event_rule_attempts_unlimited),
        )
        // Los intentos comprables se enuncian aparte de los libres: son dos números
        // distintos y juntarlos en una frase ("3 intentos, o 6 con anuncios") invita
        // justo al malentendido de creer que los tres extras ya están disponibles.
        if (event.adAttemptsLimit > 0) {
            RuleLine(
                accent = accent,
                text = if (event.adAttemptsLimit == 1) {
                    stringResource(Res.string.event_rule_ad_attempt_one)
                } else {
                    stringResource(Res.string.event_rule_ad_attempts, event.adAttemptsLimit.toString())
                },
            )
        }
        if (event.rewardTopN > 0) {
            RuleLine(
                accent = accent,
                text = stringResource(Res.string.event_rule_reward, event.rewardTopN.toString()),
            )
        }
        // La dificultad fija se enuncia con el rótulo que el propio juego usa en su
        // ranking (`GameRankingScopes`), no con el número crudo: "Experto" significa
        // algo para el jugador, "dificultad 4" no.
        event.difficultyLevel
            ?.let { GameRankingScopes.difficultyLabel(event.gameId, it) }
            ?.let { RuleLine(accent = accent, text = it) }
    }
}

@Composable
private fun RuleLine(accent: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NeonIcon(icon = KortexIcons.CheckMark, tint = accent, size = 16.dp, glow = false, contentDescription = null)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
        )
    }
}

