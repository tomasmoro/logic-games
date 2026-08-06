package com.kortexgames.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable

/**
 * Android: el inset real de la barra de navegación (atrás/home/recientes). Con
 * [ImmersiveMode] activo se oculta y este inset pasa a 0, así que en la práctica ya
 * no reserva hueco propio; se deja el inset real (en vez de fijarlo a 0 a mano) para
 * que la barra siga colocándose bien si algún día [ImmersiveMode] deja de ocultarla.
 */
@Composable
actual fun bottomBarSystemInsets(): WindowInsets = WindowInsets.navigationBars
