package com.kortexgames.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.NicknameOutcome
import com.kortexgames.app.domain.model.NicknameRejection
import com.kortexgames.app.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Estado de la sección "Cuenta" de Ajustes: edición del nombre de usuario y
 * borrado de cuenta. Ambas acciones son independientes, así que cada una lleva
 * su propio par de flags de progreso/error.
 */
data class AccountUiState(
    val displayName: String = "",
    val isEditingName: Boolean = false,
    val isSavingName: Boolean = false,
    val nameError: String? = null,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    // --- Identidad pública (nickname) ---
    val nickname: String = "",
    val isEditingNickname: Boolean = false,
    val isSavingNickname: Boolean = false,
    val isCheckingNickname: Boolean = false,
    /**
     * Veredicto del último intento (comprobación en vivo o guardado). Se guarda el
     * ENUM y no un mensaje ya redactado para que el texto salga de `strings.xml` en
     * la capa Compose (CLAUDE.md §10) y siga siendo traducible.
     */
    val nicknameRejection: NicknameRejection? = null,
    /** Solo con [NicknameRejection.COOLDOWN]: cuándo se podrá volver a cambiar. */
    val nicknameRetryAfter: Instant? = null,
    /** true = la última comprobación en vivo dijo que está libre. */
    val nicknameAvailable: Boolean = false,
) : UiState {
    /** Nombre válido para guardar: no vacío tras recortar espacios. */
    val canSaveName: Boolean get() = !isSavingName && displayName.isNotBlank()

    /**
     * Solo se habilita guardar cuando la comprobación en vivo ha dicho que sí. Es una
     * cortesía de UI, no una garantía: entre comprobar y reclamar, otro jugador puede
     * quedarse el nombre, y de eso responde el índice único del servidor.
     */
    val canSaveNickname: Boolean
        get() = !isSavingNickname && !isCheckingNickname && nicknameAvailable
}

sealed interface AccountIntent : UiIntent {
    data class NameChanged(val value: String) : AccountIntent
    data object StartEditingName : AccountIntent
    data object CancelEditingName : AccountIntent
    data object SaveName : AccountIntent

    data class NicknameChanged(val value: String) : AccountIntent
    data object StartEditingNickname : AccountIntent
    data object CancelEditingNickname : AccountIntent
    data object SaveNickname : AccountIntent

    /** Abre el diálogo de confirmación (primer paso, reversible). */
    data object RequestDeleteAccount : AccountIntent

    /** Confirma en el diálogo: dispara el borrado real (irreversible). */
    data object ConfirmDeleteAccount : AccountIntent
    data object DismissDeleteDialog : AccountIntent
}

sealed interface AccountEffect : UiEffect {
    /** La cuenta se borró y la sesión ya cerró: la pantalla debe salir a Home. */
    data object AccountDeleted : AccountEffect
}

/**
 * ViewModel de gestión de cuenta (nombre + borrado). Vive en `ui/settings`
 * junto a [SettingsViewModel] (preferencias de sonido/háptica) porque ambas
 * alimentan la misma pantalla de Ajustes, pero se mantienen separadas porque
 * una depende de [AuthRepository] y la otra de `SettingsRepository`.
 *
 * @param deleteAccount borra la cuenta. Es [com.kortexgames.app.di.AppGraph.deleteAccount]
 *   (no [AuthRepository.deleteAccount] directamente): el borrado remoto es solo la
 *   mitad del trabajo, también hay que vaciar el rastro local del usuario en este
 *   dispositivo, y esa orquestación entre repositorios vive en `AppGraph`.
 */
