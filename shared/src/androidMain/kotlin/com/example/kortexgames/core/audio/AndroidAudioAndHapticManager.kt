package com.example.kortexgames.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.kortexgames.data.settings.SettingsRepository

/**
 * Implementación Android:
 *   - SFX: [SoundPool] (baja latencia; ideal para respuestas rápidas del juego).
 *   - Música: [MediaPlayer] (streaming en bucle).
 *   - Háptica: [Vibrator] / [VibratorManager] con [VibrationEffect].
 *
 * Respeta las preferencias del usuario: consulta el snapshot de [settings]
 * antes de cada acción, así un toggle en ajustes tiene efecto inmediato.
 */
class AndroidAudioAndHapticManager(
    private val context: Context,
    private val settings: SettingsRepository,
) : AudioAndHapticManager {

    /** Atributos de audio de juego, compartidos por SoundPool y AudioTrack. */
    private val gameAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6) // varios SFX solapados sin cortarse
        .setAudioAttributes(gameAudioAttributes)
        .build()

    private val soundIds = mutableMapOf<SoundEffect, Int>()
    private var musicPlayer: MediaPlayer? = null

    /**
     * Caché de PCM por (frecuencia|duración): sintetizar la onda en cada toque es
     * barato, pero el juego reutiliza las mismas 9 notas una y otra vez, así que
     * memorizarlas evita recomputar en el hilo de UI durante la reproducción.
     */
    private val toneCache = mutableMapOf<Long, ByteArray>()

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun preload() {
        SoundEffect.entries.forEach { effect ->
            val resId = context.resources.getIdentifier(effect.fileName, "raw", context.packageName)
            if (resId != 0) soundIds[effect] = soundPool.load(context, resId, 1)
        }
    }

    override fun playSound(effect: SoundEffect) {
        if (!settings.current.isSfxEnabled) return
        val id = soundIds[effect] ?: return
        soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    override fun playTone(frequencyHz: Float, durationMs: Int) {
        if (!settings.current.isSfxEnabled) return
        // Clave estable combinando bits de la frecuencia y la duración.
        val key = (frequencyHz.toRawBits().toLong() shl 20) or (durationMs.toLong() and 0xFFFFF)
        val pcm = toneCache.getOrPut(key) { ToneSynth.squarePcm16(frequencyHz, durationMs) }

        // AudioTrack en MODO_STATIC: escribimos todo el buffer y lo disparamos.
        // Cada toque crea un track efímero que se auto-libera al llegar al final
        // (marker), así varias notas pueden solaparse sin cortarse entre sí.
        val track = AudioTrack.Builder()
            .setAudioAttributes(gameAudioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        // Marca el final (en frames) para liberar el track cuando termina de sonar.
        track.setNotificationMarkerPosition(pcm.size / 2)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) { t?.release() }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track.play()
    }

    override fun hapticFeedback(type: HapticFeedback) {
        if (!settings.current.isHapticsEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(type.toEffect())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(type.legacyDurationMs())
        }
    }

    override fun startMusic(fileName: String, loop: Boolean) {
        if (!settings.current.isMusicEnabled) return
        stopMusic()
        val resId = context.resources.getIdentifier(fileName, "raw", context.packageName)
        if (resId == 0) return
        musicPlayer = MediaPlayer.create(context, resId).apply {
            isLooping = loop
            start()
        }
    }

    override fun stopMusic() {
        musicPlayer?.run { if (isPlaying) stop(); release() }
        musicPlayer = null
    }

    override fun release() {
        soundPool.release()
        soundIds.clear()
        stopMusic()
    }

    private fun HapticFeedback.toEffect(): VibrationEffect = when (this) {
        HapticFeedback.LIGHT -> VibrationEffect.createOneShot(15, 80)
        HapticFeedback.MEDIUM -> VibrationEffect.createOneShot(25, 160)
        HapticFeedback.HEAVY -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        HapticFeedback.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 30), -1)
        HapticFeedback.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 50, 40, 50), -1)
    }

    private fun HapticFeedback.legacyDurationMs(): Long = when (this) {
        HapticFeedback.LIGHT -> 15
        HapticFeedback.MEDIUM -> 25
        HapticFeedback.HEAVY -> 40
        HapticFeedback.SUCCESS -> 30
        HapticFeedback.ERROR -> 60
    }
}
