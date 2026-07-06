package com.example.kortexgames.game.memory

/**
 * Escala musical de los 9 botones de la rejilla 3x3: **Do-Re-Mi-Fa-Sol-La-Si-Do-Re**
 * (Do mayor, de C4 a D5). Cada índice de tile (0..8) tiene una nota fija, de modo
 * que "cada botón tiene su sonido" y repetir la secuencia forma una pequeña melodía.
 *
 * El timbre arcade lo aporta [com.example.kortexgames.core.audio.ToneSynth] (onda
 * cuadrada); aquí solo vive el mapeo tile → frecuencia, que es lógica de juego pura
 * (sin dependencias de plataforma), por eso está en commonMain junto al motor.
 */
object MemoryNotes {

    /** Frecuencias en Hz (temperamento igual, A4 = 440 Hz) indexadas por tile 0..8. */
    val frequencies: FloatArray = floatArrayOf(
        261.63f, // 0 · Do  (C4)
        293.66f, // 1 · Re  (D4)
        329.63f, // 2 · Mi  (E4)
        349.23f, // 3 · Fa  (F4)
        392.00f, // 4 · Sol (G4)
        440.00f, // 5 · La  (A4)
        493.88f, // 6 · Si  (B4)
        523.25f, // 7 · Do  (C5)
        587.33f, // 8 · Re  (D5)
    )

    /** Frecuencia de la nota del tile [tileIndex]; acota por seguridad al rango válido. */
    fun frequencyFor(tileIndex: Int): Float =
        frequencies[tileIndex.coerceIn(0, frequencies.lastIndex)]
}
