package com.example.kortexgames.game

/**
 * Fase de una pantalla de juego LEVELED (Water Sort, Energy Flow): o se está en la
 * antesala eligiendo nivel, o se está jugando el nivel elegido. Permite que la
 * pantalla alterne entre la [com.example.kortexgames.ui.components.GameIntroScreen]
 * (con su carril de niveles) y el tablero sin crear rutas de navegación nuevas.
 */
enum class LeveledGamePhase { LEVEL_SELECT, PLAYING }
