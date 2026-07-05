package com.example.kortexgames.core.audio

import android.content.Context
import android.media.AudioAttributes
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

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6) // varios SFX solapados sin cortarse
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = mutableMapOf<SoundEffect, Int>()
    private var musicPlayer: MediaPlayer? = null

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
