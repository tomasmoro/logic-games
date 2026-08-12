package com.kortexgames.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.kortexgames.app.game.FirstRunGames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Recuerda en qué punto de la **primera apertura** está el jugador. Son dos etapas
 * consecutivas, y cada una tiene su propia marca persistida:
 *
 *  1. **Bienvenida jugable** ([introGamesPlayed]): antes de pedirle nada, el jugador
 *     juega los [FirstRunGames] de uno en uno. Se guarda cuántos lleva despachados
 *     para que cerrar la app a mitad no le devuelva al principio.
 *  2. **Puerta de sesión** ([hasDecided]): al terminar la bienvenida se le ofrece
 *     iniciar sesión o seguir como invitado. Se guarda que ya decidió para no volver
 *     a mostrarla (requisito de producto).
 *
 * Es estado de "onboarding visto", independiente del estado de sesión: un invitado
 * que ya pasó la puerta no la vuelve a ver, pero puede iniciar sesión después desde
 * el botón del Home/Perfil.
 *
 * **Compatibilidad con instalaciones existentes:** quien ya pasó la puerta tiene
 * `has_completed_auth_gate = true` y ningún contador de bienvenida. Por eso
 * [isFirstRunOver] mira primero [hasDecided]: un usuario de siempre no debe verse
 * empujado a una bienvenida que no le corresponde.
 */
class OnboardingGate(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val decidedKey = booleanPreferencesKey("has_completed_auth_gate")
    private val introStepKey = intPreferencesKey("first_run_intro_games_played")

    /**
     * `null` mientras DataStore aún no ha emitido (arranque): la UI muestra un
     * splash y NO decide el destino hasta resolverlo, evitando el "parpadeo" de
     * enseñar la bienvenida a un usuario que ya la pasó. Luego `true`/`false`.
     */
    val hasDecided: StateFlow<Boolean?> =
        dataStore.data
            .map { it[decidedKey] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Cuántos juegos de bienvenida quedaron ya atrás (0..[FirstRunGames.size]), o
     * `null` mientras DataStore no ha emitido. Es el índice del **siguiente** juego
     * a presentar; al alcanzar [FirstRunGames.size] la bienvenida terminó.
     */
    val introGamesPlayed: StateFlow<Int?> =
        dataStore.data
            .map { it[introStepKey] ?: 0 }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * `true` cuando la primera apertura ya no aplica: o el jugador terminó la
     * bienvenida, o es una instalación anterior a ella que ya pasó la puerta.
     *
     * Lo consume el gating de anuncios: durante la bienvenida NO se piden anuncios ni
     * se ha resuelto el consentimiento UMP/ATT, así que este flag es la señal de "ya
     * se puede empezar a monetizar" (ver `AdManager` y `beginAdConsentFlow`).
     *
     * Valor inicial `false` (conservador): mientras no se sepa, no se piden anuncios.
     */
    val isFirstRunOver: StateFlow<Boolean> =
        combine(hasDecided, introGamesPlayed) { decided, played ->
            decided == true || (played != null && played >= FirstRunGames.size)
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Marca que el juego de bienvenida número [step] (0-based) quedó atrás.
     *
     * Guarda el **máximo** y no `step + 1` a secas para que una llamada tardía o
     * repetida (una recomposición, una vuelta atrás) no pueda hacer retroceder la
     * bienvenida a un juego que el jugador ya vio.
     */
    suspend fun markIntroGamePlayed(step: Int) {
        dataStore.edit { prefs ->
            val current = prefs[introStepKey] ?: 0
            prefs[introStepKey] = maxOf(current, step + 1).coerceAtMost(FirstRunGames.size)
        }
    }

    /**
     * El jugador **renunció a la bienvenida** desde su pantalla de presentación
     * ("prefiero iniciar sesión"): se da por despachada entera.
     *
     * Se persiste en vez de limitarse a navegar al login porque, si cierra la app
     * antes de decidir en la puerta de sesión, al volver debe encontrarse esa misma
     * puerta y no la bienvenida que acaba de rechazar.
     */
    suspend fun skipIntroGames() {
        dataStore.edit { it[introStepKey] = FirstRunGames.size }
    }

    /** Marca la decisión de la puerta de sesión como tomada (idempotente). */
    suspend fun markDecided() {
        dataStore.edit { it[decidedKey] = true }
    }
}
