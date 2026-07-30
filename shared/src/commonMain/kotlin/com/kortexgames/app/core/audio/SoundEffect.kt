package com.kortexgames.app.core.audio

/**
 * Catálogo de efectos de sonido de baja latencia. El GameEngine invoca estos
 * valores semánticos sin conocer archivos ni la API nativa: el manager mapea
 * cada uno a su recurso y lo precarga.
 *
 * [fileName] es el nombre del asset **con extensión**, ubicado en la única fuente
 * de audio del proyecto: `commonMain/composeResources/files/`. Ambas plataformas
 * lo resuelven desde ahí (`Res.readBytes("files/$fileName")`), de modo que no hay
 * copias duplicadas por plataforma. La extensión viaja en el nombre porque los
 * SFX son `.wav` y las notas son `.mp3`.
 */
enum class SoundEffect(val fileName: String) {
    SUCCESS("sfx_success.wav"),
    ERROR("sfx_error.wav"),
    TAP("sfx_tap.wav"),
    TIMER_TICK("sfx_timer_tick.wav"),
    LEVEL_UP("sfx_level_up.wav"),

    /** Tirada de dado (juego al azar): rebotes secos que se asientan. */
    DICE_ROLL("sfx_dice_roll.wav"),

    // --- Notas del teclado del juego de Memoria (escala Do mayor, C4..D5) -------
    // Grabaciones propias y ligeras (una por botón 0..8). Sustituyen a los tonos
    // sintetizados: dan un timbre personalizado y consistente entre plataformas.
    // El mapeo tile → nota vive en [com.kortexgames.app.game.memory.MemoryNotes].
    NOTE_DO("note_do.mp3"),     // Do  (C4)
    NOTE_RE("note_re.mp3"),     // Re  (D4)
    NOTE_MI("note_mi.mp3"),     // Mi  (E4)
    NOTE_FA("note_fa.mp3"),     // Fa  (F4)
    NOTE_SOL("note_sol.mp3"),   // Sol (G4)
    NOTE_LA("note_la.mp3"),     // La  (A4)
    NOTE_SI("note_si.mp3"),     // Si  (B4)
    NOTE_DO2("note_do2.mp3"),   // Do  (C5)
    NOTE_RE2("note_re2.mp3"),   // Re  (D5)
}

/** Intensidades de feedback háptico, mapeadas a cada plataforma. */
enum class HapticFeedback {
    LIGHT,
    MEDIUM,
    HEAVY,
    SUCCESS,
    ERROR,
}
