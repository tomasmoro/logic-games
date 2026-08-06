package com.kortexgames.app.game.quantummerge

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * # QuantumMergeViewModel — puente MVI de Quantum Merge (Fase 2)
 *
 * Une el [QuantumMergeEngine] (física de contactos y fusión, portable y testeable) con el contrato
 * MVI de la pantalla. Sigue el mismo molde que `HypergateViewModel`:
 *  - refleja `engine.state`/`engine.status` en el [QuantumMergeUiState],
 *  - al terminar la partida guarda el resultado (local-first) antes de abrir el overlay final.
 *
 * ## Por qué el bucle de física NO vive aquí
 * Podría: el ViewModel tiene `viewModelScope` y podría lanzar un `Job` a 60 fps. Pero el proyecto
 * ya resuelve esto mejor con `BaseGameEngine` + `withFrameNanos`, y por dos motivos que importan:
 * `BaseGameEngine` aporta gratis el ciclo IDLE→RUNNING↔PAUSED→FINISHED, el **cronómetro que
 * descuenta pausas** (del que sale el `completion_time_ms` de la partida) y la construcción del
 * `GameResult`; y un temporizador propio de coroutines correría a un ritmo distinto del de la
 * pantalla, produciendo *judder* y gastando batería en segundo plano. Dejando que el frame mande,
 * cuando no hay frames la simulación se congela sola.
 *
 * ## Traducción de efectos
 * El motor emite eventos semánticos ([QuantumMergeEffect]); este ViewModel los colecta y hace dos
 * cosas por cada uno: (1) dispara el feedback concreto de plataforma y (2) lo reenvía por el canal
 * MVI para que la UI (Fase 3) pueda animar. Así el motor no depende de la plataforma.
 */
class QuantumMergeViewModel(
    private val progress: ProgressRepository,
    private val audio: AudioAndHapticManager,
    difficulty: QuantumDifficulty = QuantumDifficulty.FACIL,
) : MviViewModel<QuantumMergeIntent, QuantumMergeUiState, QuantumMergeEffect>(
    QuantumMergeUiState(),
) {

    private var engine: QuantumMergeEngine = createEngine(difficulty)
    private var bindings: Job = bind(engine)

    // No arrancamos en `init`: el juego queda en IDLE y muestra la antesala (intro). La partida
    // empieza al pulsar "Comenzar" (intent [QuantumMergeIntent.Start]).

    override fun onIntent(intent: QuantumMergeIntent) {
        when (intent) {
            is QuantumMergeIntent.MoveDropper -> engine.moveDropper(intent.worldX)
            QuantumMergeIntent.DropSphere -> engine.dropSphere()
            is QuantumMergeIntent.Tick -> engine.onFrame(intent.frameNanos)
            QuantumMergeIntent.Pause -> engine.pause()
            QuantumMergeIntent.Resume -> engine.resume()
            is QuantumMergeIntent.SelectDifficulty -> onSelectDifficulty(intent.difficulty)
            QuantumMergeIntent.Start,
            QuantumMergeIntent.RestartGame -> {
                setState { copy(gameOver = null) }
                engine.start()
            }
        }
    }

    /**
     * Cambia de nivel **reconstruyendo el motor**.
     *
     * `BaseGameEngine` recibe su `difficulty` en el constructor y la expone como `val` final —es lo
     * que garantiza que el `GameResult` no pueda mentir sobre en qué nivel se jugó—, así que no hay
     * forma legítima de mutarla. Reemplazar el motor entero es más honesto que abrir esa puerta, y
     * es barato: solo ocurre en la antesala, con el tablero vacío.
     *
     * Las suscripciones viejas se cancelan antes de crear las nuevas; si no, el motor descartado
     * seguiría empujando su estado al `UiState` y la pantalla parpadearía entre dos partidas.
     */
    private fun onSelectDifficulty(difficulty: QuantumDifficulty) {
        if (difficulty == currentState.game.difficulty) return
        // Fuera de la antesala el nivel es inmutable (ver [QuantumMergeIntent.SelectDifficulty]).
        if (currentState.status != GameStatus.IDLE) return

        bindings.cancel()
        engine = createEngine(difficulty)
        bindings = bind(engine)
    }

    private fun createEngine(difficulty: QuantumDifficulty): QuantumMergeEngine =
        QuantumMergeEngine(
            scope = viewModelScope,
            audio = audio,
            // Convención del resto de juegos con nivel elegible: `difficultyLevel` es 1-based.
            difficulty = difficulty.ordinal + 1,
        )

    /**
     * Enchufa los flujos de [engine] al estado MVI y devuelve el `Job` que los mantiene vivos, para
     * poder soltarlos de golpe cuando el motor se reemplace.
     */
    private fun bind(engine: QuantumMergeEngine): Job = viewModelScope.launch {
        launch { engine.state.collect { s -> setState { copy(game = s) } } }
        launch { engine.status.collect { st -> setState { copy(status = st) } } }
        launch { engine.outcome.collect { result -> result?.let(::onFinished) } }
        launch { engine.effects.collect(::onGameEffect) }
    }

    /**
     * Traduce un efecto de la simulación a feedback de plataforma y lo reenvía a la UI.
     *
     * El mapeo de sonidos está elegido por **volumen relativo**, no solo por semántica: la fusión
     * es el momento de recompensa y se lleva el `SUCCESS`; el rebote usa [SoundEffect.MERGE_POP],
     * que es el asset más discreto del catálogo (el mismo clic de [SoundEffect.TAP] al 22 % de
     * volumen), porque suena muchas veces por segundo y a volumen normal el juego sería insufrible.
     */
    private fun onGameEffect(effect: QuantumMergeEffect) {
        when (effect) {
            is QuantumMergeEffect.PlaySound -> audio.playSound(
                when (effect.cue) {
                    QuantumMergeEffect.PlaySound.Cue.DROP -> SoundEffect.TAP
                    QuantumMergeEffect.PlaySound.Cue.BOUNCE -> SoundEffect.MERGE_POP
                    QuantumMergeEffect.PlaySound.Cue.MERGE -> SoundEffect.SUCCESS
                    QuantumMergeEffect.PlaySound.Cue.GAME_OVER -> SoundEffect.ERROR
                },
            )
            is QuantumMergeEffect.Vibrate -> audio.hapticFeedback(
                when (effect.cue) {
                    QuantumMergeEffect.Vibrate.Cue.MERGE_SMALL -> HapticFeedback.LIGHT
                    QuantumMergeEffect.Vibrate.Cue.MERGE_BIG -> HapticFeedback.HEAVY
                    QuantumMergeEffect.Vibrate.Cue.GAME_OVER -> HapticFeedback.ERROR
                },
            )
        }
        // Reenvío one-shot para que la pantalla (Fase 3) pueda reaccionar (sacudida, destello…).
        sendEffect(effect)
    }

    /**
     * Guarda el resultado (local-first) y abre el overlay de fin.
     *
     * Ojo con el sonido: la derrota ya emitió `ERROR` desde el motor, así que aquí **no** se repite
     * un pitido de fallo; el `LEVEL_UP` de cierre es el mismo remate que usan el resto de juegos al
     * mostrar la tarjeta de resultados, y llega después, no encima.
     */
    private fun onFinished(result: GameResult) {
        viewModelScope.launch {
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            audio.hapticFeedback(HapticFeedback.SUCCESS)
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}
