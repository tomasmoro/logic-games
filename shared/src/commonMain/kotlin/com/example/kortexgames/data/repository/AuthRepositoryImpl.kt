package com.example.kortexgames.data.repository

import com.example.kortexgames.data.remote.auth.GoogleAuthClient
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.PlanType
import com.example.kortexgames.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Implementación de [AuthRepository] sobre **Supabase Auth**.
 *
 * Decisiones:
 *  - La sesión se deriva de `auth.sessionStatus` (Supabase persiste y refresca el
 *    token por su cuenta), así que al reabrir la app el usuario sigue logueado sin
 *    lógica extra por nuestra parte.
 *  - El plan (`FREE`/`PREMIUM`) se resuelve leyendo `public.users.plan_type` en
 *    cuanto hay sesión (RLS restringe a la fila propia). Se valida contra
 *    `premium_until`: un premium caducado degrada a [PlanType.FREE] aunque la
 *    columna siga en `'premium'`. Ante cualquier fallo (offline, fila aún no
 *    creada) se cae a [PlanType.FREE] para no romper el login ni el arranque
 *    offline; el peor caso es que un premium sin red vea anuncios hasta que la
 *    sesión se reemita (refresco de token) con red disponible.
 *  - Google delega en [GoogleAuthClient] (seam de plataforma) para obtener el ID
 *    token y aquí solo se canjea por sesión — así el flujo común no conoce SDKs
 *    nativos.
 *
 * @param scope scope de aplicación donde se mantiene "caliente" el [sessionState].
 */
class AuthRepositoryImpl(
    private val client: SupabaseClient,
    private val googleAuthClient: GoogleAuthClient,
    scope: CoroutineScope,
) : AuthRepository {

    override val sessionState: StateFlow<AuthState> =
        client.auth.sessionStatus
            // `map` acepta un transform suspend: resolvemos el plan (una lectura a
            // `public.users`) por cada emisión de sesión. Las emisiones son escasas
            // (login/logout/refresco de token), así que el coste es despreciable y
            // además revalida el plan en cada refresco.
            .map { it.toAuthState() }
            .stateIn(scope, SharingStarted.Eagerly, AuthState.Guest)

    override suspend fun signInWithEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signUpWithEmail(email: String, password: String): Result<Unit> =
        // Si el proyecto exige confirmación por correo, `signUpWith` no crea sesión
        // todavía; el usuario queda como invitado hasta confirmar y podrá iniciar
        // sesión con el botón del Home/Perfil. Aun así lo tratamos como éxito.
        runCatching {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
        }.map { }

    override suspend fun signInWithGoogle(): Result<Unit> {
        // 1) Token nativo (o fallo controlado si el seam aún no está cableado).
        val credential = googleAuthClient.requestIdToken().getOrElse { return Result.failure(it) }
        // 2) Canje del ID token por una sesión de Supabase.
        return runCatching {
            client.auth.signInWith(IDToken) {
                idToken = credential.idToken
                provider = Google
                nonce = credential.rawNonce
            }
        }
    }

    override suspend fun signOut() {
        runCatching { client.auth.signOut() }
    }

    /**
     * Proyecta el estado del SDK de Supabase al modelo de dominio. Solo la sesión
     * autenticada con id de usuario válido cuenta como [AuthState.Authenticated];
     * cualquier otro estado (inicializando, sin sesión, fallo de refresco) se trata
     * como invitado para que la app siempre tenga un estado usable y offline.
     *
     * Es `suspend` porque para el caso autenticado consulta el plan real en la BD.
     */
    private suspend fun SessionStatus.toAuthState(): AuthState = when (this) {
        is SessionStatus.Authenticated -> {
            val userId = session.user?.id
            if (userId != null) AuthState.Authenticated(userId, fetchPlan(userId)) else AuthState.Guest
        }
        else -> AuthState.Guest
    }

    /**
     * Lee `plan_type`/`premium_until` de la fila propia en `public.users` y los
     * resuelve a [PlanType]. La RLS ya restringe el `select` a la fila del usuario
     * autenticado, pero filtramos por `id` igualmente por claridad y para forzar la
     * fila única esperada. Cualquier error (offline, fila aún no propagada por el
     * trigger) degrada a [PlanType.FREE]: preferimos mostrar anuncios de más antes
     * que bloquear el arranque.
     */
    private suspend fun fetchPlan(userId: String): PlanType = runCatching {
        client.postgrest.from("users")
            .select(Columns.list("plan_type", "premium_until")) {
                filter { eq("id", userId) }
            }
            .decodeSingleOrNull<UserPlanRow>()
            ?.toPlanType()
            ?: PlanType.FREE
    }.getOrDefault(PlanType.FREE)

    /** Proyección mínima de `public.users` para resolver el plan. */
    @Serializable
    private data class UserPlanRow(
        @SerialName("plan_type") val planType: String,
        @SerialName("premium_until") val premiumUntil: Instant? = null,
    ) {
        /**
         * `plan_type` es la fuente, pero se **valida** contra `premium_until` (ver
         * comentario de la columna en `0001_initial_schema.sql`): un premium con
         * fecha ya pasada cuenta como [PlanType.FREE]. `premium_until` nulo con plan
         * `'premium'` se interpreta como premium sin caducidad (p. ej. concedido
         * manualmente), no como ausencia de suscripción.
         */
        fun toPlanType(): PlanType {
            if (planType != "premium") return PlanType.FREE
            val until = premiumUntil ?: return PlanType.PREMIUM
            return if (until > Clock.System.now()) PlanType.PREMIUM else PlanType.FREE
        }
    }
}
