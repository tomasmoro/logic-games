package com.kortexgames.app.data.remote

import com.kortexgames.app.domain.model.LevelBestTime
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto a `player_level_time` en Supabase: mejor tiempo por nivel (menor =
 * mejor). Igual que [RemotePlayerProgressDataSource], se escribe con **upsert** (una
 * fila por usuario/juego/nivel) y se lee entero (RLS filtra a las filas propias). El
 * servidor no interpreta la marca: el cliente sube ya el mínimo (ver la migración 0018).
 */
class RemoteLevelTimeDataSource(
    private val client: SupabaseClient,
) {

    /**
     * Fila de `player_level_time`. Incluye `user_id` porque el upsert directo lo
     * necesita para que RLS acepte la fila como propia (`auth.uid() = user_id`).
     */
    @Serializable
    private data class LevelTimeRow(
        @SerialName("user_id") val userId: String,
        @SerialName("game_id") val gameId: String,
        @SerialName("level") val level: Int,
        @SerialName("best_time_ms") val bestTimeMs: Long,
        @SerialName("updated_at") val updatedAt: Instant,
    )

    /** Sube (inserta o actualiza) el mejor tiempo de un nivel para [userId]. */
    suspend fun upsert(userId: String, time: LevelBestTime) {
        client.postgrest.from("player_level_time").upsert(
            LevelTimeRow(
                userId = userId,
                gameId = time.gameId,
                level = time.level,
                bestTimeMs = time.bestTimeMs,
                updatedAt = time.updatedAt,
            ),
        ) {
            // Clave del conflicto = PK compuesta; sin esto un re-envío insertaría duplicado.
            onConflict = "user_id,game_id,level"
        }
    }

    /**
     * Descarga todos los tiempos por nivel del usuario autenticado. RLS restringe a
     * las filas propias, por eso el `select` no lleva `where`. Se mapea a dominio ya
     * marcado como sincronizado.
     */
    suspend fun fetchAll(): List<LevelBestTime> =
        client.postgrest.from("player_level_time")
            .select()
            .decodeList<LevelTimeRow>()
            .map { r ->
                LevelBestTime(
                    gameId = r.gameId,
                    level = r.level,
                    bestTimeMs = r.bestTimeMs,
                    updatedAt = r.updatedAt,
                    isSynced = true,
                )
            }
}
