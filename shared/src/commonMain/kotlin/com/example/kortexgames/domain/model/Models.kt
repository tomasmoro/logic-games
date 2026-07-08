package com.example.kortexgames.domain.model

import kotlinx.datetime.Instant

/** Plan del usuario — espeja plan_type del backend. */
enum class PlanType { FREE, PREMIUM }

/** Estado de sesión: invitado (solo local) o autenticado (sincroniza). */
sealed interface AuthState {
    data object Guest : AuthState
    data class Authenticated(val userId: String, val plan: PlanType) : AuthState
}

/**
 * Resultado de una partida a persistir. Lo produce el GameEngine al terminar.
 *
 * @property reachedMetric valor de progresión ALCANZADO en esta partida, en la
 *   unidad natural del juego (longitud de secuencia, nivel, mejor reacción en ms…).
 *   Es la base del récord. `null` si la partida no arrojó métrica medible (p. ej.
 *   Reflejos sin ninguna reacción válida). No confundir con [difficultyLevel]
 *   (dificultad de INICIO) ni con [score] (puntaje acumulado).
 */
data class GameResult(
    val gameId: String,
    val score: Int,
    val completionTimeMs: Long,
    val accuracyPercentage: Double,
    val difficultyLevel: Int = 1,
    val dailyChallengeId: String? = null,
    val reachedMetric: Int? = null,
)

/** Fila del historial local/remoto (mapea user_progress). */
data class GameProgress(
    val localId: Long,
    val remoteId: String?,          // null = aún no sincronizado
    val gameId: String,
    val score: Int,
    val completionTimeMs: Long,
    val accuracyPercentage: Double,
    val difficultyLevel: Int,
    val createdAt: Instant,
    val isSynced: Boolean,
)

/**
 * Estado de progresión por juego: mejor marca y punto de reanudación. Mapea la
 * fila de `player_game_progress` (una por juego y usuario). A diferencia de
 * [GameProgress] (log inmutable de partidas), esto es reescribible y se sincroniza
 * como agregado entre dispositivos.
 *
 * @property bestMetric mejor marca en la unidad natural del juego (ver
 *   [GameResult.reachedMetric]). La dirección (mayor/menor mejor) la conoce la app.
 * @property lastLevel nivel de reanudación en juegos LEVELED; null en ENDLESS.
 * @property updatedAt momento de la marca; resuelve qué [lastLevel] gana al fusionar.
 * @property isSynced false = pendiente de subir a Supabase (cola local-first).
 */
data class PlayerGameProgress(
    val gameId: String,
    val bestMetric: Int,
    val lastLevel: Int?,
    val updatedAt: Instant,
    val isSynced: Boolean = true,
)

/** Salida del RPC get_score_percentile / submit_game_result (FASE 2). */
data class PercentileResult(
    val betterThanPct: Double,
    val totalPlayers: Long,
    val rank: Long,
)
