package com.example.kortexgames.game.screws

import com.example.kortexgames.core.audio.HapticFeedback
import com.example.kortexgames.core.audio.SoundEffect
import com.example.kortexgames.core.mvi.UiEffect
import com.example.kortexgames.core.mvi.UiIntent
import com.example.kortexgames.core.mvi.UiState
import com.example.kortexgames.game.GameOverInfo
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.game.LeveledGamePhase

/**
 * # Contrato MVI de "Tornillos Neón"
 *
 * Sigue el molde de los juegos LEVELED (Water Sort / Energy Flow): un estado de
 * tablero puro ([ScrewGameState]) producido por el motor, envuelto por el estado
 * de pantalla ([ScrewGameUiState]) que añade fase, ciclo de vida y game-over.
 */

/**
 * Estado puro del tablero en un instante. Es lo que el motor emite y el Canvas
 * dibuja; **no contiene nada de UI** (colores, px, animaciones) — solo geometría
 * y relaciones. Inmutable: cada jugada produce un estado nuevo.
 *
 * @property boardHeight alto del tablero en unidades (ancho fijo [BOARD_WIDTH]).
 * @property holes agujeros del tablero (fijos durante el nivel).
 * @property screws tornillos con su agujero actual.
 * @property plates placas vivas (ancladas, colgando o cayendo). Las caídas ya
 *           confirmadas por la UI se eliminan de la lista.
 * @property selectedScrewId tornillo "desatornillado" a la espera de destino,
 *           o null. Es selección visual: el tornillo sigue sujetando sus placas
 *           hasta que se confirma el destino (recolocación atómica) para que un
 *           simple toque exploratorio no derribe nada irreversiblemente.
 * @property blockedHoleIds agujeros vacíos actualmente inaccesibles porque una
 *           placa los tapa sin agujero propio alineado. Lo precalcula el motor
 *           en cada cambio (O(agujeros × placas), tableros pequeños) para que la
 *           UI pinte el bloqueo sin repetir geometría en cada frame.
 * @property moves recolocaciones realizadas (métrica de puntuación).
 * @property platesCleared placas ya caídas (progreso del nivel).
 * @property totalPlates placas totales del nivel (para la barra de progreso).
 */
data class ScrewGameState(
    val boardHeight: Float = BOARD_WIDTH,
    val holes: List<BoardHole> = emptyList(),
    val screws: List<Screw> = emptyList(),
    val plates: List<Plate> = emptyList(),
    val selectedScrewId: Int? = null,
    val blockedHoleIds: Set<Int> = emptySet(),
    val moves: Int = 0,
    val platesCleared: Int = 0,
    val totalPlates: Int = 0,
) {

    /** Tornillo clavado en [holeId], o null si el agujero está libre. */
    fun screwAt(holeId: Int): Screw? = screws.firstOrNull { it.holeId == holeId }

    /** ¿[holeId] admite un tornillo? (vacío y no tapado por ninguna placa). */
    fun isHoleAvailable(holeId: Int): Boolean =
        screwAt(holeId) == null && holeId !in blockedHoleIds
}

/**
 * Estado de la pantalla completa: antesala/selector de nivel, tablero en juego
 * y resultado, igual que [com.example.kortexgames.game.watersort.WaterSortUiState].
 *
 * @property maxUnlocked nivel máximo superado (récord); define lo desbloqueado.
 * @property currentLevel nivel en juego (para "Siguiente nivel").
 */
data class ScrewGameUiState(
    val phase: LeveledGamePhase = LeveledGamePhase.LEVEL_SELECT,
    val maxUnlocked: Int = 0,
    val currentLevel: Int = 1,
    val game: ScrewGameState = ScrewGameState(),
    val status: GameStatus = GameStatus.IDLE,
    val gameOver: GameOverInfo? = null,
) : UiState

/** Intents: único punto de entrada de la UI (patrón MVI, §4 CLAUDE.md). */
sealed interface ScrewGameIntent : UiIntent {

    /**
     * El jugador tocó el tornillo [screwId]: lo selecciona ("desatornilla"
     * visualmente). Tocar el ya seleccionado lo deselecciona.
     */
    data class SelectScrew(val screwId: Int) : ScrewGameIntent

    /**
     * El jugador tocó el agujero [holeId] teniendo un tornillo seleccionado:
     * intenta recolocarlo ahí. El motor valida (vacío y no bloqueado) y, si
     * procede, aplica la jugada y reevalúa la física de todas las placas.
     */
    data class PlaceScrew(val holeId: Int) : ScrewGameIntent

    /** Toque en zona muerta del tablero: cancela la selección actual. */
    data object CancelSelection : ScrewGameIntent

    /**
     * La UI terminó de animar la caída de la placa [plateId]: el dominio la
     * elimina del tablero. Separar "empieza a caer" (dominio) de "ya salió de
     * pantalla" (UI) evita que el motor necesite temporizadores propios.
     */
    data class PlateFallFinished(val plateId: Int) : ScrewGameIntent

    data object Restart : ScrewGameIntent
    data object Pause : ScrewGameIntent
    data object Resume : ScrewGameIntent

    /** Elige un nivel desbloqueado en el selector y empieza a jugarlo. */
    data class PlayLevel(val level: Int) : ScrewGameIntent

    /** Desde el game-over: rejugar el mismo nivel. */
    data object PlayAgain : ScrewGameIntent

    /** Desde el game-over: avanzar al siguiente nivel. */
    data object NextLevel : ScrewGameIntent

    /** Volver al selector de niveles (desde el game-over). */
    data object ChooseLevel : ScrewGameIntent
}

/**
 * Efectos one-shot (Channel, nunca en el State): feedback sensorial y eventos
 * de navegación/celebración que no deben re-emitirse en recomposición.
 */
sealed interface ScrewGameEffect : UiEffect {

    /** Reproduce un SFX semántico (destornillar = TAP, caída de placa = SUCCESS...). */
    data class PlaySound(val sound: SoundEffect) : ScrewGameEffect

    /** Vibración háptica (toque = LIGHT, caída = MEDIUM, error = ERROR). */
    data class Vibrate(val feedback: HapticFeedback) : ScrewGameEffect

    /** Última placa caída: dispara la celebración de nivel completado. */
    data object ShowLevelComplete : ScrewGameEffect
}
