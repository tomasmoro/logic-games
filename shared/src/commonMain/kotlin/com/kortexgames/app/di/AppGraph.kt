package com.kortexgames.app.di

import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.ads.installPlatformAdPresenters
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.core.audio.createAudioAndHapticManager
import com.kortexgames.app.core.startup.StartupPreloader
import com.kortexgames.app.data.local.DatabaseDriverFactory
import com.kortexgames.app.data.local.SqlDelightLocalAchievementsDataSource
import com.kortexgames.app.data.local.SqlDelightLocalLevelTimeDataSource
import com.kortexgames.app.data.local.SqlDelightLocalPlayerProgressDataSource
import com.kortexgames.app.data.local.SqlDelightLocalProgressDataSource
import com.kortexgames.app.data.local.SqlDelightLocalSavedGameStateDataSource
import com.kortexgames.app.data.local.SqlDelightLocalSudokuPuzzleDataSource
import com.kortexgames.app.data.local.createDatabase
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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

    // --- Persistencia local (fuente de verdad, modo invitado/offline) -------
    private val database = createDatabase(DatabaseDriverFactory(context))
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

    // --- Backend Supabase (FASE 2) ------------------------------------------
    val supabaseClient = buildSupabaseClient()
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
    )

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
        }
}
