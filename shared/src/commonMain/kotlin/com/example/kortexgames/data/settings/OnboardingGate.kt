package com.example.kortexgames.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Recuerda si el usuario ya **tomó una decisión** en la puerta de entrada
 * (login): iniciar sesión o continuar como invitado. Persiste un único booleano
 * en DataStore para que la pantalla de bienvenida se muestre **solo la primera
 * vez** (requisito de producto: no volver a mostrarla tras decidir).
 *
 * Es un flag de "onboarding visto", independiente del estado de sesión: un
 * invitado que ya pasó la puerta no la vuelve a ver, pero puede iniciar sesión
 * después desde el botón del Home/Perfil.
 */
class OnboardingGate(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val decidedKey = booleanPreferencesKey("has_completed_auth_gate")

    /**
     * `null` mientras DataStore aún no ha emitido (arranque): la UI muestra un
     * splash y NO decide el destino hasta resolverlo, evitando el "parpadeo" de
     * enseñar la bienvenida a un usuario que ya la pasó. Luego `true`/`false`.
     */
    val hasDecided: StateFlow<Boolean?> =
        dataStore.data
            .map { it[decidedKey] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Marca la decisión como tomada (idempotente). */
    suspend fun markDecided() {
        dataStore.edit { it[decidedKey] = true }
    }
}
