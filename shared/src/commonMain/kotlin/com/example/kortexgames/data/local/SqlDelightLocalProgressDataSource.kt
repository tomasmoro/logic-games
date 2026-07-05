package com.example.kortexgames.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.kortexgames.data.local.db.GameProgressEntity
import com.example.kortexgames.data.local.db.LogicGamesDb
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.domain.model.GameResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalProgressDataSource] sobre SQLDelight. Común a Android
 * e iOS: solo cambia el driver (ver DatabaseDriverFactory).
 */
class SqlDelightLocalProgressDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalProgressDataSource {

    private val queries get() = db.progressQueries

    override suspend fun insert(result: GameResult, createdAtEpochMs: Long): Long =
        withContext(io) {
            db.transactionWithResult {
                queries.insert(
                    gameId = result.gameId,
                    score = result.score.toLong(),
                    completionTimeMs = result.completionTimeMs,
                    accuracyPercentage = result.accuracyPercentage,
                    difficultyLevel = result.difficultyLevel.toLong(),
                    createdAt = createdAtEpochMs,
                )
                queries.lastInsertedId().executeAsOne()
            }
        }

    override fun observeHistory(gameId: String?): Flow<List<GameProgress>> {
        val query = if (gameId == null) queries.selectAll() else queries.selectByGame(gameId)
        return query.asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun getUnsynced(): List<GameProgress> = withContext(io) {
        queries.selectUnsynced().executeAsList().map { it.toDomain() }
    }

    override suspend fun markSynced(localId: Long, remoteId: String) = withContext(io) {
        queries.markSynced(remoteId = remoteId, localId = localId)
    }

    override suspend fun countInRange(startEpochMs: Long, endEpochMs: Long): Int =
        withContext(io) {
            queries.countInRange(startEpochMs, endEpochMs).executeAsOne().toInt()
        }

    private fun GameProgressEntity.toDomain() = GameProgress(
        localId = localId,
        remoteId = remoteId,
        gameId = gameId,
        score = score.toInt(),
        completionTimeMs = completionTimeMs,
        accuracyPercentage = accuracyPercentage,
        difficultyLevel = difficultyLevel.toInt(),
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        isSynced = isSynced == 1L,
    )
}
