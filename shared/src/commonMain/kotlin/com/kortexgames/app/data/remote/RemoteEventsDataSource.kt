package com.kortexgames.app.data.remote

import com.kortexgames.app.domain.model.EventAward
import com.kortexgames.app.domain.model.EventLeaderboard
import com.kortexgames.app.domain.model.EventScoringMode
import com.kortexgames.app.domain.model.EventStanding
import com.kortexgames.app.domain.model.EventSubmission
import com.kortexgames.app.domain.model.EventSubmitException
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.model.LeaderboardEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Acceso remoto a los torneos (migraciones 0051–0055). Tres caminos, cada uno con
 * su razón de ser:
 *
 *  - **Calendario** — lectura directa por PostgREST sobre `events`. Es un catálogo
 *    con RLS de lectura, así que no hace falta RPC: la regla ("solo publicados")
 *    la aplica la propia base. Desde 0055 lo lee también el invitado.
 *  - **Tabla del torneo** — RPC `get_event_leaderboard`, porque agrega marcas
 *    AJENAS, que es justo lo que RLS no deja leer en crudo. Solo autenticados.
 *  - **Enviar intento** — RPC `submit_event_result`, que valida ventana,
 *    dificultad y tope de intentos con el reloj y los datos del servidor.
 */
class RemoteEventsDataSource(
    private val client: SupabaseClient,
) {

    /** Fila de `public.events` tal cual la devuelve PostgREST. */
    @Serializable
    private data class EventRow(
        val id: String,
        @SerialName("game_id") val gameId: String,
        val title: String,
        val subtitle: String? = null,
        @SerialName("starts_at") val startsAt: Instant,
        @SerialName("ends_at") val endsAt: Instant,
        @SerialName("difficulty_level") val difficultyLevel: Int? = null,
        @SerialName("rank_by_time") val rankByTime: Boolean = false,
        @SerialName("scoring_mode") val scoringMode: String = "best_run",
        @SerialName("attempts_limit") val attemptsLimit: Int? = null,
        @SerialName("ad_attempts_limit") val adAttemptsLimit: Int = 0,
        // jsonb libre: el reto fijo en la forma de cada juego. Se decodifica como
        // JsonObject (y no como un data class por juego) porque su forma depende
        // del juego; el modelo de dominio se queda con las dos claves que hoy
        // existen y el resto se ignora sin romper nada.
        val payload: JsonObject = JsonObject(emptyMap()),
        @SerialName("reward_top_n") val rewardTopN: Int = 0,
        @SerialName("reward_badge_key") val rewardBadgeKey: String? = null,
    )

    /** Objeto jsonb de `get_event_leaderboard`. */
    @Serializable
    private data class LeaderboardRow(
        @SerialName("total_players") val totalPlayers: Long = 0,
        val top: List<EntryRow> = emptyList(),
        val me: MeRow? = null,
        // Intentos gastados por el jugador, tenga marca o no: quien solo ha
        // abandonado no sale en la tabla y aun así necesita saber cuántos le quedan.
        @SerialName("my_attempts") val myAttempts: Int = 0,
        @SerialName("my_extra_attempts") val myExtraAttempts: Int = 0,
    )

    @Serializable
    private data class EntryRow(
        val rank: Long,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("best_score") val bestScore: Int = 0,
        @SerialName("best_time_ms") val bestTimeMs: Int? = null,
        @SerialName("is_current_user") val isCurrentUser: Boolean = false,
    )

    @Serializable
    private data class MeRow(
        val rank: Long,
        @SerialName("best_score") val bestScore: Int = 0,
        @SerialName("best_time_ms") val bestTimeMs: Int? = null,
        val attempts: Int = 0,
    )

    @Serializable
    private data class LeaderboardParams(
        @SerialName("p_event_id") val eventId: String,
        @SerialName("p_limit") val limit: Int,
    )

    @Serializable
    private data class SubmitParams(
        @SerialName("p_event_id") val eventId: String,
        @SerialName("p_score") val score: Int,
        @SerialName("p_completion_time_ms") val completionTimeMs: Int,
        @SerialName("p_accuracy_percentage") val accuracy: Double,
        @SerialName("p_difficulty_level") val difficultyLevel: Int? = null,
        @SerialName("p_abandoned") val abandoned: Boolean = false,
    )

    @Serializable
    private data class SubmitRow(
        val rank: Long? = null,
        @SerialName("total_players") val totalPlayers: Long = 0,
        val improved: Boolean = false,
        @SerialName("attempts_used") val attemptsUsed: Int = 0,
        @SerialName("attempts_limit") val attemptsLimit: Int? = null,
    )

    /**
     * Calendario: torneos publicados que aún no han terminado en [from].
     *
     * El filtro de publicación NO viaja en la consulta: lo aplica la RLS
     * (`events_read_published`). Repetirlo aquí daría la falsa impresión de que la
     * seguridad depende del cliente.
     *
     * @param from instante de corte; normalmente "ahora".
     * @param limit tope defensivo — el calendario es diminuto, pero una consulta sin
     *   límite es una consulta que algún día trae 10.000 filas.
     */
    suspend fun fetchCalendar(from: Instant, limit: Int = CALENDAR_LIMIT): List<GameEvent> =
        client.postgrest.from("events")
            .select {
                filter { gte("ends_at", from.toString()) }
                // Sin `order`: quien ordena es la consulta local (`Event.sq`), que es
                // la que pinta la UI. Pedirlo también al servidor sería una promesa
                // duplicada que algún día se contradice.
                limit(limit.toLong())
            }
            .decodeList<EventRow>()
            .map { it.toDomain() }

    /**
     * Tabla del torneo: top [limit] + la fila del jugador.
     *
     * @param rankByTime criterio del torneo; decide qué métrica se copia al `score`
     *   de [LeaderboardEntry], que por contrato es "el valor por el que se ordena".
     * @return null si el evento no existe / no está publicado, o si no hay sesión
     *   (la RPC solo está concedida a `authenticated`).
     */
    suspend fun fetchLeaderboard(
        eventId: String,
        rankByTime: Boolean,
        limit: Int = LEADERBOARD_LIMIT,
    ): EventLeaderboard? {
        val row = client.postgrest.rpc(
            function = "get_event_leaderboard",
            parameters = LeaderboardParams(eventId = eventId, limit = limit),
        ).decodeAsOrNull<LeaderboardRow>() ?: return null

        return EventLeaderboard(
            totalPlayers = row.totalPlayers,
            myAttempts = row.myAttempts,
            myExtraAttempts = row.myExtraAttempts,
            top = row.top.map { e ->
                LeaderboardEntry(
                    rank = e.rank,
                    displayName = e.displayName,
                    // En un torneo por tiempo la tabla se ordena por milisegundos; el
                    // 0 de respaldo no llega a pintarse porque el backend excluye de la
                    // tabla a quien no tiene partida cronometrada.
                    score = if (rankByTime) e.bestTimeMs ?: 0 else e.bestScore,
                    isCurrentUser = e.isCurrentUser,
                )
            },
            me = row.me?.let {
                EventStanding(
                    rank = it.rank,
                    bestScore = it.bestScore,
                    bestTimeMs = it.bestTimeMs,
                    attempts = it.attempts,
                )
            },
        )
    }

    /**
     * Envía un intento del torneo.
     *
     * Los rechazos del servidor llegan con SQLSTATE propios y se traducen a
     * [EventSubmitException] para que la UI pueda reaccionar por tipo. Se buscan
     * **por código** dentro del mensaje de error y no por su texto: el código es
     * estable y el texto es una frase en español que mañana puede cambiar o
     * traducirse. La librería no expone el SQLSTATE en un campo propio, así que el
     * cuerpo del error es el único sitio donde mirar.
     *
     * @param abandoned true si la partida se ABANDONÓ: gasta intento y no registra
     *   marca (ver la migración 0056). Un abandono enviado como resultado normal
     *   pondría el cronómetro de una partida de diez segundos en lo alto de un
     *   torneo por tiempo.
     * @return [Result] con el puesto, o el rechazo tipado. Nunca lanza.
     */
    suspend fun submit(
        eventId: String,
        score: Int,
        completionTimeMs: Int,
        accuracyPercentage: Double,
        difficultyLevel: Int?,
        abandoned: Boolean = false,
    ): Result<EventSubmission> = runCatching {
        val row = client.postgrest.rpc(
            function = "submit_event_result",
            parameters = SubmitParams(
                eventId = eventId,
                score = score,
                completionTimeMs = completionTimeMs,
                accuracy = accuracyPercentage,
                difficultyLevel = difficultyLevel,
                abandoned = abandoned,
            ),
            // `decodeAsOrNull` y no `decodeAs`: es el decodificador que ya usa el
            // resto del proyecto, y un null aquí (la RPC devolvió jsonb vacío) es un
            // fallo real que debe viajar como rechazo, no como éxito en blanco.
        ).decodeAsOrNull<SubmitRow>() ?: error("Respuesta vacía de submit_event_result")

        EventSubmission(
            rank = row.rank,
            totalPlayers = row.totalPlayers,
            improved = row.improved,
            attemptsUsed = row.attemptsUsed,
            attemptsLimit = row.attemptsLimit,
        )
    }.recoverCatching { t -> throw t.toSubmitException() }

    /** Fila de `get_my_event_awards` (migración 0059). */
    @Serializable
    private data class AwardRow(
        @SerialName("event_id") val eventId: String,
        @SerialName("event_title") val eventTitle: String,
        @SerialName("game_id") val gameId: String,
        @SerialName("final_position") val position: Long,
        @SerialName("total_players") val totalPlayers: Long,
        @SerialName("badge_key") val badgeKey: String? = null,
        @SerialName("ended_at") val endedAt: Instant,
    )

    /**
     * Insignias del jugador: torneos cerrados en los que quedó premiado.
     *
     * Es una RPC y no una lectura directa porque calcular el puesto exige leer las
     * marcas ajenas del torneo, que es justo lo que RLS no deja. Devuelve lista
     * vacía ante cualquier fallo: la vitrina se queda con lo cacheado en vez de
     * vaciarse por un corte de red (ver `EventsRepositoryImpl.refreshAwards`).
     */
    suspend fun fetchMyAwards(): List<EventAward>? = runCatching {
        client.postgrest.rpc(function = "get_my_event_awards")
            .decodeList<AwardRow>()
            .map { r ->
                EventAward(
                    eventId = r.eventId,
                    eventTitle = r.eventTitle,
                    gameId = r.gameId,
                    position = r.position,
                    totalPlayers = r.totalPlayers,
                    badgeKey = r.badgeKey,
                    endedAt = r.endedAt,
                )
            }
    }.getOrNull()

    @Serializable
    private data class GrantParams(@SerialName("p_event_id") val eventId: String)

    /**
     * Concede un intento extra tras un anuncio recompensado (RPC de la migración
     * 0057). El tope lo impone el servidor (`ad_attempts_limit`), no el cliente:
     * aquí solo se pide.
     *
     * Que la recompensa se conceda al volver del anuncio y sin verificación de
     * servidor es la misma confianza que ya asumen la pista y el revivir del
     * Sudoku; AdMob no ofrece una prueba que este backend pueda validar sin montar
     * callbacks de servidor a servidor.
     */
    suspend fun grantAttempt(eventId: String): Result<Unit> = runCatching {
        client.postgrest.rpc(
            function = "grant_event_attempt",
            parameters = GrantParams(eventId),
        )
        Unit
    }

    /** Traduce el SQLSTATE del backend (0053) al rechazo tipado del dominio. */
    private fun Throwable.toSubmitException(): EventSubmitException {
        val body = message.orEmpty()
        return when {
            ERROR_CLOSED in body -> EventSubmitException.Closed
            ERROR_NO_ATTEMPTS in body -> EventSubmitException.NoAttemptsLeft
            ERROR_DIFFICULTY in body -> EventSubmitException.WrongDifficulty
            ERROR_NOT_AUTHENTICATED in body -> EventSubmitException.NotAuthenticated
            else -> EventSubmitException.Unknown(this)
        }
    }

    private fun EventRow.toDomain() = GameEvent(
        id = id,
        gameId = gameId,
        title = title,
        subtitle = subtitle,
        startsAt = startsAt,
        endsAt = endsAt,
        difficultyLevel = difficultyLevel,
        rankByTime = rankByTime,
        scoringMode = EventScoringMode.fromRaw(scoringMode),
        attemptsLimit = attemptsLimit,
        adAttemptsLimit = adAttemptsLimit,
        puzzleId = payload["puzzle_id"]?.jsonPrimitive?.content,
        seed = payload["seed"]?.jsonPrimitive?.longOrNull,
        rewardTopN = rewardTopN,
        rewardBadgeKey = rewardBadgeKey,
    )

    private companion object {
        /** Tope defensivo del calendario: hoy nunca habrá más de un puñado vivos. */
        const val CALENDAR_LIMIT = 20

        /** Cuántos puestos trae la tabla. Diez entra en pantalla sin scroll infinito. */
        const val LEADERBOARD_LIMIT = 10

        // SQLSTATE propios de la migración 0053 (ver su cabecera).
        const val ERROR_CLOSED = "KXE01"
        const val ERROR_NO_ATTEMPTS = "KXE02"
        const val ERROR_DIFFICULTY = "KXE03"

        /** SQLSTATE estándar de "invalid authorization specification". */
        const val ERROR_NOT_AUTHENTICATED = "28000"
    }
}
