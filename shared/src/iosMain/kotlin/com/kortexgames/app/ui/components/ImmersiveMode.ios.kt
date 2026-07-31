package com.kortexgames.app.ui.components

import androidx.compose.runtime.Composable

/**
 * iOS no tiene barra de navegación con botones: solo el *home indicator*, una línea
 * fina que el sistema ya atenúa y que no ocupa espacio de layout. Ocultarlo exigiría
 * subclasear el `UIViewController` de Compose (`prefersHomeIndicatorAutoHidden`) para
 * ganar unos píxeles y arriesgar gestos accidentales, así que es un no-op intencional
 * (ver KDoc del `expect`).
 */
@Composable
actual fun ImmersiveMode(enabled: Boolean) {
}
