package com.kortexgames.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Recuerda **qué tutoriales animados de juego ya vio** el jugador, para que cada uno se
 * abra solo la primera vez que entra en ese juego (después se puede volver a ver a mano
 * desde la antesala).
 *
 * Vive en el mismo DataStore que el resto de preferencias y copia el patrón de
 * [OnboardingGate] (StateFlow + `null` mientras carga).
 *
 * Ámbito **local al dispositivo**, a propósito: es "ya te lo enseñé en este teléfono", no
 * un dato de cuenta. Quien reinstale o cambie de móvil volverá a ver el tutorial una vez,
 * lo cual es barato y hasta deseable; sincronizarlo no compensaría una columna en Supabase.
 */
class TutorialStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val seenKey = stringSetPreferencesKey("seen_game_tutorials")

    /**
     * Ids de los juegos (`GameIds`) cuyo tutorial ya se mostró, o `null` mientras
     * DataStore no ha emitido todavía. La UI **no decide** con `null`: abrir el tutorial
     * "por si acaso" se lo repetiría a quien ya lo vio.
     */
    val seen: StateFlow<Set<String>?> =
        dataStore.data
            .map { it[seenKey] ?: emptySet() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Marca como visto el tutorial de [gameId]. Idempotente.
     *
     * No es `suspend`: escribe en el ámbito de la app y no en el de quien llama, porque se
     * invoca al ABRIR el tutorial y el jugador puede saltarlo y salir de la pantalla en el
     * mismo instante — una corrutina ligada a la pantalla se cancelaría sin guardar y el
     * tutorial volvería a saltarle la próxima vez.
     */
    fun markSeen(gameId: String) {
        scope.launch {
            dataStore.edit { prefs -> prefs[seenKey] = (prefs[seenKey] ?: emptySet()) + gameId }
        }
    }
}
