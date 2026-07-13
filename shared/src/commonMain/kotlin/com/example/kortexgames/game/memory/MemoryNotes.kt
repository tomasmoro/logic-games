package com.example.kortexgames.game.memory

import com.example.kortexgames.core.audio.SoundEffect

/**
 * Escala musical de los 9 botones de la rejilla 3x3: **Do-Re-Mi-Fa-Sol-La-Si-Do-Re**
 * (Do mayor, de C4 a D5). Cada índice de tile (0..8) tiene una nota fija, de modo
 * que "cada botón tiene su sonido" y repetir la secuencia forma una pequeña melodía.
 *
 * Cada nota es una grabación propia y ligera (`note_*.mp3`), precargada por el
 * manager de audio como un [SoundEffect] más. Aquí solo vive el mapeo tile → nota,
 * que es lógica de juego pura (sin dependencias de plataforma), por eso está en
 * commonMain junto al motor.
 */
object MemoryNotes {

    /**
     * Nota (SFX precargado) de cada tile 0..8, en orden ascendente de la escala.
     * El índice del array **es** el índice de tile, así el mapeo es O(1) y estable.
     */
    private val notesByTile: Array<SoundEffect> = arrayOf(
        SoundEffect.NOTE_DO,   // 0 · Do  (C4)
        SoundEffect.NOTE_RE,   // 1 · Re  (D4)
        SoundEffect.NOTE_MI,   // 2 · Mi  (E4)
        SoundEffect.NOTE_FA,   // 3 · Fa  (F4)
        SoundEffect.NOTE_SOL,  // 4 · Sol (G4)
        SoundEffect.NOTE_LA,   // 5 · La  (A4)
        SoundEffect.NOTE_SI,   // 6 · Si  (B4)
        SoundEffect.NOTE_DO2,  // 7 · Do  (C5)
        SoundEffect.NOTE_RE2,  // 8 · Re  (D5)
    )

    /** Nota del tile [tileIndex]; acota por seguridad al rango válido de la escala. */
    fun soundFor(tileIndex: Int): SoundEffect =
        notesByTile[tileIndex.coerceIn(0, notesByTile.lastIndex)]
}
