package com.kortexgames.app.data.repository

import com.kortexgames.app.data.local.LocalSavedGameStateDataSource
import com.kortexgames.app.domain.model.SavedGameState
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Implementación **solo local** de la partida en curso: a diferencia del resto de
 * repositorios local-first del proyecto, no sube nada a Supabase (ver KDoc de
 * [SavedGameStateRepository]) — es un simple passthrough al datasource local.
 */
class SavedGameStateRepositoryImpl(
    private val local: LocalSavedGameStateDataSource,
    private val clock: Clock = Clock.System,
) : SavedGameStateRepository {

    override fun observe(gameId: String): Flow<String?> =
        local.observe(gameId).map { it?.stateJson }

    override suspend fun save(gameId: String, stateJson: String) {
        local.upsert(SavedGameState(gameId = gameId, stateJson = stateJson, savedAt = clock.now()))
    }

    override suspend fun load(gameId: String): String? = local.get(gameId)?.stateJson

    override suspend fun clear(gameId: String) {
        local.delete(gameId)
    }

    override suspend fun clearAll() {
        local.clearAll()
    }
}
