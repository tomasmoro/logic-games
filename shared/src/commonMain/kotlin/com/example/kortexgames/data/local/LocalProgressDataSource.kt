package com.example.kortexgames.data.local

import app.cash.sqldelight.db.QueryResult
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.domain.model.GameResult
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia local (SQLDelight). Es la fuente de verdad de la UI y lo que
 * permite el modo invitado y el trabajo offline.
 *
 * La implementación real se apoya en las queries generadas por SQLDelight a
 * partir de `sqldelight/.../progress.sq`. Esta interfaz mantiene el repositorio
 * desacoplado del driver concreto (Android vs Native).
 */
interface LocalProgressDataSource {

    /** Inserta una partida como NO sincronizada. Devuelve el id local. */
    suspend fun insert(result: GameResult, createdAtEpochMs: Long): Long

    fun observeHistory(gameId: String?): Flow<List<GameProgress>>

    /** Filas pendientes de subir a Supabase. */
    suspend fun getUnsynced(): List<GameProgress>

    /** Marca una fila local como sincronizada con su id remoto. */
    suspend fun markSynced(localId: Long, remoteId: String): QueryResult<Long>

    /**
     * Fusiona partidas descargadas de la nube (descarga bidireccional). Inserta
     * solo las que aún no existen en local —deduplicando por `remoteId`— y las
     * marca como ya sincronizadas para no reenviarlas en el próximo push.
     *
     * Requisito: cada fila de [remoteRows] debe traer su `remoteId` no nulo (el
     * id de `user_progress` en Supabase).
     */
    suspend fun mergeRemote(remoteRows: List<GameProgress>)

    /** Partidas cuyo createdAt cae dentro del rango [startEpochMs, endEpochMs). */
    suspend fun countInRange(startEpochMs: Long, endEpochMs: Long): Int
}
