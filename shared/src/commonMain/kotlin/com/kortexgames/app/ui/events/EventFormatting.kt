package com.kortexgames.app.ui.events

import androidx.compose.runtime.Composable
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.model.EventPhase
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_countdown_days
import kortexgames.shared.generated.resources.event_countdown_ends
import kortexgames.shared.generated.resources.event_countdown_hours
import kortexgames.shared.generated.resources.event_countdown_minutes
import kortexgames.shared.generated.resources.event_countdown_seconds
import kortexgames.shared.generated.resources.event_countdown_starts
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Formateo de los textos de un torneo que dependen del reloj. Vive aparte de las
 * pantallas porque lo comparten la tarjeta de Home y el detalle, y porque así la
 * regla de "qué unidad se enseña" está en un solo sitio.
 *
 * Todos los textos salen de `strings.xml` (CLAUDE.md §10) y los números se pasan ya
 * convertidos a `String`, que es lo que exige el catálogo de recursos.
 */

/**
 * Cuenta atrás legible, **con la granularidad que toca**: días y horas cuando
 * falta mucho, minutos cuando aprieta, y "menos de un minuto" al final.
 *
 * El porqué: una cuenta atrás al segundo durante tres días es ruido que además
 * obliga a recomponer la Home 86.400 veces al día; y "2 d" a treinta segundos del
 * cierre es información inútil justo cuando más urge. La unidad se adapta a la
 * escala de lo que queda.
 */
@Composable
fun formatCountdown(remaining: Duration): String {
    val totalMinutes = remaining.inWholeMinutes
    return when {
        remaining.inWholeDays >= 1 -> stringResource(
            Res.string.event_countdown_days,
            remaining.inWholeDays.toString(),
            (remaining.inWholeHours % HOURS_PER_DAY).toString(),
        )
        remaining.inWholeHours >= 1 -> stringResource(
            Res.string.event_countdown_hours,
            remaining.inWholeHours.toString(),
            (totalMinutes % MINUTES_PER_HOUR).toString(),
        )
        totalMinutes >= 1 -> stringResource(
            Res.string.event_countdown_minutes,
            totalMinutes.toString(),
        )
        else -> stringResource(Res.string.event_countdown_seconds)
    }
}

/**
 * La frase completa de cuenta atrás según la fase: "Empieza en…" antes de abrir y
 * "Termina en…" mientras está vivo. En un torneo ya cerrado no hay nada que contar
 * y devuelve null, para que quien llama no pinte una línea vacía.
 */
@Composable
fun GameEvent.countdownLabel(now: Instant): String? = when (phaseAt(now)) {
    EventPhase.UPCOMING -> stringResource(
        Res.string.event_countdown_starts,
        formatCountdown(startsAt - now),
    )
    EventPhase.LIVE -> stringResource(
        Res.string.event_countdown_ends,
        formatCountdown(endsAt - now),
    )
    EventPhase.FINISHED -> null
}

/**
 * Cada cuánto debe recalcularse la cuenta atrás para que el texto no se quede
 * obsoleto sin gastar un tick por segundo: mientras se enseñan días u horas basta
 * con refrescar por minuto; en el último minuto, cada segundo.
 */
fun countdownTickMillis(remaining: Duration): Long = when {
    // Ya pasó el instante objetivo (el torneo abrió o cerró): latido lento, que solo
    // sirve para que la pantalla se repinte en su nueva fase. Sin este caso, una
    // duración negativa caería en el segundero y dejaría la Home latiendo a 1 Hz
    // indefinidamente después de cada torneo.
    remaining.isNegative() -> MINUTE_MS
    remaining.inWholeMinutes >= 1 -> MINUTE_MS
    else -> SECOND_MS
}

/**
 * Marca de un jugador tal y como se lee en la tabla: puntos, o tiempo si el torneo
 * se rankea por velocidad. Devuelve solo el número formateado; el sufijo lo pone
 * quien lo pinta.
 */
fun formatEventMetric(value: Int, rankByTime: Boolean): String =
    if (rankByTime) formatMillis(value) else value.toString()

/** `m:ss` a partir de milisegundos. Sin horas: ninguna partida dura tanto. */
private fun formatMillis(ms: Int): String {
    val totalSeconds = ms / SECOND_MS.toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val MINUTE_MS = 60_000L
private const val SECOND_MS = 1_000L
