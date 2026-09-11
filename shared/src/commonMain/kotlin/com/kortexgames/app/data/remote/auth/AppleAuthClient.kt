package com.kortexgames.app.data.remote.auth

import com.kortexgames.app.core.audio.PlatformContext

/**
 * Credencial de Apple obtenida **nativamente**: el ID token (JWT firmado por Apple)
 * y el nonce en crudo con el que se pidió. Supabase la canjea por una sesión propia
 * vía `signInWith(IDToken)`, exactamente igual que [GoogleIdCredential].
 *
 * @property idToken JWT de Apple a enviar a Supabase.
 * @property rawNonce nonce **sin hashear**. Al pedir el token se le manda a Apple su
 *   SHA-256; Apple incrusta ese hash en el JWT y Supabase comprueba que corresponde
 *   a este valor. Es lo que impide que un token robado se reutilice en otra sesión,
 *   así que aquí no es opcional (a diferencia de Google, donde PKCE cumple ese papel).
 */
data class AppleIdCredential(
    val idToken: String,
    val rawNonce: String,
)

/**
 * Frontera `expect/actual` del **login con Apple** ("Sign in with Apple").
 *
 * ## Por qué existe
 *
 * No es una preferencia estética: la **guideline 4.8 de App Store Review** obliga a
 * que toda app que ofrezca un login de terceros —el de Google, en nuestro caso—
 * ofrezca además una alternativa equivalente que limite los datos a nombre y email,
 * permita ocultar el email real y no rastree. Sign in with Apple es la que cumple
 * los tres. Sin esto, el envío a la App Store se arriesga a un rechazo por 4.8.
 *
 * Como efecto secundario deseable, es el método con mejor conversión en iOS: un
 * toque con Face ID, sin teclear email ni contraseña.
 *
 * ## Alcance por plataforma
 *
 * Solo iOS tiene implementación real. El `actual` de Android devuelve un fallo
 * controlado ([AppleSignInUnavailableException]) en vez de no existir, para que
 * `AuthRepository` siga siendo **un único contrato común** y no haya que ramificar
 * el dominio por plataforma. La UI no llega a llamarlo nunca porque
 * [supportsAppleSignIn] esconde el botón, pero el fallo controlado es la red de
 * seguridad si algún día se llama por error.
 *
 * El contrato es deliberadamente delgado (solo obtener el token): el canje por
 * sesión de Supabase vive en `AuthRepositoryImpl`, común a ambas plataformas — la
 * misma división que [GoogleAuthClient].
 */
expect class AppleAuthClient(context: PlatformContext) {

    /**
     * Lanza la hoja nativa de Sign in with Apple y devuelve la credencial.
     *
     * @return [Result] con la [AppleIdCredential], o un fallo: la excepción del
     *   sistema si el usuario cancela o hay error de red, o
     *   [AppleSignInUnavailableException] en plataformas sin soporte.
     */
    suspend fun requestIdToken(): Result<AppleIdCredential>
}

/**
 * `true` solo donde el sistema ofrece Sign in with Apple (iOS).
 *
 * La UI lo consulta para decidir si pinta el botón. En Android no se muestra: allí
 * la guideline 4.8 no aplica y un botón de Apple sería ruido —además de no poder
 * funcionar sin el flujo web, que no vale la pena para el único caso en que ayudaría
 * (entrar en Android a una cuenta creada con Apple desde el iPhone).
 */
expect val supportsAppleSignIn: Boolean

/**
 * El login con Apple no está disponible en esta plataforma o aún no está cableado.
 * Es un fallo *esperado*, no un crash: la UI lo traduce a un mensaje amable y deja
 * el email como alternativa. Mismo papel que [GoogleSignInUnavailableException].
 */
class AppleSignInUnavailableException(
    message: String,
) : Exception(message)
