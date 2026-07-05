package com.example.kortexgames.di

import com.example.kortexgames.core.ads.AdManager
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.core.audio.createAudioAndHapticManager
import com.example.kortexgames.data.local.DatabaseDriverFactory
import com.example.kortexgames.data.local.SqlDelightLocalProgressDataSource
import com.example.kortexgames.data.local.createDatabase
import com.example.kortexgames.data.remote.RemoteProgressDataSource
import com.example.kortexgames.data.remote.buildSupabaseClient
import com.example.kortexgames.data.repository.ProgressRepositoryImpl
import com.example.kortexgames.data.settings.SettingsRepository
import com.example.kortexgames.data.settings.createSettingsDataStore
import com.example.kortexgames.game.daily.DailyGoalManager
import com.example.kortexgames.game.daily.DailyGoalStore
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.PlanType
import com.example.kortexgames.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Estado de sesión mutable. Se actualiza al hacer login/logout. */
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

    // --- Backend Supabase (FASE 2) ------------------------------------------
    val supabaseClient = buildSupabaseClient()
    private val remoteProgress = RemoteProgressDataSource(supabaseClient)

    // --- Repositorio local-first --------------------------------------------
    val progressRepository: ProgressRepository = ProgressRepositoryImpl(
        local = localProgress,
        remote = remoteProgress,
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

    /** Llamar tras login: fija sesión y sincroniza lo pendiente. */
    suspend fun onAuthenticated(userId: String, plan: PlanType) {
        authState = AuthState.Authenticated(userId, plan)
        progressRepository.syncPending()
    }

    fun onSignedOut() {
        authState = AuthState.Guest
    }
}
