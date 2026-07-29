package com.kortexgames.app.data.repository

import com.kortexgames.app.data.local.LocalAchievementsDataSource
import com.kortexgames.app.data.remote.RemoteAchievementsDataSource
import com.kortexgames.app.domain.model.AchievementStatus
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.UserAchievement
import com.kortexgames.app.domain.repository.AchievementsRepository
import com.kortexgames.app.game.achievements.AchievementCatalog
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementación **local-first** de los logros (mismo patrón que
 * [PlayerProgressRepositoryImpl]).
 *
 *  Registrar (recordProgress):
 *    1. Sube el progreso (monotónico: nunca baja) y sella `unlockedAt` al alcanzar
 *       el umbral por primera vez.
 *    2. Escribe en local → funciona offline/invitado.
 *    3. Si el logro quedó desbloqueado y hay sesión, sube la fila (solo se suben
 *       desbloqueos; ver [RemoteAchievementsDataSource]).
 *
 *  Sincronizar (sync): descarga los desbloqueos de la nube y los fusiona con local
 *  (mayor progreso gana; `unlockedAt` más temprano gana), luego sube lo pendiente.
 *
 * @param authState proveedor del estado de sesión actual (invitado/autenticado).
 */
class AchievementsRepositoryImpl(
    private val local: LocalAchievementsDataSource,
    private val remote: RemoteAchievementsDataSource,
    private val authState: () -> AuthState,
    private val clock: Clock = Clock.System,
) : AchievementsRepository {

    override fun observeAll(): Flow<List<AchievementStatus>> =
        local.observeAll().map { rows ->
            val byId = rows.associateBy { it.achievementId }
            // Recorremos el catálogo (no las filas locales) para incluir también los
            // logros aún no tocados, con progreso cero.
            AchievementCatalog.all.map { def ->
                val ua = byId[def.id]
                AchievementStatus(
                    achievement = def,
                    progress = ua?.progress ?: 0,
                    unlockedAt = ua?.unlockedAt,
                )
            }
        }

    override suspend fun recordProgress(achievementId: String, progress: Int): Boolean {
        val def = AchievementCatalog.forId(achievementId) ?: return false
        val current = local.getById(achievementId)

        val newProgress = maxOf(progress, current?.progress ?: 0) // monotónico
        val wasUnlocked = current?.unlockedAt != null
        // El desbloqueo se sella UNA vez y no se borra aunque el umbral cambiara.
        val unlockedAt = current?.unlockedAt
            ?: if (newProgress >= def.threshold) clock.now() else null
        val justUnlocked = !wasUnlocked && unlockedAt != null

        // Nada cambió → no reescribimos ni marcamos pendiente en vano.
        if (current != null && newProgress == current.progress && unlockedAt == current.unlockedAt) {
            return false
        }

        // Solo los desbloqueos viajan a la nube; el progreso parcial se queda local
        // (recalculable), así que esas filas nacen "sincronizadas" (nada que subir).
        val hasUpload = unlockedAt != null
        val row = UserAchievement(
            achievementId = achievementId,
            progress = newProgress,
            unlockedAt = unlockedAt,
            isSynced = !hasUpload,
        )
        local.upsert(row)
        if (hasUpload) pushIfPossible(row)
        return justUnlocked
    }

    override suspend fun sync() {
        val userId = currentUserId() ?: return
        pullAndMerge()
        pushUnsynced(userId)
    }

    /** Sube una fila si hay sesión; al lograrlo la marca sincronizada. Silencia la red. */
    private suspend fun pushIfPossible(achievement: UserAchievement) {
        val userId = currentUserId() ?: return
        runCatching {
            remote.upsert(userId, achievement)
            local.markSynced(achievement.achievementId)
        } // fallo de red → queda pendiente, se subirá en sync()
    }

    /**
     * Descarga la nube (solo desbloqueos) y la fusiona con local. Reglas de
     * conflicto: mayor `progress` gana; `unlockedAt` más temprano gana (es el
     * instante real en que se logró). Si tras fusionar local aporta algo que la nube
     * no tiene, la fila queda pendiente para [pushUnsynced].
     */
    private suspend fun pullAndMerge() {
        val remoteRows = runCatching { remote.fetchAll() }.getOrNull() ?: return
        for (r in remoteRows) {
            val localRow = local.getById(r.achievementId)
            if (localRow == null) {
                local.upsert(r) // r ya viene marcada como sincronizada
                continue
            }
            val mergedProgress = maxOf(r.progress, localRow.progress)
            val localUnlocked = localRow.unlockedAt
            val remoteUnlocked = r.unlockedAt // no nulo por convención (solo se suben desbloqueos)
            val mergedUnlockedAt = when {
                localUnlocked == null -> remoteUnlocked
                remoteUnlocked == null -> localUnlocked
                else -> minOf(localUnlocked, remoteUnlocked)
            }
            // ¿local mejora lo que hay en la nube? entonces hay que volver a subir.
            val needsPush = mergedProgress != r.progress || mergedUnlockedAt != r.unlockedAt
            local.upsert(
                UserAchievement(
                    achievementId = r.achievementId,
                    progress = mergedProgress,
                    unlockedAt = mergedUnlockedAt,
                    isSynced = !needsPush,
                ),
            )
        }
    }

    /** Sube todas las filas locales pendientes (desbloqueos aún no propagados). */
    private suspend fun pushUnsynced(userId: String) {
        for (row in local.getUnsynced()) {
            runCatching {
                remote.upsert(userId, row)
                local.markSynced(row.achievementId)
            } // si una falla, seguimos; reintento en la próxima sync
        }
    }

    private fun currentUserId(): String? =
        (authState() as? AuthState.Authenticated)?.userId
}
