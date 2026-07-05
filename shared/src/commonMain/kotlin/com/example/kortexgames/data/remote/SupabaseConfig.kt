package com.example.kortexgames.data.remote

/**
 * Configuración del backend (FASE 2). Estos valores son PÚBLICOS por diseño:
 * la publishable/anon key solo permite operaciones sujetas a RLS. Los secretos
 * (service_role) NUNCA van en el cliente.
 *
 * En producción conviene inyectarlos vía BuildConfig/secrets para poder rotar
 * sin recompilar el código fuente.
 */
object SupabaseConfig {
    const val URL = "https://pfjsacrxtutrkcsybaxh.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_V07UBE_qhRT6WAMFmZswFg_5C9d0bXu"
}
