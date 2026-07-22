package com.example.kortexgames.domain.model

/**
 * Formatea una duración en milisegundos de forma **corta y legible** para stats de
 * juego (p. ej. el mejor tiempo por nivel). Pura y sin dependencias de framework para
 * poder usarla desde la UI multiplataforma y testearla directamente.
 *
 * Reglas (pensadas para tiempos de partida, de segundos a unos minutos):
 *  - < 60 s → `"12.3s"` (una décima, útil para diferenciar marcas ajustadas).
 *  - ≥ 60 s → `"m:ss"` (p. ej. `"1:07"`), sin décimas para no recargar.
 *
 * @param ms duración en milisegundos (se asume ≥ 0; los negativos se tratan como 0).
 */
fun formatDurationShort(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val totalSeconds = safe / 1000
    return if (totalSeconds < 60) {
        // Décimas solo bajo el minuto: "12.3s". Redondea hacia abajo la décima.
        val tenths = (safe % 1000) / 100
        "${totalSeconds}.${tenths}s"
    } else {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        // Segundos con cero a la izquierda ("1:07", no "1:7").
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
