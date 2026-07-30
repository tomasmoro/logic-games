package com.kortexgames.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.data.settings.UserSettings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
) : UiState

sealed interface SettingsIntent : UiIntent {
    data object ToggleSfx : SettingsIntent
    data object ToggleMusic : SettingsIntent
    data object ToggleHaptics : SettingsIntent
}

sealed interface SettingsEffect : UiEffect

/**
 * Ejemplo canónico del patrón MVI: observa el StateFlow de [SettingsRepository]
 * y lo proyecta al estado de UI; cada intent persiste el cambio y da feedback
 * inmediato (sonido + háptica).
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val audio: AudioAndHapticManager,
) : MviViewModel<SettingsIntent, SettingsUiState, SettingsEffect>(SettingsUiState()) {

    init {
        settingsRepository.settings
            .onEach { setState { copy(settings = it) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: SettingsIntent) {
        audio.playSound(SoundEffect.TAP)
        audio.hapticFeedback(HapticFeedback.LIGHT)
        viewModelScope.launch {
            val s = currentState.settings
            when (intent) {
                SettingsIntent.ToggleSfx -> settingsRepository.setSfxEnabled(!s.isSfxEnabled)
                SettingsIntent.ToggleMusic -> settingsRepository.setMusicEnabled(!s.isMusicEnabled)
                SettingsIntent.ToggleHaptics -> settingsRepository.setHapticsEnabled(!s.isHapticsEnabled)
            }
        }
    }
}
