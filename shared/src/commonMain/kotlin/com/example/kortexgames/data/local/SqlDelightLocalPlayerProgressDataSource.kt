package com.example.kortexgames.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.kortexgames.data.local.db.LogicGamesDb
import com.example.kortexgames.data.local.db.PlayerGameProgressEntity
import com.example.kortexgames.domain.model.PlayerGameProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalPlayerProgressDataSource] sobre SQLDelight. Común a
 * Android e iOS: solo cambia el driver (ver DatabaseDriverFactory).
 */
class SqlDelightLocalPlayerProgressDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalPlayerProgressDataSource {

    private val queries get() = db.playerProgressQueries

    override fun observe(gameId: String): Flow<PlayerGameProgress?> =
        queries.selectByGame(gameId).asFlow().mapToOneOrNull(io).map { it?.toDomain() }

    override fun observeAll(): Flow<List<PlayerGameProgress>> =
        queries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getByGame(gameId: String): PlayerGameProgress? = withContext(io) {
        queries.selectByGame(gameId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsert(progress: PlayerGameProgress): Unit = withContext(io) {
        queries.upsert(
            gameId = progress.gameId,
            bestMetric = progress.bestMetric.toLong(),
            lastLevel = progress.lastLevel?.toLong(),
            updatedAt = progress.updatedAt.toEpochMilliseconds(),
            isSynced = if (progress.isSynced) 1L else 0L,
        )
    }

    override suspend fun getUnsynced(): List<PlayerGameProgress> = withContext(io) {
        queries.selectUnsynced().executeAsList().map { it.toDomain() }
    }

    override suspend fun markSynced(gameId: String): Unit = withContext(io) {
        queries.markSynced(gameId)
    }

    private fun PlayerGameProgressEntity.toDomain() = PlayerGameProgress(
        gameId = gameId,
        bestMetric = bestMetric.toInt(),
        lastLevel = lastLevel?.toInt(),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        isSynced = isSynced == 1L,
    )
}
