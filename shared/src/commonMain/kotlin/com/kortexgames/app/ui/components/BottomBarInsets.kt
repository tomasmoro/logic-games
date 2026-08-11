package com.kortexgames.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * Inset de sistema que [com.kortexgames.app.ui.navigation.AnimatedBottomBar] reserva
 * ENCIMA de su propio padding fijo, para no quedar tapado por la barra/gesto de
 * navegación del sistema.
 *
 * Se declara `expect`/`actual` porque el concepto no es simétrico entre plataformas
 * (ver KDoc de cada `actual`): Android tiene una barra de navegación real que puede
 * quedar visible u oculta; iOS solo tiene el *home indicator*, que no reserva layout.
 * Sin esta frontera, `WindowInsets.navigationBars` se reutilizaría tal cual en ambas
 * plataformas y en iOS metería un hueco extra bajo la barra (ver el porqué en el
 * `actual` de iOS).
 */
@Composable
expect fun bottomBarSystemInsets(): WindowInsets
