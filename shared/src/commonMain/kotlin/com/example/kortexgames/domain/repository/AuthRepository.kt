package com.example.kortexgames.domain.repository

import com.example.kortexgames.domain.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Puerta de entrada a la autenticación. Encapsula Supabase Auth para que la UI y
 * el resto de la app dependan de un contrato de dominio (y no del SDK).
 *
 * La **fuente de verdad de la sesión** es [sessionState]: un flujo reactivo que
 * emite [AuthState.Guest] mientras no haya sesión y [AuthState.Authenticated] en
 * cuanto Supabase establece (o restaura) una. El [com.example.kortexgames.di.AppGraph]
 * lo observa para sincronizar el progreso pendiente al iniciar sesión.
 *
 * Todos los métodos de acción devuelven [Result] para que la capa de UI decida el
 * mensaje/feedback sin manejar excepciones del SDK directamente.
 */
interface AuthRepository {

    /** Estado de sesión reactivo (invitado / autenticado). */
    val sessionState: StateFlow<AuthState>

    /** Inicia sesión con email y contraseña ya existentes. */
    suspend fun signInWithEmail(email: String, password: String): Result<Unit>

    /** Crea una cuenta nueva con email y contraseña. */
    suspend fun signUpWithEmail(email: String, password: String): Result<Unit>

    /**
     * Inicia sesión con Google. Obtiene el ID token nativo (seam de plataforma) y
     * lo canjea por una sesión de Supabase. Falla de forma controlada si el flujo
     * nativo aún no está disponible en el dispositivo.
     */
    suspend fun signInWithGoogle(): Result<Unit>

    /** Cierra la sesión actual (vuelve a modo invitado). */
    suspend fun signOut()
}
