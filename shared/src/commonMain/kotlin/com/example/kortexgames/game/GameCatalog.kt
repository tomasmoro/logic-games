package com.example.kortexgames.game

/**
 * IDs estables de los juegos de ejemplo. Deben coincidir con las filas sembradas
 * en la tabla `games` de Supabase (migración `0005_seed_catalog.sql`) para que la
 * sincronización remota (FK a `games`) funcione. Son UUID fijos y deterministas.
 */
object GameIds {
    /** Memoria de secuencias (categoría "memory"). */
    const val SEQUENCE_MEMORY = "11111111-1111-4111-8111-111111111111"

    /** Reflejos de toque rápido (categoría "reflexes"). */
    const val REFLEX_TAP = "22222222-2222-4222-8222-222222222222"
}
