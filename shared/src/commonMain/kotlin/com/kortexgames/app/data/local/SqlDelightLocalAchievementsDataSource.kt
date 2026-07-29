package com.kortexgames.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.kortexgames.app.data.local.db.LogicGamesDb
import com.kortexgames.app.data.local.db.UserAchievementEntity
import com.kortexgames.app.domain.model.UserAchievement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalAchievementsDataSource] sobre SQLDelight. Común a Android
 * e iOS: solo cambia el driver (ver DatabaseDriverFactory).
 */
class SqlDelightLocalAchievementsDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalAchievementsDataSource {

    private val queries get() = db.achievementsQueries

    override fun observeAll(): Flow<List<UserAchievement>> =
        queries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(achievementId: String): UserAchievement? = withContext(io) {
        queries.selectById(achievementId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsert(achievement: UserAchievement): Unit = withContext(io) {
        queries.upsert(
            achievementId = achievement.achievementId,
            progress = achievement.progress.toLong(),
            unlockedAt = achievement.unlockedAt?.toEpochMilliseconds(),
            isSynced = if (achievement.isSynced) 1L else 0L,
        )
    }

    override suspend fun getUnsynced(): List<UserAchievement> = withContext(io) {
        queries.selectUnsynced().executeAsList().map { it.toDomain() }
    }

    override suspend fun markSynced(achievementId: String): Unit = withContext(io) {
        queries.markSynced(achievementId)
    }

    private fun UserAchievementEntity.toDomain() = UserAchievement(
        achievementId = achievementId,
        progress = progress.toInt(),
        unlockedAt = unlockedAt?.let { Instant.fromEpochMilliseconds(it) },
        isSynced = isSynced == 1L,
    )
}
