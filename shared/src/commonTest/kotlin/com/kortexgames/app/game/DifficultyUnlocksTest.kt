package com.kortexgames.app.game

import com.kortexgames.app.domain.model.GameProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests de la regla de desbloqueo de dificultades ([DifficultyUnlocks]): dado un historial
 * de partidas —lo único que hay en la BD—, ¿cuántos escalones quedan abiertos?
 *
 * Es lógica pura sobre `List<GameProgress>`: no necesita repositorios, corrutinas ni
 * ViewModel, que es justo la razón de que el cálculo viva fuera de las pantallas.
 */
class DifficultyUnlocksTest {

    /** Fila de historial mínima: solo importan juego, dificultad y puntaje. */
    private fun progress(gameId: String, difficulty: Int, score: Int) = GameProgress(
        localId = 0L,
        remoteId = null,
        gameId = gameId,
        score = score,
        completionTimeMs = 60_000L,
        accuracyPercentage = 100.0,
        difficultyLevel = difficulty,
        createdAt = Instant.fromEpochMilliseconds(0L),
        isSynced = true,
    )

    @Test
    fun `sin historial solo el primer escalon esta abierto`() {
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_DEFUSER, emptyList()))
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_SUDOKU_MATRIX, emptyList()))
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_2048, emptyList()))
    }

    @Test
    fun `perder no abre el escalon siguiente`() {
        // Derrota = puntaje 0 (así la guardan Buscaminas y Sudoku).
        val history = listOf(progress(GameIds.NEON_DEFUSER, difficulty = 1, score = 0))
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_DEFUSER, history))
    }

    @Test
    fun `ganar abre el escalon siguiente y solo ese`() {
        val history = listOf(progress(GameIds.NEON_DEFUSER, difficulty = 1, score = 820))
        assertEquals(2, DifficultyUnlocks.unlockedTiers(GameIds.NEON_DEFUSER, history))
    }

    @Test
    fun `una victoria en un escalon alto abre todo lo que hay por debajo`() {
        // Historial heredado de cuando todas las dificultades estaban abiertas: haber ganado
        // en Difícil (3) no puede dejar Medio bajo llave por no haberlo jugado nunca.
        val history = listOf(progress(GameIds.NEON_SUDOKU_MATRIX, difficulty = 3, score = 640))
        assertEquals(4, DifficultyUnlocks.unlockedTiers(GameIds.NEON_SUDOKU_MATRIX, history))
    }

    @Test
    fun `nunca se abren mas escalones de los que existen`() {
        val history = listOf(progress(GameIds.NEON_SUDOKU_MATRIX, difficulty = 4, score = 900))
        assertEquals(4, DifficultyUnlocks.unlockedTiers(GameIds.NEON_SUDOKU_MATRIX, history))
    }

    @Test
    fun `el historial de otro juego no desbloquea nada`() {
        val history = listOf(progress(GameIds.NEON_SUDOKU_MATRIX, difficulty = 2, score = 900))
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_DEFUSER, history))
    }

    @Test
    fun `en 2048 hace falta llegar al puntaje minimo del tablero`() {
        val flojo = listOf(progress(GameIds.NEON_2048, difficulty = 1, score = 400))
        assertEquals(1, DifficultyUnlocks.unlockedTiers(GameIds.NEON_2048, flojo))

        val bueno = listOf(progress(GameIds.NEON_2048, difficulty = 1, score = 1_200))
        assertEquals(2, DifficultyUnlocks.unlockedTiers(GameIds.NEON_2048, bueno))
    }

    @Test
    fun `los juegos sin puerta lo ofrecen todo abierto`() {
        // Valor "todo abierto": ningún selector puede quedarse con contenido bajo llave
        // por el mero hecho de que su juego no declare puerta.
        assertEquals(Int.MAX_VALUE, DifficultyUnlocks.unlockedTiers(GameIds.WATER_SORT, emptyList()))
    }

    @Test
    fun `la pista dice que falta y desaparece al abrirlo todo`() {
        assertNotNull(DifficultyUnlocks.nextUnlockHint(GameIds.NEON_DEFUSER, unlockedTiers = 1))
        assertNull(DifficultyUnlocks.nextUnlockHint(GameIds.NEON_DEFUSER, unlockedTiers = 4))
        assertNull(DifficultyUnlocks.nextUnlockHint(GameIds.WATER_SORT, unlockedTiers = 1))
    }

    // --- justUnlockedLabel: "¿la partida que acaba de terminar abrió un escalón?" -------

    @Test
    fun `ganar en la frontera abre el rotulo del siguiente escalon`() {
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.NEON_DEFUSER,
            unlockedBefore = 1,
            attempt = DifficultyAttempt(difficultyLevel = 1, score = 820),
        )
        assertEquals("Medio", label)
    }

    @Test
    fun `perder en la frontera no abre nada`() {
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.NEON_DEFUSER,
            unlockedBefore = 1,
            attempt = DifficultyAttempt(difficultyLevel = 1, score = 0),
        )
        assertNull(label)
    }

    @Test
    fun `ganar por debajo de la frontera no abre nada`() {
        // Rejugar Fácil cuando ya está abierto Difícil (unlockedBefore = 3): esa victoria
        // no es la que abrió nada, así que no hay que volver a celebrarlo.
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.NEON_DEFUSER,
            unlockedBefore = 3,
            attempt = DifficultyAttempt(difficultyLevel = 1, score = 820),
        )
        assertNull(label)
    }

    @Test
    fun `ganar el ultimo escalon no abre nada mas`() {
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.NEON_DEFUSER,
            unlockedBefore = 4,
            attempt = DifficultyAttempt(difficultyLevel = 4, score = 900),
        )
        assertNull(label)
    }

    @Test
    fun `2048 abre el siguiente tablero al llegar al umbral en la frontera`() {
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.NEON_2048,
            unlockedBefore = 1,
            attempt = DifficultyAttempt(difficultyLevel = 1, score = 1_200),
        )
        assertEquals("5×5", label)
    }

    @Test
    fun `los juegos sin puerta nunca reportan desbloqueo`() {
        val label = DifficultyUnlocks.justUnlockedLabel(
            GameIds.WATER_SORT,
            unlockedBefore = 1,
            attempt = DifficultyAttempt(difficultyLevel = 1, score = 999_999),
        )
        assertNull(label)
    }
}
