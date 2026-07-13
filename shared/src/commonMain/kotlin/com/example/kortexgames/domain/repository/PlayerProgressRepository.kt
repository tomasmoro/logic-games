package com.example.kortexgames.domain.repository

import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PlayerGameProgress
import kotlinx.coroutines.flow.Flow

/**
 * Estado de progresión por juego (récord + reanudación), con estrategia
 * **local-first** igual que [ProgressRepository]:
 *
 *  - [recordResult] actualiza la mejor marca (y el nivel de reanudación) en local
 *    a partir del valor ALCANZADO de la partida; si hay sesión, sube la fila.
 *  - [observe]/[observeAll] leen SIEMPRE de local (fuente de verdad de la UI).
 *  - [sync] fusiona en ambas direcciones al iniciar sesión o recuperar red.
 */
interface PlayerProgressRepository {

    /** Progresión de un juego (o null si aún no hay marca), reactivo. */
    fun observe(gameId: String): Flow<PlayerGameProgress?>

    /** Progresión de todos los juegos, reactivo (para el catálogo). */
    fun observeAll(): Flow<List<PlayerGameProgress>>

    /**
     * Registra el resultado de una partida en la progresión: si [GameResult.reachedMetric]
     * supera el récord (según la dirección del juego) lo actualiza, y guarda el
     * nivel de reanudación en juegos LEVELED. No-op si el juego no tiene progresión
     * registrada o la partida no arrojó métrica medible.
     *
     * @return true si esta partida **batió un récord previo existente** (para celebrarlo
     *   en la UI); false en la primera marca del juego o si no mejoró el récord.
     */
    suspend fun recordResult(result: GameResult): Boolean

    /**
     * Sincroniza la progresión en ambas direcciones: fusiona la nube con lo local
     * (mejor marca gana; el `lastLevel` más reciente gana) y sube lo pendiente.
     * No-op en modo invitado.
     */
    suspend fun sync()
}
