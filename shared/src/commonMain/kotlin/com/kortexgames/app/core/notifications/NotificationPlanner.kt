package com.kortexgames.app.core.notifications

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * # Cuándo notificar
 *
 * Decide, a partir de una foto del estado del jugador, **qué avisos deben estar
 * programados en este momento**. Es una función pura: mismos datos de entrada →
 * misma salida, sin reloj propio, sin acceso a repositorios y sin efectos. Esa
 * pureza es deliberada — es lo que permite probar todas las reglas de retención en
 * `commonTest` sin emulador ni simulador (CLAUDE.md §6).
 *
 * El resultado es siempre el **plan completo**, no un incremento: quien lo aplica
 * ([NotificationsManager]) cancela todo y reprograma. Razonar sobre "el estado
 * final que debe existir" evita la clase de bug más típica de este tipo de módulos
 * —avisos zombis de una situación que ya cambió— sin llevar contabilidad de qué se
 * programó antes.
 */
class NotificationPlanner {

    /**
     * Calcula el plan de avisos pendientes.
     *
     * Reglas (y su porqué):
     *
     *  - **Racha en riesgo**: solo si hay racha real (>= [MIN_STREAK_TO_WARN] días) y
     *    hoy todavía no se ha jugado. Avisar de una racha de 1 día no es una pérdida
     *    que duela; avisar a quien ya jugó hoy es ruido puro.
     *  - **Misión diaria**: solo si HOY se jugó pero la misión quedó a medias. Las dos
     *    reglas son excluyentes por construcción (una exige haber jugado hoy y la otra
     *    no haberlo hecho), así que nunca se solapan dos recordatorios la misma tarde.
     *  - **Inactividad**: cadena de 3/7/14 días desde la última partida. Después del
     *    escalón de 14 días no se insiste más (ver [InactivityStep]).
     *  - **Récord batido**: se difiere al día siguiente. Felicitar en caliente no
     *    aporta —el jugador tiene el cartel de fin de partida delante—; recordarle al
     *    día siguiente que tiene una marca que defender es lo que le hace volver.
     *
     * Todo aviso cuyo instante ya haya pasado se descarta aquí mismo, así que la
     * salida solo contiene entregas futuras.
     */
    fun plan(input: NotificationInputs): List<PlannedNotification> {
        val plan = mutableListOf<PlannedNotification>()
        val tz = input.timeZone
        val today = input.now.toLocalDateTime(tz).date
        val playedToday = input.lastPlayed?.toLocalDateTime(tz)?.date == today

        // --- Racha en riesgo: esta noche, si aún no se jugó hoy -----------------
        if (!playedToday && input.streakDays >= MIN_STREAK_TO_WARN) {
            plan += PlannedNotification(
                content = NotificationContent.StreakAtRisk(input.streakDays),
                at = today.atTime(STREAK_WARNING_TIME).toInstant(tz),
            )
        }

        // --- Misión diaria a medias: al caer la tarde ---------------------------
        if (playedToday && input.dailyMissionRemaining > 0) {
            plan += PlannedNotification(
                content = NotificationContent.DailyMissionPending(input.dailyMissionRemaining),
                at = today.atTime(DAILY_MISSION_TIME).toInstant(tz),
            )
        }

        // --- Cadena de reenganche por inactividad -------------------------------
        // Sin partidas todavía (instalación recién estrenada) el ancla es "ahora":
        // así un usuario que instala y no juega también entra en la cadena.
        val anchor = input.lastPlayed ?: input.now
        val anchorDate = anchor.toLocalDateTime(tz).date
        for (step in InactivityStep.entries) {
            plan += PlannedNotification(
                content = NotificationContent.Inactivity(step),
                at = anchorDate.plus(step.days, DateTimeUnit.DAY).atTime(INACTIVITY_TIME).toInstant(tz),
            )
        }

        // --- Récord personal batido: revancha al día siguiente ------------------
        input.recordBeaten?.let { record ->
            val beatenDate = record.at.toLocalDateTime(tz).date
            plan += PlannedNotification(
                content = NotificationContent.RecordBeaten(record.gameTitle),
                at = beatenDate.plus(1, DateTimeUnit.DAY).atTime(RECORD_REMATCH_TIME).toInstant(tz),
            )
        }

        return plan.filter { it.at > input.now }
    }

    companion object {
        /**
         * Racha mínima que merece un aviso. Con 1 día no hay nada que "perder" todavía,
         * y avisar de ello gasta la paciencia del usuario sin ganar retención.
         */
        const val MIN_STREAK_TO_WARN = 2

        /** Aviso de racha: de noche, con margen para jugar antes de medianoche. */
        val STREAK_WARNING_TIME = LocalTime(20, 30)

        /** Recordatorio de misión diaria: a la hora de la cena, antes que el de racha. */
        val DAILY_MISSION_TIME = LocalTime(19, 0)

        /** Reenganche por inactividad: media tarde, la franja con más apertura de apps. */
        val INACTIVITY_TIME = LocalTime(18, 0)

        /** Revancha por récord: a media mañana del día siguiente. */
        val RECORD_REMATCH_TIME = LocalTime(11, 0)
    }
}

/**
 * Foto del estado del jugador que necesita [NotificationPlanner]. Se construye en
 * [NotificationsManager] a partir de los repositorios; el planner no los conoce.
 *
 * @property now instante actual (inyectado, no leído de un reloj global: es lo que
 *   hace testeable el plan).
 * @property timeZone zona horaria del usuario — las horas del plan son locales, un
 *   recordatorio nocturno debe llegar de noche en su reloj, no en UTC.
 * @property lastPlayed instante de la última partida registrada, o null si nunca jugó.
 * @property streakDays días consecutivos jugados (ver `calculateStreakDays`).
 * @property dailyMissionRemaining juegos que faltan hoy para la misión diaria (0 = hecha).
 * @property recordBeaten último récord personal batido pendiente de "revancha", o null.
 */
data class NotificationInputs(
    val now: Instant,
    val timeZone: TimeZone,
    val lastPlayed: Instant?,
    val streakDays: Int,
    val dailyMissionRemaining: Int,
    val recordBeaten: RecordBeatenSignal? = null,
)

/**
 * Señal de "se batió un récord personal": el juego y cuándo ocurrió.
 *
 * @property gameTitle nombre visible del juego (`GameCatalog`), que es lo que se
 *   muestra en el texto del aviso.
 * @property at instante en que se registró la nueva marca.
 */
data class RecordBeatenSignal(
    val gameTitle: String,
    val at: Instant,
)
