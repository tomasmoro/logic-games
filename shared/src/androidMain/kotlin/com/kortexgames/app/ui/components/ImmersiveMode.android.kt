package com.kortexgames.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android: oculta la **barra de navegación** (atrás / home / recientes), dejando
 * visible la de estado (hora y batería siguen siendo útiles y ocultarlas se sentiría
 * más agresivo de lo que la app necesita).
 *
 * Decisiones:
 *  - `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`: la barra vuelve **temporalmente** con un
 *    deslizamiento desde el borde y se esconde sola. Es el único comportamiento que
 *    Google admite para apps no-kiosco: el usuario nunca queda atrapado sin navegación.
 *  - No tocamos `enableEdgeToEdge` (ya activo en `MainActivity`): al ocultarse la barra
 *    sus insets pasan a 0 y el `Scaffold` reparte ese espacio al contenido.
 *  - `onDispose` restaura siempre las barras: cubre tanto un cambio de `enabled` como
 *    que la Activity se destruya con el modo inmersivo puesto.
 */
@Composable
actual fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    // En previews no hay Activity ni ventana real que controlar.
    if (view.isInEditMode) return
    val window = view.context.findActivity()?.window
    DisposableEffect(window, enabled) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.navigationBars()) }
    }
}

/**
 * Desenvuelve el [Context] de Compose hasta la [Activity] que lo hospeda. El contexto
 * de la vista suele venir envuelto (temas, `ContextWrapper` de AppCompat), así que un
 * `as? Activity` directo fallaría.
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
