package com.kortexgames.app.core.review

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.kortexgames.app.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * # Invitación a valorar la app
 *
 * Decide **cuándo** proponerle al jugador que deje su opinión en la tienda y
 * recuerda lo que contestó. La decisión de negocio vive en [ReviewPromptPolicy]
 * (pura y testeable); aquí solo se le conectan las entradas reales —el historial de
 * partidas y lo que ya se le preguntó en este dispositivo— y se persiste la
 * respuesta.
 *
 * No abre la tienda: eso lo hace la UI con el `UriHandler` de Compose a partir de
 * [storeLink]. Así este gestor no depende de nada de plataforma y el mismo código
 * sirve para Play y, cuando exista la ficha, para la App Store.
 *
 * @param progress historial local de partidas (la señal de "cuánto ha jugado ya").
 * @param clock reloj inyectable: los tests fijan el "ahora" sin esperar un mes.
 */
class ReviewPromptManager(
    private val store: ReviewPromptStore,
    private val progress: ProgressRepository,
    private val scope: CoroutineScope,
    private val policy: ReviewPromptPolicy = ReviewPromptPolicy(),
    private val clock: Clock = Clock.System,
) {

    /**
     * Ficha de tienda a la que enviar al jugador, o `null` si en esta plataforma
     * todavía no hay ninguna (iOS hasta que la app esté en la App Store). Cuando es
     * `null`, [shouldShowPrompt] no se enciende nunca.
     */
    val storeLink: String? = storeReviewLink

    /**
     * ¿Toca mostrar ahora la invitación?
     *
     * El instante se toma al recalcular y no se observa un reloj: la condición
     * temporal es un respiro de semanas ([ReviewPromptPolicy.COOLDOWN]), así que no
     * necesita despertar a nadie — con que se reevalúe al terminar una partida o al
     * abrir la app es suficiente, y evita un flujo de tiempo corriendo de fondo.
     */
    val shouldShowPrompt: StateFlow<Boolean> =
        combine(progress.observeHistory(null), store.state) { history, state ->
            policy.shouldPrompt(
                storeAvailable = storeLink != null,
                gamesPlayed = history.size,
                timesDismissed = state.timesDismissed,
                hasRated = state.hasRated,
                lastPromptAt = state.lastPromptAt,
                now = clock.now(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * El jugador acepta ir a la tienda. Se da por valorada y **no se le vuelve a
     * pedir jamás**, haya escrito la reseña o no: no hay forma —ni debe haberla— de
     * saber qué hizo dentro de la tienda, y la alternativa (volver a preguntar a
     * quien ya nos hizo el favor de ir) es mucho peor que perder una reseña.
     *
     * Corre en el scope de aplicación: la navegación a la tienda saca la app de
     * primer plano, y una corrutina atada a la pantalla podría no llegar a escribir.
     */
    fun accept() {
        scope.launch { store.markRated(clock.now()) }
    }

    /** "Ahora no" (o descartar tocando fuera): se anota para la segunda y última oferta. */
    fun dismiss() {
        scope.launch { store.markDismissed(clock.now()) }
    }
}

/**
 * Persistencia de la invitación, en el DataStore de preferencias común.
 *
 * Es historia del **dispositivo** y no progreso del jugador, así que el borrado de
 * cuenta no la toca (igual que el registro de la antesala de notificaciones): quien
 * ya valoró la app no debería volver a ver la petición por haber cerrado sesión.
 */
class ReviewPromptStore(private val dataStore: DataStore<Preferences>) {

    private val ratedKey = booleanPreferencesKey("review_prompt_rated")
    private val dismissedKey = intPreferencesKey("review_prompt_dismissed")
    private val lastPromptKey = longPreferencesKey("review_prompt_last_at_ms")

    /** Estado persistido, reactivo. */
    val state: Flow<ReviewPromptState> = dataStore.data.map { prefs ->
        ReviewPromptState(
            hasRated = prefs[ratedKey] ?: false,
            timesDismissed = prefs[dismissedKey] ?: 0,
            lastPromptAt = prefs[lastPromptKey]?.let { Instant.fromEpochMilliseconds(it) },
        )
    }

    /** El jugador aceptó ir a la tienda: se cierra el tema para siempre. */
    suspend fun markRated(at: Instant) {
        dataStore.edit {
            it[ratedKey] = true
            it[lastPromptKey] = at.toEpochMilliseconds()
        }
    }

    /** El jugador dijo "ahora no". */
    suspend fun markDismissed(at: Instant) {
        dataStore.edit {
            it[dismissedKey] = (it[dismissedKey] ?: 0) + 1
            it[lastPromptKey] = at.toEpochMilliseconds()
        }
    }
}

/**
 * Historia de la invitación en este dispositivo.
 *
 * @property hasRated el jugador ya aceptó ir a la tienda alguna vez.
 * @property timesDismissed veces que respondió "ahora no".
 * @property lastPromptAt última respuesta, o null si nunca se le preguntó.
 */
data class ReviewPromptState(
    val hasRated: Boolean,
    val timesDismissed: Int,
    val lastPromptAt: Instant?,
)
