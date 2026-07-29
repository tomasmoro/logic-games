package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.UserAchievement
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia local del estado por-usuario de los logros (progreso + desbloqueo).
 * Es la fuente de verdad de la UI de Logros y la cola local-first que se sincroniza
 * con Supabase (`user_achievements`). El catálogo NO vive aquí (está en código,
 * `AchievementCatalog`); esto solo guarda el estado del jugador.
 */
interface LocalAchievementsDataSource {

    /** Estado de todos los logros tocados por el usuario, reactivo. */
    fun observeAll(): Flow<List<UserAchievement>>

    /** Lectura puntual (no reactiva) del estado de un logro, o null si nunca se tocó. */
    suspend fun getById(achievementId: String): UserAchievement?

    /** Inserta o actualiza la fila de un logro (upsert por achievementId). */
    suspend fun upsert(achievement: UserAchievement)

    /** Filas pendientes de subir a Supabase. */
    suspend fun getUnsynced(): List<UserAchievement>

    /** Marca la fila de un logro como sincronizada. */
    suspend fun markSynced(achievementId: String)
}
