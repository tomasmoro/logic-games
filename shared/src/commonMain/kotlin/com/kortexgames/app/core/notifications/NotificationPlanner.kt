package com.kortexgames.app.core.notifications

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
     *  - **Torneo**: hasta tres avisos (apertura, últimas horas, resultados) y solo
     *    del torneo MÁS PRÓXIMO. Ver [planEvent] para el porqué de cada uno.
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

        // --- Torneo: solo el más próximo (ver planEvent) -----------------------
        input.events.minByOrNull { it.startsAt }?.let { plan += planEvent(it, input) }

        return plan.filter { it.at > input.now }
    }

    /**
     * Avisos de un torneo. Como mucho tres, y cada uno responde a una pregunta
     * distinta del jugador:
     *
     *  - **Apertura** ("ya puedes competir"): el gancho principal. Se manda siempre,
     *    haya jugado o no — nadie ha jugado todavía cuando un torneo se abre.
     *  - **Últimas horas**: SOLO a quien aún no ha competido. A quien ya tiene marca,
     *    "se acaba el tiempo" no le dice nada nuevo; es justo el tipo de aviso que
     *    hace que alguien silencie el canal.
     *  - **Resultados**: SOLO a quien compitió. A quien no entró, anunciarle la
     *    clasificación de una tabla en la que no está es ruido.
     *
     * Las tres se corren a una hora decente con [atCivilHour]: un torneo que abre a
     * las 00:00 no puede despertar a nadie, y un aviso nocturno se silencia una vez
     * y para siempre.
     *
     * **Solo el torneo más próximo** genera avisos, igual que la Home solo enseña
     * uno: anunciar tres torneos a la vez convierte el canal en un tablón y diluye
     * el que de verdad está a punto de empezar.
     */
    private fun planEvent(event: EventNotice, input: NotificationInputs): List<PlannedNotification> {
        val tz = input.timeZone
        val plan = mutableListOf<PlannedNotification>()

        // Apertura. Si la hora decente cae ya fuera del torneo (uno corto y de
        // madrugada), no se avisa: llegaría tarde para poder jugarlo.
        val opening = atCivilHour(event.startsAt, tz)
        if (opening < event.endsAt) {
            plan += PlannedNotification(NotificationContent.EventStarting(event.title), opening)
        }

        // Últimas horas. Se descarta si cae antes de que el torneo abra (torneos más
        // cortos que el propio margen) o si desplazarla a hora decente la sacaría
        // fuera de plazo.
        if (!event.hasPlayed) {
            val lastCall = atCivilHour(event.endsAt - ENDING_SOON_MARGIN, tz)
            if (lastCall > event.startsAt && lastCall < event.endsAt) {
                plan += PlannedNotification(NotificationContent.EventEndingSoon(event.title), lastCall)
            }
        }

        // Resultados. El pequeño margen tras el cierre no es decorativo: evita que el
        // aviso llegue en el mismo instante en que el último participante aún está
        // enviando su marca y la clasificación todavía puede moverse.
        if (event.hasPlayed) {
            plan += PlannedNotification(
                content = NotificationContent.EventResults(event.title),
                at = atCivilHour(event.endsAt + RESULTS_MARGIN, tz),
            )
        }
        return plan
    }

    /**
     * Corre [instant] a una hora decente del mismo día si cae de madrugada.
     *
     * Un torneo diario abre a las 00:00 en el reloj del jugador, y avisar a esa hora
     * es la forma más rápida de que alguien desactive el canal entero. Lo que cae
     * entre medianoche y [CIVIL_HOUR_START] se mueve a [CIVIL_HOUR_START] del mismo
     * día; el resto se respeta tal cual, incluida la franja de tarde-noche, que es
     * cuando la gente juega.
     */
    private fun atCivilHour(instant: Instant, tz: TimeZone): Instant {
        val local = instant.toLocalDateTime(tz)
        if (local.time >= CIVIL_HOUR_START) return instant
        return local.date.atTime(CIVIL_HOUR_START).toInstant(tz)
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

        /** Antes de esta hora no se notifica un torneo: se corre aquí (ver atCivilHour). */
        val CIVIL_HOUR_START = LocalTime(10, 0)

        /**
         * Cuánta antelación tiene el aviso de "últimas horas". Tres horas es el punto
         * en que todavía da tiempo a jugar una partida sin prisa, y lo bastante cerca
         * del cierre como para que la urgencia sea real y no un aviso de relleno.
         */
        val ENDING_SOON_MARGIN = 3.hours

        /** Margen tras el cierre antes de anunciar resultados (la tabla se asienta). */
        val RESULTS_MARGIN = 10.minutes
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
 * @property events torneos vigentes conocidos. Solo el más próximo genera avisos
 *   (ver el planificador); se pasa la lista entera y no el elegido para que la
 *   decisión de "cuál manda" viva en el planner, que es donde se puede probar.
 */
data class NotificationInputs(
    val now: Instant,
    val timeZone: TimeZone,
    val lastPlayed: Instant?,
    val streakDays: Int,
    val dailyMissionRemaining: Int,
    val recordBeaten: RecordBeatenSignal? = null,
    val events: List<EventNotice> = emptyList(),
)

/**
 * Lo mínimo que el planificador necesita saber de un torneo. Es un modelo propio y
 * no `GameEvent` a propósito: el planner no debe depender del modelo de dominio de
 * otra capa para algo de lo que solo usa cuatro campos, y así sus tests se escriben
 * sin construir eventos completos.
 *
 * @property hasPlayed si el jugador ya compitió en este torneo. Decide dos de los
 *   tres avisos (ver el planificador).
 */
data class EventNotice(
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val hasPlayed: Boolean,
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
