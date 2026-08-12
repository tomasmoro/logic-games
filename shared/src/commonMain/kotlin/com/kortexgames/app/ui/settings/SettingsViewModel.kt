package com.kortexgames.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.core.notifications.NotificationsManager
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.data.settings.SettingsRepository
import com.kortexgames.app.data.settings.UserSettings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * @property notificationsBlocked el usuario quiso activar los recordatorios pero el
 *   sistema no lo permite (denegó el permiso o los tiene desactivados en los ajustes
 *   del móvil). Se refleja en la UI para explicar por qué el interruptor no se queda
 *   encendido; sin esto, el toque parecería simplemente no funcionar.
 */
/**
 * @property testNotificationResult resultado del último aviso de prueba (solo
 *   depuración): true = programado, false = el sistema no lo permite, null = aún no
 *   se ha pulsado.
 */
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val notificationsBlocked: Boolean = false,
    val testNotificationResult: Boolean? = null,
) : UiState {

    /**
     * Estado **real** de los recordatorios: encendidos solo si el usuario los quiere
     * Y el sistema deja notificar. Es lo que pinta el interruptor y lo que se alterna
     * al pulsarlo — la preferencia por sí sola mentiría (nace en true aunque el
     * permiso no exista todavía).
     */
    val remindersActive: Boolean get() = settings.areRemindersEnabled && !notificationsBlocked
}

sealed interface SettingsIntent : UiIntent {
    data object ToggleSfx : SettingsIntent
    data object ToggleMusic : SettingsIntent
    data object ToggleHaptics : SettingsIntent

    /** Activa/desactiva los recordatorios (pide el permiso del sistema si hace falta). */
    data object ToggleReminders : SettingsIntent

    /** Solo depuración: programa el aviso de prueba a 15 segundos. */
    data object SendTestNotification : SettingsIntent
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
    private val notifications: NotificationsManager,
) : MviViewModel<SettingsIntent, SettingsUiState, SettingsEffect>(SettingsUiState()) {

    init {
        settingsRepository.settings
            .onEach { setState { copy(settings = it) } }
            .launchIn(viewModelScope)

        // El permiso puede cambiar fuera de la app (ajustes del sistema): se consulta
        // al entrar para que el interruptor no mienta sobre lo que va a pasar.
        viewModelScope.launch {
            val allowed = notifications.areNotificationsAllowed()
            setState { copy(notificationsBlocked = !allowed && settings.areRemindersEnabled) }
        }
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
                // Se alterna sobre lo que el usuario VE encendido, no sobre la
                // preferencia cruda: en el primer arranque la preferencia nace en
                // true pero el sistema aún no ha dado permiso, así que el interruptor
                // se pinta apagado. Negar la preferencia ahí lo dejaría en false y el
                // primer toque —el que debía pedir el permiso— no habría hecho nada
                // visible, obligando a tocar dos veces.
                SettingsIntent.ToggleReminders -> toggleReminders(!currentState.remindersActive)
                SettingsIntent.SendTestNotification -> {
                    val scheduled = notifications.scheduleTestNotification()
                    setState { copy(testNotificationResult = scheduled) }
                }
            }
        }
    }

    /**
     * Al **encender** los recordatorios se pide el permiso del sistema si aún no lo
     * hay: es el gesto explícito que ambas tiendas esperan como disparador (y en iOS
     * es la única oportunidad de mostrar el diálogo). Si el usuario lo deniega, la
     * preferencia se guarda igualmente encendida y se marca [SettingsUiState.notificationsBlocked]:
     * así, si más tarde concede el permiso desde los ajustes del móvil, los
     * recordatorios funcionan sin tener que volver aquí a reactivarlos.
     *
     * Al **apagarlos** no se pide nada: el manager cancela lo pendiente en cuanto ve
     * la preferencia a false.
     */
    private suspend fun toggleReminders(enabled: Boolean) {
        settingsRepository.setRemindersEnabled(enabled)
        if (!enabled) {
            setState { copy(notificationsBlocked = false) }
            return
        }
        val granted = notifications.requestPermission()
        setState { copy(notificationsBlocked = !granted) }
    }
}
