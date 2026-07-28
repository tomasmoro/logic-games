package com.example.kortexgames.game.starport

import androidx.lifecycle.viewModelScope
import com.example.kortexgames.core.ads.AdManager
import com.example.kortexgames.core.audio.AudioAndHapticManager
import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.MviViewModel
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.repository.PlayerProgressRepository
import com.example.kortexgames.domain.repository.ProgressRepository
import com.example.kortexgames.game.GameIds
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.LeveledGamePhase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel MVI de "Neon Starport Escape". Juego **LEVELED**: arranca en el
 * selector de niveles y, al elegir uno, se carga del catálogo verificado
 * ([StarportLevels]) y se juega. Al escapar la VIP persiste el resultado
 * (local-first) y el récord de nivel máximo.
 *
 * **Feedback sensorial vía Effects:** igual que Tornillos Neón, el motor emite
 * eventos de dominio ([StarportEvent]) y este ViewModel los **traduce a
 * Effects one-shot** que la pantalla consume y ejecuta. La tabla
 * evento→(sonido, háptica) vive en [onEngineEvent], un único sitio auditable.
 */
class StarportViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<StarportIntent, StarportUiState, StarportEffect>(StarportUiState()) {

    // El motor recibe `audio` por contrato de BaseGameEngine pero NO lo usa:
    // todo el feedback de este juego viaja como Effects (ver KDoc de la clase).
    private val engine = StarportEngine(viewModelScope, audio)

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.events.onEach(::onEngineEvent).launchIn(viewModelScope)
        // Nivel máximo desbloqueado (récord), reactivo y local-first. No se
        // arranca el motor: se empieza en el selector y el jugador elige.
        playerProgress.observe(GameIds.STARPORT_ESCAPE)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: StarportIntent) {
        when (intent) {
            is StarportIntent.PlayLevel -> playLevel(intent.level)
            is StarportIntent.SelectShip -> engine.onShipLifted(intent.shipId)
            is StarportIntent.DragShip -> engine.onShipDragged(intent.shipId, intent.deltaCells)
            is StarportIntent.ReleaseShip -> engine.onShipReleased(intent.shipId)
            StarportIntent.ExitAnimFinished -> engine.onExitAnimFinished()
            StarportIntent.Restart -> engine.restart()
            StarportIntent.Pause -> engine.pause()
            StarportIntent.Resume -> engine.resume()
            StarportIntent.PlayAgain -> playLevel(currentState.currentLevel)
            StarportIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un
                // intersticial pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                playLevel(currentState.currentLevel + 1)
            }
            StarportIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
        }
    }

    /**
     * Tabla única evento de dominio → feedback sensorial. Mantenerla junta (y
     * no repartida por el motor) hace trivial ajustar la "textura" del juego.
     */
    private fun onEngineEvent(event: StarportEvent) {
        when (event) {
            StarportEvent.ShipLifted -> {
                sendEffect(StarportEffect.PlaySound(SoundEffect.TAP))
                sendEffect(StarportEffect.Vibrate(HapticFeedback.LIGHT))
            }
            // El choque es sobre todo táctil (HEAVY = "bump" físico); el SFX de
            // error refuerza sin ser castigo — chocar no es fallo, es información.
            StarportEvent.ShipBumped -> {
                sendEffect(StarportEffect.PlaySound(SoundEffect.ERROR))
                sendEffect(StarportEffect.Vibrate(HapticFeedback.HEAVY))
            }
            StarportEvent.ShipDocked -> {
                sendEffect(StarportEffect.PlaySound(SoundEffect.TAP))
                sendEffect(StarportEffect.Vibrate(HapticFeedback.MEDIUM))
            }
            // Volver al sitio no suena: no pasó nada en términos de juego.
            StarportEvent.ShipSettledBack ->
                sendEffect(StarportEffect.Vibrate(HapticFeedback.LIGHT))
            StarportEvent.VipEscaped -> {
                sendEffect(StarportEffect.PlaySound(SoundEffect.SUCCESS))
                sendEffect(StarportEffect.Vibrate(HapticFeedback.SUCCESS))
                sendEffect(StarportEffect.ShowExitAnim)
            }
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
            // La celebración sonora llega DESPUÉS de la animación de escape
            // (que disparó VipEscaped): acompaña al overlay de resultados.
            sendEffect(StarportEffect.PlaySound(SoundEffect.LEVEL_UP))
            sendEffect(StarportEffect.Vibrate(HapticFeedback.SUCCESS))
            setState { copy(gameOver = GameOverInfo(result, outcome.percentile, outcome.isNewRecord)) }
        }
    }
}
