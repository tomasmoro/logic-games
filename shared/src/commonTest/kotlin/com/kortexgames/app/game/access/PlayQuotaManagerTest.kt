package com.kortexgames.app.game.access

import com.kortexgames.app.core.ads.RewardResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests del cupo diario de los juegos premium: la política pura ([resolvePlayAccess])
 * y cómo [PlayQuotaManager] la hace cumplir (qué gasta cupo, cuándo pide anuncio y
 * cuándo concede sin él).
 */
class PlayQuotaManagerTest {

    private class FixedClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /** Store en memoria con la misma semántica de "otro día = empieza de cero". */
    private class FakeStore : PlayQuotaStore {
        val counts = MutableStateFlow<Map<String, DailyPlayCount>>(emptyMap())

        override fun observe(gameId: String): Flow<DailyPlayCount> =
            counts.map { it[gameId] ?: DailyPlayCount(null, 0) }

        override suspend fun increment(gameId: String, isoDate: String) {
            val current = counts.value[gameId]
            val used = if (current?.date == isoDate) current.used else 0
            counts.value = counts.value + (gameId to DailyPlayCount(isoDate, used + 1))
        }
    }

    private val tz = TimeZone.currentSystemDefault()
    private val clock = FixedClock(LocalDateTime(2026, 9, 27, 12, 0).toInstant(tz))
    private val store = FakeStore()
    private val exempt = MutableStateFlow(false)
    private var premiumUser = false
    private var adResult = RewardResult.EARNED
    private var adsShown = 0

    private val manager = PlayQuotaManager(
        store = store,
        showRewardedAd = { adsShown++; adResult },
        isPremiumGame = { it == PREMIUM },
        isPremiumUser = { premiumUser },
        exempt = exempt,
        clock = clock,
    )

    @Test
    fun politicaCuentaLasPartidasRestantesYPideAnuncioAlAgotarlas() {
        assertEquals(PlayAccess.Free(5, 5), resolvePlayAccess(true, false, false, usedToday = 0))
        assertEquals(PlayAccess.Free(1, 5), resolvePlayAccess(true, false, false, usedToday = 4))
        assertEquals(PlayAccess.NeedsAd, resolvePlayAccess(true, false, false, usedToday = 5))
    }

    @Test
    fun politicaNoAplicaCupoAJuegosLibresPremiumNiPartidasExentas() {
        assertEquals(PlayAccess.Unlimited, resolvePlayAccess(false, false, false, usedToday = 9))
        assertEquals(PlayAccess.Unlimited, resolvePlayAccess(true, true, false, usedToday = 9))
        assertEquals(PlayAccess.Unlimited, resolvePlayAccess(true, false, true, usedToday = 9))
    }

    @Test
    fun cincoPartidasGratisYLaSextaCuestaUnAnuncio() = runTest {
        repeat(FREE_DAILY_PLAYS) { assertTrue(manager.requestPlay(PREMIUM)) }
        assertEquals(0, adsShown)
        assertEquals(PlayAccess.NeedsAd, manager.access(PREMIUM).first())

        assertTrue(manager.requestPlay(PREMIUM))
        assertEquals(1, adsShown)
    }

    @Test
    fun cadaAnuncioDaUnaSolaPartidaYNoHayTope() = runTest {
        repeat(FREE_DAILY_PLAYS) { manager.requestPlay(PREMIUM) }
        repeat(10) { assertTrue(manager.requestPlay(PREMIUM)) }
        assertEquals(10, adsShown)
        assertEquals(PlayAccess.NeedsAd, manager.access(PREMIUM).first())
    }

    @Test
    fun cerrarElAnuncioAntesDeTiempoDeniegaLaPartida() = runTest {
        repeat(FREE_DAILY_PLAYS) { manager.requestPlay(PREMIUM) }
        adResult = RewardResult.DISMISSED
        assertFalse(manager.requestPlay(PREMIUM))
    }

    @Test
    fun sinAnuncioDisponibleSeConcedeLaPartida() = runTest {
        repeat(FREE_DAILY_PLAYS) { manager.requestPlay(PREMIUM) }
        adResult = RewardResult.UNAVAILABLE
        assertTrue(manager.requestPlay(PREMIUM))
    }

    @Test
    fun elCupoSeRenuevaAlCambiarDeDia() = runTest {
        repeat(FREE_DAILY_PLAYS) { manager.requestPlay(PREMIUM) }
        clock.instant = LocalDateTime(2026, 9, 28, 0, 1).toInstant(tz)
        assertEquals(PlayAccess.Free(5, 5), manager.access(PREMIUM).first())
    }

    @Test
    fun elCupoEsPorJuego() = runTest {
        val otherPremium = PlayQuotaManager(
            store = store,
            showRewardedAd = { adResult },
            isPremiumGame = { true },
            isPremiumUser = { false },
            exempt = exempt,
            clock = clock,
        )
        repeat(FREE_DAILY_PLAYS) { otherPremium.requestPlay(PREMIUM) }
        assertEquals(PlayAccess.Free(5, 5), otherPremium.access("otro-juego").first())
    }

    @Test
    fun premiumYPartidasExentasNoGastanCupo() = runTest {
        premiumUser = true
        repeat(10) { assertTrue(manager.requestPlay(PREMIUM)) }
        premiumUser = false
        exempt.value = true
        repeat(10) { assertTrue(manager.requestPlay(PREMIUM)) }
        exempt.value = false

        assertEquals(0, adsShown)
        assertEquals(PlayAccess.Free(5, 5), manager.access(PREMIUM).first())
    }

    @Test
    fun juegosNoPremiumNuncaGastanCupo() = runTest {
        repeat(10) { assertTrue(manager.requestPlay("libre")) }
        assertEquals(0, adsShown)
        assertEquals(PlayAccess.Unlimited, manager.access("libre").first())
    }

    private companion object {
        const val PREMIUM = "premium-game"
    }
}
