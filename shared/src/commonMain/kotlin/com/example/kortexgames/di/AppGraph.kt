package com.example.kortexgames.di

import com.example.kortexgames.core.ads.AdManager
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.core.audio.createAudioAndHapticManager
import com.example.kortexgames.data.local.DatabaseDriverFactory
import com.example.kortexgames.data.local.SqlDelightLocalAchievementsDataSource
import com.example.kortexgames.data.local.SqlDelightLocalPlayerProgressDataSource
import com.example.kortexgames.data.local.SqlDelightLocalProgressDataSource
import com.example.kortexgames.data.local.createDatabase
import com.example.kortexgames.data.remote.RemoteAchievementsDataSource
import com.example.kortexgames.data.remote.RemotePlayerProgressDataSource
import com.example.kortexgames.data.remote.RemoteProgressDataSource
import com.example.kortexgames.data.remote.auth.GoogleAuthClient
import com.example.kortexgames.data.remote.buildSupabaseClient
import com.example.kortexgames.data.repository.AchievementsRepositoryImpl
import com.example.kortexgames.data.repository.AuthRepositoryImpl
import com.example.kortexgames.data.repository.PlayerProgressRepositoryImpl
import com.example.kortexgames.data.repository.ProgressRepositoryImpl
import com.example.kortexgames.data.settings.OnboardingGate
import com.example.kortexgames.data.settings.SettingsRepository
import com.example.kortexgames.data.settings.createSettingsDataStore
import com.example.kortexgames.game.daily.DailyGoalManager
import com.example.kortexgames.game.daily.DailyGoalStore
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.PlanType
import com.example.kortexgames.domain.repository.AchievementsRepository
import com.example.kortexgames.domain.repository.AuthRepository
import com.example.kortexgames.domain.repository.PlayerProgressRepository
import com.example.kortexgames.domain.repository.ProgressRepository
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
    private val localAchievements =
        SqlDelightLocalAchievementsDataSource(database, Dispatchers.Default)

    // --- Backend Supabase (FASE 2) ------------------------------------------
    val supabaseClient = buildSupabaseClient()
    private val remoteProgress = RemoteProgressDataSource(supabaseClient)
    private val remotePlayerProgress = RemotePlayerProgressDataSource(supabaseClient)
    private val remoteAchievements = RemoteAchievementsDataSource(supabaseClient)

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

    // --- Repositorios local-first -------------------------------------------
    /** Progresión por juego (récord + reanudación), sincronizada con Supabase. */
    val playerProgressRepository: PlayerProgressRepository = PlayerProgressRepositoryImpl(
        local = localPlayerProgress,
        remote = remotePlayerProgress,
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

    // --- Audio & Háptica (nativo, respeta settings) -------------------------
    val audio: AudioAndHapticManager =
        createAudioAndHapticManager(context, settingsRepository).apply { preload() }

    // --- Anuncios: cada 3 min de juego activo si NO es premium --------------
    val adManager = AdManager(
        scope = appScope,
        isPremium = { (authState as? AuthState.Authenticated)?.plan == PlanType.PREMIUM },
    ).also { it.start() }

    // --- Objetivo diario (5 ejercicios/día → recompensa) --------------------
    val dailyGoalManager = DailyGoalManager(
        progress = progressRepository,
        store = DailyGoalStore(preferences),
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
}
