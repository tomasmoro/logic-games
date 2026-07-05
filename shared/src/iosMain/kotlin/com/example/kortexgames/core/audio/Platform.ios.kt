package com.example.kortexgames.core.audio

import com.example.kortexgames.data.settings.SettingsRepository

/** En iOS no se necesita contexto para inicializar audio/persistencia. */
actual class PlatformContext

actual fun createAudioAndHapticManager(
    context: PlatformContext,
    settings: SettingsRepository,
): AudioAndHapticManager = IosAudioAndHapticManager(settings)
