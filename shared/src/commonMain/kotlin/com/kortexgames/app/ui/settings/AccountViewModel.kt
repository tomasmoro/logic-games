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
import com.kortexgames.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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
) : UiState {
    /** Nombre válido para guardar: no vacío tras recortar espacios. */
    val canSaveName: Boolean get() = !isSavingName && displayName.isNotBlank()
}

sealed interface AccountIntent : UiIntent {
    data class NameChanged(val value: String) : AccountIntent
    data object StartEditingName : AccountIntent
    data object CancelEditingName : AccountIntent
    data object SaveName : AccountIntent

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

    init {
        // Precarga el nombre actual desde la sesión; no pisa lo que el usuario ya
        // esté escribiendo si hay una edición en curso.
        authRepository.sessionState
            .onEach { session ->
                if (session is AuthState.Authenticated && !currentState.isEditingName) {
                    setState { copy(displayName = session.displayName.orEmpty()) }
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
