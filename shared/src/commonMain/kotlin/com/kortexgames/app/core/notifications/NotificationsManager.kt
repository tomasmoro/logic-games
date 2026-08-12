package com.kortexgames.app.core.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.daily.DailyGoalManager
import com.kortexgames.app.game.daily.calculateStreakDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * # Orquestador de notificaciones
 *
 * Une las cuatro piezas del módulo y las conecta al estado real de la app:
 * observa historial, misión diaria y preferencias, le pide el plan al
 * [NotificationPlanner], resuelve los textos con [NotificationCopyProvider] y se lo
 * entrega al [NotificationScheduler] de la plataforma.
 *
 * ## Estrategia: replanificar entero, siempre
 *
 * Cada vez que cambia algo relevante (se termina una partida, se completa la misión,
 * el usuario apaga los recordatorios) se **cancela todo y se reprograma el plan
 * completo**. Es más simple y mucho más seguro que ir aplicando incrementos: no hay
 * forma de que sobreviva un aviso de una situación que ya cambió —el clásico "no
 * pierdas tu racha" que llega justo después de jugar—, y el estado del sistema
 * operativo siempre es función del estado actual, no del historial de eventos.
 *
 * El coste es despreciable: son como mucho cinco avisos y las dos plataformas
 * tratan el reemplazo por identificador como una operación barata.
 *
 * ## Qué NO hace
 *
 * No pide permisos por su cuenta ni notifica nada si el usuario apagó los
 * recordatorios en Ajustes. El permiso se pide desde un gesto explícito (ver
 * [requestPermission]); mientras no exista, [apply] no programa nada.
 *
 * @param progress historial local de partidas (fuente de "última vez que jugó" y racha).
 * @param dailyGoal estado de la misión diaria (cuántos juegos faltan hoy).
 * @param settings preferencias del usuario (interruptor de recordatorios).
 * @param clock reloj inyectable: los tests fijan el "ahora" sin esperar a mañana.
 */
