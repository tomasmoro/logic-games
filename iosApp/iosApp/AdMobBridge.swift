import AppTrackingTransparency
import Foundation
import GoogleMobileAds
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

    private var interstitial: InterstitialAd?
    private var rewarded: RewardedAd?
    private var pendingInterstitialFinish: (() -> Void)?
    private var pendingRewardedFinish: ((RewardResult) -> Void)?
    private var rewardEarned = false

    private override init() {
        super.init()
    }

    // MARK: - Arranque + consentimiento (equivalente a AdConsentManager en Android)

    /// Pide permiso de seguimiento (ATT) y consentimiento GDPR/UMP y, en cuanto se
    /// pueden pedir anuncios, arranca el SDK. Llamar **una vez** al lanzar la app,
    /// antes de que `MainViewController()` construya el `AppGraph`.
    func requestConsentAndStart() {
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
        let parameters = RequestParameters()
        // Para forzar el formulario en pruebas fuera de la UE: añadir aquí un
        // DebugSettings con geography = .EEA y el device id (sale en consola al
        // pedir el consentimiento). No se fija en código porque es por-dispositivo.
        ConsentInformation.shared.requestConsentInfoUpdate(
            with: parameters,
            completionHandler: { [weak self] error in
                guard let self else { return }
                if error != nil {
                    // Sin red u otro fallo: seguimos si una sesión previa ya dejó consentimiento.
                    if ConsentInformation.shared.canRequestAds { self.startSdkOnce() }
                    return
                }
                Task { @MainActor in
                    if let root = Self.rootViewController() {
                        try? await ConsentForm.loadAndPresentIfRequired(from: root)
                    }
                    if ConsentInformation.shared.canRequestAds { self.startSdkOnce() }
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
        MobileAds.shared.start()
        Task { await preloadInterstitial() }
        Task { await preloadRewarded() }
    }

    // MARK: - IosAdBridge (llamado desde Kotlin vía Bridged*AdPresenter)

    func showInterstitial(onFinished: @escaping () -> Void) {
        guard sdkStarted, let ad = interstitial, let root = Self.rootViewController() else {
            onFinished()
            return
        }
        pendingInterstitialFinish = onFinished
        ad.fullScreenContentDelegate = self
        ad.present(from: root)
    }

    func showRewarded(onFinished: @escaping (RewardResult) -> Void) {
        guard sdkStarted, let ad = rewarded, let root = Self.rootViewController() else {
            onFinished(.unavailable)
            return
        }
        pendingRewardedFinish = onFinished
        rewardEarned = false
        ad.fullScreenContentDelegate = self
        ad.present(from: root) { [weak self] in
            self?.rewardEarned = true
        }
    }

    // MARK: - Carga (precarga básica: siguiente anuncio tras cada cierre; sin
    // reintento/backoff — afinar es trabajo de BACKLOG "+ precarga").

    private func preloadInterstitial() async {
        interstitial = try? await InterstitialAd.load(with: interstitialUnitId, request: Request())
    }

    private func preloadRewarded() async {
        rewarded = try? await RewardedAd.load(with: rewardedUnitId, request: Request())
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
            Task { await preloadInterstitial() }
        } else if ad is RewardedAd {
            rewarded = nil
            pendingRewardedFinish?(rewardEarned ? .earned : .dismissed)
            pendingRewardedFinish = nil
            Task { await preloadRewarded() }
        }
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        if ad is InterstitialAd {
            interstitial = nil
            pendingInterstitialFinish?()
            pendingInterstitialFinish = nil
        } else if ad is RewardedAd {
            rewarded = nil
            pendingRewardedFinish?(.unavailable)
            pendingRewardedFinish = nil
        }
    }
}
