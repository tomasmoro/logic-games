package com.kortexgames.app.game.daily

import com.kortexgames.app.domain.model.GameProgress
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests de la tira semanal de la tarjeta de entrenamiento. Lo que se prueba es el
 * **encaje del calendario**: que la semana empiece en lunes y que cada día caiga en el
 * estado correcto, que es justo lo que rompería un desfase de un día en la aritmética
 * de época (el bug clásico de "la racha se pinta corrida un día").
 *
 * Todas las fechas se construyen como hora LOCAL y se convierten con la zona del
 * sistema, así el test da igual dónde se ejecute.
 */
class WeeklyTrainingTest {

    /** Reloj detenido: [weeklyTrainingDays] necesita un "hoy" fijo para ser comprobable. */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val tz = TimeZone.currentSystemDefault()

    /** Miércoles 5/8/2026 al mediodía local: mitad de semana, con pasado y futuro visibles. */
    private val wednesdayNoon = LocalDateTime(2026, 8, 5, 12, 0).toInstant(tz)

    /** Partida ficticia jugada [daysAgo] días antes de [wednesdayNoon] (misma hora local). */
    private fun playedDaysAgo(daysAgo: Int): GameProgress = GameProgress(
        localId = daysAgo.toLong(),
        remoteId = null,
        gameId = "game-$daysAgo",
        score = 100,
        completionTimeMs = 1_000,
        accuracyPercentage = 100.0,
        difficultyLevel = 1,
        createdAt = wednesdayNoon.minus(daysAgo, DateTimeUnit.DAY, tz),
        isSynced = false,
    )

    @Test
    fun semanaEmpiezaEnLunesYMarcaLosDiasJugados() {
        // Jugado el lunes (hace 2 días) y hoy miércoles; el martes quedó en blanco.
        val week = weeklyTrainingDays(
            history = listOf(playedDaysAgo(2), playedDaysAgo(0)),
            clock = FixedClock(wednesdayNoon),
        )

        assertEquals(7, week.size)
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), week.map { it.label })
        assertEquals("Lunes", week.first().name)
        assertEquals(
            listOf(
                TrainingDayStatus.DONE,     // lunes: jugado
                TrainingDayStatus.MISSED,   // martes: pasado sin jugar
                TrainingDayStatus.DONE,     // miércoles: hoy y ya jugado
                TrainingDayStatus.PENDING,  // jueves→domingo: aún por llegar
                TrainingDayStatus.PENDING,
                TrainingDayStatus.PENDING,
                TrainingDayStatus.PENDING,
            ),
            week.map { it.status },
        )
    }

    @Test
    fun sinPartidasHoySigueEnJuegoYElPasadoQuedaFallado() {
        val week = weeklyTrainingDays(history = emptyList(), clock = FixedClock(wednesdayNoon))

        assertEquals(
            listOf(
                TrainingDayStatus.MISSED,   // lunes y martes ya pasaron
                TrainingDayStatus.MISSED,
                TrainingDayStatus.TODAY,    // miércoles: aún se puede entrenar
                TrainingDayStatus.PENDING,
                TrainingDayStatus.PENDING,
                TrainingDayStatus.PENDING,
                TrainingDayStatus.PENDING,
            ),
            week.map { it.status },
        )
    }

    @Test
    fun elLunesEsElPrimerDiaDeSuPropiaSemana() {
        // Un lunes NO debe arrastrar la semana anterior: es el primer día de la suya.
        val mondayNoon = LocalDateTime(2026, 8, 3, 12, 0).toInstant(tz)
        val week = weeklyTrainingDays(history = emptyList(), clock = FixedClock(mondayNoon))

        assertEquals(TrainingDayStatus.TODAY, week.first().status)
        assertEquals(List(6) { TrainingDayStatus.PENDING }, week.drop(1).map { it.status })
    }

    @Test
    fun elDomingoEsElUltimoDiaDeSuSemana() {
        val sundayNoon = LocalDateTime(2026, 8, 9, 12, 0).toInstant(tz)
        val week = weeklyTrainingDays(history = emptyList(), clock = FixedClock(sundayNoon))

        assertEquals(TrainingDayStatus.TODAY, week.last().status)
        assertEquals(List(6) { TrainingDayStatus.MISSED }, week.dropLast(1).map { it.status })
    }
}
