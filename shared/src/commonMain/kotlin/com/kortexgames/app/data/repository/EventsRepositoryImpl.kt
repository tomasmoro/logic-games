package com.kortexgames.app.data.repository

import com.kortexgames.app.data.local.LocalEventAwardsDataSource
import com.kortexgames.app.data.local.LocalEventsDataSource
import com.kortexgames.app.data.remote.RemoteEventsDataSource
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.EventAward
import com.kortexgames.app.domain.model.EventLeaderboard
import com.kortexgames.app.domain.model.EventSubmission
import com.kortexgames.app.domain.model.EventSubmitException
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.repository.EventsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * # Torneos — implementación local-first
 *
 * La **caché local** ([LocalEventsDataSource], SQLDelight) es lo que pinta la Home:
 * la tarjeta aparece con el último calendario conocido incluso sin red, y se
 * corrige sola cuando [refresh] trae novedades. Mismo patrón que
 * [SudokuPuzzleRepositoryImpl]: réplica de solo lectura de un catálogo remoto.
 *
 * Lo que NO hace, a diferencia de [ProgressRepositoryImpl]: encolar envíos
 * pendientes. Un intento de torneo solo vale dentro de su ventana —y quien decide
 * si la ventana está abierta es el reloj del servidor—, así que guardarlo para
 * subirlo "cuando vuelva la red" produciría intentos que el backend rechazaría
 * igualmente, con la frustración añadida de haberlos dado por buenos.
 *
 * @param authState lectura de la sesión actual. Un invitado ve el calendario
 *   (migración 0055) pero no la tabla ni puede puntuar; cortar aquí evita un
 *   round-trip que el backend ya sabe que va a denegar.
 * @param localAwards vitrina local de insignias (solo lectura, ver
 *   [LocalEventAwardsDataSource]).
 * @param scope scope de aplicación para el abandono "dispara y olvida": ocurre al
 *   salir de la partida, cuando el ViewModel que lo pide está a punto de morir.
 * @param clock reloj inyectable (pruebas deterministas de las tres fases).
 */
class EventsRepositoryImpl(
    private val local: LocalEventsDataSource,
    private val localAwards: LocalEventAwardsDataSource,
    private val remote: RemoteEventsDataSource,
    private val authState: () -> AuthState,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : EventsRepository {

    override fun observeVisible(): Flow<List<GameEvent>> =
        // El corte se hace en el pasado, no en "ahora": un torneo que acaba de
        // cerrar sigue en la lista durante [RESULTS_WINDOW] para que el jugador
        // pueda ver cómo quedó. Antes se cortaba en el instante de suscribirse, así
        // que un torneo terminado solo sobrevivía si cerraba con la app abierta y
        // desaparecía al siguiente arranque — justo cuando el jugador iba a mirarlo.
        local.observeVisible(clock.now() - RESULTS_WINDOW)

    override fun observe(eventId: String): Flow<GameEvent?> = local.observe(eventId)

    override suspend fun refresh() {
        // Un fallo de red aquí no es excepcional, es el caso normal en el metro: se
        // traga a propósito y la UI se queda con lo cacheado.
        runCatching {
            // Se pide con la MISMA ventana con la que se consulta en local: si aquí se
            // cortara en "ahora", un torneo recién cerrado no se descargaría nunca y
            // su resultado solo existiría en los dispositivos que lo tuvieran ya
            // cacheado — es decir, desaparecería al reinstalar justo cuando el
            // jugador va a mirar si ganó.
            val calendar = remote.fetchCalendar(from = clock.now() - RESULTS_WINDOW)
            local.upsertAll(calendar)
            // La poda usa la MISMA ventana que la consulta: borrar antes dejaría
            // huecos (un torneo visible que desaparece a media sesión) y borrar
            // después acumularía filas que ya nadie mira.
            local.deleteEndedBefore(clock.now() - RESULTS_WINDOW)
        }
    }

    override suspend fun leaderboard(event: GameEvent): EventLeaderboard? {
        if (authState() !is AuthState.Authenticated) return null
        return runCatching {
            remote.fetchLeaderboard(eventId = event.id, rankByTime = event.rankByTime)
        }.getOrNull()
    }

    override suspend fun submitResult(
        event: GameEvent,
        score: Int,
        completionTimeMs: Long,
        accuracyPercentage: Double,
        difficultyLevel: Int?,
    ): Result<EventSubmission> {
        if (authState() !is AuthState.Authenticated) {
            return Result.failure(EventSubmitException.NotAuthenticated)
        }
        // La ventana la valida el SERVIDOR; este corte local es solo para no gastar
        // una llamada en un caso evidente (el jugador terminó la partida después del
        // cierre). Si los dos relojes discrepan, manda el del servidor.
        if (!event.isLiveAt(clock.now())) {
            return Result.failure(EventSubmitException.Closed)
        }
        return remote.submit(
            eventId = event.id,
            score = score,
            // El backend guarda milisegundos en un `integer`: un valor absurdo por un
            // reloj que saltó no debe tumbar el envío entero con un desbordamiento.
            completionTimeMs = completionTimeMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            accuracyPercentage = accuracyPercentage,
            difficultyLevel = difficultyLevel ?: event.difficultyLevel,
        )
    }

    override fun abandonAttempt(event: GameEvent) {
        if (authState() !is AuthState.Authenticated) return
        if (!event.isLiveAt(clock.now())) return
        scope.launch {
            // El resultado no se mira: no hay nada que la app pueda hacer si falla, y
            // el jugador ya está saliendo de la pantalla. El servidor es el único que
            // lleva la cuenta de intentos.
            remote.submit(
                eventId = event.id,
                score = 0,
                completionTimeMs = 0,
                accuracyPercentage = 0.0,
                difficultyLevel = event.difficultyLevel,
                abandoned = true,
            )
        }
    }

    override fun observeAwards(): Flow<List<EventAward>> = localAwards.observeAll()

    override suspend fun refreshAwards() {
        if (authState() !is AuthState.Authenticated) return
        // null = la consulta falló. Se distingue de la lista vacía a propósito: vacía
        // significa "no has ganado nada" y SÍ debe reemplazar la vitrina (un premio
        // puede dejar de existir); null es un corte de red y no debe borrar nada.
        val awards = remote.fetchMyAwards() ?: return
        localAwards.replaceAll(awards)
    }

    override suspend fun grantExtraAttempt(event: GameEvent): Result<Unit> {
        if (authState() !is AuthState.Authenticated) {
            return Result.failure(EventSubmitException.NotAuthenticated)
        }
        return remote.grantAttempt(event.id)
    }

    private companion object {
        /**
         * Cuánto sigue a la vista un torneo ya terminado, con su clasificación final.
         *
         * Un día: suficiente para que quien lo jugó por la noche vea el resultado a la
         * mañana siguiente, y lo bastante corto para que la Home no se convierta en un
         * archivo de torneos viejos. Es a la vez el filtro de la consulta y el criterio
         * de poda de la caché, a propósito: dos ventanas distintas producirían torneos
         * que desaparecen antes de tiempo o filas que no borra nadie.
         */
        val RESULTS_WINDOW = 24.hours
    }
}
