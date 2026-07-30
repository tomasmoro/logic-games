package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext

/**
 * iOS: todavía **no** hay integración del SDK de Google Mobile Ads (requiere
 * CocoaPods/SPM + un puente Swift, ver Parte B del plan). Hasta entonces se registran
 * los presentadores **simulados** para que los flujos (intersticial en el breakpoint,
 * revivir/pista con recompensado) funcionen end-to-end en desarrollo.
 *
 * SUSTITUIR por presentadores reales (`GADInterstitialAd`/`GADRewardedAd` vía el puente
 * Swift) al integrar el SDK de iOS.
 */
actual fun installPlatformAdPresenters(adManager: AdManager, context: PlatformContext) {
    adManager.setInterstitialAdPresenter(SimulatedInterstitialAdPresenter())
    adManager.setRewardedAdPresenter(SimulatedRewardedAdPresenter())
}
