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

    /**
     * Inicia sesión con Apple. Mismo esquema que [signInWithGoogle] —token nativo
     * canjeado por sesión de Supabase—, pero el nonce aquí es obligatorio: Apple
     * entrega el token al cliente, así que sin nonce sería reproducible.
     *
     * Solo tiene implementación real en iOS, donde la guideline 4.8 de App Store
     * Review lo exige por ofrecer también el login de Google. En Android falla de
     * forma controlada; la UI ni siquiera muestra el botón (ver
     * `com.kortexgames.app.data.remote.auth.supportsAppleSignIn`).
     */
    suspend fun signInWithApple(): Result<Unit>

    /** Cierra la sesión actual (vuelve a modo invitado). */
    suspend fun signOut()

    /**
     * Fija (o cambia) el nombre del jugador autenticado en
     * `public.users.display_name`.
     *
     * Es **un único dato**: el saludo dentro de la app y lo que ven los demás en el
     * ranking mundial (migración 0048). No hay identidad pública separada.
     *
     * Escribe a través de la RPC `set_display_name`, que valida longitud, forma y
     * blocklist en el servidor. Desde la migración 0049 no hay alternativa: el
     * cliente ya no tiene permiso de UPDATE sobre `public.users`, precisamente para
     * que esa validación no se pueda esquivar.
     *
     * Falla siempre con [com.kortexgames.app.domain.model.DisplayNameRejectedException],
     * cuyo `reason` distingue nombre rechazado de fallo de red o falta de sesión,
     * para que la UI elija el mensaje sin interpretar texto.
     */
    suspend fun updateDisplayName(name: String): Result<Unit>

    /**
     * Lee el `display_name` actual del perfil **directamente de la BD**, no del
     * [sessionState] cacheado. Se usa justo tras un alta con Google para decidir si
     * hay que pedir el nombre en el onboarding: el perfil recién creado por el
     * trigger `handle_new_user` puede no haberse propagado todavía al flujo.
     *
     * `null` si no hay sesión, si la fila aún no existe o si la lectura falla.
     */
    suspend fun currentDisplayName(): String?

    /**
     * Borra la cuenta de forma **permanente e irreversible**: delega en la Edge
     * Function `delete-account` (necesita `service_role` para borrar de
     * `auth.users`, algo que el cliente nunca debe tener). El borrado en cascada
     * de `auth.users` se lleva por delante `public.users` y todo lo que referencia
     * su `id` (progreso, logros, rachas…). Al terminar cierra la sesión local.
     */
    suspend fun deleteAccount(): Result<Unit>
}
