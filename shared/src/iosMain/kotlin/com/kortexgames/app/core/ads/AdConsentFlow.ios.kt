package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * iOS: delega en el puente Swift ([IosAdBridge.requestConsentAndStart]), que encadena
 * ATT (App Tracking Transparency) → UMP → arranque del SDK de Google Mobile Ads.
 *
 * Si `iOSApp.swift` no registró puente (o el paquete SPM de Google no está enlazado)
 * no hay nada que consentir: los presentadores simulados no piden anuncios reales.
 *
 * El salto a [Dispatchers.Main] no es opcional: tanto ATT como UMP presentan UI, y el
 * SDK de UMP se comporta de forma indefinida fuera del hilo principal (fue la causa
 * del formulario que se quedaba congelado; ver `AdMobBridge.swift`).
 */
actual suspend fun beginAdConsentFlow(context: PlatformContext) {
    val bridge = IosAdBridgeHolder.bridge ?: return
    withContext(Dispatchers.Main) { bridge.requestConsentAndStart() }
}
