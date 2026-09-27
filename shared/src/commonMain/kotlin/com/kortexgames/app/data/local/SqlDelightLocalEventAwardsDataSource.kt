package com.kortexgames.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.kortexgames.app.data.local.db.EventAwardEntity
import com.kortexgames.app.data.local.db.LogicGamesDb
import com.kortexgames.app.domain.model.EventAward
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Implementación de [LocalEventAwardsDataSource] sobre SQLDelight. Común a Android
 * e iOS: solo cambia el driver (ver `DatabaseDriverFactory`).
 */
class SqlDelightLocalEventAwardsDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalEventAwardsDataSource {

    private val queries get() = db.eventAwardQueries

    override fun observeAll(): Flow<List<EventAward>> =
        queries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun replaceAll(awards: List<EventAward>): Unit = withContext(io) {
        // Borrado + alta en UNA transacción: el perfil nunca debe llegar a pintar la
        // vitrina vacía a mitad del reemplazo.
        queries.transaction {
            queries.deleteAll()
            awards.forEach { a ->
                queries.insert(
                    eventId = a.eventId,
                    eventTitle = a.eventTitle,
                    gameId = a.gameId,
                    position = a.position,
                    totalPlayers = a.totalPlayers,
                    badgeKey = a.badgeKey,
                    endedAt = a.endedAt.toEpochMilliseconds(),
                )
            }
        }
    }

    override suspend fun clearAll(): Unit = withContext(io) {
        queries.deleteAll()
    }

    private fun EventAwardEntity.toDomain() = EventAward(
        eventId = eventId,
        eventTitle = eventTitle,
        gameId = gameId,
        position = position,
        totalPlayers = totalPlayers,
        badgeKey = badgeKey,
        endedAt = Instant.fromEpochMilliseconds(endedAt),
    )
}
