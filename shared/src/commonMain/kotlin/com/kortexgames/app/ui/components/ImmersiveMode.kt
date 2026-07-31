package com.kortexgames.app.ui.components

import androidx.compose.runtime.Composable

/**
 * Modo inmersivo: oculta las barras del sistema para que la app ocupe la pantalla
 * entera.
 *
 * Se declara `expect`/`actual` porque "barra del sistema" no significa lo mismo en
 * cada plataforma (ver el KDoc de cada `actual`): en Android es la barra de
 * navegación (atrás / home / recientes), que roba espacio y se pulsa sin querer al
 * arrastrar cerca del borde inferior de un tablero; en iOS no existe tal barra.
 *
 * Es **declarativo por estado** (CLAUDE.md §9.4): se llama una sola vez en la raíz de
 * la navegación y cada plataforma sincroniza las barras con el booleano. No hay que
 * acordarse de "restaurar" nada al salir de una pantalla ni llamarlo desde cada juego.
 *
 * @param enabled true para ocultar las barras; false para restaurarlas.
 */
@Composable
expect fun ImmersiveMode(enabled: Boolean)
