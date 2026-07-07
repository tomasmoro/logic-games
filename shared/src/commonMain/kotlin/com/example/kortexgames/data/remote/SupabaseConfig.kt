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

    /**
     * **Web Client ID** de Google (tipo *Web application* en Google Cloud), el
     * mismo que se registra como "Authorized Client ID" del proveedor Google en
     * Supabase. NO es secreto: viaja en el cliente igual que la publishable key
     * (el *client secret* vive solo en Supabase).
     *
     * Android (Credential Manager) lo usa como `serverClientId` para pedir un ID
     * token que Supabase canjea por sesión. Si está en blanco, el login con Google
     * falla de forma controlada (la UI ofrece email).
     *
     * Pasos para obtenerlo:
     *  1. Google Cloud Console → Credentials → crear OAuth client ID tipo *Web*.
     *  2. Supabase → Auth → Providers → Google: pegar Client ID y Client Secret.
     *  3. Copiar aquí ese Client ID (formato `xxxxx.apps.googleusercontent.com`).
     *  4. Android: crear también un OAuth client ID tipo *Android* con el
     *     package name + huella SHA-1 (no se pega aquí, pero debe existir en el
     *     mismo proyecto de Google Cloud para que Credential Manager funcione).
     */
    const val GOOGLE_WEB_CLIENT_ID = ""
}
