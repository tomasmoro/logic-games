package com.example.kortexgames.domain.model

import kotlinx.datetime.Instant

/** Plan del usuario — espeja plan_type del backend. */
enum class PlanType { FREE, PREMIUM }

/** Estado de sesión: invitado (solo local) o autenticado (sincroniza). */
sealed interface AuthState {
    data object Guest : AuthState
    data class Authenticated(val userId: String, val plan: PlanType) : AuthState
}

/** Resultado de una partida a persistir. Lo produce el GameEngine al terminar. */
data class GameResult(
    val gameId: String,
    val score: Int,
    val completionTimeMs: Long,
    val accuracyPercentage: Double,
    val difficultyLevel: Int = 1,
    val dailyChallengeId: String? = null,
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

/** Salida del RPC get_score_percentile / submit_game_result (FASE 2). */
data class PercentileResult(
    val betterThanPct: Double,
    val totalPlayers: Long,
    val rank: Long,
)
