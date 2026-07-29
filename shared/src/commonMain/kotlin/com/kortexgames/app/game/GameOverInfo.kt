package com.kortexgames.app.game

import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.PercentileResult

/**
 * Información de fin de partida para la pantalla de resultados.
 *
 * @property result el resultado guardado (siempre disponible, local-first).
 * @property percentile percentil vs. todos los jugadores; null en invitado/offline
 *           (el resultado igual quedó guardado y se sincronizará luego).
 * @property isNewRecord true si la partida batió el récord previo del jugador; la UI
 *           lo celebra (badge "¡Nuevo récord!" + fuegos artificiales + sonido arcade).
 */
data class GameOverInfo(
    val result: GameResult,
    val percentile: PercentileResult?,
    val isNewRecord: Boolean = false,
)
