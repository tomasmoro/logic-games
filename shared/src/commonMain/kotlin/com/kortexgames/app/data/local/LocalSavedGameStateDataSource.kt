package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.SavedGameState
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia local de la partida en curso guardada al salir (ver
 * [com.kortexgames.app.domain.repository.SavedGameStateRepository]). Sin
 * campos de sync: a diferencia de [LocalPlayerProgressDataSource]/[LocalLevelTimeDataSource]
 * no viaja a Supabase.
 */
interface LocalSavedGameStateDataSource {

    /**
     * Guardado de un juego, reactivo. Lo consume la **antesala** para ofrecer
     * "Continuar" solo si hay partida pendiente: al reanudarla (o al terminar el
     * nivel) la fila se borra y el flujo emite null, de modo que el botón
     * desaparece solo sin que la pantalla tenga que refrescar nada a mano.
     */
    fun observe(gameId: String): Flow<SavedGameState?>

    /** Lectura puntual (no reactiva), para el camino de reanudar. */
    suspend fun get(gameId: String): SavedGameState?

    /** Inserta o reemplaza el guardado de un juego (upsert por gameId). */
    suspend fun upsert(saved: SavedGameState)

    /** Borra el guardado de un juego. */
    suspend fun delete(gameId: String)

    /** Borra todas las partidas en curso guardadas (borrado de cuenta). */
    suspend fun clearAll()
}
