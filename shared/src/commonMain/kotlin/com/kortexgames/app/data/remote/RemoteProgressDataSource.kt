package com.kortexgames.app.data.remote

import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.PercentileResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.datetime.Instant
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

    /**
     * Fila de `user_progress` tal cual la devuelve PostgREST. Nota: `game_id` es
     * el UUID del catálogo, que coincide 1:1 con el `gameId` local (la app usa el
     * UUID como identificador de juego), así que no hace falta traducción.
     */
    @Serializable
    private data class ProgressRow(
        val id: String,
        @SerialName("game_id") val gameId: String,
        val score: Int,
        @SerialName("completion_time_ms") val completionTimeMs: Long,
        @SerialName("accuracy_percentage") val accuracyPercentage: Double,
        @SerialName("difficulty_level") val difficultyLevel: Int,
        @SerialName("created_at") val createdAt: Instant,
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

    /**
     * Descarga TODO el historial del usuario autenticado (descarga bidireccional).
     * RLS filtra a las filas propias (`auth.uid() = user_id`), por eso basta un
     * `select` sin `where`. Cada fila trae su `remoteId` para deduplicar en local.
     *
     * @return las partidas en la nube mapeadas a dominio, marcadas `isSynced = true`.
     */
    suspend fun fetchAll(): List<GameProgress> =
        client.postgrest.from("user_progress")
            .select()
            .decodeList<ProgressRow>()
            .map { r ->
                GameProgress(
                    localId = 0L,               // lo asigna SQLite al insertar en local
                    remoteId = r.id,
                    gameId = r.gameId,
                    score = r.score,
                    completionTimeMs = r.completionTimeMs,
                    accuracyPercentage = r.accuracyPercentage,
                    difficultyLevel = r.difficultyLevel,
                    createdAt = r.createdAt,
                    isSynced = true,
                )
            }
}
