package com.kortexgames.app.data.remote.auth

import com.kortexgames.app.core.audio.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * `actual` de iOS del login con Apple, en Kotlin/Native puro.
 *
 * Usa `AuthenticationServices` del sistema —el mismo framework que ya emplea
 * [GoogleAuthClient] para su `ASWebAuthenticationSession`—, así que no añade
 * dependencias: ni CocoaPods, ni SPM, ni puente Swift. El framework `Shared` sigue
 * siendo estático y autónomo.
 *
 * ## El nonce, y por qué aquí no es opcional
 *
 * Apple devuelve el ID token **directamente al cliente**, así que un token
 * interceptado podría reproducirse para suplantar la sesión. El nonce lo impide:
 *  1. Se genera un valor aleatorio (`rawNonce`).
 *  2. A Apple se le manda su **SHA-256 en hexadecimal**, que es lo que Apple
 *     incrusta en el JWT.
 *  3. A Supabase se le pasa el `rawNonce` **en crudo**; Supabase lo hashea y
 *     comprueba que coincide con el del token. Un token robado no sirve sin él.
 *
 * Contrasta con el flujo de Google de esta misma app, que no usa nonce porque PKCE
 * ya cumple ese papel y el token allí viaja por el canal servidor→servidor.
 *
 * ## Requisitos de configuración (una sola vez, fuera del código)
 *  1. Apple Developer → Identifiers → el App ID → capability **Sign In with Apple**.
 *  2. Xcode → target `iosApp` → Signing & Capabilities → **+ Sign in with Apple**
 *     (añade el entitlement; sin él la hoja no llega a abrirse).
 *  3. Supabase → Auth → Providers → **Apple** → habilitar y añadir el *bundle id*
 *     a *Authorized Client IDs*, para que GoTrue acepte el `aud` del token. Es el
 *     mismo patrón que el client id de iOS de Google.
 */
actual class AppleAuthClient actual constructor(
    @Suppress("UNUSED_PARAMETER") context: PlatformContext,
) {

    /**
     * Mantiene vivo el delegado mientras dura la petición.
     *
     * `ASAuthorizationController` guarda `delegate` y `presentationContextProvider`
     * como referencias **débiles**: si el delegado solo viviera en la variable local
     * de [requestIdToken], podría recolectarse en cuanto la corrutina se suspende y
     * los callbacks no llegarían nunca (la hoja se abriría y la app se quedaría
     * colgada esperando para siempre). Este campo es esa ancla.
     */
    private var delegadoEnCurso: AppleAuthDelegate? = null

    /**
     * Presenta la hoja nativa de Sign in with Apple y espera el resultado.
     *
     * Va en [Dispatchers.Main] porque `performRequests()` presenta interfaz: llamarlo
     * desde otro hilo es comportamiento indefinido en UIKit.
     */
    actual suspend fun requestIdToken(): Result<AppleIdCredential> = withContext(Dispatchers.Main) {
        val rawNonce = generarNonce()
        // Apple espera el hash en hexadecimal (no en base64url como el reto PKCE).
        val nonceHasheado = aHex(GoogleOAuth.sha256(rawNonce.encodeToByteArray()))

        try {
            val idToken = suspendCancellableCoroutine { continuation ->
                val peticion = ASAuthorizationAppleIDProvider().createRequest().apply {
                    // Se piden nombre y email, pero OJO: Apple los entrega SOLO en el
                    // primer alta de cada usuario. En logins posteriores llegan a null,
                    // así que no se pueden usar como fuente de verdad — el perfil lo
                    // crea el trigger `handle_new_user` a partir del token.
                    setRequestedScopes(listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail))
                    setNonce(nonceHasheado)
                }

                val delegado = AppleAuthDelegate { resultado ->
                    if (continuation.isActive) {
                        resultado
                            .onSuccess { continuation.resume(it) }
                            .onFailure { continuation.cancel(it) }
                    }
                }
                delegadoEnCurso = delegado

                ASAuthorizationController(authorizationRequests = listOf(peticion)).apply {
                    setDelegate(delegado)
                    setPresentationContextProvider(delegado)
                    performRequests()
                }
            }
            Result.success(AppleIdCredential(idToken = idToken, rawNonce = rawNonce))
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            delegadoEnCurso = null
        }
    }

    /**
     * 32 bytes criptográficamente aleatorios (`SecRandomCopyBytes`) en base64url.
     * Mismo generador que el `code_verifier` de PKCE en [GoogleAuthClient]: si la
     * fuente de aleatoriedad fuera débil, el nonce no protegería de nada.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun generarNonce(): String {
        val bytes = ByteArray(32)
        bytes.usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0))
            check(status == 0) { "SecRandomCopyBytes falló (status=$status)" }
        }
        return GoogleOAuth.base64UrlNoPadding(bytes)
    }

    /** Hexadecimal en minúsculas, que es el formato en el que Apple espera el nonce. */
    private fun aHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}

/**
 * Delegado de `ASAuthorizationController`: recibe el desenlace de la hoja de Apple
 * y además le dice sobre qué ventana montarse.
 *
 * Implementa los dos protocolos en una sola clase porque su ciclo de vida es el
 * mismo (una petición) y así hay una única referencia que mantener viva.
 *
 * @param alTerminar se invoca **exactamente una vez** con el ID token o el fallo.
 */
@OptIn(ExperimentalForeignApi::class)
private class AppleAuthDelegate(
    private val alTerminar: (Result<String>) -> Unit,
) : NSObject(),
    ASAuthorizationControllerDelegateProtocol,
    ASAuthorizationControllerPresentationContextProvidingProtocol {

    override fun authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization: ASAuthorization,
    ) {
        val credencial = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
        val token = credencial?.identityToken?.let { datos ->
            NSString.create(data = datos, encoding = NSUTF8StringEncoding)?.toString()
        }

        if (token.isNullOrBlank()) {
            // No debería ocurrir con la petición bien formada, pero si Apple devuelve
            // una credencial sin token no hay nada que canjear: mejor un fallo claro
            // que un JWT vacío rebotando en Supabase con un error críptico.
            alTerminar(
                Result.failure(
                    AppleSignInUnavailableException("Apple no devolvió identityToken"),
                ),
            )
        } else {
            alTerminar(Result.success(token))
        }
    }

    override fun authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError: NSError,
    ) {
        // Incluye la cancelación del usuario (ASAuthorizationError.canceled). No se
        // distingue aquí a propósito: el ViewModel ya trata cualquier fallo de login
        // social con el mismo mensaje genérico.
        alTerminar(
            Result.failure(
                AppleSignInUnavailableException(
                    didCompleteWithError.localizedDescription,
                ),
            ),
        )
    }

    override fun presentationAnchorForAuthorizationController(
        controller: ASAuthorizationController,
    ): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow ?: UIWindow()
}

/** Ver el KDoc del `expect`: en iOS es donde la guideline 4.8 obliga a ofrecerlo. */
actual val supportsAppleSignIn: Boolean = true
