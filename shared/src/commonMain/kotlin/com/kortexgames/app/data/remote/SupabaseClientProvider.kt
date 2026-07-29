package com.kortexgames.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Cliente Supabase único de la app. El motor Ktor se resuelve por plataforma
 * (OkHttp en Android, Darwin en iOS) a través de las dependencias declaradas.
 */
fun buildSupabaseClient(): SupabaseClient = createSupabaseClient(
    supabaseUrl = SupabaseConfig.URL,
    supabaseKey = SupabaseConfig.PUBLISHABLE_KEY,
) {
    install(Auth)        // Google Sign-In / Email-Password (FASE 2)
    install(Postgrest)   // RPC submit_game_result / get_score_percentile
    install(Functions)   // Edge Function game-percentile
}
