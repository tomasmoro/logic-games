package com.kortexgames.app.data.remote.auth

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.data.remote.SupabaseConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * `actual` de Android del login con Google, con **Credential Manager** + Google
 * Identity Services.
 *
 * Flujo:
 *  1. Genera un `rawNonce` aleatorio y su hash SHA-256. El **hash** se envía a
 *     Google (va en el claim `nonce` del ID token) y el **crudo** a Supabase, que
 *     vuelve a hashearlo y lo compara — así el token no se puede reutilizar
 *     (protección anti-replay; es el patrón que documenta Supabase).
 *  2. Pide la credencial con [CredentialManager]; requiere una Activity en primer
 *     plano ([CurrentActivityHolder]) para mostrar el selector de cuentas.
 *  3. Extrae el ID token de la [GoogleIdTokenCredential] y lo devuelve con el nonce
 *     crudo para que el repositorio lo canjee por sesión de Supabase.
 *
 * Falla de forma controlada ([GoogleSignInUnavailableException]) si no está
 * configurado el Web Client ID o no hay Activity, para que la UI ofrezca email.
 */
actual class GoogleAuthClient actual constructor(
    context: PlatformContext,
) {
    actual suspend fun requestIdToken(): Result<GoogleIdCredential> {
        val webClientId = SupabaseConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) {
            return Result.failure(
                GoogleSignInUnavailableException(
                    "Falta SupabaseConfig.GOOGLE_WEB_CLIENT_ID. Configúralo para habilitar Google.",
                ),
            )
        }
        val activity = CurrentActivityHolder.activity
            ?: return Result.failure(
                GoogleSignInUnavailableException("No hay una pantalla activa para mostrar el selector de Google."),
            )

        return runCatching {
            val rawNonce = generateRawNonce()
            val googleIdOption = GetGoogleIdOption.Builder()
                // false → muestra TODAS las cuentas de Google del dispositivo, no
                // solo las que ya autorizaron la app (mejor para el primer login).
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setNonce(sha256(rawNonce))
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = CredentialManager.create(activity).getCredential(activity, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleIdCredential(idToken = googleCredential.idToken, rawNonce = rawNonce)
            } else {
                throw GoogleSignInUnavailableException(
                    "Credential Manager devolvió una credencial inesperada (${credential.type}).",
                )
            }
        }
    }

    /** Nonce aleatorio (32 bytes en hex) usado una sola vez por intento de login. */
    private fun generateRawNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /** SHA-256 en hex del nonce crudo (lo que se envía a Google). */
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
