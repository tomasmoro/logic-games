package com.kortexgames.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.kortexgames.app.data.settings.SettingsRepository
import kortexgames.shared.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import java.io.File

/**
 * Implementación Android:
 *   - SFX: [SoundPool] (baja latencia; ideal para respuestas rápidas del juego).
 *   - Música: [MediaPlayer] (streaming en bucle).
 *   - Háptica: [Vibrator] / [VibratorManager] con [VibrationEffect].
 *
 * Los assets de audio viven en una **única fuente** compartida con iOS:
 * `commonMain/composeResources/files/`. Se leen vía `Res.readBytes` (no hay copia
 * duplicada en `res/raw`) y, como [SoundPool] carga desde un descriptor de archivo,
 * se materializan una vez en la caché para poder cargarlos por ruta.
 *
 * Respeta las preferencias del usuario: consulta el snapshot de [settings]
 * antes de cada acción, así un toggle en ajustes tiene efecto inmediato.
 */
@OptIn(ExperimentalResourceApi::class)
class AndroidAudioAndHapticManager(
    private val context: Context,
    private val settings: SettingsRepository,
) : AudioAndHapticManager {

    /** Atributos de audio de juego para el SoundPool. */
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
     * Scope de precarga. `Res.readBytes` es suspend, así que la carga es
     * asíncrona; [preload] la lanza y no bloquea el arranque. Se cancela en
     * [release]. Dispatchers.Default: el trabajo es I/O ligero + copia a caché.
     */
    private val loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
        // Directorio de caché donde materializamos los assets para SoundPool.
        val sfxDir = File(context.cacheDir, "sfx").apply { mkdirs() }
        loadScope.launch {
            SoundEffect.entries.forEach { effect ->
                runCatching {
                    val bytes = Res.readBytes("files/${effect.fileName}")
                    // Reutiliza el archivo si ya existe con el mismo tamaño (arranques
                    // posteriores no reescriben); si no, lo vuelca una vez.
                    val file = File(sfxDir, effect.fileName)
                    if (!file.exists() || file.length() != bytes.size.toLong()) {
                        file.writeBytes(bytes)
                    }
                    soundIds[effect] = soundPool.load(file.path, 1)
                }
            }
        }
    }

    override fun playSound(effect: SoundEffect) {
        if (!settings.current.isSfxEnabled) return
        val id = soundIds[effect] ?: return
        // El volumen es por EFECTO (p. ej. MERGE_POP suena mucho más bajo que TAP
        // aunque comparta archivo), no un ajuste global: ver KDoc de [SoundEffect.volume].
        soundPool.play(id, effect.volume, effect.volume, 1, 0, 1f)
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
        loadScope.cancel()
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
