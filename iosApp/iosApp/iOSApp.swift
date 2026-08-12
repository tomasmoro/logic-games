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
        //
        // El consentimiento (ATT + UMP) ya NO se pide aquí: lo dispara el código
        // común (`beginAdConsentFlow`) cuando el jugador termina la bienvenida de la
        // primera apertura. Estrenar la app con el diálogo de seguimiento de Apple,
        // antes incluso de ver un juego, era la peor primera impresión posible.
        IosAdBridgeHolder.shared.register(bridge: AdMobBridge.shared)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}