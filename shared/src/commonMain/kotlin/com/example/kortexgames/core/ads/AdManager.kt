package com.example.kortexgames.core.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Eventos que la capa de UI escucha para mostrar el anuncio real (AdMob, etc.). */
sealed interface AdEvent {
    data object ShowInterstitial : AdEvent
}

/**
 * Resultado de intentar mostrar un anuncio **recompensado** (rewarded). A diferencia
 * del intersticial (que solo interrumpe), el recompensado es un trato: el usuario ve
 * el anuncio y a cambio recibe una ventaja opcional (p. ej. revivir con una vida).
 */
enum class RewardResult {
    /** Se vio el anuncio completo (o el usuario es premium): concede la recompensa. */
    EARNED,

    /** El usuario cerró el anuncio antes de terminarlo: NO se concede la recompensa. */
    DISMISSED,

    /** No había anuncio disponible o falló la carga: NO se concede la recompensa. */
    UNAVAILABLE,
}

/**
 * Seam de plataforma que presenta un anuncio recompensado **real** (AdMob
 * `RewardedAd`, etc.) y **suspende** hasta que el usuario lo cierra, devolviendo el
 * [RewardResult]. En `commonMain` no hay SDK de anuncios, así que cada plataforma
 * (o el arranque de la app) inyecta su implementación con
 * [AdManager.setRewardedAdPresenter]. Es una `fun interface` para poder pasar una
 * lambda `suspend` directamente.
 */
fun interface RewardedAdPresenter {
    /** Muestra el anuncio y suspende hasta que se cierra; devuelve el resultado. */
    suspend fun show(): RewardResult
}

/**
 * Contabiliza SOLO el tiempo de juego activo y emite [AdEvent.ShowInterstitial]
 * cada [interval] (3 min por defecto). Requisitos cubiertos:
 *
 *  1. Corre en un coroutine (loop con TimeSource monotónico → sin drift).
 *  2. Escucha el estado de la app: [onEnterGameplay] / [onEnterMenuOrPause].
 *     En menús/pausa el temporizador NO acumula.
 *  3. Valida premium ANTES de emitir: un usuario premium nunca ve anuncios y su
 *     contador se mantiene en cero.
 *
 * @param isPremium snapshot del plan del usuario (se evalúa en cada tick, así
 *        una compra premium a mitad de partida detiene los anuncios al instante).
 */
class AdManager(
    private val scope: CoroutineScope,
    private val isPremium: () -> Boolean,
    private val interval: Duration = 3.minutes,
    private val tick: Duration = 1.seconds,
) {
    private val _adEvents = MutableSharedFlow<AdEvent>(extraBufferCapacity = 1)
    val adEvents: SharedFlow<AdEvent> = _adEvents.asSharedFlow()

    /**
     * Presentador de anuncios recompensados de la plataforma. `null` hasta que se
     * registra con [setRewardedAdPresenter]; mientras tanto [showRewardedAd] devuelve
     * [RewardResult.UNAVAILABLE] (no se puede recompensar sin un anuncio real).
     */
    private var rewardedAdPresenter: RewardedAdPresenter? = null

    /** true mientras hay una partida en curso en primer plano. */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying

    private var accumulated: Duration = Duration.ZERO
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        val timeSource = TimeSource.Monotonic
        var lastMark = timeSource.markNow()

        // delay() lanza CancellationException al cancelar el Job → salida limpia.
        while (true) {
            delay(tick)
            val now = timeSource.markNow()
            val elapsed = (now - lastMark)
            lastMark = now

            // Premium: nunca acumula ni emite; se resetea por si cambió de plan.
            if (isPremium()) {
                accumulated = Duration.ZERO
                continue
            }
            // Pausa / menús: no cuenta.
            if (!_isPlaying.value) continue

            accumulated += elapsed
            if (accumulated >= interval) {
                accumulated -= interval          // conserva el sobrante (exactitud)
                _adEvents.emit(AdEvent.ShowInterstitial)
            }
        }
    }

    /** El usuario entra a una partida: arranca/continúa el conteo activo. */
    fun onEnterGameplay() { _isPlaying.value = true }

    /** El usuario va a un menú, pausa o la app pasa a background: detiene el conteo. */
    fun onEnterMenuOrPause() { _isPlaying.value = false }

    /** Reinicia el contador (p. ej. tras mostrar un anuncio manualmente). */
    fun reset() { accumulated = Duration.ZERO }

    /**
     * Registra el presentador de anuncios recompensados de la plataforma (AdMob).
     * Se llama una vez al ensamblar el grafo de dependencias; el `commonMain` no
     * sabe de SDKs de anuncios.
     */
    fun setRewardedAdPresenter(presenter: RewardedAdPresenter) {
        rewardedAdPresenter = presenter
    }

    /**
     * Solicita un anuncio recompensado (p. ej. para **revivir** con una vida extra) y
     * suspende hasta conocer el desenlace.
     *
     *  - **Premium**: concede la recompensa SIN mostrar anuncio ([RewardResult.EARNED]);
     *    forma parte del valor de premium (nunca ve anuncios) y evita el gasto de red.
     *  - **Sin presentador** registrado: [RewardResult.UNAVAILABLE] (no se puede
     *    recompensar sin un anuncio real; quien llama debe tratarlo como "no disponible").
     *  - En el resto de casos delega en el [RewardedAdPresenter] de la plataforma.
     *
     * @return el [RewardResult]; solo [RewardResult.EARNED] debe conceder la ventaja.
     */
    suspend fun showRewardedAd(): RewardResult {
        if (isPremium()) return RewardResult.EARNED
        return rewardedAdPresenter?.show() ?: RewardResult.UNAVAILABLE
    }

    /** Tiempo restante hasta el próximo anuncio (útil para debug/telemetría). */
    fun timeUntilNextAd(): Duration = (interval - accumulated).coerceAtLeast(Duration.ZERO)

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }
}
