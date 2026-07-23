package com.example.kortexgames.ui.components

import androidx.compose.runtime.Composable

/**
 * iOS no tiene botón "atrás" de hardware: el gesto de swipe-back ya lo resuelve
 * `NavHost` directamente contra la pila de navegación. No-op intencional (ver KDoc
 * del `expect`).
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
