package com.kortexgames.app.data.remote

import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.LeaderboardEntry
import com.kortexgames.app.domain.model.PercentileResult
import com.kortexgames.app.game.GameRankingScopes
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto a Supabase. Usa el RPC `submit_game_result` de la FASE 2, que
 * inserta la partida (respetando RLS: solo filas propias) y devuelve el percentil
 * en una única llamada, y `get_game_ranking` (migración 0027) para la comparativa
 * con el mundo que se pinta al terminar.
 */
class RemoteProgressDataSource(
    private val client: SupabaseClient,
) {
    /**
     * Lo que deja una subida de partida: el id remoto (para marcar la fila local como
     * sincronizada) y la comparativa que la UI celebra.
     *
     * @property ranking null si no se pidió (sincronización en diferido) o si su RPC
     *   falló; que sea independiente del percentil es deliberado —una comparativa a
     *   medias es mejor que perder también el percentil por un solo error.
     */
    data class SubmitOutcome(
        val remoteId: String,
        val percentile: PercentileResult,
        val ranking: GameRanking?,
    )

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

    /** Parámetros del RPC `get_game_ranking` (migraciones 0027 y 0028). */
    @Serializable
    private data class RankingParams(
        @SerialName("p_game_id") val gameId: String,
        @SerialName("p_score") val score: Int,
        // El id de la partida recién insertada: el backend lo excluye al calcular la
        // mejor marca mundial anterior, si no la partida se compararía consigo misma
        // y nunca podría ser récord global.
        @SerialName("p_progress_id") val progressId: String,
        // Solo en los juegos con dificultad elegible: acota el ranking a esa
        // dificultad para que un Fácil rápido no aplaste a un Experto (ver
        // `GameRankingScopes`). null ⇒ tabla única para todo el juego.
        @SerialName("p_difficulty_level") val difficultyLevel: Int? = null,
        // Criterio de orden: por defecto puntos (mayor gana). Los juegos donde lo que
        // se compara es la velocidad piden tiempo (menor gana); ver `GameRankingScopes`.
        @SerialName("p_rank_by_time") val rankByTime: Boolean = false,
        // Tiempo de ESTA partida; el backend solo lo mira al rankear por tiempo, para
        // decidir el récord mundial con la misma métrica con la que ordena.
        @SerialName("p_completion_time_ms") val completionTimeMs: Int? = null,
    )

    /**
     * Objeto jsonb que devuelve `get_game_ranking`. Es un único valor (no una tabla)
     * porque mezcla escalares con la lista de vecinos; ver el porqué en la migración.
     */
    @Serializable
    private data class RankingRow(
        val rank: Long,
        @SerialName("total_players") val totalPlayers: Long,
        @SerialName("better_than_pct") val betterThanPct: Double,
        @SerialName("is_global_record") val isGlobalRecord: Boolean,
        val entries: List<EntryRow> = emptyList(),
    )

    @Serializable
    private data class EntryRow(
        val rank: Long,
        @SerialName("display_name") val displayName: String? = null,
        val score: Int,
        @SerialName("is_current_user") val isCurrentUser: Boolean,
    )

    /**
     * Sube la partida y, si [withRanking], resuelve además la comparativa mundial.
     *
     * @param withRanking false en la sincronización diferida de partidas antiguas: ahí
     *   nadie va a ver la comparativa, así que no se paga una segunda RPC por fila.
     */
    suspend fun submit(result: GameResult, withRanking: Boolean = false): SubmitOutcome {
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
        return SubmitOutcome(
            remoteId = row.progressId,
            percentile = PercentileResult(
                betterThanPct = row.betterThanPct,
                totalPlayers = row.totalPlayers,
                rank = row.rank,
            ),
            // La partida YA está guardada llegados aquí: un fallo de la comparativa no
            // puede tumbar la subida, así que se aísla con runCatching.
            ranking = if (withRanking) runCatching { fetchRanking(result, row.progressId) }.getOrNull() else null,
        )
    }

    /**
     * Comparativa por jugadores del juego. Devuelve null si el backend responde `null`
     * (el jugador no tiene ninguna partida en ese juego — no debería pasar justo tras
     * insertar, pero la RPC lo contempla y aquí se traduce a "no disponible").
     */
    private suspend fun fetchRanking(result: GameResult, progressId: String): GameRanking? {
        // La dificultad solo viaja en los juegos que separan su tabla por ella; en el
        // resto va null y el backend rankea el juego entero de una pieza.
        val byDifficulty = GameRankingScopes.isRankedByDifficulty(result.gameId)
        val byTime = GameRankingScopes.isRankedByTime(result.gameId)
        val row = client.postgrest.rpc(
            function = "get_game_ranking",
            parameters = RankingParams(
                gameId = result.gameId,
                score = result.score,
                progressId = progressId,
                difficultyLevel = result.difficultyLevel.takeIf { byDifficulty },
                rankByTime = byTime,
                completionTimeMs = result.completionTimeMs.toInt().takeIf { byTime },
            ),
        ).decodeAsOrNull<RankingRow>() ?: return null

        return GameRanking(
            rank = row.rank,
            totalPlayers = row.totalPlayers,
            betterThanPct = row.betterThanPct,
            isGlobalRecord = row.isGlobalRecord,
            difficultyLabel = GameRankingScopes.difficultyLabel(result.gameId, result.difficultyLevel),
            rankedByTime = byTime,
            entries = row.entries.map {
                LeaderboardEntry(
                    rank = it.rank,
                    displayName = it.displayName,
                    score = it.score,
                    isCurrentUser = it.isCurrentUser,
                )
            },
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
