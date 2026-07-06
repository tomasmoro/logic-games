package com.example.kortexgames.game.daily

import com.example.kortexgames.domain.model.GameProgress
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Calcula la **racha de días consecutivos** con al menos una partida, terminando
 * hoy (o ayer si aún no se ha jugado hoy: la racha no se rompe hasta que termine
 * el día sin jugar). Se deriva del historial local (fuente de verdad, offline).
 *
 * Se centraliza aquí para que Home y Profile muestren exactamente el mismo valor.
 *
 * @param history historial de partidas (orden indistinto).
 * @return número de días de racha (0 si no hay actividad reciente).
 */
fun calculateStreakDays(
    history: List<GameProgress>,
    clock: Clock = Clock.System,
): Int {
    if (history.isEmpty()) return 0
    val tz = TimeZone.currentSystemDefault()
    val playedDays = history.mapTo(mutableSetOf()) { it.createdAt.toLocalDateTime(tz).date }
    val today = clock.todayIn(tz)
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    // El ancla es hoy si ya se jugó hoy; si no, ayer (aún hay margen para hoy).
    var cursor = when {
        today in playedDays -> today
        yesterday in playedDays -> yesterday
        else -> return 0
    }
    var streak = 0
    while (cursor in playedDays) {
        streak++
        cursor = cursor.minus(1, DateTimeUnit.DAY)
    }
    return streak
}
