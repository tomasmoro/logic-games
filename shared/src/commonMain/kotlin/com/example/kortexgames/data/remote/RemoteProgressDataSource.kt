package com.example.kortexgames.data.remote

import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PercentileResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto a Supabase. Usa el RPC `submit_game_result` de la FASE 2, que
 * inserta la partida (respetando RLS: solo filas propias) y devuelve el percentil
 * en una única llamada.
 */
class RemoteProgressDataSource(
    private val client: SupabaseClient,
) {
    @Serializable
    private data class SubmitParams(
        @SerialName("p_game_id") val gameId: String,
        @SerialName("p_score") val score: Int,
        @SerialName("p_completion_time_ms") val completionTimeMs: Int,
        @SerialName("p_accuracy_percentage") val accuracy: Double,
        @SerialName("p_difficulty_level") val difficulty: Int,
        @SerialName("p_daily_challenge_id") val dailyChallengeId: String? = null,
    )

    @Serializable
    private data class SubmitRow(
        @SerialName("progress_id") val progressId: String,
        @SerialName("better_than_pct") val betterThanPct: Double,
        @SerialName("total_players") val totalPlayers: Long,
        @SerialName("rank") val rank: Long,
    )

    /** Sube la partida y devuelve (idRemoto, percentil). */
    suspend fun submit(result: GameResult): Pair<String, PercentileResult> {
        val rows = client.postgrest.rpc(
            function = "submit_game_result",
            parameters = SubmitParams(
                gameId = result.gameId,
                score = result.score,
                completionTimeMs = result.completionTimeMs.toInt(),
                accuracy = result.accuracyPercentage,
                difficulty = result.difficultyLevel,
                dailyChallengeId = result.dailyChallengeId,
            ),
        ).decodeList<SubmitRow>()

        val row = rows.first()
        return row.progressId to PercentileResult(
            betterThanPct = row.betterThanPct,
            totalPlayers = row.totalPlayers,
            rank = row.rank,
        )
    }
}
