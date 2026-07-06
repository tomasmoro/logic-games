package com.example.kortexgames.core.audio

import com.example.kortexgames.data.settings.SettingsRepository
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
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
// Opt-in requerido: AVAudioPlayer/AVAudioSession y los generadores hápticos de
// UIKit se consumen vía cinterop de Foundation, cuya API sigue marcada como
// experimental (ExperimentalForeignApi). Se anota la clase entera porque los usos
// se reparten entre las propiedades (AVAudioPlayer) y varios métodos
// (preload/startMusic), evitando salpicar la anotación por cada llamada.
// BetaInteropApi: NSData.create(bytes=,length=) (síntesis de tonos) aún es beta.
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosAudioAndHapticManager(
    private val settings: SettingsRepository,
) : AudioAndHapticManager {

    private val players = mutableMapOf<SoundEffect, AVAudioPlayer>()
    private var musicPlayer: AVAudioPlayer? = null

    /**
     * Reproductores de tonos vivos. AVAudioPlayer deja de sonar si se libera, así
     * que hay que retenerlos mientras suenan; se purgan los ya terminados en cada
     * disparo para no acumularlos.
     */
    private val tonePlayers = mutableListOf<AVAudioPlayer>()

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

    override fun playTone(frequencyHz: Float, durationMs: Int) {
        if (!settings.current.isSfxEnabled) return
        // AVAudioPlayer no reproduce PCM crudo: se envuelve en WAV en memoria.
        val wav = ToneSynth.wav(ToneSynth.squarePcm16(frequencyHz, durationMs))
        val player = AVAudioPlayer(data = wav.toNSData(), error = null)
        player.prepareToPlay()
        player.play()
        // Retén el reproductor mientras suena y descarta los ya finalizados.
        tonePlayers.removeAll { !it.playing }
        tonePlayers.add(player)
    }

    /** Copia el ByteArray a un NSData (sin exponer punteros fuera del pin). */
    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
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
        tonePlayers.forEach { it.stop() }
        tonePlayers.clear()
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
