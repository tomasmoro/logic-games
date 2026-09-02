package com.kortexgames.app.ui.onboarding

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.DisplayNameRejectedException
import com.kortexgames.app.domain.model.DisplayNameRejection
import com.kortexgames.app.domain.model.DisplayNameRules
import com.kortexgames.app.domain.repository.AuthRepository
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla de elegir nombre. Las reglas salen de [DisplayNameRules],
 * compartidas con el alta por email y con la edición desde Ajustes: antes cada
 * pantalla aplicaba las suyas y un nombre válido en una podía no serlo en otra.
 *
 * No se comprueba unicidad: `public.users.display_name` no la impone (el id real es
 * el UUID de la cuenta).
 */
data class PlayerNameUiState(
    val name: String = "",
    val isSaving: Boolean = false,
    val error: DisplayNameRejection? = null,
    /** El primer intento de guardar con el campo inválido activa el error inline. */
    val submitAttempted: Boolean = false,
) : UiState {
    val trimmed: String get() = name.trim()

    val isValid: Boolean
        get() = DisplayNameRules.validate(name) == null

    val canSave: Boolean get() = !isSaving && isValid
}

sealed interface PlayerNameIntent : UiIntent {
    data class NameChanged(val value: String) : PlayerNameIntent
    data object Save : PlayerNameIntent
}

sealed interface PlayerNameEffect : UiEffect {
    /** El nombre quedó guardado: el host debe continuar a Home. */
    data object Saved : PlayerNameEffect
}

/**
 * ViewModel de [PlayerNameScreen]: fija `public.users.display_name` tras un alta con
 * Google (el perfil nace sin nombre — migración 0048). Es un formulario de un solo
 * campo, así que no comparte estado con [com.kortexgames.app.ui.settings.AccountViewModel]
 * (edición del nombre desde Ajustes, con su propio modo lectura/edición y borrado).
 */
class PlayerNameViewModel(
    private val authRepository: AuthRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<PlayerNameIntent, PlayerNameUiState, PlayerNameEffect>(PlayerNameUiState()) {

    override fun onIntent(intent: PlayerNameIntent) {
        when (intent) {
            is PlayerNameIntent.NameChanged ->
                // Se recorta al máximo al escribir (mismo criterio que el alta por
                // email): el tope se nota antes de invertir esfuerzo.
                setState {
                    copy(name = intent.value.take(DisplayNameRules.MAX_LENGTH), error = null)
                }

            PlayerNameIntent.Save -> save()
        }
    }

    private fun save() {
        val state = currentState
        val localRejection = DisplayNameRules.validate(state.name)
        if (localRejection != null) {
            audio.hapticFeedback(HapticFeedback.LIGHT)
            setState { copy(submitAttempted = true, error = localRejection) }
            return
        }
        audio.playSound(SoundEffect.TAP)
        setState { copy(isSaving = true, error = null) }
        viewModelScope.launch {
            authRepository.updateDisplayName(state.trimmed)
                .onSuccess {
                    audio.playSound(SoundEffect.SUCCESS)
                    audio.hapticFeedback(HapticFeedback.LIGHT)
                    sendEffect(PlayerNameEffect.Saved)
                }
                .onFailure { cause ->
                    // El motivo real (nombre bloqueado, sin red…) viaja tipado en la
                    // excepción del repositorio; cualquier otra cosa es un fallo que
                    // este cliente no sabe explicar.
                    audio.hapticFeedback(HapticFeedback.HEAVY)
                    val reason = (cause as? DisplayNameRejectedException)?.reason
                        ?: DisplayNameRejection.UNKNOWN
                    setState { copy(isSaving = false, error = reason) }
                }
        }
    }
}
