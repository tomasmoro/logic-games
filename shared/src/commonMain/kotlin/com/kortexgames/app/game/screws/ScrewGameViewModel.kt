package com.kortexgames.app.game.screws

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.LeveledGamePhase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Tornillos Neón". Juego **LEVELED**: arranca en el selector
 * de niveles y, al elegir uno, el motor lo genera de forma determinista (semilla
 * = nivel) y se juega. Al despejar el tablero persiste el resultado
 * (local-first) y el récord de nivel máximo.
 *
 * **Feedback sensorial vía Effects (excepción consciente):** a diferencia de los
 * demás juegos —donde el motor llama a [AudioAndHapticManager] directamente—,
 * aquí (decisión del usuario para este juego) el motor emite eventos de dominio
 * ([ScrewEvent]) y este ViewModel los **traduce a Effects one-shot**
 * ([ScrewGameEffect.PlaySound] / [ScrewGameEffect.Vibrate]) que la pantalla
 * consume y ejecuta. La tabla evento→(sonido, háptica) vive en [onEngineEvent],
 * un único sitio auditable.
 */
class ScrewGameViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<ScrewGameIntent, ScrewGameUiState, ScrewGameEffect>(ScrewGameUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa:
    // todo el feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = ScrewGameEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Nivel máximo desbloqueado (récord), reactivo y local-first. No se
        // arranca el motor: se empieza en el selector y el jugador elige.
        playerProgress.observe(GameIds.NEON_SCREWS)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: ScrewGameIntent) {
        when (intent) {
            is ScrewGameIntent.SelectScrew -> engine.onScrewTap(intent.screwId)
            is ScrewGameIntent.PlaceScrew -> engine.onHoleTap(intent.holeId)
            ScrewGameIntent.CancelSelection -> engine.clearSelection()
            is ScrewGameIntent.PlateFallFinished -> engine.onPlateFallFinished(intent.plateId)
            ScrewGameIntent.Restart -> engine.restart()
            ScrewGameIntent.Pause -> engine.pause()
            ScrewGameIntent.Resume -> engine.resume()
            is ScrewGameIntent.PlayLevel -> playLevel(intent.level)
            ScrewGameIntent.PlayAgain -> playLevel(currentState.currentLevel)
            ScrewGameIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            ScrewGameIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y no
     * repartida por el motor) hace trivial ajustar la "textura" del juego.
     */
    private fun onEngineEvent(event: ScrewEvent) {
        when (event) {
            ScrewEvent.ScrewSelected -> {
                sendEffect(ScrewGameEffect.PlaySound(SoundEffect.TAP))
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.LIGHT))
            }
            ScrewEvent.SelectionCleared ->
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.LIGHT))
            ScrewEvent.ScrewPlaced -> {
                sendEffect(ScrewGameEffect.PlaySound(SoundEffect.TAP))
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.MEDIUM))
            }
            ScrewEvent.PlacementRejected -> {
                sendEffect(ScrewGameEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.ERROR))
            }
            // El "crac" de quedarse colgando es táctil, no sonoro: un SFX aquí
            // competiría con el de la colocación que lo causó.
            ScrewEvent.PlateStartedHanging ->
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.MEDIUM))
            ScrewEvent.PlateReleased -> {
                sendEffect(ScrewGameEffect.PlaySound(SoundEffect.SUCCESS))
                sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.HEAVY))
            }
            // La celebración sonora de victoria se emite en onFinished, tras
            // persistir: así acompaña a la aparición del overlay de resultados.
            ScrewEvent.BoardCleared -> Unit
        }
    }

    /** Empieza (o reempieza) un nivel: limpia el game-over y arranca el motor. */
    private fun playLevel(level: Int) {
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = level, gameOver = null) }
        engine.startAtLevel(level)
    }

    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            sendEffect(ScrewGameEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(ScrewGameEffect.Vibrate(HapticFeedback.SUCCESS))
            sendEffect(ScrewGameEffect.ShowLevelComplete)
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }
}
