package com.kortexgames.app.ui.auth

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.data.remote.auth.GoogleSignInUnavailableException
import com.kortexgames.app.data.settings.LegalConsentStore
import com.kortexgames.app.data.settings.OnboardingGate
import com.kortexgames.app.domain.repository.AuthRepository
import kotlinx.coroutines.launch

/** Modo del formulario: entrar con una cuenta existente o crear una nueva. */
enum class AuthMode { SignIn, SignUp }

/**
 * Estado renderizable de la pantalla de login. El formulario (email/contraseña),
 * el modo, el envío en curso, el error y la visibilidad del diálogo de riesgos
 * son **estado** (persisten en recomposición); la salida de la pantalla es un
 * efecto one-shot ([AuthEffect.Finished]).
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val mode: AuthMode = AuthMode.SignIn,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val showGuestRiskDialog: Boolean = false,
    val acceptedLegal: Boolean = false,
    // Se marca al pulsar "Entrar"/"Crear cuenta" con el formulario incompleto o
    // inválido. Antes de ese primer intento no se regaña a un campo intacto (el
    // usuario aún no ha terminado de escribir); a partir de ahí, los errores de
    // campo se recalculan en vivo con cada intent para que desaparezcan solos al
    // corregir.
    val submitAttempted: Boolean = false,
) : UiState {

    /**
     * La aceptación de condiciones **solo se exige al crear cuenta**: quien ya
     * tiene una las aceptó en su día, y volver a pedírselo para entrar sería
     * fricción sin valor legal.
     */
    val requiresLegalAcceptance: Boolean
        get() = mode == AuthMode.SignUp

    /** true si falta marcar la casilla para poder registrarse. */
    val legalBlocksSubmit: Boolean
        get() = requiresLegalAcceptance && !acceptedLegal

    /** El nombre de jugador solo se pide al crear cuenta. */
    val requiresUsername: Boolean
        get() = mode == AuthMode.SignUp

    /**
     * Nombre válido: entre [MIN_USERNAME_LENGTH] y [MAX_USERNAME_LENGTH] tras
     * recortar espacios. No se exige unicidad porque `public.users.display_name`
     * no la impone: es un nombre para mostrar, no un identificador — el id real
     * es el UUID de la cuenta.
     */
    val isUsernameValid: Boolean
        get() = username.trim().length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH

    /**
     * Error de nombre a mostrar bajo el campo. Vacío no se regaña hasta que se
     * intentó enviar (ahí sí, porque el campo es obligatorio); una vez con texto,
     * la longitud se valida en vivo.
     */
    val usernameError: String?
        get() = when {
            !requiresUsername -> null
            username.isEmpty() -> if (submitAttempted) "Escribe un nombre de jugador." else null
            username.trim().length < MIN_USERNAME_LENGTH ->
                "Al menos $MIN_USERNAME_LENGTH caracteres."
            username.trim().length > MAX_USERNAME_LENGTH ->
                "Máximo $MAX_USERNAME_LENGTH caracteres."
            else -> null
        }

    /** Error de email a mostrar bajo el campo, solo tras un intento de envío. */
    val emailError: String?
        get() = when {
            !submitAttempted -> null
            email.isEmpty() -> "Escribe tu email."
            !(email.contains("@") && email.contains(".")) -> "Ese email no es válido."
            else -> null
        }

    /** Error de contraseña a mostrar bajo el campo, solo tras un intento de envío. */
    val passwordError: String?
        get() = when {
            !submitAttempted -> null
            password.isEmpty() -> "Escribe tu contraseña."
            password.length < 6 -> "Al menos 6 caracteres."
            else -> null
        }

    /** Validación mínima para habilitar el botón de enviar. */
    val canSubmit: Boolean
        get() = !isSubmitting && !legalBlocksSubmit &&
            (!requiresUsername || isUsernameValid) &&
            email.contains("@") && email.contains(".") && password.length >= 6

    /**
     * Google también crea cuenta cuando el email no existía, así que en modo
     * "Crea tu cuenta" queda sujeto a la misma casilla que el alta por email.
     */
    val canUseGoogle: Boolean
        get() = !isSubmitting && !legalBlocksSubmit

    companion object {
        /** Mínimo del nombre de jugador: evita nombres de una letra en rankings. */
        const val MIN_USERNAME_LENGTH = 3

        /** Máximo: cabe en una tarjeta de ranking sin truncarse. */
        const val MAX_USERNAME_LENGTH = 20
    }
}

