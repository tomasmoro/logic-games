package com.kortexgames.app.game.neonpulse

import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.UiEffect
import com.kortexgames.app.core.mvi.UiIntent
import com.kortexgames.app.core.mvi.UiState
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.game.GameOverInfo
import com.kortexgames.app.game.GameStatus

/**
 * # Neon Pulse — contrato MVI
 *
 * Define las tres piezas del ciclo unidireccional (ver [com.kortexgames.app.core.mvi.MviViewModel]):
 * el [NeonPulseUiState] renderizable, los [NeonPulseIntent] que la UI emite y los
 * [NeonPulseEffect] one-shot (sonido/háptica/animaciones) que el ViewModel dispara.
 *
 * > Nota de firma: la base es `MviViewModel<Intent, State, Effect>` (ese es el
 * > orden real de los parámetros de tipo en este proyecto).
 */

/**
 * Estado inmutable de la pantalla de Neon Pulse (fuente única de verdad de la UI).
 *
 * La partida es **infinita por hordas**: no hay reloj de partida, solo vidas. Por
 * eso el HUD muestra la horda en curso y su progreso en lugar de una cuenta atrás.
 *
 * @property score puntuación acumulada de la partida.
 * @property lives vidas restantes ([NeonPulseConfig.INITIAL_LIVES] al empezar,
 *   hasta [NeonPulseConfig.MAX_LIVES] recogiendo corazones). A `0` la partida acaba.
 * @property activeNodes nodos actualmente visibles en el lienzo. Es la lista que el
 *   `Canvas` dibuja y sobre la que resuelve el hit-testing del toque. Se reemplaza
 *   entera en cada `tick` (estado inmutable), nunca se muta in situ.
 * @property wave número de horda en curso (1-based); es la métrica de progresión
 *   del juego y lo que se guarda como récord al terminar.
 * @property waveNodesTotal nodos que trae la horda en curso.
 * @property waveNodesResolved nodos de la horda ya resueltos (tocados o expirados).
 *   Junto con [waveNodesTotal] alimenta la barra de progreso de la horda.
 * @property waveBannerMs milisegundos que le quedan al cartel "HORDA N" que precede
 *   a cada oleada. Mientras sea `> 0` el motor no genera nodos: es el respiro entre
 *   hordas. `0` = no hay cartel visible.
 * @property status fase de la partida (IDLE mientras se muestra la antesala/intro,
 *   RUNNING en juego, PAUSED, FINISHED). Reutiliza el [GameStatus] común a todos
 *   los juegos para que la navegación y los overlays se comporten igual.
 * @property awaitingRevive true mientras se ofrece **revivir viendo un anuncio**
 *   tras agotar las vidas (una sola vez por partida). El [status] sigue siendo
 *   [GameStatus.RUNNING] —la partida aún no ha terminado, solo está en pausa de
 *   decisión— y es la UI la que muestra [com.kortexgames.app.ui.components.ReviveAdOverlay]
 *   mientras esto sea `true`, igual que en el resto de juegos con segunda
 *   oportunidad (ver `Neon2048ViewModel`/`BubbleMathEngine`).
 * @property gameOver resumen del resultado (puntaje + percentil) cuando la partida
 *   termina; `null` mientras se juega. Igual patrón que el resto de juegos.
 * @property rankingPreview comparativa mundial del jugador, para pintar en la
 *   antesala el mismo panel que el diálogo de fin de partida ANTES de jugar (ver
 *   [com.kortexgames.app.domain.repository.ProgressRepository.previewRanking]).
 *   Tabla única (el juego no separa por dificultad): `null` mientras se resuelve
 *   ([rankingPreviewLoading]) o si no hay comparativa que mostrar (invitado, sin
 *   red, o sin ninguna marca todavía).
 * @property rankingPreviewLoading `true` mientras se pide [rankingPreview] tras
 *   entrar en la antesala. Arranca en `true` (no en `false`) para no enseñar el
 *   aviso de "sin comparativa" un instante antes de que llegue.
 */
data class NeonPulseUiState(
    val score: Int = 0,
    val lives: Int = NeonPulseConfig.INITIAL_LIVES,
    val activeNodes: List<Node> = emptyList(),
    val wave: Int = 1,
    val waveNodesTotal: Int = 0,
    val waveNodesResolved: Int = 0,
    val waveBannerMs: Long = 0L,
    val status: GameStatus = GameStatus.IDLE,
    val awaitingRevive: Boolean = false,
    val gameOver: GameOverInfo? = null,
    val rankingPreview: GameRanking? = null,
    val rankingPreviewLoading: Boolean = true,
) : UiState

/**
 * Intenciones de la UI: interacciones táctiles del jugador y pulsos del motor.
 *
 * El hit-testing (¿el toque cae dentro del radio de algún nodo?) lo resuelve el
 * `Canvas` en FASE 3 con la geometría que solo la UI conoce (tamaño real del
 * lienzo). Por eso la UI envía [TapNode] con el `id` ya resuelto, o [TapMiss] si
 * el toque fue al vacío; el ViewModel no recibe coordenadas crudas.
 */
sealed interface NeonPulseIntent : UiIntent {

