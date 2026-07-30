package com.kortexgames.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Partida en curso guardada al salir, para los juegos que activan el guardado
 * (ver [com.kortexgames.app.game.ResumableGameEngine]). A diferencia de
 * [PlayerProgressRepository] es **100% local**, sin sincronizar con Supabase: es
 * estado efímero de sesión (el estado bruto del motor), no un récord que deba viajar
 * entre dispositivos. Punto de extensión futuro si se quisiera reanudar entre
 * dispositivos, no necesario hoy.
 */
interface SavedGameStateRepository {

    /**
     * Estado guardado de [gameId], reactivo (null = no hay partida pendiente). Es
     * lo que observa la **antesala** de cada juego para ofrecer "Continuar": al
     * reanudar o terminar la partida, la fila se borra y el botón desaparece solo.
     */
    fun observe(gameId: String): Flow<String?>

    /** Guarda (o reemplaza) el estado en curso de [gameId] como JSON crudo del motor. */
    suspend fun save(gameId: String, stateJson: String)

    /** Estado guardado de [gameId], o null si no hay ninguno pendiente de reanudar. */
    suspend fun load(gameId: String): String?

    /** Borra el guardado de [gameId] (nivel completado, o ya consumido al reanudar). */
    suspend fun clear(gameId: String)
}
