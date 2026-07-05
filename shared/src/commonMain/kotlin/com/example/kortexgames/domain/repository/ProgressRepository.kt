package com.example.kortexgames.domain.repository

import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PercentileResult
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de persistencia de progreso con estrategia **local-first**:
 *
 *  - [saveResult] siempre escribe en local primero (funciona offline / invitado).
 *    Si hay sesión y red, empuja a Supabase y devuelve el percentil.
 *  - [observeHistory] lee SIEMPRE de local (fuente de verdad para la UI).
 *  - [syncPending] sube lo no sincronizado; se llama al iniciar sesión y al
 *    recuperar conectividad.
 */
interface ProgressRepository {

    /**
     * Guarda una partida. Devuelve el percentil si se pudo consultar al backend
     * (usuario autenticado + online); null en modo invitado/offline.
     */
    suspend fun saveResult(result: GameResult): PercentileResult?

    /** Historial observable desde local. gameId null = todos los juegos. */
    fun observeHistory(gameId: String? = null): Flow<List<GameProgress>>

    /** Sube a Supabase todas las filas locales sin sincronizar. */
    suspend fun syncPending()

    /** Nº de partidas jugadas HOY (para el Daily Goal). */
    suspend fun countPlayedToday(): Int
}
