package com.kortexgames.app.game

import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.PercentileResult
import com.kortexgames.app.domain.model.SaveOutcome

/**
 * Información de fin de partida para la pantalla de resultados.
 *
 * @property result el resultado guardado (siempre disponible, local-first).
 * @property percentile percentil vs. todas las PARTIDAS históricas; null en
 *           invitado/offline (el resultado igual quedó guardado y se sincronizará luego).
 * @property ranking comparativa vs. los demás JUGADORES (puesto, % superado y los
 *           vecinos del ranking). Es lo que pinta la tarjeta cuando está disponible;
 *           [percentile] queda como red de seguridad si esta RPC falló.
 * @property isNewRecord true si la partida batió el récord previo del jugador; la UI
 *           lo celebra (badge "¡Nuevo récord!" + fuegos artificiales + sonido arcade).
 */
data class GameOverInfo(
    val result: GameResult,
    val percentile: PercentileResult?,
    val ranking: GameRanking?,
    val isNewRecord: Boolean = false,
)

/**
 * Traduce el [SaveOutcome] del repositorio a lo que consume la tarjeta de fin de
 * partida. Existe para que los ~18 ViewModels de juego no repitan el mismo mapeo
 * campo a campo: añadir un dato nuevo a la comparativa se hace aquí y llega a
 * todos los juegos a la vez.
 */
fun SaveOutcome.toGameOverInfo(result: GameResult): GameOverInfo = GameOverInfo(
    result = result,
    percentile = percentile,
    ranking = ranking,
    isNewRecord = isNewRecord,
)
