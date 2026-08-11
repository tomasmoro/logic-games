package com.kortexgames.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * iOS: `WindowInsets.navigationBars` de Compose Multiplatform mapea al *home
 * indicator* (no hay barra de navegación real), así que reutilizarlo aquí metía un
 * hueco extra (~34dp) bajo los iconos/etiquetas, sumado al `bottom = 12.dp` propio de
 * la barra — se veía como un margen inferior que no existe en Android (donde
 * [ImmersiveMode] oculta la barra de navegación y ese inset ya vale 0). Se fija a
 * cero para que la barra quede igual de ceñida al borde en ambas plataformas; el
 * `bottom = 12.dp` fijo ya deja aire suficiente para el gesto de inicio.
 */
@Composable
actual fun bottomBarSystemInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)
