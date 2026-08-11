package com.kortexgames.app.core.ads

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Contrato que implementa el lado **Swift** (`iosApp`) con el SDK real de Google
 * Mobile Ads (`AdMobBridge.swift`). `shared` no enlaza el SDK de Google: en vez de
 * cinterop manual contra el XCFramework de SPM o CocoaPods (ver BACKLOG "AdMob iOS
 * (Parte B)"), la app anfitriona implementa esta interfaz mínima en Swift, donde el
 * SDK se usa sin fricción de interop, y Kotlin solo habla contra ella.
 *
 * Los métodos son callback-style (no `suspend`) porque Kotlin/Native no expone
 * `suspend fun` de forma directa a Swift; el puente de vuelta a corrutinas lo hacen
 * [BridgedInterstitialAdPresenter]/[BridgedRewardedAdPresenter] con
 * `suspendCancellableCoroutine`.
 */
interface IosAdBridge {
    /** Carga y muestra un intersticial; invoca [onFinished] al cerrarse (o si no había). */
    fun showInterstitial(onFinished: () -> Unit)

    /** Carga y muestra un recompensado; invoca [onFinished] con el desenlace real. */
    fun showRewarded(onFinished: (RewardResult) -> Unit)
}

/**
 * Buzón donde `iOSApp.swift` publica la implementación real de [IosAdBridge] al
 * arrancar. Mismo espíritu que
 * [com.kortexgames.app.data.remote.auth.CurrentActivityHolder] en Android: `shared`
 * no conoce el SDK de anuncios, solo un punto donde la plataforma deja su
 * implementación para que [installPlatformAdPresenters] la recoja.
 *
 * Si nada se registra todavía (o el paquete SPM de Google Mobile Ads no está
 * enlazado en Xcode), [installPlatformAdPresenters] cae a los presentadores
 * simulados: nunca rompe el flujo en desarrollo.
 */
object IosAdBridgeHolder {
    var bridge: IosAdBridge? = null
        private set

    /** Publica la implementación real. Llamar una única vez, antes de [com.kortexgames.app.MainViewController]. */
    fun register(bridge: IosAdBridge) {
        this.bridge = bridge
    }
}

/**
 * Adapta [IosAdBridge.showInterstitial] (callback de Swift) al contrato `suspend`
 * [InterstitialAdPresenter] que espera el [AdManager].
 */
internal class BridgedInterstitialAdPresenter(
    private val bridge: IosAdBridge,
) : InterstitialAdPresenter {
    override suspend fun show(): Unit = suspendCancellableCoroutine { cont ->
        bridge.showInterstitial { if (cont.isActive) cont.resume(Unit) }
    }
}

/**
 * Adapta [IosAdBridge.showRewarded] (callback de Swift) al contrato `suspend`
 * [RewardedAdPresenter] que espera el [AdManager].
 */
internal class BridgedRewardedAdPresenter(
    private val bridge: IosAdBridge,
) : RewardedAdPresenter {
    override suspend fun show(): RewardResult = suspendCancellableCoroutine { cont ->
        bridge.showRewarded { result -> if (cont.isActive) cont.resume(result) }
    }
}
