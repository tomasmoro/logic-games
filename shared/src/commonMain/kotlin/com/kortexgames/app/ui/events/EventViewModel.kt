package com.kortexgames.app.ui.events

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.ads.RewardResult
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.repository.AuthRepository
import com.kortexgames.app.domain.repository.EventsRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * ViewModel de la pantalla de torneo.
 *
 * Dos fuentes que llegan a ritmos distintos y por eso se tratan por separado:
 *
 *  - El **torneo** se observa en local (SQLDelight): está en memoria al entrar, así
 *    que la pantalla se pinta entera en su primer frame — nada de esqueletos.
 *  - La **tabla** se pide a la red en cada entrada. No se cachea a propósito: un
 *    leaderboard viejo es peor que uno ausente (el jugador cree que sigue 3º
 *    cuando ya es 9º), y la pantalla no se abre tantas veces como para que el
 *    tráfico importe.
 *
 * @param eventId torneo a mostrar. Llega como argumento de navegación, no como
 *   objeto, para no serializar el modelo en la ruta.
 * @param ads presentador de anuncios recompensados: en los torneos con
 *   `adAttemptsLimit`, un anuncio desbloquea un intento más.
 */
class EventViewModel(
    private val eventId: String,
    private val events: EventsRepository,
    authRepository: AuthRepository,
    private val audio: AudioAndHapticManager,
    private val ads: AdManager,
    private val clock: Clock = Clock.System,
) : MviViewModel<EventIntent, EventUiState, EventEffect>(EventUiState()) {

    init {
        events.observe(eventId)
            .onEach { event ->
                val hadEvent = currentState.event != null
                setState { copy(event = event) }
                // La tabla se pide en cuanto se conoce el torneo (y no antes: hace
                // falta su `rankByTime` para interpretar las marcas). Solo la primera
                // vez —las emisiones siguientes son refrescos del calendario, no
                // motivo para golpear la red otra vez.
                if (event != null && !hadEvent) refreshLeaderboard()
            }
            .launchIn(viewModelScope)

        authRepository.sessionState
            .onEach { session ->
                val wasGuest = currentState.isGuest
                val isGuest = session !is AuthState.Authenticated
                setState { copy(isGuest = isGuest) }
                // Volver de iniciar sesión sin recargar dejaría la pantalla diciendo
                // "inicia sesión para competir" con la sesión ya abierta.
                if (wasGuest && !isGuest) refreshLeaderboard()
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: EventIntent) {
        when (intent) {
            EventIntent.RefreshLeaderboard -> viewModelScope.launch { refreshLeaderboard() }
            EventIntent.Play -> play()
            EventIntent.WatchAdForAttempt -> watchAdForAttempt()
        }
    }

    /**
     * Entrar a jugar. Las dos puertas que se cierran aquí (invitado y torneo
     * cerrado) también las valida el servidor: esto es para no gastarle al jugador
     * una pantalla de juego que iba a terminar en error.
     */
    private fun play() {
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)

        val event = currentState.event ?: return
        if (currentState.isGuest) {
            sendEffect(EventEffect.RequireSignIn)
            return
        }
        if (!event.isLiveAt(clock.now())) {
            setState { copy(error = EventError.CLOSED) }
            return
        }
        // El cupo sale del estado derivado (incluye los extras por anuncio) y los
        // intentos gastados de `myAttempts`, no de `me.attempts`: quien solo ha
        // abandonado no tiene fila en la tabla (`me` es null) y sin embargo SÍ ha
        // gastado intentos — ver la migración 0056.
        val left = currentState.attemptsLeft
        if (left != null && left <= 0) {
            setState { copy(error = EventError.NO_ATTEMPTS) }
            return
        }
        sendEffect(
            EventEffect.LaunchGame(
                event = event,
                attemptsUsed = currentState.leaderboard?.myAttempts ?: 0,
                attemptsAllowed = currentState.leaderboard?.let { board ->
                    event.attemptsLimit?.plus(board.myExtraAttempts)
                } ?: event.attemptsLimit,
            ),
        )
    }

    /**
     * Ver un anuncio para desbloquear un intento más.
     *
     * El orden importa y no es casual: primero el anuncio, y SOLO si se completa
     * (`EARNED`) se pide el intento. Conceder antes de ver sería regalar el intento
     * a quien cierra el anuncio a los dos segundos; pedirlo después y fallar deja
     * al jugador sin nada habiendo cumplido su parte, así que ese caso se le cuenta
     * con un error explícito en vez de en silencio.
     *
     * Tras conceder se recarga la tabla: de ahí sale el cupo (`myExtraAttempts`) que
     * decide si el CTA vuelve a ser "entrar al torneo".
     */
    private fun watchAdForAttempt() {
        if (currentState.isWatchingAd) return
        val event = currentState.event ?: return
        if (currentState.isGuest) {
            sendEffect(EventEffect.RequireSignIn)
            return
        }
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)

        viewModelScope.launch {
            setState { copy(isWatchingAd = true, error = null) }
            val reward = ads.showRewardedAd()
            if (reward != RewardResult.EARNED) {
                setState { copy(isWatchingAd = false, error = EventError.AD_NOT_COMPLETED) }
                return@launch
            }
            val granted = events.grantExtraAttempt(event)
            setState {
                copy(
                    isWatchingAd = false,
                    error = if (granted.isSuccess) null else EventError.GENERIC,
                )
            }
            if (granted.isSuccess) refreshLeaderboard()
        }
    }

    private suspend fun refreshLeaderboard() {
        val event = currentState.event ?: return
        if (currentState.isGuest) {
            // Sin sesión no hay tabla que pedir (la RPC solo está concedida a
            // `authenticated`): se limpia lo que hubiera en vez de dejar en pantalla
            // la tabla de la sesión anterior.
            setState { copy(leaderboard = null, isLoadingLeaderboard = false) }
            return
        }
        setState { copy(isLoadingLeaderboard = true, error = null) }
        val board = events.leaderboard(event)
        setState {
            copy(
                leaderboard = board,
                isLoadingLeaderboard = false,
                // Un null aquí es un fallo real (red caída o RPC rota): el caso
                // "todavía no ha jugado nadie" llega como tabla vacía, no como null.
                error = if (board == null) EventError.GENERIC else null,
            )
        }
    }
}
