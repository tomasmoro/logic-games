package com.kortexgames.app.data.repository

import com.kortexgames.app.data.remote.auth.GoogleAuthClient
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.DisplayNameRejectedException
import com.kortexgames.app.domain.model.DisplayNameRejection
import com.kortexgames.app.domain.model.PlanType
import com.kortexgames.app.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /**
     * Se apoya en [SessionStatus.Initializing], el único estado del SDK que
     * significa "aún estoy leyendo la sesión de disco". Cualquier otro
     * (autenticado, no autenticado o fallo de refresco) ya es una respuesta
     * definitiva para la UI.
     */
    override val sessionResolved: StateFlow<Boolean> =
        client.auth.sessionStatus
            .map { it !is SessionStatus.Initializing }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun signInWithEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
        displayName: String,
    ): Result<Unit> =
        // Si el proyecto exige confirmación por correo, `signUpWith` no crea sesión
        // todavía; el usuario queda como invitado hasta confirmar y podrá iniciar
        // sesión con el botón del Home/Perfil. Aun así lo tratamos como éxito.
        runCatching {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                // `data` acaba en `auth.users.raw_user_meta_data`, de donde el
                // trigger `handle_new_user` (migración 0026) lo copia a
                // `public.users.display_name` al crear el perfil.
                data = buildJsonObject {
                    put("display_name", displayName)
                }
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

    override suspend fun updateDisplayName(name: String): Result<Unit> {
        // NO se comprueba la sesión aquí a propósito. `sessionState` va por detrás
        // del SDK: su `map` es suspend y resuelve el plan con una lectura a
        // `public.users`, así que justo tras un login sigue valiendo `Guest` durante
        // el viaje de esa consulta — y esta pantalla aparece precisamente en ese
        // instante. Una guarda local ahí rechazaba el guardado con "sesión caducada"
        // aunque la sesión fuera perfectamente válida (mismo motivo por el que
        // `currentDisplayName` lee el `sessionStatus` crudo). Quien decide es la RPC:
        // resuelve el usuario con `auth.uid()` sobre el token que el SDK ya adjunta,
        // y devuelve `no_session` si de verdad no hay ninguna.
        //
        // Vía RPC y no con un `update` directo a la tabla porque desde la migración
        // 0049 `authenticated` ya NO tiene UPDATE sobre `public.users`: la RPC es el
        // único camino de escritura, y es donde viven la blocklist y las reglas de
        // forma. El PATCH directo que había aquí antes se saltaba ambas.
        return runCatching {
            client.postgrest.rpc(
                function = "set_display_name",
                parameters = SetDisplayNameParams(name = name.trim()),
            ).decodeAsOrNull<SetDisplayNameResponse>()
        }.fold(
            onSuccess = { response ->
                when {
                    response == null -> Result.failure(
                        DisplayNameRejectedException(DisplayNameRejection.UNKNOWN)
                    )
                    response.ok -> Result.success(Unit)
                    else -> {
                        // El mensaje que ve el usuario es deliberadamente genérico,
                        // así que este log (prefijo KORTEX, igual que en
                        // `signInWithGoogle`) es la única forma de ver por logcat qué
                        // código devolvió realmente la RPC.
                        println("KORTEX set_display_name rechazó: ${response.reason}")
                        Result.failure(
                            DisplayNameRejectedException(DisplayNameRejection.fromCode(response.reason))
                        )
                    }
                }
            },
            // Fallo de transporte (sin red, 5xx). Se conserva la causa original para
            // que el log siga siendo útil aunque la UI muestre un mensaje genérico.
            onFailure = { cause ->
                Result.failure(DisplayNameRejectedException(DisplayNameRejection.UNKNOWN, cause))
            },
        )
    }

    override suspend fun currentDisplayName(): String? {
        // Se lee del `sessionStatus` CRUDO del SDK (actualizado sincrónicamente por
        // `signInWith`), no de nuestro `sessionState` mapeado: este método existe
        // precisamente para el instante en el que ese flujo aún no ha reflejado el
        // perfil recién creado por el trigger tras un alta con Google.
        val status = client.auth.sessionStatus.value
        val userId = (status as? SessionStatus.Authenticated)?.session?.user?.id ?: return null
        return runCatching { fetchProfile(userId).displayName }.getOrNull()
    }

    override suspend fun deleteAccount(): Result<Unit> {
        if (sessionState.value !is AuthState.Authenticated) {
            return Result.failure(IllegalStateException("No hay sesión activa"))
        }
        // La Edge Function reenvía el JWT del llamador; con service_role borra
        // auth.users(id) del propio usuario y el ON DELETE CASCADE del esquema se
        // lleva por delante public.users y todo lo que cuelga de su id.
        return runCatching {
            client.functions.invoke("delete-account")
        }.map { }.onSuccess {
            runCatching { client.auth.signOut() }
        }
    }

    /**
     * Proyecta el estado del SDK de Supabase al modelo de dominio. Solo la sesión
     * autenticada con id de usuario válido cuenta como [AuthState.Authenticated];
     * cualquier otro estado (inicializando, sin sesión, fallo de refresco) se trata
     * como invitado para que la app siempre tenga un estado usable y offline.
     *
     * Es `suspend` porque para el caso autenticado consulta el perfil real en la BD.
     */
    private suspend fun SessionStatus.toAuthState(): AuthState = when (this) {
        is SessionStatus.Authenticated -> {
            val userId = session.user?.id
            if (userId != null) {
                val profile = fetchProfile(userId)
                AuthState.Authenticated(
                    userId = userId,
                    plan = profile.toPlanType(),
                    displayName = profile.displayName,
                )
            } else {
                AuthState.Guest
            }
        }
        else -> AuthState.Guest
    }

    /**
     * Lee `plan_type`/`premium_until`/`display_name` de la fila propia en
     * `public.users`. La RLS ya restringe el `select` a la fila del usuario
     * autenticado, pero filtramos por `id` igualmente por claridad y para forzar la
     * fila única esperada. Cualquier error (offline, fila aún no propagada por el
     * trigger) degrada a un perfil FREE sin nombre: preferimos mostrar anuncios de
     * más antes que bloquear el arranque.
     */
    private suspend fun fetchProfile(userId: String): UserProfileRow = runCatching {
        client.postgrest.from("users")
            .select(Columns.list("plan_type", "premium_until", "display_name")) {
                filter { eq("id", userId) }
            }
            .decodeSingleOrNull<UserProfileRow>()
            ?: UserProfileRow(planType = "free")
    }.getOrDefault(UserProfileRow(planType = "free"))

    /** Proyección mínima de `public.users` para resolver el plan y el nombre. */
    @Serializable
    private data class UserProfileRow(
        @SerialName("plan_type") val planType: String,
        @SerialName("premium_until") val premiumUntil: Instant? = null,
        @SerialName("display_name") val displayName: String? = null,
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

    /**
     * Parámetro de `set_display_name`. El nombre del campo tiene que coincidir con
     * el de la función SQL (`p_name`): PostgREST casa los parámetros por nombre.
     */
    @Serializable
    private data class SetDisplayNameParams(
        @SerialName("p_name") val name: String,
    )

    /**
     * Respuesta de `set_display_name`. `reason` solo viene cuando `ok` es false, y
     * es un CÓDIGO (`too_short`, `blocked`…), no un mensaje: el texto de UI sale de
     * `strings.xml` (CLAUDE.md §10).
     */
    @Serializable
    private data class SetDisplayNameResponse(
        val ok: Boolean,
        val reason: String? = null,
    )
}
