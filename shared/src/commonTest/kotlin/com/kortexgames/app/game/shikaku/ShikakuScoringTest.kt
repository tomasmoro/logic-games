package com.kortexgames.app.game.shikaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Balance de la puntuación: el nivel domina y rectificar o reiniciar siempre cuesta. */
class ShikakuScoringTest {

    private fun score(level: Int, elapsedMs: Long = 1, corrections: Int = 0, restarts: Int = 0) =
        ShikakuScoring.score(level, 25, elapsedMs, ShikakuScoring.efficiency(8, corrections), restarts)

    @Test
    fun partidaPerfectaSumaTodosLosBonus() {
        assertEquals(3 * 1_000 + 400 + 300, score(level = 3))
    }

    @Test
    fun corregirReiniciarYTardarRestan() {
        val perfect = score(level = 3)
        assertTrue(score(level = 3, corrections = 4) < perfect)
        assertTrue(score(level = 3, restarts = 2) < perfect)
        assertTrue(score(level = 3, elapsedMs = 10 * 60_000) < perfect)
    }

    @Test
    fun elCastigoNuncaBajaDeLaBaseDelNivelAnterior() {
        val worst = score(level = 4, elapsedMs = Long.MAX_VALUE / 2, corrections = 1_000, restarts = 1_000)
        assertTrue(worst > 3 * ShikakuScoring.LEVEL_POINTS, "peor nivel 4 = $worst")
    }
}
