package com.kortexgames.app.game.hypercube

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.ads.AdManager
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.LeveledGamePhase
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * # HyperCubeViewModel — puente MVI del Neon Hyper-Cube
 *
 * Une el [HyperCubeEngine] (mezcla, giros y victoria: portable y testeable) con el contrato MVI de
 * la pantalla, siguiendo el molde de los demás juegos LEVELED (`NeonCircuitViewModel`): se arranca
 * en el selector de niveles y, al elegir uno, se juega y se persiste el resultado local-first.
 *
 * ## Reparto de responsabilidades
 *  - **Motor**: todo lo que es "el cubo" (permutaciones, mezcla, detección de resuelto, puntaje).
 *  - **ViewModel**: la **cámara** (presentación pura: orbitarla no cambia el juego, así que no
 *    ensucia el estado de dominio) y la **traducción** de las señales semánticas del motor a las
 *    llamadas concretas de audio/háptica, en una única tabla auditable ([onEngineEffect]).
 */
class HyperCubeViewModel(
    private val progress: ProgressRepository,
    private val playerProgress: PlayerProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val adManager: AdManager,
) : MviViewModel<HyperCubeIntent, HyperCubeUiState, HyperCubeEffect>(HyperCubeUiState()) {

    private val engine = HyperCubeEngine(viewModelScope, audio)

    /**
     * Velocidad angular de la órbita libre, en radianes por segundo. Cero = cámara quieta.
     *
     * Vive como campo del ViewModel y no en el [HyperCubeUiState] a propósito: la velocidad no se
     * dibuja, solo produce el ángulo (que sí es estado). Meterla en el estado obligaría a emitir
     * una copia nueva por cada frame de frenado sin que la UI tuviera nada que hacer con ella.
     */
    private var cameraVelocityYaw = 0f
    private var cameraVelocityPitch = 0f

    /** Timestamp del frame anterior, para medir el `dt` real del frenado. */
    private var lastInertiaFrameNanos: Long? = null

    /**
     * Tiempo de la partida en curso (ms, sin contar pausas), para el cronómetro del HUD.
     *
     * Se expone como **función** en vez de como campo del estado a propósito: a 60 fps, meterlo en
     * el `UiState` emitiría un estado nuevo por frame y recompondría la pantalla entera —incluida
     * la escena 3D— solo para mover un dígito. Así, el único que se refresca es el reloj.
     */
    fun elapsedMs(): Long = engine.elapsedMs()

    init {
        engine.state.onEach { s -> setState { copy(game = s) } }.launchIn(viewModelScope)
        engine.status.onEach { st -> setState { copy(status = st) } }.launchIn(viewModelScope)
        engine.outcome.onEach { result -> result?.let(::onFinished) }.launchIn(viewModelScope)
        engine.effects.onEach(::onEngineEffect).launchIn(viewModelScope)
        // Nivel máximo alcanzado (récord), reactivo y local-first. No se arranca el motor: se
        // empieza en el selector y el jugador elige nivel.
        playerProgress.observe(GameIds.HYPER_CUBE)
            .onEach { p -> setState { copy(maxUnlocked = p?.bestMetric ?: 0) } }
            .launchIn(viewModelScope)
        // Partida a medias guardada al salir: la antesala la ofrece como "Continuar". Se OBSERVA
        // (en vez de leerla una vez) para que la oferta desaparezca sola en cuanto se consume o se
        // descarta, que es cuando se borra la fila.
        savedGameState.observe(GameIds.HYPER_CUBE)
            .onEach { json -> setState { copy(saved = json?.let(::decodeSaved)?.toSummary()) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: HyperCubeIntent) {
        when (intent) {
            is HyperCubeIntent.RotateCamera -> rotateCamera(intent.deltaYawRad, intent.deltaPitchRad)
            is HyperCubeIntent.FlingCamera -> startCameraInertia(
                intent.yawRadPerSec,
                intent.pitchRadPerSec,
            )
            HyperCubeIntent.StopCameraInertia -> stopCameraInertia()
            is HyperCubeIntent.StartLayerRotation ->
                engine.requestTurn(intent.axis, intent.layer, intent.direction)
            is HyperCubeIntent.Tick -> {
                engine.onFrame(intent.frameNanos)
                advanceCameraInertia(intent.frameNanos)
            }
            is HyperCubeIntent.PlayLevel -> playLevel(intent.level)
            HyperCubeIntent.PlayFreeMode -> playFreeMode()
            HyperCubeIntent.ScrambleCube -> replayCurrent()
            HyperCubeIntent.Pause -> {
                stopCameraInertia() // pausar congela TODO, también el giro libre de la cámara.
                engine.pause()
            }
            HyperCubeIntent.Resume -> engine.resume()
            HyperCubeIntent.PlayAgain -> replayCurrent()
            HyperCubeIntent.NextLevel -> {
                // Breakpoint de avance de nivel (solo juegos LEVELED): cobra un intersticial
                // pendiente sin cortar la partida. No-op si no hay ninguno.
                adManager.onAdBreakpoint()
                // Superar el último nivel de la rampa desemboca en el modo libre: es la
                // continuación natural (ver el KDoc de scrambleDepthFor), no un callejón sin salida.
                val next = currentState.currentLevel + 1
                if (next > MAX_LEVEL) playFreeMode() else playLevel(next)
            }
            HyperCubeIntent.ChooseLevel -> setState {
                copy(phase = LeveledGamePhase.LEVEL_SELECT, gameOver = null)
            }
            HyperCubeIntent.ResumeSaved -> resumeSaved()
            HyperCubeIntent.RequestUndo -> requestUndo()
            HyperCubeIntent.ConfirmUndo -> {
                setState { copy(awaitingUndoAd = false) }
                engine.resume()
                engine.undoLastTurn()
            }
            HyperCubeIntent.CancelUndo -> {
                setState { copy(awaitingUndoAd = false) }
                engine.resume()
            }
        }
    }

    /**
     * "Deshacer": el primero de la partida es gratis, los siguientes cuestan un anuncio.
     *
     * Cuando toca pagar se **pausa el motor** mientras dura el anuncio. No es un capricho: el
     * cronómetro alimenta el récord de mejor tiempo por nivel, y cobrarle al jugador los 20-30 s
     * que puede durar un vídeo convertiría una ayuda en un castigo desproporcionado (y arbitrario,
     * porque depende de qué anuncio toque). La pantalla se encarga de que esa pausa técnica no
     * abra el menú de pausa.
     */
    private fun requestUndo() {
        val game = currentState.game
        if (!game.canUndo || currentState.status != GameStatus.RUNNING) return
        if (currentState.awaitingUndoAd) return

        if (game.undoCostsAd) {
            engine.pause()
            setState { copy(awaitingUndoAd = true) }
        } else {
            engine.undoLastTurn()
        }
    }

    /**
     * Retoma la partida guardada. El guardado se **consume** (se borra) al reanudar: a partir de
     * ahí la partida vuelve a estar viva y el próximo guardado será el de esta sesión.
     *
     * Si el guardado resulta ilegible (formato viejo, datos corruptos) se borra igualmente y no se
     * hace nada más: es preferible perder una partida a medias que dejar al jugador con un botón
     * "Continuar" que no continúa nada.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.HYPER_CUBE)?.let(::decodeSaved)
            savedGameState.clear(GameIds.HYPER_CUBE)
            if (saved == null || !engine.restore(saved)) return@launch
            setState {
                copy(
                    phase = LeveledGamePhase.PLAYING,
                    currentLevel = saved.level,
                    gameOver = null,
                    saved = null,
                )
            }
        }
    }

    /**
     * Punto único de salida "en juego" (atrás del sistema vía `GameExitGuard`, o "SALIR" del menú
     * de pausa): guarda la partida antes de navegar atrás si hay algo que guardar.
     *
     * El motor decide qué es "algo que guardar" ([HyperCubeEngine.snapshot] devuelve `null`
     * durante la mezcla o con el cubo ya resuelto): en esos estados no hay progreso que perder.
     */
    fun requestExit(onExit: () -> Unit) {
        val snapshot = engine.snapshot()
        if (snapshot == null || currentState.phase != LeveledGamePhase.PLAYING) {
            onExit()
            return
        }
        viewModelScope.launch {
            savedGameState.save(GameIds.HYPER_CUBE, Json.encodeToString(snapshot))
            onExit()
        }
    }

    /**
     * Decodifica un guardado. Tolerante a fallos: un JSON de una versión anterior del estado se
     * trata como "no hay partida" en vez de romper la antesala.
     */
    private fun decodeSaved(json: String): HyperCubeSavedState? =
        runCatching { Json.decodeFromString<HyperCubeSavedState>(json) }.getOrNull()

    /** Resumen para la antesala (ver [SavedGameSummary]). */
    private fun HyperCubeSavedState.toSummary() = SavedGameSummary(level, isFreeMode, moves)

    /**
     * Acumula el arrastre de la cámara.
     *
     * Dos cuidados que solo tienen sentido aquí, en la capa de presentación:
     *  - el **pitch se limita** a ±[HyperCubeGeometry.MAX_PITCH_RAD] para no cruzar los polos
     *    (ver su KDoc: dejaría el cubo boca abajo y el mapeo de gestos invertido);
     *  - el **yaw se envuelve** en `[-2π, 2π]`. Visualmente `yaw` y `yaw + 2π` son idénticos, pero
     *    un jugador que gira sin parar acumularía miles de radianes y `sin/cos` empezarían a
     *    perder precisión con un `Float` grande. Envolver es gratis y elimina el problema.
     */
    private fun rotateCamera(deltaYawRad: Float, deltaPitchRad: Float) {
        val limit = HyperCubeGeometry.MAX_PITCH_RAD
        val rawPitch = currentState.cameraPitchRad + deltaPitchRad
        val clampedPitch = rawPitch.coerceIn(-limit, limit)

        // Si la inclinación ha topado, se mata su inercia: seguir empujando contra el tope daría
        // la sensación de que el gesto "se queda pegado" hasta que la velocidad decae sola.
        if (clampedPitch != rawPitch) cameraVelocityPitch = 0f

        setState {
            copy(
                cameraYawRad = (cameraYawRad + deltaYawRad) % TWO_PI,
                cameraPitchRad = clampedPitch,
            )
        }
    }

    /**
     * Arranca la inercia al soltar el dedo, con una fracción de la velocidad que llevaba el
     * arrastre ([INERTIA_STRENGTH]).
     *
     * Se limita además a [MAX_INERTIA_SPEED] porque un golpe seco de dedo puede alcanzar
     * velocidades de varias vueltas por segundo: divertido un instante, inservible para jugar (se
     * pierde de vista qué cara se estaba mirando). Por debajo de [MIN_INERTIA_SPEED] no se arranca
     * nada: un arrastre que termina parado debe quedarse quieto, no derivar.
     */
    private fun startCameraInertia(yawRadPerSec: Float, pitchRadPerSec: Float) {
        val yaw = (yawRadPerSec * INERTIA_STRENGTH)
            .coerceIn(-MAX_INERTIA_SPEED, MAX_INERTIA_SPEED)
        val pitch = (pitchRadPerSec * INERTIA_STRENGTH)
            .coerceIn(-MAX_INERTIA_SPEED, MAX_INERTIA_SPEED)
        if (abs(yaw) < MIN_INERTIA_SPEED && abs(pitch) < MIN_INERTIA_SPEED) return
        cameraVelocityYaw = yaw
        cameraVelocityPitch = pitch
    }

    /** Detiene la inercia en seco (el dedo vuelve a tocar la pantalla, o se pausa la partida). */
    private fun stopCameraInertia() {
        cameraVelocityYaw = 0f
        cameraVelocityPitch = 0f
    }

    /**
     * Hace avanzar y frenar la órbita libre de la cámara, un frame cada vez.
     *
     * ## Frenado exponencial, no lineal ni "por frame"
     * La velocidad se multiplica por `e^(−k·dt)`, donde `dt` es el tiempo real transcurrido. Dos
     * decisiones ahí:
     *
     *  - **Exponencial** porque así el cubo pierde mucha velocidad al principio y se va posando
     *    despacio al final, que es como se detiene un objeto con rozamiento — un frenado lineal
     *    se nota "de motor", termina de golpe.
     *  - **En función de `dt`** (y no un `v *= 0.94` por frame) porque si no, la misma inercia
     *    duraría el doble en una pantalla de 120 Hz que en una de 60: el gesto se sentiría
     *    distinto según el móvil.
     *
     * Por debajo de [MIN_INERTIA_SPEED] se corta a cero para no dejar la cámara derivando
     * eternamente con velocidades imperceptibles (y para que el estado deje de emitir, y con él
     * las recomposiciones).
     */
    private fun advanceCameraInertia(frameNanos: Long) {
        val previous = lastInertiaFrameNanos
        lastInertiaFrameNanos = frameNanos
        if (cameraVelocityYaw == 0f && cameraVelocityPitch == 0f) return
        if (previous == null) return

        val dtSeconds = (frameNanos - previous) / NANOS_PER_SECOND
        if (dtSeconds <= 0f || dtSeconds > MAX_FRAME_SECONDS) return

        rotateCamera(cameraVelocityYaw * dtSeconds, cameraVelocityPitch * dtSeconds)

        val decay = exp(-INERTIA_DECAY_PER_SECOND * dtSeconds)
        cameraVelocityYaw *= decay
        cameraVelocityPitch *= decay
        if (abs(cameraVelocityYaw) < MIN_INERTIA_SPEED &&
            abs(cameraVelocityPitch) < MIN_INERTIA_SPEED
        ) {
            stopCameraInertia()
        }
    }

    /**
     * Tabla única señal del motor → feedback sensorial. Mantenerla junta (y no repartida por el
     * motor) hace trivial ajustar la "textura" táctil y sonora del juego.
     *
     * Nota sobre [HyperCubeEffect.PlaySound.Cue.SLICE]: el arranque del giro pide un roce suave
     * que el catálogo [SoundEffect] todavía no tiene; hasta que exista el asset se resuelve solo
     * con háptica —el "clack" al encajar ya da el cierre sonoro—. Cuando se añada, se cablea aquí
     * sin tocar motor ni UI.
     *
     * Cada señal se **reenvía** además por el canal MVI para que la pantalla (Fase 3) pueda
     * animar el destello de la capa o la celebración final.
     */
    private fun onEngineEffect(effect: HyperCubeEffect) {
        when (effect) {
            is HyperCubeEffect.PlaySound -> when (effect.cue) {
                HyperCubeEffect.PlaySound.Cue.SLICE -> Unit // sin asset todavía (ver KDoc)
                HyperCubeEffect.PlaySound.Cue.CLACK -> audio.playSound(SoundEffect.TAP)
                HyperCubeEffect.PlaySound.Cue.SOLVED -> audio.playSound(SoundEffect.LEVEL_UP)
            }
            is HyperCubeEffect.Vibrate -> audio.hapticFeedback(
                when (effect.cue) {
                    // El tick del arrastre es deliberadamente el más flojo: acompaña al gesto sin
                    // competir con el golpe del giro, que sí debe notarse como un encaje mecánico.
                    HyperCubeEffect.Vibrate.Cue.TICK -> HapticFeedback.LIGHT
                    HyperCubeEffect.Vibrate.Cue.CLACK -> HapticFeedback.MEDIUM
                    HyperCubeEffect.Vibrate.Cue.SOLVED -> HapticFeedback.SUCCESS
                },
            )
        }
        sendEffect(effect)
    }

    /** Empieza (o reempieza) un nivel: limpia el overlay final y manda mezclar al motor. */
    private fun playLevel(level: Int) {
        val clamped = level.coerceIn(1, MAX_LEVEL)
        discardSavedGame()
        setState { copy(phase = LeveledGamePhase.PLAYING, currentLevel = clamped, gameOver = null) }
        engine.startAtLevel(clamped)
    }

    /** Empieza el modo libre: cubo entero mezclado, sin nivel (ver [scrambleDepthFor]). */
    private fun playFreeMode() {
        discardSavedGame()
        setState { copy(phase = LeveledGamePhase.PLAYING, gameOver = null) }
        engine.startFreeMode()
    }

    /**
     * Descarta la partida guardada al empezar una nueva.
     *
     * Llegar aquí siempre es una decisión explícita del jugador (elegir nivel, "Jugar de nuevo",
     * modo libre), y dejar el guardado vivo volvería a ofrecerle como "Continuar" una partida que
     * acaba de abandonar. Para retomarla está [resumeSaved].
     */
    private fun discardSavedGame() {
        viewModelScope.launch { savedGameState.clear(GameIds.HYPER_CUBE) }
    }

    /** Repite lo que se estaba jugando —nivel o modo libre— con una mezcla nueva. */
    private fun replayCurrent() {
        if (currentState.game.isFreeMode) playFreeMode() else playLevel(currentState.currentLevel)
    }

    /**
     * Persiste el resultado (local-first) y abre el overlay final.
     *
     * A diferencia de otros juegos, aquí **no** se dispara sonido de victoria: el motor ya emitió
     * [HyperCubeEffect.PlaySound.Cue.SOLVED] en el mismo giro que resolvió el cubo, y repetirlo
     * sonaría a eco.
     */
    private fun onFinished(result: GameResult) {
        val freeMode = currentState.game.isFreeMode
        val corrected = result.copy(
            // El cronómetro de BaseGameEngine solo cuenta desde el último `start()`, así que en una
            // partida reanudada le falta el tiempo jugado antes de salir. Se corrige aquí, antes de
            // persistir: si no, salir y volver a entrar sería la forma más fácil de "batir" el
            // récord de mejor tiempo del nivel.
            completionTimeMs = engine.elapsedMs(),
            // El **nivel es el universo del ranking** de este juego (ver `GameRankingScopes`): cada
            // uno mezcla con un giro más, así que solo tiene sentido comparar tiempos entre
            // partidas del mismo nivel. Se rellena aquí porque `BaseGameEngine.difficulty` es fijo
            // desde su construcción y el nivel se elige partida a partida. El modo libre ocupa su
            // propio hueco para no colarse jamás en la tabla de un nivel.
            difficultyLevel = if (freeMode) FREE_MODE_DIFFICULTY else currentState.currentLevel,
        )
        viewModelScope.launch {
            val outcome = progress.saveResult(corrected)
            val info = outcome.toGameOverInfo(corrected)
            setState {
                copy(
                    // En modo libre no se enseña comparativa: cada partida baraja el cubo a fondo y
                    // sin nivel, así que no hay un universo con el que medirse —ni por puntos ni por
                    // tiempo—. Enseñar un puesto ahí sería inventarse una competición que no existe.
                    gameOver = if (freeMode) info.copy(ranking = null, percentile = null) else info,
                )
            }
        }
    }

    private companion object {
        /** Vuelta completa en radianes; módulo del yaw acumulado (ver [rotateCamera]). */
        const val TWO_PI = (2.0 * PI).toFloat()

        /**
         * Constante de frenado (1/s): la velocidad cae a `1/e` cada `1/3.2 ≈ 0.31 s`, de modo que
         * un impulso fuerte se apaga en algo menos de un segundo. Suficiente para que el gesto se
         * sienta con peso, sin que el jugador tenga que esperar a que el cubo pare para jugar.
         */
        const val INERTIA_DECAY_PER_SECOND = 3.2f

        /**
         * Fracción de la velocidad del dedo que hereda la cámara al soltar: la inercia se quedó en
         * un 15 % de la original (petición de producto).
         *
         * Se recorta aquí, en la velocidad inicial, y no acelerando el frenado
         * ([INERTIA_DECAY_PER_SECOND]): así el cubo recorre un 85 % menos —que es lo que se pedía—
         * conservando la MISMA curva de desaceleración. Subir la constante de frenado habría dado
         * el mismo recorrido pero deteniéndolo en seco, que es justo la sensación que la inercia
         * venía a quitar.
         */
        const val INERTIA_STRENGTH = 0.15f

        /** Tope de la órbita libre (~1 vuelta/s): más allá se pierde de vista qué cara se mira. */
        const val MAX_INERTIA_SPEED = 6f

        /**
         * Umbral por debajo del cual la inercia ni arranca ni continúa (rad/s ≈ 9°/s).
         *
         * Corta la cola muerta del decaimiento exponencial —que matemáticamente nunca llega a
         * cero— antes de que se convierta en una deriva que se ve pero no se entiende; y de paso
         * hace que soltar el dedo sin movimiento deje el cubo exactamente donde estaba.
         */
        const val MIN_INERTIA_SPEED = 0.15f

        /**
         * Delta máximo entre frames que se acepta para integrar la inercia. Un salto mayor (app en
         * segundo plano, GC largo) daría un tirón de varios radianes de golpe.
         */
        const val MAX_FRAME_SECONDS = 0.1f

        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
