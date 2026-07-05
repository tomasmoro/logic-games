package com.example.kortexgames.core.audio

import android.content.Context
import com.example.kortexgames.data.settings.SettingsRepository

/**
 * En Android, PlatformContext envuelve un [Context]. Se usa una clase envoltorio
 * (y no `typealias PlatformContext = Context`) porque Kotlin 2.4 exige que la
 * modalidad de expect/actual coincida: `Context` es `abstract` y el `expect
 * class` es `final`, así que el typealias directo ya no compila.
 *
 * @property context normalmente el `applicationContext` de la app.
 */
actual class PlatformContext(val context: Context)

actual fun createAudioAndHapticManager(
    context: PlatformContext,
    settings: SettingsRepository,
): AudioAndHapticManager =
    AndroidAudioAndHapticManager(context.context.applicationContext, settings)
