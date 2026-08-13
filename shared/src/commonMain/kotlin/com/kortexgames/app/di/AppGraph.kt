package com.kortexgames.app.di

import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.ads.beginAdConsentFlow
import com.kortexgames.app.core.ads.installPlatformAdPresenters
import com.kortexgames.app.core.isDebugBuild
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.core.audio.createAudioAndHapticManager
import com.kortexgames.app.core.notifications.NotificationCopyProvider
import com.kortexgames.app.core.notifications.NotificationStore
import com.kortexgames.app.core.notifications.NotificationsManager
import com.kortexgames.app.core.notifications.createNotificationScheduler
import com.kortexgames.app.core.startup.StartupPreloader
import com.kortexgames.app.data.local.DatabaseDriverFactory
import com.kortexgames.app.data.local.SqlDelightLocalAchievementsDataSource
import com.kortexgames.app.data.local.SqlDelightLocalLevelTimeDataSource
import com.kortexgames.app.data.local.SqlDelightLocalPlayerProgressDataSource
import com.kortexgames.app.data.local.SqlDelightLocalProgressDataSource
import com.kortexgames.app.data.local.SqlDelightLocalSavedGameStateDataSource
import com.kortexgames.app.data.local.SqlDelightLocalSudokuPuzzleDataSource
import com.kortexgames.app.data.local.createDatabase
import com.kortexgames.app.data.local.db.LogicGamesDb
import com.kortexgames.app.data.remote.RemoteAchievementsDataSource
import com.kortexgames.app.data.remote.RemoteLevelTimeDataSource
import com.kortexgames.app.data.remote.RemotePlayerProgressDataSource
import com.kortexgames.app.data.remote.RemoteProgressDataSource
import com.kortexgames.app.data.remote.RemoteSudokuPuzzleDataSource
import com.kortexgames.app.data.remote.auth.GoogleAuthClient
import com.kortexgames.app.data.remote.buildSupabaseClient
import com.kortexgames.app.data.repository.AchievementsRepositoryImpl
import com.kortexgames.app.data.repository.AuthRepositoryImpl
import com.kortexgames.app.data.repository.PlayerProgressRepositoryImpl
import com.kortexgames.app.data.repository.ProgressRepositoryImpl
import com.kortexgames.app.data.repository.SavedGameStateRepositoryImpl
import com.kortexgames.app.data.repository.SudokuPuzzleRepositoryImpl
import com.kortexgames.app.data.settings.LegalConsentStore
import com.kortexgames.app.data.settings.OnboardingGate
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.data.settings.createSettingsDataStore
import com.kortexgames.app.game.daily.DailyGoalManager
import com.kortexgames.app.game.daily.DailyGoalStore
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.PlanType
import com.kortexgames.app.domain.repository.AchievementsRepository
import com.kortexgames.app.domain.repository.AuthRepository
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.neonsudoku.SudokuPuzzleRepository
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Contenedor de dependencias manual (sin framework de DI). Se instancia una vez
 * por plataforma pasando el [PlatformContext] y vive durante toda la app.
 *
 * Aquí se ve el ensamblaje completo de la FASE 3: settings (DataStore) →
 * StateFlow, base local (SQLDelight), cliente Supabase, repositorio local-first,
 * AudioManager nativo y AdManager.
 */
class AppGraph(context: PlatformContext) {

    /** Scope de aplicación (sobrevive a las pantallas). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * ¿Build de depuración? Lo consulta la UI para mostrar herramientas de
     * diagnóstico (p. ej. el aviso de notificación de prueba en Ajustes) que no deben
     * existir en la app publicada. Se resuelve una vez al arrancar.
     */
    val isDebugBuild: Boolean = isDebugBuild(context)

    /**
     * Snapshot síncrono del estado de sesión. Lo consumen sin suspender
     * [ProgressRepositoryImpl] (¿subir a la nube?) y [AdManager] (¿es premium?).
     * Se mantiene sincronizado observando [AuthRepository.sessionState] (ver init).
     */
    var authState: AuthState = AuthState.Guest
        private set

    // --- DataStore compartido por ajustes y objetivo diario -----------------
    private val preferences = createSettingsDataStore(context)

    // --- Ajustes (DataStore + StateFlow) ------------------------------------
    val settingsRepository = SettingsRepository(
        dataStore = preferences,
        scope = appScope,
    )

    // --- Persistencia local + backend Supabase (FASE 2), en PARALELO --------
    // Abrir el SqlDriver (I/O de disco, y posible migración de esquema) y montar
    // el cliente Ktor de Supabase (Auth + Postgrest + Functions) no dependen entre
    // sí, pero antes se pagaban en SERIE en el constructor de AppGraph — que se
    // ejecuta síncrono en el hilo principal (Application.onCreate en Android, la
    // primera composición en iOS), antes del primer frame. Lanzarlos a la vez en
    // Dispatchers.Default convierte T(db) + T(supabase) en max(T(db), T(supabase)):
    // el hilo llamante sigue bloqueado (ningún repositorio de abajo puede montarse
    // sin ambos), pero por el tiempo del más lento de los dos, no de la suma.
    // Un `runBlocking` local aquí es aceptable —y no un antipatrón de coroutines—
    // porque AppGraph ya es, por diseño, una construcción síncrona de arranque.
    private val database: LogicGamesDb
    val supabaseClient: SupabaseClient

