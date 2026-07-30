package com.kortexgames.app.core.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Eventos que la capa de UI escucha para mostrar el anuncio real (AdMob, etc.). */
sealed interface AdEvent {
    /**
     * Ha llegado el momento de mostrar un intersticial en un **breakpoint** seguro
     * (nunca en medio de la partida). Lo emite [AdManager.onAdBreakpoint] cuando el
     * contador ya cruzó el umbral; un único colector a nivel de app lo consume
     * llamando a [AdManager.showInterstitialAd].
     */
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
 * Seam de plataforma que presenta un anuncio **intersticial real** (AdMob
 * `InterstitialAd`, etc.) y **suspende** hasta que el usuario lo cierra. Simétrico a
 * [RewardedAdPresenter] pero sin resultado: el intersticial solo interrumpe, no
 * concede nada. `commonMain` no conoce ningún SDK, así que cada plataforma inyecta su
 * implementación con [AdManager.setInterstitialAdPresenter].
 */
fun interface InterstitialAdPresenter {
    /** Carga y muestra el intersticial; suspende hasta que el usuario lo cierra. */
    suspend fun show()
}

/**
 * Gobierna cuándo se muestran los anuncios. Modelo de tiempo (decidido con producto):
 *
 *  - **El contador corre desde que la app entra a primer plano**, NO solo mientras se
 *    juega. Se pausa al pasar a background ([onAppBackground]) y se reanuda al volver
 *    ([onAppForeground]). Así los 7 min ([interval]) cuentan tiempo real de uso.
 *  - **Nunca se interrumpe la partida.** Al cruzar el umbral NO se muestra el anuncio
 *    de inmediato: se marca uno "pendiente" y se cobra en el próximo **breakpoint**
 *    natural ([onAdBreakpoint]) — salir de un juego o avanzar/terminar de nivel — donde
 *    interrumpir es aceptable. Interrumpir a mitad de juego daría mala experiencia.
 *  - **Premium nunca ve anuncios:** se valida en cada tick y en cada intento; su
 *    contador se mantiene en cero y cualquier pendiente se descarta.
 *
 * El anuncio **recompensado** ([showRewardedAd]) es aparte: lo dispara el usuario a
 * propósito (p. ej. "ver un anuncio para revivir / obtener una pista") y no depende de
 * este contador ni de los breakpoints.
 *
 * Escalabilidad: los dos únicos puntos que alimentan el contador y los breakpoints son
 * centrales (lifecycle de la app + el hook de navegación de `App.kt`), así que sumar
 * juegos nuevos no requiere tocar la lógica de anuncios.
 *
 * @param isPremium snapshot del plan del usuario (se evalúa en cada tick, así una
 *        compra premium a mitad de sesión detiene los anuncios al instante).
 */
class AdManager(
    private val scope: CoroutineScope,
    private val isPremium: () -> Boolean,
    private val interval: Duration = 7.minutes,
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

    /**
     * Presentador de intersticiales de la plataforma. `null` hasta que se registra con
     * [setInterstitialAdPresenter]; mientras tanto [showInterstitialAd] es un no-op
     * (no se puede interrumpir con un anuncio que no existe).
     */
    private var interstitialAdPresenter: InterstitialAdPresenter? = null

    /**
     * true mientras la app está en primer plano. Es la **puerta del contador**: el
     * tiempo solo se acumula en foreground (el usuario está usando la app). Arranca en
     * `true` porque el grafo se construye cuando la app ya está abierta.
     *
     * `@Volatile`: lo escribe el hilo de UI (lifecycle de la Activity) y lo lee el loop
     * en `Dispatchers.Default`; sin la barrera de memoria el loop podría no ver el cambio.
     */
    @Volatile
    private var foreground: Boolean = true

    /**
     * Hay un intersticial "debiendo": el contador cruzó el umbral pero aún no hubo un
     * breakpoint seguro donde mostrarlo. Es un booleano (no una cola): por larga que sea
     * la sesión sin breakpoints, solo se debe UN anuncio, nunca una ráfaga acumulada.
     *
     * `@Volatile`: lo pone el loop (Default) y lo leen/limpian el loop y el hilo de UI
     * (onAdBreakpoint / showInterstitialAd); la visibilidad entre hilos es imprescindible.
     */
    @Volatile
    private var pendingInterstitial: Boolean = false

    /**
     * Un intersticial ya está en curso (emitido/mostrándose). Evita disparar dos
     * anuncios solapados si llegan breakpoints seguidos antes de que el primero cierre.
     */
    @Volatile
    private var showingInterstitial: Boolean = false

    /**
     * Petición de reinicio de la cadencia tras mostrar un anuncio. La pone el hilo de UI
     * ([showInterstitialAd]) y la **consume el loop**, que así queda como ÚNICO escritor
     * de [accumulated]: evita la carrera lectura-modificación-escritura (y el problema de
     * visibilidad) que había al poner `accumulated = 0` desde otro hilo — esa era la
     * causa de que "el temporizador no se reseteara" y el anuncio saliera en cada breakpoint.
     */
    @Volatile
    private var resetRequested: Boolean = false

    /** Solo lo muta el loop (ver [resetRequested]); no se escribe desde otros hilos. */
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

            // Reinicio pedido tras mostrar un anuncio: arranca una cadencia nueva y limpia
            // (descarta el tiempo transcurrido durante el anuncio). Es el único punto donde
            // `accumulated` vuelve a cero por un anuncio, garantizando un solo escritor.
            if (resetRequested) {
                resetRequested = false
                accumulated = Duration.ZERO
                pendingInterstitial = false
                continue
            }

            // Premium: nunca acumula ni debe anuncios; se resetea por si cambió de plan.
            if (isPremium()) {
                accumulated = Duration.ZERO
                pendingInterstitial = false
                continue
            }
            // App en background: no cuenta (y no se podría mostrar nada de todos modos).
            if (!foreground) continue

            accumulated += elapsed
            if (accumulated >= interval) {
                accumulated -= interval          // conserva el sobrante (exactitud)
                // NO se muestra aquí: se marca pendiente y se cobra en el próximo
                // breakpoint para no interrumpir la partida en curso.
                pendingInterstitial = true
            }
        }
    }

    /** La app vuelve a primer plano: reanuda el conteo de tiempo de uso. */
    fun onAppForeground() { foreground = true }

    /** La app pasa a segundo plano: pausa el conteo (no se acumula fuera de foco). */
    fun onAppBackground() { foreground = false }

    /**
     * Notifica un **breakpoint** natural: un momento donde interrumpir con un
     * intersticial es aceptable (salir de un juego al menú, terminar/avanzar de nivel).
     * Es el ÚNICO sitio donde un intersticial pendiente llega a mostrarse.
     *
     * Si hay uno pendiente (y no es premium ni hay otro en curso) emite
     * [AdEvent.ShowInterstitial] para que el colector de la UI lo presente. Si no hay
     * nada pendiente, no hace nada: llamar de más es barato y seguro, por eso el hook
     * central de navegación puede invocarlo en cada salida de juego sin condicionar.
     */
    fun onAdBreakpoint() {
        if (isPremium()) { pendingInterstitial = false; return }
        if (pendingInterstitial && !showingInterstitial) {
            // Marca "en curso" ya para que breakpoints seguidos no re-emitan; se libera
            // en showInterstitialAd() cuando el anuncio se cierra (o falla).
            showingInterstitial = true
            _adEvents.tryEmit(AdEvent.ShowInterstitial)
        }
    }

    /**
     * Registra el presentador de anuncios recompensados de la plataforma (AdMob).
     * Se llama una vez al ensamblar el grafo de dependencias; el `commonMain` no
     * sabe de SDKs de anuncios.
     */
    fun setRewardedAdPresenter(presenter: RewardedAdPresenter) {
        rewardedAdPresenter = presenter
    }

    /**
     * Registra el presentador de intersticiales de la plataforma (AdMob). Igual que
     * [setRewardedAdPresenter], se inyecta al ensamblar el grafo desde código de
     * plataforma (o un placeholder en desarrollo).
     */
    fun setInterstitialAdPresenter(presenter: InterstitialAdPresenter) {
        interstitialAdPresenter = presenter
    }

    /**
     * Presenta el intersticial pendiente y suspende hasta que se cierra. La invoca el
     * colector de [adEvents] al recibir [AdEvent.ShowInterstitial], pero también sirve
     * para forzar un intersticial puntual.
     *
     *  - **Premium** o **sin presentador**: no muestra nada (no-op), pero igualmente
     *    limpia el estado para no quedar "atascado" debiendo un anuncio.
     *  - En el resto delega en el [InterstitialAdPresenter] de la plataforma.
     *
     * El `finally` garantiza que, pase lo que pase (cierre normal, fallo de carga o
     * cancelación de la corrutina), el estado quede consistente y la cadencia se
     * reinicie: el siguiente anuncio será [interval] más tarde.
     */
    suspend fun showInterstitialAd() {
        try {
            if (!isPremium()) interstitialAdPresenter?.show()
        } finally {
            pendingInterstitial = false
            showingInterstitial = false
            // El reinicio de `accumulated` lo hace el loop (único escritor) al consumir
            // esta bandera; así la cadencia se reinicia de verdad tras cada anuncio.
            resetRequested = true
        }
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

    /**
     * Reinicia el contador (p. ej. tras mostrar un anuncio manualmente). Se hace vía la
     * bandera para respetar el "único escritor" de [accumulated] (seguro desde cualquier hilo).
     */
    fun reset() { resetRequested = true }

    /** Tiempo restante hasta que se deba el próximo anuncio (útil para debug/telemetría). */
    fun timeUntilNextAd(): Duration = (interval - accumulated).coerceAtLeast(Duration.ZERO)

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }
}
