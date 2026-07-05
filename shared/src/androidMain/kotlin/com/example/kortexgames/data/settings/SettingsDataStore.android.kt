package com.example.kortexgames.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.kortexgames.core.audio.PlatformContext
import okio.Path.Companion.toPath

/** DataStore Android: persiste bajo el filesDir de la app. */
actual fun createSettingsDataStore(context: PlatformContext): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            context.context.filesDir.resolve(SETTINGS_DATASTORE_FILE).absolutePath.toPath()
        },
    )
