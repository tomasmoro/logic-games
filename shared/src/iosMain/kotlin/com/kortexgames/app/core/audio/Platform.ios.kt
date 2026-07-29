package com.kortexgames.app.core.audio

import com.kortexgames.app.data.settings.SettingsRepository

/** En iOS no se necesita contexto para inicializar audio/persistencia. */
actual class PlatformContext

actual fun createAudioAndHapticManager(
    context: PlatformContext,
    settings: SettingsRepository,
): AudioAndHapticManager = IosAudioAndHapticManager(settings)