class NotificationsManager(
    private val scheduler: NotificationScheduler,
    private val copy: NotificationCopyProvider,
    private val store: NotificationStore,
    private val progress: ProgressRepository,
    private val dailyGoal: DailyGoalManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val planner: NotificationPlanner = NotificationPlanner(),
    private val primingPolicy: NotificationPrimingPolicy = NotificationPrimingPolicy(),
    private val clock: Clock = Clock.System,
) {

    /**
     * Arranca la observación reactiva. Se llama una vez desde el `AppGraph`.
     *
     * El `distinctUntilChanged` sobre la señal —que NO incluye el instante actual—
     * es lo que evita reprogramar en cada emisión repetida de un `StateFlow`: solo
     * se replanifica cuando cambia algo que altera el plan de verdad.
     */
    fun start() {
        signal()
            .distinctUntilChanged()
            .onEach {
                lastSignal = it
                apply(it)
            }
            .launchIn(scope)
    }

    /**
     * Última señal observada. Se guarda para poder replanificar desde fuera del flujo
     * (al conceder el permiso) sin recomponer a mano un estado que ya está calculado.
     */
    private var lastSignal: PlanSignal? = null

    /**
     * Registra que el jugador acaba de batir su récord personal en [gameId].
     *
     * Se persiste (y no se limita a programar el aviso al vuelo) porque cualquier
     * replanificación posterior cancela todo: sin dejar rastro, la revancha de mañana
     * desaparecería en cuanto el jugador terminara otra partida. Guardar la señal la
     * hace parte del estado del que se deriva el plan, igual que la racha.
     *
     * Dispara y olvida a propósito: se invoca desde la ruta de fin de partida, donde
     * un fallo al persistir esto no debe tumbar el guardado del resultado.
     */
    fun onRecordBeaten(gameId: String) {
        scope.launch {
            store.setRecordBeaten(gameId, clock.now())
        }
    }

    /**
     * ¿Toca mostrar la **antesala de permiso**? (ver [NotificationPrimingPolicy] para
     * el porqué de cada regla). La UI la observa y pinta el diálogo de la app —nunca
     * el del sistema— cuando emite true.
     *
     * Es un `StateFlow` y no un evento porque la oferta debe seguir en pie hasta que
     * el usuario responda: si cierra la app a mitad, al volver sigue pendiente.
     */
    val shouldShowPriming: StateFlow<Boolean> =
        combine(progress.observeHistory(null), store.priming) { history, priming ->
            primingPolicy.shouldPrime(
                gamesPlayed = history.size,
                timesDeclined = priming.timesDeclined,
                permissionAlreadyAsked = priming.permissionAsked,
            )
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * El usuario aceptó la oferta: ahora sí se muestra el diálogo del sistema.
     *
     * Corre en el scope de aplicación y no en el de la pantalla: el diálogo del
     * sistema sobrevive a que la UI que lo lanzó se recomponga o se destruya, y una
     * corrutina atada a ella lo dejaría a medias.
     */
    fun acceptPriming() {
        scope.launch { requestPermission() }
    }

    /**
     * El usuario respondió "ahora no". No se toca el permiso del sistema —esa es toda
     * la gracia de la antesala— y solo se anota la negativa para decidir si habrá una
     * segunda oferta ([NotificationPrimingPolicy.MAX_ATTEMPTS]).
     */
    fun declinePriming() {
        scope.launch { store.incrementPrimingDeclined() }
    }

    /**
     * Pide al sistema el permiso de notificaciones y replanifica si se concede.
     *
     * Deja constancia de que el diálogo ya se mostró **pase lo que pase**: a partir de
     * aquí el sistema no lo volverá a mostrar, así que ni la antesala ni ningún otro
     * punto deben volver a ofrecerlo (el camino que queda es Ajustes).
     *
     * @return true si tras la llamada la app puede notificar.
     */
    suspend fun requestPermission(): Boolean {
        store.markPermissionAsked()
        val granted = scheduler.requestPermission()
        if (granted) lastSignal?.let { apply(it) }
        return granted
    }

    /** ¿Permite el sistema operativo publicar notificaciones ahora mismo? */
    suspend fun areNotificationsAllowed(): Boolean = scheduler.areNotificationsAllowed()

    /**
     * Retira todos los avisos pendientes sin tocar preferencias. Lo usa el borrado de
     * cuenta: el progreso del que hablaban ya no existe.
     */
    fun cancelAll() {
        scheduler.cancelAll()
    }

    /**
     * **Solo depuración.** Programa un aviso de prueba a 15 segundos para comprobar en
     * un dispositivo real que la entrega funciona de punta a punta.
     *
     * Existe porque los avisos reales tardan horas o días en dispararse por diseño, y
     * eso hace imposible verificar de un vistazo la parte más frágil del módulo: que
     * el sistema operativo acepte la programación y publique la notificación. Usa su
     * propio [NotificationKind.DEBUG_TEST], que el planificador no emite nunca y que
     * las replanificaciones no cancelan (`isManaged = false`), para que una partida
     * terminada durante esos 15 segundos no se lo lleve por delante.
     *
     * La UI que lo invoca solo se pinta en builds de debug (ver `isDebugBuild`).
     *
     * @return false si el sistema no permite notificar (permiso no concedido): no hay
     *   nada que probar hasta que lo esté.
     */
    suspend fun scheduleTestNotification(): Boolean {
        if (!scheduler.areNotificationsAllowed()) return false
        scheduler.schedule(
            notification = copy.resolve(NotificationContent.DebugTest),
            at = clock.now() + TEST_NOTIFICATION_DELAY,
        )
        return true
    }

    private companion object {
        /**
         * Margen del aviso de prueba: suficiente para mandar la app a segundo plano y
         * ver la notificación llegar como la vería un usuario real.
         */
        val TEST_NOTIFICATION_DELAY = 15.seconds
    }

    /**
     * Cancela lo pendiente y reprograma el plan que corresponde a [signal].
     *
     * El orden importa: primero se cancela y solo después se decide si toca volver a
     * programar. Así, apagar el interruptor de Ajustes (o revocar el permiso desde el
     * sistema) limpia lo que ya estaba programado en vez de dejarlo vivo.
     */
    private suspend fun apply(signal: PlanSignal) {
        scheduler.cancelAll()
        if (!signal.remindersEnabled) return
        if (!scheduler.areNotificationsAllowed()) return

        val inputs = NotificationInputs(
            now = clock.now(),
            timeZone = TimeZone.currentSystemDefault(),
            lastPlayed = signal.lastPlayed,
            streakDays = signal.streakDays,
            dailyMissionRemaining = signal.missionRemaining,
            recordBeaten = signal.recordBeaten,
        )
        for (planned in planner.plan(inputs)) {
            scheduler.schedule(copy.resolve(planned.content), planned.at)
        }
    }

    /** Señal combinada: todo lo que puede cambiar el plan, y nada más. */
    private fun signal(): Flow<PlanSignal> = combine(
        progress.observeHistory(null),
        dailyGoal.state,
        settings.settings,
        store.recordBeaten,
    ) { history, goal, userSettings, recordBeaten ->
        PlanSignal(
            lastPlayed = history.maxOfOrNull { it.createdAt },
            streakDays = calculateStreakDays(history, clock),
            missionRemaining = goal.remaining,
            remindersEnabled = userSettings.areRemindersEnabled,
            recordBeaten = recordBeaten,
        )
    }

    /**
     * Entradas del plan **sin el instante actual**. Excluir "ahora" es lo que permite
     * comparar dos señales por igualdad y no reprogramar cuando nada ha cambiado.
     */
    private data class PlanSignal(
        val lastPlayed: Instant?,
        val streakDays: Int,
        val missionRemaining: Int,
        val remindersEnabled: Boolean,
        val recordBeaten: RecordBeatenSignal?,
    )
}

/**
 * Persistencia mínima del módulo: el último récord personal batido pendiente de
 * "revancha". Vive en el mismo DataStore que el resto de preferencias.
 *
 * Se guarda el **id** del juego y no su título: el título es contenido de UI y puede
 * cambiar (o traducirse); el id es estable. La traducción a nombre visible se hace al
 * leer, contra el [GameCatalog].
 */
class NotificationStore(private val dataStore: DataStore<Preferences>) {

    private val recordGameIdKey = stringPreferencesKey("notif_record_game_id")
    private val recordAtKey = longPreferencesKey("notif_record_at_ms")
    private val permissionAskedKey = booleanPreferencesKey("notif_permission_asked")
    private val primingDeclinedKey = intPreferencesKey("notif_priming_declined")

    /**
     * Estado de la antesala de permiso, reactivo.
     *
     * Se persiste porque el permiso es de una sola oportunidad real (ver
     * [NotificationPrimingPolicy]) y el estado del sistema no distingue "nunca se lo
     * pedimos" de "lo denegó": ambos responden lo mismo. Sin este registro, la app no
     * podría saber si le queda alguna bala.
     */
    val priming: Flow<PrimingState> = dataStore.data.map { prefs ->
        PrimingState(
            permissionAsked = prefs[permissionAskedKey] ?: false,
            timesDeclined = prefs[primingDeclinedKey] ?: 0,
        )
    }

    /** Deja constancia de que el diálogo del sistema ya se mostró (o se intentó). */
    suspend fun markPermissionAsked() {
        dataStore.edit { it[permissionAskedKey] = true }
    }

    /** Anota un "ahora no" de la antesala (no toca el permiso del sistema). */
    suspend fun incrementPrimingDeclined() {
        dataStore.edit { it[primingDeclinedKey] = (it[primingDeclinedKey] ?: 0) + 1 }
    }

    /**
     * Último récord batido, o null si no hay ninguno pendiente.
     *
     * Emite null también cuando el juego guardado ya no está en el catálogo visible
     * (un juego despublicado con `published = false`): sin título que mostrar, no hay
     * notificación que dar — mejor callar que enseñar un identificador.
     */
    val recordBeaten: Flow<RecordBeatenSignal?> = dataStore.data.map { prefs ->
        val gameId = prefs[recordGameIdKey] ?: return@map null
        val atMs = prefs[recordAtKey] ?: return@map null
        val title = GameCatalog.games.firstOrNull { it.id == gameId }?.title ?: return@map null
        RecordBeatenSignal(gameTitle = title, at = Instant.fromEpochMilliseconds(atMs))
    }

    /** Guarda (reemplazando) el récord pendiente de revancha. */
    suspend fun setRecordBeaten(gameId: String, at: Instant) {
        dataStore.edit {
            it[recordGameIdKey] = gameId
            it[recordAtKey] = at.toEpochMilliseconds()
        }
    }

    /**
     * Olvida el récord pendiente (borrado de cuenta).
     *
     * NO borra el estado de la antesala ni el "ya se pidió el permiso": eso no es
     * progreso del usuario, es historia de lo que el sistema operativo ya nos concedió
     * o denegó en este dispositivo. Reiniciarlo volvería a ofrecer un permiso que el
     * sistema ya no muestra.
     */
    suspend fun clear() {
        dataStore.edit {
            it.remove(recordGameIdKey)
            it.remove(recordAtKey)
        }
    }
}

/**
 * Historia de la oferta de permiso en este dispositivo.
 *
 * @property permissionAsked el diálogo del sistema ya se mostró alguna vez.
 * @property timesDeclined veces que el usuario respondió "ahora no" en la antesala.
 */
data class PrimingState(
    val permissionAsked: Boolean,
    val timesDeclined: Int,
)