sealed interface AuthIntent : UiIntent {
    data class EmailChanged(val value: String) : AuthIntent
    data class PasswordChanged(val value: String) : AuthIntent
    data class UsernameChanged(val value: String) : AuthIntent
    data object ToggleMode : AuthIntent
    data object SubmitEmail : AuthIntent
    data object SignInWithGoogle : AuthIntent

    /** Marca/desmarca la casilla de aceptación de condiciones y privacidad. */
    data object ToggleLegalAcceptance : AuthIntent

    /** El usuario pulsó "continuar sin cuenta": abre el diálogo de riesgos. */
    data object RequestContinueAsGuest : AuthIntent

    /** Confirmó en el diálogo que asume los riesgos y quiere seguir como invitado. */
    data object ConfirmContinueAsGuest : AuthIntent

    /** Cerró el diálogo de riesgos sin confirmar (se queda en el login). */
    data object DismissGuestDialog : AuthIntent
}

sealed interface AuthEffect : UiEffect {
    /**
     * La pantalla debe cerrarse: el usuario o bien inició sesión, o bien decidió
     * seguir como invitado. En ambos casos la puerta ya quedó marcada como resuelta.
     */
    data object Finished : AuthEffect
}

/**
 * ViewModel de la pantalla de login/onboarding.
 *
 * Orquesta las tres salidas posibles hacia la app: email (entrar/registrar),
 * Google (seam de plataforma) y "continuar como invitado" (con confirmación de
 * riesgos). En **cualquier** salida marca [OnboardingGate.markDecided] para que la
 * bienvenida no se vuelva a mostrar, y emite [AuthEffect.Finished].
 *
 * Al **crear cuenta** exige aceptar condiciones y privacidad, y registra esa
 * aceptación en [LegalConsentStore] solo si la creación tuvo éxito: guardar el
 * consentimiento de un alta que falló dejaría constancia de algo que nunca llegó
 * a ocurrir.
 *
 * Da feedback inmediato (sonido + háptica) en cada intento, según CLAUDE.md §9.4.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val onboardingGate: OnboardingGate,
    private val audio: AudioAndHapticManager,
    private val legalConsent: LegalConsentStore,
) : MviViewModel<AuthIntent, AuthUiState, AuthEffect>(AuthUiState()) {

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.EmailChanged ->
                setState { copy(email = intent.value, error = null) }

            is AuthIntent.PasswordChanged ->
                setState { copy(password = intent.value, error = null) }

            is AuthIntent.UsernameChanged ->
                // Se recorta por el máximo al escribir en vez de dejar teclear de
                // más y rechazar al enviar: el tope se nota antes de invertir
                // esfuerzo. Los espacios se limpian al enviar, no aquí, para no
                // impedir escribir "Ana " + "María".
                setState {
                    copy(username = intent.value.take(AuthUiState.MAX_USERNAME_LENGTH), error = null)
                }

            AuthIntent.ToggleMode -> {
                audio.playSound(SoundEffect.TAP)
                setState {
                    copy(
                        mode = if (mode == AuthMode.SignIn) AuthMode.SignUp else AuthMode.SignIn,
                        error = null,
                        // Los campos que exige cada modo son distintos (p. ej. el
                        // nombre solo en alta): arrastrar errores del modo anterior
                        // confundiría más de lo que ayuda.
                        submitAttempted = false,
                    )
                }
            }

            AuthIntent.ToggleLegalAcceptance -> {
                audio.playSound(SoundEffect.TAP)
                setState { copy(acceptedLegal = !acceptedLegal, error = null) }
            }

            AuthIntent.SubmitEmail -> submitEmail()
            AuthIntent.SignInWithGoogle -> signInWithGoogle()

            AuthIntent.RequestContinueAsGuest -> {
                audio.playSound(SoundEffect.TAP)
                setState { copy(showGuestRiskDialog = true) }
            }

            AuthIntent.DismissGuestDialog ->
                setState { copy(showGuestRiskDialog = false) }

            AuthIntent.ConfirmContinueAsGuest -> continueAsGuest()
        }
    }

    private fun submitEmail() {
        val state = currentState
        if (!state.canSubmit) {
            // El botón sigue respondiendo al toque aunque el formulario esté
            // incompleto: marcar el intento hace que email/contraseña/nombre
            // muestren su error bajo el campo en vez de que el botón se quede
            // mudo (antes simplemente no hacía nada).
            audio.hapticFeedback(HapticFeedback.LIGHT)
            setState { copy(submitAttempted = true) }
            return
        }
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        setState { copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = when (state.mode) {
                AuthMode.SignIn -> authRepository.signInWithEmail(state.email.trim(), state.password)
                AuthMode.SignUp -> authRepository.signUpWithEmail(
                    email = state.email.trim(),
                    password = state.password,
                    displayName = state.username.trim(),
                )
            }
            result
                .onSuccess { finishSuccessfully() }
                .onFailure { fail(emailErrorMessage(state.mode, it)) }
        }
    }

    private fun signInWithGoogle() {
        if (!currentState.canUseGoogle) return
        audio.playSound(SoundEffect.TAP)
        setState { copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            authRepository.signInWithGoogle()
                .onSuccess { finishSuccessfully() }
                .onFailure {
                    // `googleErrorMessage` oculta a propósito el detalle del SDK al usuario
                    // (mensaje genérico); este log es la única forma de ver la causa real
                    // (SHA-1/client id mal registrado, "invalid audience", sin red, etc.) vía
                    // `adb logcat` o Android Vitals de Play Console. Prefijo "KORTEX" para
                    // poder filtrarlo, igual que en `ProgressRepositoryImpl`.
                    println("KORTEX google_sign_in FALLÓ: ${it::class.simpleName}: ${it.message}")
                    fail(googleErrorMessage(it))
                }
        }
    }

    private fun continueAsGuest() {
        viewModelScope.launch {
            onboardingGate.markDecided()
            setState { copy(showGuestRiskDialog = false) }
            sendEffect(AuthEffect.Finished)
        }
    }

    /**
     * Éxito de auth: registra el consentimiento si lo hubo, marca la puerta como
     * resuelta y cierra la pantalla.
     */
    private suspend fun finishSuccessfully() {
        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.MEDIUM)
        if (currentState.acceptedLegal) {
            legalConsent.accept()
        }
        onboardingGate.markDecided()
        sendEffect(AuthEffect.Finished)
    }

    private fun fail(message: String) {
        audio.hapticFeedback(HapticFeedback.HEAVY)
        setState { copy(isSubmitting = false, error = message) }
    }

    /** Traduce fallos de email a mensajes en español, sin filtrar detalles del SDK. */
    private fun emailErrorMessage(mode: AuthMode, error: Throwable): String {
        val raw = error.message?.lowercase().orEmpty()
        return when {
            "invalid" in raw && "credential" in raw ->
                "Email o contraseña incorrectos."
            "already" in raw && "registered" in raw ->
                "Ese email ya tiene una cuenta. Inicia sesión."
            "network" in raw || "timeout" in raw || "connect" in raw ->
                "Sin conexión. Revisa tu internet e inténtalo de nuevo."
            mode == AuthMode.SignIn ->
                "No pudimos iniciar sesión. Revisa tus datos."
            else ->
                "No pudimos crear la cuenta. Inténtalo de nuevo."
        }
    }

    /** Mensaje amable cuando Google no está disponible o el usuario cancela. */
    private fun googleErrorMessage(error: Throwable): String = when (error) {
        is GoogleSignInUnavailableException ->
            "Google no está disponible ahora. Puedes entrar con email."
        else ->
            "No pudimos entrar con Google. Inténtalo de nuevo."
    }
}
