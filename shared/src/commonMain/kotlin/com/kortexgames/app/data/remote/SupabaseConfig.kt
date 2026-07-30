package com.kortexgames.app.data.remote

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
     *
     * El valor real NO se hardcodea aquí: lo inyecta la tarea Gradle `generateSecrets`
     * desde `secrets.properties` (gitignored). Vacío ⇒ login con Google deshabilitado
     * de forma controlada. Ver `secrets.properties.example` y `docs/google-signin-setup.md`.
     */
    const val GOOGLE_WEB_CLIENT_ID = Secrets.GOOGLE_WEB_CLIENT_ID

    /**
     * **iOS OAuth Client ID** de Google (tipo *iOS* en Google Cloud, formato
     * `xxxx.apps.googleusercontent.com`). Tampoco es secreto (identifica al cliente,
     * no lo autoriza: PKCE reemplaza al *client secret* en apps instaladas).
     *
     * Lo usa el `actual` de iOS ([com.kortexgames.app.data.remote.auth.GoogleAuthClient])
     * en el flujo *Authorization Code + PKCE* sobre `ASWebAuthenticationSession`. Del
     * client id se deriva el esquema de redirección (*reversed client id*). Si está
     * en blanco, el login con Google en iOS falla de forma controlada (la UI ofrece
     * email).
     *
     * Pasos:
     *  1. Google Cloud → Credentials → crear OAuth client ID tipo *iOS* con el
     *     *bundle id* de la app (`iosApp/Configuration/Config.xcconfig`).
     *  2. Supabase → Auth → Providers → Google: añadir este client id a
     *     *Authorized Client IDs* (GoTrue valida el `aud` del ID token contra la lista).
     *  3. Copiar aquí el Client ID (`xxxx.apps.googleusercontent.com`).
     *
     * Igual que el Web Client ID, el valor lo inyecta `generateSecrets` desde
     * `secrets.properties`; no se hardcodea ni se commitea.
     */
    const val GOOGLE_IOS_CLIENT_ID = Secrets.GOOGLE_IOS_CLIENT_ID
}
