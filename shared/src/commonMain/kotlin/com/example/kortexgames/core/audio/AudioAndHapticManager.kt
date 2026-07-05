package com.example.kortexgames.core.audio

/**
 * Contrato multiplataforma de audio y háptica. La UI y los motores de juego
 * dependen SOLO de esta interfaz; las implementaciones nativas (SoundPool /
 * AVAudioPlayer / Vibrator / UIImpactFeedbackGenerator) viven en actual.
 *
 * El manager respeta las preferencias del usuario (isSfxEnabled, isMusicEnabled,
 * isHapticsEnabled): si están desactivadas, las llamadas son no-ops silenciosas.
 */
interface AudioAndHapticManager {

    /** Precarga los SFX en memoria (SoundPool en Android). Llamar al inicio. */
    fun preload()

    /** Reproduce un efecto de sonido de baja latencia. */
    fun playSound(effect: SoundEffect)

    /** Dispara feedback háptico. */
    fun hapticFeedback(type: HapticFeedback)

    /** Música de fondo en bucle (MediaPlayer / AVAudioPlayer). */
    fun startMusic(fileName: String, loop: Boolean = true)
    fun stopMusic()

    /** Libera recursos nativos (onDestroy / deinit). */
    fun release()
}

/**
 * `PlatformContext` abstrae lo que cada plataforma necesita para inicializar
 * audio/persistencia: en Android es un android.content.Context; en iOS no hace
 * falta nada. Se resuelve vía actual typealias / actual class.
 */
expect class PlatformContext

/** Fábrica de la implementación nativa del manager. */
expect fun createAudioAndHapticManager(
    context: PlatformContext,
    settings: com.example.kortexgames.data.settings.SettingsRepository,
): AudioAndHapticManager
