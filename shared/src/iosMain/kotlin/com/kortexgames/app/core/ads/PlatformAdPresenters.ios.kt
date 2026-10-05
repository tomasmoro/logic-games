package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext

/**
 * iOS: registra presentadores **reales** si `iOSApp.swift` ya publicó un
 * [IosAdBridge] en [IosAdBridgeHolder] (implementado con el SDK de Google Mobile Ads
 * — ver `AdMobBridge.swift`); si no (paquete SPM no enlazado todavía, o se llama
 * antes de que Swift registre el puente), cae a los **simulados** para que los
 * flujos (intersticial en el breakpoint, revivir/pista con recompensado) sigan
 * funcionando end-to-end en desarrollo sin romper nada.
 *
 * La selección de unidad real/prueba por build la hace [IosAdUnits] (gemelo del
 * `AdMobConfig` de Android); la carga, precarga y reintentos viven en el puente Swift.
 */
actual fun installPlatformAdPresenters(adManager: AdManager, context: PlatformContext) {
    val bridge = IosAdBridgeHolder.bridge
    if (bridge != null) {
        adManager.setInterstitialAdPresenter(BridgedInterstitialAdPresenter(bridge))
        adManager.setRewardedAdPresenter(BridgedRewardedAdPresenter(bridge))
    } else {
        adManager.setInterstitialAdPresenter(SimulatedInterstitialAdPresenter())
        adManager.setRewardedAdPresenter(SimulatedRewardedAdPresenter())
    }
}