class AccountViewModel(
    private val authRepository: AuthRepository,
    private val audio: AudioAndHapticManager,
    private val deleteAccount: suspend () -> Result<Unit>,
) : MviViewModel<AccountIntent, AccountUiState, AccountEffect>(AccountUiState()) {

    /** Comprobación de nickname en vuelo, para poder cancelarla al seguir escribiendo. */
    private var nicknameCheckJob: Job? = null

    init {
        // Precarga el nombre actual desde la sesión; no pisa lo que el usuario ya
        // esté escribiendo si hay una edición en curso.
        authRepository.sessionState
            .onEach { session ->
                if (session is AuthState.Authenticated) {
                    if (!currentState.isEditingName) {
                        setState { copy(displayName = session.displayName.orEmpty()) }
                    }
                    if (!currentState.isEditingNickname) {
                        setState { copy(nickname = session.nickname.orEmpty()) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: AccountIntent) {
        when (intent) {
            is AccountIntent.NameChanged ->
                setState { copy(displayName = intent.value, nameError = null) }

            AccountIntent.StartEditingName -> {
                audio.playSound(SoundEffect.TAP)
                setState { copy(isEditingName = true, nameError = null) }
            }

            AccountIntent.CancelEditingName -> cancelEditingName()
            AccountIntent.SaveName -> saveName()

            is AccountIntent.NicknameChanged -> onNicknameChanged(intent.value)

            AccountIntent.StartEditingNickname -> {
                audio.playSound(SoundEffect.TAP)
                setState { copy(isEditingNickname = true, nicknameRejection = null) }
            }

            AccountIntent.CancelEditingNickname -> cancelEditingNickname()
            AccountIntent.SaveNickname -> saveNickname()

            AccountIntent.RequestDeleteAccount -> {
                audio.playSound(SoundEffect.TAP)
                setState { copy(showDeleteDialog = true, deleteError = null) }
            }

            AccountIntent.DismissDeleteDialog ->
                setState { copy(showDeleteDialog = false, deleteError = null) }

            AccountIntent.ConfirmDeleteAccount -> performDeleteAccount()
        }
    }

    /** Descarta cambios sin guardar y vuelve al nombre que hay en la sesión. */
    private fun cancelEditingName() {
        val saved = (authRepository.sessionState.value as? AuthState.Authenticated)?.displayName.orEmpty()
        setState { copy(isEditingName = false, displayName = saved, nameError = null) }
    }

    private fun saveName() {
        val name = currentState.displayName.trim()
        if (name.isEmpty()) {
            setState { copy(nameError = "El nombre no puede estar vacío") }
            return
        }
        audio.playSound(SoundEffect.TAP)
        setState { copy(isSavingName = true, nameError = null) }
        viewModelScope.launch {
            authRepository.updateDisplayName(name)
                .onSuccess {
                    audio.playSound(SoundEffect.SUCCESS)
                    audio.hapticFeedback(HapticFeedback.LIGHT)
                    setState { copy(displayName = name, isEditingName = false, isSavingName = false) }
                }
                .onFailure {
                    audio.hapticFeedback(HapticFeedback.HEAVY)
                    setState {
                        copy(isSavingName = false, nameError = "No pudimos guardar el nombre. Inténtalo de nuevo.")
                    }
                }
        }
    }

    /**
     * Comprobación de disponibilidad **con debounce**: cada pulsación cancela la
     * consulta anterior y reprograma. Sin esto, escribir "Kortex" dispararía seis
     * RPC y las respuestas podrían llegar desordenadas, dejando el aviso de la
     * penúltima letra sobre el nombre completo.
     */
    private fun onNicknameChanged(value: String) {
        nicknameCheckJob?.cancel()
        setState {
            copy(
                nickname = value,
                nicknameRejection = null,
                nicknameAvailable = false,
                isCheckingNickname = value.isNotBlank(),
            )
        }
        if (value.isBlank()) {
            setState { copy(isCheckingNickname = false) }
            return
        }
        nicknameCheckJob = viewModelScope.launch {
            delay(NICKNAME_CHECK_DEBOUNCE_MS)
            authRepository.checkNicknameAvailable(value.trim())
                .onSuccess { outcome ->
                    setState {
                        copy(
                            isCheckingNickname = false,
                            nicknameAvailable = outcome is NicknameOutcome.Ok,
                            nicknameRejection = (outcome as? NicknameOutcome.Rejected)?.reason,
                        )
                    }
                }
                .onFailure {
                    // Sin red no se puede afirmar que esté libre. Se deja el guardado
                    // deshabilitado en vez de dejar pasar algo que el servidor
                    // rechazaría después con peor experiencia.
                    setState {
                        copy(isCheckingNickname = false, nicknameAvailable = false, nicknameRejection = null)
                    }
                }
        }
    }

    /** Descarta cambios sin guardar y vuelve al nickname que hay en la sesión. */
    private fun cancelEditingNickname() {
        nicknameCheckJob?.cancel()
        val saved = (authRepository.sessionState.value as? AuthState.Authenticated)?.nickname.orEmpty()
        setState {
            copy(
                isEditingNickname = false,
                nickname = saved,
                nicknameRejection = null,
                nicknameAvailable = false,
                isCheckingNickname = false,
            )
        }
    }

    private fun saveNickname() {
        val candidate = currentState.nickname.trim()
        if (candidate.isEmpty()) {
            setState { copy(nicknameRejection = NicknameRejection.EMPTY) }
            return
        }
        nicknameCheckJob?.cancel()
        audio.playSound(SoundEffect.TAP)
        setState { copy(isSavingNickname = true, nicknameRejection = null, isCheckingNickname = false) }
        viewModelScope.launch {
            authRepository.claimNickname(candidate)
                .onSuccess { outcome ->
                    when (outcome) {
                        is NicknameOutcome.Ok -> {
                            audio.playSound(SoundEffect.SUCCESS)
                            audio.hapticFeedback(HapticFeedback.LIGHT)
                            setState {
                                copy(
                                    nickname = outcome.nickname,
                                    isEditingNickname = false,
                                    isSavingNickname = false,
                                    nicknameAvailable = false,
                                )
                            }
                        }
                        // Rechazo esperado (cogido, prohibido, cooldown): no es un
                        // error de la app, así que se queda en edición con el motivo
                        // a la vista en vez de cerrar la tarjeta.
                        is NicknameOutcome.Rejected -> {
                            audio.hapticFeedback(HapticFeedback.HEAVY)
                            setState {
                                copy(
                                    isSavingNickname = false,
                                    nicknameAvailable = false,
                                    nicknameRejection = outcome.reason,
                                    nicknameRetryAfter = outcome.retryAfter,
                                )
                            }
                        }
                    }
                }
                .onFailure {
                    audio.hapticFeedback(HapticFeedback.HEAVY)
                    setState {
                        copy(isSavingNickname = false, nicknameRejection = NicknameRejection.UNKNOWN)
                    }
                }
        }
    }

    private fun performDeleteAccount() {
        audio.hapticFeedback(HapticFeedback.HEAVY)
        setState { copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            deleteAccount()
                .onSuccess {
                    setState { copy(isDeleting = false, showDeleteDialog = false) }
                    sendEffect(AccountEffect.AccountDeleted)
                }
                .onFailure {
                    setState {
                        copy(isDeleting = false, deleteError = "No pudimos borrar la cuenta. Inténtalo de nuevo.")
                    }
                }
        }
    }
}

/**
 * Espera antes de consultar la disponibilidad del nickname. 400 ms es el punto en el
 * que la comprobación se siente inmediata pero ya no se dispara a cada tecla.
 */
private const val NICKNAME_CHECK_DEBOUNCE_MS = 400L
