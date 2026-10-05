package com.kortexgames.app.game.access

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kortexgames.app.core.ads.RewardResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/*
 * Cupo diario de los juegos premium.
 *
 * Decisiones clave (ver también [PlayAccess]):
 *  - **Local y sin validar en servidor.** El contador vive en DataStore, igual que la
 *    misión diaria. Cambiar la hora del móvil se lo salta, pero lo que se pierde es
 *    poco: es la misma confianza que ya asumen la pista, el revivir y los intentos
 *    extra de torneo (migración 0057). Validarlo en servidor rompería además el modo
 *    invitado offline.
 *  - **Se cuenta al EMPEZAR**, no al terminar: reiniciar desde la pausa y "Jugar de
 *    nuevo" también gastan partida (decisión de producto). Contar al terminar dejaría
 *    jugar gratis indefinidamente abandonando cada partida antes del final.
 *  - **Día local**, como la racha y el objetivo diario: el cupo se renueva a la
 *    medianoche del jugador, que es lo que entiende por "5 al día".
 */

/**
 * Partidas gratis gastadas en un juego, tal cual se persisten: la fecha (ISO
 * `yyyy-MM-dd`) a la que corresponden y el contador. [PlayQuotaManager] decide si
 * [date] sigue siendo "hoy"; si no, [used] ha caducado y cuenta como 0.
 */
data class DailyPlayCount(val date: String?, val used: Int)

/**
 * Persistencia del cupo diario. Es una interfaz para que [PlayQuotaManager] se pueda
 * testear sin DataStore.
 */
interface PlayQuotaStore {
    /** Contador guardado de [gameId] (con su fecha, aunque ya haya caducado). */
    fun observe(gameId: String): Flow<DailyPlayCount>

    /**
     * Suma una partida gratis a [gameId] en [isoDate]. Si lo guardado era de otro día,
     * empieza de nuevo en 1 en vez de acumular sobre un día caducado.
     */
    suspend fun increment(gameId: String, isoDate: String)
}

/**
 * [PlayQuotaStore] sobre las preferencias de la app. Dos claves por juego (fecha y
 * contador) en vez de un set de todos: son pocos juegos y así cada lectura/escritura
 * toca solo lo suyo.
 *
 * No se borra al eliminar la cuenta: no es un dato personal sino un contador de uso
 * del dispositivo, y borrarlo regalaría el cupo del día a quien borre y recree la
 * cuenta.
 */
class DataStorePlayQuotaStore(private val dataStore: DataStore<Preferences>) : PlayQuotaStore {
    private fun dateKey(gameId: String) = stringPreferencesKey("play_quota_date_$gameId")
    private fun usedKey(gameId: String) = intPreferencesKey("play_quota_used_$gameId")

    override fun observe(gameId: String): Flow<DailyPlayCount> = dataStore.data.map { prefs ->
        DailyPlayCount(date = prefs[dateKey(gameId)], used = prefs[usedKey(gameId)] ?: 0)
    }

    override suspend fun increment(gameId: String, isoDate: String) {
        dataStore.edit { prefs ->
            val used = if (prefs[dateKey(gameId)] == isoDate) prefs[usedKey(gameId)] ?: 0 else 0
            prefs[dateKey(gameId)] = isoDate
            prefs[usedKey(gameId)] = used + 1
        }
    }
}

/**
 * Hace cumplir el cupo de los juegos premium: expone el [PlayAccess] de cada juego
 * para la UI y decide, al pedir una partida, si se concede gratis, a cambio de un
 * anuncio o no se concede.
 *
 * @param store dónde se guardan las partidas gratis gastadas.
 * @param showRewardedAd muestra un anuncio recompensado y devuelve su resultado
 *        (en la app, [com.kortexgames.app.core.ads.AdManager.showRewardedAd]). Es una
 *        lambda para no acoplar los tests al `AdManager`.
 * @param isPremiumGame si un juego está marcado premium en el catálogo.
 * @param isPremiumUser plan vigente del jugador. Se evalúa en cada lectura (no es un
 *        `Flow`), igual que en el `AdManager`.
 * @param exempt `true` mientras las partidas no cuentan para el cupo (torneo en
 *        curso, bienvenida de primera apertura). Ver [resolvePlayAccess].
 */
class PlayQuotaManager(
    private val store: PlayQuotaStore,
    private val showRewardedAd: suspend () -> RewardResult,
    private val isPremiumGame: (String) -> Boolean,
    private val isPremiumUser: () -> Boolean,
    private val exempt: Flow<Boolean>,
    private val clock: Clock = Clock.System,
    private val limit: Int = FREE_DAILY_PLAYS,
) {
    /**
     * Serializa las peticiones: sin esto, un doble toque en "Jugar" leería el mismo
     * contador dos veces y concedería dos partidas gastando una, o abriría dos
     * anuncios a la vez.
     */
    private val mutex = Mutex()

    /** Acceso actual a [gameId], reactivo al contador y a [exempt]. */
    fun access(gameId: String): Flow<PlayAccess> =
        combine(store.observe(gameId), exempt) { count, isExempt -> resolve(gameId, count, isExempt) }

    /**
     * Pide permiso para empezar una partida de [gameId] y, si hace falta, lo cobra:
     *
     *  - [PlayAccess.Unlimited]: se concede sin tocar nada.
     *  - [PlayAccess.Free]: se concede y se gasta una partida gratis.
     *  - [PlayAccess.NeedsAd]: muestra el anuncio recompensado y suspende hasta que se
     *    cierra. Se concede si se vio entero y también si **no había anuncio**
     *    ([RewardResult.UNAVAILABLE]): quedarse sin inventario de AdMob (o sin red) es
     *    culpa nuestra, no del jugador, y bloquearle el juego sería la peor
     *    experiencia posible. Solo cerrar el anuncio antes de tiempo lo deniega.
     *
     * Quien llama debe haber dejado claro antes que la partida cuesta un anuncio:
     * la política de AdMob exige que el recompensado sea opcional y anunciado.
     *
     * @return `true` si la partida puede empezar.
     */
    suspend fun requestPlay(gameId: String): Boolean = mutex.withLock {
        val today = today()
        when (resolve(gameId, store.observe(gameId).first(), exempt.first(), today)) {
            PlayAccess.Unlimited -> true
            is PlayAccess.Free -> {
                store.increment(gameId, today)
                true
            }
            PlayAccess.NeedsAd -> showRewardedAd() != RewardResult.DISMISSED
        }
    }

    private fun resolve(
        gameId: String,
        count: DailyPlayCount,
        isExempt: Boolean,
        today: String = today(),
    ): PlayAccess = resolvePlayAccess(
        isPremiumGame = isPremiumGame(gameId),
        isPremiumUser = isPremiumUser(),
        exempt = isExempt,
        usedToday = if (count.date == today) count.used else 0,
        limit = limit,
    )

    private fun today(): String = clock.todayIn(TimeZone.currentSystemDefault()).toString()
}
