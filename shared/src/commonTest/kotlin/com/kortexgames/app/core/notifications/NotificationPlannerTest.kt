package com.kortexgames.app.core.notifications

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Reglas de retención del módulo de notificaciones.
 *
 * Se prueba el [NotificationPlanner] y no el manager entero a propósito: el planner
 * concentra TODAS las decisiones ("¿toca avisar?", "¿a qué hora?") y es puro, así que
 * estas reglas se verifican sin emulador, sin simulador y sin relojes reales —que es
 * la política de verificación del proyecto (CLAUDE.md §6).
 *
 * La zona horaria se fija a UTC en todos los casos para que las horas locales
 * esperadas no dependan de dónde corra el test.
 */
class NotificationPlannerTest {

    private val planner = NotificationPlanner()
    private val tz = TimeZone.UTC

    /** 10:00 del 11 de agosto de 2026, en UTC. */
    private val now = at(2026, 8, 11, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(tz)

    private fun inputs(
        lastPlayed: Instant? = null,
        streakDays: Int = 0,
        missionRemaining: Int = 0,
        recordBeaten: RecordBeatenSignal? = null,
    ) = NotificationInputs(
        now = now,
        timeZone = tz,
        lastPlayed = lastPlayed,
        streakDays = streakDays,
        dailyMissionRemaining = missionRemaining,
        recordBeaten = recordBeaten,
    )

    private fun List<PlannedNotification>.of(kind: NotificationKind) =
        firstOrNull { it.kind == kind }

    @Test
    fun `avisa de la racha en riesgo si aun no se ha jugado hoy`() {
        val plan = planner.plan(inputs(lastPlayed = now - 1.days, streakDays = 5))

        val warning = plan.of(NotificationKind.STREAK_AT_RISK)
        assertTrue(warning != null, "debía programarse el aviso de racha")
        assertEquals(at(2026, 8, 11, 20, 30), warning.at)
        assertEquals(
            NotificationContent.StreakAtRisk(5),
            warning.content,
            "el mensaje debe llevar los días reales de racha",
        )
    }

    @Test
    fun `no avisa de la racha si ya se jugo hoy`() {
        val plan = planner.plan(inputs(lastPlayed = now, streakDays = 5))

        assertNull(plan.of(NotificationKind.STREAK_AT_RISK))
    }

    @Test
    fun `no avisa de una racha de un solo dia`() {
        // Con un día no hay nada que "perder": avisar solo gastaría la paciencia del
        // usuario (ver NotificationPlanner.MIN_STREAK_TO_WARN).
        val plan = planner.plan(inputs(lastPlayed = now - 1.days, streakDays = 1))

        assertNull(plan.of(NotificationKind.STREAK_AT_RISK))
    }

    @Test
    fun `recuerda la mision diaria solo si se jugo hoy y quedan juegos`() {
        val conJuegosPendientes = planner.plan(inputs(lastPlayed = now, missionRemaining = 2))
        val misionHecha = planner.plan(inputs(lastPlayed = now, missionRemaining = 0))

        val reminder = conJuegosPendientes.of(NotificationKind.DAILY_MISSION)
        assertTrue(reminder != null, "debía programarse el recordatorio de misión")
        assertEquals(at(2026, 8, 11, 19, 0), reminder.at)
        assertEquals(NotificationContent.DailyMissionPending(2), reminder.content)
        assertNull(misionHecha.of(NotificationKind.DAILY_MISSION))
    }

    @Test
    fun `racha y mision diaria nunca coinciden el mismo dia`() {
        // Son excluyentes por construcción: una exige haber jugado hoy y la otra no.
        // Este test protege esa invariante — es lo que evita dos avisos la misma tarde.
        val jugoHoy = planner.plan(inputs(lastPlayed = now, streakDays = 5, missionRemaining = 2))
        val noJugoHoy = planner.plan(inputs(lastPlayed = now - 1.days, streakDays = 5, missionRemaining = 2))

        assertNull(jugoHoy.of(NotificationKind.STREAK_AT_RISK))
        assertTrue(jugoHoy.of(NotificationKind.DAILY_MISSION) != null)
        assertTrue(noJugoHoy.of(NotificationKind.STREAK_AT_RISK) != null)
        assertNull(noJugoHoy.of(NotificationKind.DAILY_MISSION))
    }

    @Test
    fun `programa la cadena completa de inactividad desde la ultima partida`() {
        val lastPlayed = at(2026, 8, 10, 21, 0)

        val plan = planner.plan(inputs(lastPlayed = lastPlayed, streakDays = 1))

        assertEquals(at(2026, 8, 13, 18, 0), plan.of(NotificationKind.INACTIVITY_3D)?.at)
        assertEquals(at(2026, 8, 17, 18, 0), plan.of(NotificationKind.INACTIVITY_7D)?.at)
        assertEquals(at(2026, 8, 24, 18, 0), plan.of(NotificationKind.INACTIVITY_14D)?.at)
    }

    @Test
    fun `sin partidas todavia la cadena arranca desde ahora`() {
        // Instalar y no jugar también debe entrar en el reenganche.
        val plan = planner.plan(inputs(lastPlayed = null))

        assertEquals(at(2026, 8, 14, 18, 0), plan.of(NotificationKind.INACTIVITY_3D)?.at)
    }

    @Test
    fun `descarta los avisos cuyo instante ya paso`() {
        // Última partida hace 10 días: los escalones de 3 y 7 días quedaron atrás y
        // solo tiene sentido el de 14.
        val plan = planner.plan(inputs(lastPlayed = now - 10.days))

        assertNull(plan.of(NotificationKind.INACTIVITY_3D))
        assertNull(plan.of(NotificationKind.INACTIVITY_7D))
        assertTrue(plan.of(NotificationKind.INACTIVITY_14D) != null)
        assertTrue(plan.all { it.at > now }, "ningún aviso planificado puede estar vencido")
    }

    @Test
    fun `la revancha por record se difiere al dia siguiente`() {
        val record = RecordBeatenSignal(gameTitle = "Crucigrama Neón", at = at(2026, 8, 11, 9, 30))

        val plan = planner.plan(inputs(lastPlayed = now, recordBeaten = record))

        val rematch = plan.of(NotificationKind.RECORD_BEATEN)
        assertTrue(rematch != null, "debía programarse la revancha")
        assertEquals(at(2026, 8, 12, 11, 0), rematch.at)
        assertEquals(NotificationContent.RecordBeaten("Crucigrama Neón"), rematch.content)
    }

    @Test
    fun `un record viejo ya no genera revancha`() {
        val record = RecordBeatenSignal(gameTitle = "2048", at = now - 5.days)

        val plan = planner.plan(inputs(lastPlayed = now, recordBeaten = record))

        assertNull(plan.of(NotificationKind.RECORD_BEATEN))
    }

    @Test
    fun `el planificador solo emite avisos que gobierna en local`() {
        // "Te superaron" solo puede llegar por push (el dispositivo no sabe lo que
        // hacen los demás) y el de prueba solo lo dispara la herramienta de depuración.
        // Ninguno de los dos debe aparecer nunca en un plan.
        val plan = planner.plan(
            inputs(
                lastPlayed = now - 1.days,
                streakDays = 9,
                missionRemaining = 3,
                recordBeaten = RecordBeatenSignal("Neon Sudoku", now),
            ),
        )

        assertFalse(plan.any { it.kind == NotificationKind.RECORD_BROKEN })
        assertFalse(plan.any { it.kind == NotificationKind.DEBUG_TEST })
        assertTrue(plan.all { it.kind.isManaged }, "el plan solo puede contener avisos gestionados")
    }

    @Test
    fun `las horas del plan son locales a la zona del usuario`() {
        // Mismo instante, zona con desfase: el aviso de racha debe caer a las 20:30
        // del reloj del usuario, no a las 20:30 UTC.
        val madrid = TimeZone.of("Europe/Madrid")
        val plan = planner.plan(
            NotificationInputs(
                now = now,
                timeZone = madrid,
                lastPlayed = now - 1.days,
                streakDays = 3,
                dailyMissionRemaining = 0,
            ),
        )

        val local = plan.of(NotificationKind.STREAK_AT_RISK)!!.at.toLocalDateTime(madrid)
        assertEquals(20, local.hour)
        assertEquals(30, local.minute)
    }
}
