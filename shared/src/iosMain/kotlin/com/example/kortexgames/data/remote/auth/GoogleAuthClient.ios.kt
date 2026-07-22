package com.example.kortexgames.data.remote.auth

import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.data.remote.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * `actual` de iOS del login con Google, **sin el SDK GoogleSignIn**: usa el flujo
 * OAuth 2.0 *Authorization Code + PKCE* sobre [ASWebAuthenticationSession] (el
 * navegador seguro del sistema) y canjea el código por un **ID token de Google**.
 *
 * Por qué este enfoque y no el pod `GoogleSignIn`:
 *  - No añade dependencias de CocoaPods/SPM ni el plugin cocoapods a Gradle: el
 *    framework `Shared` sigue siendo estático y autónomo.
 *  - `ASWebAuthenticationSession` **intercepta el esquema de callback por sí mismo**
 *    (parámetro `callbackURLScheme`), así que NO hace falta registrar el URL scheme
 *    en `Info.plist` para que el flujo funcione.
 *  - Devuelve el mismo contrato que Android: un [GoogleIdCredential] con un ID token
 *    que [com.example.kortexgames.data.repository.AuthRepositoryImpl] canjea por
 *    sesión de Supabase vía `signInWith(IDToken)`. El flujo común no conoce nada de
 *    esto.
 *
 * PKCE (RFC 7636) sustituye al *client secret* (que una app instalada no puede
 * guardar en secreto): mandamos `code_challenge` al pedir el código y el
 * `code_verifier` en crudo al canjearlo. La aleatoriedad segura la da
 * `SecRandomCopyBytes`; el resto de la lógica (reto, base64url, URL) vive en
 * [GoogleOAuth] (puro y testeado).
 *
 * Nonce: este flujo NO usa nonce (se pasa `rawNonce = null` a Supabase). PKCE ya
 * ata el código a este cliente, y el ID token viaja por el canal servidor→servidor
 * del token endpoint (no por la redirección), así que no hay token expuesto que
 * reproducir. El nonce del patrón Android existe porque allí Google Identity
 * devuelve el token directamente al cliente; aquí no aplica.
 *
 * Requisitos de configuración (una sola vez, por el usuario):
 *  1. Google Cloud → Credentials → crear un **OAuth client ID de tipo iOS** con el
 *     *bundle id* de la app y pegarlo en [SupabaseConfig.GOOGLE_IOS_CLIENT_ID].
 *  2. Supabase → Auth → Providers → Google → añadir ese client id de iOS a
 *     *Authorized Client IDs* (GoTrue valida el `aud` del ID token contra esa lista).
 *
 * Falla de forma controlada ([GoogleSignInUnavailableException]) si falta el client
 * id o el usuario cancela, para que la UI ofrezca email como alternativa.
 */
