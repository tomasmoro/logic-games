package com.kortexgames.app.game.daily

import com.kortexgames.app.domain.model.GameProgress
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Estado de un día en la tira semanal de la tarjeta de entrenamiento. */
enum class TrainingDayStatus {
    /** Ya se entrenó ese día (al menos una partida). */
    DONE,

    /** Es hoy y aún no se ha jugado: el día "en juego". */
    TODAY,

    /** Día pasado de esta semana sin ninguna partida. */
    MISSED,

    /** Día futuro de esta semana: aún por llegar. */
    PENDING,
}

/**
 * Un día de la semana en curso para la tira de la tarjeta de entrenamiento.
 *
 * @property label inicial que se pinta bajo/sobre el punto ("L", "M", …).
 * @property name nombre completo del día, para el `contentDescription` (accesibilidad:
 *   una inicial suelta no dice nada leída en voz alta).
 * @property status cómo se dibuja el punto (ver [TrainingDayStatus]).
 */
data class TrainingDay(
    val label: String,
    val name: String,
    val status: TrainingDayStatus,
)

/** Días de la semana en orden lunes→domingo: inicial + nombre para accesibilidad. */
private val WEEK_DAYS = listOf(
    "L" to "Lunes",
    "M" to "Martes",
    "M" to "Miércoles",
    "J" to "Jueves",
    "V" to "Viernes",
    "S" to "Sábado",
    "D" to "Domingo",
)

/**
 * Reparte el historial local en la **semana en curso** (lunes→domingo) para pintar la
 * tira de días de la tarjeta de entrenamiento. Como [calculateStreakDays], se deriva
 * del historial local: funciona offline y se actualiza solo al guardar una partida.
 *
 * El primer día de la semana se calcula con aritmética de días desde la época en vez
 * de con `dayOfWeek`: el 1/1/1970 fue **jueves**, así que `(epochDays + 3) % 7` da
 * directamente la distancia al lunes anterior. Evita depender de la representación de
 * `DayOfWeek` (que cambia entre versiones de kotlinx-datetime y entre plataformas) y
 * fija el lunes como inicio de semana, que es lo que espera el usuario en España.
 *
 * @param history historial de partidas (orden indistinto).
 * @return exactamente 7 días, de lunes a domingo de la semana actual.
 */
fun weeklyTrainingDays(
    history: List<GameProgress>,
    clock: Clock = Clock.System,
): List<TrainingDay> {
    val tz = TimeZone.currentSystemDefault()
    val playedDays = history.mapTo(mutableSetOf()) { it.createdAt.toLocalDateTime(tz).date }
    val today = clock.todayIn(tz)
    val daysFromMonday = ((today.toEpochDays() + 3) % 7).toInt()
    val monday = today.minus(daysFromMonday, DateTimeUnit.DAY)

    return WEEK_DAYS.mapIndexed { index, (label, name) ->
        val date = monday.plus(index, DateTimeUnit.DAY)
        // Haber jugado manda sobre "es hoy": si ya entrenaste hoy, el día se marca como
        // cumplido (recompensa inmediata) en vez de seguir mostrándose como pendiente.
        val status = when {
            date in playedDays -> TrainingDayStatus.DONE
            date == today -> TrainingDayStatus.TODAY
            date < today -> TrainingDayStatus.MISSED
            else -> TrainingDayStatus.PENDING
        }
        TrainingDay(label = label, name = name, status = status)
    }
}
