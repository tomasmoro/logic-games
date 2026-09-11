package com.kortexgames.app.core.review

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Reglas de **cuándo se invita a valorar** la app.
 *
 * Lo que protegen estos tests tiene consecuencias fuera de la app: una petición
 * prematura o repetida no solo molesta, sino que empuja a puntuar la ficha —una nota
 * pública y acumulativa— a quien todavía no se ha formado una opinión.
 */
class ReviewPromptPolicyTest {

    private val policy = ReviewPromptPolicy()
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    /** Caso base: jugador enganchado, sin nada preguntado todavía. */
    private fun shouldPrompt(
        storeAvailable: Boolean = true,
        gamesPlayed: Int = 50,
        timesDismissed: Int = 0,
        hasRated: Boolean = false,
        lastPromptAt: Instant? = null,
    ) = policy.shouldPrompt(
        storeAvailable = storeAvailable,
        gamesPlayed = gamesPlayed,
        timesDismissed = timesDismissed,
        hasRated = hasRated,
        lastPromptAt = lastPromptAt,
        now = now,
    )

    @Test
    fun `no se pide nada si la plataforma no tiene ficha de tienda`() {
        // Es el caso de iOS mientras la app no esté publicada en la App Store: sin
        // enlace, el CTA no llevaría a ninguna parte.
        assertFalse(shouldPrompt(storeAvailable = false))
    }

    @Test
    fun `no se pide antes de que haya opinión que dar`() {
        assertFalse(shouldPrompt(gamesPlayed = ReviewPromptPolicy.FIRST_OFFER_GAMES - 1))
        assertTrue(shouldPrompt(gamesPlayed = ReviewPromptPolicy.FIRST_OFFER_GAMES))
    }

    @Test
    fun `tras un ahora no espera mucho mas juego y un respiro largo`() {
        val recent = now - (ReviewPromptPolicy.COOLDOWN - 1.days)
        val old = now - (ReviewPromptPolicy.COOLDOWN + 1.days)
        // Ni con las partidas de la segunda oferta se pregunta dentro del respiro...
        assertFalse(
            shouldPrompt(
                gamesPlayed = ReviewPromptPolicy.SECOND_OFFER_GAMES,
                timesDismissed = 1,
                lastPromptAt = recent,
            ),
        )
        // ...ni pasado el respiro si aún no ha jugado bastante más que la primera vez.
        assertFalse(
            shouldPrompt(
                gamesPlayed = ReviewPromptPolicy.SECOND_OFFER_GAMES - 1,
                timesDismissed = 1,
                lastPromptAt = old,
            ),
        )
        assertTrue(
            shouldPrompt(
                gamesPlayed = ReviewPromptPolicy.SECOND_OFFER_GAMES,
                timesDismissed = 1,
                lastPromptAt = old,
            ),
        )
    }

    @Test
    fun `no se pide una tercera vez`() {
        // Dos "ahora no" son un no, por muchas partidas y meses que pasen.
        assertFalse(
            shouldPrompt(
                gamesPlayed = 1_000,
                timesDismissed = ReviewPromptPolicy.MAX_ATTEMPTS,
                lastPromptAt = now - 365.days,
            ),
        )
    }

    @Test
    fun `a quien ya fue a la tienda no se le vuelve a pedir`() {
        assertFalse(shouldPrompt(hasRated = true, lastPromptAt = now - 365.days))
    }
}
