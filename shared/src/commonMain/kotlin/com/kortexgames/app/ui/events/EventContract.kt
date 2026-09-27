package com.kortexgames.app.ui.events

import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.EventLeaderboard
import com.kortexgames.app.domain.model.GameEvent

/**
 * Contrato MVI de la pantalla de torneo (CLAUDE.md §4).
 *
 * @property event torneo mostrado; null mientras la caché local no ha emitido.
 * @property leaderboard tabla con top y fila propia; null si es invitado, si aún
 *   no ha cargado o si la consulta falló — los tres casos se distinguen mirando
 *   [isGuest] y [isLoadingLeaderboard], no metiendo estados centinela en el modelo.
 * @property isGuest sin sesión: ve el torneo, no la tabla ni el botón de jugar
 *   (migración 0055).
 * @property isWatchingAd hay un anuncio recompensado en vuelo para desbloquear un
 *   intento extra. Bloquea el CTA para que dos toques no pidan dos anuncios.
 * @property error último fallo a mostrar, o null. Es estado —no efecto— porque el
 *   mensaje se queda en pantalla hasta que se reintenta.
 */
data class EventUiState(
    val event: GameEvent? = null,
    val leaderboard: EventLeaderboard? = null,
    val isLoadingLeaderboard: Boolean = false,
    val isWatchingAd: Boolean = false,
    val isGuest: Boolean = true,
    val error: EventError? = null,
) : UiState {

    /**
     * Intentos que le quedan al jugador, o null si el torneo no los limita.
     *
     * El cupo NO es solo `attemptsLimit`: incluye los extras ya desbloqueados con
     * anuncios (migración 0057). Vive aquí, derivado, y no como campo propio, para
     * que el ViewModel y la pantalla no puedan calcularlo cada uno a su manera — que
     * es como se acaba enseñando "te queda 1" a quien el servidor ya rechaza.
     */
    val attemptsLeft: Int?
        get() {
            val limit = event?.attemptsLimit ?: return null
            val board = leaderboard ?: return limit
            return (limit + board.myExtraAttempts - board.myAttempts).coerceAtLeast(0)
        }

    /**
     * ¿Puede desbloquear otro intento viendo un anuncio? Solo cuando se ha quedado
     * sin intentos —ofrecerlo antes sería vender algo que ya tiene— y el torneo aún
     * permite extras.
     */
    val canBuyAttempt: Boolean
        get() {
            val event = event ?: return false
            val used = leaderboard?.myExtraAttempts ?: 0
            return !isGuest && attemptsLeft == 0 && used < event.adAttemptsLimit
        }
}

/**
 * Fallos que la pantalla sabe contar. Enum y no `String`: el texto vive en
 * `strings.xml` (§10) y el dominio no debe cargar con frases.
 */
enum class EventError {
    /** El torneo cerró (o aún no abrió) según el servidor. */
    CLOSED,

    /** Se agotaron los intentos del jugador. */
    NO_ATTEMPTS,

    /** El anuncio no se completó (o no había): no hay intento extra. */
    AD_NOT_COMPLETED,

    /** Red o error inesperado: se ofrece reintentar. */
    GENERIC,
}

sealed interface EventIntent : UiIntent {
    /** Recarga la tabla (entrada a la pantalla, vuelta de una partida, reintento). */
    data object RefreshLeaderboard : EventIntent

    /** El jugador pulsa el CTA: entrar a jugar el torneo. */
    data object Play : EventIntent

    /**
     * Sin intentos: ver un anuncio para desbloquear otro. Es un intent aparte de
     * [Play] a propósito —son dos tratos distintos— y así la pantalla no tiene que
     * adivinar cuál de los dos quiso el jugador a partir del estado.
     */
    data object WatchAdForAttempt : EventIntent
}

sealed interface EventEffect : UiEffect {
    /**
     * Abre la partida en modo torneo.
     *
     * Es un efecto one-shot y no un flag del estado porque navegar dos veces al
     * mismo juego por una recomposición sería un bug visible (y un intento
     * malgastado, que en un torneo con tope duele).
     */
    data class LaunchGame(
        val event: GameEvent,
        val attemptsUsed: Int,
        /** Cupo TOTAL del jugador (libres + extras por anuncio); null = ilimitado. */
        val attemptsAllowed: Int?,
    ) : EventEffect

    /** Invitado que intenta competir: hay que mandarlo al login. */
    data object RequireSignIn : EventEffect
}