    init {
        val (db, client) = runBlocking(Dispatchers.Default) {
            val dbDeferred = async { createDatabase(DatabaseDriverFactory(context)) }
            val clientDeferred = async { buildSupabaseClient() }
            dbDeferred.await() to clientDeferred.await()
        }
        database = db
        supabaseClient = client
    }

    private val localProgress = SqlDelightLocalProgressDataSource(database, Dispatchers.Default)
    private val localPlayerProgress =
        SqlDelightLocalPlayerProgressDataSource(database, Dispatchers.Default)
    private val localLevelTime =
        SqlDelightLocalLevelTimeDataSource(database, Dispatchers.Default)
    private val localAchievements =
        SqlDelightLocalAchievementsDataSource(database, Dispatchers.Default)
    private val localSavedGameState =
        SqlDelightLocalSavedGameStateDataSource(database, Dispatchers.Default)
    private val localSudokuPuzzle =
        SqlDelightLocalSudokuPuzzleDataSource(database, Dispatchers.Default)

    private val remoteProgress = RemoteProgressDataSource(supabaseClient)
    private val remotePlayerProgress = RemotePlayerProgressDataSource(supabaseClient)
    private val remoteLevelTime = RemoteLevelTimeDataSource(supabaseClient)
    private val remoteAchievements = RemoteAchievementsDataSource(supabaseClient)
    private val remoteSudokuPuzzle = RemoteSudokuPuzzleDataSource(supabaseClient)

    // --- Autenticación (email + Google) -------------------------------------
    /** Seam de plataforma para el login con Google (ID token nativo). */
    private val googleAuthClient = GoogleAuthClient(context)

    /** Repositorio de auth: fuente de verdad reactiva de la sesión. */
    val authRepository: AuthRepository = AuthRepositoryImpl(
        client = supabaseClient,
        googleAuthClient = googleAuthClient,
        scope = appScope,
    )

    /** Recuerda si el usuario ya decidió en la puerta de login (onboarding). */
    val onboardingGate = OnboardingGate(preferences, appScope)

    /** Registro de qué versión de condiciones/privacidad aceptó el usuario. */
    val legalConsentStore = LegalConsentStore(preferences, appScope)

    // --- Repositorios local-first -------------------------------------------
    /** Progresión por juego (récord + reanudación), sincronizada con Supabase. */
    val playerProgressRepository: PlayerProgressRepository = PlayerProgressRepositoryImpl(
        local = localPlayerProgress,
        remote = remotePlayerProgress,
        localLevelTime = localLevelTime,
        remoteLevelTime = remoteLevelTime,
        authState = { authState },
    )

    val progressRepository: ProgressRepository = ProgressRepositoryImpl(
        local = localProgress,
        remote = remoteProgress,
        authState = { authState },
        playerProgress = playerProgressRepository,
        // Seam hacia el módulo de notificaciones (ver KDoc del parámetro). La lambda
        // se evalúa al terminar una partida, cuando `notificationsManager` —declarado
        // más abajo, porque depende de este repositorio— ya está construido.
        onNewRecord = { gameId -> notificationsManager.onRecordBeaten(gameId) },
    )

    /** Logros del jugador (progreso + desbloqueo), sincronizados con Supabase. */
    val achievementsRepository: AchievementsRepository = AchievementsRepositoryImpl(
        local = localAchievements,
        remote = remoteAchievements,
        authState = { authState },
    )

    /** Partida en curso guardada al salir (juegos que lo activan). Solo local. */
    val savedGameStateRepository: SavedGameStateRepository =
        SavedGameStateRepositoryImpl(local = localSavedGameState)

    /** Banco de puzzles de Neon Sudoku Matrix (local-first): caché SQLDelight
     *  sembrada desde el seed empaquetado y enriquecida desde Supabase en segundo
     *  plano. Sustituye a las plantillas que vivían embebidas en el `enum`. */
    val sudokuPuzzleRepository: SudokuPuzzleRepository = SudokuPuzzleRepositoryImpl(
        local = localSudokuPuzzle,
        remote = remoteSudokuPuzzle,
        scope = appScope,
    )

    // --- Audio & Háptica (nativo, respeta settings) -------------------------
    val audio: AudioAndHapticManager =
        createAudioAndHapticManager(context, settingsRepository).apply { preload() }

