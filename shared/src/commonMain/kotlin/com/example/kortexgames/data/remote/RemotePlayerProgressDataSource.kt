package com.example.kortexgames.data.remote

import com.example.kortexgames.domain.model.PlayerGameProgress
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto a `player_game_progress` en Supabase. El estado de progresión se
 * escribe con un **upsert** (una fila por usuario y juego) y se lee entero (RLS
 * filtra a las filas propias). El servidor no interpreta la métrica: la mejor
 * marca la resuelve el cliente antes de subir (ver la migración 0010).
 */
class RemotePlayerProgressDataSource(
    private val client: SupabaseClient,
) {

    /**
     * Fila de `player_game_progress`. Incluye `user_id` porque el upsert directo
     * (a diferencia del RPC de partidas) lo necesita para que RLS acepte la fila
     * como propia (`auth.uid() = user_id`).
     */
    @Serializable
    private data class PlayerProgressRow(
        @SerialName("user_id") val userId: String,
        @SerialName("game_id") val gameId: String,
        @SerialName("best_metric") val bestMetric: Int,
        @SerialName("last_level") val lastLevel: Int?,
        @SerialName("updated_at") val updatedAt: Instant,
    )

    /** Sube (inserta o actualiza) la progresión de un juego para [userId]. */
    suspend fun upsert(userId: String, progress: PlayerGameProgress) {
        client.postgrest.from("player_game_progress").upsert(
            PlayerProgressRow(
                userId = userId,
                gameId = progress.gameId,
                bestMetric = progress.bestMetric,
                lastLevel = progress.lastLevel,
                updatedAt = progress.updatedAt,
            ),
        ) {
            // Clave del conflicto = PK compuesta; sin esto un re-envío insertaría duplicado.
            onConflict = "user_id,game_id"
        }
    }

    /**
     * Descarga toda la progresión del usuario autenticado. RLS restringe a las
     * filas propias, por eso el `select` no lleva `where`. Se mapea a dominio ya
     * marcada como sincronizada.
     */
    suspend fun fetchAll(): List<PlayerGameProgress> =
        client.postgrest.from("player_game_progress")
            .select()
            .decodeList<PlayerProgressRow>()
            .map { r ->
                PlayerGameProgress(
                    gameId = r.gameId,
                    bestMetric = r.bestMetric,
                    lastLevel = r.lastLevel,
                    updatedAt = r.updatedAt,
                    isSynced = true,
                )
            }
}
