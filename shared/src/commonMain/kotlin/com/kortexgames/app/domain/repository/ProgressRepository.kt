package com.kortexgames.app.domain.repository

import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de persistencia de progreso con estrategia **local-first**:
 *
 *  - [saveResult] siempre escribe en local primero (funciona offline / invitado).
 *    Si hay sesión y red, empuja a Supabase y devuelve el percentil.
 *  - [observeHistory] lee SIEMPRE de local (fuente de verdad para la UI).
 *  - [syncPending] sincroniza en AMBAS direcciones (sube lo pendiente y descarga
 *    el historial de la nube que falte en local); se llama al iniciar sesión y al
 *    recuperar conectividad.
 */
interface ProgressRepository {

    /**
     * Guarda una partida y devuelve su [SaveOutcome]: el percentil global (si hay
     * sesión + red; null en invitado/offline) y si batió el récord previo del jugador.
     */
    suspend fun saveResult(result: GameResult): SaveOutcome

    /** Historial observable desde local. gameId null = todos los juegos. */
    fun observeHistory(gameId: String? = null): Flow<List<GameProgress>>

    /**
     * Sincroniza el progreso en ambas direcciones: sube a Supabase las filas
     * locales sin sincronizar y descarga a local el historial de la nube que aún
     * no esté presente (deduplicado por id remoto). No-op en modo invitado.
     */
    suspend fun syncPending()

    /** Nº de partidas jugadas HOY (para el Daily Goal). */
    suspend fun countPlayedToday(): Int
}
