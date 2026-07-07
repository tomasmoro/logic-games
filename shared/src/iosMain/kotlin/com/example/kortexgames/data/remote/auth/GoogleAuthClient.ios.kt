package com.example.kortexgames.data.remote.auth

import com.example.kortexgames.core.audio.PlatformContext

/**
 * `actual` de iOS del login con Google.
 *
 * Implementación real pendiente (seam documentado). Los pasos para cablearlo:
 *  1. Añadir el pod `GoogleSignIn` al proyecto iOS (`iosApp`) y exponerlo al
 *     framework `Shared` (cinterop) o resolverlo desde Swift y pasar el token.
 *  2. Configurar el `GIDClientID` en `Info.plist` y registrar el **Web Client ID**
 *     de Google como proveedor en Supabase.
 *  3. Presentar `GIDSignIn.sharedInstance.signIn(...)` desde el rootViewController,
 *     leer `result.user.idToken` y devolverlo con el nonce en crudo.
 *
 * De momento devuelve un fallo controlado para no romper la compilación del
 * framework iOS mientras el email/contraseña ya funciona en ambas plataformas.
 */
actual class GoogleAuthClient actual constructor(
    private val context: PlatformContext,
) {
    actual suspend fun requestIdToken(): Result<GoogleIdCredential> =
        Result.failure(
            GoogleSignInUnavailableException(
                "El inicio de sesión con Google en iOS aún no está configurado " +
                    "(falta el pod GoogleSignIn + Web Client ID). Usa email por ahora.",
            ),
        )
}
