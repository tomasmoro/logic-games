package com.kortexgames.app.game

import com.kortexgames.app.domain.model.EventSubmission
import com.kortexgames.app.domain.model.EventSubmitException
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.PercentileResult
import com.kortexgames.app.domain.model.SaveOutcome

/**
 * Información de fin de partida para la pantalla de resultados.
 *
 * @property result el resultado guardado (siempre disponible, local-first).
 * @property percentile percentil vs. todas las PARTIDAS históricas; null en
 *           invitado/offline (el resultado igual quedó guardado y se sincronizará luego).
 * @property ranking comparativa vs. los demás JUGADORES (puesto, % superado y los
 *           vecinos del ranking). Es lo que pinta la tarjeta cuando está disponible;
 *           [percentile] queda como red de seguridad si esta RPC falló.
 * @property isNewRecord true si la partida batió el récord previo del jugador; la UI
 *           lo celebra (badge "¡Nuevo récord!" + fuegos artificiales + sonido arcade).
 * @property isSyncPending true mientras se espera la respuesta de Supabase (usuario
 *           autenticado, subida en vuelo): la tarjeta lo usa para mostrar "comparando
 *           con el mundo…" en vez de confundirlo con el aviso de invitado/sin red.
 *           Ver [ProgressRepository.saveResult][com.kortexgames.app.domain.repository.ProgressRepository.saveResult].
 * @property event resultado en el TORNEO, si la partida era de torneo; null en una
 *           partida normal. Cuando viene, la tarjeta enseña el puesto del torneo en
 *           lugar de la comparativa mundial (ver [EventGameOverInfo]).
 */
data class GameOverInfo(
    val result: GameResult,
    val percentile: PercentileResult?,
    val ranking: GameRanking?,
    val isNewRecord: Boolean = false,
    val isSyncPending: Boolean = false,
    val event: EventGameOverInfo? = null,
)

/**
 * Cómo quedó la partida **en el torneo**, ya en forma de presentación.
 *
 * Se traduce aquí y no en la tarjeta para que la capa de UI no tenga que conocer
 * [EventSubmitException] ni decidir qué significa cada rechazo.
 *
 * @property title título del torneo, para encabezar el panel.
 * @property rank puesto tras el intento; null si el resultado no llegó a contar
 *   (ver [failure]) o si la partida quedó fuera de la tabla.
 * @property improved true si este intento MEJORÓ la marca del jugador en el
 *   torneo. Un intento peor no es un fracaso —el torneo conserva la mejor marca—,
 *   pero tampoco merece celebración.
 * @property failure motivo por el que el resultado NO entró en el torneo, o null si
 *   entró. Es información que el jugador necesita ver: creía estar compitiendo.
 */
data class EventGameOverInfo(
    val title: String,
    val rank: Long?,
    val totalPlayers: Long,
    val improved: Boolean,
    val attemptsLeft: Int?,
    val failure: EventSubmitFailure?,
) {
    companion object {
        /** Traduce el envío al torneo a lo que pinta la tarjeta. */
        fun from(event: GameEvent, outcome: Result<EventSubmission>): EventGameOverInfo {
            val submission = outcome.getOrNull()
            return EventGameOverInfo(
                title = event.title,
                rank = submission?.rank,
                totalPlayers = submission?.totalPlayers ?: 0,
                improved = submission?.improved == true,
                attemptsLeft = submission?.attemptsLeft,
                failure = outcome.exceptionOrNull()?.toFailure(),
            )
        }

        private fun Throwable.toFailure(): EventSubmitFailure = when (this) {
            EventSubmitException.Closed -> EventSubmitFailure.CLOSED
            EventSubmitException.NoAttemptsLeft -> EventSubmitFailure.NO_ATTEMPTS
            EventSubmitException.NotAuthenticated -> EventSubmitFailure.NOT_AUTHENTICATED
            // Una dificultad rechazada es un bug del cliente, no algo que el jugador
            // pueda entender ni arreglar: se le cuenta como fallo genérico.
            else -> EventSubmitFailure.GENERIC
        }
    }
}

/** Por qué un intento no llegó a contar en el torneo. */
enum class EventSubmitFailure { CLOSED, NO_ATTEMPTS, NOT_AUTHENTICATED, GENERIC }

/**
 * Traduce el [SaveOutcome] del repositorio a lo que consume la tarjeta de fin de
 * partida. Existe para que los ~18 ViewModels de juego no repitan el mismo mapeo
 * campo a campo: añadir un dato nuevo a la comparativa se hace aquí y llega a
 * todos los juegos a la vez.
 */
fun SaveOutcome.toGameOverInfo(result: GameResult): GameOverInfo = GameOverInfo(
    result = result,
    percentile = percentile,
    ranking = ranking,
    isNewRecord = isNewRecord,
    isSyncPending = isSyncPending,
)
