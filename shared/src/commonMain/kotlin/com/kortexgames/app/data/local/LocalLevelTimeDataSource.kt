package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.LevelBestTime
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia local del **mejor tiempo por nivel** (menor = mejor). Es la fuente de
 * verdad de la UI (badge de tiempo en el selector de niveles) y la cola local-first
 * que se sincroniza con Supabase. Espeja `player_level_time`.
 */
interface LocalLevelTimeDataSource {

    /** Mejores tiempos de todos los niveles de un juego, reactivo (para el selector). */
    fun observeByGame(gameId: String): Flow<List<LevelBestTime>>

    /** Lectura puntual (no reactiva) de un nivel; la usa la lógica de "mejora la marca". */
    suspend fun getOne(gameId: String, level: Int): LevelBestTime?

    /** Inserta o actualiza la fila (upsert por gameId+level). */
    suspend fun upsert(time: LevelBestTime)

    /** Filas pendientes de subir a Supabase. */
    suspend fun getUnsynced(): List<LevelBestTime>

    /** Marca la fila de un (juego, nivel) como sincronizada. */
    suspend fun markSynced(gameId: String, level: Int)
}
