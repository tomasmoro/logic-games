package com.kortexgames.app.game.events

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.EventPhase
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.repository.EventsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * # La insignia de un torneo ganado
 *
 * Detecta que un torneo **ya cerrado** dejó al jugador entre los premiados y lo
 * anuncia UNA vez, con su celebración.
 *
 * ## Por qué hace falta un vigilante y no basta con la pantalla del torneo
 * Un torneo termina a una hora en la que el jugador casi nunca está mirando la app
 * —de madrugada, a media mañana— y el premio no se entrega en ninguna partida:
 * aparece cuando la clasificación se congela. Si el aviso viviera solo en la
 * pantalla del torneo, ganar pasaría inadvertido salvo que al jugador le diera por
 * volver a entrar. Esto lo mira al arrancar la app y en cada refresco del
 * calendario, que es cuando el resultado puede haber cambiado.
 *
 * ## Por qué se anuncia una sola vez
 * Una celebración que se repite en cada arranque deja de ser una celebración. Los
 * torneos ya resueltos se anotan en [EventRewardStore]; una vez anotado, ese torneo
 * no vuelve a mirarse ni a consultarse a la red.
 *
 * ## Qué se anota y qué no
 * Se anota **todo torneo resuelto**, se haya ganado o no. Si solo se anotaran los
 * premiados, cada arranque volvería a pedir la clasificación de los torneos
 * perdidos —una llamada por torneo y por arranque, para siempre— solo para volver a
 * decidir que no hay nada que enseñar.
 *
 * @param authState sesión actual: sin cuenta no hay clasificación que consultar (la
 *   RPC solo está concedida a `authenticated`), así que el invitado no entra aquí.
 * @param clock reloj inyectable: decide qué torneos cuentan como terminados.
 */
class EventRewardManager(
    private val events: EventsRepository,
    private val store: EventRewardStore,
    private val authState: () -> AuthState,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {

    private val _pending = MutableStateFlow<EventReward?>(null)

    /**
     * Insignia pendiente de celebrar, o null.
     *
     * Es un `StateFlow` y no un evento one-shot porque la celebración debe seguir en
     * pie hasta que el jugador la cierre: si cierra la app con el cartel abierto,
     * al volver sigue ahí. Solo [dismiss] lo apaga, y solo entonces se anota.
     */
    val pending: StateFlow<EventReward?> = _pending.asStateFlow()

    /** Arranca la vigilancia. Se llama una vez desde el `AppGraph`. */
    fun start() {
        events.observeVisible()
            .onEach { visible -> resolve(visible) }
            .launchIn(scope)
    }

    /**
     * El jugador cerró la celebración: se anota para no repetirla y se busca si hay
     * otra detrás (dos torneos pueden cerrar la misma noche).
     */
    fun dismiss() {
        val reward = _pending.value ?: return
        _pending.value = null
        scope.launch {
            store.markResolved(reward.eventId)
            resolve(events.observeVisible().first())
        }
    }

    /**
     * Mira los torneos terminados que aún no se han resuelto y se queda con el
     * primero premiado.
     *
     * El orden es por cierre más reciente: si hay varios pendientes, el que el
     * jugador tiene más fresco es el que quiere ver primero.
     */
    private suspend fun resolve(visible: List<GameEvent>) {
        if (_pending.value != null) return
        if (authState() !is AuthState.Authenticated) return

        val now = clock.now()
        val resolved = store.resolvedIds.first()
        val candidates = visible
            .filter { it.phaseAt(now) == EventPhase.FINISHED && it.id !in resolved }
            .sortedByDescending { it.endsAt }

        for (event in candidates) {
            // Sin premio configurado no hay nada que celebrar, y tampoco hace falta
            // preguntar a la red por la clasificación: se anota y se pasa.
            if (event.rewardTopN <= 0) {
                store.markResolved(event.id)
                continue
            }
            val board = events.leaderboard(event)
            if (board == null) {
                // Sin red: NO se anota. El torneo sigue pendiente y se reintenta en el
                // próximo arranque; anotarlo aquí perdería la insignia para siempre por
                // un fallo pasajero.
                continue
            }
            val rank = board.me?.rank
            if (rank != null && rank <= event.rewardTopN) {
                _pending.value = EventReward(
                    eventId = event.id,
                    eventTitle = event.title,
                    gameId = event.gameId,
                    rank = rank,
                    totalPlayers = board.totalPlayers,
                    badgeKey = event.rewardBadgeKey,
                )
                return
            }
            store.markResolved(event.id)
        }
    }
}

/**
 * Lo que se celebra: el puesto premiado de un torneo ya cerrado.
 *
 * @property rank puesto final (1 = campeón). Siempre dentro del premio.
 * @property badgeKey clave de la insignia definida por el evento, o null para la
 *   genérica. Hoy no elige arte —no hay catálogo de insignias todavía— pero viaja
 *   porque es el dato del backend que lo decidirá.
 */
data class EventReward(
    val eventId: String,
    val eventTitle: String,
    val gameId: String,
    val rank: Long,
    val totalPlayers: Long,
    val badgeKey: String?,
)

/**
 * Qué torneos ya se resolvieron (celebrados o no). Es la memoria que impide que la
 * celebración se repita en cada arranque.
 *
 * Se guarda un conjunto de ids y no una fecha: son pocos —solo los torneos de la
 * ventana de resultados sobreviven en la caché— y comparar por id es exacto,
 * mientras que "todo lo anterior a tal fecha" fallaría con dos torneos que cierran
 * casi a la vez.
 */
class EventRewardStore(private val dataStore: DataStore<Preferences>) {

    private val resolvedKey = stringSetPreferencesKey("event_rewards_resolved")

    /** Ids de torneos ya resueltos. */
    val resolvedIds: Flow<Set<String>> = dataStore.data.map { it[resolvedKey] ?: emptySet() }

    /** Anota [eventId] como resuelto. */
    suspend fun markResolved(eventId: String) {
        dataStore.edit { prefs ->
            prefs[resolvedKey] = (prefs[resolvedKey] ?: emptySet()) + eventId
        }
    }

    /** Olvida todo (borrado de cuenta). */
    suspend fun clear() {
        dataStore.edit { it.remove(resolvedKey) }
    }
}