@OptIn(ExperimentalForeignApi::class)
actual class GoogleAuthClient actual constructor(
    private val context: PlatformContext,
) {
    /**
     * `ASWebAuthenticationSession` guarda su `presentationContextProvider` de forma
     * **débil**, así que lo retenemos aquí para que no se libere a mitad del flujo.
     */
    private val presentationProvider = PresentationContextProvider()

    actual suspend fun requestIdToken(): Result<GoogleIdCredential> {
        val clientId = SupabaseConfig.GOOGLE_IOS_CLIENT_ID
        if (clientId.isBlank()) {
            return Result.failure(
                GoogleSignInUnavailableException(
                    "Falta SupabaseConfig.GOOGLE_IOS_CLIENT_ID (OAuth client id de tipo iOS). " +
                        "Configúralo para habilitar Google en iOS.",
                ),
            )
        }

        return runCatching {
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = GoogleOAuth.pkceChallenge(codeVerifier)
            val redirectScheme = GoogleOAuth.reversedClientScheme(clientId)
            val redirectUri = "$redirectScheme${GoogleOAuth.REDIRECT_PATH}"
            val authUrl = GoogleOAuth.authorizationUrl(clientId, redirectUri, codeChallenge)

            val callbackUrl = presentAuthSession(authUrl, redirectScheme)

            // Google puede volver con ?error=access_denied si el usuario cancela.
            GoogleOAuth.queryParam(callbackUrl, "error")?.let { error ->
                throw GoogleSignInUnavailableException("Google rechazó el inicio de sesión: $error")
            }
            val code = GoogleOAuth.queryParam(callbackUrl, "code")
                ?: throw GoogleSignInUnavailableException("Google no devolvió un código de autorización.")

            val idToken = exchangeCodeForIdToken(code, codeVerifier, clientId, redirectUri)
            GoogleIdCredential(idToken = idToken, rawNonce = null)
        }
    }

    /**
     * Presenta el navegador seguro y suspende hasta que el usuario completa o
     * cancela. `ASWebAuthenticationSession.start()` debe llamarse en el hilo
     * principal, de ahí el [withContext] a [Dispatchers.Main].
     *
     * @return la URL de callback (`<esquema>:/oauth2redirect?code=...`).
     * @throws GoogleSignInUnavailableException si el usuario cancela o el sistema
     *   no puede iniciar la sesión.
     */
    private suspend fun presentAuthSession(authUrl: String, callbackScheme: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val session = ASWebAuthenticationSession(
                    uRL = NSURL(string = authUrl),
                    callbackURLScheme = callbackScheme,
                ) { callback: NSURL?, error: NSError? ->
                    when {
                        callback != null ->
                            continuation.resume(callback.absoluteString ?: "")
                        else -> continuation.resumeWith(
                            Result.failure(
                                GoogleSignInUnavailableException(
                                    "El usuario canceló o el navegador falló" +
                                        (error?.localizedDescription?.let { ": $it" } ?: "."),
                                ),
                            ),
                        )
                    }
                }
                session.presentationContextProvider = presentationProvider
                // Sin cookies persistentes: cada login parte de cero (evita que se
                // "recuerde" una cuenta y no deje elegir otra).
                session.prefersEphemeralWebBrowserSession = true

                continuation.invokeOnCancellation { session.cancel() }

                if (!session.start()) {
                    continuation.resumeWith(
                        Result.failure(
                            GoogleSignInUnavailableException(
                                "No se pudo iniciar la sesión de autenticación de Google.",
                            ),
                        ),
                    )
                }
            }
        }

    /**
     * Canjea el código de autorización por un ID token en el *token endpoint* de
     * Google (POST servidor→servidor con el `code_verifier` de PKCE; sin secreto).
     *
     * @throws GoogleSignInUnavailableException si la respuesta no trae `id_token`.
     */
    private suspend fun exchangeCodeForIdToken(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String,
    ): String {
        val client = HttpClient(Darwin)
        try {
            val responseBody = client.submitForm(
                url = GoogleOAuth.TOKEN_ENDPOINT,
                formParameters = Parameters.build {
                    append("code", code)
                    append("client_id", clientId)
                    append("redirect_uri", redirectUri)
                    append("grant_type", "authorization_code")
                    append("code_verifier", codeVerifier)
                },
            ).bodyAsText()

            val json = Json.parseToJsonElement(responseBody).jsonObject
            return json["id_token"]?.jsonPrimitive?.content
                ?: throw GoogleSignInUnavailableException(
                    "El token endpoint de Google no devolvió id_token " +
                        "(${json["error"]?.jsonPrimitive?.content ?: "respuesta inesperada"}).",
                )
        } finally {
            client.close()
        }
    }

    /**
     * `code_verifier` de PKCE: 32 bytes de aleatoriedad segura del sistema
     * (`SecRandomCopyBytes`) codificados en base64url → 43 caracteres, dentro del
     * rango [43, 128] que exige la RFC 7636.
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        bytes.usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0))
            check(status == 0) { "SecRandomCopyBytes falló (status=$status)" }
        }
        return GoogleOAuth.base64UrlNoPadding(bytes)
    }
}

/**
 * Proveedor del *anchor* (ventana) sobre el que `ASWebAuthenticationSession` monta
 * su vista. Devuelve la key window actual de la app.
 */
@OptIn(ExperimentalForeignApi::class)
private class PresentationContextProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): ASPresentationAnchor =
        UIApplication.sharedApplication.keyWindow ?: UIWindow()
}
