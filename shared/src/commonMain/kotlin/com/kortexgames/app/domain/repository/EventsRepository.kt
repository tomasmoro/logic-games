package com.kortexgames.app.domain.repository

import com.kortexgames.app.domain.model.EventAward
import com.kortexgames.app.domain.model.EventLeaderboard
import com.kortexgames.app.domain.model.EventSubmission
import com.kortexgames.app.domain.model.GameEvent
import kotlinx.coroutines.flow.Flow

/**
 * Torneos: calendario, tabla y envío de intentos.
 *
 * Local-first como el resto (CLAUDE.md §4): lo que la UI observa es SIEMPRE la
 * caché local, y la red solo la rellena. La diferencia con
 * [ProgressRepository] es que aquí no hay camino de subida diferida — un intento
 * de torneo o se registra **dentro de la ventana** o no cuenta, así que no tiene
 * sentido encolarlo para cuando vuelva la red.
 */
interface EventsRepository {

    /**
     * Torneos vigentes (en curso o por empezar), en orden de comienzo. Es la fuente
     * de la tarjeta de Home; emite sola cuando [refresh] trae novedades.
     */
    fun observeVisible(): Flow<List<GameEvent>>

    /** Un torneo concreto, observado. `null` si no está en la caché. */
    fun observe(eventId: String): Flow<GameEvent?>

    /**
     * Sincroniza el calendario con Supabase y poda lo ya caducado. Silencioso ante
     * fallos de red: la Home sigue con el último calendario conocido.
     */
    suspend fun refresh()

    /**
     * Tabla del torneo (top + la fila del jugador).
     *
     * @return null si no hay sesión (un invitado ve el torneo pero no la tabla; ver
     *   la migración 0055) o si la consulta falla.
     */
    suspend fun leaderboard(event: GameEvent): EventLeaderboard?

    /**
     * Registra un intento. El servidor valida ventana, dificultad y tope de
     * intentos, así que este camino puede fallar por reglas de negocio y no solo
     * por red: el error llega tipado en [com.kortexgames.app.domain.model.EventSubmitException].
     */
    suspend fun submitResult(
        event: GameEvent,
        score: Int,
        completionTimeMs: Long,
        accuracyPercentage: Double,
        difficultyLevel: Int?,
    ): Result<EventSubmission>

    /**
     * Registra que el jugador **abandonó** una partida del torneo: gasta un intento
     * sin dejar marca (migración 0056).
     *
     * Dispara y olvida, como [com.kortexgames.app.core.notifications.NotificationsManager.onRecordBeaten]:
     * se llama justo antes de navegar fuera de la pantalla de juego, así que no
     * puede depender del ciclo de vida del ViewModel que la invoca ni retrasar la
     * salida esperando a la red.
     *
     * Si la llamada falla (sin cobertura), el intento **no** se consume: el contador
     * vive en el servidor, que es lo único que puede arbitrar un torneo, y no hay
     * forma honesta de "encolar" un abandono sin abrir la puerta a que el tope se
     * aplique tarde y en el momento más confuso para el jugador.
     */
    fun abandonAttempt(event: GameEvent)

    /**
     * Desbloquea un intento extra: es la contrapartida de un anuncio recompensado
     * ya visto (el cliente lo llama solo tras un `RewardResult.EARNED`).
     *
     * El tope lo decide el servidor (`events.ad_attempts_limit`): esta llamada
     * puede fallar legítimamente si el jugador ya agotó los extras, y por eso
     * devuelve `Result` en vez de dispararse y olvidarse — hay que poder decirle
     * que el anuncio no le ha dado nada.
     */
    suspend fun grantExtraAttempt(event: GameEvent): Result<Unit>

    /**
     * Vitrina de insignias: torneos cerrados en los que el jugador quedó premiado,
     * de la caché local. Es lo que pinta el perfil, así que funciona sin red.
     */
    fun observeAwards(): Flow<List<EventAward>>

    /**
     * Sincroniza la vitrina con el backend. Silencioso ante fallos: el perfil se
     * queda con las insignias que ya tenía.
     */
    suspend fun refreshAwards()
}
