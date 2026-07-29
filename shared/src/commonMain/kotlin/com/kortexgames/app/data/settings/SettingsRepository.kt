package com.kortexgames.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.kortexgames.app.core.audio.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Preferencias de audio/háptica del usuario. */
data class UserSettings(
    val isSfxEnabled: Boolean = true,
    val isMusicEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
)

/**
 * Fuente de verdad de las preferencias. Persiste en DataStore (multiplataforma)
 * y expone un StateFlow<UserSettings> que la UI observa directamente.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private object Keys {
        val SFX = booleanPreferencesKey("is_sfx_enabled")
        val MUSIC = booleanPreferencesKey("is_music_enabled")
        val HAPTICS = booleanPreferencesKey("is_haptics_enabled")
    }

    val settings: StateFlow<UserSettings> = dataStore.data
        .map { prefs ->
            UserSettings(
                isSfxEnabled = prefs[Keys.SFX] ?: true,
                isMusicEnabled = prefs[Keys.MUSIC] ?: true,
                isHapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, UserSettings())

    /** Snapshot síncrono para que el AudioManager decida sin suspender. */
    val current: UserSettings get() = settings.value

    suspend fun setSfxEnabled(enabled: Boolean) = update(Keys.SFX, enabled)
    suspend fun setMusicEnabled(enabled: Boolean) = update(Keys.MUSIC, enabled)
    suspend fun setHapticsEnabled(enabled: Boolean) = update(Keys.HAPTICS, enabled)

    private suspend fun update(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }
}

/** Nombre del archivo DataStore, compartido por ambas plataformas. */
internal const val SETTINGS_DATASTORE_FILE = "logic_games_settings.preferences_pb"

/** Creación del DataStore específica de plataforma (necesita ruta/context). */
expect fun createSettingsDataStore(context: PlatformContext): DataStore<Preferences>
