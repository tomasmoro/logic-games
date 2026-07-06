package com.example.kortexgames.core.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sintetizador de tonos en memoria (sin assets de audio).
 *
 * Motivación: el teclado musical del juego de Memoria necesita **una nota
 * distinta por botón** (escala Do-Re-Mi-Fa-Sol-La-Si-Do-Re). Empaquetar 9
 * archivos WAV/MP3 sería pesado y frágil (nombres, sincronía entre plataformas);
 * en su lugar generamos la señal PCM al vuelo. Como bonus, la **onda cuadrada**
 * produce el timbre "chip/arcade" clásico (rica en armónicos impares) que pide
 * el diseño, gratis y con control total del carácter del sonido.
 *
 * El resultado es PCM lineal 16-bit, mono, little-endian: el formato crudo que
 * consumen `AudioTrack` (Android) y, envuelto en cabecera WAV, `AVAudioPlayer`
 * (iOS). Toda la síntesis vive en commonMain; cada plataforma solo reproduce.
 */
object ToneSynth {

    /** Frecuencia de muestreo estándar; suficiente para tonos audibles y ligera. */
    const val SAMPLE_RATE = 44100

    /**
     * Genera un "blip" arcade: onda cuadrada a [frequencyHz] durante [durationMs].
     *
     * La envolvente evita el "click" de los cortes bruscos (ataque y release
     * cortos) y aporta un decaimiento tipo campana que hace el sonido percusivo y
     * agradable en toques repetidos, en vez de un pitido plano.
     *
     * @param volume amplitud 0f..1f; se deja margen (0.5) para no saturar al
     *        solaparse varias notas seguidas.
     * @return PCM 16-bit LE mono, listo para reproducir a [sampleRate].
     */
    fun squarePcm16(
        frequencyHz: Float,
        durationMs: Int,
        sampleRate: Int = SAMPLE_RATE,
        volume: Float = 0.5f,
    ): ByteArray {
        val totalSamples = (sampleRate.toLong() * durationMs / 1000L).toInt().coerceAtLeast(1)
        val out = ByteArray(totalSamples * 2)

        val attack = (sampleRate * 0.005f).toInt().coerceAtLeast(1)   // ~5 ms de subida
        val release = (sampleRate * 0.030f).toInt().coerceAtLeast(1)  // ~30 ms de bajada
        val twoPiF = 2.0 * PI * frequencyHz

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Onda cuadrada = signo de la senoidal → timbre arcade (armónicos impares).
            val square = if (sin(twoPiF * t) >= 0.0) 1.0 else -1.0

            // Envolvente anti-click: rampa de ataque, meseta y rampa de release.
            val env = when {
                i < attack -> i.toFloat() / attack
                i > totalSamples - release -> (totalSamples - i).toFloat() / release
                else -> 1f
            }
            // Decaimiento global (~40 %): da el carácter percusivo de "blip".
            val decay = 1f - 0.4f * (i.toFloat() / totalSamples)

            val sample = (square * volume * env * decay * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Envuelve PCM 16-bit LE mono en un contenedor WAV mínimo (cabecera de 44 B).
     *
     * `AVAudioPlayer` de iOS no reproduce PCM crudo pero sí datos WAV vía
     * `initWithData:`; Android usa el PCM directamente con `AudioTrack`, por eso
     * esta envoltura solo la necesita iOS.
     */
    fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size

        val header = ByteArray(44)
        fun putStr(off: Int, s: String) { for (k in s.indices) header[off + k] = s[k].code.toByte() }
        fun putIntLE(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShortLE(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        putStr(0, "RIFF"); putIntLE(4, 36 + dataSize); putStr(8, "WAVE")
        putStr(12, "fmt "); putIntLE(16, 16); putShortLE(20, 1); putShortLE(22, channels)
        putIntLE(24, sampleRate); putIntLE(28, byteRate); putShortLE(32, blockAlign)
        putShortLE(34, bitsPerSample)
        putStr(36, "data"); putIntLE(40, dataSize)

        return header + pcm
    }
}
