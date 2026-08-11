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
     * Guarda una partida (local-first) y emite su evolución como [SaveOutcome].
     *
     * Emite en **1 o 2 pasos** a propósito: la subida a Supabase (RPC de guardado +
     * RPC de ranking) puede tardar un par de vueltas de red, y si el ViewModel
     * esperara la única respuesta antes de mostrar el cartel de fin de partida, el
     * jugador vería el tablero "congelado" varios segundos tras resolver el nivel.
     *
     *  1. **Local inmediata**: récord ya calculado (SQLDelight, sin red), con
     *     `percentile`/`ranking` en `null`. El ViewModel puede pintar el cartel de
     *     resultado con esta emisión SIN esperar más.
     *  2. **(Solo con sesión) Remota**: llega en cuanto Supabase responde, con el
     *     percentil/ranking reales (o `null` si la subida falla; la partida ya está
     *     a salvo en local y se reintentará en [syncPending]).
     *
     * En invitado/offline el flow completa tras la única emisión local.
     */
    fun saveResult(result: GameResult): Flow<SaveOutcome>

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

    /**
     * Vacía el historial local. Es puramente local (no toca la nube): lo usa el
     * borrado de cuenta, después de que el backend ya confirmó su propio borrado,
     * para que el dispositivo tampoco siga mostrando datos de una cuenta borrada.
     */
    suspend fun clearLocal()
}
