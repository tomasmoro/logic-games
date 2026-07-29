package com.kortexgames.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.kortexgames.app.data.local.db.LevelBestTimeEntity
import com.kortexgames.app.data.local.db.LogicGamesDb
import com.kortexgames.app.domain.model.LevelBestTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalLevelTimeDataSource] sobre SQLDelight. Común a Android e iOS:
 * solo cambia el driver (ver DatabaseDriverFactory). Mismo patrón que
 * [SqlDelightLocalPlayerProgressDataSource].
 */
class SqlDelightLocalLevelTimeDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalLevelTimeDataSource {

    private val queries get() = db.levelTimeQueries

    override fun observeByGame(gameId: String): Flow<List<LevelBestTime>> =
        queries.selectByGame(gameId).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getOne(gameId: String, level: Int): LevelBestTime? = withContext(io) {
        queries.selectOne(gameId, level.toLong()).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsert(time: LevelBestTime): Unit = withContext(io) {
        queries.upsert(
            gameId = time.gameId,
            level = time.level.toLong(),
            bestTimeMs = time.bestTimeMs,
            updatedAt = time.updatedAt.toEpochMilliseconds(),
            isSynced = if (time.isSynced) 1L else 0L,
        )
    }

    override suspend fun getUnsynced(): List<LevelBestTime> = withContext(io) {
        queries.selectUnsynced().executeAsList().map { it.toDomain() }
    }

    override suspend fun markSynced(gameId: String, level: Int): Unit = withContext(io) {
        queries.markSynced(gameId, level.toLong())
    }

    private fun LevelBestTimeEntity.toDomain() = LevelBestTime(
        gameId = gameId,
        level = level.toInt(),
        bestTimeMs = bestTimeMs,
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        isSynced = isSynced == 1L,
    )
}
