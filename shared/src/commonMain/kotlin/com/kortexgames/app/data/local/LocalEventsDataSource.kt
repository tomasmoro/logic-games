package com.kortexgames.app.data.local

import com.kortexgames.app.domain.model.GameEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Caché local del calendario de torneos (réplica de solo lectura de
 * `public.events`; ver `Event.sq`). Local-first: es lo que pinta la Home, con o
 * sin red. A diferencia de [LocalPlayerProgressDataSource] no tiene camino de
 * subida — un torneo lo crea el backend, nunca el dispositivo.
 */
interface LocalEventsDataSource {

    /**
     * Torneos que terminan después de [cutoff], en orden de comienzo. Es un [Flow]
     * porque la Home se suscribe: al refrescar el calendario la tarjeta aparece
     * sola, sin que nadie tenga que recargar la pantalla.
     *
     * [cutoff] no es "ahora": quien llama resta la ventana de resultados, para que
     * un torneo recién cerrado siga en la lista con su clasificación final. Los que
     * aún no han empezado también entran —se anuncian con cuenta atrás, que es la
     * mitad del gancho—, así que esta lista cubre las tres fases.
     */
    fun observeVisible(cutoff: Instant): Flow<List<GameEvent>>

    /** Un torneo concreto, observado (la pantalla de detalle vive de esto). */
    fun observe(id: String): Flow<GameEvent?>

    /** Vuelca el calendario recién descargado, reemplazando las filas por id. */
    suspend fun upsertAll(events: List<GameEvent>)

    /** Poda los torneos terminados antes de [cutoff]. */
    suspend fun deleteEndedBefore(cutoff: Instant)

    /** Vacía la caché (cierre de sesión / borrado de cuenta). */
    suspend fun clearAll()
}
