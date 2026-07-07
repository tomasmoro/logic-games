package com.example.kortexgames.data.repository

import com.example.kortexgames.data.remote.auth.GoogleAuthClient
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.PlanType
import com.example.kortexgames.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Implementación de [AuthRepository] sobre **Supabase Auth**.
 *
 * Decisiones:
 *  - La sesión se deriva de `auth.sessionStatus` (Supabase persiste y refresca el
 *    token por su cuenta), así que al reabrir la app el usuario sigue logueado sin
 *    lógica extra por nuestra parte.
 *  - El plan (`FREE`/`PREMIUM`) no se resuelve aquí para no acoplar el login a la
 *    tabla `public.users`; se asume [PlanType.FREE] y la lógica premium se calcula
 *    donde corresponda. Los invitados y usuarios free ven anuncios igualmente.
 *  - Google delega en [GoogleAuthClient] (seam de plataforma) para obtener el ID
 *    token y aquí solo se canjea por sesión — así el flujo común no conoce SDKs
 *    nativos.
 *
 * @param scope scope de aplicación donde se mantiene "caliente" el [sessionState].
 */
class AuthRepositoryImpl(
    private val client: SupabaseClient,
    private val googleAuthClient: GoogleAuthClient,
    scope: CoroutineScope,
) : AuthRepository {

    override val sessionState: StateFlow<AuthState> =
        client.auth.sessionStatus
            .map { it.toAuthState() }
            .stateIn(scope, SharingStarted.Eagerly, AuthState.Guest)

    override suspend fun signInWithEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signUpWithEmail(email: String, password: String): Result<Unit> =
        // Si el proyecto exige confirmación por correo, `signUpWith` no crea sesión
        // todavía; el usuario queda como invitado hasta confirmar y podrá iniciar
        // sesión con el botón del Home/Perfil. Aun así lo tratamos como éxito.
        runCatching {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
        }.map { }

    override suspend fun signInWithGoogle(): Result<Unit> {
        // 1) Token nativo (o fallo controlado si el seam aún no está cableado).
        val credential = googleAuthClient.requestIdToken().getOrElse { return Result.failure(it) }
        // 2) Canje del ID token por una sesión de Supabase.
        return runCatching {
            client.auth.signInWith(IDToken) {
                idToken = credential.idToken
                provider = Google
                nonce = credential.rawNonce
            }
        }
    }

    override suspend fun signOut() {
        runCatching { client.auth.signOut() }
    }
}

/**
 * Proyecta el estado del SDK de Supabase al modelo de dominio. Solo la sesión
 * autenticada con id de usuario válido cuenta como [AuthState.Authenticated];
 * cualquier otro estado (inicializando, sin sesión, fallo de refresco) se trata
 * como invitado para que la app siempre tenga un estado usable y offline.
 */
private fun SessionStatus.toAuthState(): AuthState = when (this) {
    is SessionStatus.Authenticated -> {
        val userId = session.user?.id
        if (userId != null) AuthState.Authenticated(userId, PlanType.FREE) else AuthState.Guest
    }
    else -> AuthState.Guest
}
