package com.example.kortexgames.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android: delega en el `BackHandler` real de `activity-compose` (botón/gesto atrás). */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
