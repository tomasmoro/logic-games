package com.example.kortexgames.core.audio

import com.example.kortexgames.data.settings.SettingsRepository
import kortexgames.shared.generated.resources.Res
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
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
 * Los assets de audio viven en la única fuente compartida con Android
 * (`commonMain/composeResources/files/`); el plugin de Compose los empaqueta en el
 * bundle iOS y aquí se leen vía `Res.readBytes`.
 *
 * Respeta las preferencias del usuario vía [settings].
 */
// Opt-in requerido: AVAudioPlayer/AVAudioSession y los generadores hápticos de
// UIKit se consumen vía cinterop de Foundation, cuya API sigue marcada como
// experimental (ExperimentalForeignApi). Se anota la clase entera porque los usos
// se reparten entre las propiedades (AVAudioPlayer) y varios métodos.
// BetaInteropApi: NSData.create(bytes=,length=) aún es beta.
@OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    ExperimentalResourceApi::class,
)
class IosAudioAndHapticManager(
    private val settings: SettingsRepository,
) : AudioAndHapticManager {

    private val players = mutableMapOf<SoundEffect, AVAudioPlayer>()
    private var musicPlayer: AVAudioPlayer? = null

    /**
     * Scope de carga de los SFX. Se usa [Dispatchers.Main] a propósito: el mapa
     * [players] lo lee [playSound] desde el hilo de UI, así que construirlo también
     * ahí evita compartir estado mutable entre hilos en Kotlin/Native.
     */
    private val loadScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val impactLight = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val impactMedium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val impactHeavy = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
    private val notify = UINotificationFeedbackGenerator()

    override fun preload() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, null)
        AVAudioSession.sharedInstance().setActive(true, null)
        // Los assets viven en composeResources/files y el plugin de Compose los
        // empaqueta en el bundle iOS. Se leen como bytes (suspend) y se envuelven
        // en un AVAudioPlayer precargado por efecto. El nombre ya incluye extensión.
        loadScope.launch {
            SoundEffect.entries.forEach { effect ->
                runCatching {
                    val bytes = Res.readBytes("files/${effect.fileName}")
                    AVAudioPlayer(data = bytes.toNSData(), error = null).also {
                        it.prepareToPlay()
                        players[effect] = it
                    }
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
        loadScope.cancel()
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
