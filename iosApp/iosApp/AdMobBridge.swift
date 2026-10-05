import AppTrackingTransparency
import Foundation
import GoogleMobileAds
import OSLog
import Shared
import UIKit
import UserMessagingPlatform

/// Puente real de AdMob para iOS: implementa el protocolo Kotlin `IosAdBridge`
/// (ver `IosAdBridge.kt` / `PlatformAdPresenters.ios.kt` en `shared`) usando el SDK
/// de Google Mobile Ads. Vive en Swift a propósito: así `shared` no necesita enlazar
/// el SDK de Google (CocoaPods o cinterop manual contra el XCFramework de SPM), solo
/// habla contra una interfaz Kotlin mínima — ver BACKLOG "AdMob iOS (Parte B)".
///
/// API "Swift-first" del SDK v13 (sin prefijo `GAD`/`UMP`; ver
/// developers.google.com/admob/ios/migration). Requiere DOS productos del paquete
/// SPM `swift-package-manager-google-mobile-ads` en el target `iosApp`:
/// **GoogleMobileAds** y **GoogleUserMessagingPlatform** (esta última es una
/// dependencia transitiva — Xcode la lista aparte al añadir el paquete, hay que
/// marcarla también).
///
/// Los ad unit ID **no se deciden aquí**: los resuelve `IosAdUnits` en `shared`
/// (iosMain), que aplica la misma política que `AdMobConfig` en Android — unidad real
/// solo si el binario es de release Y hay una configurada en `secrets.properties`; en
/// cualquier otro caso, la de prueba. Así no hay ningún paso manual que recordar antes
/// de publicar, y un clon del repo sin secretos sigue funcionando con anuncios de
/// prueba.
///
/// **Hilo:** todo el estado mutable de esta clase se toca SOLO desde el hilo principal
/// (el SDK lo exige para cargar/presentar, y así los flags no necesitan cerrojos). Los
/// métodos que entran desde Kotlin saltan a `@MainActor` antes de hacer nada.
///
/// **Diagnóstico:** cada paso que puede dejar la app sin anuncios (consentimiento que
/// falla, carga sin inventario, presentación rechazada) se registra en el log unificado
/// con la categoría `AdMob`. Un build de la App Store no tiene depurador, pero el log
/// sí se puede leer desde Console.app filtrando por esa categoría: sin estas líneas un
/// "no salen anuncios" es indistinguible entre fallo de código, de configuración de
/// AdMob o simple falta de inventario.
final class AdMobBridge: NSObject, IosAdBridge {

    static let shared = AdMobBridge()

    /// Ad units resueltos por `shared` (ver `IosAdUnits`). Se consultan como propiedad
    /// calculada, no se copian a un `let`, para que la política viva en un único sitio
    /// compartido con Android y no haya dos verdades sobre qué anuncio se pide.
    private var interstitialUnitId: String { IosAdUnits.shared.interstitialUnitId }
    private var rewardedUnitId: String { IosAdUnits.shared.rewardedUnitId }

    /// `true` cuando el SDK ya arrancó (tras resolver consentimiento). Antes de esto
    /// no se piden anuncios, igual que Android espera a `AdConsentManager`.
    private var sdkStarted = false

    /// `true` desde que Kotlin pidió el flujo de consentimiento. Distingue "todavía en
    /// la bienvenida, no toca pedir nada" de "se pidió y falló": solo en el segundo caso
    /// tiene sentido reintentar al volver a primer plano.
    private var consentRequested = false

    /// El flujo ATT → UMP está en curso. Cerrar el diálogo de ATT o el formulario de
    /// UMP dispara `didBecomeActive`; sin este flag, el reintento de primer plano
    /// lanzaría un segundo flujo en paralelo (y un segundo formulario encima).
    private var consentInFlight = false

