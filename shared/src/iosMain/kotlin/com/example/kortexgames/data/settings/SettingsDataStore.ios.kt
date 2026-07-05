package com.example.kortexgames.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.kortexgames.core.audio.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** DataStore iOS: persiste en el directorio Documents del sandbox de la app. */
@OptIn(ExperimentalForeignApi::class)
actual fun createSettingsDataStore(context: PlatformContext): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val documents: NSURL? = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            )
            (requireNotNull(documents?.path) + "/" + SETTINGS_DATASTORE_FILE).toPath()
        },
    )
