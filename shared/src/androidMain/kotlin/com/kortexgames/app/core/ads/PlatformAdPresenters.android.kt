package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext

/**
 * Android: registra los presentadores REALES de intersticial y recompensado.
 *
 * **No inicializa el SDK aquí**: `MobileAds.initialize` corre en [AdConsentManager]
 * DESPUÉS de resolver el consentimiento GDPR/UMP, que necesita una `Activity` (ver
 * [com.kortexgames.app.MainActivity]) y no el `applicationContext` con el que se
 * arma el grafo. Registrar el presentador es seguro antes del init: solo toca el SDK al
 * llamar `show()`, momento en que el consentimiento y el init ya se resolvieron.
 *
 * El SDK requiere además el App ID como `meta-data` en el manifest (placeholder
 * `admobAppId`); sin él, `MobileAds.initialize` lanza en tiempo de ejecución.
 */
actual fun installPlatformAdPresenters(adManager: AdManager, context: PlatformContext) {
    val appContext = context.context.applicationContext
    adManager.setInterstitialAdPresenter(
        AdMobInterstitialAdPresenter(appContext, AdMobConfig.interstitialUnitId(appContext)),
    )
    adManager.setRewardedAdPresenter(
        AdMobRewardedAdPresenter(appContext, AdMobConfig.rewardedUnitId(appContext)),
    )
}
