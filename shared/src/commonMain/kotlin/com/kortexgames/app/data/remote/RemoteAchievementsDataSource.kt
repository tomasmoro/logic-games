package com.kortexgames.app.data.remote

import com.kortexgames.app.domain.model.UserAchievement
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Acceso remoto a `user_achievements` en Supabase. Una fila por usuario y logro,
 * escrita con **upsert** (PK compuesta `user_id, achievement_id`) y leída entera
 * (RLS filtra a las filas propias).
 *
 * **Convención de este proyecto:** el backend solo almacena logros **desbloqueados**
 * (con `unlocked_at` real). El progreso parcial hacia un logro se mantiene en local
 * y es recalculable, así que no se sube; esto evita depender del `unlocked_at NOT
 * NULL DEFAULT now()` de la tabla, que no distinguiría "en progreso" de
 * "desbloqueado". Por eso [upsert] exige un `unlockedAt` no nulo.
 */
class RemoteAchievementsDataSource(
    private val client: SupabaseClient,
) {

    /**
     * Fila de `user_achievements`. Incluye `user_id` porque el upsert directo lo
     * necesita para que RLS acepte la fila como propia (`auth.uid() = user_id`).
     */
    @Serializable
    private data class UserAchievementRow(
        @SerialName("user_id") val userId: String,
        @SerialName("achievement_id") val achievementId: String,
        @SerialName("progress") val progress: Int,
        @SerialName("unlocked_at") val unlockedAt: Instant,
    )

    /**
     * Sube (inserta o actualiza) un logro desbloqueado del usuario [userId].
     * Requiere `progress.unlockedAt != null`; si es null se ignora (no subimos
     * progreso parcial, ver nota de clase).
     */
    suspend fun upsert(userId: String, achievement: UserAchievement) {
        val unlockedAt = achievement.unlockedAt ?: return
        client.postgrest.from("user_achievements").upsert(
            UserAchievementRow(
                userId = userId,
                achievementId = achievement.achievementId,
                progress = achievement.progress,
                unlockedAt = unlockedAt,
            ),
        ) {
            // Clave del conflicto = PK compuesta; sin esto un re-envío insertaría duplicado.
            onConflict = "user_id,achievement_id"
        }
    }

    /**
     * Descarga todos los logros del usuario autenticado. RLS restringe a las filas
     * propias, por eso el `select` no lleva `where`. Todas vienen desbloqueadas
     * (por la convención de clase), marcadas ya como sincronizadas.
     */
    suspend fun fetchAll(): List<UserAchievement> =
        client.postgrest.from("user_achievements")
            .select()
            .decodeList<UserAchievementRow>()
            .map { r ->
                UserAchievement(
                    achievementId = r.achievementId,
                    progress = r.progress,
                    unlockedAt = r.unlockedAt,
                    isSynced = true,
                )
            }
}