    /** Arranca la partida desde la antesala (IDLE → RUNNING). */
    data object Start : NeonPulseIntent

    /** Repite partida desde la pantalla de resultado. */
    data object PlayAgain : NeonPulseIntent

    /** Pausa / reanuda (congela el game loop y los anillos). */
    data object Pause : NeonPulseIntent
    data object Resume : NeonPulseIntent

    /**
     * El anuncio recompensado concedió el trato: repone una vida y la partida
     * continúa la horda en curso. Solo tiene efecto mientras
     * [NeonPulseUiState.awaitingRevive] sea `true`; ver KDoc de esa propiedad.
     */
    data object Revive : NeonPulseIntent

    /** El jugador rechazó la segunda oportunidad (o el anuncio falló/se cerró):
     *  la partida termina de verdad. Mismo guard que [Revive]. */
    data object DeclineRevive : NeonPulseIntent

    /**
     * El jugador tocó **dentro** del nodo [id]. El reducer decidirá si fue acierto
     * (nodo normal → suma), error (nodo trampa → penaliza) o rescate (corazón →
     * suma una vida) según su [NodeType].
     *
     * @property id identificador del [Node] impactado (resuelto por el `Canvas`).
     */
    data class TapNode(val id: Long) : NeonPulseIntent

    /** El jugador tocó el fondo vacío (ningún nodo bajo el dedo). Se modela como
     *  intent propio para poder dar feedback de "fallo" sin afectar puntuación. */
    data object TapMiss : NeonPulseIntent

    /**
     * Pulso del bucle de juego. Avanza la simulación [deltaMillis] milisegundos:
     * descuenta vida a cada nodo, los desplaza si la horda los tiene en movimiento,
     * retira los expirados (perdiendo vida si eran normales), agenda nuevos spawns
     * y encadena la siguiente horda cuando la actual se vacía.
     *
     * Se modela como intent (y no como método interno) para mantener el ciclo MVI
     * puro y unidireccional: el motor de tiempo es la única fuente que emite
     * [Tick], igual que el jugador es la única fuente de los taps. Detalle de la
     * generación del delta y su concurrencia: ver KDoc del ViewModel (FASE 2).
     *
     * @property deltaMillis tiempo transcurrido desde el `tick` anterior.
     */
    data class Tick(val deltaMillis: Long) : NeonPulseIntent
}

/**
 * Efectos one-shot (feedback inmediato). NO forman parte del estado: se emiten por
 * `Channel` para no repetirse en recomposición ni al rotar (CLAUDE.md §MVI).
 *
 * El feedback inmediato (sonoro + háptico + visual) al acertar/fallar es un
 * requisito de producto para la sensación "viva" de la app (CLAUDE.md §9.4).
 */
sealed interface NeonPulseEffect : UiEffect {

    /**
     * Reproduce un efecto de sonido. Se envuelve [SoundEffect] para que la capa de
     * UI solo tenga que reenviarlo al [com.kortexgames.app.core.audio.AudioAndHapticManager].
     *
     * Atajos semánticos del juego:
     *  - [Hit]   → acierto sobre un nodo normal ([SoundEffect.SUCCESS]).
     *  - [Error] → tocar una trampa o dejar expirar un nodo ([SoundEffect.ERROR]).
     */
    data class PlaySound(val sound: SoundEffect) : NeonPulseEffect {
        companion object {
            val Hit = PlaySound(SoundEffect.SUCCESS)
            val Error = PlaySound(SoundEffect.ERROR)
        }
    }

    /**
     * Dispara feedback háptico.
     *
     * Atajos semánticos:
     *  - [Tick]  → vibración ligera de confirmación al acertar ([HapticFeedback.LIGHT]).
     *  - [Heavy] → vibración fuerte al perder una vida / trampa ([HapticFeedback.HEAVY]).
     */
    data class Vibrate(val haptic: HapticFeedback) : NeonPulseEffect {
        companion object {
            val Tick = Vibrate(HapticFeedback.LIGHT)
            val Heavy = Vibrate(HapticFeedback.HEAVY)
        }
    }

    /**
     * Solicita a la UI la animación de "explosión"/combo sobre un acierto: chispas
     * y una onda expansiva que se desvanecen. Lleva la posición normalizada del
     * nodo impactado para que el `Canvas` sepa dónde animarla.
     *
     * @property x centro X normalizado `[0f..1f]` del acierto.
     * @property y centro Y normalizado `[0f..1f]` del acierto.
     * @property type tipo del nodo impactado; la UI tiñe la explosión con su color
     *   (coral en un objetivo, verde en un corazón) para que el premio se lea de
     *   inmediato sin texto.
     */
    data class ShowComboAnim(
        val x: Float,
        val y: Float,
        val type: NodeType = NodeType.NORMAL,
    ) : NeonPulseEffect

    /**
     * Se ha superado una horda y arranca la siguiente. La UI lo usa para el sonido
     * de progresión y el destello del cartel; el número de horda ya viaja en el
     * estado ([NeonPulseUiState.wave]), así que este efecto no lo duplica.
     */
    data object WaveCleared : NeonPulseEffect
}
