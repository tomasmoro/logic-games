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
    val mode: AuthMode = AuthMode.SignIn,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val showGuestRiskDialog: Boolean = false,
) : UiState {
    /** Validación mínima para habilitar el botón de enviar. */
    val canSubmit: Boolean
        get() = !isSubmitting && email.contains("@") && email.contains(".") && password.length >= 6
}

sealed interface AuthIntent : UiIntent {
    data class EmailChanged(val value: String) : AuthIntent
    data class PasswordChanged(val value: String) : AuthIntent
    data object ToggleMode : AuthIntent
    data object SubmitEmail : AuthIntent
    data object SignInWithGoogle : AuthIntent

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
 * Da feedback inmediato (sonido + háptica) en cada intento, según CLAUDE.md §9.4.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val onboardingGate: OnboardingGate,
    private val audio: AudioAndHapticManager,
) : MviViewModel<AuthIntent, AuthUiState, AuthEffect>(AuthUiState()) {

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.EmailChanged ->
                setState { copy(email = intent.value, error = null) }

            is AuthIntent.PasswordChanged ->
                setState { copy(password = intent.value, error = null) }

            AuthIntent.ToggleMode -> {
                audio.playSound(SoundEffect.TAP)
                setState {
                    copy(
                        mode = if (mode == AuthMode.SignIn) AuthMode.SignUp else AuthMode.SignIn,
                        error = null,
                    )
                }
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
        if (!state.canSubmit) return
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        setState { copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = when (state.mode) {
                AuthMode.SignIn -> authRepository.signInWithEmail(state.email.trim(), state.password)
                AuthMode.SignUp -> authRepository.signUpWithEmail(state.email.trim(), state.password)
            }
            result
                .onSuccess { finishSuccessfully() }
                .onFailure { fail(emailErrorMessage(state.mode, it)) }
        }
    }

    private fun signInWithGoogle() {
        audio.playSound(SoundEffect.TAP)
        setState { copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            authRepository.signInWithGoogle()
                .onSuccess { finishSuccessfully() }
                .onFailure { fail(googleErrorMessage(it)) }
        }
    }

    private fun continueAsGuest() {
        viewModelScope.launch {
            onboardingGate.markDecided()
            setState { copy(showGuestRiskDialog = false) }
            sendEffect(AuthEffect.Finished)
        }
    }

    /** Éxito de auth: marca la puerta como resuelta y cierra la pantalla. */
    private suspend fun finishSuccessfully() {
        audio.playSound(SoundEffect.SUCCESS)
        audio.hapticFeedback(HapticFeedback.MEDIUM)
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
