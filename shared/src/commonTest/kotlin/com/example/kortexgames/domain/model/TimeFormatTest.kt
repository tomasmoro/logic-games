package com.example.kortexgames.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del formateador corto de duraciones ([formatDurationShort]) usado en las stats
 * de tiempo por nivel. Cubre las dos ramas (bajo el minuto con décimas / m:ss) y los
 * bordes: cero, negativos, el salto en 60 s y el relleno de segundos.
 */
class TimeFormatTest {

    @Test
    fun bajoUnMinutoMuestraDecimas() {
        assertEquals("0.0s", formatDurationShort(0))
        assertEquals("12.3s", formatDurationShort(12_345))
        // Trunca la décima (no redondea): 999 ms → 0.9s.
        assertEquals("0.9s", formatDurationShort(999))
        assertEquals("59.9s", formatDurationShort(59_900))
    }

    @Test
    fun desdeUnMinutoUsaFormatoMinutosSegundos() {
        assertEquals("1:00", formatDurationShort(60_000))
        // Rellena los segundos a dos dígitos: 67 s → 1:07.
        assertEquals("1:07", formatDurationShort(67_000))
        assertEquals("2:05", formatDurationShort(125_400))
        assertEquals("10:00", formatDurationShort(600_000))
    }

    @Test
    fun losNegativosSeTratanComoCero() {
        assertEquals("0.0s", formatDurationShort(-500))
    }
}
