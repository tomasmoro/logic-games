package com.kortexgames.app.game.daily

import com.kortexgames.app.game.FirstRunGames
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameInfo
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

/**
 * La misión del **día 1**: en vez del sorteo de [dailyMissionGames], son literalmente
 * [FirstRunGames.sequence] —los mismos juegos de la bienvenida—, en el mismo orden.
 *
 * El porqué: sin esto, "hoy" se sortea igual que cualquier otro día y casi con toda
 * seguridad NO coincide con los tres juegos que el jugador acaba de probar en la
 * bienvenida (de 17 juegos jugables, la probabilidad de que el sorteo saque justo
 * esos tres es prácticamente nula). El resultado sería pedirle jugar tres juegos
 * MÁS justo después de haber jugado tres — la peor primera impresión posible. Al
 * reutilizar [FirstRunGames.sequence] como fuente, la bienvenida y la misión del
 * día 1 son **la misma lista**: un juego nuevo que se añada a la bienvenida entra
 * aquí solo, sin tocar esta función.
 *
 * @param games catálogo del que resolver los ids a [GameInfo] (por defecto, el
 *   catálogo completo). Si algún id de [FirstRunGames.sequence] no aparece en
 *   [games] (no debería pasar: es el mismo catálogo), simplemente se omite.
 */
fun firstRunMissionGames(games: List<GameInfo> = GameCatalog.games): List<GameInfo> =
    FirstRunGames.sequence.mapNotNull { id -> games.firstOrNull { it.id == id } }
