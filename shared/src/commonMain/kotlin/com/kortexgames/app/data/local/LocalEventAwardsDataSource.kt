package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.EventAward
import kotlinx.coroutines.flow.Flow

/**
 * Vitrina local de insignias de torneo (réplica de solo lectura; ver
 * `EventAward.sq`). Local-first: es lo que pinta el perfil, con o sin red. Como el
 * calendario de torneos y a diferencia de [LocalPlayerProgressDataSource], no tiene
 * camino de subida — un premio lo otorga el servidor, nunca el dispositivo.
 */
interface LocalEventAwardsDataSource {

    /** Insignias en orden cronológico inverso (lo más reciente primero). */
    fun observeAll(): Flow<List<EventAward>>

    /**
     * Sustituye la vitrina entera por [awards].
     *
     * Reemplaza en vez de fusionar porque la respuesta del servidor es la lista
     * completa: si un premio deja de existir (torneo despublicado, `reward_top_n`
     * corregido), fusionar lo dejaría en el perfil para siempre.
     */
    suspend fun replaceAll(awards: List<EventAward>)

    /** Vacía la vitrina (cierre de sesión / borrado de cuenta). */
    suspend fun clearAll()
}