    // --- Anuncios: cada 3 min de juego activo si NO es premium --------------
    val adManager = AdManager(
        scope = appScope,
        isPremium = { (authState as? AuthState.Authenticated)?.plan == PlanType.PREMIUM },
        // Ni un anuncio durante la bienvenida de la primera apertura: el
        // consentimiento (UMP/ATT) aún no se ha resuelto —pedirlos incumpliría la
        // política de AdMob— y además el primer minuto del jugador debe ser juego.
        adsSuspended = { !onboardingGate.isFirstRunOver.value },
    ).also {
        it.start()
        // Presentadores de anuncios por plataforma: AdMob real en Android (con IDs de
        // prueba hasta publicar); en iOS, simulados hasta integrar el SDK (Parte B). El
        // commonMain no conoce ningún SDK: la elección vive tras este seam expect/actual.
        installPlatformAdPresenters(it, context)
    }

    // --- Objetivo diario (misión de 3 juegos del día → recompensa) ----------
    val dailyGoalManager = DailyGoalManager(
        progress = progressRepository,
        store = DailyGoalStore(preferences),
        scope = appScope,
        // El día en que se jugó el primer juego de la bienvenida: si coincide con
        // "hoy", la misión del día es la propia bienvenida (ver KDoc de
        // DailyGoalManager y de OnboardingGate.firstRunDate) para que terminarla
        // no obligue a jugar tres juegos más el mismo día.
        firstRunDate = onboardingGate.firstRunDate,
    )

    // --- Notificaciones (recordatorios locales de retención) ----------------
    /**
     * Orquestador de notificaciones. Se declara después del objetivo diario porque
     * depende de él (y del historial) para decidir qué avisos programar.
     *
     * `start()` solo abre observadores reactivos: no toca el sistema de
     * notificaciones ni pide permisos hasta que hay algo que programar y el usuario
     * ya lo ha autorizado, así que no añade coste al arranque.
     */
    private val notificationCopy = NotificationCopyProvider()

    /** Persistencia del módulo de notificaciones (récord pendiente de revancha). */
    private val notificationStore = NotificationStore(preferences)

    val notificationsManager = NotificationsManager(
        scheduler = createNotificationScheduler(context, notificationCopy),
        copy = notificationCopy,
        store = notificationStore,
        progress = progressRepository,
        dailyGoal = dailyGoalManager,
        settings = settingsRepository,
        scope = appScope,
    ).also { it.start() }

    /**
     * Precarga de arranque: calienta durante la splash lo que la Home necesita
     * (historial, sesión, puerta de onboarding) y lo comparte para que la pantalla
     * se pinte ya completa. Se declara al final porque depende de casi todo lo
     * anterior.
     */
    val startup = StartupPreloader(
        onboardingGate = onboardingGate,
        authRepository = authRepository,
        progressRepository = progressRepository,
        scope = appScope,
    )

    init {
        // Consentimiento de anuncios (UMP en ambas plataformas + ATT en iOS): se pide
        // en cuanto la primera apertura queda atrás, que es justo antes de que pueda
        // hacer falta el primer anuncio. Para un usuario que ya pasó la bienvenida el
        // flag ya viene a true, así que ocurre en el arranque, como siempre.
        appScope.launch {
            onboardingGate.isFirstRunOver.first { it }
            beginAdConsentFlow(context)
        }

        // La sesión de Supabase manda: al iniciar sesión (o restaurarla al abrir
        // la app) refrescamos el snapshot y subimos lo que se jugó como invitado.
        authRepository.sessionState
            .onEach { state ->
                val wasAuthenticated = authState is AuthState.Authenticated
                authState = state
                if (state is AuthState.Authenticated && !wasAuthenticated) {
                    progressRepository.syncPending()
                    playerProgressRepository.sync()
                    achievementsRepository.sync()
                }
            }
            .launchIn(appScope)
    }

    /** Cierra sesión: Supabase emite el nuevo estado y [authState] vuelve a Guest. */
    suspend fun signOut() {
        authRepository.signOut()
    }

    /**
     * Borra la cuenta de forma permanente: [AuthRepository.deleteAccount] borra el
     * backend (cascada en Supabase) y cierra la sesión; si eso tiene éxito, aquí
     * además vaciamos TODO el rastro local del usuario en este dispositivo
     * (historial, récords, tiempos por nivel, logros, partida en curso y la
     * reclamación del objetivo diario). Es necesario porque la app es
     * local-first: sin esto, el dispositivo seguiría mostrando el progreso de una
     * cuenta que ya no existe en la nube. Si el borrado remoto falla, no se toca
     * nada local (más vale una cuenta que no se pudo borrar que datos huérfanos).
     */
    suspend fun deleteAccount(): Result<Unit> =
        authRepository.deleteAccount().onSuccess {
            progressRepository.clearLocal()
            playerProgressRepository.clearLocal()
            achievementsRepository.clearLocal()
            savedGameStateRepository.clearAll()
            dailyGoalManager.clearClaimedReward()
            // Los avisos pendientes hablan de un progreso que ya no existe ("defiende
            // tu récord de ayer"): se olvidan y se retiran del sistema.
            notificationStore.clear()
            notificationsManager.cancelAll()
        }
}
