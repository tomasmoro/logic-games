package com.example.kortexgames.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.kortexgames.data.local.db.LogicGamesDb
import com.example.kortexgames.data.local.db.SavedGameStateEntity
import com.example.kortexgames.domain.model.SavedGameState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalSavedGameStateDataSource] sobre SQLDelight. Común a Android
 * e iOS: solo cambia el driver (ver DatabaseDriverFactory). Mismo patrón que
 * [SqlDelightLocalPlayerProgressDataSource], sin campos de sync.
 */
class SqlDelightLocalSavedGameStateDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalSavedGameStateDataSource {

    private val queries get() = db.savedGameStateQueries

    override fun observe(gameId: String): Flow<SavedGameState?> =
        queries.selectByGame(gameId).asFlow().mapToOneOrNull(io).map { it?.toDomain() }

    override suspend fun get(gameId: String): SavedGameState? = withContext(io) {
        queries.selectByGame(gameId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsert(saved: SavedGameState): Unit = withContext(io) {
        queries.upsert(
            gameId = saved.gameId,
            stateJson = saved.stateJson,
            savedAt = saved.savedAt.toEpochMilliseconds(),
        )
    }

    override suspend fun delete(gameId: String): Unit = withContext(io) {
        queries.delete(gameId)
    }

    private fun SavedGameStateEntity.toDomain() = SavedGameState(
        gameId = gameId,
        stateJson = stateJson,
        savedAt = Instant.fromEpochMilliseconds(savedAt),
    )
}
