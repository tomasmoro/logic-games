package com.kortexgames.app.data.remote.auth

import com.kortexgames.app.core.audio.PlatformContext

/**
 * `actual` de Android del login con Apple: **no disponible a propósito**.
 *
 * Sign in with Apple existe en la app por la guideline 4.8 de App Store Review (ver
 * el KDoc del `expect` [AppleAuthClient]), que solo rige en iOS. En Android el botón
 * ni se pinta, porque [supportsAppleSignIn] es `false`.
 *
 * Apple sí publica un flujo web que permitiría autenticarse desde Android, pero no
 * se implementa: el único caso que cubriría es entrar desde un móvil Android a una
 * cuenta creada con Apple en un iPhone, y para eso el usuario tiene el email —Apple
 * entrega uno, real o de reenvío privado, y el trigger `handle_new_user` lo guarda
 * igual que con cualquier alta. Añadir un WebView de OAuth por ese caso sería más
 * superficie de la que ahorra.
 *
 * Devuelve un fallo controlado en vez de lanzar: así `AuthRepository` mantiene un
 * único contrato común y una llamada por error degrada en un mensaje, no en un crash.
 */
actual class AppleAuthClient actual constructor(
    @Suppress("UNUSED_PARAMETER") context: PlatformContext,
) {

    actual suspend fun requestIdToken(): Result<AppleIdCredential> =
        Result.failure(
            AppleSignInUnavailableException(
                "Sign in with Apple no está disponible en Android; usa Google o email.",
            ),
        )
}

/** Ver el KDoc del `expect`: en Android el botón de Apple no se muestra. */
actual val supportsAppleSignIn: Boolean = false
