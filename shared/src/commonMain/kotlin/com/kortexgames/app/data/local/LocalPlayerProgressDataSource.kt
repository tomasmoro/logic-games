package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.PlayerGameProgress
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia local del estado de progresión por juego (récord + reanudación).
 * Es la fuente de verdad de la UI (tarjetas del catálogo, "Continuar nivel") y la
 * cola local-first que se sincroniza con Supabase.
 */
interface LocalPlayerProgressDataSource {

    /** Progresión de un juego (o null si aún no hay marca), reactivo. */
    fun observe(gameId: String): Flow<PlayerGameProgress?>

    /** Progresión de todos los juegos, reactivo (para pintar el catálogo). */
    fun observeAll(): Flow<List<PlayerGameProgress>>

    /** Lectura puntual (no reactiva) usada por la lógica de récord/fusión. */
    suspend fun getByGame(gameId: String): PlayerGameProgress?

    /** Inserta o actualiza la fila del juego (upsert por gameId). */
    suspend fun upsert(progress: PlayerGameProgress)

    /** Filas pendientes de subir a Supabase. */
    suspend fun getUnsynced(): List<PlayerGameProgress>

    /** Marca la fila de un juego como sincronizada. */
    suspend fun markSynced(gameId: String)
}
