package com.example.kortexgames.game.daily

import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameInfo
import kotlin.random.Random

/** Número de juegos que componen la **misión diaria** (los del entrenamiento). */
const val DAILY_MISSION_SIZE = 3

/**
 * Un juego de la misión diaria junto con su estado de hoy, listo para pintar en la
 * Home (celda con check ✔ o pendiente). Es un modelo de presentación derivado: no
 * se persiste, se recalcula a partir de la fecha y el historial.
 *
 * @property game metadatos del juego (título, categoría → icono/color, ruta).
 * @property isDone true si el jugador ya ha jugado este juego **hoy**.
 */
data class DailyMissionGame(
    val game: GameInfo,
    val isDone: Boolean,
)

/**
 * Elige los juegos de la misión de un día concreto de forma **determinista**: la
 * misma [epochDay] siempre produce la misma selección (mismos juegos durante todo
 * el día), y al día siguiente cambia. Se deriva de la fecha —no se persiste—, así
 * que la misión se "renueva" sola a medianoche sin ningún reset explícito.
 *
 * Usar el día como semilla (`Random(epochDay)`) es clave: garantiza estabilidad
 * intradía y variación entre días sin guardar nada.
 *
 * Solo entran juegos **jugables y con id** (los placeholders del roadmap no tienen
 * pantalla a la que llevar al usuario). Si hay menos candidatos que [count], se
 * devuelven todos sin barajar (caso de arranque del catálogo).
 *
 * @param epochDay día como número de días desde la época (ver `LocalDate.toEpochDays`).
 * @param games catálogo del que elegir (por defecto, el catálogo completo).
 * @param count cuántos juegos componen la misión.
 * @return la lista de juegos de la misión para ese día.
 */
fun dailyMissionGames(
    epochDay: Long,
    games: List<GameInfo> = GameCatalog.games,
    count: Int = DAILY_MISSION_SIZE,
): List<GameInfo> {
    val playable = games.filter { it.playable && it.id != null }
    if (playable.size <= count) return playable
    return playable.shuffled(Random(epochDay)).take(count)
}
