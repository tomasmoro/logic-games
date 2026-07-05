package com.example.kortexgames.game

import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PercentileResult

/**
 * Información de fin de partida para la pantalla de resultados.
 *
 * @property result el resultado guardado (siempre disponible, local-first).
 * @property percentile percentil vs. todos los jugadores; null en invitado/offline
 *           (el resultado igual quedó guardado y se sincronizará luego).
 */
data class GameOverInfo(
    val result: GameResult,
    val percentile: PercentileResult?,
)
