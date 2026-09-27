package com.kortexgames.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.kortexgames.app.data.local.db.EventEntity
import com.kortexgames.app.data.local.db.LogicGamesDb
import com.kortexgames.app.domain.model.EventScoringMode
import com.kortexgames.app.domain.model.GameEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementación de [LocalEventsDataSource] sobre SQLDelight. Común a Android e
 * iOS: solo cambia el driver (ver `DatabaseDriverFactory`).
 */
class SqlDelightLocalEventsDataSource(
    private val db: LogicGamesDb,
    private val io: CoroutineDispatcher,
) : LocalEventsDataSource {

    private val queries get() = db.eventQueries

    override fun observeVisible(cutoff: Instant): Flow<List<GameEvent>> =
        queries.selectVisible(cutoff.toEpochMilliseconds())
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<GameEvent?> =
        queries.selectById(id).asFlow().mapToOneOrNull(io).map { it?.toDomain() }

    override suspend fun upsertAll(events: List<GameEvent>): Unit = withContext(io) {
        // Una sola transacción: el calendario se ve entero o no se ve, sin que la
        // Home llegue a pintar un estado a medio volcar.
        queries.transaction {
            events.forEach { e ->
                queries.upsert(
                    id = e.id,
                    gameId = e.gameId,
                    title = e.title,
                    subtitle = e.subtitle,
                    startsAt = e.startsAt.toEpochMilliseconds(),
                    endsAt = e.endsAt.toEpochMilliseconds(),
                    difficultyLevel = e.difficultyLevel?.toLong(),
                    rankByTime = if (e.rankByTime) 1L else 0L,
                    scoringMode = e.scoringMode.name,
                    attemptsLimit = e.attemptsLimit?.toLong(),
                    adAttemptsLimit = e.adAttemptsLimit.toLong(),
                    puzzleId = e.puzzleId,
                    seed = e.seed,
                    rewardTopN = e.rewardTopN.toLong(),
                    rewardBadgeKey = e.rewardBadgeKey,
                )
            }
        }
    }

    override suspend fun deleteEndedBefore(cutoff: Instant): Unit = withContext(io) {
        queries.deleteEndedBefore(cutoff.toEpochMilliseconds())
    }

    override suspend fun clearAll(): Unit = withContext(io) {
        queries.deleteAll()
    }

    private fun EventEntity.toDomain() = GameEvent(
        id = id,
        gameId = gameId,
        title = title,
        subtitle = subtitle,
        startsAt = Instant.fromEpochMilliseconds(startsAt),
        endsAt = Instant.fromEpochMilliseconds(endsAt),
        difficultyLevel = difficultyLevel?.toInt(),
        rankByTime = rankByTime == 1L,
        scoringMode = EventScoringMode.fromRaw(scoringMode),
        attemptsLimit = attemptsLimit?.toInt(),
        adAttemptsLimit = adAttemptsLimit.toInt(),
        puzzleId = puzzleId,
        seed = seed,
        rewardTopN = rewardTopN.toInt(),
        rewardBadgeKey = rewardBadgeKey,
    )
}
