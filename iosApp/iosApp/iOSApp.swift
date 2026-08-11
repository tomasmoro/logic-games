import Shared
import SwiftUI

@main
struct iOSApp: App {
    init() {
        // Publica el puente de anuncios ANTES de que `ContentView` construya el
        // `AppGraph` (MainViewController() lo hace al aparecer la vista de Compose),
        // para que `installPlatformAdPresenters` ya encuentre el `IosAdBridge`
        // registrado en vez de caer a los presentadores simulados. Ver
        // `AdMobBridge.swift` / `IosAdBridge.kt`.
        IosAdBridgeHolder.shared.register(bridge: AdMobBridge.shared)
        AdMobBridge.shared.requestConsentAndStart()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}