    private var interstitial: InterstitialAd?
    private var rewarded: RewardedAd?
    /// Momento de carga de cada anuncio: Google los da por caducados pasada 1 hora y
    /// presentar uno caducado falla, así que se descartan antes (ver `maxAdAge`).
    private var interstitialLoadedAt: Date?
    private var rewardedLoadedAt: Date?
    /// Hay una petición de carga en vuelo (evita pedir el mismo formato dos veces).
    private var interstitialLoading = false
    private var rewardedLoading = false
    private var pendingInterstitialFinish: (() -> Void)?
    private var pendingRewardedFinish: ((RewardResult) -> Void)?
    private var rewardEarned = false

    private static let log = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "KortexGames",
        category: "AdMob",
    )

    /// Vida útil que damos a un anuncio precargado. El límite de Google es 1 hora; se
    /// deja margen para no presentar uno que caduque mientras se abre.
    private static let maxAdAge: TimeInterval = 50 * 60

    /// Esperas (s) entre reintentos de carga. Una unidad real recién estrenada devuelve
    /// "no fill" a menudo: sin reintento, un único fallo al arrancar dejaba la sesión
    /// entera sin anuncios. La lista es corta a propósito —Google penaliza el martilleo—
    /// porque además cada intento de mostrar y cada vuelta a primer plano reinician la
    /// carga.
    private static let retryDelays: [TimeInterval] = [10, 30, 60, 120]

    /// Cuánto se espera, al ir a mostrar, a que termine una carga aún no lista. El
    /// intersticial espera poco: llega en un corte entre pantallas y, si tarda, aparecería
    /// encima de la siguiente partida. El recompensado lo pidió el jugador, así que
    /// compensa esperar más antes de decirle que no hay anuncio.
    private static let interstitialWait: TimeInterval = 3
    private static let rewardedWait: TimeInterval = 8

    private override init() {
        super.init()
        // Al volver a primer plano: reintenta el consentimiento si falló (p. ej. primera
        // apertura sin red) y repone los anuncios caducados mientras la app dormía.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main,
        ) { [weak self] _ in
            guard let self, self.consentRequested, !self.consentInFlight else { return }
            if self.sdkStarted {
                self.loadInterstitialIfNeeded()
                self.loadRewardedIfNeeded()
            } else {
                self.requestConsentInfo()
            }
        }
    }

    // MARK: - Arranque + consentimiento (equivalente a AdConsentManager en Android)

    /// Pide permiso de seguimiento (ATT) y consentimiento GDPR/UMP y, en cuanto se
    /// pueden pedir anuncios, arranca el SDK. Llamar **una vez** al lanzar la app,
    /// antes de que `MainViewController()` construya el `AppGraph`.
    func requestConsentAndStart() {
        consentRequested = true
        consentInFlight = true
        if #available(iOS 14, *) {
            // Apple NO garantiza que este completion handler llegue en el hilo
            // principal (aunque en la práctica casi siempre lo hace). Sin el
            // `DispatchQueue.main.async`, `requestConsentInfo()` puede acabar
            // tocando `UIApplication.shared` (en `rootViewController()`) y el SDK
            // de UMP desde un hilo en segundo plano — comportamiento indefinido en
            // UIKit que se manifestaba como el formulario de consentimiento
            // quedándose "congelado" (no respondía al toque) hasta forzar cierre y
            // reabrir la app.
            ATTrackingManager.requestTrackingAuthorization { [weak self] _ in
                DispatchQueue.main.async {
                    self?.requestConsentInfo()
                }
            }
        } else {
            requestConsentInfo()
        }
    }

    private func requestConsentInfo() {
        consentInFlight = true
        let parameters = RequestParameters()
        // Para forzar el formulario en pruebas fuera de la UE: añadir aquí un
        // DebugSettings con geography = .EEA y el device id (sale en consola al
        // pedir el consentimiento). No se fija en código porque es por-dispositivo.
        ConsentInformation.shared.requestConsentInfoUpdate(
            with: parameters,
            completionHandler: { [weak self] error in
                guard let self else { return }
                Task { @MainActor in
                    defer { self.consentInFlight = false }
                    if let error {
                        // Sin red u otro fallo: seguimos si una sesión previa ya dejó
                        // consentimiento. Si no, NO se arranca el SDK (pedir anuncios sin
                        // consentimiento resuelto incumple la política de AdMob) y se
                        // reintenta al volver a primer plano. Ojo: si este error se
                        // repite siempre, suele ser configuración —la app iOS sin mensaje
                        // publicado en AdMob → Privacidad y mensajes—, no la red.
                        Self.log.error("UMP: fallo al actualizar el consentimiento: \(error.localizedDescription, privacy: .public)")
                    } else if let root = Self.rootViewController() {
                        do {
                            try await ConsentForm.loadAndPresentIfRequired(from: root)
                        } catch {
                            Self.log.error("UMP: fallo al cargar/mostrar el formulario: \(error.localizedDescription, privacy: .public)")
                        }
                    }
                    if ConsentInformation.shared.canRequestAds {
                        self.startSdkOnce()
                    } else {
                        Self.log.error("UMP: canRequestAds=false, el SDK de anuncios NO arranca (estado \(ConsentInformation.shared.consentStatus.rawValue, privacy: .public))")
                    }
                }
            },
        )
        // Arranque rápido: si una sesión previa ya dejó consentimiento válido, no
        // esperamos al callback (el flag `sdkStarted` evita el doble arranque).
        if ConsentInformation.shared.canRequestAds {
            startSdkOnce()
        }
    }

    private func startSdkOnce() {
        guard !sdkStarted else { return }
        sdkStarted = true
        Self.log.info("SDK arrancando. Anuncios reales: \(IosAdUnits.shared.usaAnunciosReales, privacy: .public)")
        MobileAds.shared.start()
        loadInterstitialIfNeeded()
        loadRewardedIfNeeded()
    }

    // MARK: - IosAdBridge (llamado desde Kotlin vía Bridged*AdPresenter)

    func showInterstitial(onFinished: @escaping () -> Void) {
        Task { @MainActor in
            guard self.sdkStarted else {
                Self.log.error("Intersticial omitido: el SDK no arrancó (consentimiento sin resolver)")
                onFinished()
                return
            }
            self.loadInterstitialIfNeeded()
            guard let ad = await Self.wait(Self.interstitialWait, for: { self.freshInterstitial }),
                  let root = Self.rootViewController()
            else {
                Self.log.error("Intersticial omitido: no había anuncio cargado")
                onFinished()
                return
            }
            self.pendingInterstitialFinish = onFinished
            ad.fullScreenContentDelegate = self
            ad.present(from: root)
        }
    }

    func showRewarded(onFinished: @escaping (RewardResult) -> Void) {
        Task { @MainActor in
            guard self.sdkStarted else {
                Self.log.error("Recompensado no disponible: el SDK no arrancó (consentimiento sin resolver)")
                onFinished(.unavailable)
                return
            }
            self.loadRewardedIfNeeded()
            guard let ad = await Self.wait(Self.rewardedWait, for: { self.freshRewarded }),
                  let root = Self.rootViewController()
            else {
                Self.log.error("Recompensado no disponible: no había anuncio cargado")
                onFinished(.unavailable)
                return
            }
            self.pendingRewardedFinish = onFinished
            self.rewardEarned = false
            ad.fullScreenContentDelegate = self
            ad.present(from: root) { [weak self] in
                self?.rewardEarned = true
            }
        }
    }

    // MARK: - Carga (precarga + reintento)
    //
    // Se precarga al arrancar el SDK y tras cada cierre, para que el anuncio salga sin
    // latencia. Pero la precarga sola no basta: si esa única petición fallaba ("no
    // fill", red), la variable se quedaba en nil y NADA volvía a pedir un anuncio —la
    // recarga colgaba del cierre de uno que nunca llegaba a mostrarse—. Por eso además:
    // reintento con espera tras cada fallo, y carga bajo demanda en cada intento de
    // mostrar y en cada vuelta a primer plano.

    /// Intersticial listo para presentar, o nil si no hay o ya caducó.
    private var freshInterstitial: InterstitialAd? {
        guard let ad = interstitial, let at = interstitialLoadedAt,
              Date().timeIntervalSince(at) < Self.maxAdAge else { return nil }
        return ad
    }

    /// Recompensado listo para presentar, o nil si no hay o ya caducó.
    private var freshRewarded: RewardedAd? {
        guard let ad = rewarded, let at = rewardedLoadedAt,
              Date().timeIntervalSince(at) < Self.maxAdAge else { return nil }
        return ad
    }

    /// Pide un intersticial si no hay uno vigente ni una petición en vuelo.
    /// - Parameter attempt: nº de fallos seguidos de esta cadena; indexa `retryDelays`.
    private func loadInterstitialIfNeeded(attempt: Int = 0) {
        guard sdkStarted, !interstitialLoading, freshInterstitial == nil else { return }
        interstitialLoading = true
        Task { @MainActor in
            do {
                self.interstitial = try await InterstitialAd.load(with: self.interstitialUnitId, request: Request())
                self.interstitialLoadedAt = Date()
                self.interstitialLoading = false
            } catch {
                self.interstitialLoading = false
                Self.log.error("Intersticial: fallo de carga (intento \(attempt + 1, privacy: .public)): \(error.localizedDescription, privacy: .public)")
                guard attempt < Self.retryDelays.count else { return }
                try? await Task.sleep(nanoseconds: UInt64(Self.retryDelays[attempt] * 1_000_000_000))
                self.loadInterstitialIfNeeded(attempt: attempt + 1)
            }
        }
    }

    /// Gemelo de `loadInterstitialIfNeeded` para el recompensado.
    private func loadRewardedIfNeeded(attempt: Int = 0) {
        guard sdkStarted, !rewardedLoading, freshRewarded == nil else { return }
        rewardedLoading = true
        Task { @MainActor in
            do {
                self.rewarded = try await RewardedAd.load(with: self.rewardedUnitId, request: Request())
                self.rewardedLoadedAt = Date()
                self.rewardedLoading = false
            } catch {
                self.rewardedLoading = false
                Self.log.error("Recompensado: fallo de carga (intento \(attempt + 1, privacy: .public)): \(error.localizedDescription, privacy: .public)")
                guard attempt < Self.retryDelays.count else { return }
                try? await Task.sleep(nanoseconds: UInt64(Self.retryDelays[attempt] * 1_000_000_000))
                self.loadRewardedIfNeeded(attempt: attempt + 1)
            }
        }
    }

    /// Espera hasta `seconds` a que `value` deje de ser nil (una carga en vuelo que está
    /// a punto de terminar). Sondea en vez de encadenarse a la tarea de carga para no
    /// acoplar el "mostrar" a la cadena de reintentos.
    @MainActor
    private static func wait<T>(_ seconds: TimeInterval, for value: () -> T?) async -> T? {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if let v = value() { return v }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        return value()
    }

    private static func rootViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?.rootViewController
    }
}

extension AdMobBridge: FullScreenContentDelegate {
    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        if ad is InterstitialAd {
            interstitial = nil
            pendingInterstitialFinish?()
            pendingInterstitialFinish = nil
            loadInterstitialIfNeeded()
        } else if ad is RewardedAd {
            rewarded = nil
            pendingRewardedFinish?(rewardEarned ? .earned : .dismissed)
            pendingRewardedFinish = nil
            loadRewardedIfNeeded()
        }
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Self.log.error("Fallo al presentar el anuncio: \(error.localizedDescription, privacy: .public)")
        if ad is InterstitialAd {
            interstitial = nil
            pendingInterstitialFinish?()
            pendingInterstitialFinish = nil
            loadInterstitialIfNeeded()
        } else if ad is RewardedAd {
            rewarded = nil
            pendingRewardedFinish?(.unavailable)
            pendingRewardedFinish = nil
            loadRewardedIfNeeded()
        }
    }
}
