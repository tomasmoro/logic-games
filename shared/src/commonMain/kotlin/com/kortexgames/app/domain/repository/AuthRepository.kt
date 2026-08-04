package com.kortexgames.app.domain.repository

import com.kortexgames.app.domain.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Puerta de entrada a la autenticación. Encapsula Supabase Auth para que la UI y
 * el resto de la app dependan de un contrato de dominio (y no del SDK).
 *
 * La **fuente de verdad de la sesión** es [sessionState]: un flujo reactivo que
 * emite [AuthState.Guest] mientras no haya sesión y [AuthState.Authenticated] en
 * cuanto Supabase establece (o restaura) una. El [com.kortexgames.app.di.AppGraph]
 * lo observa para sincronizar el progreso pendiente al iniciar sesión.
 *
 * Todos los métodos de acción devuelven [Result] para que la capa de UI decida el
 * mensaje/feedback sin manejar excepciones del SDK directamente.
 */
interface AuthRepository {

    /** Estado de sesión reactivo (invitado / autenticado). */
    val sessionState: StateFlow<AuthState>

    /**
     * true cuando la sesión ya está **resuelta**: se restauró la guardada en disco
     * o se confirmó que no hay ninguna.
     *
     * Existe porque [sessionState] arranca en [AuthState.Guest] y no distingue
     * "invitado" de "todavía cargando", así que la UI que depende de la sesión
     * (saludo, banner de "inicia sesión") parpadearía al restaurarse una cuenta.
     * El arranque lo espera antes de mostrar la Home.
     */
    val sessionResolved: StateFlow<Boolean>

    /** Inicia sesión con email y contraseña ya existentes. */
    suspend fun signInWithEmail(email: String, password: String): Result<Unit>

    /**
     * Crea una cuenta nueva con email y contraseña.
     *
     * @param displayName nombre de jugador elegido en el formulario. Viaja como
     *   metadato del alta para que el trigger `handle_new_user` lo escriba en
     *   `public.users.display_name` **en la misma transacción** que crea el
     *   perfil. Hacerlo con un `update` posterior desde el cliente dejaría
     *   perfiles sin nombre si la app se cierra o pierde red justo después.
     */
    suspend fun signUpWithEmail(
        email: String,
        password: String,
        displayName: String,
    ): Result<Unit>

    /**
     * Inicia sesión con Google. Obtiene el ID token nativo (seam de plataforma) y
     * lo canjea por una sesión de Supabase. Falla de forma controlada si el flujo
     * nativo aún no está disponible en el dispositivo.
     */
    suspend fun signInWithGoogle(): Result<Unit>

    /** Cierra la sesión actual (vuelve a modo invitado). */
    suspend fun signOut()

    /**
     * Cambia el nombre de usuario (`public.users.display_name`) del usuario
     * autenticado. Falla con [IllegalStateException] si se llama en modo invitado.
     */
    suspend fun updateDisplayName(name: String): Result<Unit>

    /**
     * Borra la cuenta de forma **permanente e irreversible**: delega en la Edge
     * Function `delete-account` (necesita `service_role` para borrar de
     * `auth.users`, algo que el cliente nunca debe tener). El borrado en cascada
     * de `auth.users` se lleva por delante `public.users` y todo lo que referencia
     * su `id` (progreso, logros, rachas…). Al terminar cierra la sesión local.
     */
    suspend fun deleteAccount(): Result<Unit>
}
