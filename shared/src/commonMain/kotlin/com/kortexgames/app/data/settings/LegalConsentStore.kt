package com.kortexgames.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kortexgames.app.core.legal.LegalLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

/**
 * Guarda **qué versión** de los términos aceptó el usuario y **cuándo**.
 *
 * ¿Por qué persistirlo y no limitarse a exigir el tick en el formulario? Porque el
 * RGPD obliga a poder *demostrar* el consentimiento (art. 7.1) y porque la §10 de
 * las condiciones promete volver a pedir aceptación cuando cambien de forma
 * sustancial: sin registrar la versión aceptada no hay forma de saber si la que el
 * usuario aceptó sigue siendo la vigente.
 *
 * Vive en el mismo DataStore que el resto de preferencias, junto a
 * [OnboardingGate], del que copia el patrón (StateFlow + `null` mientras carga).
 *
 * Ámbito **local al dispositivo**: es un registro de que se mostró y se aceptó en
 * esta instalación, no un dato de cuenta. Un usuario que reinstale volverá a ver
 * el tick al registrarse, lo cual es correcto y barato.
 */
class LegalConsentStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val versionKey = stringPreferencesKey("accepted_legal_version")
    private val acceptedAtKey = longPreferencesKey("accepted_legal_at_epoch_ms")

    /**
     * Versión aceptada, o `null` si el usuario aún no aceptó nada (y también
     * mientras DataStore no ha emitido todavía; los consumidores actuales no
     * distinguen ambos casos porque solo escriben, nunca bloquean por él).
     */
    val acceptedVersion: StateFlow<String?> =
        dataStore.data
            .map { it[versionKey] }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * `true` si lo aceptado coincide con [LegalLinks.CURRENT_VERSION]. Hoy nadie
     * lo consume —el tick del alta es la puerta— pero es el gancho listo para el
     * aviso de "los términos han cambiado" sin volver a tocar esta clase.
     */
    val hasAcceptedCurrentVersion: StateFlow<Boolean> =
        dataStore.data
            .map { it[versionKey] == LegalLinks.CURRENT_VERSION }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Registra la aceptación de la versión vigente. Idempotente: reaceptar la
     * misma versión solo refresca la marca de tiempo.
     */
    suspend fun accept(version: String = LegalLinks.CURRENT_VERSION) {
        dataStore.edit { prefs ->
            prefs[versionKey] = version
            prefs[acceptedAtKey] = clock.now().toEpochMilliseconds()
        }
    }
}
