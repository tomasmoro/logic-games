package com.example.kortexgames.core.audio

import com.example.kortexgames.data.settings.SettingsRepository
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * Implementación iOS con AVFoundation:
 *   - SFX y música: [AVAudioPlayer] (uno precargado por efecto para minimizar
 *     latencia en los toques rápidos).
 *   - Háptica: [UIImpactFeedbackGenerator] (light/medium/heavy) y
 *     [UINotificationFeedbackGenerator] (success/error).
 *
 * Respeta las preferencias del usuario vía [settings].
 */
class IosAudioAndHapticManager(
    private val settings: SettingsRepository,
) : AudioAndHapticManager {

    private val players = mutableMapOf<SoundEffect, AVAudioPlayer>()
    private var musicPlayer: AVAudioPlayer? = null

    private val impactLight = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val impactMedium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val impactHeavy = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
    private val notify = UINotificationFeedbackGenerator()

    override fun preload() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, null)
        AVAudioSession.sharedInstance().setActive(true, null)
        SoundEffect.entries.forEach { effect ->
            resolveUrl(effect.fileName)?.let { url ->
                AVAudioPlayer(contentsOfURL = url, error = null).also {
                    it.prepareToPlay()
                    players[effect] = it
                }
            }
        }
        // Prepara los generadores hápticos (reduce el retardo del primer disparo).
        listOf(impactLight, impactMedium, impactHeavy).forEach { it.prepare() }
        notify.prepare()
    }

    override fun playSound(effect: SoundEffect) {
        if (!settings.current.isSfxEnabled) return
        players[effect]?.apply {
            currentTime = 0.0
            play()
        }
    }

    override fun hapticFeedback(type: HapticFeedback) {
        if (!settings.current.isHapticsEnabled) return
        when (type) {
            HapticFeedback.LIGHT -> impactLight.impactOccurred()
            HapticFeedback.MEDIUM -> impactMedium.impactOccurred()
            HapticFeedback.HEAVY -> impactHeavy.impactOccurred()
            HapticFeedback.SUCCESS ->
                notify.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticFeedback.ERROR ->
                notify.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
        }
    }

    override fun startMusic(fileName: String, loop: Boolean) {
        if (!settings.current.isMusicEnabled) return
        stopMusic()
        val url = resolveUrl(fileName) ?: return
        musicPlayer = AVAudioPlayer(contentsOfURL = url, error = null).apply {
            numberOfLoops = if (loop) -1 else 0
            prepareToPlay()
            play()
        }
    }

    override fun stopMusic() {
        musicPlayer?.stop()
        musicPlayer = null
    }

    override fun release() {
        players.values.forEach { it.stop() }
        players.clear()
        stopMusic()
    }

    /** Busca el recurso en el bundle probando extensiones comunes. */
    private fun resolveUrl(name: String): NSURL? {
        listOf("wav", "mp3", "caf", "m4a").forEach { ext ->
            NSBundle.mainBundle.URLForResource(name, ext)?.let { return it }
        }
        return null
    }
}
