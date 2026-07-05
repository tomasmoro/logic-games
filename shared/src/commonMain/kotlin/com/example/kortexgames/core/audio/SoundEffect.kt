package com.example.kortexgames.core.audio

/**
 * Catálogo de SFX básicos. El GameEngine invoca estos valores semánticos sin
 * conocer archivos ni la API nativa: el manager mapea cada uno a su recurso.
 *
 * [fileName] es el nombre lógico del asset (mismo nombre en Android res/raw
 * e iOS bundle) para que las implementaciones actual lo resuelvan.
 */
enum class SoundEffect(val fileName: String) {
    SUCCESS("sfx_success"),
    ERROR("sfx_error"),
    TAP("sfx_tap"),
    TIMER_TICK("sfx_timer_tick"),
    LEVEL_UP("sfx_level_up"),
}

/** Intensidades de feedback háptico, mapeadas a cada plataforma. */
enum class HapticFeedback {
    LIGHT,
    MEDIUM,
    HEAVY,
    SUCCESS,
    ERROR,
}
