package com.kortexgames.app.data.remote.auth

import com.kortexgames.app.core.audio.PlatformContext

/**
 * Credencial de Google obtenida **nativamente**: un ID token (JWT firmado por
 * Google) y el nonce en crudo usado al pedirlo. Supabase la canjea por una sesión
 * propia vía `signInWith(IDToken)` — el ID token nunca se genera desde `commonMain`
 * porque depende del SDK de cada plataforma.
 *
 * @property idToken JWT de Google a enviar a Supabase.
 * @property rawNonce nonce sin hashear; Supabase lo valida contra el hash del token.
 */
data class GoogleIdCredential(
    val idToken: String,
    val rawNonce: String?,
)

/**
 * Frontera `expect/actual` del **login con Google**.
 *
 * A diferencia del email/contraseña (100 % en `commonMain` vía Supabase Auth), el
 * flujo de Google exige obtener un ID token con el SDK nativo:
 *  - **Android**: Credential Manager + Google Identity Services (`GetGoogleIdOption`).
 *  - **iOS**: `GoogleSignIn` (pod) presentando el `ASWebAuthenticationSession`.
 *
 * Ambos necesitan además un **Google OAuth Web Client ID** registrado como
 * proveedor en el proyecto Supabase. Mientras ese Client ID no esté configurado,
 * los `actual` devuelven un fallo controlado ([GoogleSignInUnavailableException])
 * para que la UI muestre un mensaje claro sin romper la compilación.
 *
 * El contrato es intencionadamente delgado (solo obtener el token): el canje por
 * sesión de Supabase vive en `AuthRepositoryImpl`, común a ambas plataformas.
 */
expect class GoogleAuthClient(context: PlatformContext) {

    /**
     * Lanza el flujo nativo de Google y devuelve la credencial resultante.
     *
     * @return [Result] con la [GoogleIdCredential] en caso de éxito, o un fallo
     *   ([GoogleSignInUnavailableException] si el flujo aún no está cableado, o la
     *   excepción del SDK si el usuario cancela / hay error de red).
     */
    suspend fun requestIdToken(): Result<GoogleIdCredential>
}

/**
 * El flujo nativo de Google no está disponible (aún sin cablear el SDK/Client ID,
 * o la plataforma no lo soporta). Es un fallo *esperado*, no un crash: la UI lo
 * traduce a un mensaje amable y ofrece el email como alternativa.
 */
class GoogleSignInUnavailableException(
    message: String,
) : Exception(message)
